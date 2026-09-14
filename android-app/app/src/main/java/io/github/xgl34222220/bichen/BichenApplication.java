package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Shared LuoShu/BaiZe visual bridge for every legacy Java screen. */
public final class BichenApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final WeakHashMap<Activity,SkinSession> sessions=new WeakHashMap<>();
    @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);}
    @Override public void onActivityCreated(Activity a,Bundle b){if(a instanceof MainActivity){SkinSession s=new SkinSession(a);sessions.put(a,s);a.getWindow().getDecorView().post(s::install);}}
    @Override public void onActivityResumed(Activity a){SkinSession s=sessions.get(a);if(s!=null)s.request();}
    @Override public void onActivityDestroyed(Activity a){SkinSession s=sessions.remove(a);if(s!=null)s.dispose();}
    @Override public void onActivityStarted(Activity a){}@Override public void onActivityPaused(Activity a){}@Override public void onActivityStopped(Activity a){}@Override public void onActivitySaveInstanceState(Activity a,Bundle b){}

    private static final class SkinSession implements ViewTreeObserver.OnGlobalLayoutListener {
        final Activity a;final boolean dark;final int bg,surface,text,muted,accent,soft,danger,divider;
        final WeakHashMap<View,Boolean> styled=new WeakHashMap<>();boolean scheduled,disposed;LinearLayout mainDock;
        SkinSession(Activity a){this.a=a;dark=ProxyUi.isDark(a);int dyn;if(Build.VERSION.SDK_INT>=31)dyn=a.getColor(dark?android.R.color.system_accent1_200:android.R.color.system_accent1_500);else dyn=dark?0xffa9bfff:0xff4f6fd8;bg=dark?0xff111214:0xfff4f6fa;surface=dark?0xff1b1c20:0xffffffff;text=dark?0xfff1f2f5:0xff16171b;muted=dark?0xffa8abb4:0xff70727c;accent=dyn;soft=mix(surface,accent,dark?.13f:.09f);danger=dark?0xffffb39d:0xffa64d3d;divider=dark?0x24ffffff:0x12000000;}
        void install(){if(disposed)return;Window w=a.getWindow();w.setStatusBarColor(Color.TRANSPARENT);w.setNavigationBarColor(Color.TRANSPARENT);if(Build.VERSION.SDK_INT>=30){w.setDecorFitsSystemWindows(false);View d=w.getDecorView();d.post(()->{WindowInsetsController c=d.getWindowInsetsController();if(c!=null){int appearance=dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;c.setSystemBarsAppearance(appearance,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}});}a.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(this);request();}
        void dispose(){disposed=true;View decor=a.getWindow().getDecorView();ViewTreeObserver o=decor.getViewTreeObserver();if(o.isAlive())o.removeOnGlobalLayoutListener(this);}
        @Override public void onGlobalLayout(){request();}
        void request(){if(disposed||scheduled)return;scheduled=true;a.getWindow().getDecorView().post(()->{scheduled=false;if(disposed)return;styleKnownRoots();skin(a.getWindow().getDecorView());styleMainDock();});}

        private void styleKnownRoots(){
            try{Field f=MainActivity.class.getDeclaredField("shell");f.setAccessible(true);Object o=f.get(a);if(o instanceof View)((View)o).setBackgroundColor(bg);}catch(Throwable ignored){}
            try{Field f=MainActivity.class.getDeclaredField("banner");f.setAccessible(true);Object o=f.get(a);if(o instanceof LinearLayout)((LinearLayout)o).setBackground(round(soft,18));}catch(Throwable ignored){}
            try{Field f=MainActivity.class.getDeclaredField("heading");f.setAccessible(true);Object o=f.get(a);if(o instanceof TextView){TextView t=(TextView)o;t.setTextColor(text);t.setTextSize(30);t.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));}}catch(Throwable ignored){}
            try{Field f=MainActivity.class.getDeclaredField("subtitle");f.setAccessible(true);Object o=f.get(a);if(o instanceof TextView){TextView t=(TextView)o;t.setTextColor(muted);t.setTextSize(12);}}catch(Throwable ignored){}
        }
        private int currentTab(){try{Field f=MainActivity.class.getDeclaredField("tab");f.setAccessible(true);return f.getInt(a);}catch(Throwable e){return 0;}}
        /** MainActivity keeps its original LinearLayout so old behavior/tests remain real; the visible geometry is the same optical port as LuoShuDockView. */
        private void styleMainDock(){
            try{
                Field f=MainActivity.class.getDeclaredField("nav");f.setAccessible(true);Object o=f.get(a);if(!(o instanceof LinearLayout))return;mainDock=(LinearLayout)o;
                ViewGroup.LayoutParams raw=mainDock.getLayoutParams();if(raw instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)raw;lp.height=dp(72);lp.setMargins(dp(20),dp(6),dp(20),dp(12));mainDock.setLayoutParams(lp);}
                mainDock.setPadding(dp(6),dp(6),dp(6),dp(6));GradientDrawable shell=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{dark?0xff24272d:0xf9ffffff,dark?0xff1b1d22:0xf7fdfdff});shell.setCornerRadius(dp(31));shell.setStroke(Math.max(1,dp(.7f)),dark?0x1cffffff:0x70ffffff);mainDock.setBackground(shell);mainDock.setElevation(dp(18));
                int selected=currentTab();
                for(int i=0;i<mainDock.getChildCount();i++){
                    View child=mainDock.getChildAt(i);if(!(child instanceof LinearLayout))continue;LinearLayout item=(LinearLayout)child;boolean on=i==selected;item.setGravity(Gravity.CENTER);item.setPadding(0,0,0,0);item.setBackground(round(on?withAlpha(accent,dark?0x38:0x28):Color.TRANSPARENT,23));item.setSelected(on);
                    if(item.getChildCount()>0&&item.getChildAt(0) instanceof IconView)((IconView)item.getChildAt(0)).setColor(on?accent:muted);
                    TextView label=findLastText(item);if(label!=null){label.setTextSize(12);label.setTextColor(on?accent:muted);label.setTypeface(Typeface.create("sans-serif",on?Typeface.BOLD:Typeface.NORMAL));label.setGravity(Gravity.CENTER);label.setSingleLine(true);}
                }
            }catch(Throwable ignored){}
        }
        private TextView findLastText(ViewGroup g){for(int i=g.getChildCount()-1;i>=0;i--){View v=g.getChildAt(i);if(v instanceof TextView)return(TextView)v;if(v instanceof ViewGroup){TextView t=findLastText((ViewGroup)v);if(t!=null)return t;}}return null;}

        private void skin(View v){
            if(v==null)return;if(!styled.containsKey(v)){styled.put(v,Boolean.TRUE);styleOne(v);}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)skin(g.getChildAt(i));}
        }
        private void styleOne(View v){
            if(v==a.getWindow().getDecorView()||v==mainDock)return;
            if(v instanceof ProgressBar){if(Build.VERSION.SDK_INT>=21)((ProgressBar)v).setIndeterminateTintList(ColorStateList.valueOf(accent));return;}
            if(v instanceof CompoundButton){CompoundButton b=(CompoundButton)v;if(Build.VERSION.SDK_INT>=21)b.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,muted}));}
            if(v instanceof TextView){TextView t=(TextView)v;int c=t.getCurrentTextColor();if(isOldText(c))t.setTextColor(text);else if(isOldMuted(c))t.setTextColor(muted);else if(isOldAccent(c))t.setTextColor(accent);else if(isOldDanger(c))t.setTextColor(danger);if(v instanceof EditText){t.setBackground(round(surface,18));t.setPadding(dp(16),dp(11),dp(16),dp(11));}else if(t.isClickable()&&Color.alpha(t.getCurrentTextColor())>0&&isNearlyWhite(t.getCurrentTextColor())){t.setBackground(round(accent,17));}return;}
            if(v instanceof IconView){IconView i=(IconView)v;String k=i.kind();i.setColor("chevron".equals(k)||"back".equals(k)||"close".equals(k)?muted:accent);return;}
            if(v instanceof LinearLayout){LinearLayout l=(LinearLayout)v;if(l.getElevation()>.5f){l.setBackground(round(surface,24));l.setElevation(dp(1));}}
            if(v instanceof ListView)v.setBackgroundColor(Color.TRANSPARENT);
        }
        private boolean isOldText(int c){return c==0xff22342b||c==0xffe6eee8||c==0xff1e2b25||c==0xfff2f3f5;}
        private boolean isOldMuted(int c){return c==0xff5e7265||c==0xffaab9ae||c==0xff687078||c==0xffa5abb2;}
        private boolean isOldAccent(int c){return c==0xff317c70||c==0xff8dd3c3||c==0xff0a70d8||c==0xff78b7ff;}
        private boolean isOldDanger(int c){return c==0xffac5d45||c==0xffffb6a0||c==0xffb14c3a||c==0xffffa996;}
        private boolean isNearlyWhite(int c){return Color.red(c)>235&&Color.green(c)>235&&Color.blue(c)>235;}
        private GradientDrawable round(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
        private int dp(float v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
        private static int withAlpha(int color,int alpha){return(alpha<<24)|(color&0x00ffffff);}
        private static int mix(int a,int b,float amount){amount=Math.max(0f,Math.min(1f,amount));int ar=(a>>16)&255,ag=(a>>8)&255,ab=a&255,br=(b>>16)&255,bg=(b>>8)&255,bb=b&255;return 0xff000000|((int)(ar+(br-ar)*amount)<<16)|((int)(ag+(bg-ag)*amount)<<8)|(int)(ab+(bb-ab)*amount);}
    }
}
