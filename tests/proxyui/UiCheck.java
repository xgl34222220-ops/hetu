package bichen.proxyuicheck;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;

/** Installed UI/storage regression for the single proxy control surface. */
public final class UiCheck extends Instrumentation {
 Context c;Activity a;int checks;StringBuilder output=new StringBuilder();
 void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;output.append("PASS ").append(name).append('\n');}
 Object invoke(Object o,String name,Class<?>[] t,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,t);m.setAccessible(true);return m.invoke(o,args);}
 Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 Object create(String name)throws Exception{Constructor<?> ctor=Class.forName(c.getPackageName()+"."+name,true,c.getClassLoader()).getDeclaredConstructor(Context.class);ctor.setAccessible(true);return ctor.newInstance(c);}
 String strings(){StringBuilder b=new StringBuilder();runOnMainSync(()->collect(a.getWindow().getDecorView(),b));return b.toString();}
 void collect(View v,StringBuilder b){if(v instanceof TextView)b.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup){ViewGroup p=(ViewGroup)v;for(int i=0;i<p.getChildCount();i++)collect(p.getChildAt(i),b);}}
 void shot(String name)throws Exception{Bitmap b=getUiAutomation().takeScreenshot();if(b!=null)try(OutputStream out=new FileOutputStream(new File(c.getFilesDir(),"ui-"+name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle b=new Bundle();try{
  c=getTargetContext();SharedPreferences prefs=c.getSharedPreferences("bichen",0);
  prefs.edit().putString("appearance","light").putString("proxyBaseCore","mihomo").putString("proxyBaseMode","tproxy").putString("proxyBaseIpv6","disable").putBoolean("proxyBaseAutoOverwrite",true).commit();
  Object store=create("ProxyStore");
  String rootYaml="mode: rule\nlisteners:\n - name: root-in\n   type: tproxy\n   port: 9898\nproxies: []\nproxy-groups: [{name: SELECT, type: select, proxies: [DIRECT]}]\nrules: ['MATCH,SELECT']\ndns: {enable: true, nameserver: [https://1.1.1.1/dns-query]}\n";
  String rev=(String)invoke(store,"revision",new Class<?>[0]);
  Object saved=invoke(store,"saveRootIfUnchanged",new Class<?>[]{String.class,String.class,String.class},rootYaml,"",rev);
  check(Boolean.TRUE.equals(saved),"Root configuration with TPROXY listener is accepted");
  check(rootYaml.equals(invoke(store,"yaml",new Class<?>[0])),"Root configuration stored without rewriting YAML");
  boolean tunRejected=false;
  try{invoke(store,"saveIfUnchanged",new Class<?>[]{String.class,String.class,String.class},rootYaml,"",invoke(store,"revision",new Class<?>[0]));}
  catch(InvocationTargetException expected){tunRejected=expected.getCause()!=null;}
  check(tunRejected,"TUN validation remains separate from Root validation");
  for(String appearance:new String[]{"light","dark"}){
   prefs.edit().putString("appearance",appearance).commit();
   a=startActivitySync(new Intent(c,Class.forName(c.getPackageName()+".RootTproxyActivity",true,c.getClassLoader())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
   for(int i=0;i<100&&(Boolean)field(a,"busy");i++)SystemClock.sleep(100);
   String text=strings();
   check(text.contains("基础代理配置"),appearance+": unified proxy settings opens");
   check(text.contains("核心选择")&&text.contains("Mihomo"),appearance+": core selector visible");
   check(text.contains("运行模式")&&text.contains("TPROXY"),appearance+": TPROXY mode visible");
   check(text.contains("IPv6")&&text.contains("禁用系统 IPv6"),appearance+": IPv6 mode visible");
   check(text.contains("自动覆写")&&text.contains("开启"),appearance+": overwrite setting visible");
   check(text.contains("配置选择")&&text.contains("config.yaml"),appearance+": imported Root config visible");
   check(text.contains("启动")&&text.contains("停止"),appearance+": one start-stop control visible");
   check(!text.contains("Android TUN 模式不会接管"),appearance+": stale TUN warning absent");
   Object theme=field(a,"u");check(((Boolean)field(theme,"dark"))==appearance.equals("dark"),appearance+": appearance follows setting");
   shot(appearance+"-unified-proxy");runOnMainSync(a::finish);waitForIdleSync();SystemClock.sleep(250);
  }
  prefs.edit().putString("appearance","system").commit();
  b.putString("stream",output+"BICHEN_PROXY_UI_PASS checks="+checks+"\nSingle proxy control surface; no external traffic asserted here.\n");finish(Activity.RESULT_OK,b);
 }catch(Throwable e){b.putString("stream",output+"BICHEN_PROXY_UI_FAIL\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,b);}}
}
