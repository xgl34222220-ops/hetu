package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

public final class ProxyNetworkMatchService extends Service {
    private static final String CHANNEL="bichen-network-match";
    private SharedPreferences prefs;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cb;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Network lastDefaultNetwork;
    private boolean defaultNetworkSeen;

    @Override public void onCreate(){
        super.onCreate();
        prefs=getSharedPreferences("bichen",MODE_PRIVATE);
        cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        ensureChannel();
        startForeground(92,note("代理网络守护已就绪"));
        register();
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
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent i){return null;}

    private void register(){
        cb=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){handleDefaultNetwork(n);evaluate();}
            @Override public void onLost(Network n){evaluate();}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){evaluate();}
        };
        try{cm.registerDefaultNetworkCallback(cb);}
        catch(Exception e){prefs.edit().putString("networkMatchLastEnvironment","监听失败："+e.getClass().getSimpleName()).apply();}
    }

    private void handleDefaultNetwork(Network n){
        worker.execute(()->{
            boolean changed=defaultNetworkSeen&&lastDefaultNetwork!=null&&n!=null&&!lastDefaultNetwork.equals(n);
            lastDefaultNetwork=n;
            defaultNetworkSeen=true;
            if(!changed||!prefs.getBoolean("proxyRootWanted",false))return;
            SystemClock.sleep(220);
            try{
                new MihomoControllerClient(getApplicationContext()).closeAll();
                prefs.edit().putLong("proxyLastNetworkSessionReset",System.currentTimeMillis()).remove("proxyNetworkSessionResetError").apply();
            }catch(Exception e){
                prefs.edit().putString("proxyNetworkSessionResetError",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()).apply();
            }
        });
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
                        boolean running=root.status().optBoolean("running",false);
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
    private void ensureChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"辟尘代理网络守护",NotificationManager.IMPORTANCE_LOW));}
    private Notification note(String text){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);PendingIntent p=PendingIntent.getActivity(this,0,new Intent(this,ReferenceProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return b.setSmallIcon(R.drawable.ic_bichen).setContentTitle("辟尘 · 代理守护").setContentText(text).setContentIntent(p).setOngoing(true).build();}
}
