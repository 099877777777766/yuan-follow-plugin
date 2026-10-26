package com.local.yuanatlas;

import android.graphics.*;
import android.os.*;
import java.util.function.*;

/** 仅指定回合触发的短检查；闪光间隙按未知重读，未知不能当作“不满足”退出战斗。 */
final class RecorderConditionCheck {
    interface Capture {void read(RuYuanCaptureService.GameScreenshotCallback callback);}
    private final Handler handler=new Handler(Looper.getMainLooper());private int generation;
    void stop(){generation++;handler.removeCallbacksAndMessages(null);}
    void start(int round,RecorderRoundConditions rules,RecorderFollowTable table,RecorderFollowVision.Layout layout,Capture capture,Supplier<Rect> overlay,BooleanSupplier valid,Consumer<String> trace,Runnable passed,Consumer<String> rejected,Consumer<String> error){
        start(round,rules,table,layout,capture,overlay,valid,trace,reading->passed.run(),rejected,error);
    }
    /** 分支动作和原重开条件共享这次已确认的读数，不再额外截图识别一次。 */
    void start(int round,RecorderRoundConditions rules,RecorderFollowTable table,RecorderFollowVision.Layout layout,Capture capture,Supplier<Rect> overlay,BooleanSupplier valid,Consumer<String> trace,Consumer<RecorderConditionVision.Reading> passed,Consumer<String> rejected,Consumer<String> error){
        stop();int token=generation;String expectedStar=rules.starAt(round);boolean wantStar=expectedStar!=null,wantDragon=rules.dragonRounds.contains(round)||rules.dragonActionRounds.contains(round),wantVein=rules.veinTargets.containsKey(round)||rules.veinActionRounds.contains(round);int column=RecorderRoundConditions.zhugeColumn(table);
        if(!wantStar&&!wantDragon&&!wantVein){passed.accept(null);return;}
        if(wantDragon&&column<0){error.accept("未找到诸葛亮的位置，请核对导入表的密探名字");return;}
        String[] previous={null};int[] same={0};boolean[] done={false},loggedUnknown={false};
        Runnable timeout=()->{if(token!=generation||done[0]||!valid.getAsBoolean())return;done[0]=true;stop();error.accept("第"+round+"回合条件未识别清楚，已停止；请核对星色、龙气、龙脉背景或浮窗遮挡后继续");};
        handler.postDelayed(timeout,5000);
        Runnable sample=new Runnable(){public void run(){
            if(token!=generation||done[0]||!valid.getAsBoolean())return;
            capture.read(new RuYuanCaptureService.GameScreenshotCallback(){
                public void onFailure(String reason){again();}
                public void onSuccess(Bitmap frame){
                    if(token!=generation||done[0]||!valid.getAsBoolean()){frame.recycle();return;}
                    RecorderConditionVision.Reading reading;
                    try{reading=RecorderConditionalActionDecision.read(frame,overlay.get(),layout,table,rules,round);}catch(Exception|OutOfMemoryError failure){reading=null;}finally{frame.recycle();}
                    if(reading==null||!reading.pending.isEmpty()){
                        // 首个未知结果带出区域及各项读数，便于区分 0 龙气、星色不明和取样位置偏移。
                        if(!loggedUnknown[0]){loggedUnknown[0]=true;trace.accept("第"+round+"回合条件暂未识别：星色="+(wantStar?(reading==null?"未知":starValue(reading.star)):"未启用")+"，龙气="+(wantDragon?(reading==null||reading.dragon<0?"未知或未执行":String.valueOf(reading.dragon)):"未启用")+"，原因="+(reading==null?"读取失败":reading.pending)+"，诸葛亮列="+(column+1)+"，游戏="+layout.viewport+"，卡牌="+layout.cards);}
                        previous[0]=null;same[0]=0;again();return;
                    }
                    String signature=reading.star+":"+reading.dragon+":"+reading.vein+":"+reading.actionSource+":"+reading.actionMatched;
                    if(signature.equals(previous[0]))same[0]++;else{previous[0]=signature;same[0]=1;}
                    if(same[0]<2){again();return;}
                    done[0]=true;stop();
                    java.util.StringJoiner details=new java.util.StringJoiner("，");if(wantStar)details.add(RecorderConditionVision.starName(reading.star));if(reading.dragon>=0)details.add("诸葛亮第"+(column+1)+"位龙气="+reading.dragon);if(reading.vein>=0)details.add(RecorderDragonVeinVision.name(reading.vein));
                    String observed="第"+round+"回合条件识别："+details;trace.accept(observed);
                    // 条件动作不匹配仍可走其分支；星色、龙气或龙脉重开要求失败才退出。
                    boolean accepted=(!wantStar||expectedStar.equals(reading.star))&&(!rules.dragonRounds.contains(round)||rules.acceptsDragon(reading.dragon))&&rules.acceptsVein(round,reading.vein);
                    if(accepted)passed.accept(reading);else rejected.accept(observed+"，不满足设置条件");
                }
                private void again(){if(token==generation&&!done[0]&&valid.getAsBoolean())handler.postDelayed(()->run(),RecorderActionMotion.INTERVAL_MS);}
            });
        }};sample.run();
    }
    private static String starValue(String value){return value==null?"未知":RecorderConditionVision.starName(value);}
}
