package bichen.proxyuicheck;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;

/** Installed UI/storage regression for the BoxProxy-style single proxy control surface. */
public final class UiCheck extends Instrumentation {
 Context c;Activity a;int checks;StringBuilder output=new StringBuilder();
 void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;output.append("PASS ").append(name).append('\n');}
 Object invoke(Object o,String name,Class<?>[] t,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,t);m.setAccessible(true);return m.invoke(o,args);}
 Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 Object create(String name)throws Exception{Constructor<?> ctor=Class.forName(c.getPackageName()+"."+name,true,c.getClassLoader()).getDeclaredConstructor(Context.class);ctor.setAccessible(true);return ctor.newInstance(c);}
 String strings(){StringBuilder b=new StringBuilder();runOnMainSync(()->collect(a.getWindow().getDecorView(),b));return b.toString();}
 void collect(View v,StringBuilder b){if(v instanceof TextView)b.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup){ViewGroup p=(ViewGroup)v;for(int i=0;i<p.getChildCount();i++)collect(p.getChildAt(i),b);}}
 void shot(String name)throws Exception{Bitmap b=getUiAutomation().takeScreenshot();if(b!=null)try(OutputStream out=new FileOutputStream(new File(c.getFilesDir(),"ui-"+name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}
 @SuppressWarnings({"unchecked","rawtypes"})
 @Override public void onStart(){Bundle b=new Bundle();try{
  c=getTargetContext();SharedPreferences prefs=c.getSharedPreferences("bichen",0);prefs.edit().putString("appearance","light").putString("proxyBaseCore","mihomo").putString("proxyBaseMode","tproxy").putString("proxyBaseIpv6","disable").putBoolean("proxyBaseAutoOverwrite",true).commit();
  String rootYaml="mode: rule\ntproxy-port: 9898\nproxies: []\nproxy-groups: [{name: SELECT, type: select, proxies: [DIRECT]}]\nrules: ['MATCH,SELECT']\ndns: {enable: true, nameserver: [https://1.1.1.1/dns-query]}\n";
  Class<?> coreClass=Class.forName(c.getPackageName()+".ProxyRuntimeProfile$Core",true,c.getClassLoader());Object mihomo=Enum.valueOf((Class)coreClass,"MIHOMO");
  Object library=create("ProxyConfigLibrary");Object entry=invoke(library,"importConfig",new Class<?>[]{coreClass,String.class,InputStream.class},mihomo,"root.yaml",new ByteArrayInputStream(rootYaml.getBytes("UTF-8")));
  Class<?> entryClass=Class.forName(c.getPackageName()+".ProxyConfigLibrary$Entry",true,c.getClassLoader());String stored=(String)invoke(library,"read",new Class<?>[]{entryClass},entry);check(rootYaml.equals(stored),"source configuration library preserves imported YAML");check("root.yaml".equals(field(entry,"name")),"configuration library keeps source filename");
  Object legacy=create("ProxyStore");boolean tunRejected=false;String listenerYaml=rootYaml+"listeners:\n - name: root-in\n   type: tproxy\n   port: 9898\n";try{invoke(legacy,"saveIfUnchanged",new Class<?>[]{String.class,String.class,String.class},listenerYaml,"",invoke(legacy,"revision",new Class<?>[0]));}catch(InvocationTargetException expected){tunRejected=expected.getCause()!=null;}check(tunRejected,"Android TUN validation stays separate from Root source library");
  for(String appearance:new String[]{"light","dark"}){
   prefs.edit().putString("appearance",appearance).commit();a=startActivitySync(new Intent(c,Class.forName(c.getPackageName()+".RootTproxyActivity",true,c.getClassLoader())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();SystemClock.sleep(500);String text=strings();
   check(text.contains("基础代理配置"),appearance+": unified proxy settings opens");check(text.contains("核心选择")&&text.contains("Mihomo"),appearance+": core selector visible");check(text.contains("运行模式")&&text.contains("TPROXY"),appearance+": TPROXY mode visible");check(text.contains("IPv6")&&text.contains("禁用系统 IPv6"),appearance+": IPv6 mode visible");check(text.contains("自动覆写")&&text.contains("开启"),appearance+": overwrite setting visible");check(text.contains("配置选择")&&text.contains("root.yaml"),appearance+": selected source config visible");check(text.contains("核心管理")&&text.contains("查看启动配置"),appearance+": core and startup tools visible");check(text.contains("启动")&&text.contains("停止"),appearance+": one start-stop control visible");check(!text.contains("Android TUN 模式不会接管"),appearance+": stale TUN warning absent");Object theme=field(a,"u");check(((Boolean)field(theme,"dark"))==appearance.equals("dark"),appearance+": appearance follows setting");shot(appearance+"-boxproxy-architecture");runOnMainSync(a::finish);waitForIdleSync();SystemClock.sleep(250);
  }
  prefs.edit().putString("appearance","system").commit();b.putString("stream",output+"BICHEN_PROXY_UI_PASS checks="+checks+"\nCore/config/runtime stores are separate; Root traffic still requires real rooted-device validation.\n");finish(Activity.RESULT_OK,b);
 }catch(Throwable e){b.putString("stream",output+"BICHEN_PROXY_UI_FAIL\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,b);}}
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
}
