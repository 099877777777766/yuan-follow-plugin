package com.local.yuanatlas;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.*;
import java.util.function.Supplier;

/** 0–6 双端整数滑块；不引入额外 UI 库，触摸、键盘和无障碍共用同一间隔约束。 */
final class RecorderDragonRangeSlider extends View {
    interface Changed {void change(int lower,int upper);}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Supplier<RecorderOverlayTheme> theme;
    private final Changed listener;
    private int lower,upper,active=0,pointer=-1;
    RecorderDragonRangeSlider(Context context,int lower,int upper,Supplier<RecorderOverlayTheme> theme,Changed listener){
        super(context);this.theme=theme;this.listener=listener;setRange(lower,upper,false);
        setContentDescription("龙气检测范围");setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    int lower(){return lower;}int upper(){return upper;}
    void setRange(int lower,int upper,boolean notify){RecorderDragonActionCondition.validateRange(lower,upper);this.lower=lower;this.upper=upper;invalidate();if(notify&&listener!=null)listener.change(lower,upper);}
    private float x(int value){return dp(20)+(getWidth()-dp(40))*value/6f;}
    private float y(){return getHeight()/2f+dp(5);}
    private int value(float x){return Math.max(0,Math.min(6,Math.round((x-dp(20))*6/Math.max(1,getWidth()-dp(40)))));}
    private void move(int thumb,int value){
        int l=thumb==0?Math.max(0,Math.min(upper-1,value)):lower;
        int u=thumb==1?Math.min(6,Math.max(lower+1,value)):upper;
        if(l!=lower||u!=upper){setRange(l,u,true);sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);}
    }
    @Override protected void onMeasure(int w,int h){setMeasuredDimension(resolveSize((int)dp(280),w),resolveSize((int)dp(66),h));}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);RecorderOverlayTheme t=theme.get();float y=y();paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(5));
        paint.setColor(t.line);c.drawLine(x(0),y,x(6),y,paint);paint.setColor(t.accent);c.drawLine(x(lower),y,x(upper),y,paint);
        paint.setTypeface(Typeface.DEFAULT);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(dp(11));
        for(int n=0;n<=6;n++){paint.setColor(n>=lower&&n<=upper?t.accent:t.muted);c.drawCircle(x(n),y,dp(2),paint);c.drawText(String.valueOf(n),x(n),y-dp(23),paint);}
        for(int n=0;n<2;n++){float cx=x(n==0?lower:upper);paint.setColor(t.surface);c.drawCircle(cx,y,dp(13),paint);paint.setColor(t.accent);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3));c.drawCircle(cx,y,dp(12),paint);paint.setStyle(Paint.Style.FILL);c.drawCircle(cx,y,dp(3),paint);}
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!isEnabled())return false;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                pointer=e.getPointerId(0);active=Math.abs(e.getX()-x(lower))<=Math.abs(e.getX()-x(upper))?0:1;requestFocus();getParent().requestDisallowInterceptTouchEvent(true);move(active,value(e.getX()));return true;
            case MotionEvent.ACTION_MOVE:
                int index=e.findPointerIndex(pointer);if(index>=0)move(active,value(e.getX(index)));return true;
            case MotionEvent.ACTION_UP:
                if(pointer!=-1){move(active,value(e.getX()));performClick();}pointer=-1;getParent().requestDisallowInterceptTouchEvent(false);return true;
            case MotionEvent.ACTION_CANCEL:pointer=-1;getParent().requestDisallowInterceptTouchEvent(false);return true;
            case MotionEvent.ACTION_POINTER_UP:if(e.getPointerId(e.getActionIndex())==pointer){pointer=-1;getParent().requestDisallowInterceptTouchEvent(false);}return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onKeyDown(int key,android.view.KeyEvent e){
        if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){active=1-active;invalidate();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){move(active,(active==0?lower:upper)+(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1));return true;}return super.onKeyDown(key,e);
    }
    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(){return new AccessibilityNodeProvider(){
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id){
            AccessibilityNodeInfo info=AccessibilityNodeInfo.obtain();
            if(id==View.NO_ID){onInitializeAccessibilityNodeInfo(info);info.addChild(RecorderDragonRangeSlider.this,0);info.addChild(RecorderDragonRangeSlider.this,1);return info;}
            if(id<0||id>1)return null;
            info.setSource(RecorderDragonRangeSlider.this,id);info.setParent(RecorderDragonRangeSlider.this);info.setPackageName(getContext().getPackageName());info.setClassName("android.widget.SeekBar");info.setEnabled(isEnabled());info.setVisibleToUser(isShown());info.setFocusable(true);
            int current=id==0?lower:upper;info.setContentDescription((id==0?"龙气范围下限 ":"龙气范围上限 ")+current);
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,id==0?0:lower+1,id==0?upper-1:6,current));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            Rect box=new Rect((int)(x(current)-dp(20)),(int)(y()-dp(20)),(int)(x(current)+dp(20)),(int)(y()+dp(20)));info.setBoundsInParent(box);int[] offset=new int[2];getLocationOnScreen(offset);box.offset(offset[0],offset[1]);info.setBoundsInScreen(box);return info;
        }
        @Override public boolean performAction(int id,int action,Bundle args){
            if(id<0||id>1||!isEnabled())return false;active=id;int current=id==0?lower:upper;
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)move(id,current+1);
            else if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)move(id,current-1);
            else if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null)move(id,Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));else return false;return true;
        }
    };}
}
