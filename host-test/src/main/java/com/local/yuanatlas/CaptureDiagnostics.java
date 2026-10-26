package com.local.yuanatlas;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 本机仅保留少量服务事件、跟打步骤/手势运行结果与异常栈；不保存截图、角色名、整张操作表或用户录制内容。 */
final class CaptureDiagnostics {
    private static boolean installed;
    private static final String STORE="capture-diagnostics";
    static synchronized void install(Context context){
        if(installed)return;installed=true;Context app=context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        if(previous==null)return;
        // 记录后仍交给 Android 的异常处理器；不能吞掉异常并在损坏的主线程上继续操作。
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{recordCrash(app,error);previous.uncaughtException(thread,error);});
    }
    static String time(long timestamp){return new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(timestamp));}
    static synchronized void event(Context context,String message){
        try{
            var prefs=context.getSharedPreferences(STORE,0);String old=prefs.getString("events","");
            String value=old+new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.CHINA).format(new Date())+" "+message+"\n";
            if(value.length()>6000)value=value.substring(value.indexOf('\n',value.length()-6000)+1);
            prefs.edit().putString("events",value).apply();
        }catch(RuntimeException|OutOfMemoryError ignored){/* 诊断本身不能影响服务运行。 */}
    }
    static void recordCrash(Context context,Throwable error){
        try{
            StringBuilder text=new StringBuilder(time(System.currentTimeMillis())+" "+BuildConfig.VERSION_NAME+"\n");
            for(int cause=0;error!=null&&cause<3;cause++,error=error.getCause()){
                text.append(error.getClass().getName()).append('\n');int n=0;
                for(StackTraceElement frame:error.getStackTrace()){if(n++>=16)break;text.append("  ").append(frame).append('\n');}
            }
            context.getSharedPreferences(STORE,0).edit().putString("crash",text.toString()).commit();
        }catch(RuntimeException|OutOfMemoryError ignored){}
    }
    static boolean enabled(Context context){
        String value=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(value==null)return false;ComponentName own=new ComponentName(context,RuYuanCaptureService.class);
        for(String name:value.split(":"))if(own.equals(ComponentName.unflattenFromString(name)))return true;return false;
    }
    static String reason(int reason){
        switch(reason){case 0:return "系统未提供原因";case 1:return "进程自行退出";case 2:return "系统信号终止";case 3:return "系统内存不足";case 4:return "Java 异常退出";case 5:return "原生异常退出";case 6:return "应用无响应（ANR）";case 7:return "进程初始化失败";case 8:return "权限变更";case 9:return "系统资源限制";case 10:return "请求停止（旧系统也可能为升级或组件变化）";case 11:return "系统用户停止";case 12:return "依赖进程退出";case 13:return "系统其他原因（需看说明）";case 14:return "系统冻结进程终止";case 15:return "组件状态变化";case 16:return "应用升级";default:return "系统原因 "+reason;}
    }
    /** 保留系统给出的具体原因和进程，避免把同一秒的主进程、WebView 退出混为一次崩溃。 */
    static String exitDetails(String timestamp,int reason,String process,int pid,int status,int importance,long pss,long rss,String description){
        String detail=description==null||description.trim().isEmpty()?"系统未提供":description.replaceAll("[\\p{Cntrl}\\s]+"," ").trim();
        if(detail.length()>600)detail=detail.substring(0,600)+"…";
        return timestamp+" "+reason(reason)+" [reason="+reason+"]\n"
            +"  进程："+process+"，PID="+pid+"，status="+status+"，importance="+importance+"\n"
            +"  PSS="+pss+" KB，RSS="+rss+" KB（系统最近采样，非退出瞬间值）\n"
            +"  系统说明："+detail+"\n";
    }
    static String report(Context context){
        StringBuilder text=new StringBuilder("鸢家百宝袋 "+BuildConfig.VERSION_NAME+"\n设备："+Build.MANUFACTURER+" "+Build.MODEL+"\nAndroid "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+"\n");
        text.append("诊断时间：").append(time(System.currentTimeMillis())).append("，当前 PID=").append(android.os.Process.myPid()).append('\n');
        text.append("无障碍设置：").append(enabled(context)?"已开启":"未开启").append("\n服务连接：").append(RuYuanCaptureService.instance!=null?"已连接":"未连接").append('\n');
        text.append("跟打前台运行：").append(RuYuanCaptureService.instance!=null&&RuYuanCaptureService.instance.recorderForegroundActive()?"已开启":"未运行").append('\n');
        try{
            android.os.PowerManager power=context.getSystemService(android.os.PowerManager.class);
            text.append("系统省电模式：").append(power.isPowerSaveMode()?"已开启":"未开启")
                .append("\n系统电池优化豁免：").append(power.isIgnoringBatteryOptimizations(context.getPackageName())?"是":"否").append('\n');
            if(Build.VERSION.SDK_INT>=28)text.append("系统后台限制：").append(context.getSystemService(ActivityManager.class).isBackgroundRestricted()?"已限制":"未限制").append('\n');
            text.append("通知显示权限：").append(context.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()?"已允许":"未允许").append('\n');
            text.append("上述状态为诊断当时值；厂商启动管理的三个开关无法由 App 读取。\n");
        }catch(RuntimeException ignored){text.append("系统暂未提供后台运行状态\n");}
        if(Build.VERSION.SDK_INT>=30)try{
            text.append("\n系统最近退出记录（包括升级/测试停止）：\n");
            var exits=((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getHistoricalProcessExitReasons(context.getPackageName(),0,8);
            if(exits.isEmpty())text.append("暂无记录\n");
            for(var exit:exits)text.append(exitDetails(time(exit.getTimestamp()),exit.getReason(),exit.getProcessName(),exit.getPid(),exit.getStatus(),exit.getImportance(),exit.getPss(),exit.getRss(),exit.getDescription()));
        }catch(RuntimeException ignored){text.append("系统暂未提供退出记录\n");}
        var prefs=context.getSharedPreferences(STORE,0);
        text.append("\n最近服务事件：\n").append(prefs.getString("events","暂无记录\n"));
        text.append("\n最近 Java 异常：\n").append(prefs.getString("crash","暂无记录\n"));
        return text.toString();
    }
}
