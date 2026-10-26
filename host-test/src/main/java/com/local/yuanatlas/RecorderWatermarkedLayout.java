package com.local.yuanatlas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.LinearLayout;

/** 水印在表格子控件之后绘制；普通、紧凑、点选及选择回合弹窗都覆盖，触摸仍交给单元格。 */
final class RecorderWatermarkedLayout extends LinearLayout {
    private final String author;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    RecorderWatermarkedLayout(Context context,String author,int color){
        super(context);this.author=author==null?"":author;density=getResources().getDisplayMetrics().density;
        setWatermarkColor(color);paint.setTextAlign(Paint.Align.CENTER);
    }
    // 弹窗打开期间切换主题，水印也跟随新主题，仍保持相同透明度。
    void setWatermarkColor(int color){paint.setColor(color);paint.setAlpha(48);invalidate();}
    @Override protected void dispatchDraw(Canvas canvas){
        super.dispatchDraw(canvas);if(author.isEmpty())return;
        paint.setTextSize(12*density);
        float measured=paint.measureText(author);if(measured>160*density)paint.setTextSize(12*density*160*density/measured);
        for(float y=22*density;y<getHeight()+30*density;y+=58*density){
            for(float x=90*density;x<getWidth()+90*density;x+=190*density){
                canvas.save();canvas.rotate(-18,x,y);canvas.drawText(author,x,y,paint);canvas.restore();
            }
        }
    }
}
