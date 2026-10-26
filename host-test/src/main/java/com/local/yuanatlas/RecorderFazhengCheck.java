package com.local.yuanatlas;

import android.graphics.*;
import android.os.*;
import java.util.*;
import java.util.function.*;

/** 动作后读到两张相同的完整堆叠才判定；超时暂停，不把未知当成失败。 */
final class RecorderFazhengCheck {
    private final Handler handler=new Handler(Looper.getMainLooper());private int generation;private int[] lastItems;private int lastPull;
    void stop(){generation++;handler.removeCallbacksAndMessages(null);}
    void start(int pulls,int column,RecorderFollowVision.Layout layout,RecorderConditionCheck.Capture capture,Supplier<Rect> overlay,BooleanSupplier valid,Consumer<int[]> result,Consumer<String> error){
        stop();if(pulls==1||pulls<lastPull){lastItems=null;lastPull=0;}int token=generation;int[][] previous={null};int[] unreadable={0};
        handler.postDelayed(()->{if(token!=generation||!valid.getAsBoolean())return;stop();error.accept("下拉后未确认新增圣物，已停止跟打；请核对画面后继续");},5000);
        Runnable sample=new Runnable(){public void run(){
            if(token!=generation||!valid.getAsBoolean())return;
            capture.read(new RuYuanCaptureService.GameScreenshotCallback(){
                public void onSuccess(Bitmap frame){
                    if(token!=generation||!valid.getAsBoolean()){frame.recycle();return;}
                    int[] colors;try{colors=RecorderFazhengVision.readItems(frame,overlay.get(),layout,column,pulls);}catch(Exception|OutOfMemoryError ignored){colors=null;}finally{frame.recycle();}
                    // 动画等待后，连续两帧仍读不出新增物品就停下，不补发手势或执行重开。
                    if(colors==null){if(++unreadable[0]>=2){stop();error.accept("下拉后未确认新增圣物，已停止跟打；请核对画面后继续");return;}}
                    else unreadable[0]=0;
                    if(colors!=null&&Arrays.equals(previous[0],colors)){
                        if(lastPull==pulls-1&&Arrays.equals(lastItems,colors)){stop();error.accept("下拉后圣物未变化，未确认新增，已停止跟打");return;}
                        lastItems=colors.clone();lastPull=pulls;stop();result.accept(colors);return;
                    }
                    previous[0]=colors;again();
                }
                public void onFailure(String reason){previous[0]=null;again();}
                private void again(){if(token==generation&&valid.getAsBoolean())handler.postDelayed(()->run(),RecorderActionMotion.INTERVAL_MS);}
            });
        }};sample.run();
    }
}
