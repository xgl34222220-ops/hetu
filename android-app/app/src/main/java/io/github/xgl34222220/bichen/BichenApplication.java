package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Shared visual bridge for the legacy Java screens.
 *
 * Proxy screens already use ProxyUi directly. MainActivity predates the LuoShu/BaiZe
 * component system, so this bridge normalises every newly-created view to the same MIUIX
 * palette, 24dp surfaces and LuoShu dock until that very large screen is split into the
 * shared component layer. It intentionally changes presentation only, never business state.
 */
public final class BichenApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final WeakHashMap<Activity, SkinSession> sessions=new WeakHashMap<>();
    @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);}
    @Override public void onActivityCreated(Activity a,Bundle b){if(a instanceof MainActivity){SkinSession s=new SkinSession(a);sessions.put(a,s);a.getWindow().getDecorView().post(s::install);}}
    @Override public void onActivityResumed(Activity a){SkinSession s=sessions.get(a);if(s!=null)s.request();}
    @Override public void onActivityDestroyed(Activity a){SkinSession s=sessions.remove(a);if(s!=null)s.dispose();}
    @Override public void onActivityStarted(Activity a){}@Override public void onActivityPaused(Activity a){}@Override public void onActivityStopped(Activity a){}@Override public void onActivitySaveInstanceState(Activity a,Bundle b){}

    private static final class SkinSession implements ViewTreeObserver.OnGlobalLayoutListener {
        final Activity a;final boolean dark;final int bg,surface,text,muted,accent,soft,danger,divider;
        final WeakHashMap<View,Boolean> styled=new WeakHashMap<>();boolean scheduled,disposed;LuoShuDockView dock;
        SkinSession(Activity a){this.a=a;dark=ProxyUi.isDark(a);int dyn;if(Build.VERSION.SDK_INT>=31)dyn=a.getColor(dark?android.R.color.system_accent1_200:android.R.color.system_accent1_500);else dyn=dark?0xffa9bfff:0xff4f6fd8;bg=dark?0xff111214:0xfff4f6fa;surface=dark?0xff1b1c20:0xffffffff;text=dark?0xfff1f2f5:0xff16171b;muted=dark?0xffa8abb4:0xff70727c;accent=dyn;soft=mix(surface,accent,dark?.13f:.09f);danger=dark?0xffffb39d:0xffa64d3d;divider=dark?0x24ffffff:0x12000000;}
        void install(){if(disposed)return;Window w=a.getWindow();w.setStatusBarColor(Color.TRANSPARENT);w.setNavigationBarColor(Color.TRANSPARENT);if(Build.VERSION.SDK_INT>=30){w.setDecorFitsSystemWindows(false);View d=w.getDecorView();d.post(()->{WindowInsetsController c=d.getWindowInsetsController();if(c!=null){int appearance=dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;c.setSystemBarsAppearance(appearance,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}});}replaceDock();a.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(this);request();}
        void dispose(){disposed=true;View decor=a.getWindow().getDecorView();ViewTreeObserver o=decor.getViewTreeObserver();if(o.isAlive())o.removeOnGlobalLayoutListener(this);}
        @Override public void onGlobalLayout(){request();}
        void request(){if(disposed||scheduled)return;scheduled=true;a.getWindow().getDecorView().post(()->{scheduled=false;if(disposed)return;replaceDock();skin(a.getWindow().getDecorView());syncDock();});}

        private void replaceDock(){if(dock!=null&&dock.getParent()!=null)return;try{Field f=MainActivity.class.getDeclaredField("nav");f.setAccessible(true);Object o=f.get(a);if(!(o instanceof View))return;View old=(View)o;ViewParent vp=old.getParent();if(!(vp instanceof ViewGroup))return;ViewGroup parent=(ViewGroup)vp;int index=parent.indexOfChild(old);ViewGroup.LayoutParams oldLp=old.getLayoutParams();parent.removeView(old);dock=new LuoShuDockView(a,new String[]{"首页","应用","规则","活动"},new String[]{"shield","apps","rules","activity"},currentTab(),accent,muted,surface,dark,this::selectTab);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(72));lp.setMargins(dp(20),dp(6),dp(20),dp(12));parent.addView(dock,index,lp);old.setVisibility(View.GONE);}catch(Throwable ignored){}}
        private int currentTab(){try{Field f=MainActivity.class.getDeclaredField("tab");f.setAccessible(true);return f.getInt(a);}catch(Throwable e){return 0;}}
        private void selectTab(int index){try{Field f=MainActivity.class.getDeclaredField("tab");f.setAccessible(true);if(f.getInt(a)==index)return;f.setInt(a,index);Method m=MainActivity.class.getDeclaredMethod("showPage",boolean.class);m.setAccessible(true);m.invoke(a,false);request();}catch(Throwable ignored){}}
        private void syncDock(){if(dock!=null)dock.setSelected(currentTab(),true);}

        private void skin(View v){
            if(v==null||v==dock)return;if(!styled.containsKey(v)){styled.put(v,Boolean.TRUE);styleOne(v);}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)skin(g.getChildAt(i));}
        }
        private void styleOne(View v){
            if(v==a.getWindow().getDecorView())return;
            if(v instanceof ProgressBar){if(Build.VERSION.SDK_INT>=21)((ProgressBar)v).setIndeterminateTintList(ColorStateList.valueOf(accent));return;}
            if(v instanceof CompoundButton){CompoundButton b=(CompoundButton)v;if(Build.VERSION.SDK_INT>=21)b.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,muted}));}
            if(v instanceof TextView){TextView t=(TextView)v;int c=t.getCurrentTextColor();if(isOldText(c))t.setTextColor(text);else if(isOldMuted(c))t.setTextColor(muted);else if(isOldAccent(c))t.setTextColor(accent);else if(isOldDanger(c))t.setTextColor(danger);if(v instanceof EditText){t.setBackground(round(surface,18));t.setPadding(dp(16),dp(11),dp(16),dp(11));}else if(t.isClickable()&&Color.alpha(t.getCurrentTextColor())>0&&isNearlyWhite(t.getCurrentTextColor())){t.setBackground(round(accent,17));}return;}
            if(v instanceof IconView){IconView i=(IconView)v;String k=i.kind();i.setColor("chevron".equals(k)||"back".equals(k)||"close".equals(k)?muted:accent);return;}
            if(v instanceof LinearLayout){LinearLayout l=(LinearLayout)v;if(l.getElevation()>.5f&&l.getHeight()!=dp(70)){l.setBackground(round(surface,24));l.setElevation(dp(1));}}
            if(v instanceof ListView){v.setBackgroundColor(Color.TRANSPARENT);}
        }
        private boolean isOldText(int c){return c==0xff22342b||c==0xffe6eee8||c==0xff1e2b25||c==0xfff2f3f5;}
        private boolean isOldMuted(int c){return c==0xff5e7265||c==0xffaab9ae||c==0xff687078||c==0xffa5abb2;}
        private boolean isOldAccent(int c){return c==0xff317c70||c==0xff8dd3c3||c==0xff0a70d8||c==0xff78b7ff;}
        private boolean isOldDanger(int c){return c==0xffac5d45||c==0xffffb6a0||c==0xffb14c3a||c==0xffffa996;}
        private boolean isNearlyWhite(int c){return Color.red(c)>235&&Color.green(c)>235&&Color.blue(c)>235;}
        private GradientDrawable round(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
        private int dp(float v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
        private static int mix(int a,int b,float amount){amount=Math.max(0f,Math.min(1f,amount));int ar=(a>>16)&255,ag=(a>>8)&255,ab=a&255,br=(b>>16)&255,bg=(b>>8)&255,bb=b&255;return 0xff000000|((int)(ar+(br-ar)*amount)<<16)|((int)(ag+(bg-ag)*amount)<<8)|(int)(ab+(bb-ab)*amount);}
    }
}
