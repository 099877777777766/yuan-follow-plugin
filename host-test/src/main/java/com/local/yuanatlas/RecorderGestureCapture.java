package com.local.yuanatlas;

import android.view.MotionEvent;
import java.util.*;

/** 单指录制缓冲：不记录浮窗按钮；转发完成后重新计时，避免把转发耗时叠进下一步间隔。 */
final class RecorderGestureCapture {
    private final int width,height,slop,longPress;
    private long readyAt,downAt;
    private boolean touching,multiple;
    private final List<RecorderLoopScript.Point> points=new ArrayList<>();
    RecorderGestureCapture(int width,int height,int slop,int longPress,long startedAt){this.width=width;this.height=height;this.slop=slop;this.longPress=longPress;readyAt=startedAt;}
    void ready(long at){readyAt=at;}
    void cancel(){touching=false;points.clear();}
    RecorderLoopScript.Action accept(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){touching=true;multiple=false;points.clear();downAt=event.getEventTime();add(event.getRawX(),event.getRawY(),0);return null;}
        if(!touching)return null;
        if(action==MotionEvent.ACTION_POINTER_DOWN||event.getPointerCount()>1)multiple=true;
        if(action==MotionEvent.ACTION_CANCEL){cancel();return null;}
        if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP){
            float offsetX=event.getRawX()-event.getX(),offsetY=event.getRawY()-event.getY();
            for(int n=0;n<event.getHistorySize();n++)add(event.getHistoricalX(n)+offsetX,event.getHistoricalY(n)+offsetY,event.getHistoricalEventTime(n)-downAt);
            add(event.getRawX(),event.getRawY(),event.getEventTime()-downAt);
        }
        if(action!=MotionEvent.ACTION_UP)return null;
        touching=false;if(multiple){points.clear();throw new IllegalArgumentException("请使用单指录制点击、滑动或长按");}
        long duration=Math.max(1,event.getEventTime()-downAt);boolean moved=false;RecorderLoopScript.Point first=points.get(0);
        for(RecorderLoopScript.Point point:points)if(Math.hypot(point.x-first.x,point.y-first.y)>slop)moved=true;
        RecorderLoopScript.Action result=new RecorderLoopScript.Action(moved?"swipe":duration>=longPress?"hold":"tap",Math.max(0,downAt-readyAt),duration);
        result.points.addAll(points);points.clear();return result;
    }
    private void add(float x,float y,long time){points.add(new RecorderLoopScript.Point(Math.max(0,Math.min(width-1,x)),Math.max(0,Math.min(height-1,y)),Math.max(0,time)));}
}
