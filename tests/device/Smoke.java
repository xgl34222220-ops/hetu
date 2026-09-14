package bichen.devicecheck;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.zip.ZipFile;

/** Actual installed APK UI and asset tests. State fixtures are explicitly synthetic. */
public final class Smoke extends Instrumentation {
    private int checks;
    private final StringBuilder log=new StringBuilder();
    private Context target;
    private Activity activity;
    private String expectedVersion;
    @Override public void onCreate(Bundle args) { super.onCreate(args);expectedVersion=args.getString("expectedVersion");start(); }
    private void check(boolean value,String detail) { if(!value)throw new AssertionError(detail);checks++;log.append("PASS ").append(detail).append('\n'); }
    private Field field(String name)throws Exception{Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private Object get(String name)throws Exception{return field(name).get(activity);}
    private void call(String name)throws Exception{call(name,new Class<?>[0],new Object[0]);}
    private void call(String name,Class<?>[] types,Object[] args)throws Exception{Method m=activity.getClass().getDeclaredMethod(name,types);m.setAccessible(true);runOnMainSync(()->{try{m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();}
    private String screen(){final StringBuilder s=new StringBuilder();runOnMainSync(()->collect(activity.getWindow().getDecorView(),s));return s.toString();}
    private void collect(View v,StringBuilder b){if(v instanceof TextView)b.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),b);}}
    private View byDescription(View v,String wanted){CharSequence d=v.getContentDescription();if(d!=null&&wanted.contentEquals(d))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=byDescription(g.getChildAt(i),wanted);if(found!=null)return found;}}return null;}
    private View findClassSuffix(View v,String suffix){if(v.getClass().getName().endsWith(suffix))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=findClassSuffix(g.getChildAt(i),suffix);if(found!=null)return found;}}return null;}
    private void shot(String name)throws Exception{SystemClock.sleep(300);Bitmap image=getUiAutomation().takeScreenshot();if(image!=null)try(OutputStream o=new FileOutputStream(new File(target.getFilesDir(),name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,o);}}
    private static String sha(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream i=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=i.read(b))!=-1)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format("%02x",b&255));return s.toString();}
    private void setStatus(JSONObject status)throws Exception{Field s=field("status"),l=field("statusLoaded");runOnMainSync(()->{try{s.set(activity,status);l.setBoolean(activity,true);}catch(Exception e){throw new RuntimeException(e);}});call("showPage",new Class<?>[]{boolean.class},new Object[]{false});}
    @Override public void onStart(){Bundle result=new Bundle();try{
        target=getTargetContext();String pkg=target.getPackageName();
        check(pkg.equals("io.github.xgl34222220.bichen.preview"),"unchanged preview package");
        check(target.getPackageManager().getPackageInfo(pkg,0).versionName.equals(expectedVersion),"installed expected version "+expectedVersion);
        Class<?> installer=Class.forName(pkg+".ModuleInstaller",true,target.getClassLoader());
        JSONObject info=(JSONObject)installer.getMethod("bundledInfo",Context.class).invoke(null,target);File module=(File)installer.getMethod("bundledZip",Context.class).invoke(null,target);
        check(module.getName().equals("Bichen-"+info.getString("version")+"-module.zip"),"export module filename matches its actual metadata");check(sha(module).equals(info.getString("sha256")),"embedded/exported module hash identical");
        try(ZipFile z=new ZipFile(module)){check(z.getEntry("module.prop")!=null&&z.getEntry("bin/bichen")!=null,"export contains module root entries");}
        check(sha((File)installer.getMethod("bundledZip",Context.class).invoke(null,target)).equals(info.getString("sha256")),"repeated export unchanged");
        Drawable icon=target.getResources().getDrawable(target.getApplicationInfo().icon,target.getTheme());Bitmap b=Bitmap.createBitmap(108,108,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,108,108);icon.draw(new Canvas(b));check(b.getPixel(10,54)==0xff146b59&&b.getPixel(54,23)==0xffdff4e8,"retained original green icon rendering");
        activity=startActivitySync(target.getPackageManager().getLaunchIntentForPackage(pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        for(int i=0;i<450;i++){final boolean[] busy={true};runOnMainSync(()->{try{busy[0]=(Boolean)get("busy");}catch(Exception e){throw new RuntimeException(e);}});if(!busy[0])break;SystemClock.sleep(100);}
        check(!activity.isFinishing(),"app launch does not crash");check(!(Boolean)get("busy"),"initial status attempt finishes, no permanent spinner");getUiAutomation();
        String[] pages={"辟尘·测试","应用放行","过滤规则","请求活动"};String[] dockLabels={"首页","应用","规则","活动"};
        final View[] dock={null};runOnMainSync(()->dock[0]=findClassSuffix(activity.getWindow().getDecorView(),"LuoShuDockView"));check(dock[0]!=null,"real floating LuoShu dock exists");
        for(int i=0;i<4;i++){
            final int n=i;final View[] item={null};final boolean[] measurable={false};
            runOnMainSync(()->{item[0]=byDescription(dock[0],dockLabels[n]);measurable[0]=item[0]!=null&&item[0].getHeight()>0&&item[0].getWidth()>0;if(item[0]!=null)item[0].performClick();});
            check(measurable[0],"floating dock destination measurable "+dockLabels[i]);waitForIdleSync();SystemClock.sleep(400);check(screen().contains(pages[i]),"real page opens "+pages[i]);shot("actual-page-"+i);
        }
        runOnMainSync(()->{View home=byDescription(dock[0],"首页");if(home!=null)home.performClick();});waitForIdleSync();
        RequestLogs.run(this, activity, log);
        JSONObject paused=new JSONObject().put("ok",true).put("installed",true).put("enabled",false).put("mounted",false).put("ruleCount",7081);
        runOnMainSync(()->{try{field("homeRefreshNeeded").setBoolean(activity,true);}catch(Exception e){throw new RuntimeException(e);}});setStatus(paused);check(!(Boolean)get("homeRefreshNeeded"),"explicit home render consumes prior structural refresh flag");check(screen().contains("模块保护已暂停"),"fixture: installed paused module not called uninstalled");
        Object scroll=get("scroll");target.getSharedPreferences("bichen",0).edit().putLong("blocked",431).apply();SystemClock.sleep(450);waitForIdleSync();check(scroll==get("scroll"),"counter event updates existing home instead of rebuilding it");
        setStatus(new JSONObject(paused.toString()).put("ok",false).put("enabled",true).put("mounted",true));check(screen().contains("状态待确认")&&!screen().contains("模块保护已开启"),"fixture: exit/error state never shown as active");
        setStatus(new JSONObject(paused.toString()).put("moduleDisabled",true));check(screen().contains("模块已停用"),"fixture: manager disable visible");call("toggleProtection");check(!(Boolean)get("busy")&&((String)get("notice")).contains("Root 管理器"),"fixture: disabled module power does not queue enable");
        setStatus(new JSONObject(paused.toString()).put("moduleRemovalPending",true));check(screen().contains("模块等待卸载"),"fixture: removal visible");setStatus(new JSONObject(paused.toString()).put("pendingReboot",true));check(screen().contains("模块等待重启"),"fixture: pending reboot visible");call("toggleProtection");check(!(Boolean)get("busy"),"fixture: pending update power does not enqueue module writes");
        log.append("BICHEN_DEVICE_PASS checks=").append(checks).append("\nAndroid emulator UI/assets only. No ReSukiSU, OEM hardware or actual ad-block efficacy tested.\n");result.putString("stream",log.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){try{shot("failure");}catch(Throwable ignored){}result.putString("stream",log+"\nBICHEN_DEVICE_FAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
