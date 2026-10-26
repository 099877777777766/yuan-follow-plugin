package com.local.yuanatlas;

import android.graphics.*;
import android.os.SystemClock;
import com.google.android.gms.tasks.*;
import com.google.mlkit.vision.text.TextRecognizer;
import java.util.Arrays;

/** 会话内缓存已定位的回合标题；只有连续两次 OCR 一致且像素完全相同时才复用。 */
final class RecorderRoundReader {
    private Rect viewport,area;
    private int width,height,generation,confirmed;
    private int[] pixels,scratch;
    private long verifiedAt;
    private RecorderBattleRound.Reading saved;
    int ocrCalls,cacheHits,wideReads;
    void clear(){generation++;viewport=null;area=null;saved=null;confirmed=0;pixels=null;scratch=null;}
    /** 回合标题与左上返回位于同一工具栏；使用已识别的屏幕坐标，避免按整屏高度估计按钮。 */
    Rect titleBounds(int sourceWidth,int sourceHeight){
        return saved==null||area==null||width!=sourceWidth||height!=sourceHeight?null:new Rect(area);
    }
    Task<RecorderBattleRound.Reading> read(TextRecognizer recognizer,Bitmap frame,Rect overlay){
        Rect view=GameViewport.detect(frame);
        if(width!=frame.getWidth()||height!=frame.getHeight()||!view.equals(viewport)){
            clear();width=frame.getWidth();height=frame.getHeight();viewport=new Rect(view);
        }
        int token=generation;
        if(area!=null&&overlay!=null&&Rect.intersects(area,overlay)){
            // 遮挡后不能凭历史像素继续动作，重新走完整的排除浮窗识别。
            area=null;saved=null;confirmed=0;pixels=null;
        }
        if(area!=null){
            readPixels(frame,area);
            if(saved!=null&&confirmed>=2&&SystemClock.uptimeMillis()-verifiedAt<5000&&Arrays.equals(pixels,scratch)){
                cacheHits++;return Tasks.forResult(saved);
            }
            Rect narrow=new Rect(area);Bitmap region=scaledCrop(frame,narrow,overlay);
            ocrCalls++;
            Task<RecorderBattleRound.Reading> pending;
            try{pending=RecorderRoundOcr.recognize(recognizer,region);}catch(RuntimeException|OutOfMemoryError error){region.recycle();throw error;}
            return pending.continueWithTask(task->{
                region.recycle();
                if(token!=generation)return Tasks.forResult(null);
                if(!task.isSuccessful()||task.getResult()==null)return wide(recognizer,frame,overlay,view,token);
                remember(frame,narrow,task.getResult());return Tasks.forResult(task.getResult());
            });
        }
        return wide(recognizer,frame,overlay,view,token);
    }
    private Task<RecorderBattleRound.Reading> wide(TextRecognizer recognizer,Bitmap frame,Rect overlay,Rect view,int token){
        Bitmap region=RecorderRoundAutoReader.region(frame,overlay);Rect[] found={null};
        int left=view.left+Math.round(view.width()*.5f);float scale=region.getWidth()/(float)(view.right-left);
        ocrCalls++;wideReads++;
        Task<RecorderBattleRound.Reading> pending;
        try{pending=RecorderRoundOcr.recognize(recognizer,region,bounds->found[0]=bounds);}catch(RuntimeException|OutOfMemoryError error){region.recycle();throw error;}
        return pending.continueWith(task->{
            region.recycle();if(token!=generation)return null;
            if(!task.isSuccessful()||task.getResult()==null){area=null;saved=null;confirmed=0;pixels=null;return null;}
            RecorderBattleRound.Reading result=task.getResult();Rect bounds=found[0];
            if(bounds!=null){
                // 右侧保留至游戏边缘，回合数字从一位增至两位时不会被旧文字宽度裁掉。
                Rect title=new Rect(left+(int)Math.floor(bounds.left/scale),view.top+(int)Math.floor(bounds.top/scale),view.right,view.top+(int)Math.ceil(bounds.bottom/scale));
                if(title.intersect(view)&&(overlay==null||!Rect.intersects(title,overlay)))remember(frame,title,result);
                else{area=null;saved=null;confirmed=0;pixels=null;}
            }else{area=null;saved=null;confirmed=0;pixels=null;}
            return result;
        });
    }
    private static Bitmap scaledCrop(Bitmap frame,Rect area,Rect overlay){
        Bitmap crop=CaptureOcrImage.crop(frame,area,overlay);
        try{Bitmap result=Bitmap.createScaledBitmap(crop,Math.max(1,Math.round(crop.getWidth()*160f/crop.getHeight())),160,true);if(result!=crop)crop.recycle();return result;}
        catch(RuntimeException|OutOfMemoryError error){crop.recycle();throw error;}
    }
    private void readPixels(Bitmap frame,Rect bounds){
        int size=bounds.width()*bounds.height();if(scratch==null||scratch.length!=size)scratch=new int[size];
        frame.getPixels(scratch,0,bounds.width(),bounds.left,bounds.top,bounds.width(),bounds.height());
    }
    private void remember(Bitmap frame,Rect bounds,RecorderBattleRound.Reading reading){
        boolean same=saved!=null&&saved.current==reading.current&&saved.limit==reading.limit&&bounds.equals(area);
        confirmed=same?confirmed+1:1;saved=reading;area=new Rect(bounds);verifiedAt=SystemClock.uptimeMillis();
        readPixels(frame,bounds);int[] old=pixels;pixels=scratch;scratch=old;
    }
}
