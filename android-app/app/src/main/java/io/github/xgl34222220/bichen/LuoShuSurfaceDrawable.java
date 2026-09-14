package io.github.xgl34222220.bichen;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small dependency-free optical port of LuoShu's MIUIX background/card highlights. */
final class LuoShuSurfaceDrawable extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int base,accent,border;
    private final float radius,accentAlpha,highlightAlpha;
    private final boolean page;

    LuoShuSurfaceDrawable(int base,int accent,int border,float radiusPx,float accentAlpha,float highlightAlpha,boolean page){
        this.base=base;this.accent=accent;this.border=border;this.radius=radiusPx;this.accentAlpha=accentAlpha;this.highlightAlpha=highlightAlpha;this.page=page;
    }

    @Override public void draw(Canvas canvas){
        Rect b=getBounds();if(b.isEmpty())return;RectF r=new RectF(b);
        paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(base);
        if(page)canvas.drawRect(r,paint);else canvas.drawRoundRect(r,radius,radius,paint);

        float w=r.width(),h=r.height();
        int a=Math.max(0,Math.min(255,Math.round(255f*accentAlpha)));
        int ac=(a<<24)|(accent&0x00ffffff);
        paint.setShader(new RadialGradient(r.right-w*.06f,r.top+h*.02f,Math.max(w*.82f,h*.42f),new int[]{ac,Color.TRANSPARENT},null,Shader.TileMode.CLAMP));
        if(page)canvas.drawRect(r,paint);else canvas.drawRoundRect(r,radius,radius,paint);

        if(page){
            int a2=Math.max(0,Math.min(255,Math.round(255f*accentAlpha*.62f)));
            int ac2=(a2<<24)|(accent&0x00ffffff);
            paint.setShader(new RadialGradient(r.left+w*.03f,r.bottom-h*.16f,Math.max(w,h)*.72f,new int[]{ac2,Color.TRANSPARENT},null,Shader.TileMode.CLAMP));
            canvas.drawRect(r,paint);
        }else if(highlightAlpha>0f){
            int hi=Math.max(0,Math.min(255,Math.round(255f*highlightAlpha)));
            paint.setShader(new LinearGradient(r.left,r.top,r.left,r.top+h*.44f,new int[]{(hi<<24)|0x00ffffff,Color.TRANSPARENT},null,Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r,radius,radius,paint);
        }
        paint.setShader(null);
        if(!page&&Color.alpha(border)>0){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1f,canvas.getDensity()/160f));paint.setColor(border);canvas.drawRoundRect(new RectF(r.left+.5f,r.top+.5f,r.right-.5f,r.bottom-.5f),radius,radius,paint);paint.setStyle(Paint.Style.FILL);}
    }
    @Override public void setAlpha(int alpha){}
    @Override public void setColorFilter(ColorFilter colorFilter){}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
