package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

/** 仅处理供OCR使用的副本；实际浮窗、原始截图和屏幕坐标均保持不变。 */
final class CaptureOcrImage {
    /** 直接复制所需区域，避免每次 OCR 为遮挡浮窗而额外复制整张高清屏幕。 */
    static Bitmap crop(Bitmap source,Rect bounds,Rect overlay){
        Bitmap result=android.os.Build.VERSION.SDK_INT>=26&&source.getColorSpace()!=null?
            Bitmap.createBitmap(bounds.width(),bounds.height(),Bitmap.Config.ARGB_8888,source.hasAlpha(),source.getColorSpace()):
            Bitmap.createBitmap(bounds.width(),bounds.height(),Bitmap.Config.ARGB_8888);
        result.setDensity(source.getDensity());result.setHasAlpha(source.hasAlpha());
        try{
            Canvas canvas=new Canvas(result);canvas.drawBitmap(source,bounds,new Rect(0,0,bounds.width(),bounds.height()),null);
            if(overlay!=null){Rect clipped=new Rect(overlay);if(clipped.intersect(bounds)){clipped.offset(-bounds.left,-bounds.top);Paint paint=new Paint();paint.setColor(Color.BLACK);canvas.drawRect(clipped,paint);}}
            return result;
        }catch(RuntimeException|OutOfMemoryError error){result.recycle();throw error;}
    }
    static Bitmap excluding(Bitmap source,Rect overlay){
        if(overlay==null||overlay.isEmpty())return source;
        Rect clipped=new Rect(overlay);
        if(!clipped.intersect(0,0,source.getWidth(),source.getHeight()))return source;
        Bitmap copy=source.copy(Bitmap.Config.ARGB_8888,true);
        Paint paint=new Paint();paint.setColor(Color.BLACK);
        new Canvas(copy).drawRect(clipped,paint);
        return copy;
    }
}
