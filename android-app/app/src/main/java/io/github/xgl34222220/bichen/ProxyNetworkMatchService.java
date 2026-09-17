package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

public final class ProxyNetworkMatchService extends Service {
    private static final String CHANNEL="bichen-network-match"; private SharedPreferences prefs; private ConnectivityManager cm; private ConnectivityManager.NetworkCallback cb; private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(){super.onCreate();prefs=getSharedPreferences("bichen",MODE_PRIVATE);cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);ensureChannel();startForeground(92,note("监听网络环境"));register();}
    @Override public int onStartCommand(Intent i,int f,int id){if(!prefs.getBoolean("networkMatchEnabled",false)){stopSelf();return START_NOT_STICKY;}evaluate();return START_STICKY;}
    @Override public void onDestroy(){if(cb!=null)try{cm.unregisterNetworkCallback(cb);}catch(Exception ignored){}worker.shutdownNow();super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent i){return null;}
    private void register(){cb=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network n){evaluate();}@Override public void onLost(Network n){evaluate();}@Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){evaluate();}};try{cm.registerDefaultNetworkCallback(cb);}catch(Exception e){prefs.edit().putString("networkMatchLastEnvironment","监听失败："+e.getClass().getSimpleName()).apply();}}
    private void evaluate(){worker.execute(()->{try{Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);boolean wifi=c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),mobile=c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);String ssid="",bssid="";if(wifi)try{WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);WifiInfo info=wm==null?null:wm.getConnectionInfo();if(info!=null){ssid=clean(info.getSSID());bssid=clean(info.getBSSID());}}catch(SecurityException ignored){}String env=wifi?("Wi‑Fi"+(ssid.isEmpty()?"":" · "+ssid)+(bssid.isEmpty()?"":" · "+bssid)):(mobile?"移动数据":"其他/离线");prefs.edit().putString("networkMatchLastEnvironment",env).apply();boolean matched=(wifi&&matches(ssid,set("networkMatchSsids"),false)&&matches(bssid,set("networkMatchBssids"),true))||(mobile&&prefs.getBoolean("networkMatchMobile",false));String action=prefs.getString(matched?"networkMatchAction":"networkUnmatchAction",matched?"start":"none");String sig=env+"|"+matched+"|"+action;String old=prefs.getString("networkMatchLastSig","");boolean force=prefs.getBoolean("networkMatchForceEval",false);if(sig.equals(old)&&!force)return;prefs.edit().putString("networkMatchLastSig",sig).putBoolean("networkMatchForceEval",false).apply();RootProxyManager root=new RootProxyManager(getApplicationContext());if("start".equals(action)){if(!root.status().optBoolean("running",false))root.start(ProxyRuntimeProfile.load(prefs));}else if("stop".equals(action)){if(root.status().optBoolean("running",false))root.stop();}((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(92,note(env+(matched?" · 已匹配":" · 未匹配")));}catch(Exception e){prefs.edit().putString("networkMatchLastEnvironment","执行失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())).apply();}});}
    private Set<String> set(String key){Set<String>s=prefs.getStringSet(key,Collections.emptySet());return s==null?Collections.emptySet():new HashSet<>(s);}
    private boolean matches(String value,Set<String>s,boolean ignore){if(s.isEmpty())return true;for(String x:s)if(ignore?x.equalsIgnoreCase(value):x.equals(value))return true;return false;}
    private String clean(String s){if(s==null||"<unknown ssid>".equalsIgnoreCase(s))return"";if(s.length()>1&&s.startsWith("\"")&&s.endsWith("\""))return s.substring(1,s.length()-1);return s;}
    private void ensureChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"辟尘网络匹配",NotificationManager.IMPORTANCE_LOW));}
    private Notification note(String text){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);PendingIntent p=PendingIntent.getActivity(this,0,new Intent(this,ReferenceProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return b.setSmallIcon(R.drawable.ic_bichen).setContentTitle("辟尘 · 网络匹配").setContentText(text).setContentIntent(p).setOngoing(true).build();}
}
