package com.local.yuanatlas;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.AtomicFile;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 仅由内置页面注册：截图写入待核对区，不直接写图鉴。 */
public final class RuYuanCapture {
    private final Activity activity;
    static volatile String message="尚未开始采集";
    RuYuanCapture(Activity activity){this.activity=activity;}
    static synchronized void checkpoint(android.content.Context c,JSONObject result)throws Exception{
        AtomicFile file=new AtomicFile(new File(folder(c),"automatic.json"));FileOutputStream out=null;
        try{out=file.startWrite();out.write(result.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    @JavascriptInterface public String automaticResult(){try{return new String(new AtomicFile(new File(folder(activity),"automatic.json")).readFully(),StandardCharsets.UTF_8);}catch(Exception e){return "null";}}
    @JavascriptInterface public String acknowledgeAutomatic(String sessionId){try{JSONObject result=new JSONObject(automaticResult());if(!sessionId.equals(result.optString("sessionId")))return "采集会话已变化";result.put("phase","saved");checkpoint(activity,result);return "";}catch(Exception e){return "无法更新采集状态";}}
    @JavascriptInterface public String startAutomatic(String packageName,String config){
        if(RuYuanCaptureService.instance!=null&&RuYuanCaptureService.instance.running())return "已有采集任务正在运行，请先结束";
        try{JSONObject previous=new JSONObject(automaticResult());if(!"saved".equals(previous.optString("phase")))return "上次采集结果尚未处理，请先查看结果或放弃草稿";}catch(Exception ignored){}
        try{JSONObject settings=new JSONObject(config);String selectedGame="";JSONArray installed=new JSONArray(games());for(int i=0;i<installed.length();i++)if(packageName.equals(installed.getJSONObject(i).getString("package")))selectedGame=installed.getJSONObject(i).getString("game");if(selectedGame.isEmpty()||!selectedGame.equals(settings.optString("game","ruYuan")))return "所选游戏与采集类型不一致";settings.put("game",selectedGame);RuYuanCaptureService service=RuYuanCaptureService.instance;if(service!=null)service.configure(settings);return start(packageName);}catch(Exception e){return "录入参数无效";}
    }
    @JavascriptInterface public String discardAutomatic(){if(RuYuanCaptureService.instance!=null&&RuYuanCaptureService.instance.running())return "请先结束采集";new AtomicFile(new File(folder(activity),"automatic.json")).delete();return "";}
    static File folder(android.content.Context c){File f=new File(c.getFilesDir(),"ruyuan-capture");f.mkdirs();return f;}
    static synchronized int count(android.content.Context c){int n=0;while(new File(folder(c),"page-"+n+".json").exists())n++;return n;}
    static synchronized void append(android.content.Context c,JSONObject page)throws Exception{
        int n=count(c);if(n>=40)throw new Exception("已采集40页，请回App核对后清理");page.put("index",n+1);
        AtomicFile file=new AtomicFile(new File(folder(c),"page-"+n+".json"));FileOutputStream stream=null;
        try{stream=file.startWrite();stream.write(page.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}catch(Exception e){if(stream!=null)file.failWrite(stream);throw e;}
    }
    @JavascriptInterface public String status(){try{return new JSONObject().put("supported",Build.VERSION.SDK_INT>=30).put("enabled",RuYuanCaptureService.instance!=null).put("running",RuYuanCaptureService.instance!=null&&RuYuanCaptureService.instance.running()).put("count",count(activity)).put("message",message).toString();}catch(Exception e){return "{}";}}
    @JavascriptInterface public String games(){
        JSONArray rows=new JSONArray();
        for(ResolveInfo info:activity.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)){
            String label=info.loadLabel(activity.getPackageManager()).toString();String compact=(label+info.activityInfo.applicationInfo.loadLabel(activity.getPackageManager())).replaceAll("\\s+","").replace("號","号").replace("鳶","鸢");String game=activity.getPreferences(0).getString("capture-game:"+info.activityInfo.packageName,"");if(game.isEmpty())game=compact.contains("代号鸢")?"codeYuan":compact.contains("如鸢")?"ruYuan":"";if(game.isEmpty())continue;
            try{rows.put(new JSONObject().put("name",label).put("game",game).put("package",info.activityInfo.packageName));}catch(Exception ignored){}
        }return rows.toString();
    }
    /** 回合读取沿用已有游戏名称和渠道服关联，离开游戏后不截取其他应用。 */
    static boolean isRecorderGame(android.content.Context context,String packageName){
        if(packageName==null||packageName.isEmpty()||packageName.equals(context.getPackageName()))return false;
        // 沿用游戏采集入口保存的渠道服关联。
        String linked=context.getSharedPreferences(MainActivity.class.getSimpleName(),0).getString("capture-game:"+packageName,"");
        if("codeYuan".equals(linked)||"ruYuan".equals(linked))return true;
        try{
            var manager=context.getPackageManager();String label=manager.getApplicationInfo(packageName,0).loadLabel(manager).toString();
            Intent launch=manager.getLaunchIntentForPackage(packageName);
            if(launch!=null){var info=launch.resolveActivityInfo(manager,0);if(info!=null)label+=info.loadLabel(manager);}
            String compact=label.replaceAll("\\s+","").replace("號","号").replace("鳶","鸢");
            return compact.contains("代号鸢")||compact.contains("如鸢");
        }catch(Exception ignored){return false;}
    }
    /** 用户明确关联渠道服或改名应用；不因未知名称猜测游戏类型。 */
    @JavascriptInterface public void linkGame(String game){
        if(!"codeYuan".equals(game)&&!"ruYuan".equals(game))return;
        activity.runOnUiThread(()->{
            java.util.List<ResolveInfo> apps=activity.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);
            apps.removeIf(info->info.activityInfo.packageName.equals(activity.getPackageName()));
            apps.sort(new ResolveInfo.DisplayNameComparator(activity.getPackageManager()));
            String[] names=new String[apps.size()];for(int i=0;i<apps.size();i++)names[i]=apps.get(i).loadLabel(activity.getPackageManager())+"\n"+apps.get(i).activityInfo.packageName;
            new android.app.AlertDialog.Builder(activity).setTitle("选择"+("codeYuan".equals(game)?"代号鸢":"如鸢")+"对应的已安装应用")
                .setItems(names,(dialog,which)->{
                    String packageName=apps.get(which).activityInfo.packageName;
                    activity.getPreferences(0).edit().putString("capture-game:"+packageName,game).apply();
                    message="已关联游戏，请在识别游戏中选择";
                    if(activity instanceof MainActivity)((MainActivity)activity).refreshCaptureGames();
                }).setNegativeButton("取消",null).show();
        });
    }
    @JavascriptInterface public void settings(){activity.runOnUiThread(()->activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));}
    @JavascriptInterface public String start(String packageName){
        if(Build.VERSION.SDK_INT<30)return "游戏内采集需要Android 11及以上，可改用截图导入";
        RuYuanCaptureService service=RuYuanCaptureService.instance;if(service==null)return "请先开启"+activity.getString(R.string.app_name)+"无障碍服务";
        if(service.running())return "已有采集任务正在运行，请先结束";
        try{
            JSONArray list=new JSONArray(games());boolean allowed=false;for(int i=0;i<list.length();i++)if(packageName.equals(list.getJSONObject(i).getString("package")))allowed=true;
            if(!allowed)return "请选择本机安装的如鸢或代号鸢游戏";
            Intent launch=activity.getPackageManager().getLaunchIntentForPackage(packageName);if(launch==null)return "无法启动所选游戏";
            activity.runOnUiThread(()->{try{service.begin(packageName);activity.startActivity(launch);}catch(Exception e){service.end(false);message="启动采集失败，请重试";}});return "";
        }catch(Exception e){return "启动失败，请确认已安装所选游戏";}
    }
    @JavascriptInterface public String page(int index){if(index<0||index>=40)return "null";try{return new String(new AtomicFile(new File(folder(activity),"page-"+index+".json")).readFully(),StandardCharsets.UTF_8);}catch(Exception e){return "null";}}
    @JavascriptInterface public void stop(){activity.runOnUiThread(()->{if(RuYuanCaptureService.instance!=null)RuYuanCaptureService.instance.end(false);});}
    @JavascriptInterface public String clear(){synchronized(RuYuanCapture.class){if(RuYuanCaptureService.instance!=null&&RuYuanCaptureService.instance.running())return "请先结束采集";for(int i=count(activity)-1;i>=0;i--)if(!new File(folder(activity),"page-"+i+".json").delete())return "清理失败，请重试";}return "";}
}
