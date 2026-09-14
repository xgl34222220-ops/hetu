package io.github.xgl34222220.bichen;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;

/** Native optical port of LuoShu/BaiZe's four-item floating MIUIX dock.
 * The real Compose apps use RuntimeShader/Haze for refraction; this native fallback keeps
 * the same geometry, spacing, indicator proportions and motion without faking background blur.
 */
final class LuoShuDockView extends FrameLayout {
    interface Listener{void onSelected(int index);}
    private final String[] labels,icons;
    private final int accent,muted,surface;
    private final boolean dark;
    private final Listener listener;
    private final View indicator;
    private final LinearLayout items;
    private TextView[] textViews;
    private IconView[] iconViews;
    private int selected;
    private int innerWidth,itemWidth;

    LuoShuDockView(Context context,String[] labels,String[] icons,int selected,int accent,int muted,int surface,boolean dark,Listener listener){
        super(context);this.labels=labels;this.icons=icons;this.selected=Math.max(0,Math.min(labels.length-1,selected));this.accent=accent;this.muted=muted;this.surface=surface;this.dark=dark;this.listener=listener;
        setClipChildren(false);setClipToPadding(false);setPadding(dp(6),dp(6),dp(6),dp(6));
        GradientDrawable shell=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{dark?0xff24272d:0xf9ffffff,dark?0xff1b1d22:0xf7fdfdff});
        shell.setCornerRadius(dp(31));shell.setStroke(Math.max(1,dp(.7f)),dark?0x1cffffff:0x70ffffff);setBackground(shell);setElevation(dp(18));
        indicator=new View(context);GradientDrawable pill=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{withAlpha(accent,dark?0x3b:0x2d),withAlpha(accent,dark?0x25:0x1d)});pill.setCornerRadius(dp(23));pill.setStroke(dp(1),dark?0x2cffffff:0x7affffff);indicator.setBackground(pill);indicator.setElevation(dp(3));addView(indicator,new FrameLayout.LayoutParams(1,dp(60),Gravity.LEFT|Gravity.TOP));
        items=new LinearLayout(context);items.setOrientation(LinearLayout.HORIZONTAL);items.setGravity(Gravity.CENTER_VERTICAL);addView(items,new FrameLayout.LayoutParams(-1,dp(60),Gravity.TOP));
        textViews=new TextView[labels.length];iconViews=new IconView[labels.length];
        for(int i=0;i<labels.length;i++){final int index=i;LinearLayout item=new LinearLayout(context);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);item.setClickable(true);item.setFocusable(true);item.setContentDescription(labels[i]);IconView iv=new IconView(context,icons[i],i==this.selected?accent:muted);iconViews[i]=iv;item.addView(iv,new LinearLayout.LayoutParams(dp(22),dp(22)));Space gap=new Space(context);item.addView(gap,new LinearLayout.LayoutParams(1,dp(3)));TextView tv=new TextView(context);tv.setText(labels[i]);tv.setTextSize(12);tv.setTextColor(i==this.selected?accent:muted);tv.setGravity(Gravity.CENTER);tv.setSingleLine(true);tv.setIncludeFontPadding(false);tv.setTypeface(Typeface.create("sans-serif",i==this.selected?Typeface.BOLD:Typeface.NORMAL));textViews[i]=tv;item.addView(tv,new LinearLayout.LayoutParams(-1,dp(18)));item.setOnClickListener(v->{if(index==this.selected)return;setSelected(index,true);if(this.listener!=null)this.listener.onSelected(index);});items.addView(item,new LinearLayout.LayoutParams(0,dp(60),1));}
    }
    void setSelected(int index,boolean animate){index=Math.max(0,Math.min(labels.length-1,index));int old=selected;selected=index;for(int i=0;i<labels.length;i++){boolean on=i==selected;textViews[i].setTextColor(on?accent:muted);textViews[i].setTypeface(Typeface.create("sans-serif",on?Typeface.BOLD:Typeface.NORMAL));iconViews[i].setColor(on?accent:muted);}positionIndicator(animate&&old!=selected);}
    int getSelected(){return selected;}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);innerWidth=Math.max(0,w-getPaddingLeft()-getPaddingRight());itemWidth=labels.length==0?0:innerWidth/labels.length;FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)indicator.getLayoutParams();lp.width=Math.max(dp(1),itemWidth-dp(8));lp.height=dp(60);lp.leftMargin=getPaddingLeft()+dp(4);lp.topMargin=getPaddingTop();indicator.setLayoutParams(lp);positionIndicator(false);}
    private void positionIndicator(boolean animate){if(itemWidth<=0)return;float target=getPaddingLeft()+dp(4)+selected*itemWidth;if(animate){ObjectAnimator a=ObjectAnimator.ofFloat(indicator,"translationX",indicator.getTranslationX(),target-(getPaddingLeft()+dp(4)));a.setDuration(250);a.setInterpolator(new DecelerateInterpolator(1.65f));a.start();indicator.animate().scaleX(1.06f).setDuration(90).withEndAction(()->indicator.animate().scaleX(1f).setDuration(180).start()).start();}else indicator.setTranslationX(target-(getPaddingLeft()+dp(4)));}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static int withAlpha(int color,int alpha){return (alpha<<24)|(color&0x00ffffff);}
}
