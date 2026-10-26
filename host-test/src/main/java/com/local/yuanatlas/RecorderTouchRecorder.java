package com.local.yuanatlas;

import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.function.*;

/** 透明录制层接收用户手势，松手后撤掉触摸拦截并向游戏转发；操作条不进入录制数据。 */
final class RecorderTouchRecorder {
    private final RuYuanCaptureService service;private final WindowManager windows;private final RecorderOverlayTheme theme;
    private final Consumer<RecorderLoopScript> saved;private final Runnable cancelled;private final Supplier<String> focused;
    private final Handler handler=new Handler(Looper.getMainLooper());private final RecorderMacroPlayer.Sink sink;
    private View capture;private LinearLayout bar;private TextView status,save;private WindowManager.LayoutParams captureParams,barParams;
    private RecorderLoopScript script;private RecorderGestureCapture buffer;private long readyAt;private boolean busy;private int generation;
    RecorderTouchRecorder(RuYuanCaptureService service,RecorderOverlayTheme theme,Supplier<String> focused,Consumer<RecorderLoopScript> saved,Runnable cancelled){
        this.service=service;this.theme=theme;this.focused=focused;this.saved=saved;this.cancelled=cancelled;
        windows=(WindowManager)service.getSystemService(android.content.Context.WINDOW_SERVICE);sink=RecorderMacroPlayer.nativeSink(service);
    }
    private int dp(int value){return Math.round(value*service.getResources().getDisplayMetrics().density);}
    private WindowManager.LayoutParams params(int width,int height){WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;return p;}
    void start(String game){
        Rect screen=windows.getMaximumWindowMetrics().getBounds();script=new RecorderLoopScript();script.gamePackage=game;script.width=screen.width();script.height=screen.height();
        readyAt=SystemClock.uptimeMillis();buffer=new RecorderGestureCapture(script.width,script.height,ViewConfiguration.get(service).getScaledTouchSlop(),ViewConfiguration.getLongPressTimeout(),readyAt);
        capture=new View(service);capture.setContentDescription("录制手势区域");capture.setBackgroundColor(0x04000000);captureParams=params(-1,-1);captureParams.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        capture.setOnTouchListener((view,event)->{
            if(busy)return true;
            if(!script.gamePackage.equals(focused.get())){finish(false);return true;}
            try{RecorderLoopScript.Action action=buffer.accept(event);if(action!=null)forward(action);}catch(IllegalArgumentException e){status.setText(e.getMessage());buffer.ready(SystemClock.uptimeMillis());}
            return true;
        });windows.addView(capture,captureParams);
        bar=new LinearLayout(service);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(10),dp(4),dp(4),dp(4));
        GradientDrawable background=new GradientDrawable();background.setColor(theme.surface);background.setCornerRadius(dp(12));background.setStroke(dp(1),theme.line);bar.setBackground(background);
        status=new TextView(service);status.setTextColor(theme.accent);status.setTextSize(11);status.setText("录制中 · 0 步\n松手后执行，请等提示继续");bar.addView(status,new LinearLayout.LayoutParams(0,dp(44),1));
        save=control("保存",()->finish(true));bar.addView(save,new LinearLayout.LayoutParams(dp(46),dp(40)));
        bar.addView(control("取消",()->finish(false)),new LinearLayout.LayoutParams(dp(46),dp(40)));
        barParams=params(Math.min(screen.width()-dp(16),dp(380)),dp(52));barParams.x=dp(8);barParams.y=dp(28);windows.addView(bar,barParams);
        // 拖动操作条可让出需要录制的区域，拖动自身不记录。
        status.setOnTouchListener(new View.OnTouchListener(){float x,y;int bx,by;public boolean onTouch(View v,MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();bx=barParams.x;by=barParams.y;return true;}if(e.getActionMasked()==MotionEvent.ACTION_MOVE){barParams.x=Math.max(0,Math.min(script.width-barParams.width,bx+Math.round(e.getRawX()-x)));barParams.y=Math.max(0,Math.min(script.height-barParams.height,by+Math.round(e.getRawY()-y)));windows.updateViewLayout(bar,barParams);}return true;}});
        watch(generation);
    }
    private TextView control(String text,Runnable click){TextView button=new TextView(service);button.setText(text);button.setGravity(Gravity.CENTER);button.setTextSize(12);button.setTextColor(theme.accent);button.setContentDescription("录制"+text);button.setOnClickListener(v->click.run());return button;}
    private void watch(int token){handler.postDelayed(()->{if(token!=generation||capture==null)return;if(!script.gamePackage.equals(focused.get())){Toast.makeText(service,"已离开游戏，录制已取消",Toast.LENGTH_SHORT).show();finish(false);return;}watch(token);},300);}
    private void forward(RecorderLoopScript.Action action){
        if(action.duration>android.accessibilityservice.GestureDescription.getMaxGestureDuration()){status.setText("单次手势超过系统时长，请重新录制这一步");buffer.ready(SystemClock.uptimeMillis());return;}
        busy=true;save.setEnabled(false);status.setText("正在执行"+action.label()+"，请稍候…");captureParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;windows.updateViewLayout(capture,captureParams);
        int token=generation;
        // 等待 WindowManager 撤掉录制层的触摸区域，避免把转发的手势再次录进去。
        handler.postDelayed(()->{
            if(token!=generation||capture==null)return;
            if(!script.gamePackage.equals(focused.get())){finish(false);return;}
            try{sink.send(action.path(script.width,script.height,script.width,script.height),action.duration,ok->{
                if(token!=generation||capture==null)return;
                if(ok)script.actions.add(action);
                busy=false;save.setEnabled(true);captureParams.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;windows.updateViewLayout(capture,captureParams);
                readyAt=SystemClock.uptimeMillis();buffer.ready(readyAt);status.setText(ok?"录制中 · "+script.actions.size()+" 步\n可继续下一步，或保存":"这一步未执行，请重新操作");
            });}catch(Exception e){busy=false;save.setEnabled(true);captureParams.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;windows.updateViewLayout(capture,captureParams);readyAt=SystemClock.uptimeMillis();buffer.ready(readyAt);status.setText("这一步未执行，请重新操作");}
        },120);
    }
    private void finish(boolean keep){
        if(keep&&busy)return;
        if(keep&&script.actions.isEmpty()){status.setText("请先录制至少一个操作");return;}
        if(keep)script.tailDelay=Math.max(0,SystemClock.uptimeMillis()-readyAt);
        close();if(keep)saved.accept(script);else cancelled.run();
    }
    // 录制层也必须让出通知面板，避免透明全屏窗口继续拦截系统触摸。
    void setSystemUiCovered(boolean covered){
        if(capture!=null)capture.setVisibility(covered?View.INVISIBLE:View.VISIBLE);
        if(bar!=null)bar.setVisibility(covered?View.INVISIBLE:View.VISIBLE);
    }
    void close(){generation++;handler.removeCallbacksAndMessages(null);if(buffer!=null)buffer.cancel();if(capture!=null){windows.removeView(capture);capture=null;}if(bar!=null){windows.removeView(bar);bar=null;}}
}
