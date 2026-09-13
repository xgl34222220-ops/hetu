package io.github.xgl34222220.bichen;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.Build;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** Shared native design tokens, accessible touch areas and inset handling. */
final class ProxyUi {
    final Activity a;
    final boolean dark;
    final int bg, surface, text, muted, accent, soft, danger;
    ProxyUi(Activity activity) {
        a=activity; dark=isDark(activity);
        bg=dark?0xff121714:0xfff4f5f2; surface=dark?0xff1d2721:0xfffefefd;
        text=dark?0xffe6eee8:0xff22342b; muted=dark?0xffaab9ae:0xff5e7265;
        accent=dark?0xff8dd3c3:0xff317c70; soft=dark?0xff283930:0xffe8efea;
        danger=dark?0xffffb6a0:0xffa44e39;
    }
    static boolean isDark(Activity a) {
        String m=a.getSharedPreferences("bichen",0).getString("appearance","system");
        return "dark".equals(m)||("system".equals(m)&&(a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
    }
    int dp(float value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
    GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    Drawable touch(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(dark?0x308dd3c3:0x18317c70),bg(color,radius),bg(Color.WHITE,radius));}
    LinearLayout col(){LinearLayout c=new LinearLayout(a);c.setOrientation(LinearLayout.VERTICAL);return c;}
    LinearLayout row(){LinearLayout c=new LinearLayout(a);c.setGravity(Gravity.CENTER_VERTICAL);return c;}
    void gap(LinearLayout c,int h){c.addView(new View(a),new LinearLayout.LayoutParams(1,dp(h)));}
    TextView text(String value,float size,int color,boolean bold){
        TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(3),1);t.setFontFeatureSettings("kern");
        if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;
    }
    LinearLayout card(LinearLayout parent){
        LinearLayout c=col();c.setPadding(dp(20),dp(19),dp(20),dp(19));
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{surface,dark?0xff202c24:0xfffafcf9});g.setCornerRadius(dp(26));c.setBackground(g);c.setElevation(dp(dark?0:1.2f));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);parent.addView(c,p);return c;
    }
    TextView button(String label,boolean primary,Runnable action){
        TextView t=text(label,14,primary?(dark?0xff163b31:Color.WHITE):accent,true);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(50));t.setPadding(dp(16),dp(13),dp(16),dp(13));
        t.setBackground(touch(primary?accent:soft,18));t.setOnClickListener(v->action.run());return t;
    }
    FrameLayout icon(String kind,String description,Runnable action){
        FrameLayout f=new FrameLayout(a);f.setMinimumWidth(dp(48));f.setMinimumHeight(dp(48));f.setBackground(touch(surface,24));
        f.addView(new IconView(a,kind,text),new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));f.setContentDescription(description);f.setFocusable(true);f.setOnClickListener(v->action.run());return f;
    }
    LinearLayout action(LinearLayout parent,String icon,String title,String subtitle,Runnable action){
        LinearLayout r=row();r.setMinimumHeight(dp(64));r.setPadding(0,dp(12),0,dp(12));r.setBackground(touch(Color.TRANSPARENT,16));
        FrameLayout well=new FrameLayout(a);well.setBackground(bg(soft,14));well.addView(new IconView(a,icon,accent),new FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));r.addView(well,new LinearLayout.LayoutParams(dp(40),dp(40)));
        LinearLayout labels=col();labels.setPadding(dp(12),0,dp(8),0);labels.addView(text(title,14,text,true));
        if(subtitle!=null&&!subtitle.isEmpty()){gap(labels,5);labels.addView(text(subtitle,12,muted,false));}
        r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));r.addView(new IconView(a,"chevron",muted),new LinearLayout.LayoutParams(dp(16),dp(16)));r.setOnClickListener(v->action.run());parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;
    }
    EditText search(String hint,String initial){
        EditText e=new EditText(a);e.setSingleLine(true);e.setTextSize(14);e.setTextColor(text);e.setHintTextColor(muted);e.setHint(hint);e.setText(initial);
        e.setPadding(dp(16),dp(11),dp(16),dp(11));e.setMinHeight(dp(50));e.setBackground(bg(surface,19));e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);return e;
    }
    TextView chip(String value,boolean selected,Runnable action){TextView t=button(value,false,action);t.setTextSize(12);t.setBackground(touch(selected?soft:Color.TRANSPARENT,16));t.setTextColor(selected?accent:muted);t.setSelected(selected);return t;}
    void window(LinearLayout shell){
        shell.setBackgroundColor(bg);a.getWindow().getDecorView();a.getWindow().setStatusBarColor(Color.TRANSPARENT);a.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=30){a.getWindow().setDecorFitsSystemWindows(false);a.getWindow().getInsetsController().setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}
        else a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        shell.setOnApplyWindowInsetsListener((v,i)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets k=i.getInsets(WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,Math.max(b.bottom,k.bottom));}
            else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());
            return i;
        });
    }
}
