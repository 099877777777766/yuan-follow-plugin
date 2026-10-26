package com.local.yuanatlas;

import org.json.*;
import java.util.*;

/** 比较范围按回合保存；每个回合的两个分支分别绑定原表中固定的诸葛亮行动位。 */
final class RecorderDragonActionCondition {
    // 空串统一表示“不操作”，也兼容未选择或旧配置缺项的行动位。
    static final String[] ACTIONS={"↑","↓","A"};
    static boolean validAction(String action){return "".equals(action)||Arrays.asList(ACTIONS).contains(action);}
    static String actionLabel(String action){return "".equals(action)?"不操作":action;}
    static String describeActions(List<String> actions){StringJoiner text=new StringJoiner("、");for(String action:actions)text.add(actionLabel(action));return text.toString();}
    // 包含两端的整数范围；新建默认 0–2，步长及最小端点间隔都为 1。
    int minimum=0,maximum=2;
    void range(int lower,int upper){validateRange(lower,upper);minimum=lower;maximum=upper;}
    static void validateRange(int lower,int upper){if(lower<0||upper>6||upper-lower<1)throw new IllegalArgumentException("龙气范围为 0–6，两端至少间隔 1");}
    String comparisonLabel(){return minimum+"–"+maximum;}
    // 旧版共用范围仅作为读取兜底；新配置块按回合保存独立的不可变范围。
    static final class Range {final int lower,upper;Range(int lower,int upper){validateRange(lower,upper);this.lower=lower;this.upper=upper;}}
    final Map<Integer,Range> ranges=new TreeMap<>();
    void range(int round,int lower,int upper){ranges.put(round,new Range(lower,upper));}
    int minimum(int round){Range value=ranges.get(round);return value==null?minimum:value.lower;}
    int maximum(int round){Range value=ranges.get(round);return value==null?maximum:value.upper;}
    void validateComparison(int round){validateRange(minimum(round),maximum(round));}
    boolean matches(int round,int observed){
        if(observed<0||observed>6)throw new IllegalArgumentException("龙气数量未识别清楚");
        validateComparison(round);return observed>=minimum(round)&&observed<=maximum(round);
    }
    static final class Branches {
        final List<String> matched=new ArrayList<>(),unmatched=new ArrayList<>();
        Branches copy(){Branches b=new Branches();b.matched.addAll(matched);b.unmatched.addAll(unmatched);return b;}
        JSONObject json()throws JSONException{return new JSONObject().put("matched",new JSONArray(matched)).put("unmatched",new JSONArray(unmatched));}
    }
    final Map<Integer,Branches> rounds=new TreeMap<>();
    // 仅用于迁移 0.6.112 的共用列表；迁移后按固定原行动位配置，留空可省略。
    private Branches legacy;
    RecorderDragonActionCondition copy(){
        RecorderDragonActionCondition v=new RecorderDragonActionCondition();v.minimum=minimum;v.maximum=maximum;v.ranges.putAll(ranges);
        for(Map.Entry<Integer,Branches> e:rounds.entrySet())v.rounds.put(e.getKey(),e.getValue().copy());
        v.legacy=legacy==null?null:legacy.copy();return v;
    }
    static List<RecorderFollowTable.Step> slots(RecorderFollowTable table,int round){
        List<RecorderFollowTable.Step> result=new ArrayList<>();int column=RecorderRoundConditions.zhugeColumn(table);
        for(RecorderFollowTable.Step step:table.steps(round))if(step.column==column)result.add(step);return result;
    }
    /** 每个原行动位保留配置槽；新选及旧缺项留空，运行时按不操作跳过。 */
    void prepare(RecorderFollowTable table,Set<Integer> selected){
        for(int round:selected){
            Branches b=rounds.get(round);
            if(b==null){b=legacy==null?new Branches():legacy.copy();rounds.put(round,b);}
            int size=slots(table,round).size();resize(b.matched,size);resize(b.unmatched,size);
        }
        legacy=null;
    }
    private static void resize(List<String> actions,int size){while(actions.size()>size)actions.remove(actions.size()-1);while(actions.size()<size)actions.add("");}
    boolean matches(int observed){
        // 未识别不能进入“不满足”分支。
        if(observed<0||observed>6)throw new IllegalArgumentException("龙气数量未识别清楚");
        validateRange(minimum,maximum);return observed>=minimum&&observed<=maximum;
    }
    List<String> actions(int round,int observed){Branches b=rounds.get(round);if(b==null)throw new IllegalArgumentException("请配置第 "+round+" 回合龙气条件动作");return new ArrayList<>(matches(round,observed)?b.matched:b.unmatched);}
    void validateComparison(){
        validateRange(minimum,maximum);
    }
    void validate(RecorderFollowTable table,int round){
        validateComparison(round);validateBranches(table,round,true,"满足条件","不满足条件");
    }
    /** 龙脉、龙气及四种组合均复用固定行动位校验，空动作按跳过处理。 */
    void validateBranches(RecorderFollowTable table,int round,boolean unmatched,String matchedLabel,String unmatchedLabel){
        List<RecorderFollowTable.Step> slots=slots(table,round);
        if(slots.isEmpty())throw new IllegalArgumentException("第 "+round+" 回合没有诸葛亮的出手位置，请取消该回合选择");
        Branches b=rounds.get(round);if(b==null)throw new IllegalArgumentException("请配置第 "+round+" 回合龙气条件动作");
        validateActions(table,round,slots,b.matched,matchedLabel);if(unmatched)validateActions(table,round,slots,b.unmatched,unmatchedLabel);
    }
    static void validateActions(RecorderFollowTable table,int round,List<RecorderFollowTable.Step> slots,List<String> actions,String branch){
        if(actions.size()!=slots.size())throw new IllegalArgumentException("第 "+round+" 回合"+branch+"必须配置 "+slots.size()+" 个固定行动位");
        for(int i=0;i<actions.size();i++)if(!validAction(actions.get(i))){RecorderFollowTable.Step s=slots.get(i);throw new IllegalArgumentException("请配置"+table.rows.get(s.row).label+"「"+branch+"」第 "+s.order+" 步动作");}
    }
    JSONObject json()throws JSONException{
        JSONObject perRound=new JSONObject();for(Map.Entry<Integer,Branches> e:rounds.entrySet())perRound.put(String.valueOf(e.getKey()),e.getValue().json());
        JSONObject perRange=new JSONObject();for(Map.Entry<Integer,Range> e:ranges.entrySet())perRange.put(String.valueOf(e.getKey()),new JSONObject().put("minimum",e.getValue().lower).put("maximum",e.getValue().upper));
        JSONObject value=new JSONObject().put("minimum",minimum).put("maximum",maximum).put("ranges",perRange);
        if(legacy==null)value.put("rounds",perRound);
        if(legacy!=null){value.put("matched",new JSONArray(legacy.matched));value.put("unmatched",new JSONArray(legacy.unmatched));}return value;
    }
    static RecorderDragonActionCondition parse(JSONObject json)throws JSONException{
        RecorderDragonActionCondition v=new RecorderDragonActionCondition();
        if(json.has("minimum")||json.has("maximum")){v.minimum=json.getInt("minimum");v.maximum=json.getInt("maximum");}
        JSONObject perRange=json.optJSONObject("ranges");
        if(perRange!=null){Iterator<String> keys=perRange.keys();while(keys.hasNext()){String key=keys.next();JSONObject value=perRange.getJSONObject(key);v.range(Integer.parseInt(key),value.getInt("minimum"),value.getInt("maximum"));}}
        JSONObject perRound=json.optJSONObject("rounds");
        if(perRound!=null){Iterator<String> keys=perRound.keys();while(keys.hasNext()){String key=keys.next();v.rounds.put(Integer.parseInt(key),read(perRound.getJSONObject(key)));}}
        else if(json.has("matched")||json.has("unmatched"))v.legacy=read(json);return v;
    }
    private static Branches read(JSONObject json)throws JSONException{Branches b=new Branches();read(json.optJSONArray("matched"),b.matched);read(json.optJSONArray("unmatched"),b.unmatched);return b;}
    private static void read(JSONArray source,List<String> target)throws JSONException{if(source!=null)for(int i=0;i<source.length();i++)target.add(source.getString(i));}
}
