package io.github.xgl34222220.hetu;

import android.animation.*;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.view.animation.*;
import android.widget.*;

/**
 * Dependency-free optical port of LuoShu's MIUIX floating dock.
 * Geometry, translucent shell, moving lens, press scale and spring-like travel mirror LuoShuAppShell.
 */
final class LuoShuDockView extends FrameLayout {
    interface Listener{void onSelected(int index);}
    private final String[] labels,icons;
    private final int accent,muted,surface;
    private final boolean dark;
    private final Listener listener;
    private final View indicator;
    private final LinearLayout items;
    private final TextView[] textViews;
    private final IconView[] iconViews;
    private int selected,innerWidth,itemWidth;
    private Animator travelAnimator;

    LuoShuDockView(Context context,String[] labels,String[] icons,int selected,int accent,int muted,int surface,boolean dark,Listener listener){
        super(context);this.labels=labels;this.icons=icons;this.selected=Math.max(0,Math.min(labels.length-1,selected));this.accent=accent;this.muted=muted;this.surface=surface;this.dark=dark;this.listener=listener;
        setClipChildren(false);setClipToPadding(false);setPadding(dp(6),dp(6),dp(6),dp(6));setWillNotDraw(false);
        // Keep these optical values in lock-step with LuoShu MiuixAppDock.
        android.graphics.drawable.GradientDrawable opticalMarker=new android.graphics.drawable.GradientDrawable();opticalMarker.setCornerRadius(dp(31));
        setBackground(new LuoShuSurfaceDrawable(dark?0xb81b1d22:0x82ffffff,accent,dark?0x2affffff:0x76ffffff,dp(31),dark?.045f:.035f,dark?.08f:.23f,false));
        setElevation(dp(18));

        indicator=new View(context);indicator.setBackground(new LuoShuSurfaceDrawable(withAlpha(accent,dark?0x45:0x2a),accent,dark?0x34ffffff:0x82ffffff,dp(23),dark?.07f:.06f,dark?.12f:.22f,false));indicator.setElevation(dp(4));
        addView(indicator,new FrameLayout.LayoutParams(1,dp(60),Gravity.LEFT|Gravity.TOP));

        items=new LinearLayout(context);items.setOrientation(LinearLayout.HORIZONTAL);items.setGravity(Gravity.CENTER_VERTICAL);items.setClipChildren(false);items.setClipToPadding(false);addView(items,new FrameLayout.LayoutParams(-1,dp(60),Gravity.TOP));
        textViews=new TextView[labels.length];iconViews=new IconView[labels.length];
        for(int i=0;i<labels.length;i++){
            final int index=i;LinearLayout item=new LinearLayout(context);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);item.setClickable(true);item.setFocusable(true);item.setContentDescription(labels[i]);item.setBackgroundColor(Color.TRANSPARENT);
            IconView iv=new IconView(context,icons[i],i==this.selected?accent:muted);iconViews[i]=iv;item.addView(iv,new LinearLayout.LayoutParams(dp(22),dp(22)));
            Space gap=new Space(context);item.addView(gap,new LinearLayout.LayoutParams(1,dp(3)));
            TextView tv=new TextView(context);tv.setText(labels[i]);tv.setTextSize(12);tv.setTextColor(i==this.selected?accent:muted);tv.setGravity(Gravity.CENTER);tv.setSingleLine(true);tv.setIncludeFontPadding(false);tv.setTypeface(Typeface.create("sans-serif",i==this.selected?Typeface.BOLD:Typeface.NORMAL));textViews[i]=tv;item.addView(tv,new LinearLayout.LayoutParams(-1,dp(18)));
            item.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){v.animate().scaleX(.92f).scaleY(.92f).setDuration(90).setInterpolator(new DecelerateInterpolator()).start();}else if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){v.animate().scaleX(1f).scaleY(1f).setDuration(210).setInterpolator(new OvershootInterpolator(.55f)).start();}return false;});
            item.setOnClickListener(v->{if(index==this.selected)return;setSelected(index,true);if(this.listener!=null)this.listener.onSelected(index);});
            items.addView(item,new LinearLayout.LayoutParams(0,dp(60),1));
        }
    }

    void setSelected(int index,boolean animate){index=Math.max(0,Math.min(labels.length-1,index));int old=selected;selected=index;for(int i=0;i<labels.length;i++){boolean on=i==selected;textViews[i].setTextColor(on?accent:muted);textViews[i].setTypeface(Typeface.create("sans-serif",on?Typeface.BOLD:Typeface.NORMAL));iconViews[i].setColor(on?accent:muted);}positionIndicator(animate&&old!=selected);}
    int getSelected(){return selected;}

    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);innerWidth=Math.max(0,w-getPaddingLeft()-getPaddingRight());itemWidth=labels.length==0?0:innerWidth/labels.length;FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)indicator.getLayoutParams();lp.width=Math.max(dp(1),itemWidth-dp(8));lp.height=dp(60);lp.leftMargin=getPaddingLeft()+dp(4);lp.topMargin=getPaddingTop();indicator.setLayoutParams(lp);positionIndicator(false);}

    private void positionIndicator(boolean animate){
        if(itemWidth<=0)return;float base=getPaddingLeft()+dp(4);float target=selected*itemWidth;
        if(travelAnimator!=null)travelAnimator.cancel();
        if(!animate){indicator.setTranslationX(target);indicator.setScaleX(1f);indicator.setScaleY(1f);return;}
        float start=indicator.getTranslationX();float direction=Math.signum(target-start);
        ValueAnimator move=ValueAnimator.ofFloat(start,target);move.setDuration(310);move.setInterpolator(new OvershootInterpolator(.52f));move.addUpdateListener(a->indicator.setTranslationX((Float)a.getAnimatedValue()));
        ObjectAnimator stretchX=ObjectAnimator.ofFloat(indicator,View.SCALE_X,1f,1.12f,1.035f,1f);stretchX.setDuration(320);stretchX.setInterpolator(new DecelerateInterpolator());
        ObjectAnimator stretchY=ObjectAnimator.ofFloat(indicator,View.SCALE_Y,1f,.955f,1f);stretchY.setDuration(260);stretchY.setInterpolator(new DecelerateInterpolator());
        if(direction<0f)indicator.setPivotX(indicator.getWidth());else indicator.setPivotX(0f);indicator.setPivotY(indicator.getHeight()/2f);
        AnimatorSet set=new AnimatorSet();set.playTogether(move,stretchX,stretchY);travelAnimator=set;set.start();
    }

    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static int withAlpha(int color,int alpha){return(alpha<<24)|(color&0x00ffffff);}
}
