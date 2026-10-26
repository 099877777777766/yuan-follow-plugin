package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 操作后只追加有限等待；截图失败、动画不停均回退到定时执行，不自动暂停。 */
final class RecorderMotionWaiter {
    interface Capture {void get(FollowAccessibilityService.GameScreenshotCallback callback);}
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int generation;
    /** 短延迟直接观察；长延迟仍只在结束前采样，避免整段空等都反复截图。 */
    static int firstSampleDelay(int minimumMs){return Math.max(0,minimumMs-RecorderActionMotion.STABLE_MS-RecorderActionMotion.INTERVAL_MS);}
    void cancel(){generation++;handler.removeCallbacksAndMessages(null);}
    void start(int minimumMs,int extraMs,Capture capture,Supplier<Rect> overlay,BooleanSupplier active,Consumer<String> status,Runnable next){
        start(minimumMs,extraMs,capture,overlay,active,status,event->{},next);
    }
    /** 只在等待结束时汇总诊断；截图处理耗时计入采样间隔，稳定条件和截止上限独立保持。 */
    void start(int minimumMs,int extraMs,Capture capture,Supplier<Rect> overlay,BooleanSupplier active,Consumer<String> status,Consumer<String> trace,Runnable next){
        cancel();int token=generation;long started=SystemClock.uptimeMillis(),minimumAt=started+minimumMs;
        RecorderActionMotion motion=new RecorderActionMotion();int[] failures={0},samples={0},totalFailures={0};boolean[] announced={false};String[] lastError={""};
        Consumer<String> finish=reason->{if(token!=generation)return;cancel();if(active.getAsBoolean()){
            trace.accept("等待结束："+reason+"，耗时="+(SystemClock.uptimeMillis()-started)+"ms，成功帧="+samples[0]+"，失败="+totalFailures[0]+"，"+motion.diagnostic()+(lastError[0].isEmpty()?"":"，最近失败="+lastError[0]));next.run();
        }};
        // 独立截止计时器保证系统截图不回调时也不会永久卡住。
        handler.postDelayed(()->finish.accept("追加等待达到上限"),(long)minimumMs+extraMs);
        Runnable read=new Runnable(){public void run(){
            if(token!=generation||!active.getAsBoolean())return;
            final long requestedAt=SystemClock.uptimeMillis();
            if(!announced[0]&&SystemClock.uptimeMillis()>=minimumAt){announced[0]=true;status.accept("等待动作画面稳定");}
            try{capture.get(new FollowAccessibilityService.GameScreenshotCallback(){
                public void onFailure(String error){
                    if(token!=generation||!active.getAsBoolean())return;
                    motion.missing();
                    totalFailures[0]++;lastError[0]=error==null||error.isEmpty()?"截图/处理失败":error;
                    // 连续三次失败按原操作后延迟继续，不因识别失败取消跟打勾选。
                    if(++failures[0]>=3)handler.postDelayed(()->finish.accept("连续三次截图失败，回退固定延迟"),Math.max(0,minimumAt-SystemClock.uptimeMillis()));
                    else scheduleNext(requestedAt);
                }
                public void onSuccess(Bitmap frame){
                    if(token!=generation||!active.getAsBoolean()){frame.recycle();return;}
                    boolean stable;
                    try{stable=motion.observe(motion.sampleFrame(frame,overlay.get()),SystemClock.uptimeMillis());}
                    catch(Exception|OutOfMemoryError e){onFailure("");return;}finally{frame.recycle();}
                    failures[0]=0;samples[0]++;
                    if(stable&&SystemClock.uptimeMillis()>=minimumAt)finish.accept("画面判定稳定");
                    else scheduleNext(requestedAt);
                }
            });}catch(Exception e){motion.missing();totalFailures[0]++;lastError[0]="截图异常 "+e.getClass().getSimpleName();if(++failures[0]>=3)handler.postDelayed(()->finish.accept("连续三次截图失败，回退固定延迟"),Math.max(0,minimumAt-SystemClock.uptimeMillis()));else scheduleNext(requestedAt);}
        }
            private void scheduleNext(long requestedAt){
                // 一次只处理一张图；从请求起计 400ms，不在截图/像素处理结束后再加完整的 400ms。
                // 与执行器共享队列时仍由其限制真正的系统截图频率，排队不会导致并发截图。
                if(token==generation&&active.getAsBoolean())handler.postDelayed(this,Math.max(0,requestedAt+RecorderActionMotion.INTERVAL_MS-SystemClock.uptimeMillis()));
            }
        };
        // 在原延迟结束前就开始观察，平静画面不必再额外等待完整稳定窗口。
        handler.postDelayed(read,firstSampleDelay(minimumMs));
    }
}
