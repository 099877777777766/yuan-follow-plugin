package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/** 勾选期间串行截取游戏窗口并在本机识别右上角；关闭后丢弃迟到结果，不保存截图。 */
final class RecorderRoundAutoReader {
    interface FrameSource {
        String focusedGame();
        void capture(String game,RuYuanCaptureService.GameScreenshotCallback callback);
        Rect overlayBounds();
        // 缩小截图的来源报告真实屏幕尺寸；旧来源默认仍返回原尺寸截图。
        default Rect screenBounds(){return null;}
        // 测试源及非原生来源可沿用原截图；真实服务给帧差提供 96px 宽的等比小图。
        default void captureMotion(String game,RuYuanCaptureService.GameScreenshotCallback callback){capture(game,callback);}
        default Rect motionOverlayBounds(){return overlayBounds();}
        // 血条框需要保留细线；与动画判断共享同一次 448 宽采样，避免读取两遍系统截图。
        default void captureBattle(String game,RuYuanCaptureService.GameScreenshotCallback callback){capture(game,callback);}
        default Rect battleOverlayBounds(){return overlayBounds();}
    }
    interface Listener {void onRound(int round,boolean changed);void onStatus(String status);}
    private final FrameSource source;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final RecorderBattleRound.Stability stability=new RecorderBattleRound.Stability();
    private final RecorderRoundReader roundReader=new RecorderRoundReader();
    private TextRecognizer recognizer;
    private boolean enabled;
    private int generation;
    private String lastGame="";
    private final Runnable poll=this::poll;
    RecorderRoundAutoReader(FrameSource source,Listener listener){this.source=source;this.listener=listener;}
    void start(){
        stop();enabled=true;generation++;lastGame="";stability.reset();
        recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());handler.post(poll);
    }
    void stop(){enabled=false;generation++;roundReader.clear();handler.removeCallbacksAndMessages(null);if(recognizer!=null){recognizer.close();recognizer=null;}}
    private boolean valid(int token){return enabled&&token==generation;}
    private void later(int token){if(valid(token))handler.postDelayed(poll,750);}
    private void failed(int token,String message){if(!valid(token))return;stability.miss();listener.onStatus(message);later(token);}
    private void poll(){
        if(!enabled)return;int token=generation;
        String game=source.focusedGame();
        if(game==null||game.isEmpty()){failed(token,"进入游戏后识别");return;}
        if(!lastGame.equals(game)){lastGame=game;stability.reset();roundReader.clear();}
        try{source.capture(game,new RuYuanCaptureService.GameScreenshotCallback(){
            @Override public void onFailure(String error){failed(token,error);}
            @Override public void onSuccess(Bitmap frame){
                if(!valid(token)||!game.equals(source.focusedGame())){frame.recycle();if(valid(token))failed(token,"进入游戏后识别");return;}
                try{roundReader.read(recognizer,frame,source.overlayBounds())
                    .addOnSuccessListener(reading->{
                        if(!valid(token)||!game.equals(source.focusedGame()))return;
                        if(reading==null){stability.miss();listener.onStatus("未读到右上角回合");return;}
                        int state=stability.observe(reading.current);
                        if(state==0)listener.onStatus("核对回合 "+reading.current);
                        else listener.onRound(reading.current,state==2);
                    })
                    .addOnFailureListener(error->{if(valid(token)){stability.miss();listener.onStatus("回合识别暂时失败");}})
                    .addOnCompleteListener(task->{frame.recycle();later(token);});
                }catch(Exception|OutOfMemoryError error){frame.recycle();failed(token,"回合识别暂时失败");}
            }
        });}catch(Exception error){failed(token,"游戏暂时无法截屏");}
    }
    /** 相对坐标覆盖黑边与不同分辨率；老系统把浮窗覆盖范围排除，避免读到自己的内容。 */
    static Bitmap region(Bitmap frame,Rect overlay){
        Bitmap crop=null;
        try{
            Rect v=GameViewport.detect(frame);
            int left=v.left+Math.round(v.width()*.5f),height=Math.max(1,Math.round(v.height()*.26f));
            crop=CaptureOcrImage.crop(frame,new Rect(left,v.top,v.right,v.top+height),overlay);
            float scale=Math.max(1f,Math.min(3f,960f/crop.getWidth()));
            Bitmap result=Bitmap.createScaledBitmap(crop,Math.max(1,Math.round(crop.getWidth()*scale)),Math.max(1,Math.round(crop.getHeight()*scale)),true);
            if(result==crop)crop=null;
            return result;
        }finally{if(crop!=null)crop.recycle();}
    }
}
