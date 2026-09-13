package io.github.xgl34222220.bichen;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.content.pm.ServiceInfo;
import org.json.*;
import java.io.IOException;
import java.util.concurrent.*;
/** Full IP tunnel. No Root firewall/Box changes; opt-in linkage only to our own hosts. */
public final class MihomoVpnService extends VpnService {
 public static volatile boolean engaged,running;
 public static volatile String state="未启动",failure="";
 private static volatile MihomoVpnService owner;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Handler ui=new Handler(Looper.getMainLooper());
 private volatile boolean stopping,destroyed;private volatile ParcelFileDescriptor descriptor;
 private SharedPreferences prefs;private ConnectivityManager manager;private ConnectivityManager.NetworkCallback callback;private volatile Network physical;
 private final Runnable startupDeadline=()->{if(owner==this&&engaged&&!running&&!stopping){failure="启动超过 60 秒，正在关闭内核；未报告连接成功";requestStop();}};
 @Override public void onCreate(){super.onCreate();prefs=getSharedPreferences("bichen",0);}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  String action=intent==null?"STOP":intent.getAction();
  if("STOP".equals(action)||"RESTORE".equals(action)){
   if(owner!=null&&owner!=this){stopSelf(startId);return START_NOT_STICKY;}owner=this;engaged=true;state="正在停止并检查恢复";foreground();requestStop();return START_NOT_STICKY;
  }
  if(engaged||destroyed)return START_NOT_STICKY;
  owner=this;engaged=true;stopping=false;failure="";state="正在校验配置";foreground();
  prefs.edit().putBoolean("proxyWanted",true).putString("engineOwner","mihomo").apply();ui.postDelayed(startupDeadline,60000);
  submit(()->{try{startCore();}catch(Exception|LinkageError e){failure=e.getMessage()==null?"内核启动失败":e.getMessage();finishStop();}});return START_NOT_STICKY;
 }
 private void submit(Runnable r){try{worker.execute(r);}catch(RejectedExecutionException ignored){}}
 private void requestStop(){stopping=true;running=false;state="正在停止";prefs.edit().putBoolean("proxyWanted",false).apply();ui.removeCallbacks(startupDeadline);submit(this::finishStop);}
 private void startCore()throws Exception{
  if(DnsVpnService.running||prefs.getBoolean("vpnWanted",false)||prefs.getBoolean("vpnRestoreHosts",false))throw new IOException("请先停止旧应用保护并完成模块恢复");
  ProxyStore store=new ProxyStore(this);String yaml=store.yaml();MihomoNative.call(new JSONObject().put("action","inspect").put("yaml",yaml));
  if(stopping||destroyed){finishStop();return;}RuleStore rules=new RuleStore(this);rules.reload();
  if(prefs.getBoolean("proxyManageHosts",false)){
   JSONObject s=RootBridge.status(this);if(!s.optBoolean("ok"))throw new IOException("模块状态未确认，未切换保护方式");if(s.optBoolean("pendingReboot"))throw new IOException("模块等待重启，未切换保护方式");
   if(s.optBoolean("installed")){rules.syncFromModule();if(s.optBoolean("enabled")&&!s.optBoolean("moduleDisabled")&&!s.optBoolean("moduleRemovalPending")){
    if(!prefs.edit().putBoolean("proxyRestoreHosts",true).commit())throw new IOException("无法保存模块恢复状态");RootBridge.Result paused=RootBridge.run(this,"pause");if(!paused.ok())throw new IOException("模块暂停失败，取消代理启动");
   }}
  }
  if(stopping||destroyed){finishStop();return;}if(VpnService.prepare(this)!=null)throw new IOException("需要在 App 内确认 VPN 授权");state="正在连接 Mihomo";foreground();
  Builder b=new Builder().setSession("辟尘 · 代理与去广告").setMtu(1500).addAddress("172.29.0.1",30).addAddress("fdfe:dcba:9876::1",126).addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("172.29.0.2").setBlocking(false);
  b.addDisallowedApplication(getPackageName());b.setConfigureIntent(PendingIntent.getActivity(this,401,new Intent(this,ProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
  descriptor=b.establish();if(descriptor==null)throw new IOException("系统没有建立 VPN 接口");
  MihomoNative.call(new JSONObject().put("action","start").put("home",store.home().getAbsolutePath()).put("yaml",yaml).put("fd",descriptor.getFd()).put("filter",prefs.getBoolean("proxyFilter",true)).put("domains",new JSONArray(rules.effectiveDomains())));
  if(stopping||destroyed){finishStop();return;}running=true;ui.removeCallbacks(startupDeadline);state=prefs.getBoolean("proxyFilter",true)?"代理与去广告运行中":"代理运行中 · 辟尘过滤关闭";prefs.edit().remove("proxyError").apply();manager=getSystemService(ConnectivityManager.class);
  callback=new ConnectivityManager.NetworkCallback(){
   @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){if(running&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){physical=n;setUnderlyingNetworks(new Network[]{n});}}
   @Override public void onLost(Network n){if(running&&n.equals(physical)){physical=null;setUnderlyingNetworks(new Network[0]);state="等待网络恢复";foreground();}}
   @Override public void onAvailable(Network n){if(running){state=prefs.getBoolean("proxyFilter",true)?"代理与去广告运行中":"代理运行中";foreground();}}
  };if(manager!=null)manager.registerDefaultNetworkCallback(callback);foreground();
 }
 private void foreground(){if(destroyed)return;
  NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("proxy_core","Mihomo 代理",NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,402,new Intent(this,ProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,403,new Intent(this,MihomoVpnService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification n=new Notification.Builder(this,"proxy_core").setSmallIcon(getApplicationInfo().icon).setContentTitle("辟尘 · Mihomo").setContentText(state).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
  if(Build.VERSION.SDK_INT>=34)startForeground(401,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(401,n);
 }
 private void finishStop(){if(owner!=this)return;
  running=false;stopping=true;ui.removeCallbacks(startupDeadline);try{MihomoNative.call("stop");}catch(Exception|LinkageError ignored){}closeDescriptor();
  if(manager!=null&&callback!=null){try{manager.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}callback=null;}
  if(prefs.getBoolean("proxyRestoreHosts",false)){try{
   JSONObject s=RootBridge.status(this);if(!s.optBoolean("ok")||s.optBoolean("pendingReboot"))throw new IOException("原模块恢复状态待确认");
   if(s.optBoolean("installed")&&!s.optBoolean("moduleDisabled")&&!s.optBoolean("moduleRemovalPending")){RootBridge.Result r=RootBridge.run(this,"enable");if(!r.ok())throw new IOException("原模块恢复失败");}
   if(!prefs.edit().putBoolean("proxyRestoreHosts",false).commit())throw new IOException("恢复标记保存失败");
  }catch(Exception e){failure="代理已停止；原模块恢复未完成，请点重试恢复原模块";}}
  prefs.edit().putBoolean("proxyWanted",false).putString("proxyError",failure).apply();engaged=false;owner=null;state=failure.isEmpty()?"已停止":failure;stopForeground(true);stopSelf();
 }
 private void closeDescriptor(){ParcelFileDescriptor p=descriptor;descriptor=null;if(p!=null)try{p.close();}catch(IOException ignored){}}
 @Override public void onRevoke(){requestStop();}
 @Override public void onDestroy(){destroyed=true;stopping=true;ui.removeCallbacksAndMessages(null);if(owner==this)submit(this::finishStop);worker.shutdown();super.onDestroy();}
}
