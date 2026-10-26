package com.local.yuanatlas;

import android.app.Activity;
import android.content.Context;
import android.util.AtomicFile;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 配队草稿与图鉴/背包草稿隔离，写盘完成后才允许下一次自动点击。 */
public final class TeamGameCapture {
    private final Activity activity;
    TeamGameCapture(Activity activity){this.activity=activity;}
    private static AtomicFile file(Context c){return new AtomicFile(new File(c.getFilesDir(),"team-capture.json"));}
    static synchronized void checkpoint(Context c,JSONObject value)throws Exception{AtomicFile f=file(c);FileOutputStream out=null;try{out=f.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}}
    @JavascriptInterface public String result(){try{return new String(file(activity).readFully(),StandardCharsets.UTF_8);}catch(java.io.FileNotFoundException e){return "null";}catch(Exception e){return "{}";}}
    @JavascriptInterface public String status(){try{JSONObject status=new JSONObject(new RuYuanCapture(activity).status());RuYuanCaptureService s=RuYuanCaptureService.instance;return status.put("running",s!=null&&s.teamRunning()).put("otherRunning",s!=null&&s.running()&&!s.teamRunning()).toString();}catch(Exception e){return "{}";}}
    @JavascriptInterface public String games(){return new RuYuanCapture(activity).games();}
    @JavascriptInterface public void settings(){new RuYuanCapture(activity).settings();}
    @JavascriptInterface public void linkGame(String game){new RuYuanCapture(activity).linkGame(game);}
    @JavascriptInterface public String start(String packageName,String raw){try{
        if(!"null".equals(result()))return "请先处理上次配队采集草稿";
        RuYuanCaptureService service=RuYuanCaptureService.instance;if(service==null)return "请先开启无障碍采集服务";if(service.running())return "已有采集任务运行，请先结束";
        JSONObject config=new JSONObject(raw);new TeamCaptureSession(config);JSONArray agents=config.getJSONArray("agents");if(agents.length()==0||agents.length()>1000)return "请先导入当前游戏图鉴，供姓名和命盘匹配";
        JSONArray games=new JSONArray(games());boolean matched=false;for(int i=0;i<games.length();i++){JSONObject g=games.getJSONObject(i);if(g.getString("package").equals(packageName)&&g.getString("game").equals(config.getString("game")))matched=true;}
        if(!matched)return "请选择当前分区对应的游戏";
        config.put("mode","team-v1");service.configure(config);return new RuYuanCapture(activity).start(packageName);
    }catch(Exception e){return e.getMessage()==null?"无法启动配队采集":e.getMessage();}}
    @JavascriptInterface public void stop(){activity.runOnUiThread(()->{
        RuYuanCaptureService s=RuYuanCaptureService.instance;
        if(s!=null&&s.teamRunning()){s.end(false);return;}
        // 服务已退出时仍可结束磁盘中的暂停草稿，保留已读取的 1～5 名成员。
        synchronized(TeamGameCapture.class){try{JSONObject draft=new JSONObject(result());draft.put("phase","ready");checkpoint(activity,draft);}catch(Exception ignored){/* 原草稿保留，由前端提示重试。 */}}
    });}
    @JavascriptInterface public String discard(String sessionId){synchronized(TeamGameCapture.class){try{RuYuanCaptureService s=RuYuanCaptureService.instance;if(s!=null&&s.teamRunning())return "请先结束配队采集";if(!new JSONObject(result()).getString("sessionId").equals(sessionId))return "草稿已变化，请刷新";file(activity).delete();return "null".equals(result())?"":"草稿清理失败";}catch(Exception e){return "草稿读取失败";}}}
}
