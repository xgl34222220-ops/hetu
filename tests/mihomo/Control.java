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
 private Object active()throws Exception{Field f=service.getDeclaredField("activePolicy");f.setAccessible(true);return f.get(null);}
 private Object policy(String name)throws Exception{Object p=active();Field f=p.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(p);}
 private boolean pending()throws Exception{Method m=service.getDeclaredMethod("pendingSettings",SharedPreferences.class,String.class);m.setAccessible(true);return (Boolean)m.invoke(null,c.getSharedPreferences("bichen",0),c.getPackageName());}
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
  startServiceAndWait();
  SharedPreferences prefs=c.getSharedPreferences("bichen",0);Set<String> bypass=new HashSet<>(Arrays.asList("bichen.proxyprobe","bichen.nonexistent.fixture"));
  check(!pending(),"fresh session has no unapplied settings");
  check(Boolean.TRUE.equals(policy("filterEnabled"))&&((Set<?>)policy("applied")).isEmpty(),"actual session snapshots enabled filter and empty bypass list");
  prefs.edit().putBoolean("proxyFilter",false).putStringSet("bypassApps",bypass).commit();
  check(pending(),"editing active filter and app list reports unapplied changes");
  check(Boolean.TRUE.equals(policy("filterEnabled"))&&((Set<?>)policy("applied")).isEmpty(),"saved edits do not pretend to change running VPN policy");
  prefs.edit().putBoolean("proxyFilter",true).putStringSet("bypassApps",Collections.emptySet()).commit();check(!pending(),"reverting settings removes reconnect warning");
  String loaded=(String)service.getField("activeRuleRevision").get(null);
  rs.getMethod("changeDomain",String.class,boolean.class,boolean.class,boolean.class).invoke(rules,"new.ads.bichen.test",false,true,false);
  check(pending(),"rule changes are marked pending until next actual start");check(loaded.equals(service.getField("activeRuleRevision").get(null)),"running rule revision remains the actually loaded revision");
  check(core("proxies").getJSONObject("data").has("SELECT"),"actual core exposes imported group");
  JSONObject select=new JSONObject().put("action","select").put("group","SELECT").put("name","SENTINEL");bridge.getMethod("call",JSONObject.class).invoke(null,select);check(core("proxies").getJSONObject("data").getJSONObject("SELECT").getString("now").equals("SENTINEL"),"node selection changes actual core");
  mark("vpn-ready","ready");await("path-done");check(new File(dir,"live-connections.json").isFile(),"real independent-UID traffic appears in core connections");
  List<?> captured=observations(seen);check(captured.toString().contains("allowed.bichen.test"),"service sampler retains actual independent-UID domain connection");check(!captured.toString().contains("ads.bichen.test"),"rejected requests are not fabricated into active-connection observations");
  stopServiceAndWait();int retained=observations(seen).size();check(retained>0,"sampled observations remain after proxy stops");method(seen,"setEnabled",new Class<?>[]{boolean.class},false);
  mark("vpn-stopped","stopped");await("stop-probe-done");
  startServiceAndWait();check(!pending(),"restart actually loads revised rule snapshot");mark("vpn-restarted","running");await("restart-probe-done");stopServiceAndWait();check(observations(seen).size()==retained,"disabling observations prevents writes during next real traffic session");
  prefs.edit().putStringSet("bypassApps",bypass).commit();startServiceAndWait();
  check(((Set<?>)policy("applied")).equals(Collections.singleton("bichen.proxyprobe")),"installed selected app is actually excluded by VPN Builder");
  check(((Set<?>)policy("missing")).equals(Collections.singleton("bichen.nonexistent.fixture")),"missing package is not counted as an applied exclusion");check(!pending(),"uninstalled selection alone does not cause endless reconnect warning");
  mark("bypass-ready","running");await("bypass-done");check(service.getField("running").getBoolean(null)&&core("version").getJSONObject("data").getBoolean("running"),"bypassed probe uses direct network while native VPN stays running");
  prefs.edit().putStringSet("bypassApps",Collections.emptySet()).commit();check(pending(),"removing bypass warns before reconnect");
  mark("bypass-draft-ready","running");await("bypass-draft-done");
  stopServiceAndWait();startServiceAndWait();check(((Set<?>)policy("applied")).isEmpty()&&!pending(),"reconnect applies removal of bypass");
  mark("reincluded-ready","running");await("reincluded-done");stopServiceAndWait();
  check(active()==null&&service.getField("activeRuleCount").getInt(null)==0,"stop clears stale effective policy and rule counters");
  ((android.database.sqlite.SQLiteOpenHelper)seen).close();
  mark("proxy-checks.txt",result.toString());b.putString("stream",result+"BICHEN_MIHOMO_CONTROL_PASS checks="+checks+"\nNo OEM/Root framework or commercial subscription was tested.\n");finish(Activity.RESULT_OK,b);
 }catch(Throwable e){try{mark("control-error.txt",android.util.Log.getStackTraceString(e));}catch(Throwable ignored){}b.putString("stream",result+"BICHEN_MIHOMO_CONTROL_FAIL\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,b);}}
}
