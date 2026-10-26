package com.local.yuanatlas;

import android.graphics.*;
import android.os.*;
import java.util.function.*;

/** 仅补识别缺失列的卡内圆环；短时多帧确认，不增加 OCR，也不改变已缓存坐标。 */
final class RecorderCircleCheck {
    static final int WAIT_MS=3000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int generation;
    void stop(){generation++;handler.removeCallbacksAndMessages(null);}
    void start(boolean[] columns,RecorderFollowVision.Layout layout,RecorderConditionCheck.Capture capture,Supplier<Rect> overlay,BooleanSupplier valid,BiConsumer<Integer,PointF> confirmed,Runnable complete){
        stop();int token=generation;boolean[] missing=columns.clone(),done={false};PointF[] previous=new PointF[5];
        Runnable finish=()->{if(token!=generation||done[0]||!valid.getAsBoolean())return;done[0]=true;stop();complete.run();};
        // 独立截止计时，截图失败/回调迟到不能把等待无限延长。
        handler.postDelayed(finish,WAIT_MS);
        Runnable sample=new Runnable(){public void run(){
            if(token!=generation||done[0]||!valid.getAsBoolean())return;
            capture.read(new RuYuanCaptureService.GameScreenshotCallback(){
                public void onFailure(String reason){if(token!=generation||done[0])return;java.util.Arrays.fill(previous,null);again();}
                public void onSuccess(Bitmap frame){
                    if(token!=generation||done[0]||!valid.getAsBoolean()){frame.recycle();return;}
                    Bitmap clean=null;
                    try{
                        // 旋转或尺寸改变时不用旧卡牌区域推算新的点击点。
                        if(frame.getWidth()!=layout.width||frame.getHeight()!=layout.height){java.util.Arrays.fill(previous,null);return;}
                        clean=CaptureOcrImage.excluding(frame,overlay.get());
                        for(int column=0;column<5;column++)if(missing[column]){
                            PointF point=RecorderFollowVision.circle(clean,layout,column),last=previous[column];previous[column]=point;
                            float tolerance=Math.max(2,layout.cards.width()/5f*.06f);
                            // 闪光单帧不落缓存；两次真实圆环中心接近才接受，不使用估算点或邻列坐标。
                            if(point!=null&&last!=null&&Math.hypot(point.x-last.x,point.y-last.y)<=tolerance){
                                missing[column]=false;confirmed.accept(column,point);
                            }
                        }
                    }catch(Exception|OutOfMemoryError error){java.util.Arrays.fill(previous,null);}
                    finally{if(clean!=null&&clean!=frame)clean.recycle();frame.recycle();again();}
                }
                private void again(){
                    if(token!=generation||done[0]||!valid.getAsBoolean())return;
                    boolean pending=false;for(boolean column:missing)pending|=column;
                    if(!pending)finish.run();else handler.postDelayed(()->run(),RecorderActionMotion.INTERVAL_MS);
                }
            });
        }};sample.run();
    }
}
