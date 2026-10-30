package com.local.yuanatlas;
import android.app.Instrumentation;import android.os.Bundle;import org.json.*;
/** 独立宿主中的真实 Android 协议、识别和模拟手势回归，不操作真实游戏。 */
public final class PluginChecks extends Instrumentation {
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle result=new Bundle();try{
 String table=new String(RecorderFollowChecks.asset(this,"table.json"),java.nio.charset.StandardCharsets.UTF_8);
 RecorderFollowChecks.check(RecorderFollowTable.parseLocal(table).allowAutoFollow,"fixture allows follow");
 JSONObject blocked=new JSONObject(table);blocked.getJSONObject("state").put("allowAutoFollow",false);
 RecorderFollowChecks.check(!RecorderFollowTable.parseLocal(blocked.toString()).allowAutoFollow,"disabled authorization must remain false");
 try{YuanFollow.validatePayloadSize("x".repeat(1024*1024+1));throw new AssertionError("oversize accepted");}catch(IllegalArgumentException expected){}
 try{YuanFollow.validateTarget(getTargetContext(),getTargetContext().getPackageName());throw new AssertionError("self target accepted");}catch(IllegalArgumentException expected){}
 String report=RecorderFollowChecks.run(this)+"\n"+RecorderStarColorChecks.run(this);result.putString("stream","\nPASS plugin boundary, authorization, payload bounds\n"+report+"\n");finish(-1,result);
 }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);}}
}
