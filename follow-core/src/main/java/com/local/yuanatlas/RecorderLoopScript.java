package com.local.yuanatlas;

import android.content.Context;
import android.graphics.Path;
import org.json.*;
import java.util.*;

/** 用户主动录制的局间操作，仅保存在本机；时间单位毫秒，坐标同时保留原屏幕尺寸。 */
final class RecorderLoopScript {
    static final class Point {
        final float x,y;final long time;
        Point(float x,float y,long time){this.x=x;this.y=y;this.time=time;}
    }
    static final class Action {
        String kind;long delay,duration;final List<Point> points=new ArrayList<>();
        Action(String kind,long delay,long duration){this.kind=kind;this.delay=delay;this.duration=duration;}
        Path path(int width,int height,int recordedWidth,int recordedHeight){
            Path path=new Path();for(int n=0;n<points.size();n++){
                Point p=points.get(n);float x=p.x*width/recordedWidth,y=p.y*height/recordedHeight;
                if(n==0)path.moveTo(x,y);else path.lineTo(x,y);
            }return path;
        }
        String label(){return "swipe".equals(kind)?"滑动":"hold".equals(kind)?"长按":"点击";}
    }
    final List<Action> actions=new ArrayList<>();
    String gamePackage="";int width,height;long tailDelay;
    void validate(){
        if(width<=0||height<=0||gamePackage.isEmpty()||actions.isEmpty())throw new IllegalArgumentException("请先录制操作");
        if(tailDelay<0)throw new IllegalArgumentException("等待时间不能小于 0");
        for(Action action:actions){
            if(!Arrays.asList("tap","hold","swipe").contains(action.kind)||action.delay<0||action.duration<=0||action.points.isEmpty())throw new IllegalArgumentException("录制操作数据无效");
            long previous=-1;
            for(Point point:action.points){
                if(!Float.isFinite(point.x)||!Float.isFinite(point.y)||point.x<0||point.y<0||point.x>=width||point.y>=height||point.time<previous||point.time>action.duration)throw new IllegalArgumentException("录制轨迹数据无效");
                previous=point.time;
            }
        }
    }
    JSONObject json()throws JSONException{
        validate();JSONArray list=new JSONArray();
        for(Action action:actions){
            JSONArray points=new JSONArray();for(Point point:action.points)points.put(new JSONArray().put(point.x).put(point.y).put(point.time));
            list.put(new JSONObject().put("kind",action.kind).put("delay",action.delay).put("duration",action.duration).put("points",points));
        }
        return new JSONObject().put("version",1).put("package",gamePackage).put("width",width).put("height",height).put("tailDelay",tailDelay).put("actions",list);
    }
    static RecorderLoopScript parse(String raw)throws JSONException{
        JSONObject json=new JSONObject(raw);if(json.getInt("version")!=1)throw new IllegalArgumentException("录制数据版本不支持");
        RecorderLoopScript script=new RecorderLoopScript();script.gamePackage=json.getString("package");script.width=json.getInt("width");script.height=json.getInt("height");script.tailDelay=json.getLong("tailDelay");
        JSONArray list=json.getJSONArray("actions");
        for(int n=0;n<list.length();n++){
            JSONObject value=list.getJSONObject(n);Action action=new Action(value.getString("kind"),value.getLong("delay"),value.getLong("duration"));
            JSONArray points=value.getJSONArray("points");for(int p=0;p<points.length();p++){JSONArray point=points.getJSONArray(p);action.points.add(new Point((float)point.getDouble(0),(float)point.getDouble(1),point.getLong(2)));}
            script.actions.add(action);
        }script.validate();return script;
    }
    /** 按操作表内容绑定，避免在另一个关卡误用上一张表的“再次挑战”位置。 */
    static String key(RecorderFollowTable table){
        StringBuilder key=new StringBuilder().append(table.game).append('\n').append(table.tableName).append('\n').append(table.roleName);
        for(String agent:table.agents)key.append('\n').append(agent);
        for(RecorderFollowTable.Row row:table.rows){key.append('\n').append(row.label);for(int c=0;c<5;c++)key.append('|').append(row.cells[c]).append(':').append(row.colors[c]);}
        try{
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(key.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    void save(Context context,String key)throws JSONException{
        if(!context.getSharedPreferences("recorder-loop",0).edit().putString(key,json().toString()).commit())throw new IllegalArgumentException("录制操作保存失败");
    }
    static RecorderLoopScript load(Context context,String key){
        String raw=context.getSharedPreferences("recorder-loop",0).getString(key,null);if(raw==null)return null;
        try{return parse(raw);}catch(Exception invalid){return null;}
    }
}
