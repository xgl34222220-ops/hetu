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

/** BoxProxy-aligned native design tokens shared by proxy screens. */
final class ProxyUi {
    final Activity a;
    final boolean dark;
    final int bg,surface,text,muted,accent,soft,danger,divider;
    ProxyUi(Activity activity){
        a=activity;dark=isDark(activity);
        bg=dark?0xff111315:0xfff4f4f5;
        surface=dark?0xff1c1f22:0xffffffff;
        text=dark?0xfff2f3f5:0xff171a1f;
        muted=dark?0xffa5abb2:0xff687078;
        accent=dark?0xff78b7ff:0xff0a70d8;
        soft=dark?0xff262b30:0xfff0f2f4;
        danger=dark?0xffffa996:0xffb14c3a;
        divider=dark?0x22ffffff:0x10000000;
    }
    static boolean isDark(Activity a){String m=a.getSharedPreferences("bichen",0).getString("appearance","system");return"dark".equals(m)||("system".equals(m)&&(a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    int dp(float value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
    GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    Drawable touch(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(dark?0x2678b7ff:0x180a70d8),bg(color,radius),bg(Color.WHITE,radius));}
    LinearLayout col(){LinearLayout c=new LinearLayout(a);c.setOrientation(LinearLayout.VERTICAL);return c;}
    LinearLayout row(){LinearLayout c=new LinearLayout(a);c.setGravity(Gravity.CENTER_VERTICAL);return c;}
    void gap(LinearLayout c,int h){c.addView(new View(a),new LinearLayout.LayoutParams(1,dp(h)));}
    TextView text(String value,float size,int color,boolean bold){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(dp(1),1);t.setFontFeatureSettings("kern");if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    LinearLayout card(LinearLayout parent){LinearLayout c=col();c.setPadding(dp(18),dp(4),dp(18),dp(4));c.setBackground(bg(surface,24));if(Build.VERSION.SDK_INT>=21)c.setElevation(0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);parent.addView(c,p);return c;}
    TextView button(String label,boolean primary,Runnable action){TextView t=text(label,14.5f,primary?Color.WHITE:accent,true);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(48));t.setPadding(dp(16),dp(11),dp(16),dp(11));t.setBackground(touch(primary?accent:soft,16));t.setOnClickListener(v->action.run());return t;}
    FrameLayout icon(String kind,String description,Runnable action){FrameLayout f=new FrameLayout(a);f.setMinimumWidth(dp(44));f.setMinimumHeight(dp(44));f.setBackground(touch(Color.TRANSPARENT,22));f.addView(new IconView(a,kind,text),new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));f.setContentDescription(description);f.setFocusable(true);f.setOnClickListener(v->action.run());return f;}
    LinearLayout action(LinearLayout parent,String icon,String title,String subtitle,Runnable action){LinearLayout r=row();r.setMinimumHeight(dp(66));r.setPadding(dp(1),dp(7),0,dp(7));r.setBackground(touch(Color.TRANSPARENT,14));if(icon!=null&&!icon.isEmpty()){FrameLayout well=new FrameLayout(a);well.setBackground(bg(soft,14));well.addView(new IconView(a,icon,accent),new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));r.addView(well,new LinearLayout.LayoutParams(dp(40),dp(40)));}LinearLayout labels=col();labels.setPadding(icon==null||icon.isEmpty()?0:dp(12),0,dp(8),0);labels.addView(text(title,15.5f,text,true));if(subtitle!=null&&!subtitle.isEmpty()){gap(labels,5);labels.addView(text(subtitle,12.5f,muted,false));}r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));r.addView(new IconView(a,"chevron",muted),new LinearLayout.LayoutParams(dp(18),dp(18)));r.setOnClickListener(v->action.run());parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    EditText search(String hint,String initial){EditText e=new EditText(a);e.setSingleLine(true);e.setTextSize(14);e.setTextColor(text);e.setHintTextColor(muted);e.setHint(hint);e.setText(initial);e.setPadding(dp(16),dp(10),dp(16),dp(10));e.setMinHeight(dp(48));e.setBackground(bg(surface,18));e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);return e;}
    TextView chip(String value,boolean selected,Runnable action){TextView t=button(value,false,action);t.setTextSize(12.5f);t.setBackground(touch(selected?soft:Color.TRANSPARENT,15));t.setTextColor(selected?accent:muted);t.setSelected(selected);return t;}
    void separator(LinearLayout p){View line=new View(a);line.setBackgroundColor(divider);p.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    void window(LinearLayout shell){
        shell.setBackgroundColor(bg);
        final Window w=a.getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);w.setNavigationBarColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=30){
            w.setDecorFitsSystemWindows(false);
            final View decor=w.getDecorView();
            decor.post(()->{
                WindowInsetsController controller=decor.getWindowInsetsController();
                if(controller!=null){
                    int appearance=dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    controller.setSystemBarsAppearance(appearance,mask);
                }
            });
        }else{
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        }
        shell.setOnApplyWindowInsetsListener((v,i)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets k=i.getInsets(WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,Math.max(b.bottom,k.bottom));}
            else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());
            return i;
        });
    }
}
