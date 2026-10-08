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
    private volatile int bootRestoreAttempts;
    private ProxyRestoreScheduler bootRestores;

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
        if(RootAutostart.ACTION_RUNNING.equals(action)&&prefs.getBoolean("proxyRootAutoStart",false)){
            // Validate native boot state on the worker before publishing recovery intent.
            worker.execute(()->{
                if(destroyed||!prefs.getBoolean("proxyRootAutoStart",false))return;
                try{
                    if(new RootProxyManager(getApplicationContext()).adoptBootRuntime())evaluate();
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
        }
        evaluate();
        return START_STICKY;
    }

    private void scheduleBootRestore(long delayMs){
        bootRestores.request(delayMs);
    }

    private long restoreWantedProxyAfterBoot(){
        if(!prefs.getBoolean("proxyRootAutoStart",false)||!prefs.getBoolean("proxyRootWanted",false))return 0L;
        // A persisted running flag belongs to the previous boot. Only a live
        // process observation can confirm restore success; Root may be unready.
        ProxyContinuity.ProcessState bootState=probeCoreState();
        if(bootState==ProxyContinuity.ProcessState.UNKNOWN){
            prefs.edit().putString("proxyRootBootError","等待 Root 运行状态可确认…").apply();
            return Math.min(30000L,1000L+Math.min(++bootRestoreAttempts,29)*1000L);
        }
        if(bootState==ProxyContinuity.ProcessState.ALIVE){
            prefs.edit()
                    .putLong("proxyRootBootRestoreSuccessAt",System.currentTimeMillis())
                    .remove("proxyRootBootError")
                    .apply();
            return 0L;
        }
        if(RootAutostart.restoreInProgress(getApplicationContext())){
            prefs.edit().putString("proxyRootBootError","Root 开机脚本正在等待或恢复…").apply();
            return 5000L;
        }
        final NetworkEpoch.Snapshot<Network> route=networkEvents.snapshot();
        if(route.network==null){
            prefs.edit().putString("proxyRootBootError","等待开机网络就绪…").apply();
            return Math.min(30000L,1000L+Math.min(++bootRestoreAttempts,29)*1000L);
        }
        try{
            JSONObject result=new RootProxyManager(getApplicationContext()).startIfWanted(ProxyRuntimeProfile.load(prefs),
                    ()->!destroyed&&networkEvents.isCurrent(route));
            if(result.optBoolean("cancelled",false))
                return "control-busy".equals(result.optString("reason"))?1000L:
                        "network-changed".equals(result.optString("reason"))?1000L:0L;
            if(result.optBoolean("ok",false)||result.optBoolean("running",false)){
                bootRestoreAttempts=0;
                prefs.edit()
                        .putLong("proxyRootBootRestoreSuccessAt",System.currentTimeMillis())
                        .remove("proxyRootBootError")
                        .apply();
                return 0L;
            }
            throw new IllegalStateException(result.optString("message","开机恢复未完成"));
        }catch(Exception error){
            String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            if(detail.length()>260)detail=detail.substring(0,260)+"…";
            prefs.edit()
                    .putString("proxyRootBootError","Root 代理开机恢复失败："+detail)
                    .putLong("proxyRootBootRestoreAttemptAt",System.currentTimeMillis())
                    .apply();
            return Math.min(30000L,2000L+Math.min(++bootRestoreAttempts,28)*1000L);
        }
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
                if(prefs.getBoolean("proxyRootAutoStart",false))scheduleBootRestore(250L);
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

    private void maintainProxyRuntime(){
        try{
            if(destroyed||!prefs.getBoolean("proxyRootWanted",false)||RootProxyManager.observationTicket()<0L)return;
            ProxyContinuity.ProcessState state=probeCoreState();
            if(state==ProxyContinuity.ProcessState.UNKNOWN){
                prefs.edit().putLong("proxyLastUnknownProcessProbeAt",System.currentTimeMillis()).apply();
                return;
            }
            if(state==ProxyContinuity.ProcessState.ALIVE){
                checkLiveNetworkIntegrity();
                probeEgressIfPending();
                return;
            }
            final NetworkEpoch.Snapshot<Network> route=networkEvents.snapshot();
            if(route.network==null){
                prefs.edit().putString("proxyAutoRecoveryError","等待网络恢复后重新启动代理").apply();
                return;
            }
            long nowElapsed=SystemClock.elapsedRealtime();
            if(lastAutoRecoveryElapsed>=0L&&nowElapsed-lastAutoRecoveryElapsed<30000L)return;
            lastAutoRecoveryElapsed=nowElapsed;
            prefs.edit().putLong("proxyAutoRecoveryAttempt",System.currentTimeMillis()).apply();
            RootProxyManager root=new RootProxyManager(getApplicationContext());
            JSONObject result=root.startIfWanted(ProxyRuntimeProfile.load(prefs),
                    ()->!destroyed&&networkEvents.isCurrent(route));
            if(result.optBoolean("cancelled",false))return;
            if(result.optBoolean("running",false)||result.optBoolean("ok",false)){
                prefs.edit()
                        .putLong("proxyAutoRecoverySuccess",System.currentTimeMillis())
                        .remove("proxyAutoRecoveryError")
                        .apply();
            }else{
                prefs.edit().putString("proxyAutoRecoveryError",result.optString("message","代理自动恢复未完成")).apply();
            }
        }catch(Exception error){
            String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            if(detail.length()>300)detail=detail.substring(0,300)+"…";
            prefs.edit().putString("proxyAutoRecoveryError",detail).apply();
        }
    }

    private void checkLiveNetworkIntegrity(){
        if(destroyed||!prefs.getBoolean("proxyRootWanted",false))return;
        final NetworkEpoch.Snapshot<Network> route=networkEvents.snapshot();
        final long ticket=RootProxyManager.observationTicket();
        if(ticket<0L)return;
        try{
            JSONObject health=new RootProxyManager(getApplicationContext()).networkHealth();
            String integrity=health.optString("networkIntegrity","unknown");
            String id=recordHealth(route,integrity,health.optString("networkFault",""),null);
            SharedPreferences.Editor editor=prefs.edit().putString("proxyNetworkHealthTraceId",id)
                    .putString("proxyNetworkIntegrity",integrity)
                    .putString("proxyNetworkFault",health.optString("networkFault",""))
                    .putLong("proxyNetworkCheckedAt",System.currentTimeMillis());
            editor.remove("proxyNetworkHealthReadError");
            if("healthy".equals(integrity))editor.remove("proxyAutoRecoveryError");
            else if("degraded".equals(integrity))editor.putString("proxyAutoRecoveryError","核心存活，网络接管不完整："+health.optString("networkFault"));
            // Unknown/old-script observations are never treated as proof of failure.
            if(!publishNetworkObservation(route,ticket,editor::apply))staleResult(route,id);
        }catch(Exception failure){
            String id=recordHealth(route,"unknown","health-read-failed",failure);
            if(!publishNetworkObservation(route,ticket,()->prefs.edit()
                    .putString("proxyNetworkIntegrity","unknown").putString("proxyNetworkFault","health-read-failed")
                    .putLong("proxyNetworkCheckedAt",System.currentTimeMillis()).putString("proxyNetworkHealthTraceId",id)
                    .putString("proxyNetworkHealthReadError",failure.getClass().getSimpleName()).apply()))staleResult(route,id);
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
