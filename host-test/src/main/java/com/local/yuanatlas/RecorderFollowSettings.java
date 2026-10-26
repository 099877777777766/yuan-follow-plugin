package com.local.yuanatlas;

import android.content.Context;
import org.json.JSONObject;
import java.util.*;

/** 只保存时序和位置编号，不保存截图、密钥或解码后的操作表。 */
final class RecorderFollowSettings {
    static final String[] ACTIONS={"A","↑","↓","圈"};
    // 默认延迟按用户截图设置；读取时已保存的各项数值优先，不覆盖自定义配置。
    final int[] before={200,200,200,200},after={2200,2500,2000,2000};
    final Map<String,Integer> targets=new LinkedHashMap<>();
    // 仅随本表保存；0 表示只跳过切敌、不猜嘲讽者编号，1–5 为已知嘲讽位置。
    final Map<Integer,Integer> tauntRounds=new TreeMap<>();
    int enemies=5,initialTarget=1;
    // 回合计数可能早于游戏恢复接收输入；该等待只发生在每轮开头，可由用户设为 0。
    int roundStartDelay=2000;
    // 勾选后按动作决定是否等待；取消则每一步都等待，不再把取消解释为关闭识别。
    boolean selectiveMotionWait=true;
    boolean detectEnemyDeaths=true;
    int motionExtraWait=8000;
    RecorderFollowSettings(){for(String color:RecorderFollowTable.COLORS)targets.put(color,0);}
    static RecorderFollowSettings load(Context context){
        RecorderFollowSettings value=new RecorderFollowSettings();
        try{JSONObject json=new JSONObject(context.getSharedPreferences("recorder-follow",0).getString("settings","{}"));
            value.enemies=json.optInt("enemies",5);value.initialTarget=json.optInt("initial",1);
            value.roundStartDelay=json.optInt("roundStartDelay",value.roundStartDelay);
            // 沿用旧开关的勾选状态，新字段明确保存等待策略；缺少配置时默认勾选按需等待。
            value.selectiveMotionWait=json.optBoolean("selectiveMotionWait",json.optBoolean("waitForMotion",value.selectiveMotionWait));value.motionExtraWait=json.optInt("motionExtraWait",value.motionExtraWait);
            value.detectEnemyDeaths=json.optBoolean("detectEnemyDeaths",value.detectEnemyDeaths);
            for(int i=0;i<4;i++){value.before[i]=json.optInt("before"+i,value.before[i]);value.after[i]=json.optInt("after"+i,value.after[i]);}
            for(String color:RecorderFollowTable.COLORS)value.targets.put(color,json.optInt(color,0));value.validate();
        }catch(Exception ignored){return new RecorderFollowSettings();}return value;
    }
    /** 延迟仍是通用设置；新表敌人数按实际颜色数，已保存的本表手动数量优先。 */
    static RecorderFollowSettings load(Context context,RecorderFollowTable table){
        RecorderFollowSettings value=load(context);if(table==null)return value;
        List<String> colors=table.usedColors();
        // 无色开放站位输入，但不额外增加默认敌人数；仍按有色种数推算，全无色默认 1。
        int fallback=Math.max(1,colors.size()-(colors.contains("none")?1:0));JSONObject saved=null;
        try{String raw=context.getSharedPreferences("recorder-follow-targets",0).getString(RecorderLoopScript.key(table),null);if(raw!=null)saved=new JSONObject(raw);}catch(Exception ignored){}
        int count=saved==null?fallback:saved.optInt("enemies",fallback);value.enemies=count>=1&&count<=5?count:fallback;
        int initial=saved==null?1:saved.optInt("initial",1);value.initialTarget=initial>=1&&initial<=value.enemies?initial:1;
        for(String color:RecorderFollowTable.COLORS){
            // 无色与其他可见颜色一同恢复；隐藏颜色及超出新敌人数的旧编号清零。
            int target=saved==null?value.targets.getOrDefault(color,0):saved.optInt(color,0);
            value.targets.put(color,colors.contains(color)&&target>=0&&target<=value.enemies?target:0);
        }
        JSONObject taunts=saved==null?null:saved.optJSONObject("tauntRounds");
        if(taunts!=null)for(int round:RecorderRoundConditions.rounds(table))if(taunts.has(String.valueOf(round))){
            int target=taunts.optInt(String.valueOf(round),-1);if(target>=0&&target<=value.enemies)value.tauntRounds.put(round,target);
        }
        return value;
    }
    void validate(){
        if(enemies<1||enemies>5||initialTarget<1||initialTarget>enemies)throw new IllegalArgumentException("敌人数为 1–5，起始位置不能超过敌人数");
        if(roundStartDelay<0||roundStartDelay>60000)throw new IllegalArgumentException("回合开始延迟范围为 0–60000 ms");
        if(motionExtraWait<0||motionExtraWait>60000)throw new IllegalArgumentException("动画最多追加等待范围为 0–60000 ms");
        for(int i=0;i<4;i++)if(before[i]<0||before[i]>60000||after[i]<0||after[i]>60000)throw new IllegalArgumentException("延迟范围为 0–60000 ms");
        for(int target:targets.values())if(target<0||target>enemies)throw new IllegalArgumentException("颜色位置不能超过敌人数");
        for(Map.Entry<Integer,Integer> entry:tauntRounds.entrySet())if(entry.getKey()<1||entry.getValue()<0||entry.getValue()>enemies)throw new IllegalArgumentException("嘲讽回合须为正数，目标为 0–敌人数");
    }
    void save(Context context)throws Exception{
        validate();JSONObject json=new JSONObject().put("enemies",enemies).put("initial",initialTarget).put("roundStartDelay",roundStartDelay);
        json.put("selectiveMotionWait",selectiveMotionWait).put("motionExtraWait",motionExtraWait);
        json.put("detectEnemyDeaths",detectEnemyDeaths);
        for(int i=0;i<4;i++)json.put("before"+i,before[i]).put("after"+i,after[i]);
        for(String color:targets.keySet())json.put(color,targets.get(color));
        if(!context.getSharedPreferences("recorder-follow",0).edit().putString("settings",json.toString()).commit())throw new IllegalArgumentException("设置保存失败");
    }
    /** 同一张表重新导入可恢复手动校正，新表不继承上一张表的敌人数。 */
    void save(Context context,RecorderFollowTable table)throws Exception{
        List<String> colors=table.usedColors();for(String color:RecorderFollowTable.COLORS)if(!colors.contains(color))targets.put(color,0);
        validate();JSONObject local=new JSONObject().put("enemies",enemies).put("initial",initialTarget);
        JSONObject taunts=new JSONObject();Set<Integer> rounds=RecorderRoundConditions.rounds(table);
        for(Map.Entry<Integer,Integer> entry:tauntRounds.entrySet())if(rounds.contains(entry.getKey()))taunts.put(String.valueOf(entry.getKey()),entry.getValue());
        local.put("tauntRounds",taunts);
        for(String color:colors)local.put(color,targets.getOrDefault(color,0));
        if(!context.getSharedPreferences("recorder-follow-targets",0).edit().putString(RecorderLoopScript.key(table),local.toString()).commit())throw new IllegalArgumentException("本表颜色映射保存失败");
        save(context);
    }
    int delay(String action,boolean pre){int i=Arrays.asList(ACTIONS).indexOf(action);return i<0?0:(pre?before[i]:after[i]);}
    static String colorName(String color){
        switch(color){case "red":return "红色";case "blue":return "蓝色";case "green":return "绿色";case "yellow":return "黄色";case "gray":return "灰色";default:return "无色";}
    }
    /** 正数表示向左滑，负数向右滑；循环取最短路径。 */
    static int route(int current,int target,int count){int left=(target-current+count)%count,right=left-count;return left<=-right?left:right;}
}
