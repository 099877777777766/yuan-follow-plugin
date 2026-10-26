package com.local.yuanatlas;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;

/** 跟打会话使用现有无障碍服务的前台状态；停止后立即释放，不启动第二个保活进程。 */
final class RecorderRunForeground {
    static final int NOTIFICATION_ID=2016;
    private static final String CHANNEL="recorder-follow-running";
    private final RuYuanCaptureService service;
    private boolean active,running,live;
    RecorderRunForeground(RuYuanCaptureService service){this.service=service;}
    boolean active(){return active;}
    void setRunning(boolean running){
        if(this.running==running&&active==(running||live))return;this.running=running;refresh();
    }
    void setLive(boolean live){if(this.live==live)return;this.live=live;refresh();}
    private void refresh(){
        if(!running&&!live){
            try{service.stopForeground(Service.STOP_FOREGROUND_REMOVE);}
            catch(RuntimeException error){CaptureDiagnostics.event(service,"结束前台运行时系统已断开："+error.getClass().getSimpleName());}
            finally{active=false;}
            CaptureDiagnostics.event(service,"跟打前台运行已结束");return;
        }
        try{
            NotificationManager manager=service.getSystemService(NotificationManager.class);
            Notification.Builder builder;
            if(Build.VERSION.SDK_INT>=26){
                NotificationChannel channel=new NotificationChannel(CHANNEL,"自动跟打运行",NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("显示正在进行的跟打，并允许随时暂停");channel.setShowBadge(false);channel.setSound(null,null);
                manager.createNotificationChannel(channel);builder=new Notification.Builder(service,CHANNEL);
            }else builder=new Notification.Builder(service);
            PendingIntent open=PendingIntent.getActivity(service,NOTIFICATION_ID,new Intent(service,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent pause=PendingIntent.getBroadcast(service,NOTIFICATION_ID,new Intent(service,RecorderRunNotificationReceiver.class)
                .setAction(RecorderRunNotificationReceiver.PAUSE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.setSmallIcon(R.drawable.recorder_running).setContentTitle(live?"鸢家百宝袋 · 实时跟表":"鸢家百宝袋 · 跟打运行中")
                .setContentText(live?(running?"实时接收操作表并跟打，可随时暂停":"实时接收操作表，跟打尚未运行"):"正在识别回合并按表操作，可随时暂停")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"暂停跟打",pause).build());
            if(Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            if(Build.VERSION.SDK_INT>=34)service.startForeground(NOTIFICATION_ID,builder.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else service.startForeground(NOTIFICATION_ID,builder.build());
            active=true;CaptureDiagnostics.event(service,"跟打前台运行已开启");
        }catch(RuntimeException error){
            // 厂商拒绝提升优先级时记录原因类型；不让保护措施本身造成服务崩溃。
            active=false;CaptureDiagnostics.event(service,"跟打前台运行未开启："+error.getClass().getSimpleName());
            android.widget.Toast.makeText(service,"前台运行未开启，请查看运行诊断",android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
