package bichen.devicecheck;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.os.SystemClock;
import org.json.JSONArray;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;

/** Calls the APK's real log writer with synthetic queries; not a DNS/VPN traffic test. */
public final class RequestLogs {
    private static void check(boolean ok,String what,StringBuilder log){
        if(!ok)throw new AssertionError(what);
        log.append("PASS log-fixture: ").append(what).append('\n');
    }
    private static Field field(Class<?> cls,String name)throws Exception{
        Field f=cls.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static JSONArray entries(SharedPreferences p)throws Exception{return new JSONArray(p.getString("dnsLogs","[]"));}
    public static void run(Instrumentation in,Activity activity,StringBuilder log)throws Exception{
        SharedPreferences p=activity.getSharedPreferences("bichen",0);
        Class<?> cls=Class.forName(activity.getPackageName()+".DnsVpnService",true,activity.getClassLoader());
        Object service=cls.getDeclaredConstructor().newInstance();
        field(cls,"prefs").set(service,p);
        Method record=cls.getDeclaredMethod("record",String.class,String.class,String.class);record.setAccessible(true);
        Method clear=cls.getMethod("clearRequestLogs",android.content.Context.class);
        Method set=cls.getMethod("setRequestLogging",android.content.Context.class,boolean.class);
        try{
            long queries=p.getLong("queries",0);
            set.invoke(null,activity,false);clear.invoke(null,activity);
            record.invoke(service,"disabled.example","allowed","");
            check(entries(p).length()==0,"disabled switch saves no domain",log);
            set.invoke(null,activity,true);record.invoke(service,"one.example","allowed","");
            check(entries(p).length()==1,"enabled writer persists an entry",log);
            check(entries(p).getJSONObject(0).getString("domain").equals("one.example"),"domain preserved",log);
            record.invoke(service,"alias.example","blocked_cname","ad.example");
            check(entries(p).getJSONObject(1).getString("matchedDomain").equals("ad.example"),"CNAME reason preserved",log);
            for(int i=0;i<135;i++)record.invoke(service,"item"+i+".example","cached","");
            check(entries(p).length()==100,"bounded to latest 100 records",log);
            check(entries(p).getJSONObject(0).getString("domain").equals("item35.example"),"oldest evicted in order",log);
            set.invoke(null,activity,false);String before=p.getString("dnsLogs","");
            record.invoke(service,"ignored.example","allowed","");
            check(before.equals(p.getString("dnsLogs","")),"turning off keeps history but stops new writes",log);
            set.invoke(null,activity,true);p.edit().putString("dnsLogs","{broken").commit();
            record.invoke(service,"recovered.example","allowed","");
            check(entries(p).length()==1&&entries(p).getJSONObject(0).getString("domain").equals("recovered.example"),"corrupt JSON cannot permanently stop next append",log);
            check(!p.getString("dnsLogNotice","").isEmpty(),"corruption recovery reported, not silently hidden",log);
            p.edit().putString("dnsLogs","[3,null,{},false]").commit();record.invoke(service,"valid.example","blocked","");
            check(entries(p).length()==1,"invalid history items skipped without losing new query",log);
            clear.invoke(null,activity);
            check(entries(p).length()==0&&!p.contains("dnsLogNotice")&&!p.contains("dnsLogError"),"clear removes records and stale log warnings",log);
            check(p.getLong("queries",0)==queries,"clearing records never resets cumulative request count",log);
            // Keep actual network service stopped. Record delivery/refresh can be tested
            // independently from DNS ingress without pretending to have a Root framework.
            check(!cls.getField("running").getBoolean(null),"tests did not start a VPN or change protection",log);
            in.runOnMainSync(()->{try{((ViewGroup)field(activity.getClass(),"nav").get(activity)).getChildAt(3).performClick();}catch(Exception e){throw new RuntimeException(e);}});
            in.waitForIdleSync();SystemClock.sleep(300);
            AtomicReference<Throwable> writerError=new AtomicReference<>();
            Thread writer=new Thread(()->{try{for(int i=0;i<70;i++){record.invoke(service,"live"+i+".example","allowed","");Thread.sleep(40);}}catch(Throwable e){writerError.set(e);}},"synthetic-log-test");
            writer.start();SystemClock.sleep(950);
            final int[] displayed={0};
            in.runOnMainSync(()->{try{displayed[0]=((BaseAdapter)field(activity.getClass(),"logAdapter").get(activity)).getCount();}catch(Exception e){throw new RuntimeException(e);}});
            boolean ongoing=writer.isAlive();writer.join(6000);
            if(writerError.get()!=null)throw new AssertionError(writerError.get());
            check(ongoing&&displayed[0]>0,"live list refreshes while events keep arriving (no debounce starvation)",log);
            clear.invoke(null,activity);SystemClock.sleep(300);in.waitForIdleSync();
            in.runOnMainSync(()->{try{displayed[0]=((BaseAdapter)field(activity.getClass(),"logAdapter").get(activity)).getCount();}catch(Exception e){throw new RuntimeException(e);}});
            check(displayed[0]==0,"clear refreshes the visible list",log);
            Method empty=activity.getClass().getDeclaredMethod("requestLogEmpty");empty.setAccessible(true);
            check(((String)empty.invoke(activity)).contains("应用保护"),"empty list explains inactive application protection",log);
            log.append("REQUEST_LOG_FIXTURE_PASS checks=16; direct synthetic calls to production writer, not external app traffic\n");
        }finally{
            clear.invoke(null,activity);set.invoke(null,activity,false);
            in.runOnMainSync(()->{try{((ViewGroup)field(activity.getClass(),"nav").get(activity)).getChildAt(0).performClick();}catch(Exception e){throw new RuntimeException(e);}});
            in.waitForIdleSync();
        }
    }
}
