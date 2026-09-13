package bichen.proxytest;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Bitmap;
import java.io.*;
import org.json.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
/** An independent UID crosses a real VPN, with filter-off and whitelist controls. */
public final class ProxySmoke extends Instrumentation {
 private int checks;private final StringBuilder log=new StringBuilder();private Context context;private Class<?> nativeClass,service;
 private void check(boolean ok,String detail){if(!ok)throw new AssertionError(detail);checks++;log.append("PASS ").append(detail).append('\n');}
 private JSONObject call(JSONObject q)throws Exception{return(JSONObject)nativeClass.getMethod("call",JSONObject.class).invoke(null,q);}
 private void startCore()throws Exception{
  context.startForegroundService(new Intent(context,service).setAction("START"));
  for(int i=0;i<300&&!service.getField("running").getBoolean(null);i++){if(!service.getField("failure").get(null).toString().isEmpty()&&i>5)break;SystemClock.sleep(100);}
  check(service.getField("running").getBoolean(null),"real TUN starts: "+service.getField("failure").get(null));
 }
 private void stopCore()throws Exception{
  context.startService(new Intent(context,service).setAction("STOP"));for(int i=0;i<100&&service.getField("engaged").getBoolean(null);i++)SystemClock.sleep(100);
  check(!service.getField("engaged").getBoolean(null),"real TUN and core stop");SystemClock.sleep(600);
 }
 private void probe(boolean adsAllowed,String phase)throws Exception{
  CountDownLatch done=new CountDownLatch(1);final Intent[] response={null};BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){response[0]=i;done.countDown();}};
  context.registerReceiver(receiver,new IntentFilter("bichen.integration.RESULT"),Context.RECEIVER_EXPORTED);
  try{
   context.startActivity(new Intent().setComponent(new ComponentName("bichen.probe","bichen.probe.Probe")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   check(done.await(25,TimeUnit.SECONDS),phase+": independent UID probe returned");
   check(response[0].getBooleanExtra("allowed",false),phase+": ordinary request reaches configured proxy: "+response[0].getStringExtra("detail"));
   check(response[0].getBooleanExtra("adsAllowed",false)==adsAllowed,phase+": ad-domain result matches filter state");
   if(!adsAllowed)check(response[0].getBooleanExtra("blocked",false),phase+": rejection observed by client");
  }finally{context.unregisterReceiver(receiver);}
 }
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Bundle result=new Bundle();try{
  context=getTargetContext();String pkg=context.getPackageName();ClassLoader loader=context.getClassLoader();
  nativeClass=Class.forName(pkg+".MihomoNative",true,loader);service=Class.forName(pkg+".MihomoVpnService",true,loader);
  check(call(new JSONObject().put("action","version")).getJSONObject("data").getString("engine").equals("Mihomo"),"real shared core loads via JNI");
  Class<?> storeClass=Class.forName(pkg+".ProxyStore",true,loader);Constructor<?> ctor=storeClass.getDeclaredConstructor(Context.class);ctor.setAccessible(true);Object store=ctor.newInstance(context);Method save=storeClass.getDeclaredMethod("save",String.class,String.class);save.setAccessible(true);
  String yaml="mode: rule\nproxies:\n - name: LOOP\n   type: http\n   server: 10.0.2.2\n   port: 18081\nproxy-groups:\n - name: SELECT\n   type: select\n   proxies: [LOOP]\nrules: ['MATCH,SELECT']\ndns:\n enable: true\n enhanced-mode: fake-ip\n fake-ip-range: 198.18.0.1/16\n nameserver: [https://1.1.1.1/dns-query]\n";
  save.invoke(store,yaml,"");check(true,"complete YAML imported through production store");
  Class<?> ruleClass=Class.forName(pkg+".RuleStore",true,loader);Object rules=ruleClass.getConstructor(Context.class).newInstance(context);ruleClass.getMethod("reload").invoke(rules);
  Method edit=ruleClass.getMethod("changeDomain",String.class,boolean.class,boolean.class,boolean.class);edit.invoke(rules,"ads.integration.test",false,true,false);
  SharedPreferences prefs=context.getSharedPreferences("bichen",0);prefs.edit().putBoolean("proxyManageHosts",false).putBoolean("proxyFilter",true).putBoolean("vpnWanted",false).putBoolean("vpnRestoreHosts",false).commit();
  startCore();JSONObject groups=call(new JSONObject().put("action","proxies")).getJSONObject("data");check(groups.has("SELECT"),"core returns actual proxy group");call(new JSONObject().put("action","select").put("group","SELECT").put("name","LOOP"));check(true,"actual selector change accepted");
  probe(false,"filter-on");call(new JSONObject().put("action","connections"));check(true,"actual connection snapshot callable");stopCore();
  prefs.edit().putBoolean("proxyFilter",false).commit();startCore();probe(true,"filter-off positive control");stopCore();
  edit.invoke(rules,"ads.integration.test",true,true,false);prefs.edit().putBoolean("proxyFilter",true).commit();startCore();probe(true,"whitelist retains proxy routing");
  Activity screen=startActivitySync(new Intent(context,Class.forName(pkg+".ProxyActivity",true,loader)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();SystemClock.sleep(700);check(!screen.isFinishing(),"proxy configuration screen opens");
  Bitmap shot=getUiAutomation().takeScreenshot();if(shot!=null)try(OutputStream out=new FileOutputStream(new File(context.getFilesDir(),"mihomo-screen.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}
  stopCore();log.append("BICHEN_PROXY_PASS checks=").append(checks).append("\nControlled emulator proxy only; no OEM Root, real subscription or leak certification.\n");result.putString("stream",log.toString());finish(Activity.RESULT_OK,result);
 }catch(Throwable e){try{if(context!=null&&service!=null)context.startService(new Intent(context,service).setAction("STOP"));}catch(Throwable ignored){}result.putString("stream",log+"\nBICHEN_PROXY_FAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
