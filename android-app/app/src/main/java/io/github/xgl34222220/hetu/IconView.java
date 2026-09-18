package io.github.xgl34222220.hetu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** A single 24dp stroke grid keeps navigation and actions visually aligned. */
public final class IconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String kind;
    public IconView(Context context, String kind, int color) {
        super(context); this.kind = kind;
        paint.setColor(color); paint.setStrokeWidth(1.7f);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void setColor(int color){ paint.setColor(color); invalidate(); }
    String kind(){ return kind; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.save();
        float unit = Math.min(getWidth(), getHeight()) / 24f;
        canvas.translate((getWidth()-24*unit)/2, (getHeight()-24*unit)/2); canvas.scale(unit,unit);
        switch (kind) {
            case "back": line(canvas,15,5,8,12);line(canvas,8,12,15,19);break;
            case "globe": canvas.drawCircle(12,12,9,paint);canvas.drawOval(new RectF(8,3,16,21),paint);line(canvas,3,12,21,12);break;
            case "upload": line(canvas,12,21,12,5);line(canvas,6,11,12,5);line(canvas,12,5,18,11);break;
            case "folder": {Path p=new Path();p.moveTo(3,7);p.lineTo(3,4);p.lineTo(10,4);p.lineTo(13,7);p.lineTo(21,7);p.lineTo(21,20);p.lineTo(3,20);p.close();canvas.drawPath(p,paint);break;}
            case "server": canvas.drawRoundRect(new RectF(3,3,21,10),2,2,paint);canvas.drawRoundRect(new RectF(3,14,21,21),2,2,paint);line(canvas,7,6.5f,7.1f,6.5f);line(canvas,7,17.5f,7.1f,17.5f);line(canvas,12,6.5f,17,6.5f);line(canvas,12,17.5f,17,17.5f);break;
            case "more": canvas.drawPoint(5,12,paint);canvas.drawPoint(12,12,paint);canvas.drawPoint(19,12,paint);break;
            case "shield": { Path p=new Path();p.moveTo(12,2.8f);p.lineTo(20,6);p.lineTo(20,11);p.cubicTo(20,16.2f,16.4f,20,12,21.5f);p.cubicTo(7.6f,20,4,16.2f,4,11);p.lineTo(4,6);p.close();canvas.drawPath(p,paint);line(canvas,8.5f,12,11,14.5f);line(canvas,11,14.5f,16,9.5f);break; }
            case "apps": for(int y=0;y<2;y++)for(int x=0;x<2;x++)canvas.drawRoundRect(new RectF(3+x*11,3+y*11,10+x*11,10+y*11),2,2,paint);break;
            case "rules": line(canvas,4,6,20,6);line(canvas,4,12,20,12);line(canvas,4,18,20,18);dot(canvas,8,6);dot(canvas,16,12);dot(canvas,10,18);break;
            case "activity": {Path p=new Path();p.moveTo(2,12);p.lineTo(6,12);p.lineTo(9,5);p.lineTo(14,19);p.lineTo(17,12);p.lineTo(22,12);canvas.drawPath(p,paint);break;}
            case "settings": canvas.drawCircle(12,12,4,paint);for(int i=0;i<8;i++){double a=i*Math.PI/4;line(canvas,(float)(12+7*Math.cos(a)),(float)(12+7*Math.sin(a)),(float)(12+9*Math.cos(a)),(float)(12+9*Math.sin(a)));}canvas.drawCircle(12,12,7,paint);break;
            case "refresh": canvas.drawArc(new RectF(4,4,20,20),-45,290,false,paint);line(canvas,20,4,20,10);line(canvas,14,10,20,10);break;
            case "power": canvas.drawArc(new RectF(4,4,20,20),-45,270,false,paint);line(canvas,12,2,12,12);break;
            case "chevron": line(canvas,9,6,15,12);line(canvas,15,12,9,18);break;
            case "close": line(canvas,6,6,18,18);line(canvas,18,6,6,18);break;
            case "download": line(canvas,12,3,12,15);line(canvas,7,10,12,15);line(canvas,12,15,17,10);line(canvas,4,16,4,21);line(canvas,4,21,20,21);line(canvas,20,21,20,16);break;
            case "search": canvas.drawCircle(10.5f,10.5f,6.5f,paint);line(canvas,15.5f,15.5f,21,21);break;
            case "check": line(canvas,5,12,10,17);line(canvas,10,17,20,6);break;
            case "info": canvas.drawCircle(12,12,9,paint);line(canvas,12,11,12,17);canvas.drawPoint(12,7,paint);break;
            case "clock": canvas.drawCircle(12,12,9,paint);line(canvas,12,6,12,12);line(canvas,12,12,16,14);break;
            case "module": canvas.drawRoundRect(new RectF(4,4,20,20),4,4,paint);line(canvas,8,9,16,9);line(canvas,8,15,16,15);break;
            default: canvas.drawCircle(12,12,7,paint);
        }
        canvas.restore();
    }
    private void line(Canvas c,float x,float y,float x2,float y2){c.drawLine(x,y,x2,y2,paint);}
    private void dot(Canvas c,float x,float y){Paint.Style old=paint.getStyle();paint.setStyle(Paint.Style.FILL);c.drawCircle(x,y,2.8f,paint);paint.setStyle(old);}
}
