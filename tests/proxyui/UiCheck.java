package bichen.proxyuicheck;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Real installed UI/storage tests. Injected storage snapshots are explicitly fixtures. */
public final class UiCheck extends Instrumentation {
 Context c;Activity a;int checks;StringBuilder output=new StringBuilder();
 void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;output.append("PASS ").append(name).append('\n');}
 Object invoke(Object o,String name,Class<?>[] t,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,t);m.setAccessible(true);return m.invoke(o,args);}
 Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 Object create(String name)throws Exception{Constructor<?> ctor=Class.forName(c.getPackageName()+"."+name,true,c.getClassLoader()).getDeclaredConstructor(Context.class);ctor.setAccessible(true);return ctor.newInstance(c);}
 String strings(){StringBuilder b=new StringBuilder();runOnMainSync(()->collect(a.getWindow().getDecorView(),b));return b.toString();}
 void collect(View v,StringBuilder b){if(v instanceof TextView)b.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup){ViewGroup p=(ViewGroup)v;for(int i=0;i<p.getChildCount();i++)collect(p.getChildAt(i),b);}}
 void page(int n)throws Exception{Method m=a.getClass().getDeclaredMethod("showPage",int.class);m.setAccessible(true);runOnMainSync(()->{try{m.invoke(a,n);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();SystemClock.sleep(350);}
 void shot(String name)throws Exception{Bitmap b=getUiAutomation().takeScreenshot();if(b!=null)try(OutputStream out=new FileOutputStream(new File(c.getFilesDir(),"ui-"+name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}
 JSONArray rows(Object store)throws Exception{List<?> rows=(List<?>)invoke(store,"readRecent",new Class<?>[0]);return new JSONArray(rows);}
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle b=new Bundle();try{
  c=getTargetContext();SharedPreferences prefs=c.getSharedPreferences("bichen",0);prefs.edit().putString("appearance","light").commit();
  Object store=create("ProxyStore");String yaml="mode: rule\nproxies: []\nproxy-groups: [{name: SELECT, type: select, proxies: [DIRECT]}]\nrules: ['MATCH,SELECT']\ndns: {enable: true, nameserver: [https://1.1.1.1/dns-query]}\n";
  invoke(store,"save",new Class<?>[]{String.class,String.class},yaml,"https://fixture.invalid/?secret=DO_NOT_DISPLAY");String first=(String)invoke(store,"revision",new Class<?>[0]);
  String updated=yaml+"# revised fixture\n";invoke(store,"save",new Class<?>[]{String.class,String.class},updated,"https://fixture.invalid/?secret=DO_NOT_DISPLAY");String second=(String)invoke(store,"revision",new Class<?>[0]);check(!first.equals(second),"configuration revision follows actual content");
  Object same=invoke(store,"saveIfUnchanged",new Class<?>[]{String.class,String.class,String.class},updated,"https://fixture.invalid/?secret=DO_NOT_DISPLAY",second);check(Boolean.FALSE.equals(same),"identical update is classified as unchanged");
  invoke(store,"restore",new Class<?>[0]);check(yaml.equals(invoke(store,"yaml",new Class<?>[0])),"identical update preserves previous rollback target");
  try{invoke(store,"saveIfUnchanged",new Class<?>[]{String.class,String.class,String.class},updated,"",second);throw new AssertionError("stale commit accepted");}catch(InvocationTargetException expected){check(yaml.equals(invoke(store,"yaml",new Class<?>[0])),"stale download cannot replace newly changed configuration");}
  JSONObject info=(JSONObject)invoke(store,"info",new Class<?>[0]);check(!info.toString().contains("DO_NOT_DISPLAY")&&!info.has("yaml"),"configuration UI metadata excludes credentials and source text");check(info.optBoolean("hasPrevious"),"rollback remains available");
  Object obs=create("ProxyObservations");invoke(obs,"clear",new Class<?>[0]);invoke(obs,"setEnabled",new Class<?>[]{boolean.class},false);
  JSONObject m=new JSONObject().put("host","observed.fixture.test").put("destinationIP","198.51.100.1").put("destinationPort",443).put("network","tcp");JSONObject entry=new JSONObject().put("id","fixture-1").put("metadata",m).put("download",1234).put("rule","Match").put("chains",new JSONArray().put("SELECT")).put("password","MUST_NOT_SAVE");JSONObject snap=new JSONObject().put("connections",new JSONArray().put(entry));
  long epoch=(Long)invoke(obs,"epoch",new Class<?>[0]);invoke(obs,"capture",new Class<?>[]{JSONObject.class,long.class},snap,epoch);check(rows(obs).length()==0,"observations default off saves nothing");
  invoke(obs,"setEnabled",new Class<?>[]{boolean.class},true);epoch=(Long)invoke(obs,"epoch",new Class<?>[0]);invoke(obs,"capture",new Class<?>[]{JSONObject.class,long.class},snap,epoch);check(rows(obs).length()==1,"opt-in snapshot writes a real provided entry");check(!rows(obs).toString().contains("MUST_NOT_SAVE"),"only approved metadata fields are retained");
  invoke(obs,"clear",new Class<?>[0]);invoke(obs,"capture",new Class<?>[]{JSONObject.class,long.class},snap,epoch);check(rows(obs).length()==0,"clear rejects in-flight old-epoch snapshot");
  epoch=(Long)invoke(obs,"epoch",new Class<?>[0]);JSONArray many=new JSONArray();for(int n=0;n<350;n++)many.put(new JSONObject(entry.toString()).put("id","fixture-"+n));invoke(obs,"capture",new Class<?>[]{JSONObject.class,long.class},new JSONObject().put("connections",many),epoch);check(rows(obs).length()==300,"observations are capped to 300 unique connection IDs");
  invoke(obs,"setEnabled",new Class<?>[]{boolean.class},false);invoke(obs,"capture",new Class<?>[]{JSONObject.class,long.class},snap,epoch);check(rows(obs).length()==300,"turning off stops writes but retains prior observations");
  // Screens show an imported fixture configuration without pretending a tunnel is running.
  for(String mode:new String[]{"light","dark"}){
   prefs.edit().putString("appearance",mode).commit();a=startActivitySync(new Intent(c,Class.forName(c.getPackageName()+".ProxyActivity",true,c.getClassLoader())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
   for(int i=0;i<150&&(Boolean)field(a,"busy");i++)SystemClock.sleep(100);check(!(Boolean)field(a,"busy"),mode+": initialization completes");
   Object theme=field(a,"u");check(((Boolean)field(theme,"dark"))==mode.equals("dark"),mode+": proxy theme follows shared appearance");
   String[] titles={"代理与去广告","选择节点","连接活动","代理配置"};for(int p=0;p<4;p++){page(p);check(strings().contains(titles[p]),mode+": section opens "+titles[p]);check(!strings().contains("DO_NOT_DISPLAY"),mode+": secrets not rendered");shot(mode+"-"+p);}
   page(0);LinearLayout nav=(LinearLayout)field(a,"nav");runOnMainSync(()->{for(int n=0;n<4;n++){ViewGroup item=(ViewGroup)nav.getChildAt(n);TextView label=(TextView)item.getChildAt(2);check(label.getBottom()<=item.getHeight(),mode+": 1.3x navigation label fits "+n);check(item.getHeight()>=48*c.getResources().getDisplayMetrics().density,mode+": navigation target >=48dp "+n);}});
   runOnMainSync(a::finish);waitForIdleSync();SystemClock.sleep(300);
  }
  invoke(obs,"clear",new Class<?>[0]);((android.database.sqlite.SQLiteOpenHelper)obs).close();prefs.edit().putString("appearance","system").putBoolean("proxyHistory",false).commit();
  b.putString("stream",output+"BICHEN_PROXY_UI_PASS checks="+checks+"\nStorage fixtures and actual UI only; not actual external app traffic.\n");finish(Activity.RESULT_OK,b);
 }catch(Throwable e){b.putString("stream",output+"BICHEN_PROXY_UI_FAIL\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,b);}}
}
