package bichen.uitest;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;

/** Real Activity/resources plus explicitly synthetic records fixtures. No simulated VPN. */
public final class Workspace extends Instrumentation {
    private Context c; private Activity a; private int checks; private final StringBuilder log=new StringBuilder();
    private String theme;
    @Override public void onCreate(Bundle b){super.onCreate(b);theme=b.getString("theme","light");start();}
    private void check(boolean v,String detail){if(!v)throw new AssertionError(detail);checks++;log.append("PASS ").append(detail).append('\n');}
    private Object field(String n)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(a);}
    private void setField(String n,Object v)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
    private Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
    private void main(Runnable r){runOnMainSync(r);waitForIdleSync();}
    private void page(int n)throws Exception{main(()->{try{call(a,"choosePage",new Class<?>[]{int.class},n);}catch(Exception e){throw new RuntimeException(e);}});SystemClock.sleep(350);}
    private String texts(){StringBuilder s=new StringBuilder();main(()->collect(a.getWindow().getDecorView(),s));return s.toString();}
    private void collect(View v,StringBuilder s){if(v instanceof TextView)s.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),s);}}
    private void screenshot(String name)throws Exception{Bitmap b=getUiAutomation().takeScreenshot();check(b!=null,"screenshot available "+name);try(OutputStream o=new FileOutputStream(new File(c.getFilesDir(),"workspace-"+theme+"-"+name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,o);}}
    private void waitIdle()throws Exception{for(int i=0;i<120;i++){final boolean[] busy={true};main(()->{try{busy[0]=(Boolean)field("busy");}catch(Exception e){throw new RuntimeException(e);}});if(!busy[0])return;SystemClock.sleep(100);}throw new AssertionError("operation never completed");}
    @Override public void onStart(){Bundle result=new Bundle();try{
        c=getTargetContext();String pkg=c.getPackageName();c.getSharedPreferences("bichen",0).edit().putString("appearance",theme).commit();
        a=startActivitySync(new Intent(c,Class.forName(pkg+".ProxyActivity",true,c.getClassLoader())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitIdle();
        check(!a.isFinishing(),"new proxy workspace opens "+theme);
        for(int n=0;n<4;n++){page(n);check(texts().contains(new String[]{"代理概览","策略与节点","连接活动","代理设置"}[n]),"page heading "+n);final int selected=n;
            main(()->{try{ViewGroup nav=(ViewGroup)field("nav");check(nav.getChildCount()==4,"four workspace tabs");for(int i=0;i<4;i++){View v=nav.getChildAt(i);check(v.isSelected()==(i==selected),"selected state "+i);check(v.getHeight()>=48*c.getResources().getDisplayMetrics().density,"navigation touch height "+i);ViewGroup g=(ViewGroup)v;View icon=g.getChildAt(0),label=g.getChildAt(2);check(Math.abs((icon.getLeft()+icon.getRight())-(label.getLeft()+label.getRight()))<=2,"icon label centers "+i);check(label.getBottom()<=g.getHeight(),"label fits 1.3 font "+i);} }catch(Exception e){throw new RuntimeException(e);}});
            screenshot("page-"+n);
        }
        // Synthetic group data exercises search/rendering only, not a pretend connection.
        JSONObject proxies=new JSONObject().put("日本组",new JSONObject().put("type","Selector").put("now","Tokyo A").put("all",new JSONArray().put("Tokyo A").put("Tokyo B"))).put("自动",new JSONObject().put("type","URLTest").put("now","US").put("all",new JSONArray().put("US")));
        page(1);main(()->{try{setField("proxies",proxies);call(a,"renderRows",new Class<?>[0]);}catch(Exception e){throw new RuntimeException(e);}});check(texts().contains("Tokyo A")&&texts().contains("自动测速"),"fixture: selected member and automatic type visible");
        main(()->{try{((EditText)field("search")).setText("Tokyo");}catch(Exception e){throw new RuntimeException(e);}});check(((ListView)field("list")).getCount()==1,"fixture: member search filters groups");
        page(2);main(()->{try{setField("frozen",true);}catch(Exception e){throw new RuntimeException(e);}});
        JSONObject snap=new JSONObject().put("connections",new JSONArray().put(new JSONObject().put("id","sample-1").put("metadata",new JSONObject().put("host","fixture.example.test").put("network","tcp")).put("rule","Match").put("chains",new JSONArray().put("PROXY"))));
        main(()->{try{setField("traffic",snap);call(a,"renderRows",new Class<?>[0]);}catch(Exception e){throw new RuntimeException(e);}});
        Object before=field("list");main(()->{try{call(a,"renderRows",new Class<?>[0]);}catch(Exception e){throw new RuntimeException(e);}});check(before==field("list"),"fixture: refresh reuses ListView instead of rebuilding page");
        check(texts().contains("fixture.example.test"),"fixture: connection metadata visible");
        main(()->{try{((EditText)field("search")).setText("no-such-domain");}catch(Exception e){throw new RuntimeException(e);}});check(((ListView)field("list")).getCount()==0&&texts().contains("没有匹配项"),"fixture: search empty state is not no-traffic state");
        Class<?> recordType=Class.forName(pkg+".ProxyRecords",true,c.getClassLoader());Method get=recordType.getDeclaredMethod("get",Context.class);get.setAccessible(true);Object records=get.invoke(null,c);
        call(records,"clear",new Class<?>[0]);call(records,"setEnabled",new Class<?>[]{boolean.class},false);long t=(Long)call(records,"ticket",new Class<?>[0]);
        call(records,"ingest",new Class<?>[]{JSONObject.class,long.class},snap,t);check(((JSONArray)call(records,"snapshot",new Class<?>[0])).length()==0,"fixture: disabled observations save no domains");
        call(records,"setEnabled",new Class<?>[]{boolean.class},true);t=(Long)call(records,"ticket",new Class<?>[0]);call(records,"ingest",new Class<?>[]{JSONObject.class,long.class},snap,t);
        JSONArray one=(JSONArray)call(records,"snapshot",new Class<?>[0]);check(one.length()==1&&one.getJSONObject(0).getString("host").equals("fixture.example.test"),"fixture: enabled snapshot produces one observation");
        call(records,"ingest",new Class<?>[]{JSONObject.class,long.class},snap,t);check(((JSONArray)call(records,"snapshot",new Class<?>[0])).length()==1,"fixture: same connection updates rather than duplicates");
        call(records,"clear",new Class<?>[0]);call(records,"ingest",new Class<?>[]{JSONObject.class,long.class},snap,t);check(((JSONArray)call(records,"snapshot",new Class<?>[0])).length()==0,"fixture: clear rejects in-flight stale sample");
        JSONArray many=new JSONArray();for(int i=0;i<360;i++)many.put(new JSONObject().put("id","bounded-"+i).put("privateToken","NEVER_PERSIST").put("metadata",new JSONObject().put("host","host"+i+".test").put("uid",12345).put("destinationIP","192.0.2.1")));
        t=(Long)call(records,"ticket",new Class<?>[0]);call(records,"ingest",new Class<?>[]{JSONObject.class,long.class},new JSONObject().put("connections",many),t);JSONArray capped=(JSONArray)call(records,"snapshot",new Class<?>[0]);check(capped.length()==300,"fixture: observations capped at 300");check(!capped.toString().contains("NEVER_PERSIST")&&!capped.toString().contains("uid"),"fixture: only selected metadata persisted, no guessed UID or arbitrary fields");
        call(records,"flush",new Class<?>[0]);Constructor<?> ctor=recordType.getDeclaredConstructor(Context.class);ctor.setAccessible(true);Object reopened=ctor.newInstance(c);check(((JSONArray)call(reopened,"snapshot",new Class<?>[0])).length()==300,"fixture: observations survive storage reopen");
        call(records,"setEnabled",new Class<?>[]{boolean.class},false);call(records,"ingest",new Class<?>[]{JSONObject.class,long.class},snap,t);check(((JSONArray)call(records,"snapshot",new Class<?>[0])).length()==300,"fixture: turning off retains previous observations");call(records,"clear",new Class<?>[0]);
        check(!Class.forName(pkg+".MihomoVpnService",true,c.getClassLoader()).getField("running").getBoolean(null),"fixtures did not start a VPN or impersonate real traffic");
        log.append("BICHEN_WORKSPACE_PASS theme=").append(theme).append(" checks=").append(checks).append("\nFixtures are synthetic. Physical Android/OEM not tested.\n");result.putString("stream",log.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream",log+"BICHEN_WORKSPACE_FAIL\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
