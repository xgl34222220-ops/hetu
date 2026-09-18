package io.github.xgl34222220.hetu;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class ProxyNetworkMatchService extends Service {
    private static final String CHANNEL="hetu-network-match";
    private SharedPreferences prefs;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cb;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService metrics=Executors.newScheduledThreadPool(2);
    private Network lastDefaultNetwork;
    private boolean defaultNetworkSeen;
    private volatile long lastAdblockMetricPoll;
    private volatile long networkChangeGeneration;

    @Override public void onCreate(){
        super.onCreate();
        prefs=getSharedPreferences("hetu",MODE_PRIVATE);
        cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        ensureChannel();
        startForeground(92,note("代理网络守护已就绪"));
        register();
        metrics.scheduleWithFixedDelay(this::updateAdblockMetrics,1500L,4000L,TimeUnit.MILLISECONDS);
        metrics.scheduleWithFixedDelay(this::maintainProxyRuntime,7000L,12000L,TimeUnit.MILLISECONDS);
    }

    @Override public int onStartCommand(Intent i,int f,int id){
        if(!prefs.getBoolean("networkMatchEnabled",false)&&!prefs.getBoolean("proxyRootWanted",false)){
            stopSelf();return START_NOT_STICKY;
        }
        evaluate();
        return START_STICKY;
    }

    @Override public void onDestroy(){
        if(cb!=null)try{cm.unregisterNetworkCallback(cb);}catch(Exception ignored){}
        worker.shutdownNow();
        metrics.shutdownNow();
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent i){return null;}

    private void register(){
        cb=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){handleDefaultNetwork(n);evaluate();}
            @Override public void onLost(Network n){networkChangeGeneration++;evaluate();}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){evaluate();}
        };
        try{cm.registerDefaultNetworkCallback(cb);}
        catch(Exception e){prefs.edit().putString("networkMatchLastEnvironment","监听失败："+e.getClass().getSimpleName()).apply();}
    }

    private void handleDefaultNetwork(Network n){
        boolean changed=defaultNetworkSeen&&lastDefaultNetwork!=null&&n!=null&&!lastDefaultNetwork.equals(n);
        lastDefaultNetwork=n;
        defaultNetworkSeen=true;
        if(!changed||!prefs.getBoolean("proxyRootWanted",false))return;
        final long generation=++networkChangeGeneration;
        metrics.schedule(()->worker.execute(()->{
            if(generation!=networkChangeGeneration||!prefs.getBoolean("proxyRootWanted",false))return;
            Network active=cm.getActiveNetwork();
            if(active==null||!active.equals(n))return;
            NetworkCapabilities caps=cm.getNetworkCapabilities(active);
            if(caps==null||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return;
            try{
                new MihomoControllerClient(getApplicationContext()).closeAll();
                prefs.edit()
                        .putLong("proxyLastNetworkSessionReset",System.currentTimeMillis())
                        .putString("proxyLastNetworkSessionResetReason","default-network-stable-2500ms")
                        .remove("proxyNetworkSessionResetError")
                        .apply();
            }catch(Exception e){
                prefs.edit().putString("proxyNetworkSessionResetError",
                        e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()).apply();
            }
        }),2500L,TimeUnit.MILLISECONDS);
    }

    private void evaluate(){
        worker.execute(()->{
            try{
                boolean automation=prefs.getBoolean("networkMatchEnabled",false);
                Network n=cm.getActiveNetwork();
                NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);
                boolean wifi=c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean mobile=c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                String ssid="",bssid="";
                if(wifi)try{
                    WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
                    WifiInfo info=wm==null?null:wm.getConnectionInfo();
                    if(info!=null){ssid=clean(info.getSSID());bssid=clean(info.getBSSID());}
                }catch(SecurityException ignored){}
                String env=wifi?("Wi‑Fi"+(ssid.isEmpty()?"":" · "+ssid)+(bssid.isEmpty()?"":" · "+bssid)):(mobile?"移动数据":"其他/离线");
                prefs.edit().putString("networkMatchLastEnvironment",env).apply();
                if(automation){
                    boolean matched=(wifi&&matches(ssid,set("networkMatchSsids"),false)&&matches(bssid,set("networkMatchBssids"),true))||(mobile&&prefs.getBoolean("networkMatchMobile",false));
                    String action=prefs.getString(matched?"networkMatchAction":"networkUnmatchAction",matched?"start":"none");
                    String sig=env+"|"+matched+"|"+action;
                    String old=prefs.getString("networkMatchLastSig","");
                    boolean force=prefs.getBoolean("networkMatchForceEval",false);
                    if(!sig.equals(old)||force){
                        prefs.edit().putString("networkMatchLastSig",sig).putBoolean("networkMatchForceEval",false).apply();
                        RootProxyManager root=new RootProxyManager(getApplicationContext());
                        boolean running=coreAlive();
                        String owner=prefs.getString("proxyRootSessionOwner","");
                        if("start".equals(action)){
                            if(!running&&!"manual".equals(owner)){
                                root.start(ProxyRuntimeProfile.load(prefs));
                                prefs.edit().putString("proxyRootSessionOwner","automation").apply();
                            }
                        }else if("stop".equals(action)){
                            if(running&&"automation".equals(owner)){
                                if(shouldStopAfterStabilize()){
                                    prefs.edit()
                                            .putString("proxyLastAutoStopReason","网络匹配在稳定确认后执行停止："+env)
                                            .putLong("proxyLastAutoStopAt",System.currentTimeMillis())
                                            .apply();
                                    root.stop();
                                    prefs.edit().remove("proxyRootSessionOwner").apply();
                                }else{
                                    prefs.edit().putString("networkMatchLastEnvironment",env+" · 网络切换抖动，已取消自动停止").apply();
                                }
                            }
                        }
                    }
                    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(92,note(env+(matched?" · 已匹配":" · 未匹配")));
                }else{
                    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(92,note(env+" · 长连接守护"));
                }
            }catch(Exception e){
                prefs.edit().putString("networkMatchLastEnvironment","执行失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())).apply();
            }
        });
    }

    private boolean coreAlive(){
        try{
            String command="P=$(cat /data/adb/hetu/run/core.pid 2>/dev/null || echo 0); "
                    +"case \"$P\" in ''|*[!0-9]*) P=0;; esac; "
                    +"if [ \"$P\" -gt 0 ] && kill -0 \"$P\" >/dev/null 2>&1; then "
                    +"EXE=$(readlink \"/proc/$P/exe\" 2>/dev/null || true); "
                    +"case \"$EXE\" in /data/adb/hetu/bin/core) printf 1;; *) printf 0;; esac; "
                    +"else printf 0; fi";
            RootBridge.Result result=RootBridge.rootShell(getApplicationContext(),command,3500L);
            boolean alive=result.ok()&&"1".equals(result.output.trim());
            prefs.edit().putBoolean("proxyRootRuntimeRunning",alive).apply();
            return alive;
        }catch(Exception ignored){
            return prefs.getBoolean("proxyRootRuntimeRunning",false);
        }
    }

    private void maintainProxyRuntime(){
        try{
            if(!prefs.getBoolean("proxyRootWanted",false))return;
            if(coreAlive()){
                prefs.edit().remove("proxyAutoRecoveryError").apply();
                probeEgressIfPending();
                return;
            }
            prefs.edit().putBoolean("proxyRootRuntimeRunning",false).apply();
            Network network=cm==null?null:cm.getActiveNetwork();
            if(network==null){
                prefs.edit().putString("proxyAutoRecoveryError","等待网络恢复后重新启动代理").apply();
                return;
            }
            long now=System.currentTimeMillis();
            long last=prefs.getLong("proxyAutoRecoveryAttempt",0L);
            if(now-last<30000L)return;
            prefs.edit().putLong("proxyAutoRecoveryAttempt",now).apply();
            RootProxyManager root=new RootProxyManager(getApplicationContext());
            JSONObject result=root.start(ProxyRuntimeProfile.load(prefs));
            if(result.optBoolean("running",false)||result.optBoolean("ok",false)){
                prefs.edit()
                        .putBoolean("proxyRootRuntimeRunning",true)
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

    private void probeEgressIfPending(){
        if(!prefs.getBoolean("proxyRootEgressPending",false))return;
        long now=System.currentTimeMillis();
        long last=prefs.getLong("proxyRootEgressProbeAttemptAt",0L);
        if(now-last<30000L)return;
        prefs.edit().putLong("proxyRootEgressProbeAttemptAt",now).apply();
        int attempts=prefs.getInt("proxyRootEgressProbeAttempts",0)+1;
        MihomoControllerClient controller=new MihomoControllerClient(getApplicationContext());
        try{
            try{
                controller.delay("DIRECT","https://connectivitycheck.platform.hicloud.com/generate_204","200-399");
            }catch(Exception first){
                controller.delay("DIRECT","https://cp.cloudflare.com/generate_204","200-399");
            }
            prefs.edit()
                    .putBoolean("proxyRootEgressPending",false)
                    .putInt("proxyRootEgressProbeAttempts",attempts)
                    .putLong("proxyRootEgressVerifiedAt",System.currentTimeMillis())
                    .remove("proxyRootEgressWarning")
                    .remove("proxyRootEgressProbeLastError")
                    .apply();
        }catch(Exception error){
            String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            if(detail.length()>260)detail=detail.substring(0,260)+"…";
            prefs.edit()
                    .putInt("proxyRootEgressProbeAttempts",attempts)
                    .putString("proxyRootEgressProbeLastError",detail)
                    .putString("proxyRootEgressWarning","核心正在运行；后台联网验证暂未通过："+detail)
                    .apply();
        }
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
            long offset=Math.max(0L,prefs.getLong("proxyAdblockLogOffset",0L));
            String path="/data/adb/hetu/run/core.log";
            String command="set +e; S=$(wc -c < "+RootBridge.quote(path)+" 2>/dev/null || echo 0); "
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
            long hits=prefs.getLong("proxyAdblockSessionHits",0L);
            LinkedHashSet<String> recent=new LinkedHashSet<>();
            String oldRecent=prefs.getString("proxyAdblockRecentDomains","");
            if(oldRecent!=null&&!oldRecent.isEmpty())for(String item:oldRecent.split("\\n"))if(!item.trim().isEmpty())recent.add(item.trim());
            java.util.regex.Pattern domainPattern=java.util.regex.Pattern.compile("-->\\s+([^\\s\\\"]+)");
            for(String line:chunk.split("\\r?\\n")){
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
                    .putString("proxyAdblockRecentDomains",recentText.toString());
            if(!recent.isEmpty()){
                String first=recent.iterator().next();
                edit.putString("proxyAdblockLastDomain",first).putLong("proxyAdblockLastHitAt",System.currentTimeMillis());
            }
            edit.apply();
        }catch(Exception ignored){}
    }

    private boolean shouldStopAfterStabilize() {
        SystemClock.sleep(3500L);
        Network n=cm.getActiveNetwork();
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
        prefs.edit().putString("networkMatchLastStableEnvironment",
                wifi?("Wi‑Fi"+(ssid.isEmpty()?"":" · "+ssid)):(mobile?"移动数据":"其他/离线")).apply();
        return "stop".equals(action);
    }

    private Set<String> set(String key){Set<String>s=prefs.getStringSet(key,Collections.emptySet());return s==null?Collections.emptySet():new HashSet<>(s);}
    private boolean matches(String value,Set<String>s,boolean ignore){if(s.isEmpty())return true;for(String x:s)if(ignore?x.equalsIgnoreCase(value):x.equals(value))return true;return false;}
    private String clean(String s){if(s==null||"<unknown ssid>".equalsIgnoreCase(s))return"";if(s.length()>1&&s.startsWith("\"")&&s.endsWith("\""))return s.substring(1,s.length()-1);return s;}
    private void ensureChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"河图代理网络守护",NotificationManager.IMPORTANCE_LOW));}
    private Notification note(String text){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);PendingIntent p=PendingIntent.getActivity(this,0,new Intent(this,ReferenceProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return b.setSmallIcon(R.drawable.ic_hetu).setContentTitle("河图 · 代理守护").setContentText(text).setContentIntent(p).setOngoing(true).build();}
}
