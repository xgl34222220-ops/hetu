package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Shared LuoShu/BaiZe visual bridge for every Bichen screen. */
public final class BichenApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final WeakHashMap<Activity,SkinSession> sessions=new WeakHashMap<>();
    @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);}
    @Override public void onActivityCreated(Activity a,Bundle b){
        if(a instanceof MainActivity){SkinSession s=new SkinSession(a);sessions.put(a,s);a.getWindow().getDecorView().post(s::install);}
        else if(a instanceof ProxyActivity){a.getWindow().getDecorView().post(()->floatProxyDock(a));}
    }
    @Override public void onActivityResumed(Activity a){SkinSession s=sessions.get(a);if(s!=null)s.request();}
    @Override public void onActivityDestroyed(Activity a){SkinSession s=sessions.remove(a);if(s!=null)s.dispose();}
    @Override public void onActivityStarted(Activity a){}@Override public void onActivityPaused(Activity a){}@Override public void onActivityStopped(Activity a){}@Override public void onActivitySaveInstanceState(Activity a,Bundle b){}

    private static void floatProxyDock(Activity a){
        try{
            Field nf=ProxyActivity.class.getDeclaredField("nav");nf.setAccessible(true);Object no=nf.get(a);if(!(no instanceof LuoShuDockView))return;LuoShuDockView nav=(LuoShuDockView)no;ViewParent vp=nav.getParent();if(!(vp instanceof ViewGroup))return;((ViewGroup)vp).removeView(nav);
            View hostView=a.findViewById(android.R.id.content);if(!(hostView instanceof FrameLayout))return;FrameLayout host=(FrameLayout)hostView;
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(a,72),Gravity.BOTTOM);lp.leftMargin=dp(a,20);lp.rightMargin=dp(a,20);lp.bottomMargin=dp(a,12);host.addView(nav,lp);nav.setElevation(dp(a,18));
            nav.setOnApplyWindowInsetsListener((v,insets)->{int bottom=Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.navigationBars()).bottom:insets.getSystemWindowInsetBottom();ViewGroup.LayoutParams raw=v.getLayoutParams();if(raw instanceof FrameLayout.LayoutParams){FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)raw;int wanted=dp(a,12)+bottom;if(p.bottomMargin!=wanted){p.bottomMargin=wanted;v.setLayoutParams(p);}}return insets;});nav.requestApplyInsets();
            Field cf=ProxyActivity.class.getDeclaredField("content");cf.setAccessible(true);Object co=cf.get(a);if(co instanceof FrameLayout){FrameLayout content=(FrameLayout)co;content.setClipToPadding(false);content.setPadding(content.getPaddingLeft(),content.getPaddingTop(),content.getPaddingRight(),dp(a,92));}
        }catch(Throwable ignored){}
    }

    private static int dp(Context c,float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}

    private static final class SkinSession implements ViewTreeObserver.OnGlobalLayoutListener {
        final Activity a;final boolean dark;final int bg,surface,text,muted,accent,soft,danger,divider;
        final WeakHashMap<View,Boolean> styled=new WeakHashMap<>();final WeakHashMap<View,Boolean> rootStyled=new WeakHashMap<>();
        boolean scheduled,disposed;LinearLayout mainDock,lastDockGeometry;LuoShuDockView overlayDock;int lastDockTab=-1;
        SkinSession(Activity a){this.a=a;dark=ProxyUi.isDark(a);int dyn;if(Build.VERSION.SDK_INT>=31)dyn=a.getColor(dark?android.R.color.system_accent1_200:android.R.color.system_accent1_500);else dyn=dark?0xffa9bfff:0xff4f6fd8;bg=dark?0xff111214:mix(0xfff4f6fa,dyn,.07f);surface=dark?0xff1b1c20:mix(0xffffffff,dyn,.018f);text=dark?0xfff1f2f5:0xff16171b;muted=dark?0xffa8abb4:0xff70727c;accent=dyn;soft=mix(surface,accent,dark?.13f:.10f);danger=dark?0xffffb39d:0xffa64d3d;divider=dark?0x24ffffff:0x12000000;}
        void install(){if(disposed)return;Window w=a.getWindow();w.setStatusBarColor(Color.TRANSPARENT);w.setNavigationBarColor(Color.TRANSPARENT);if(Build.VERSION.SDK_INT>=30){w.setDecorFitsSystemWindows(false);View d=w.getDecorView();d.post(()->{WindowInsetsController c=d.getWindowInsetsController();if(c!=null){int appearance=dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;c.setSystemBarsAppearance(appearance,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}});}a.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(this);request();}
        void dispose(){disposed=true;if(overlayDock!=null&&overlayDock.getParent() instanceof ViewGroup)((ViewGroup)overlayDock.getParent()).removeView(overlayDock);View decor=a.getWindow().getDecorView();ViewTreeObserver o=decor.getViewTreeObserver();if(o.isAlive())o.removeOnGlobalLayoutListener(this);}
        @Override public void onGlobalLayout(){request();}
        void request(){if(disposed||scheduled)return;scheduled=true;a.getWindow().getDecorView().postDelayed(()->{scheduled=false;if(disposed)return;styleKnownRoots();styleMainDock();skin(a.getWindow().getDecorView());syncDock();},32);}

        private void styleKnownRoots(){
            styleRoot("shell",v->v.setBackground(new LuoShuSurfaceDrawable(bg,accent,Color.TRANSPARENT,0,dark?.055f:.08f,0,true)));
            styleRoot("banner",v->{if(v instanceof LinearLayout)v.setBackground(new LuoShuSurfaceDrawable(soft,accent,dark?0x18ffffff:0x42ffffff,dp(18),.025f,dark?.04f:.13f,false));});
            styleRoot("heading",v->{if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(text);t.setTextSize(34);t.setTypeface(Typeface.create("sans-serif-black",Typeface.NORMAL));}});
            styleRoot("subtitle",v->{if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(muted);t.setTextSize(12);t.setLineSpacing(0,1);}});
        }
        private interface RootStyle{void apply(View v);}
        private void styleRoot(String field,RootStyle action){try{Field f=MainActivity.class.getDeclaredField(field);f.setAccessible(true);Object o=f.get(a);if(o instanceof View){View v=(View)o;if(!rootStyled.containsKey(v)){rootStyled.put(v,Boolean.TRUE);action.apply(v);}}}catch(Throwable ignored){}}
        private int currentTab(){try{Field f=MainActivity.class.getDeclaredField("tab");f.setAccessible(true);return f.getInt(a);}catch(Throwable e){return 0;}}

        private void styleMainDock(){
            try{
                Field f=MainActivity.class.getDeclaredField("nav");f.setAccessible(true);Object o=f.get(a);if(!(o instanceof LinearLayout))return;mainDock=(LinearLayout)o;
                boolean newDock=mainDock!=lastDockGeometry;
                if(newDock){
                    lastDockGeometry=mainDock;lastDockTab=-1;mainDock.setAlpha(0f);mainDock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    ViewGroup.LayoutParams old=mainDock.getLayoutParams();if(old instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams gone=(LinearLayout.LayoutParams)old;gone.height=0;gone.setMargins(0,0,0,0);mainDock.setLayoutParams(gone);}
                    if(overlayDock!=null&&overlayDock.getParent() instanceof ViewGroup)((ViewGroup)overlayDock.getParent()).removeView(overlayDock);
                    View hostView=a.findViewById(android.R.id.content);if(!(hostView instanceof FrameLayout))return;FrameLayout host=(FrameLayout)hostView;
                    overlayDock=new LuoShuDockView(a,new String[]{"首页","应用","规则","活动"},new String[]{"shield","apps","rules","activity"},currentTab(),accent,muted,surface,dark,index->{try{if(index<mainDock.getChildCount())mainDock.getChildAt(index).performClick();}catch(Throwable ignored){}});
                    FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(72),Gravity.BOTTOM);lp.leftMargin=dp(20);lp.rightMargin=dp(20);lp.bottomMargin=dp(12);host.addView(overlayDock,lp);
                    overlayDock.setOnApplyWindowInsetsListener((v,insets)->{int bottom=Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.navigationBars()).bottom:insets.getSystemWindowInsetBottom();ViewGroup.LayoutParams raw=v.getLayoutParams();if(raw instanceof FrameLayout.LayoutParams){FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)raw;int wanted=dp(12)+bottom;if(p.bottomMargin!=wanted){p.bottomMargin=wanted;v.setLayoutParams(p);}}return insets;});overlayDock.requestApplyInsets();
                }
            }catch(Throwable ignored){}
        }
        private void syncDock(){int selected=currentTab();if(overlayDock!=null&&selected!=lastDockTab){lastDockTab=selected;overlayDock.setSelected(selected,true);}}

        private void skin(View v){if(v==null||v==mainDock||v==overlayDock)return;if(!styled.containsKey(v)){styled.put(v,Boolean.TRUE);styleOne(v);}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)skin(g.getChildAt(i));}}
        private void styleOne(View v){
            if(v==a.getWindow().getDecorView())return;
            if(v instanceof ProgressBar){if(Build.VERSION.SDK_INT>=21)((ProgressBar)v).setIndeterminateTintList(ColorStateList.valueOf(accent));return;}
            if(v instanceof CompoundButton){CompoundButton b=(CompoundButton)v;if(Build.VERSION.SDK_INT>=21)b.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,muted}));}
            if(v instanceof TextView){TextView t=(TextView)v;int c=t.getCurrentTextColor();if(isOldText(c))t.setTextColor(text);else if(isOldMuted(c))t.setTextColor(muted);else if(isOldAccent(c))t.setTextColor(accent);else if(isOldDanger(c))t.setTextColor(danger);if(v instanceof EditText){t.setBackground(new LuoShuSurfaceDrawable(surface,accent,dark?0x14ffffff:0x3affffff,dp(18),.015f,dark?.03f:.09f,false));t.setPadding(dp(16),dp(12),dp(16),dp(12));}else if(t.isClickable()&&Color.alpha(t.getCurrentTextColor())>0&&isNearlyWhite(t.getCurrentTextColor())){t.setBackground(round(accent,20));t.setMinHeight(dp(54));}CharSequence value=t.getText();if(value!=null&&value.length()>80&&!t.isTextSelectable()){t.setMaxLines(3);t.setEllipsize(TextUtils.TruncateAt.END);}return;}
            if(v instanceof IconView){IconView i=(IconView)v;String k=i.kind();i.setColor("chevron".equals(k)||"back".equals(k)||"close".equals(k)?muted:accent);return;}
            if(v instanceof LinearLayout){LinearLayout l=(LinearLayout)v;if(l.getElevation()>.5f){boolean hero=containsIcon(l,"power"),warning=containsText(l,"需要处理"),proxy=containsText(l,"代理与去广告 · Mihomo");int radius=hero?30:warning?20:24;int base=warning?soft:hero?mix(surface,accent,dark?.13f:.10f):surface;l.setBackground(new LuoShuSurfaceDrawable(base,accent,dark?0x16ffffff:0x3cffffff,dp(radius),hero?(dark?.09f:.12f):.018f,hero?(dark?.10f:.22f):(dark?.035f:.10f),false));l.setElevation(dp(hero?8:warning?0:1));if(hero)l.setPadding(dp(20),dp(20),dp(20),dp(20));else if(warning||proxy)l.setPadding(dp(15),dp(13),dp(15),dp(13));if(warning)compactLongText(l,2);}}
            if(v instanceof FrameLayout&&v.getElevation()>.5f){v.setBackground(new LuoShuSurfaceDrawable(surface,accent,dark?0x16ffffff:0x42ffffff,dp(18),.025f,dark?.05f:.14f,false));}
            if(v instanceof ListView){ListView l=(ListView)v;l.setBackgroundColor(Color.TRANSPARENT);l.setClipToPadding(false);}
            if(v instanceof ScrollView){ScrollView s=(ScrollView)v;s.setClipToPadding(false);s.setPadding(s.getPaddingLeft(),s.getPaddingTop(),s.getPaddingRight(),Math.max(s.getPaddingBottom(),dp(102)));}
        }
        private void compactLongText(View v,int lines){if(v instanceof TextView){TextView t=(TextView)v;CharSequence s=t.getText();if(s!=null&&s.length()>45){t.setMaxLines(lines);t.setEllipsize(TextUtils.TruncateAt.END);}}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)compactLongText(g.getChildAt(i),lines);}}
        private boolean containsText(View v,String target){if(v instanceof TextView&&target.contentEquals(((TextView)v).getText()))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(containsText(g.getChildAt(i),target))return true;}return false;}
        private boolean containsIcon(View v,String kind){if(v instanceof IconView)return kind.equals(((IconView)v).kind());if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(containsIcon(g.getChildAt(i),kind))return true;}return false;}
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
