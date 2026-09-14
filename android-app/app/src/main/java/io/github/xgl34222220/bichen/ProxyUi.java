package io.github.xgl34222220.bichen;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.widget.*;

/** LuoShu MIUIX visual tokens shared by Bichen proxy screens. */
final class ProxyUi {
    final Activity a;
    final boolean dark;
    final int bg,surface,elevated,text,muted,accent,soft,danger,divider;

    ProxyUi(Activity activity){
        a=activity;dark=isDark(activity);
        int seed=resolveAccent(activity,dark);
        // Same optical baseline as LuoShuTheme: F4F6FA, quiet cards, dynamic Monet accent.
        bg=dark?0xff111214:blend(0xfff4f6fa,seed,.07f);
        surface=dark?0xff1b1c20:blend(0xffffffff,seed,.018f);
        elevated=dark?blend(0xff1f2024,seed,.12f):blend(0xffffffff,seed,.13f);
        text=dark?0xfff1f2f5:0xff16171b;
        muted=dark?0xffa8abb4:0xff70727c;
        accent=seed;
        soft=dark?blend(surface,seed,.16f):blend(0xfff4f6fa,seed,.12f);
        danger=dark?0xffffb39d:0xffa64d3d;
        divider=dark?0x20ffffff:0x0f000000;
    }

    static boolean isDark(Activity a){
        String m=a.getSharedPreferences("bichen",0).getString("appearance","system");
        return "dark".equals(m)||("system".equals(m)&&(a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
    }
    private static int resolveAccent(Activity a,boolean dark){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
            try{return a.getResources().getColor(dark?android.R.color.system_accent1_300:android.R.color.system_accent1_500,a.getTheme());}catch(Throwable ignored){}
        }
        return dark?0xffa9bfff:0xff4f6fd8;
    }
    private static int blend(int from,int to,float t){
        t=Math.max(0f,Math.min(1f,t));int aa=Math.round(Color.alpha(from)+(Color.alpha(to)-Color.alpha(from))*t);int r=Math.round(Color.red(from)+(Color.red(to)-Color.red(from))*t);int g=Math.round(Color.green(from)+(Color.green(to)-Color.green(from))*t);int b=Math.round(Color.blue(from)+(Color.blue(to)-Color.blue(from))*t);return Color.argb(aa,r,g,b);
    }

    int dp(float value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
    GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    GradientDrawable outlinedBg(int color,int radius,int stroke){GradientDrawable d=bg(color,radius);d.setStroke(dp(1),stroke);return d;}
    Drawable touch(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(dark?0x28ffffff:0x163f6fd8),bg(color,radius),bg(Color.WHITE,radius));}
    Drawable glassDock(){return new LuoShuSurfaceDrawable(dark?0xb81b1d22:0x82ffffff,accent,dark?0x2affffff:0x76ffffff,dp(31),dark?.045f:.035f,dark?.08f:.23f,false);}
    LinearLayout col(){LinearLayout c=new LinearLayout(a);c.setOrientation(LinearLayout.VERTICAL);return c;}
    LinearLayout row(){LinearLayout c=new LinearLayout(a);c.setGravity(Gravity.CENTER_VERTICAL);return c;}
    void gap(LinearLayout c,int h){c.addView(new View(a),new LinearLayout.LayoutParams(1,dp(h)));}
    TextView text(String value,float size,int color,boolean bold){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(dp(1),1);t.setFontFeatureSettings("kern");t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));return t;}

    LinearLayout card(LinearLayout parent){
        LinearLayout c=col();c.setPadding(dp(18),dp(16),dp(18),dp(16));c.setBackground(new LuoShuSurfaceDrawable(surface,accent,dark?0x14ffffff:0x36ffffff,dp(24),dark?.018f:.018f,dark?.035f:.11f,false));
        if(Build.VERSION.SDK_INT>=21)c.setElevation(dp(1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);parent.addView(c,p);return c;
    }
    LinearLayout emphasizedCard(LinearLayout parent){
        LinearLayout c=col();c.setPadding(dp(20),dp(19),dp(20),dp(19));c.setBackground(new LuoShuSurfaceDrawable(elevated,accent,dark?0x20ffffff:0x52ffffff,dp(30),dark?.08f:.11f,dark?.08f:.22f,false));if(Build.VERSION.SDK_INT>=21)c.setElevation(dp(8));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);parent.addView(c,p);return c;
    }
    TextView button(String label,boolean primary,Runnable action){TextView t=text(label,14,primary?Color.WHITE:accent,true);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(54));t.setPadding(dp(16),dp(13),dp(16),dp(13));t.setBackground(touch(primary?accent:soft,20));t.setOnClickListener(v->action.run());return t;}
    FrameLayout icon(String kind,String description,Runnable action){FrameLayout f=new FrameLayout(a);f.setMinimumWidth(dp(50));f.setMinimumHeight(dp(50));f.setBackground(new LuoShuSurfaceDrawable(elevated,accent,dark?0x18ffffff:0x40ffffff,dp(16),.025f,dark?.05f:.14f,false));if(Build.VERSION.SDK_INT>=21)f.setElevation(dp(4));f.addView(new IconView(a,kind,text),new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));f.setContentDescription(description);f.setFocusable(true);f.setOnClickListener(v->action.run());return f;}
    LinearLayout action(LinearLayout parent,String icon,String title,String subtitle,Runnable action){
        LinearLayout r=row();r.setMinimumHeight(dp(62));r.setPadding(0,dp(7),0,dp(7));r.setBackground(touch(Color.TRANSPARENT,18));
        if(icon!=null&&!icon.isEmpty()){FrameLayout well=new FrameLayout(a);well.setBackground(new LuoShuSurfaceDrawable(soft,accent,dark?0x10ffffff:0x30ffffff,dp(14),.018f,dark?.03f:.10f,false));well.addView(new IconView(a,icon,accent),new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));r.addView(well,new LinearLayout.LayoutParams(dp(40),dp(40)));}
        LinearLayout labels=col();labels.setPadding(icon==null||icon.isEmpty()?0:dp(12),0,dp(8),0);labels.addView(text(title,15,text,true));if(subtitle!=null&&!subtitle.isEmpty()){gap(labels,3);labels.addView(text(subtitle,12,muted,false));}
        r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));r.addView(new IconView(a,"chevron",muted),new LinearLayout.LayoutParams(dp(17),dp(17)));r.setOnClickListener(v->action.run());parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;
    }
    EditText search(String hint,String initial){EditText e=new EditText(a);e.setSingleLine(true);e.setTextSize(14);e.setTextColor(text);e.setHintTextColor(muted);e.setHint(hint);e.setText(initial);e.setPadding(dp(16),dp(12),dp(16),dp(12));e.setMinHeight(dp(50));e.setBackground(new LuoShuSurfaceDrawable(surface,accent,dark?0x12ffffff:0x36ffffff,dp(18),.015f,dark?.025f:.09f,false));e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);return e;}
    TextView chip(String value,boolean selected,Runnable action){TextView t=button(value,false,action);t.setTextSize(12);t.setMinHeight(dp(42));t.setBackground(touch(selected?soft:Color.TRANSPARENT,17));t.setTextColor(selected?accent:muted);t.setSelected(selected);return t;}
    void separator(LinearLayout p){View line=new View(a);line.setBackgroundColor(divider);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.leftMargin=dp(2);lp.rightMargin=dp(2);p.addView(line,lp);}
    void enter(View v){v.setAlpha(.01f);v.setTranslationY(dp(10));v.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}

    void window(LinearLayout shell){
        shell.setBackground(new LuoShuSurfaceDrawable(bg,accent,Color.TRANSPARENT,0,.08f,0,true));final Window w=a.getWindow();w.setStatusBarColor(Color.TRANSPARENT);w.setNavigationBarColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=30){w.setDecorFitsSystemWindows(false);final View decor=w.getDecorView();decor.post(()->{WindowInsetsController controller=decor.getWindowInsetsController();if(controller!=null){int appearance=dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;controller.setSystemBarsAppearance(appearance,mask);}});}
        else w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        shell.setOnApplyWindowInsetsListener((v,i)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets k=i.getInsets(WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,Math.max(b.bottom,k.bottom));}else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
    }
}
