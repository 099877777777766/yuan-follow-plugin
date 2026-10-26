package com.local.yuanatlas;

import android.graphics.*;

/** 仅供串行截图线程使用：先在图形层缩小硬件截图，再读回小图像素。 */
final class RecorderFrameSampler {
    private final Picture picture=new Picture();
    private final Paint filter=new Paint(Paint.FILTER_BITMAP_FLAG);
    private boolean softwareFallback;
    long readbackPixels;
    int acceleratedFrames,fallbackFrames;

    Bitmap sample(Bitmap source,Rect display,Rect destination,int width){
        int height=Math.max(1,Math.round(display.height()*width/(float)display.width()));
        if(!softwareFallback&&source.getConfig()==Bitmap.Config.HARDWARE){
            try{
                Canvas canvas=picture.beginRecording(width,height);
                try{draw(canvas,source,display,destination,width,height);}finally{picture.endRecording();}
                // Picture 的硬件绘制路径只将最终小尺寸图读回 CPU，不能用软件 Canvas 直接画硬件图。
                Bitmap scaled=Bitmap.createBitmap(picture,width,height,Bitmap.Config.HARDWARE);
                try{
                    Bitmap result=scaled.copy(Bitmap.Config.ARGB_8888,false);
                    if(result==null)throw new IllegalStateException("无法读取缩小截图");
                    readbackPixels+=(long)width*height;acceleratedFrames++;return result;
                }finally{scaled.recycle();}
            }catch(RuntimeException error){
                // 部分厂商图形驱动不支持此路径时，本服务会话退回已验证的原方式。
                softwareFallback=true;
            }finally{
                // 清空录制命令，避免 Picture 跨帧持有已经关闭的截图硬件缓冲。
                picture.beginRecording(1,1);picture.endRecording();
            }
        }
        Bitmap software=source.getConfig()==Bitmap.Config.HARDWARE?source.copy(Bitmap.Config.ARGB_8888,false):source;
        Bitmap result=null;
        try{
            if(software==null)throw new IllegalStateException("无法读取截图像素");
            if(software!=source)readbackPixels+=(long)source.getWidth()*source.getHeight();
            result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            draw(new Canvas(result),software,display,destination,width,height);fallbackFrames++;return result;
        }catch(RuntimeException|OutOfMemoryError error){if(result!=null)result.recycle();throw error;}
        finally{if(software!=null&&software!=source)software.recycle();}
    }
    private void draw(Canvas canvas,Bitmap source,Rect display,Rect destination,int width,int height){
        canvas.drawColor(Color.BLACK);
        canvas.scale(width/(float)display.width(),height/(float)display.height());
        canvas.drawBitmap(source,null,destination,filter);
    }
}
