package com.local.yuanatlas;

import android.graphics.Rect;
import android.view.*;
import android.widget.*;

/** 设置校验错误同时携带控件位置，所有关闭入口复用，避免只弹提示却找不到错误项。 */
final class RecorderSettingsValidation extends IllegalArgumentException {
    final View target;
    RecorderSettingsValidation(View target,String message){super(message);this.target=target;}
    static int number(EditText input,String label,int min,int max){
        int value;
        try{value=Integer.parseInt(input.getText().toString().trim());}catch(NumberFormatException e){throw new RecorderSettingsValidation(input,"请填写"+label+"（"+min+"–"+max+"）");}
        if(value<min||value>max)throw new RecorderSettingsValidation(input,label+"范围为 "+min+"–"+max);
        input.setError(null);return value;
    }
    void reveal(){
        if(target instanceof EditText){((EditText)target).setError(getMessage());target.requestFocus();}
        // 用内容坐标滚动到第一处错误的上方，长设置页、续行和屏幕密度都适用。
        target.post(()->{
            ViewParent parent=target.getParent();while(parent!=null&&!(parent instanceof ScrollView))parent=parent.getParent();
            if(parent instanceof ScrollView){ScrollView scroll=(ScrollView)parent;Rect rect=new Rect(0,0,target.getWidth(),target.getHeight());scroll.offsetDescendantRectToMyCoords(target,rect);scroll.smoothScrollTo(0,Math.max(0,rect.top-12));}
            else target.requestRectangleOnScreen(new Rect(0,0,target.getWidth(),target.getHeight()),false);
            target.announceForAccessibility(getMessage());
        });
    }
    static void report(android.content.Context context,Exception error){if(error instanceof RecorderSettingsValidation)((RecorderSettingsValidation)error).reveal();Toast.makeText(context,error.getMessage(),Toast.LENGTH_LONG).show();}
}
