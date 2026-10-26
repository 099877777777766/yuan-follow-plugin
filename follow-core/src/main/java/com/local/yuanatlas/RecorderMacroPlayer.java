package com.local.yuanatlas;

import android.accessibilityservice.*;
import android.graphics.*;
import android.os.*;
import java.util.function.*;

/** 录制操作回放：按间隔串行执行，取消后所有迟到回调失效。 */
final class RecorderMacroPlayer {
    interface Sink {void send(Path path,long duration,Consumer<Boolean> callback);}
    static Sink nativeSink(FollowAccessibilityService service){return (path,duration,done)->{
        GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build();
        if(!service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback(){
            @Override public void onCompleted(GestureDescription gesture){done.accept(true);}
            @Override public void onCancelled(GestureDescription gesture){done.accept(false);}
        },null))done.accept(false);
    };}
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Sink sink;private final Supplier<String> focused;
    private int generation;private boolean playing;
    RecorderMacroPlayer(Sink sink,Supplier<String> focused){this.sink=sink;this.focused=focused;}
    boolean playing(){return playing;}
    void stop(){generation++;playing=false;handler.removeCallbacksAndMessages(null);}
    void play(RecorderLoopScript script,int width,int height,Consumer<String> status,Runnable complete,Consumer<String> error){
        stop();script.validate();playing=true;int token=generation;
        next(script,width,height,0,token,status,complete,error);
    }
    private void next(RecorderLoopScript script,int width,int height,int index,int token,Consumer<String> status,Runnable complete,Consumer<String> error){
        if(!valid(token))return;
        if(index==script.actions.size()){
            status.accept("等待进入下一局");handler.postDelayed(()->{if(!valid(token))return;if(!script.gamePackage.equals(focused.get())){fail("已离开游戏，循环停止",error);return;}playing=false;complete.run();},script.tailDelay);return;
        }
        RecorderLoopScript.Action action=script.actions.get(index);status.accept("录制操作 "+(index+1)+"/"+script.actions.size()+" · "+action.label());
        handler.postDelayed(()->{
            if(!valid(token))return;if(!script.gamePackage.equals(focused.get())){fail("已离开游戏，循环停止",error);return;}
            try{sink.send(action.path(width,height,script.width,script.height),action.duration,ok->{
                if(!valid(token))return;if(!ok){fail("录制手势中断，循环已停止",error);return;}
                next(script,width,height,index+1,token,status,complete,error);
            });}catch(Exception e){fail("录制操作未执行："+e.getMessage(),error);}
        },action.delay);
    }
    private boolean valid(int token){return playing&&generation==token;}
    private void fail(String reason,Consumer<String> error){stop();error.accept(reason);}
}
