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

/** Dense native design tokens shared by proxy screens. */
final class ProxyUi {
    final Activity a;
    final boolean dark;
    final int bg,surface,text,muted,accent,soft,danger;
    ProxyUi(Activity activity){
        a=activity;dark=isDark(activity);
        bg=dark?0xff101512:0xfff6f7f5;surface=dark?0xff1b231f:0xffffffff;
        text=dark?0xffe8eee9:0xff1e2b25;muted=dark?0xff9eada3:0xff6b7a70;
        accent=dark?0xff86d0bf:0xff2f8578;soft=dark?0xff26342d:0xffedf3ef;
        danger=dark?0xffffb39d:0xffa74d39;
    }
    static boolean isDark(Activity a){String m=a.getSharedPreferences("bichen",0).getString("appearance","system");return"dark".equals(m)||("system".equals(m)&&(a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    int dp(float value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
    GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    Drawable touch(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(dark?0x2886d0bf:0x162f8578),bg(color,radius),bg(Color.WHITE,radius));}
    LinearLayout col(){LinearLayout c=new LinearLayout(a);c.setOrientation(LinearLayout.VERTICAL);return c;}
    LinearLayout row(){LinearLayout c=new LinearLayout(a);c.setGravity(Gravity.CENTER_VERTICAL);return c;}
    void gap(LinearLayout c,int h){c.addView(new View(a),new LinearLayout.LayoutParams(1,dp(h)));}
    TextView text(String value,float size,int color,boolean bold){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(dp(2),1);t.setFontFeatureSettings("kern");if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    LinearLayout card(LinearLayout parent){LinearLayout c=col();c.setPadding(dp(17),dp(15),dp(17),dp(15));c.setBackground(bg(surface,21));if(Build.VERSION.SDK_INT>=21)c.setElevation(dp(dark?0:.7f));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(11);parent.addView(c,p);return c;}
    TextView button(String label,boolean primary,Runnable action){TextView t=text(label,14,primary?Color.WHITE:accent,true);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(50));t.setPadding(dp(16),dp(12),dp(16),dp(12));t.setBackground(touch(primary?accent:soft,17));t.setOnClickListener(v->action.run());return t;}
    FrameLayout icon(String kind,String description,Runnable action){FrameLayout f=new FrameLayout(a);f.setMinimumWidth(dp(46));f.setMinimumHeight(dp(46));f.setBackground(touch(surface,23));f.addView(new IconView(a,kind,text),new FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));f.setContentDescription(description);f.setFocusable(true);f.setOnClickListener(v->action.run());return f;}
    LinearLayout action(LinearLayout parent,String icon,String title,String subtitle,Runnable action){LinearLayout r=row();r.setMinimumHeight(dp(61));r.setPadding(0,dp(9),0,dp(9));r.setBackground(touch(Color.TRANSPARENT,14));FrameLayout well=new FrameLayout(a);well.setBackground(bg(soft,13));well.addView(new IconView(a,icon,accent),new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));r.addView(well,new LinearLayout.LayoutParams(dp(38),dp(38)));LinearLayout labels=col();labels.setPadding(dp(11),0,dp(7),0);labels.addView(text(title,14,text,true));if(subtitle!=null&&!subtitle.isEmpty()){gap(labels,4);labels.addView(text(subtitle,11.5f,muted,false));}r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));r.addView(new IconView(a,"chevron",muted),new LinearLayout.LayoutParams(dp(16),dp(16)));r.setOnClickListener(v->action.run());parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    EditText search(String hint,String initial){EditText e=new EditText(a);e.setSingleLine(true);e.setTextSize(14);e.setTextColor(text);e.setHintTextColor(muted);e.setHint(hint);e.setText(initial);e.setPadding(dp(15),dp(10),dp(15),dp(10));e.setMinHeight(dp(48));e.setBackground(bg(surface,17));e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);return e;}
    TextView chip(String value,boolean selected,Runnable action){TextView t=button(value,false,action);t.setTextSize(12);t.setBackground(touch(selected?soft:Color.TRANSPARENT,15));t.setTextColor(selected?accent:muted);t.setSelected(selected);return t;}
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
