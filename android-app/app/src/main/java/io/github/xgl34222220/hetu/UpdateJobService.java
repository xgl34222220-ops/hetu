package io.github.xgl34222220.hetu;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Explicitly opted-in updates; Android chooses the time once conditions allow. */
public final class UpdateJobService extends JobService {
    public static final int JOB_ID = 0x42494348;
    /**
     * Audit5 G7: the opt-in 订阅到期/流量提醒 also checks in the background, twice a day, through
     * this already-declared job service (no manifest change). It only reads the running core's
     * subscription list; nothing is downloaded or changed.
     */
    public static final int SUBSCRIPTION_ALERT_JOB_ID = 0x42494349;
    private volatile Thread alertThread;
    private final Object jobLock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Run active;

    private static final class Run {
        final JobParameters parameters;
        volatile boolean stopped;
        Future<?> future;
        Run(JobParameters p) { parameters=p; }
    }

    /** Safe on the UI thread; scheduling failure is exposed through preferences. */
    public static void schedule(Context context, boolean enabled) {
        Context app=context.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences("hetu",Context.MODE_PRIVATE);
        try {
            JobScheduler scheduler=(JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if(scheduler==null) throw new IOException("此系统未提供任务调度服务");
            if(!enabled) {
                // Set opt-out before cancel so an onStopJob callback cannot retry it.
                prefs.edit().putBoolean("dailyUpdateEnabled",false).putBoolean("dailyUpdateRunning",false).remove("dailyUpdateError").remove("dailyUpdateErrorStage").apply();
                scheduler.cancel(JOB_ID);
                return;
            }
            JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(app,UpdateJobService.class))
                    .setPeriodic(TimeUnit.HOURS.toMillis(24))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .setBackoffCriteria(TimeUnit.MINUTES.toMillis(30),JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build();
            // The job can become eligible immediately. Store opt-in before scheduling.
            prefs.edit().putBoolean("dailyUpdateEnabled",true).remove("dailyUpdateError").remove("dailyUpdateErrorStage").apply();
            if(scheduler.schedule(job)!=JobScheduler.RESULT_SUCCESS) {
                scheduler.cancel(JOB_ID);
                throw new IOException("系统未接受每日更新任务");
            }
        } catch(Exception error) {
            prefs.edit().putBoolean("dailyUpdateEnabled",false).putBoolean("dailyUpdateRunning",false)
                    .putString("dailyUpdateErrorStage","schedule").putString("dailyUpdateError","定时更新设置失败："+message(error)).apply();
        }
    }

    /** Safe on the UI thread; failures are silent (the foreground check still runs on every read). */
    public static void scheduleSubscriptionAlerts(Context context, boolean enabled) {
        Context app=context.getApplicationContext();
        try {
            JobScheduler scheduler=(JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if(scheduler==null) return;
            if(!enabled) { scheduler.cancel(SUBSCRIPTION_ALERT_JOB_ID); return; }
            if(scheduler.getPendingJob(SUBSCRIPTION_ALERT_JOB_ID)!=null) return;
            scheduler.schedule(new JobInfo.Builder(SUBSCRIPTION_ALERT_JOB_ID,new ComponentName(app,UpdateJobService.class))
                    .setPeriodic(TimeUnit.HOURS.toMillis(12))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        } catch(Exception ignored) { }
    }

    private boolean startSubscriptionAlertJob(final JobParameters parameters) {
        if(!HxSubscriptionAlerts.INSTANCE.enabled(getSharedPreferences("hetu",MODE_PRIVATE))) return false;
        Thread thread=new Thread(new Runnable() { @Override public void run() {
            try { HxSubscriptionAlerts.INSTANCE.checkInBackground(getApplicationContext(),System.currentTimeMillis()); }
            catch(Throwable ignored) { }
            finally {
                if(alertThread==Thread.currentThread()) { alertThread=null; jobFinished(parameters,false); }
            }
        } },"hetu-subscription-alerts");
        alertThread=thread;
        thread.start();
        return true;
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        if(parameters.getJobId()==SUBSCRIPTION_ALERT_JOB_ID) return startSubscriptionAlertJob(parameters);
        SharedPreferences prefs=getSharedPreferences("hetu",MODE_PRIVATE);
        if(!prefs.getBoolean("dailyUpdateEnabled",false)) return false;
        synchronized(jobLock) {
            if(active!=null) {
                active.stopped=true;
                if(active.future!=null) active.future.cancel(true);
            }
            final Run run=new Run(parameters); active=run;
            prefs.edit().putBoolean("dailyUpdateRunning",true).putLong("dailyUpdateLastAttempt",System.currentTimeMillis()).apply();
            try {
                run.future=worker.submit(new Runnable() { @Override public void run() { perform(run); } });
                return true;
            } catch(Exception error) {
                active=null;
                prefs.edit().putBoolean("dailyUpdateRunning",false).putString("dailyUpdateError","定时更新未启动："+message(error)).apply();
                return false;
            }
        }
    }

    private void perform(Run run) {
        Exception failure=null;
        String stage="更新 DNS 过滤规则";
        try {
            checkActive(run);
            RuleStore rules=new RuleStore(this);
            rules.reload();
            checkActive(run);
            rules.updateRules(false);
            checkActive(run);
        } catch(Exception error) { failure=error; }
        synchronized(jobLock) {
            if(run.stopped || active!=run) return;
            active=null;
            SharedPreferences.Editor edit=getSharedPreferences("hetu",MODE_PRIVATE).edit().putBoolean("dailyUpdateRunning",false);
            if(failure==null) edit.putLong("dailyUpdateLastSuccess",System.currentTimeMillis()).remove("dailyUpdateError").remove("dailyUpdateErrorStage");
            else edit.putString("dailyUpdateErrorStage",stage).putString("dailyUpdateError",stage+"失败："+message(failure)+"；旧快照继续生效，等待下次每日调度");
            edit.apply();
            jobFinished(run.parameters,false);
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        if(parameters.getJobId()==SUBSCRIPTION_ALERT_JOB_ID) {
            Thread thread=alertThread; alertThread=null;
            if(thread!=null) thread.interrupt();
            return false;
        }
        SharedPreferences prefs=getSharedPreferences("hetu",MODE_PRIVATE);
        boolean enabled=prefs.getBoolean("dailyUpdateEnabled",false);
        synchronized(jobLock) {
            if(active!=null && active.parameters.getJobId()==parameters.getJobId()) {
                active.stopped=true;
                if(active.future!=null)active.future.cancel(true);
                active=null;
                SharedPreferences.Editor edit=prefs.edit().putBoolean("dailyUpdateRunning",false);
                if(enabled) edit.putString("dailyUpdateErrorStage","system-stop").putString("dailyUpdateError","系统已停止本次定时更新；已经完成的提交保留，等待下次每日调度");
                else edit.remove("dailyUpdateError").remove("dailyUpdateErrorStage");
                edit.apply();
            }
        }
        // A periodic job already gets a future daily opportunity. Returning true
        // here would additionally retry an interrupted job on short backoff.
        return false;
    }

    @Override public void onDestroy() {
        synchronized(jobLock) {
            if(active!=null) {
                active.stopped=true;
                if(active.future!=null)active.future.cancel(true);
                active=null;
                getSharedPreferences("hetu",MODE_PRIVATE).edit().putBoolean("dailyUpdateRunning",false)
                        .putString("dailyUpdateErrorStage","service-stop").putString("dailyUpdateError","更新服务已停止；请查看当前规则状态").apply();
            }
        }
        worker.shutdownNow();
        super.onDestroy();
    }
    private void checkActive(Run run) throws IOException {
        if(run.stopped || Thread.currentThread().isInterrupted() || !getSharedPreferences("hetu",MODE_PRIVATE).getBoolean("dailyUpdateEnabled",false))
            throw new IOException("定时更新已取消");
    }
    private static boolean has(JSONArray array,String value) {
        if(array!=null)for(int i=0;i<array.length();i++)if(value.equals(array.optString(i)))return true;
        return false;
    }
    private static String message(Throwable error) {
        String value=error.getMessage();return value==null||value.trim().isEmpty()?error.getClass().getSimpleName():value;
    }
}
