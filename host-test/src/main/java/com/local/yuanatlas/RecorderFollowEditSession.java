package com.local.yuanatlas;

import android.content.Intent;
import org.json.JSONObject;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 会话只存在于内存；原表、作者和授权来自导入快照，不写任何本地操作表。 */
final class RecorderFollowEditSession {
    private static volatile RecorderFollowEditSession active;
    final String token=UUID.randomUUID().toString();
    private final String snapshot;
    private final BooleanSupplier current;
    private final Consumer<RecorderFollowTable> update;
    private final Runnable restore;
    private boolean applied;
    RecorderFollowEditSession(RecorderFollowTable source,BooleanSupplier current,Consumer<RecorderFollowTable> update,Runnable restore){
        this.snapshot=source.editPayload;this.current=current;this.update=update;this.restore=restore;
    }
    void open(android.content.Context context){
        if(active!=null)active.close();active=this;
        try{context.startActivity(new Intent(context,RecorderFollowEditorActivity.class).putExtra("followSession",token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(RuntimeException error){close();throw error;}
    }
    static RecorderFollowEditSession require(String token){
        RecorderFollowEditSession session=active;
        if(session==null||!session.token.equals(token))throw new IllegalArgumentException("跟打编辑会话已结束，请重新打开调整表操作");
        return session;
    }
    String read(){return snapshot;}
    /** 主线程提交；导入或实时更新已换表时，旧编辑结果不能覆盖新表。 */
    void apply(String json)throws Exception{
        if(active!=this||applied||!current.getAsBoolean())throw new IllegalArgumentException("操作表已更新或关闭，请重新打开调整表操作");
        RecorderFollowTable next=edited(snapshot,json);update.accept(next);applied=true;
    }
    static RecorderFollowTable edited(String snapshot,String json)throws Exception{
        if(json==null||json.length()>1024*1024||json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024*1024)throw new IllegalArgumentException("操作表过大");
        JSONObject original=new JSONObject(snapshot),draft=new JSONObject(json);
        // 即使前端误传接收者资料，也只能沿用导入作者、游戏和原始授权。
        draft.put("type","yuan-recorder-local-table").put("version",1).put("roleName",original.getString("roleName")).put("game",original.getString("game"));
        // 原生再次锁定阵容和位置，拒绝旧页面或异常载荷换人；姓名也沿用原表。
        if(!draft.getJSONObject("state").getJSONArray("slots").toString().equals(original.getJSONObject("state").getJSONArray("slots").toString()))throw new IllegalArgumentException("跟打调整不允许更换密探");
        draft.put("agents",original.getJSONArray("agents"));
        draft.getJSONObject("state").put("allowAutoFollow",original.getJSONObject("state").optBoolean("allowAutoFollow",true));
        return RecorderFollowTable.parseLocal(draft.toString());
    }
    void close(){if(active==this){active=null;restore.run();}}
}
