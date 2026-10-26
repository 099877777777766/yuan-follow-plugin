package com.local.yuanatlas;

import android.app.Activity;
import android.content.Context;
import android.util.AtomicFile;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** v2 草稿独立存放；只在核对确认后清理，旧版失败草稿不能混入新算法。 */
public final class StoneGameCapture {
    private final Activity activity;
    StoneGameCapture(Activity value){activity=value;}
    private static File folder(Context c){File dir=new File(c.getFilesDir(),"stone-capture-v2");dir.mkdirs();return dir;}
    private static void write(File target,JSONObject value)throws Exception{
        AtomicFile file=new AtomicFile(target);FileOutputStream out=null;
        try{out=file.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);if(!new String(file.readFully(),StandardCharsets.UTF_8).equals(value.toString()))throw new java.io.IOException("草稿写入校验失败");}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    static synchronized void checkpoint(Context c,JSONObject result)throws Exception{write(new File(folder(c),"result.json"),result);}
    static synchronized void append(Context c,StoneCaptureSession session)throws Exception{
        if(session.pendingPage==null)return;
        int count=session.result.getInt("count"),index=count+1;
        write(new File(folder(c),"page-"+index+".json"),session.pendingPage);
        session.result.put("count",index);
        try{checkpoint(c,session.result);}catch(Exception e){session.result.put("count",count);throw e;}
        session.pendingPage=null;
    }
    private static String read(File file){try{return new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8);}catch(Exception e){return "null";}}
    @JavascriptInterface public String result(){return read(new File(folder(activity),"result.json"));}
    @JavascriptInterface public String page(int index){return index<1||index>250?"null":read(new File(folder(activity),"page-"+index+".json"));}
    @JavascriptInterface public String status(){try{
        JSONObject status=new JSONObject(new RuYuanCapture(activity).status());RuYuanCaptureService service=RuYuanCaptureService.instance;
        return status.put("running",service!=null&&service.stoneRunning()).put("otherRunning",service!=null&&service.running()&&!service.stoneRunning()).toString();
    }catch(Exception e){return "{}";}}
    @JavascriptInterface public String games(){return new RuYuanCapture(activity).games();}
    @JavascriptInterface public void settings(){new RuYuanCapture(activity).settings();}
    @JavascriptInterface public String start(String packageName,String raw){
        try{
            // 先检查旧草稿和共用服务占用；参数验证不改变正在运行的图鉴任务。
            if(!"null".equals(result()))return "有未处理的星石草稿，请先核对或放弃";
            RuYuanCaptureService service=RuYuanCaptureService.instance;if(service==null)return "请先开启无障碍采集服务";
            if(service.running())return "已有采集任务运行，请先结束当前任务";
            JSONObject config=new JSONObject(raw);new StoneCaptureSession(config);
            org.json.JSONArray installed=new org.json.JSONArray(games());boolean matching=false;
            for(int i=0;i<installed.length();i++){JSONObject game=installed.getJSONObject(i);if(packageName.equals(game.getString("package"))&&config.getString("game").equals(game.getString("game")))matching=true;}
            if(!matching)return "请选择与当前角色一致的游戏";
            config.put("mode","stones-v2");
            service.configure(config);return new RuYuanCapture(activity).start(packageName);
        }catch(Exception e){return e.getMessage()==null?"无法启动星石采集":e.getMessage();}
    }
    @JavascriptInterface public void stop(){activity.runOnUiThread(()->{RuYuanCaptureService s=RuYuanCaptureService.instance;if(s!=null&&s.stoneRunning())s.end(false);});}
    @JavascriptInterface public String discard(String sessionId){synchronized(StoneGameCapture.class){try{
        RuYuanCaptureService s=RuYuanCaptureService.instance;if(s!=null&&s.stoneRunning())return "请先结束星石采集";
        JSONObject result=new JSONObject(result());if(!sessionId.equals(result.getString("sessionId")))return "采集草稿已变化，请刷新";
        // 先删除索引，防止清理中断后再次显示已保存的草稿；页文件会在下次会话覆盖。
        new AtomicFile(new File(folder(activity),"result.json")).delete();
        if(!"null".equals(result()))return "草稿清理失败，请重试";
        for(int i=1;i<=250;i++)new AtomicFile(new File(folder(activity),"page-"+i+".json")).delete();return "";
    }catch(Exception e){return "草稿读取失败，请刷新";}}}
}
