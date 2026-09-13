package io.github.xgl34222220.bichen;

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
        SharedPreferences prefs=app.getSharedPreferences("bichen",Context.MODE_PRIVATE);
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

    @Override public boolean onStartJob(JobParameters parameters) {
        SharedPreferences prefs=getSharedPreferences("bichen",MODE_PRIVATE);
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
        String stage="确认模块状态";
        try {
            checkActive(run);
            JSONObject status=RootBridge.status(this);
            checkActive(run);
            if(!status.optBoolean("ok",false) || !status.optBoolean("rootGranted",false) || !status.has("installed"))
                throw new IOException(status.optString("error",status.optString("message","未能确认 Root 或模块状态；未执行更新")));
            boolean installed=status.getBoolean("installed");
            if(status.optBoolean("pendingReboot",false)) throw new IOException("模块正在等待重启，本次未更新规则");
            if(installed) {
                if(status.optBoolean("moduleDisabled",false)||status.optBoolean("moduleRemovalPending",false))
                    throw new IOException("模块已禁用或等待卸载，本次未更新规则");
                JSONArray capabilities=status.optJSONArray("capabilities");
                if(!has(capabilities,"import-batch")||!has(capabilities,"export-config")||!has(capabilities,"export-domains"))
                    throw new IOException("已安装模块不支持整批更新，请在应用中安装内置新版模块");
            }
            checkActive(run);
            stage="下载与整批更新规则";
            RuleStore rules=new RuleStore(this);
            rules.updateRules(installed);
            checkActive(run);
        } catch(Exception error) { failure=error; }
        synchronized(jobLock) {
            // A stopped or superseded invocation must not replace a newer result.
            if(run.stopped || active!=run) return;
            active=null;
            SharedPreferences.Editor edit=getSharedPreferences("bichen",MODE_PRIVATE).edit().putBoolean("dailyUpdateRunning",false);
            if(failure==null) edit.putLong("dailyUpdateLastSuccess",System.currentTimeMillis()).remove("dailyUpdateError").remove("dailyUpdateErrorStage");
            else edit.putString("dailyUpdateErrorStage",stage).putString("dailyUpdateError",stage+"失败："+message(failure)+"；等待下次每日调度，可在规则页手动重试");
            edit.apply();
            // A failed source is left for the next daily opportunity; no tight retry loop.
            jobFinished(run.parameters,false);
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        SharedPreferences prefs=getSharedPreferences("bichen",MODE_PRIVATE);
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
                getSharedPreferences("bichen",MODE_PRIVATE).edit().putBoolean("dailyUpdateRunning",false)
                        .putString("dailyUpdateErrorStage","service-stop").putString("dailyUpdateError","更新服务已停止；请查看当前规则状态").apply();
            }
        }
        worker.shutdownNow();
        super.onDestroy();
    }
    private void checkActive(Run run) throws IOException {
        if(run.stopped || Thread.currentThread().isInterrupted() || !getSharedPreferences("bichen",MODE_PRIVATE).getBoolean("dailyUpdateEnabled",false))
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
