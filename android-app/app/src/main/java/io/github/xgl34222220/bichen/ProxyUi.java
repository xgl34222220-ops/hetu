package io.github.xgl34222220.bichen;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Shared native visual tokens; no WebView, image fonts, or changes to the launcher icon. */
final class ProxyUi {
    final Activity a; final boolean dark;
    final int bg, surface, ink, muted, accent, soft, danger;
    ProxyUi(Activity activity) {
        a=activity; String mode=a.getSharedPreferences("bichen",0).getString("appearance","system");
        dark=mode.equals("dark") || mode.equals("system") && (a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        bg=dark?0xff121b17:0xfff3f5f1; surface=dark?0xff202e26:0xfffcfdfb;
        ink=dark?0xffe5eee8:0xff223a30; muted=dark?0xffa5b7ac:0xff60766a;
        accent=dark?0xff99dfc5:0xff216955; soft=dark?0xff2c4437:0xffe2eee5; danger=dark?0xffffbda7:0xff9d4936;
    }
    int dp(float n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    LinearLayout col(){LinearLayout v=new LinearLayout(a);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(a);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    void gap(LinearLayout p,int h){p.addView(new View(a),new LinearLayout.LayoutParams(1,dp(h)));}
    TextView text(String s,float size,int color,boolean strong){TextView v=new TextView(a);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setLineSpacing(dp(3),1);if(strong)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    Drawable touch(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(dark?0x2699dfc5:0x20216955),round(color,radius),round(Color.WHITE,radius));}
    LinearLayout panel(LinearLayout p){LinearLayout v=col();pad(v,20,20);GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{surface,dark?0xff1c2922:0xfff6faf5});d.setCornerRadius(dp(25));v.setBackground(d);v.setElevation(dp(dark?0:1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);p.addView(v,lp);return v;}
    TextView badge(String s){TextView t=text(s,11,accent,true);pad(t,10,6);t.setBackground(round(soft,12));return t;}
    TextView button(String label,boolean primary,Runnable action){TextView v=text(label,14,primary?(dark?0xff153629:Color.WHITE):accent,true);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(50));pad(v,16,13);v.setBackground(touch(primary?accent:soft,18));v.setFocusable(true);v.setOnClickListener(w->action.run());return v;}
    void button(LinearLayout p,String label,boolean primary,Runnable action){p.addView(button(label,primary,action),new LinearLayout.LayoutParams(-1,-2));}
    FrameLayout icon(String kind,String description,Runnable action){FrameLayout f=new FrameLayout(a);f.setMinimumHeight(dp(48));f.setMinimumWidth(dp(48));f.setBackground(touch(surface,24));f.addView(new IconView(a,kind,accent),new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));f.setContentDescription(description);f.setFocusable(true);f.setOnClickListener(v->action.run());return f;}
    void action(LinearLayout p,String kind,String title,String sub,Runnable action){LinearLayout r=row();pad(r,0,12);r.setMinimumHeight(dp(64));FrameLayout i=new FrameLayout(a);i.setBackground(round(soft,15));i.addView(new IconView(a,kind,accent),new FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));r.addView(i,new LinearLayout.LayoutParams(dp(42),dp(42)));LinearLayout c=col();c.addView(text(title,14,ink,true));gap(c,5);c.addView(text(sub,12,muted,false));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(12),0,dp(8),0);r.addView(c,lp);r.addView(new IconView(a,"chevron",muted),new LinearLayout.LayoutParams(dp(18),dp(18)));r.setBackground(touch(Color.TRANSPARENT,14));r.setOnClickListener(v->action.run());r.setFocusable(true);p.addView(r,new LinearLayout.LayoutParams(-1,-2));}
    EditText search(String hint){EditText e=new EditText(a);e.setSingleLine(true);e.setTextSize(14);e.setTextColor(ink);e.setHintTextColor(muted);e.setHint(hint);e.setMinHeight(dp(50));pad(e,16,10);e.setBackground(round(soft,17));e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);return e;}
}
