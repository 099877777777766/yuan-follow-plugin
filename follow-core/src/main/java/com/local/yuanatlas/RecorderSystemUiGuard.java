package com.local.yuanatlas;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import java.util.function.Consumer;

/** 无障碍浮窗无法降低系统层级；通知面板展开期间让出显示与触摸，保留原窗口。 */
final class RecorderSystemUiGuard {
    private final AccessibilityService service;
    private final Consumer<Boolean> changed;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean running,covered;
    private final Runnable check=this::refresh;
    RecorderSystemUiGuard(AccessibilityService service,Consumer<Boolean> changed){
        this.service=service;this.changed=changed;
    }
    void start(){running=true;refresh();}
    void close(){running=false;handler.removeCallbacksAndMessages(null);}
    void refresh(){
        if(!running)return;
        handler.removeCallbacks(check);
        Boolean next=readCovered();
        // 窗口切换期间 root 可能暂不可读，不能因此把已经隐藏的浮窗提前恢复。
        if(next!=null&&next!=covered){covered=next;changed.accept(covered);}
        // 部分系统不完整发送通知面板的窗口事件；仅在浮窗存在期间低频复查。
        handler.postDelayed(check,200);
    }
    private Boolean readCovered(){
        List<AccessibilityWindowInfo> windows=null;
        boolean uncertain=false;
        try{
            windows=service.getWindows();
            int barId=service.getResources().getIdentifier("status_bar_height","dimen","android");
            int barHeight=barId==0?Math.round(24*service.getResources().getDisplayMetrics().density):service.getResources().getDimensionPixelSize(barId);
            int screenWidth=service.getResources().getDisplayMetrics().widthPixels;
            for(AccessibilityWindowInfo window:windows){
                if(window.getType()!=AccessibilityWindowInfo.TYPE_SYSTEM)continue;
                Rect bounds=new Rect();window.getBoundsInScreen(bounds);
                // 常驻状态栏、导航栏不会触发隐藏；覆盖内容区的系统面板才需要让位。
                if(bounds.height()<=barHeight*2||(!window.isFocused()&&!window.isActive()&&bounds.width()<=screenWidth/2))continue;
                AccessibilityNodeInfo root=window.getRoot();
                if(root==null){uncertain=true;continue;}
                try{if("com.android.systemui".contentEquals(root.getPackageName()==null?"":root.getPackageName()))return true;}
                finally{root.recycle();}
            }
            return uncertain?null:false;
        }catch(RuntimeException error){return null;}
        finally{if(windows!=null)for(AccessibilityWindowInfo window:windows)window.recycle();}
    }
}
