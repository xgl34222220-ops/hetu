package bichen.mihomotest;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
/** Controls the actual service; a separate-UID APK supplies VPN traffic. */
public final class Control extends Instrumentation {
 private Context c;private Class<?> service,bridge;private File dir;private int checks;private StringBuilder result=new StringBuilder();
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 private void check(boolean pass,String name){if(!pass)throw new AssertionError(name);checks++;result.append("PASS ").append(name).append('\n');}
 private JSONObject core(String action)throws Exception{return (JSONObject)bridge.getMethod("call",String.class).invoke(null,action);}
 private void mark(String name,String value)throws Exception{try(OutputStream s=new FileOutputStream(new File(dir,name))){s.write(value.getBytes("UTF-8"));}}
 private void await(String file)throws Exception{for(int i=0;i<180;i++){if(new File(dir,file).exists())return;if(service.getField("running").getBoolean(null)){JSONObject s=core("connections").getJSONObject("data");if(s.optJSONArray("connections")!=null&&s.getJSONArray("connections").length()>0)mark("live-connections.json",s.toString());}Thread.sleep(500);}throw new AssertionError("host handshake timed out: "+file);}
 private void startServiceAndWait()throws Exception{runOnMainSync(()->c.startForegroundService(new Intent(c,service).setAction("START")));for(int i=0;i<120;i++){if(service.getField("running").getBoolean(null))return;Thread.sleep(500);}throw new AssertionError("actual VPN service did not start: "+service.getField("failure").get(null));}
 private void stopServiceAndWait()throws Exception{runOnMainSync(()->c.startService(new Intent(c,service).setAction("STOP")));for(int i=0;i<120;i++){if(!service.getField("engaged").getBoolean(null)){check(!service.getField("running").getBoolean(null),"service reports stopped");check(!core("version").getJSONObject("data").getBoolean("running"),"native core actually stopped");return;}Thread.sleep(250);}throw new AssertionError("actual VPN did not stop");}
 private Object method(Object target,String name,Class<?>[] types,Object...args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
 private List<?> observations(Object store)throws Exception{return (List<?>)method(store,"readRecent",new Class<?>[0]);}
 @Override public void onStart(){Bundle b=new Bundle();try{
  c=getTargetContext();dir=c.getFilesDir();String pkg=c.getPackageName();ClassLoader cl=c.getClassLoader();service=Class.forName(pkg+".MihomoVpnService",true,cl);bridge=Class.forName(pkg+".MihomoNative",true,cl);
  check(core("version").getJSONObject("data").getString("revision").equals("ac017cdd246ce8bd547653d927e7bf77d7ee73d5"),"installed native library loads exact pinned Mihomo");
  c.getSharedPreferences("bichen",0).edit().putBoolean("vpnWanted",false).putBoolean("vpnRestoreHosts",false).putBoolean("proxyManageHosts",false).putBoolean("proxyFilter",true).commit();
  Class<?> history=Class.forName(pkg+".ProxyObservations",true,cl);Constructor<?> hc=history.getDeclaredConstructor(Context.class);hc.setAccessible(true);Object seen=hc.newInstance(c);method(seen,"clear",new Class<?>[0]);method(seen,"setEnabled",new Class<?>[]{boolean.class},true);
  String yaml="mode: rule\nproxies:\n - name: SENTINEL\n   type: socks5\n   server: 10.0.2.2\n   port: 19088\nproxy-groups:\n - name: SELECT\n   type: select\n   proxies: [SENTINEL]\nrules:\n - MATCH,SELECT\ndns:\n enable: true\n enhanced-mode: fake-ip\n fake-ip-range: 198.18.0.1/16\n nameserver: [127.0.0.1:19553]\n nameserver-policy:\n  stun.fixture.test: [rcode://name_error]\n";
  Class<?> store=Class.forName(pkg+".ProxyStore",true,cl);Constructor<?> constructor=store.getDeclaredConstructor(Context.class);constructor.setAccessible(true);Object cfg=constructor.newInstance(c);Method save=store.getDeclaredMethod("save",String.class,String.class);save.setAccessible(true);save.invoke(cfg,yaml,"https://fixture.invalid/non-secret");Method read=store.getDeclaredMethod("yaml");read.setAccessible(true);check(read.invoke(cfg).equals(yaml),"private import preserves original YAML exactly");
  try{save.invoke(cfg,"proxies: []\nmode: global","");throw new AssertionError("global unexpectedly accepted");}catch(InvocationTargetException expected){check(read.invoke(cfg).equals(yaml),"failed import keeps last original YAML");}
  Class<?> rs=Class.forName(pkg+".RuleStore",true,cl);Object rules=rs.getConstructor(Context.class).newInstance(c);rs.getMethod("reload").invoke(rules);rs.getMethod("changeDomain",String.class,boolean.class,boolean.class,boolean.class).invoke(rules,"ads.bichen.test",false,true,false);
  Activity a=startActivitySync(new Intent(c,Class.forName(pkg+".ProxyActivity",true,cl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();check(!a.isFinishing(),"actual proxy page opens");
  startServiceAndWait();check(core("proxies").getJSONObject("data").has("SELECT"),"actual core exposes imported group");
  JSONObject select=new JSONObject().put("action","select").put("group","SELECT").put("name","SENTINEL");bridge.getMethod("call",JSONObject.class).invoke(null,select);check(core("proxies").getJSONObject("data").getJSONObject("SELECT").getString("now").equals("SENTINEL"),"node selection changes actual core");
  mark("vpn-ready","ready");await("path-done");check(new File(dir,"live-connections.json").isFile(),"real independent-UID traffic appears in core connections");
  List<?> captured=observations(seen);check(captured.toString().contains("allowed.bichen.test"),"service sampler retains actual independent-UID domain connection");check(!captured.toString().contains("ads.bichen.test"),"rejected requests are not fabricated into active-connection observations");
  stopServiceAndWait();int retained=observations(seen).size();check(retained>0,"sampled observations remain after proxy stops");method(seen,"setEnabled",new Class<?>[]{boolean.class},false);
  mark("vpn-stopped","stopped");await("stop-probe-done");
  startServiceAndWait();mark("vpn-restarted","running");await("restart-probe-done");stopServiceAndWait();check(observations(seen).size()==retained,"disabling observations prevents writes during next real traffic session");((android.database.sqlite.SQLiteOpenHelper)seen).close();
  mark("proxy-checks.txt",result.toString());b.putString("stream",result+"BICHEN_MIHOMO_CONTROL_PASS checks="+checks+"\nNo OEM/Root framework or commercial subscription was tested.\n");finish(Activity.RESULT_OK,b);
 }catch(Throwable e){try{mark("control-error.txt",android.util.Log.getStackTraceString(e));}catch(Throwable ignored){}b.putString("stream",result+"BICHEN_MIHOMO_CONTROL_FAIL\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,b);}}
}
