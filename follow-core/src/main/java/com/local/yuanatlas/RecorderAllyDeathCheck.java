package com.local.yuanatlas;

import android.graphics.*;
import android.os.*;
import com.google.mlkit.vision.text.TextRecognizer;
import java.util.Arrays;
import java.util.function.*;

/** 所选回合操作前短时复核两张新截图；任一选中列阵亡即重开，未知不等于存活。 */
final class RecorderAllyDeathCheck {
    private final Handler handler=new Handler(Looper.getMainLooper());private int generation;private RecorderAllyDeathVision.TextCache textCache;
    void stop(){generation++;handler.removeCallbacksAndMessages(null);if(textCache!=null){textCache.close();textCache=null;}}
    void start(TextRecognizer recognizer,int round,int mask,RecorderFollowVision.Layout layout,RecorderConditionCheck.Capture capture,Supplier<Rect> overlay,BooleanSupplier valid,Consumer<String> trace,Runnable passed,Consumer<String> rejected,Consumer<String> error){
        stop();if(mask==0){passed.run();return;}int token=generation;RecorderAllyDeathVision.TextCache cache=new RecorderAllyDeathVision.TextCache();textCache=cache;
        int[] previous={-1,-1,-1,-1,-1},same=new int[5];boolean[] done={false},loggedUnknown={false};
        handler.postDelayed(()->{if(token!=generation||done[0]||!valid.getAsBoolean())return;done[0]=true;stop();error.accept("第"+round+"回合密探阵亡状态未识别清楚，已停止；请核对卡牌或浮窗遮挡后继续");},5000);
        Runnable sample=new Runnable(){public void run(){
            if(token!=generation||done[0]||!valid.getAsBoolean())return;
            capture.read(new FollowAccessibilityService.GameScreenshotCallback(){
                public void onFailure(String reason){Arrays.fill(same,0);again();}
                public void onSuccess(Bitmap frame){
                    if(token!=generation||done[0]||!valid.getAsBoolean()){frame.recycle();return;}
                    try{RecorderAllyDeathVision.read(recognizer,frame,overlay.get(),layout,mask,cache).addOnCompleteListener(task->{
                        frame.recycle();if(token!=generation||done[0]||!valid.getAsBoolean())return;
                        if(!task.isSuccessful()){if(!loggedUnknown[0]){loggedUnknown[0]=true;trace.accept("密探阵亡检查失败："+task.getException());}onFailure("");return;}RecorderAllyDeathVision.Reading reading=task.getResult();boolean all=true,foundDead=false;
                        for(int state:reading.states)if(state==RecorderAllyDeathVision.DEAD)foundDead=true;
                        for(int c=0;c<5;c++)if((mask&(1<<c))!=0){
                            int state=reading.states[c];same[c]=state<0?0:state==previous[c]?same[c]+1:1;previous[c]=state;
                            // 已发现阵亡后未再读取的后续列也为未知，不应误记成遮挡或识别失败。
                            if(state<0&&!foundDead&&!loggedUnknown[0]){loggedUnknown[0]=true;trace.accept("第"+round+"回合阵亡状态暂未确认：第"+(c+1)+"号位，"+(reading.reasons[c]==null?"卡牌被遮挡或区域不完整":reading.reasons[c])+"，卡牌="+layout.cards);}
                            if(state==RecorderAllyDeathVision.DEAD&&same[c]>=2){done[0]=true;stop();String reason="第"+round+"回合第"+(c+1)+"号位密探已阵亡（"+reading.reasons[c]+"）";trace.accept("检测阵亡重开："+reason);rejected.accept(reason);return;}
                            if(state!=RecorderAllyDeathVision.ALIVE||same[c]<2)all=false;
                        }
                        if(all){done[0]=true;stop();trace.accept("第"+round+"回合检测阵亡通过，选中密探均存活");passed.run();}else again();
                    });}catch(Exception|OutOfMemoryError failure){frame.recycle();onFailure("");}
                }
                private void again(){if(token==generation&&!done[0]&&valid.getAsBoolean())handler.postDelayed(()->run(),400);}
            });
        }};sample.run();
    }
}
