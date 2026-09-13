package io.github.xgl34222220.bichen;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.content.pm.ServiceInfo;
import org.json.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Full IP tunnel; mutually exclusive with legacy DNS-only engine. */
public final class MihomoVpnService extends VpnService {
 public static volatile boolean engaged,running;
 public static volatile String state="未启动",failure="";
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final AtomicBoolean cleanupDone=new AtomicBoolean();
 private volatile boolean stopping;private volatile ParcelFileDescriptor descriptor;
 private SharedPreferences prefs;private ConnectivityManager manager;private ConnectivityManager.NetworkCallback callback;
 private volatile Network underlying;
 @Override public void onCreate(){super.onCreate();prefs=getSharedPreferences("bichen",0);}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  String action=intent==null?"STOP":intent.getAction();
  if("STOP".equals(action)){stopping=true;prefs.edit().putBoolean("proxyWanted",false).apply();submit(this::finishStop);return START_NOT_STICKY;}
  if(engaged||cleanupDone.get())return START_NOT_STICKY;
  engaged=true;stopping=false;failure="";state="正在校验配置";foreground();
  prefs.edit().putBoolean("proxyWanted",true).putString("engineOwner","mihomo").apply();
  submit(()->{try{startCore();}catch(Exception|LinkageError e){failure=e.getMessage()==null?"内核启动失败":e.getMessage();finishStop();}});return START_NOT_STICKY;
 }
 private void submit(Runnable r){try{worker.execute(r);}catch(RejectedExecutionException ignored){}}
 private String readyState(){return prefs.getBoolean("proxyFilter",true)?"代理与去广告隧道已就绪":"代理隧道已就绪（辟尘过滤关闭）";}
 private void startCore()throws Exception{
  if(DnsVpnService.running||prefs.getBoolean("vpnWanted",false)||prefs.getBoolean("vpnRestoreHosts",false))throw new IOException("请先停止旧的应用保护并完成模块恢复，再开启代理");
  ProxyStore store=new ProxyStore(this);String yaml=store.yaml();MihomoNative.call(new JSONObject().put("action","inspect").put("yaml",yaml));
  if(stopping){finishStop();return;}RuleStore rules=new RuleStore(this);rules.reload();
  if(prefs.getBoolean("proxyManageHosts",false)){
   JSONObject s=RootBridge.status(this);if(!s.optBoolean("ok"))throw new IOException("模块状态未确认，未切换保护方式");if(s.optBoolean("pendingReboot"))throw new IOException("模块等待重启，未切换保护方式");
   if(s.optBoolean("installed")){rules.syncFromModule();if(s.optBoolean("enabled")&&!s.optBoolean("moduleDisabled")&&!s.optBoolean("moduleRemovalPending")){
    if(!prefs.edit().putBoolean("proxyRestoreHosts",true).commit())throw new IOException("无法保存模块恢复状态");RootBridge.Result paused=RootBridge.run(this,"pause");if(!paused.ok())throw new IOException("模块暂停未成功，取消代理启动");
   }}
  }
  if(stopping){finishStop();return;}if(VpnService.prepare(this)!=null)throw new IOException("需要在 App 内确认 VPN 授权");state="正在连接 Mihomo";foreground();
  Builder b=new Builder().setSession("辟尘 · 代理与去广告").setMtu(1500).addAddress("172.29.0.1",30).addAddress("fdfe:dcba:9876::1",126).addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("172.29.0.2").setBlocking(false);
  // Outbounds share this excluded UID. Do not enable allowBypass or silently
  // alter Private DNS. Readiness here means native TUN, not client validation.
  b.addDisallowedApplication(getPackageName());b.setConfigureIntent(PendingIntent.getActivity(this,401,new Intent(this,ProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
  descriptor=b.establish();if(descriptor==null)throw new IOException("系统没有建立 VPN 接口");
  MihomoNative.call(new JSONObject().put("action","start").put("home",store.home().getAbsolutePath()).put("yaml",yaml).put("fd",descriptor.getFd()).put("filter",prefs.getBoolean("proxyFilter",true)).put("domains",new JSONArray(rules.effectiveDomains())));
  if(stopping){finishStop();return;}running=true;state=readyState();prefs.edit().remove("proxyError").apply();manager=getSystemService(ConnectivityManager.class);
  callback=new ConnectivityManager.NetworkCallback(){
   @Override public void onAvailable(Network n){if(running&&!stopping){underlying=n;setUnderlyingNetworks(new Network[]{n});state=readyState();foreground();}}
   @Override public void onLost(Network n){if(running&&!stopping&&n.equals(underlying)){underlying=null;setUnderlyingNetworks(new Network[0]);state="等待网络恢复";foreground();}}
  };if(manager!=null)manager.registerDefaultNetworkCallback(callback);foreground();
 }
 private void foreground(){
  NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("proxy_core","Mihomo 代理",NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,402,new Intent(this,ProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,403,new Intent(this,MihomoVpnService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification n=new Notification.Builder(this,"proxy_core").setSmallIcon(getApplicationInfo().icon).setContentTitle("辟尘 · Mihomo").setContentText(state).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
  if(Build.VERSION.SDK_INT>=34)startForeground(401,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(401,n);
 }
 private void finishStop(){
  // onRevoke/onDestroy/STOP may all arrive for one instance. Old teardown must
  // never stop a later core instance or resume hosts a second time.
  if(!cleanupDone.compareAndSet(false,true))return;
  running=false;stopping=true;try{MihomoNative.call("stop");}catch(Exception|LinkageError ignored){}closeDescriptor();
  if(manager!=null&&callback!=null){try{manager.unregisterNetworkCallback(callback);}catch(Exception ignored){}callback=null;}
  if(prefs.getBoolean("proxyRestoreHosts",false)){try{
   JSONObject s=RootBridge.status(this);if(!s.optBoolean("ok")||s.optBoolean("pendingReboot"))throw new IOException("原模块恢复状态待确认");
   if(s.optBoolean("installed")&&!s.optBoolean("moduleDisabled")&&!s.optBoolean("moduleRemovalPending")){RootBridge.Result r=RootBridge.run(this,"enable");if(!r.ok())throw new IOException("原模块恢复失败");}
   if(!prefs.edit().putBoolean("proxyRestoreHosts",false).commit())throw new IOException("无法保存模块恢复结果");
  }catch(Exception e){failure="代理已停止；原模块恢复未完成，请到保护页检查";}}
  prefs.edit().putBoolean("proxyWanted",false).putString("proxyError",failure).apply();state=failure.isEmpty()?"已停止":failure;stopForeground(true);stopSelf();engaged=false;
 }
 private void closeDescriptor(){ParcelFileDescriptor p=descriptor;descriptor=null;if(p!=null)try{p.close();}catch(IOException ignored){}}
 @Override public void onRevoke(){stopping=true;prefs.edit().putBoolean("proxyWanted",false).apply();submit(this::finishStop);}
 @Override public void onDestroy(){stopping=true;closeDescriptor();if(!cleanupDone.get())submit(this::finishStop);worker.shutdown();super.onDestroy();}
}
