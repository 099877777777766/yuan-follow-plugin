package com.local.yuanatlas;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 对外唯一业务入口。加载只显示浮窗，实际手势必须由用户在浮窗中主动开始。 */
public final class YuanFollow {
    public static final String VERSION="1.0.0";
    public interface Callback { void onResult(boolean ok,String code,String message); }
    public interface Listener { void onEvent(String type,String message); }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->new Thread(r,"yuan-follow-import"));
    private static final CopyOnWriteArraySet<Listener> listeners=new CopyOnWriteArraySet<>();
    private static int generation;
    private static Pending pending;
    private static String target="";
    private YuanFollow(){}
    private static final class Pending {
        final int id;final Callback callback;final String gamePackage;final FollowAccessibilityService service;
        Pending(int id,Callback callback,String gamePackage,FollowAccessibilityService service){this.id=id;this.callback=callback;this.gamePackage=gamePackage;this.service=service;}
    }
    static android.content.ComponentName serviceComponent(Context context){
        // 宿主可复用已有无障碍服务；没有配置时使用插件自带服务。
        String name=FollowAccessibilityService.class.getName();
        try{android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(context.getPackageName(),android.content.pm.PackageManager.GET_META_DATA);if(info.metaData!=null)name=info.metaData.getString("com.yuan.follow.service",name);}catch(Exception ignored){}
        return new android.content.ComponentName(context,name);
    }
    public static void addListener(Listener listener){if(listener!=null)listeners.add(listener);}
    public static void removeListener(Listener listener){listeners.remove(listener);}
    // 回调始终在主线程，宿主异常不能破坏跟打服务。
    static void event(String type,String message){MAIN.post(()->{for(Listener l:listeners)try{l.onEvent(type,message);}catch(RuntimeException ignored){}});}
    static boolean isTarget(Context context,String name){return !target.isEmpty()&&target.equals(name)&&!name.equals(context.getPackageName());}
    public static String status(Context context){
        if(Build.VERSION.SDK_INT<30)return "unsupported";
        FollowAccessibilityService service=FollowAccessibilityService.instance;
        return service!=null?"ready":CaptureDiagnostics.enabled(context)?"disconnected":"disabled";
    }
    public static String diagnostics(Context context){return CaptureDiagnostics.report(context.getApplicationContext());}
    public static void openAccessibilitySettings(Activity activity){activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
    /** 输入遵循现有 yuan-recorder-local-table v1；不改写作者、阵容或允许跟打标记。 */
    public static void openTable(Context context,String gamePackage,String json,Callback callback){
        Context app=context.getApplicationContext();MAIN.post(()->{
            int id=begin(app,gamePackage,callback);if(id<0)return;
            WORK.execute(()->{try{validatePayloadSize(json);loaded(id,RecorderFollowTable.parseLocal(json));}catch(Exception error){fail(id,"INVALID_TABLE",error.getMessage());}});
        });
    }
    public static void openCode(Context context,String gamePackage,String code,Callback callback){
        Context app=context.getApplicationContext();MAIN.post(()->{int id=begin(app,gamePackage,callback);if(id<0)return;
            WORK.execute(()->{try{loaded(id,RecorderFollowTable.decode(app,code));}catch(Exception error){fail(id,"INVALID_CODE",error.getMessage());}});
        });
    }
    /** 使用系统选择器，不要求宿主申请全盘文件权限，也不接受远程图片地址。 */
    public static void chooseImage(Activity activity,String gamePackage,Callback callback){openImage(activity,gamePackage,null,callback);}
    public static void openImage(Activity activity,String gamePackage,Uri uri,Callback callback){
        MAIN.post(()->{
            if(activity.isFinishing()||activity.isDestroyed()){reply(callback,false,"ACTIVITY_CLOSED","页面已关闭");return;}
            if(uri!=null&&!"content".equals(uri.getScheme())){reply(callback,false,"INVALID_URI","只接受本机 content URI");return;}
            int id=begin(activity,gamePackage,callback);if(id<0)return;
            try{Intent intent=new Intent(activity,FollowImportActivity.class).putExtra("request",id);if(uri!=null)intent.setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);activity.startActivity(intent);}
            catch(RuntimeException error){fail(id,"IMPORT_FAILED",error.getMessage());}
        });
    }
    public static void pause(){MAIN.post(()->{FollowAccessibilityService s=FollowAccessibilityService.instance;if(s!=null)s.pauseRecorderFromNotification();});}
    /** 关闭会话同时使尚未完成的解码失效，迟到结果不得重建浮窗。 */
    public static void close(){MAIN.post(()->{cancelPending();generation++;target="";FollowAccessibilityService s=FollowAccessibilityService.instance;if(s!=null)s.closeRecorderRounds();});}
    public static void setTheme(Context context,String json,Callback callback){Context app=context.getApplicationContext();MAIN.post(()->{
        try{RecorderOverlayTheme theme=RecorderOverlayTheme.save(app,json);FollowAccessibilityService s=FollowAccessibilityService.instance;if(s!=null)s.updateRecorderTheme(theme);reply(callback,true,"OK","主题已更新");}
        catch(Exception error){reply(callback,false,"INVALID_THEME",error.getMessage());}
    });}
    static void validatePayloadSize(String json){if(json==null||json.length()>1024*1024||json.getBytes(StandardCharsets.UTF_8).length>1024*1024)throw new IllegalArgumentException("操作表数据过大或为空");}
    static void validateTarget(Context context,String name){
        if(name==null||!name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")||name.equals(context.getPackageName()))throw new IllegalArgumentException("请指定实际游戏包名");
        if(context.getPackageManager().getLaunchIntentForPackage(name)==null)throw new IllegalArgumentException("目标游戏未安装或不可见");
    }
    private static int begin(Context context,String name,Callback callback){
        if(!"ready".equals(status(context))){reply(callback,false,"NOT_READY","需要 Android 11 以上并连接自动跟打无障碍服务");return -1;}
        try{validateTarget(context,name);}catch(Exception e){reply(callback,false,"INVALID_TARGET",e.getMessage());return -1;}
        cancelPending();int id=++generation;pending=new Pending(id,callback,name,FollowAccessibilityService.instance);return id;
    }
    static boolean current(int id){return pending!=null&&pending.id==id&&generation==id;}
    private static void cancelPending(){if(pending!=null){Callback callback=pending.callback;pending=null;reply(callback,false,"CANCELLED","导入已取消");}}
    static void loaded(int id,RecorderFollowTable table){MAIN.post(()->{
        if(!current(id))return;
        if(!table.allowAutoFollow){fail(id,"NOT_ALLOWED","该表不允许自动跟打");return;}
        FollowAccessibilityService service=FollowAccessibilityService.instance;
        if(service==null||service!=pending.service){fail(id,"DISCONNECTED","无障碍服务已断开，请重新导入");return;}
        try{target=pending.gamePackage;service.showRecorderFollow(table);complete(id,true,"OK","操作表已载入，请进入游戏并勾选开始跟打");}
        catch(RuntimeException error){service.closeRecorderRounds();fail(id,"OPEN_FAILED",error.getMessage());}
    });}
    static void fail(int id,String code,String message){MAIN.post(()->complete(id,false,code,message==null?"导入失败":message));}
    private static void complete(int id,boolean ok,String code,String message){if(!current(id))return;Callback callback=pending.callback;pending=null;reply(callback,ok,code,message);}
    private static void reply(Callback callback,boolean ok,String code,String message){if(callback!=null)try{callback.onResult(ok,code,message);}catch(RuntimeException ignored){}}
}
