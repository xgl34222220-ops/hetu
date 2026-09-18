package io.github.xgl34222220.hetu;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.content.pm.ServiceInfo;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.*;
import android.content.pm.PackageManager;
/** Full IP tunnel. No Root firewall/Box changes; opt-in linkage only to our own hosts. */
public final class MihomoVpnService extends VpnService {
 public static volatile boolean engaged,running;
 public static volatile long generation,startedAt;
 static volatile ProxyAppPolicy activePolicy;
 public static volatile String networkLabel="等待网络恢复",activeRuleRevision="";
 public static volatile int activeRuleCount;
 public static volatile boolean networkValidated,activeDnsGuard;
 private final ProxyNetworkState networkState=new ProxyNetworkState();
 static boolean pendingSettings(SharedPreferences p,String self){ProxyAppPolicy a=activePolicy;return running&&a!=null&&(a.differs(p.getBoolean("proxyFilter",true),p.getStringSet("bypassApps",Collections.emptySet()),self)||activeDnsGuard!=p.getBoolean("proxyDnsGuard",false)||(a.filterEnabled&&!activeRuleRevision.equals(RuleStore.publishedRevision())));}
 static String settingsSummary(SharedPreferences p,String self){
  ProxyAppPolicy a=activePolicy;int draft=p.getStringSet("bypassApps",Collections.emptySet()).size();
  if(!running||a==null)return "已保存 "+draft+" 个应用放行 · 下次连接生效"+(p.getBoolean("proxyDnsGuard",false)?" · 加密 DNS 防绕过待连接":"");
  String protect=a.filterEnabled?"过滤 "+activeRuleCount+" 个域名规则":"广告域名过滤关闭";
  if(activeDnsGuard)protect+=" · 加密 DNS 防绕过已开";
  return "本次已放行 "+a.applied.size()+" 个 · "+protect+(a.missing.isEmpty()?"":"\n"+a.missing.size()+" 个应用未安装或当前不可见，未计入已放行")+(pendingSettings(p,self)?"\n存在未生效更改，请停止后重新连接":"");
 }
 private ProxyObservations observations;
 private final Runnable observe=new Runnable(){public void run(){if(owner!=MihomoVpnService.this||!running||stopping||destroyed)return;final long session=generation;final long epoch=observations.epoch();submit(()->{try{if(prefs.getBoolean("proxyHistory",false)&&running&&generation==session){JSONObject snap=MihomoNative.call("connections").getJSONObject("data");if(running&&!stopping&&generation==session){observations.capture(snap,epoch);if(prefs.contains("proxyHistoryError"))prefs.edit().remove("proxyHistoryError").apply();}}}catch(Exception e){prefs.edit().putString("proxyHistoryError","连接观察暂时失败，未补造记录").apply();}finally{if(running&&!stopping&&generation==session)ui.postDelayed(this,2000);}});}};
 public static volatile String state="未启动",failure="";
 private static volatile MihomoVpnService owner;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Handler ui=new Handler(Looper.getMainLooper());
 private volatile boolean stopping,destroyed;private volatile ParcelFileDescriptor descriptor;
 private SharedPreferences prefs;private volatile ConnectivityManager manager;private volatile ConnectivityManager.NetworkCallback callback;private volatile Network physical;
 private final Runnable startupDeadline=()->{if(owner==this&&engaged&&!running&&!stopping){failure="启动超过 60 秒，正在关闭内核；未报告连接成功";requestStop();}};
 @Override public void onCreate(){super.onCreate();prefs=getSharedPreferences("hetu",0);observations=new ProxyObservations(this);}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  String action=intent==null?"STOP":intent.getAction();
  if("STOP".equals(action)||"RESTORE".equals(action)){
   if(owner!=null&&owner!=this){stopSelf(startId);return START_NOT_STICKY;}owner=this;engaged=true;state="正在停止并检查恢复";foreground();requestStop();return START_NOT_STICKY;
  }
  if(engaged||destroyed)return START_NOT_STICKY;
  owner=this;engaged=true;stopping=false;failure="";generation++;startedAt=0;activePolicy=null;activeRuleCount=0;activeRuleRevision="";networkValidated=false;activeDnsGuard=false;networkLabel="等待网络恢复";state="正在校验配置";foreground();
  prefs.edit().putBoolean("proxyWanted",true).putString("engineOwner","mihomo").apply();ui.postDelayed(startupDeadline,60000);
  submit(()->{try{startCore();}catch(Exception|LinkageError e){failure=e.getMessage()==null?"内核启动失败":e.getMessage();finishStop();}});return START_NOT_STICKY;
 }
 private void submit(Runnable r){try{worker.execute(r);}catch(RejectedExecutionException ignored){}}
 private void requestStop(){if(owner!=this)return;stopping=true;running=false;ui.removeCallbacks(observe);state="正在停止";prefs.edit().putBoolean("proxyWanted",false).apply();ui.removeCallbacks(startupDeadline);submit(this::finishStop);}
 private void startCore()throws Exception{
  if(DnsVpnService.running||prefs.getBoolean("vpnWanted",false)||prefs.getBoolean("vpnRestoreHosts",false))throw new IOException("请先停止旧应用保护并完成模块恢复");
  final ProxyAppPolicy requested=new ProxyAppPolicy(prefs.getBoolean("proxyFilter",true),new HashSet<>(prefs.getStringSet("bypassApps",Collections.emptySet())),null,getPackageName());
  final boolean dnsGuardEnabled=prefs.getBoolean("proxyDnsGuard",false);
  final Set<String> dohDomains=dnsGuardEnabled?new EncryptedDnsGuard(this).domains():Collections.emptySet();
  ProxyStore store=new ProxyStore(this);String yaml=store.yaml();MihomoNative.call(new JSONObject().put("action","inspect").put("yaml",yaml));
  if(stopping||destroyed){finishStop();return;}RuleStore rules=new RuleStore(this);rules.reload();
  if(prefs.getBoolean("proxyManageHosts",false)){
   JSONObject s=RootBridge.status(this);if(!s.optBoolean("ok"))throw new IOException("模块状态未确认，未切换保护方式");if(s.optBoolean("pendingReboot"))throw new IOException("模块等待重启，未切换保护方式");
   if(s.optBoolean("installed")){rules.syncFromModule();if(s.optBoolean("enabled")&&!s.optBoolean("moduleDisabled")&&!s.optBoolean("moduleRemovalPending")){
    if(!prefs.edit().putBoolean("proxyRestoreHosts",true).commit())throw new IOException("无法保存模块恢复状态");RootBridge.Result paused=RootBridge.run(this,"pause");if(!paused.ok())throw new IOException("模块暂停失败，取消代理启动");
   }}
  }
  if(stopping||destroyed){finishStop();return;}if(VpnService.prepare(this)!=null)throw new IOException("需要在 App 内确认 VPN 授权");state="正在连接 Mihomo";foreground();
  Builder b=new Builder().setSession("河图 · 代理与去广告").setMtu(1500).addAddress("172.29.0.1",30).addAddress("fdfe:dcba:9876::1",126).addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("172.29.0.2").setBlocking(false);
  b.addDisallowedApplication(getPackageName());
  Set<String> accepted=new HashSet<>();
  for(String pkg:requested.requested){try{
   // Builder.verifyApp may accept a null binder result on some framework versions.
   // Check the public PackageManager API and current-user installation explicitly.
   android.content.pm.ApplicationInfo app=getPackageManager().getApplicationInfo(pkg,0);
   if(app==null||(app.flags&android.content.pm.ApplicationInfo.FLAG_INSTALLED)==0)throw new PackageManager.NameNotFoundException(pkg);
   b.addDisallowedApplication(pkg);accepted.add(pkg);
  }catch(PackageManager.NameNotFoundException absent){/* Preserve selection for reinstall; do not count as applied. */}}
  final ProxyAppPolicy applied=new ProxyAppPolicy(requested.filterEnabled,requested.requested,accepted,getPackageName());
  final RuleStore.EffectiveRules effective=rules.effectiveRules();
  Set<String> suffixCandidates=Collections.emptySet();
  if(applied.filterEnabled&&prefs.getBoolean("source_hagezi",false)){
   try(InputStream in=getAssets().open("rules/hagezi.txt")){suffixCandidates=RuleStore.parseRules(in,false);}
  }
  final DomainRuleProjection projected=DomainRuleProjection.build(effective.domains,suffixCandidates,rules.userList(true));
  b.setConfigureIntent(PendingIntent.getActivity(this,401,new Intent(this,ProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
  descriptor=b.establish();if(descriptor==null)throw new IOException("系统没有建立 VPN 接口");
  JSONObject start=new JSONObject().put("action","start").put("home",store.home().getAbsolutePath()).put("yaml",yaml).put("fd",descriptor.getFd()).put("filter",applied.filterEnabled).put("dnsGuard",dnsGuardEnabled);
  if(applied.filterEnabled)start.put("domains",new JSONArray(projected.exact)).put("suffixDomains",new JSONArray(projected.suffix));
  if(applied.filterEnabled||dnsGuardEnabled)start.put("allowDomains",new JSONArray(projected.allow));
  if(dnsGuardEnabled)start.put("dohDomains",new JSONArray(dohDomains));
  MihomoNative.call(start);
  if(stopping||destroyed){finishStop();return;}
  activePolicy=applied;activeRuleRevision=effective.revision;activeRuleCount=applied.filterEnabled?projected.blockedCount():0;activeDnsGuard=dnsGuardEnabled;
  running=true;startedAt=SystemClock.elapsedRealtime();ui.post(observe);ui.removeCallbacks(startupDeadline);
  state="内核已启动 · 等待网络确认";prefs.edit().remove("proxyError").apply();foreground();
  final long session=generation;ui.post(()->watchNetwork(session));
 }
 private boolean currentSession(long session){return owner==this&&running&&!stopping&&!destroyed&&generation==session;}
 private void watchNetwork(final long session){
  if(!currentSession(session))return;
  manager=getSystemService(ConnectivityManager.class);
  if(manager==null){networkLabel="无法确认网络状态";state=networkLabel;foreground();return;}
  final ConnectivityManager cm=manager;
  final ConnectivityManager.NetworkCallback watch=new ConnectivityManager.NetworkCallback(){
   @Override public void onAvailable(Network n){if(currentSession(session)){networkState.available(n);reportNetwork(session,false);}}
   @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){
    if(currentSession(session)&&networkState.capabilities(n,c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)))reportNetwork(session,true);
   }
   @Override public void onBlockedStatusChanged(Network n,boolean blocked){if(currentSession(session)&&networkState.blocked(n,blocked))reportNetwork(session,true);}
   @Override public void onLost(Network n){if(currentSession(session)&&networkState.lost(n))reportNetwork(session,true);}
  };
  callback=watch;
  try{
   cm.registerDefaultNetworkCallback(watch,ui);
   // Stop may race registration on the worker; always unregister our exact callback.
   if(!currentSession(session)){try{cm.unregisterNetworkCallback(watch);}catch(RuntimeException ignored){}if(callback==watch)callback=null;}
  }catch(RuntimeException error){
   try{cm.unregisterNetworkCallback(watch);}catch(RuntimeException ignored){}
   if(callback==watch)callback=null;
   if(currentSession(session)){networkLabel="网络状态监听失败";state=networkLabel;foreground();}
  }
 }
 private void reportNetwork(long session,boolean updateUnderlying){
  if(!currentSession(session))return;
  ProxyNetworkState.State net=networkState.state();networkValidated=net==ProxyNetworkState.State.READY;networkLabel=ProxyNetworkState.label(net);
  if(updateUnderlying){
   physical=(Network)networkState.underlying();
   try{if(!setUnderlyingNetworks(physical==null?new Network[0]:new Network[]{physical})){networkValidated=false;networkLabel="底层网络关联未确认";}}
   catch(RuntimeException error){networkValidated=false;networkLabel="底层网络关联失败";}
  }
  ProxyAppPolicy a=activePolicy;
  String protection=a!=null&&a.filterEnabled?"代理与域名过滤已加载":"仅代理已加载";
  if(activeDnsGuard)protection+=" · DNS 防绕过";
  String next=networkLabel+" · "+protection;
  if(!next.equals(state)){state=next;foreground();}
 }

 private void foreground(){if(destroyed||owner!=this)return;
  NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("proxy_core","Mihomo 代理",NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,402,new Intent(this,ProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,403,new Intent(this,MihomoVpnService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification n=new Notification.Builder(this,"proxy_core").setSmallIcon(getApplicationInfo().icon).setContentTitle("河图 · Mihomo").setContentText(state).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
  if(Build.VERSION.SDK_INT>=34)startForeground(401,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(401,n);
 }
 private void finishStop(){if(owner!=this)return;
  running=false;stopping=true;startedAt=0;networkValidated=false;activeDnsGuard=false;networkLabel="未运行";activePolicy=null;activeRuleCount=0;activeRuleRevision="";ui.removeCallbacks(observe);ui.removeCallbacks(startupDeadline);try{MihomoNative.call("stop");}catch(Exception|LinkageError ignored){}closeDescriptor();
  ConnectivityManager cm=manager;ConnectivityManager.NetworkCallback watch=callback;callback=null;
  if(cm!=null&&watch!=null){try{cm.unregisterNetworkCallback(watch);}catch(RuntimeException ignored){}}
  if(prefs.getBoolean("proxyRestoreHosts",false)){try{
   JSONObject s=RootBridge.status(this);if(!s.optBoolean("ok")||s.optBoolean("pendingReboot"))throw new IOException("原模块恢复状态待确认");
   if(s.optBoolean("installed")&&!s.optBoolean("moduleDisabled")&&!s.optBoolean("moduleRemovalPending")){RootBridge.Result r=RootBridge.run(this,"enable");if(!r.ok())throw new IOException("原模块恢复失败");}
   if(!prefs.edit().putBoolean("proxyRestoreHosts",false).commit())throw new IOException("恢复标记保存失败");
  }catch(Exception e){failure="代理已停止；原模块恢复未完成，请点重试恢复原模块";}}
  prefs.edit().putBoolean("proxyWanted",false).putString("proxyError",failure).apply();engaged=false;owner=null;state=failure.isEmpty()?"已停止":failure;stopForeground(true);stopSelf();
 }
 private void closeDescriptor(){ParcelFileDescriptor p=descriptor;descriptor=null;if(p!=null)try{p.close();}catch(IOException ignored){}}
 @Override public void onRevoke(){requestStop();}
 @Override public void onDestroy(){destroyed=true;stopping=true;ui.removeCallbacksAndMessages(null);if(owner==this)submit(this::finishStop);submit(observations::close);worker.shutdown();super.onDestroy();}
}
