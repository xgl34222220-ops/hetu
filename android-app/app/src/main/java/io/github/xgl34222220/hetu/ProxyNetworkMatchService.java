package io.github.xgl34222220.hetu;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.json.JSONArray;

public final class ProxyNetworkMatchService extends Service {
    static final String ACTION_BOOT_RESTORE="io.github.xgl34222220.hetu.BOOT_RESTORE";
    private static final String CHANNEL="hetu-network-match";
    private SharedPreferences prefs;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cb;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledThreadPoolExecutor metrics=createMetrics();
    private static final Object SERVICE_SESSION_LOCK=new Object();
    private final String networkSessionId=UUID.randomUUID().toString();
    private final NetworkEpoch<Network> networkEvents=new NetworkEpoch<>();
    private final ProxyNetworkState networkState=new ProxyNetworkState();
    private ScheduledFuture<?> networkRefreshTask;
    private NetworkEpoch.Snapshot<Network> pendingRecoveryRoute;
    private String pendingRecoveryReason;
    private final ProxyTaskCoalescer networkRecoveries=new ProxyTaskCoalescer(worker,this::recoverQueuedNetwork);
    private ProxyNetworkJournal journal;
    private final ExecutorService journalWorker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128));
    private String lastHealthTraceSignature="";
    private String lastHealthTraceId="";
    private long lastHealthTraceAt;
    private final java.util.concurrent.atomic.AtomicLong droppedJournalEvents=new java.util.concurrent.atomic.AtomicLong();
    private static final Object JOURNAL_STATUS_LOCK=new Object();
    interface EgressRequest { int code(String target,int port)throws Exception; }
    private EgressRequest egressRequest=this::fetchEgressCode;
    private final ProxyTaskCoalescer evaluations=new ProxyTaskCoalescer(worker,this::evaluateNow);
    private volatile boolean destroyed;
    private long automaticStopGeneration;
    private volatile long lastPolicyProbeAt=-90000L;
    // A wall-clock correction must neither postpone recovery nor bypass its
    // backoff. This observer session owns its monotonic retry clock; persisted
    // wall timestamps remain diagnostic history only.
    private long lastAutoRecoveryElapsed=-1L;
    private volatile long lastAdblockMetricPoll;
    // Boot callbacks and the periodic guardian share one episode. The deadline
    // and start count survive observer recreation; its local throttle does not.
    private static final int MAX_RECOVERY_STARTS=6;
    private static final long RECOVERY_DEADLINE_MS=300000L;
    private static final long RECOVERY_BACKOFF_MS=30000L;
    // Native start now keeps 145s for its own bounded transaction/rollback;
    // retain another 20s for a result revoked after native success. Preparation
    // calls share this reserve within the same persisted deadline;
    // this admission guard is not an outer process-kill timeout.
    private static final long RECOVERY_START_RESERVE_MS=165000L;
    private long nextBootRecoveryElapsed=-1L;
    private int bootRestoreAttempts;
    private ProxyRestoreScheduler bootRestores;
    private boolean bootRuntimeReattachRequested;

    @Override public void onCreate(){
        super.onCreate();
        prefs=getSharedPreferences("hetu",MODE_PRIVATE);
        initializeNetworkSession();
        cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        journal=new ProxyNetworkJournal(getApplicationContext());
        droppedJournalEvents.set(Math.max(0L,prefs.getLong("proxyNetworkJournalDropped",0L)));
        bootRestores=new ProxyRestoreScheduler((task,delayMs)->metrics.schedule(()->{
            try{worker.execute(task);}catch(RejectedExecutionException stopped){}
        },delayMs,TimeUnit.MILLISECONDS),this::restoreWantedProxyAfterBoot);
        ensureChannel();
        startForeground(92,note("代理网络守护已就绪"));
        register();
        metrics.scheduleWithFixedDelay(this::updateAdblockMetrics,1500L,4000L,TimeUnit.MILLISECONDS);
        metrics.scheduleWithFixedDelay(this::maintainProxyRuntime,7000L,12000L,TimeUnit.MILLISECONDS);
    }

    private static ScheduledThreadPoolExecutor createMetrics(){
        ScheduledThreadPoolExecutor executor=new ScheduledThreadPoolExecutor(2);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private void initializeNetworkSession(){
        synchronized(SERVICE_SESSION_LOCK){
            // Persisted observations describe the previous observer instance.
            // Keep its journal; wait for fresh callback/health/probe evidence.
            prefs.edit().putString("proxyNetworkSessionId",networkSessionId)
                    .putLong("proxyNetworkEpoch",0L).putString("proxyPhysicalNetworkState","checking")
                    .putString("proxyPolicyEgressState","unverified").putLong("proxyPolicyEgressCheckedAt",0L)
                    .putBoolean("proxyRootEgressPending",prefs.getBoolean("proxyRootWanted",false))
                    .putString("proxyNetworkIntegrity","unknown").putString("proxyNetworkFault","service-recreated")
                    .putBoolean("proxyNetworkDataPlaneHealthy",false)
                    .putLong("proxyNetworkCheckedAt",0L)
                    .remove("proxyNetworkHealthTraceId").remove("proxyPolicyEgressTraceId")
                    .remove("proxyRootEgressVerifiedAt").apply();
        }
    }

    private boolean publishServiceObservation(long ticket,Runnable action){
        synchronized(SERVICE_SESSION_LOCK){
            if(destroyed||!networkSessionId.equals(prefs.getString("proxyNetworkSessionId","")))return false;
            return RootProxyManager.publishObservation(ticket,action);
        }
    }

    @Override public int onStartCommand(Intent i,int f,int id){
        String action=i==null?"":i.getAction();
        if(RootAutostart.ACTION_RUNNING.equals(action)&&bootAutoEnabled()){
            // Validate native boot state on the worker before publishing recovery intent.
            worker.execute(()->{
                if(destroyed||!bootAutoEnabled())return;
                try{
                    if(!networkSessionId.equals(prefs.getString("proxyNetworkSessionId",""))
                            ||RootAutostart.restoreState(getApplicationContext())!=RootAutostart.BootRestoreState.IDLE)return;
                    if(new RootProxyManager(getApplicationContext()).adoptBootRuntime()){
                        // Native can finish after this observer's bounded wait.
                        // Its notification starts no new core and must retain
                        // the already exhausted App admission/start budget.
                        synchronized(ProxyNetworkMatchService.this){confirmRecoveryHealth(true,true);}
                        evaluate();
                    }
                    else if(!prefs.getBoolean("networkMatchEnabled",false)&&!prefs.getBoolean("proxyRootWanted",false))stopSelf();
                }catch(Exception error){prefs.edit().putString("proxyRootBootError","开机运行状态同步失败："+error.getClass().getSimpleName()).apply();}
            });
            return START_STICKY;
        }
        if(!prefs.getBoolean("networkMatchEnabled",false)&&!prefs.getBoolean("proxyRootWanted",false)){
            stopSelf();return START_NOT_STICKY;
        }
        if(ACTION_BOOT_RESTORE.equals(action)){
            prefs.edit().putLong("proxyRootBootServiceAt",System.currentTimeMillis()).apply();
            scheduleBootRestore(350L);
        }else if(bootAutoEnabled()&&currentRecoveryIntent())scheduleBootRuntimeReattach();
        evaluate();
        return START_STICKY;
    }

    private void scheduleBootRestore(long delayMs){
        bootRestores.request(delayMs);
    }

    private synchronized void scheduleBootRuntimeReattach(){
        if(bootRuntimeReattachRequested||!currentRecoveryIntent()||!bootAutoEnabled())return;
        bootRuntimeReattachRequested=true;
        // One read-only check on an observer reattach event, never a new timer.
        // Native may have completed after our deadline and lost its notification.
        try{worker.execute(()->{
            synchronized(ProxyNetworkMatchService.this){
                if(!currentRecoveryIntent()||!bootAutoEnabled())return;
                try{
                    if(RootAutostart.restoreState(getApplicationContext())!=RootAutostart.BootRestoreState.IDLE)return;
                    if(new RootProxyManager(getApplicationContext()).adoptBootRuntime())
                        confirmRecoveryHealth(true,true);
                }catch(Exception error){publishRecoveryError(true,"开机运行状态同步失败："+error.getClass().getSimpleName());}
            }
        });}catch(RejectedExecutionException stopped){}
    }

    private synchronized long restoreWantedProxyAfterBoot(){
        if(!currentRecoveryIntent()||!bootAutoEnabled())return 0L;
        // An explicit later manual runtime belongs to its own intent, never to
        // the stopped native boot task or its queued restore callbacks.
        if(explicitManualRecoveryIntent())return 0L;
        prepareRecoveryEpisode();
        if(prefs.getBoolean("proxyRecoveryEpisodeComplete",false)
                &&!prefs.getBoolean("proxyRecoveryCompletedAfterBudget",false))beginRecoveryEpisode(prefs.edit());
        if(!recoveryWindowOpen(true))return 0L;
        long now=SystemClock.elapsedRealtime();
        if(nextBootRecoveryElapsed>now)return Math.min(nextBootRecoveryElapsed-now,remainingRecoveryTime());
        // A persisted running flag belongs to the previous boot. Only a live
        // process observation can confirm restore success; Root may be unready.
        ProxyContinuity.ProcessState bootState=probeCoreState();
        if(bootState==ProxyContinuity.ProcessState.UNKNOWN){
            return deferBootRecovery("等待 Root 运行状态可确认…");
        }
        RootAutostart.BootRestoreState nativeState=RootAutostart.restoreState(getApplicationContext());
        if(!nativeRecoveryAllowsStart(nativeState,true))
            return nativeRecoveryPending(nativeState)
                    ?deferBootRecovery(nativeState==RootAutostart.BootRestoreState.ACTIVE
                    ?"Root 开机脚本正在恢复，App 等待原任务完成…":"Root 开机恢复所有者尚未确认…"):0L;
        if(bootState==ProxyContinuity.ProcessState.ALIVE){
            if(confirmRecoveryHealth(true))return 0L;
            return deferBootRecovery("核心存活，开机网络接管尚未完整验证；已保留当前核心");
        }
        final NetworkEpoch.Snapshot<Network> route=networkEvents.snapshot();
        if(route.network==null){
            return deferBootRecovery("等待开机网络就绪…");
        }
        if(!reserveRecoveryStart(true))return recoveryWindowOpen(true)?deferBootRecovery("等待代理恢复退避间隔…"):0L;
        final long manualGeneration=prefs.getLong("proxyRootManualStartGeneration",0L);
        try{
            JSONObject result=startWithRecoveryBudget(ProxyRuntimeProfile.load(prefs),
                    ()->recoveryStartCurrent(route,manualGeneration));
            if(!currentRecoveryIntent()||manualGeneration!=prefs.getLong("proxyRootManualStartGeneration",0L))return 0L;
            if(result.optBoolean("cancelled",false)){
                return "control-busy".equals(result.optString("reason"))||"network-changed".equals(result.optString("reason"))
                        ?deferBootRecovery("新的控制操作或网络变化已替代此次恢复…"):0L;
            }
            if(result.optBoolean("ok",false)||result.optBoolean("running",false)){
                if(networkEvents.isCurrent(route)&&probeCoreState()==ProxyContinuity.ProcessState.ALIVE&&confirmRecoveryHealth(true))return 0L;
                return deferBootRecovery("启动请求已返回，开机网络接管仍未完整验证");
            }
            throw new IllegalStateException(result.optString("message","开机恢复未完成"));
        }catch(Exception error){
            String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            if(detail.length()>260)detail=detail.substring(0,260)+"…";
            return deferBootRecovery("Root 代理开机恢复失败："+detail);
        }
    }

    private boolean bootAutoEnabled(){
        return prefs.getBoolean("proxyRootAutoStart",false)&&!prefs.getBoolean("proxyRootAutoStartRevoked",false);
    }

    private boolean explicitManualRecoveryIntent(){
        long manual=prefs.getLong("proxyRootManualStartGeneration",0L);
        if(manual<=0L||manual!=prefs.getLong("proxyRootManualStartSucceededGeneration",-1L)
                ||manual<=prefs.getLong("proxyRootStoppedManualGeneration",0L)
                ||!"manual".equals(prefs.getString("proxyRootSessionOwner","")))return false;
        int boot=currentBootCount();
        return boot>=0&&boot==prefs.getInt("proxyRootManualStartSucceededBootCount",-1);
    }

    private int currentBootCount(){
        try{return android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);}
        catch(Exception unavailable){return -1;}
    }

    private boolean currentRecoveryIntent(){
        return !destroyed&&prefs.getBoolean("proxyRootWanted",false)
                &&networkSessionId.equals(prefs.getString("proxyNetworkSessionId",""));
    }

    private void prepareRecoveryEpisode(){
        int bootCount=currentBootCount();
        synchronized(SERVICE_SESSION_LOCK){synchronized(RootProxyManager.RECOVERY_BUDGET_LOCK){
            if(!currentRecoveryIntent())return;
            long now=SystemClock.elapsedRealtime();
            long manual=prefs.getLong("proxyRootManualStartGeneration",0L);
            if(!prefs.contains("proxyRecoveryBootCount")||bootCount!=prefs.getInt("proxyRecoveryBootCount",-1)
                    ||manual!=prefs.getLong("proxyRecoveryManualGeneration",0L)
                    ||now<prefs.getLong("proxyRecoveryStartedElapsed",0L)){
                beginRecoveryEpisode(prefs.edit().putInt("proxyRecoveryBootCount",bootCount)
                        .putLong("proxyRecoveryManualGeneration",manual));
                lastAutoRecoveryElapsed=-1L;
            }
        }
    }
    }

    private void beginRecoveryEpisode(SharedPreferences.Editor edit){
        synchronized(SERVICE_SESSION_LOCK){synchronized(RootProxyManager.RECOVERY_BUDGET_LOCK){
        if(!currentRecoveryIntent())return;
        long now=SystemClock.elapsedRealtime();
        if(!edit.putLong("proxyRecoveryManualGeneration",prefs.getLong("proxyRootManualStartGeneration",0L))
                .putLong("proxyRecoveryStartedElapsed",now).putLong("proxyRecoveryDeadlineElapsed",now+RECOVERY_DEADLINE_MS)
                .putInt("proxyRecoveryStarts",0).putBoolean("proxyRecoveryEpisodeComplete",false)
                .remove("proxyRecoveryTerminalReason").remove("proxyRecoveryCompletedAfterBudget").commit())
            prefs.edit().putString("proxyRecoveryTerminalReason","恢复预算未能保存，已停止自动尝试，请手动检查").apply();
        nextBootRecoveryElapsed=-1L;bootRestoreAttempts=0;
        }
    }
    }

    private long remainingRecoveryTime(){
        return Math.max(0L,prefs.getLong("proxyRecoveryDeadlineElapsed",0L)-SystemClock.elapsedRealtime());
    }

    private boolean recoveryBudgetExhausted(){
        return prefs.getBoolean("proxyRecoveryCompletedAfterBudget",false)
                ||prefs.contains("proxyRecoveryStartedElapsed")&&!prefs.getBoolean("proxyRecoveryEpisodeComplete",false)
                &&(remainingRecoveryTime()==0L||prefs.getInt("proxyRecoveryStarts",0)>=MAX_RECOVERY_STARTS
                ||!prefs.getString("proxyRecoveryTerminalReason","").isEmpty());
    }

    private boolean recoveryWindowOpen(boolean boot){
        synchronized(SERVICE_SESSION_LOCK){synchronized(RootProxyManager.RECOVERY_BUDGET_LOCK){
        if(!currentRecoveryIntent()||prefs.getLong("proxyRecoveryManualGeneration",0L)
                !=prefs.getLong("proxyRootManualStartGeneration",0L))return false;
        if(prefs.getBoolean("proxyRecoveryCompletedAfterBudget",false))return false;
        String terminal=prefs.getString("proxyRecoveryTerminalReason","");
        if(terminal.isEmpty()&&!prefs.getBoolean("proxyRecoveryEpisodeComplete",false)){
            if(remainingRecoveryTime()==0L)terminal="恢复已达到 5 分钟总期限，请检查后手动启动";
            else if(prefs.getInt("proxyRecoveryStarts",0)>=MAX_RECOVERY_STARTS)terminal="自动恢复已达到 6 次上限，请检查后手动启动";
        }
        if(terminal.isEmpty())return true;
        prefs.edit().putString("proxyRecoveryTerminalReason",terminal).apply();
        publishRecoveryError(boot,terminal);
        return false;
        }
    }
    }

    private boolean nativeRecoveryAllowsStart(RootAutostart.BootRestoreState state,boolean boot){
        synchronized(SERVICE_SESSION_LOCK){
        if(!currentRecoveryIntent()||prefs.getLong("proxyRecoveryManualGeneration",0L)
                !=prefs.getLong("proxyRootManualStartGeneration",0L))return false;
        if(state==RootAutostart.BootRestoreState.IDLE)return true;
        if(state==RootAutostart.BootRestoreState.STOPPED||state==RootAutostart.BootRestoreState.TERMINAL){
            if(!boot&&explicitManualRecoveryIntent())return true;
            String reason=state==RootAutostart.BootRestoreState.STOPPED?"本次开机恢复已明确停止，App 不再自动启动"
                    :"Root 开机恢复已结束且未通过验证，App 不追加重试；请检查后手动启动";
            prefs.edit().putString("proxyRecoveryTerminalReason",reason).apply();
            publishRecoveryError(boot,reason);
        }
        return false;
        }
    }

    private boolean nativeRecoveryPending(RootAutostart.BootRestoreState state){
        return state==RootAutostart.BootRestoreState.ACTIVE||state==RootAutostart.BootRestoreState.PENDING
                ||state==RootAutostart.BootRestoreState.UNKNOWN;
    }

    private void publishRecoveryError(boolean boot,String detail){
        final long ticket=RootProxyManager.observationTicket();
        publishServiceObservation(ticket,()->{
            if(!currentRecoveryIntent())return;
            SharedPreferences.Editor edit=prefs.edit().putString("proxyAutoRecoveryError",detail);
            if(boot)edit.putString("proxyRootBootError",detail);
            edit.apply();
        });
    }

    private long deferBootRecovery(String message){
        if(!recoveryWindowOpen(true))return 0L;
        publishRecoveryError(true,message);
        long delay=Math.min(RECOVERY_BACKOFF_MS<<Math.min(bootRestoreAttempts++,2),remainingRecoveryTime());
        nextBootRecoveryElapsed=SystemClock.elapsedRealtime()+delay;
        return delay;
    }

    private boolean reserveRecoveryStart(boolean boot){
        synchronized(SERVICE_SESSION_LOCK){synchronized(RootProxyManager.RECOVERY_BUDGET_LOCK){
        if(!recoveryWindowOpen(boot))return false;
        long now=SystemClock.elapsedRealtime();
        int starts=prefs.getInt("proxyRecoveryStarts",0);
        long delay=RECOVERY_BACKOFF_MS<<Math.min(Math.max(0,starts-1),2);
        if(lastAutoRecoveryElapsed>=0L&&now-lastAutoRecoveryElapsed<delay)return false;
        if(prefs.getBoolean("proxyRecoveryEpisodeComplete",false)){beginRecoveryEpisode(prefs.edit());starts=0;}
        if(!recoveryWindowOpen(boot))return false;
        if(remainingRecoveryTime()<RECOVERY_START_RESERVE_MS){
            String reason="剩余恢复时间不足以保留启动及回滚预算，已停止追加自动尝试；请检查后手动启动";
            prefs.edit().putString("proxyRecoveryTerminalReason",reason).apply();
            publishRecoveryError(boot,reason);
            return false;
        }
        lastAutoRecoveryElapsed=now;
        if(!prefs.edit().putInt("proxyRecoveryStarts",starts+1).putLong("proxyAutoRecoveryAttempt",System.currentTimeMillis())
                .putLong("proxyRootBootRestoreAttemptAt",System.currentTimeMillis()).commit()){
            prefs.edit().putString("proxyRecoveryTerminalReason","恢复预算未能保存，已停止自动尝试，请手动检查").apply();
            publishRecoveryError(boot,"恢复预算未能保存，已停止自动尝试，请手动检查");
            return false;
        }
        return true;
        }
    }
    }

    // Recovery93 additive change: preparation and native start consume this same
    // persisted episode deadline; the original restored method bodies stay archived.
    private JSONObject startWithRecoveryBudget(ProxyRuntimeProfile profile,java.util.function.BooleanSupplier current)throws Exception{
        RootProxyManager.beginAutomaticRecoveryScope(prefs.getLong("proxyRecoveryDeadlineElapsed",0L));
        try{return new RootProxyManager(getApplicationContext()).startIfWanted(profile,current);}
        finally{RootProxyManager.endAutomaticRecoveryScope();}
    }

    private boolean recoveryStartCurrent(NetworkEpoch.Snapshot<Network> route,long manualGeneration){
        return currentRecoveryIntent()&&networkEvents.isCurrent(route)
                &&manualGeneration==prefs.getLong("proxyRootManualStartGeneration",0L)&&remainingRecoveryTime()>0L
                &&!(prefs.getBoolean("proxyRootAutoStart",false)&&prefs.getBoolean("proxyRootAutoStartRevoked",false))
                &&(!bootAutoEnabled()||nativeRecoveryIntentCurrent());
    }

    private boolean nativeRecoveryIntentCurrent(){
        RootAutostart.BootRestoreState state=RootAutostart.restoreState(getApplicationContext());
        return state==RootAutostart.BootRestoreState.IDLE||explicitManualRecoveryIntent()
                &&(state==RootAutostart.BootRestoreState.STOPPED||state==RootAutostart.BootRestoreState.TERMINAL);
    }

    private boolean confirmRecoveryHealth(boolean boot){return confirmRecoveryHealth(boot,false);}

    private boolean confirmRecoveryHealth(boolean boot,boolean retainExhaustedBudget){
        return observeLiveNetworkIntegrity(()->{
            SharedPreferences.Editor complete=prefs.edit().putBoolean("proxyRecoveryEpisodeComplete",true)
                    .putLong("proxyAutoRecoverySuccess",System.currentTimeMillis()).remove("proxyAutoRecoveryError");
            if((retainExhaustedBudget||remainingRecoveryTime()==0L)&&recoveryBudgetExhausted())
                complete.putBoolean("proxyRecoveryCompletedAfterBudget",true);
            else complete.putInt("proxyRecoveryStarts",0).remove("proxyRecoveryTerminalReason");
            complete.apply();
            if(boot)prefs.edit().putLong("proxyRootBootRestoreSuccessAt",System.currentTimeMillis()).remove("proxyRootBootError").apply();
            nextBootRecoveryElapsed=-1L;bootRestoreAttempts=0;lastAutoRecoveryElapsed=-1L;
        });
    }

    @Override public void onDestroy(){
        destroyed=true;
        evaluations.close();
        networkRecoveries.close();
        bootRestores.close();
        synchronized(networkEvents){if(networkRefreshTask!=null)networkRefreshTask.cancel(false);pendingRecoveryRoute=null;networkEvents.reset();}
        if(cb!=null)try{cm.unregisterNetworkCallback(cb);}catch(Exception ignored){}
        worker.shutdownNow();
        metrics.shutdownNow();
        journalWorker.shutdown(); // Drain accepted diagnostic records; preserve history.
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent i){return null;}

    private void register(){
        cb=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){
                networkChanged("available",()->{networkEvents.available(n);networkState.available(n);});
            }
            @Override public void onLost(Network n){
                networkChanged("lost",()->{networkEvents.lost(n);networkState.lost(n);});
            }
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){
                boolean physical=c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
                boolean internet=c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                boolean validated=c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                boolean captive=c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
                String signature=physical+"|"+internet+"|"+validated+"|"+captive+"|"
                        +c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)+"|"
                        +c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                networkChanged("capabilities",()->{
                    networkState.capabilities(n,physical,internet,validated,captive);
                    // Validation can itself need the proxy. Do not make it a
                    // prerequisite; blocked/captive networks cannot auto-recover.
                    networkEvents.capabilities(n,physical&&internet&&!captive,signature);
                });
            }
            @Override public void onLinkPropertiesChanged(Network n,LinkProperties links){
                networkChanged("links",()->networkEvents.links(n,
                        links.getInterfaceName()==null?null:links.toString()));
            }
            @Override public void onBlockedStatusChanged(Network n,boolean blocked){
                networkChanged("blocked",()->{networkEvents.blocked(n,blocked);networkState.blocked(n,blocked);});
            }
        };
        try{cm.registerDefaultNetworkCallback(cb);}
        catch(Exception e){prefs.edit().putString("networkMatchLastEnvironment","监听失败："+e.getClass().getSimpleName()).apply();}
    }

    private void networkChanged(String reason,Runnable update){
        synchronized(networkEvents){
            if(destroyed)return;
            NetworkEpoch.Snapshot<Network> before=networkEvents.snapshot();
            update.run();
            final NetworkEpoch.Snapshot<Network> route=networkEvents.snapshot();
            if(route==before)return;
            lastPolicyProbeAt=-90000L;
            prefs.edit().putLong("proxyNetworkEpoch",route.epoch)
                    .putString("proxyPhysicalNetworkState",networkState.state().name().toLowerCase(Locale.ROOT))
                    .putString("proxyLastNetworkCallback",reason)
                    .putLong("proxyLastNetworkCallbackAt",System.currentTimeMillis())
                    .putString("proxyPolicyEgressState","unverified")
                    .putLong("proxyPolicyEgressCheckedAt",0L)
                    .putBoolean("proxyRootEgressPending",prefs.getBoolean("proxyRootWanted",false)).apply();
            recordEvent(ProxyNetworkJournal.Stage.NETWORK_CHANGE,route,
                    ProxyNetworkJournal.outcome(networkState.state().name()),0,null,null);
            if(networkRefreshTask!=null){networkRefreshTask.cancel(false);networkRefreshTask=null;}
            pendingRecoveryRoute=null;
            if(route.network!=null&&prefs.getBoolean("proxyRootWanted",false)){
                if(prefs.getBoolean("proxyRootAutoStart",false)&&!prefs.getBoolean("proxyRootAutoStartRevoked",false)
                        &&!prefs.contains("proxyRootBootRestoreSuccessAt"))scheduleBootRestore(250L);
                try{networkRefreshTask=metrics.schedule(()->queueNetworkRecovery(route,reason),
                        2500L,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException stopped){}
            }
        }
        evaluate();
    }

    private void queueNetworkRecovery(NetworkEpoch.Snapshot<Network> route,String reason){
        synchronized(networkEvents){
            if(destroyed||route.network==null||!networkEvents.isCurrent(route)||!prefs.getBoolean("proxyRootWanted",false))return;
            pendingRecoveryRoute=route;pendingRecoveryReason=reason;
            // Reuse the existing coalescer: one queued task, plus a current task.
            networkRecoveries.request();
        }
    }

    private void recoverQueuedNetwork(){
        final NetworkEpoch.Snapshot<Network> route;
        final String reason;
        synchronized(networkEvents){
            route=pendingRecoveryRoute;reason=pendingRecoveryReason;pendingRecoveryRoute=null;
        }
        if(route!=null)recoverNetwork(route,reason);
    }

    private void recoverNetwork(NetworkEpoch.Snapshot<Network> route,String reason){
        if(destroyed||route.network==null||!networkEvents.isCurrent(route)||!prefs.getBoolean("proxyRootWanted",false))return;
        long ticket=RootProxyManager.observationTicket();
        if(!publishNetworkObservation(route,ticket,()->prefs.edit()
                .putLong("proxyLastNetworkObservationAt",System.currentTimeMillis())
                .putString("proxyLastNetworkObservationReason",reason+"-changed-connections-preserved").apply()))return;
        // Refresh only Hetu-owned state in place. A new default does not prove
        // old connections failed, so preserve the core and all live connections.
        repairLiveNetworkIntegrity(route);
        if(networkEvents.isCurrent(route))probeEgressIfPending();
    }

    boolean publishNetworkObservation(NetworkEpoch.Snapshot<Network> route,long ticket,Runnable action){
        synchronized(networkEvents){
            if(destroyed||!prefs.getBoolean("proxyRootWanted",false))return false;
            if(!networkEvents.isCurrent(route))return false;
            return publishServiceObservation(ticket,action);
        }
    }

    private String recordEvent(ProxyNetworkJournal.Stage stage,NetworkEpoch.Snapshot<Network> route,
            ProxyNetworkJournal.Outcome outcome,int code,Throwable error,String parent){
        ProxyNetworkJournal.Event event=ProxyNetworkJournal.capture(stage,route.epoch,outcome,code,error,parent);
        enqueueEvent(event);
        return event.id;
    }

    private void enqueueEvent(ProxyNetworkJournal.Event source){
        ProxyNetworkJournal.Event event=ProxyNetworkJournal.inSession(source,networkSessionId);
        try{journalWorker.execute(()->{
            try{journal.append(event,()->{
                synchronized(JOURNAL_STATUS_LOCK){prefs.edit().remove("proxyNetworkJournalError")
                        .putString("proxyNetworkJournalLastWrittenId",event.id).apply();}
            });}
            catch(Exception unavailable){recordJournalDrop(event,unavailable.getClass().getSimpleName());}
        });}catch(RejectedExecutionException fullOrStopped){
            recordJournalDrop(event,journalWorker.isShutdown()?"writer-closed":"queue-full");
        }
    }

    private void recordJournalDrop(ProxyNetworkJournal.Event event,String reason){
        synchronized(JOURNAL_STATUS_LOCK){
            // Old draining writers and callbacks must not regress the shared counter.
            long prior=Math.max(Math.max(0L,prefs.getLong("proxyNetworkJournalDropped",0L)),droppedJournalEvents.get());
            long next=prior==Long.MAX_VALUE?prior:prior+1;droppedJournalEvents.set(next);
            prefs.edit().putLong("proxyNetworkJournalDropped",next).putString("proxyNetworkJournalError",reason)
                    .putString("proxyNetworkJournalLastDroppedId",event.id).apply();
        }
    }

    private synchronized String recordHealth(NetworkEpoch.Snapshot<Network> route,String integrity,String fault,Throwable error){
        String signature=route.epoch+"|"+integrity+"|"+fault+"|"+(error==null?"":error.getClass().getName());
        long now=SystemClock.elapsedRealtime();
        // Record transitions and at most one repeated identical observation per minute.
        if(signature.equals(lastHealthTraceSignature)&&now-lastHealthTraceAt<60000L)return lastHealthTraceId;
        lastHealthTraceSignature=signature;lastHealthTraceAt=now;
        ProxyNetworkJournal.Event event=ProxyNetworkJournal.captureHealth(route.epoch,
                ProxyNetworkJournal.outcome(integrity),fault,error);
        enqueueEvent(event);lastHealthTraceId=event.id;
        return lastHealthTraceId;
    }

    private void staleResult(NetworkEpoch.Snapshot<Network> route,String parent){
        recordEvent(ProxyNetworkJournal.Stage.STALE_RESULT,route,ProxyNetworkJournal.Outcome.DISCARDED,0,null,parent);
    }

    private void repairLiveNetworkIntegrity(NetworkEpoch.Snapshot<Network> route){
        if(destroyed||!networkEvents.isCurrent(route)||!prefs.getBoolean("proxyRootWanted",false))return;
        final long ticket=RootProxyManager.observationTicket();
        if(ticket<0L)return;
        String trace=recordEvent(ProxyNetworkJournal.Stage.RECOVERY_REQUEST,route,ProxyNetworkJournal.Outcome.REQUESTED,0,null,null);
        try{
            RootBridge.Result result=RootBridge.rootShell(getApplicationContext(),
                    "P=$(cat /data/adb/hetu/run/core.pid 2>/dev/null || true); "
                            +"case \"$P\" in ''|*[!0-9]*) exit 3;; esac; "
                            +"/data/adb/hetu/hetu-root.sh repair-network \"$P\" >/dev/null 2>&1",
                    6000L);
            String id=recordEvent(ProxyNetworkJournal.Stage.REPAIR_RESULT,route,
                    result.ok()?ProxyNetworkJournal.Outcome.ACKNOWLEDGED:ProxyNetworkJournal.Outcome.FAILED,result.code,null,trace);
            if(!publishNetworkObservation(route,ticket,()->prefs.edit()
                    .putInt("proxyNetworkRepairExit",result.code).putString("proxyNetworkRepairTraceId",id).apply()))staleResult(route,id);
        }catch(Exception failure){
            String id=recordEvent(ProxyNetworkJournal.Stage.REPAIR_RESULT,route,ProxyNetworkJournal.Outcome.FAILED,-1,failure,trace);
            if(!publishNetworkObservation(route,ticket,()->prefs.edit()
                    .putInt("proxyNetworkRepairExit",-1).putString("proxyNetworkRepairTraceId",id).apply()))staleResult(route,id);
        }
        // A successful shell invocation only acknowledges the request; health is checked independently.
        if(networkEvents.isCurrent(route))checkLiveNetworkIntegrity();
    }

    private void scheduleWorker(Runnable task,long delayMs){
        if(destroyed)return;
        try{
            metrics.schedule(()->{
                if(destroyed)return;
                try{worker.execute(()->{if(!destroyed)task.run();});}
                catch(RejectedExecutionException stopped){}
            },delayMs,TimeUnit.MILLISECONDS);
        }catch(RejectedExecutionException stopped){}
    }

    private void evaluate(){evaluations.request();}

    private void evaluateNow(){
        try{
            boolean automation=prefs.getBoolean("networkMatchEnabled",false);
            Environment environment=readEnvironment();
            String env=environment.label;
            prefs.edit().putString("networkMatchLastEnvironment",env).apply();
            if(automation){
                String action=environment.action;
                String sig=env+"|"+environment.matched+"|"+action;
                String old=prefs.getString("networkMatchLastSig","");
                boolean force=prefs.getBoolean("networkMatchForceEval",false);
                if(!sig.equals(old)||force){
                    automaticStopGeneration++;
                    RootProxyManager root=new RootProxyManager(getApplicationContext());
                    boolean running=coreAlive();
                    String owner=prefs.getString("proxyRootSessionOwner","");
                    if("start".equals(action)){
                        if(!running&&!"manual".equals(owner)){
                            JSONObject result=root.startIfAutomationAllowed(ProxyRuntimeProfile.load(prefs),
                                    ()->!destroyed&&"start".equals(readEnvironment().action));
                            if(!result.optBoolean("cancelled",false)&&!result.optBoolean("running",false)&&!result.optBoolean("ok",false))
                                throw new IllegalStateException(result.optString("message","自动启动未完成"));
                        }
                    }else if("stop".equals(action)&&running&&"automation".equals(owner)){
                        scheduleAutomaticStop(automaticStopGeneration);
                    }
                    // A failed start must remain eligible for the next callback.
                    prefs.edit().putString("networkMatchLastSig",sig).putBoolean("networkMatchForceEval",false).apply();
                }
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(92,note(env+(environment.matched?" · 已匹配":" · 未匹配")));
            }else{
                automaticStopGeneration++;
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(92,note(env+" · 长连接守护"));
            }
        }catch(Exception e){
            prefs.edit().putString("networkMatchLastEnvironment","执行失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())).apply();
        }
    }

    private void scheduleAutomaticStop(long generation){
        // Never sleep on the worker: network changes and manual actions must be
        // able to invalidate this decision throughout the stabilization period.
        scheduleWorker(()->{
            if(generation!=automaticStopGeneration||!prefs.getBoolean("networkMatchEnabled",false)
                    ||!"automation".equals(prefs.getString("proxyRootSessionOwner","")))return;
            Environment current=readEnvironment();
            prefs.edit().putString("networkMatchLastStableEnvironment",current.label).apply();
            if(!"stop".equals(current.action))return;
            try{
                JSONObject result=new RootProxyManager(getApplicationContext()).stopIfAutomationOwned(
                        ()->!destroyed&&generation==automaticStopGeneration&&"stop".equals(readEnvironment().action));
                if(result.optBoolean("cancelled",false))return;
                prefs.edit()
                        .putString("proxyLastAutoStopReason","网络匹配在稳定确认后执行停止："+current.label)
                        .putLong("proxyLastAutoStopAt",System.currentTimeMillis())
                        .apply();
            }catch(Exception error){
                prefs.edit().remove("networkMatchLastSig").putString("networkMatchLastEnvironment",
                        "自动停止失败："+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage())).apply();
            }
        },3500L);
    }

    private static final class Environment{
        final String label;
        final boolean matched;
        final String action;
        Environment(String label,boolean matched,String action){this.label=label;this.matched=matched;this.action=action;}
    }

    private Environment readEnvironment(){
        Network n=cm==null?null:cm.getActiveNetwork();
        NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
        boolean wifi=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean mobile=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        String ssid="",bssid="";
        if(wifi)try{
            WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info=wm==null?null:wm.getConnectionInfo();
            if(info!=null){ssid=clean(info.getSSID());bssid=clean(info.getBSSID());}
        }catch(SecurityException ignored){}
        boolean matched=(wifi&&matches(ssid,set("networkMatchSsids"),false)&&matches(bssid,set("networkMatchBssids"),true))
                ||(mobile&&prefs.getBoolean("networkMatchMobile",false));
        String action=prefs.getString(matched?"networkMatchAction":"networkUnmatchAction",matched?"start":"none");
        String label=wifi?("Wi‑Fi"+(ssid.isEmpty()?"":" · "+ssid)+(bssid.isEmpty()?"":" · "+bssid)):(mobile?"移动数据":"其他/离线");
        return new Environment(label,matched,action);
    }

    private boolean coreAlive(){
        return ProxyContinuity.preserveRunning(probeCoreState(),prefs.getBoolean("proxyRootRuntimeRunning",false));
    }

    private ProxyContinuity.ProcessState probeCoreState(){
        final long ticket=RootProxyManager.observationTicket();
        if(ticket<0L)return ProxyContinuity.ProcessState.UNKNOWN;
        try{
            String command=ProxyContinuity.coreProbeCommand("/data/adb/hetu/run/core.pid","/data/adb/hetu/bin/core");
            RootBridge.Result result=RootBridge.rootShell(getApplicationContext(),command,3500L);
            ProxyContinuity.ProcessState state=ProxyContinuity.processState(result.ok(),result.output);
            if(state!=ProxyContinuity.ProcessState.UNKNOWN){
                if(!publishServiceObservation(ticket,()->prefs.edit()
                        .putBoolean("proxyRootRuntimeRunning",state==ProxyContinuity.ProcessState.ALIVE).apply()))
                    return ProxyContinuity.ProcessState.UNKNOWN;
            }
            return state;
        }catch(Exception ignored){
            return ProxyContinuity.ProcessState.UNKNOWN;
        }
    }

    private synchronized void maintainProxyRuntime(){
        try{
            if(!currentRecoveryIntent()||(prefs.getBoolean("proxyRootAutoStart",false)
                    &&prefs.getBoolean("proxyRootAutoStartRevoked",false)&&!explicitManualRecoveryIntent())
                    ||RootProxyManager.observationTicket()<0L)return;
            prepareRecoveryEpisode();
            if(!recoveryWindowOpen(false))return;
            boolean boot=bootAutoEnabled();
            if(boot&&nextBootRecoveryElapsed>SystemClock.elapsedRealtime())return;
            ProxyContinuity.ProcessState state=probeCoreState();
            if(state==ProxyContinuity.ProcessState.UNKNOWN){
                prefs.edit().putLong("proxyLastUnknownProcessProbeAt",System.currentTimeMillis()).apply();
                return;
            }
            if(boot){
                RootAutostart.BootRestoreState nativeState=RootAutostart.restoreState(getApplicationContext());
                if(!nativeRecoveryAllowsStart(nativeState,false)){
                    if(nativeRecoveryPending(nativeState))
                        deferBootRecovery(nativeState==RootAutostart.BootRestoreState.ACTIVE
                                ?"Root 开机脚本正在恢复，App 等待原任务完成…":"Root 开机恢复所有者尚未确认…");
                    return;
                }
            }
            if(state==ProxyContinuity.ProcessState.ALIVE){
                confirmRecoveryHealth(false);
                probeEgressIfPending();
                return;
            }
            final NetworkEpoch.Snapshot<Network> route=networkEvents.snapshot();
            if(route.network==null){
                prefs.edit().putString("proxyAutoRecoveryError","等待网络恢复后重新启动代理").apply();
                return;
            }
            if(!reserveRecoveryStart(false))return;
            final long manualGeneration=prefs.getLong("proxyRootManualStartGeneration",0L);
            JSONObject result=startWithRecoveryBudget(ProxyRuntimeProfile.load(prefs),
                    ()->recoveryStartCurrent(route,manualGeneration));
            if(!currentRecoveryIntent()||manualGeneration!=prefs.getLong("proxyRootManualStartGeneration",0L))return;
            if(result.optBoolean("cancelled",false))return;
            if(result.optBoolean("running",false)||result.optBoolean("ok",false)){
                if(!networkEvents.isCurrent(route)||probeCoreState()!=ProxyContinuity.ProcessState.ALIVE||!confirmRecoveryHealth(false))
                    publishRecoveryError(false,"启动请求已返回，网络接管仍未完整验证；已保留当前核心");
            }else{
                publishRecoveryError(false,result.optString("message","代理自动恢复未完成"));
            }
        }catch(Exception error){
            String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            if(detail.length()>300)detail=detail.substring(0,300)+"…";
            publishRecoveryError(false,detail);
        }
    }

    private void checkLiveNetworkIntegrity(){observeLiveNetworkIntegrity(null);}

    private boolean observeLiveNetworkIntegrity(Runnable healthyRuntime){
        if(!currentRecoveryIntent())return false;
        final NetworkEpoch.Snapshot<Network> route=networkEvents.snapshot();
        final long ticket=RootProxyManager.observationTicket();
        if(ticket<0L)return false;
        try{
            JSONObject health=new RootProxyManager(getApplicationContext()).networkHealth();
            String integrity=health.optString("networkIntegrity","unknown");
            boolean healthy="healthy".equals(integrity)&&health.optBoolean("dataPlaneHealthy",false);
            String id=recordHealth(route,integrity,health.optString("networkFault",""),null);
            SharedPreferences.Editor editor=prefs.edit().putString("proxyNetworkHealthTraceId",id)
                    .putString("proxyNetworkIntegrity",integrity)
                    .putBoolean("proxyNetworkDataPlaneHealthy",healthy)
                    .putString("proxyNetworkFault",health.optString("networkFault",""))
                    .putLong("proxyNetworkCheckedAt",System.currentTimeMillis());
            editor.remove("proxyNetworkHealthReadError");
            if(healthy)editor.remove("proxyAutoRecoveryError");
            else if("degraded".equals(integrity)||"healthy".equals(integrity))editor.putString("proxyAutoRecoveryError","核心存活，网络接管不完整："+health.optString("networkFault"));
            // Unknown/old-script observations are never treated as proof of failure.
            boolean current=publishNetworkObservation(route,ticket,()->{
                editor.apply();
                if(healthy&&healthyRuntime!=null)healthyRuntime.run();
            });
            if(!current)staleResult(route,id);
            return current&&healthy;
        }catch(Exception failure){
            String id=recordHealth(route,"unknown","health-read-failed",failure);
            if(!publishNetworkObservation(route,ticket,()->prefs.edit()
                    .putString("proxyNetworkIntegrity","unknown").putString("proxyNetworkFault","health-read-failed")
                    .putBoolean("proxyNetworkDataPlaneHealthy",false)
                    .putLong("proxyNetworkCheckedAt",System.currentTimeMillis()).putString("proxyNetworkHealthTraceId",id)
                    .putString("proxyNetworkHealthReadError",failure.getClass().getSimpleName()).apply()))staleResult(route,id);
            return false;
        }
    }

    private void probeEgressIfPending(){
        if(destroyed||!prefs.getBoolean("proxyRootWanted",false))return;
        final long ticket=RootProxyManager.observationTicket();
        if(ticket<0L)return;
        final NetworkEpoch.Snapshot<Network> route;
        synchronized(networkEvents){
            route=networkEvents.snapshot();
            if(route.network==null)return;
            long now=android.os.SystemClock.elapsedRealtime();
            if(now-lastPolicyProbeAt<90000L)return;
            lastPolicyProbeAt=now;
        }
        int port=MihomoStartupConfig.egressProbePort(prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT));
        String error=""; boolean success=false; int responseCode=-1; Throwable probeFailure=null;
        String request=recordEvent(ProxyNetworkJournal.Stage.EGRESS_REQUEST,route,ProxyNetworkJournal.Outcome.REQUESTED,0,null,null);
        // Explicit loopback proxy; no DIRECT fallback, node switch, connection flush
        // or core restart on a slow/blocked website. These requests obey current rules.
        String[] targets={"https://www.gstatic.com/generate_204","https://cp.cloudflare.com/generate_204"};
        for(int index=0;index<targets.length;index++){
            if(destroyed||!networkEvents.isCurrent(route)||!prefs.getBoolean("proxyRootWanted",false)){staleResult(route,request);return;}
            try{
                responseCode=egressRequest.code(targets[index],port);probeFailure=null;
                success=responseCode==204;
                if(!success)error="HTTP "+responseCode;
            }catch(Exception e){probeFailure=e;responseCode=-1;error=e.getClass().getSimpleName();}
            ProxyNetworkJournal.Event observation=ProxyNetworkJournal.captureEgress(route.epoch,
                    index==0?ProxyNetworkJournal.Target.GOOGLE_204:ProxyNetworkJournal.Target.CLOUDFLARE_204,responseCode,probeFailure,request);
            enqueueEvent(observation);
            if(success)break;
        }
        // Discard results from a completed stop or a network handover.
        String id=recordEvent(ProxyNetworkJournal.Stage.EGRESS_RESULT,route,
                success?ProxyNetworkJournal.Outcome.REACHABLE:ProxyNetworkJournal.Outcome.UNVERIFIED,responseCode,probeFailure,request);
        if(destroyed||!prefs.getBoolean("proxyRootWanted",false)||!networkEvents.isCurrent(route)){staleResult(route,id);return;}
        error=error+"（记录 "+id+"）";
        SharedPreferences.Editor edit=prefs.edit().putString("proxyPolicyEgressTraceId",id)
                .putString("proxyPolicyEgressState",success?"reachable":"unverified")
                .putLong("proxyPolicyEgressCheckedAt",System.currentTimeMillis());
        if(success){
            edit.putBoolean("proxyRootEgressPending",false).putLong("proxyRootEgressVerifiedAt",System.currentTimeMillis())
                    .remove("proxyRootEgressWarning").remove("proxyRootEgressProbeLastError");
        }else{
            edit.putBoolean("proxyRootEgressPending",true).putString("proxyRootEgressProbeLastError",error)
                    .putString("proxyRootEgressWarning","按规则出口暂未验证通过；未重启核心、未改节点："+error);
        }
        if(!publishNetworkObservation(route,ticket,edit::apply))staleResult(route,id);
    }

    private int fetchEgressCode(String target,int port)throws Exception{
        java.net.Proxy proxy=new java.net.Proxy(java.net.Proxy.Type.HTTP,new java.net.InetSocketAddress("127.0.0.1",port));
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(target).openConnection(proxy);
        try{
            connection.setConnectTimeout(2500);connection.setReadTimeout(2500);
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestProperty("Connection","close");
            return connection.getResponseCode();
        }finally{connection.disconnect();}
    }

    private void updateAdblockMetrics(){
        try{
            if(!prefs.getBoolean("proxyRootRuntimeRunning",false)
                    ||!prefs.getBoolean("proxyAdblockChain",true)
                    ||!prefs.getBoolean("proxyAdblockCounterArmed",false))return;
            long now=SystemClock.elapsedRealtime();
            long interval=prefs.getBoolean("proxyAdblockUiVisible",false)?3000L:15000L;
            if(now-lastAdblockMetricPoll<interval)return;
            lastAdblockMetricPoll=now;
            final long offset,generation,previousHits;
            final String pending,oldRecent;
            final boolean discarding;
            synchronized(ProxyAdblockSession.LOCK){
                if(!prefs.getBoolean("proxyAdblockCounterArmed",false))return;
                offset=Math.max(0L,prefs.getLong("proxyAdblockLogOffset",0L));
                generation=prefs.getLong("proxyAdblockSessionGeneration",0L);
                pending=prefs.getString("proxyAdblockPendingLogLine","");
                discarding=prefs.getBoolean("proxyAdblockDiscardLogLine",false);
                previousHits=prefs.getLong("proxyAdblockSessionHits",0L);
                oldRecent=prefs.getString("proxyAdblockRecentDomains","");
            }
            String path="/data/adb/hetu/run/core.log";
            String command="set +e; S=$(stat -c %s "+RootBridge.quote(path)+" 2>/dev/null || wc -c < "+RootBridge.quote(path)+" 2>/dev/null || echo 0); "
                    +"case \"$S\" in ''|*[!0-9]*) S=0;; esac; "
                    +"if [ \"$S\" -lt "+offset+" ]; then START=1; else START="+(offset+1)+"; fi; "
                    +"END=$S; LIMIT=$((START+1048576-1)); [ \"$END\" -gt \"$LIMIT\" ] && END=$LIMIT; "
                    +"printf '%s\\n' \"$END\"; "
                    +"if [ \"$END\" -ge \"$START\" ]; then tail -c +\"$START\" "+RootBridge.quote(path)+" 2>/dev/null | head -c $((END-START+1)); fi";
            RootBridge.Result result=RootBridge.rootShell(getApplicationContext(),command,5000L);
            if(!result.ok()||result.output==null||result.output.isEmpty())return;
            int newline=result.output.indexOf('\n');
            String sizeText=(newline<0?result.output:result.output.substring(0,newline)).trim();
            long size;
            try{size=Long.parseLong(sizeText);}catch(Exception invalid){return;}
            String chunk=newline<0?"":result.output.substring(newline+1);
            ProxyLogLines lines=ProxyLogLines.read(pending,discarding,chunk,size<offset);
            long hits=previousHits;
            LinkedHashSet<String> recent=new LinkedHashSet<>();
            if(oldRecent!=null&&!oldRecent.isEmpty())for(String item:oldRecent.split("\\n"))if(!item.trim().isEmpty())recent.add(item.trim());
            java.util.regex.Pattern domainPattern=java.util.regex.Pattern.compile("-->\\s+([^\\s\\\"]+)");
            for(String line:lines.complete.split("\\r?\\n")){
                String lower=line.toLowerCase(Locale.ROOT);
                if(!lower.contains("hetu-adblock")||!lower.contains("reject")||!lower.contains("match"))continue;
                hits++;
                java.util.regex.Matcher matcher=domainPattern.matcher(line);
                if(matcher.find()){
                    String raw=matcher.group(1);
                    String domain=raw;
                    int colon=raw.lastIndexOf(':');
                    if(colon>0&&!raw.endsWith("]"))domain=raw.substring(0,colon);
                    domain=domain.replace("[","").replace("]","").trim();
                    if(!domain.isEmpty()){
                        recent.remove(domain);
                        LinkedHashSet<String> next=new LinkedHashSet<>();
                        next.add(domain);next.addAll(recent);recent=next;
                        while(recent.size()>8){
                            Iterator<String> it=recent.iterator();
                            String last=null;while(it.hasNext())last=it.next();
                            if(last!=null)recent.remove(last);else break;
                        }
                    }
                }
            }
            StringBuilder recentText=new StringBuilder();
            for(String item:recent){if(recentText.length()>0)recentText.append('\n');recentText.append(item);}
            SharedPreferences.Editor edit=prefs.edit()
                    .putLong("proxyAdblockSessionHits",hits)
                    .putLong("proxyAdblockLogOffset",size)
                    .putString("proxyAdblockPendingLogLine",lines.pending)
                    .putBoolean("proxyAdblockDiscardLogLine",lines.discarding)
                    .putString("proxyAdblockRecentDomains",recentText.toString());
            if(hits>previousHits&&!recent.isEmpty()){
                String first=recent.iterator().next();
                edit.putString("proxyAdblockLastDomain",first).putLong("proxyAdblockLastHitAt",System.currentTimeMillis());
            }
            synchronized(ProxyAdblockSession.LOCK){
                if(destroyed||!ProxyAdblockSession.canCommit(generation,prefs.getLong("proxyAdblockSessionGeneration",0L),
                        prefs.getBoolean("proxyAdblockCounterArmed",false),offset,prefs.getLong("proxyAdblockLogOffset",0L)))return;
                edit.apply();
            }
        }catch(Exception ignored){}
    }

    private Set<String> set(String key){Set<String>s=prefs.getStringSet(key,Collections.emptySet());return s==null?Collections.emptySet():new HashSet<>(s);}
    private boolean matches(String value,Set<String>s,boolean ignore){if(s.isEmpty())return true;for(String x:s)if(ignore?x.equalsIgnoreCase(value):x.equals(value))return true;return false;}
    private String clean(String s){if(s==null||"<unknown ssid>".equalsIgnoreCase(s))return"";if(s.length()>1&&s.startsWith("\"")&&s.endsWith("\""))return s.substring(1,s.length()-1);return s;}
    private void ensureChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"河图代理网络守护",NotificationManager.IMPORTANCE_LOW));}
    private Notification note(String text){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);PendingIntent p=PendingIntent.getActivity(this,0,new Intent(this,HetuActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return b.setSmallIcon(R.drawable.ic_hetu).setContentTitle("河图 · 代理守护").setContentText(text).setContentIntent(p).setOngoing(true).build();}
}
