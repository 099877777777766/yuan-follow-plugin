package com.local.yuanatlas;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 仅接收本应用创建的不可变通知操作；不重启服务，也不恢复动作。 */
public final class RecorderRunNotificationReceiver extends BroadcastReceiver {
    static final String PAUSE="com.local.yuanatlas.PAUSE_RECORDER_FOLLOW";
    @Override public void onReceive(Context context,Intent intent){
        if(intent!=null&&PAUSE.equals(intent.getAction())&&RuYuanCaptureService.instance!=null)
            RuYuanCaptureService.instance.pauseRecorderFromNotification();
    }
}
