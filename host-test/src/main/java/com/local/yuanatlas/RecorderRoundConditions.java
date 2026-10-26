package com.local.yuanatlas;

import android.content.Context;
import org.json.*;
import java.util.*;

/** 检查规则绑定当前操作表；默认关闭，不把上一张攻略的重开条件带到新表。 */
final class RecorderRoundConditions {
    final Map<Integer,String> starTargets=new TreeMap<>();
    final Set<Integer> dragonRounds=new TreeSet<>();
    // 回合 -> 期望的龙脉内外状态；与条件动作独立，同回合只能保留一种要求。
    final Map<Integer,Integer> veinTargets=new TreeMap<>();
    final Set<Integer> dragonActionRounds=new TreeSet<>();
    final Set<Integer> veinActionRounds=new TreeSet<>();
    // 旧字段仅用于读取版本 7 配置；运行和新保存都采用四组合，不再使用优先级。
    final Map<Integer,String> actionPriority=new TreeMap<>();
    final Map<Integer,List<String>> jointActions=new TreeMap<>();
    RecorderJointActionCondition combinations=new RecorderJointActionCondition();
    RecorderDragonActionCondition veinActions=new RecorderDragonActionCondition();
    // 回合 -> 五个密探列的位掩码；续行与所属正常回合共用同一次开始检查。
    final Map<Integer,Integer> deathCells=new TreeMap<>();
    RecorderDragonActionCondition dragonActions=new RecorderDragonActionCondition();
    // 新建规则默认至少 2 龙气；已保存的数量在解析时仍优先采用。
    int dragonCount=2;
    RecorderFazhengCondition fazheng=new RecorderFazhengCondition();
    boolean actionRequired(int round){return dragonActionRounds.contains(round)||veinActionRounds.contains(round);}
    boolean joint(int round){return dragonActionRounds.contains(round)&&veinActionRounds.contains(round);}
    boolean veinFirst(int round){return "vein".equals(actionPriority.get(round));}
    Set<Integer> actionRounds(){Set<Integer> rounds=new TreeSet<>(dragonActionRounds);rounds.addAll(veinActionRounds);return rounds;}
    void prepareActions(RecorderFollowTable table,Set<Integer> selected){
        dragonActions.prepare(table,selected);veinActions.prepare(table,selected);
        combinations.prepare(table,selected);
    }
    boolean required(int round){return starTargets.containsKey(round)||dragonRounds.contains(round)||veinTargets.containsKey(round)||actionRequired(round)||deathCells.containsKey(round);}
    Set<Integer> roundsForVein(int state){Set<Integer> result=new TreeSet<>();for(Map.Entry<Integer,Integer> e:veinTargets.entrySet())if(e.getValue()==state)result.add(e.getKey());return result;}
    /** 后确认的内外选择覆盖冲突回合，清空其中一组不影响另一组。 */
    void setVeinRounds(int state,Set<Integer> rounds){veinTargets.entrySet().removeIf(e->e.getValue()==state);for(int round:rounds)veinTargets.put(round,state);}
    boolean acceptsVein(int round,int state){return !veinTargets.containsKey(round)||veinTargets.get(round)==state;}
    String starAt(int round){return starTargets.get(round);}
    Set<Integer> roundsForStar(String color){Set<Integer> result=new TreeSet<>();for(Map.Entry<Integer,String> e:starTargets.entrySet())if(e.getValue().equals(color))result.add(e.getKey());return result;}
    /** 同一回合只能期待一种星色；确认新选择时替换该回合原先的颜色。 */
    void setStarRounds(String color,Set<Integer> rounds){starTargets.entrySet().removeIf(e->e.getValue().equals(color));for(int round:rounds)starTargets.put(round,color);}
    RecorderRoundConditions copy(){RecorderRoundConditions c=new RecorderRoundConditions();c.fazheng=fazheng.copy();c.starTargets.putAll(starTargets);c.dragonRounds.addAll(dragonRounds);c.veinTargets.putAll(veinTargets);c.dragonCount=dragonCount;c.dragonActionRounds.addAll(dragonActionRounds);c.dragonActions=dragonActions.copy();c.deathCells.putAll(deathCells);c.veinActionRounds.addAll(veinActionRounds);c.veinActions=veinActions.copy();c.combinations=combinations.copy();c.actionPriority.putAll(actionPriority);for(Map.Entry<Integer,List<String>> e:jointActions.entrySet())c.jointActions.put(e.getKey(),new ArrayList<>(e.getValue()));return c;}
    static int zhugeColumn(RecorderFollowTable table){
        int found=-1;
        for(int c=0;c<table.agents.length;c++){
            String name=table.agents[c];if(name==null)continue;name=name.replaceAll("\\s","");
            if(name.contains("诸葛亮")||name.contains("諸葛亮")){if(found>=0)return -1;found=c;}
        }return found;
    }
    static Set<Integer> rounds(RecorderFollowTable table){Set<Integer> values=new TreeSet<>();for(RecorderFollowTable.Row row:table.rows)if(row.round>0)values.add(row.round);return values;}
    void validate(RecorderFollowTable table){
        validateBase(table);
        for(int round:actionRounds())validateActions(table,round);
    }
    void validateActions(RecorderFollowTable table,int round){
        if(dragonActionRounds.contains(round))dragonActions.validateComparison(round);
        if(joint(round))combinations.validate(table,round);
        else if(dragonActionRounds.contains(round))dragonActions.validateBranches(table,round,true,"满足条件","不满足条件");
        else if(veinActionRounds.contains(round))veinActions.validateBranches(table,round,true,"龙脉内","龙脉外");
    }
    List<String> branchActions(int round,String source,boolean matched){
        if(source.equals("joint-inside")||source.equals("joint-outside"))return new ArrayList<>(combinations.actions(round,source.equals("joint-inside"),matched));
        RecorderDragonActionCondition.Branches branches=(source.equals("vein")?veinActions:dragonActions).rounds.get(round);
        if(branches==null)throw new IllegalArgumentException("本回合条件动作尚未配置");
        return new ArrayList<>(matched?branches.matched:branches.unmatched);
    }
    /** 先校验回合与重开规则，兼容读取旧配置；行动位留空由后续校验按不操作接受。 */
    void validateBase(RecorderFollowTable table){
        fazheng.validate(table);
        for(String star:starTargets.values())if(!"purple".equals(star)&&!"orange".equals(star))throw new IllegalArgumentException("请选择紫星或橙星");
        if(dragonCount<1||dragonCount>6)throw new IllegalArgumentException("龙气数量范围为 1–6");
        Set<Integer> available=rounds(table);
        if(!available.containsAll(starTargets.keySet())||!available.containsAll(dragonRounds)||!available.containsAll(veinTargets.keySet())||!available.containsAll(actionRounds())||!available.containsAll(deathCells.keySet()))throw new IllegalArgumentException("请选择当前操作表中的回合");
        for(int state:veinTargets.values())if(state!=RecorderDragonVeinVision.INSIDE&&state!=RecorderDragonVeinVision.OUTSIDE)throw new IllegalArgumentException("请选择龙脉内或龙脉外");
        for(int mask:deathCells.values())if(mask<1||mask>31)throw new IllegalArgumentException("请选择要检测阵亡的密探单元格");
        if((!dragonRounds.isEmpty()||!veinTargets.isEmpty()||!actionRounds().isEmpty())&&zhugeColumn(table)<0)throw new IllegalArgumentException("操作表需有且仅有一列密探名包含诸葛亮，才能配置龙气和龙脉检测或条件动作");
    }
    boolean acceptsDragon(int count){return count>=dragonCount;}
    JSONObject json()throws JSONException{
        JSONObject stars=new JSONObject();for(Map.Entry<Integer,String> e:starTargets.entrySet())stars.put(String.valueOf(e.getKey()),e.getValue());
        JSONObject deaths=new JSONObject();for(Map.Entry<Integer,Integer> e:deathCells.entrySet())deaths.put(String.valueOf(e.getKey()),e.getValue());
        JSONObject veins=new JSONObject();for(Map.Entry<Integer,Integer> e:veinTargets.entrySet())veins.put(String.valueOf(e.getKey()),e.getValue());
        return new JSONObject().put("veinTargets",veins).put("version",9).put("fazheng",fazheng.json()).put("deathCells",deaths).put("starTargets",stars).put("dragonRounds",new JSONArray(dragonRounds)).put("dragonCount",dragonCount)
            .put("dragonActionRounds",new JSONArray(dragonActionRounds)).put("dragonActions",dragonActions.json())
            .put("veinActionRounds",new JSONArray(veinActionRounds)).put("veinActions",veinActions.json()).put("combinations",combinations.json());
    }
    static RecorderRoundConditions parse(String raw)throws JSONException{
        JSONObject json=new JSONObject(raw);RecorderRoundConditions c=new RecorderRoundConditions();c.dragonCount=json.optInt("dragonCount",c.dragonCount);c.fazheng=RecorderFazhengCondition.parse(json.optJSONObject("fazheng"));
        if(json.has("starTargets")){
            JSONObject stars=json.getJSONObject("starTargets");Iterator<String> keys=stars.keys();while(keys.hasNext()){String key=keys.next();c.starTargets.put(Integer.parseInt(key),stars.getString(key));}
        }else{
            // 旧版全表共用一种星色；按原选择逐回合迁移，保留已保存的规则。
            Set<Integer> rounds=new TreeSet<>();read(json.optJSONArray("starRounds"),rounds);c.setStarRounds(json.optString("star","purple"),rounds);
        }
        read(json.optJSONArray("dragonRounds"),c.dragonRounds);read(json.optJSONArray("dragonActionRounds"),c.dragonActionRounds);
        // 旧配置没有此字段时默认关闭，不改变已有重开及条件动作设置。
        JSONObject veins=json.optJSONObject("veinTargets");if(veins!=null){Iterator<String> keys=veins.keys();while(keys.hasNext()){String key=keys.next();c.veinTargets.put(Integer.parseInt(key),veins.getInt(key));}}
        JSONObject deaths=json.optJSONObject("deathCells");if(deaths!=null){Iterator<String> keys=deaths.keys();while(keys.hasNext()){String key=keys.next();c.deathCells.put(Integer.parseInt(key),deaths.getInt(key));}}
        if(json.has("dragonActions"))c.dragonActions=RecorderDragonActionCondition.parse(json.getJSONObject("dragonActions"));
        read(json.optJSONArray("veinActionRounds"),c.veinActionRounds);if(json.has("veinActions"))c.veinActions=RecorderDragonActionCondition.parse(json.getJSONObject("veinActions"));
        JSONObject priorities=json.optJSONObject("actionPriority");if(priorities!=null){Iterator<String> keys=priorities.keys();while(keys.hasNext()){String key=keys.next();c.actionPriority.put(Integer.parseInt(key),priorities.getString(key));}}
        JSONObject fallback=json.optJSONObject("jointActions");if(fallback!=null){Iterator<String> keys=fallback.keys();while(keys.hasNext()){String key=keys.next();JSONArray array=fallback.getJSONArray(key);List<String> actions=new ArrayList<>();for(int n=0;n<array.length();n++)actions.add(array.getString(n));c.jointActions.put(Integer.parseInt(key),actions);}}
        // 用户要求旧版条件动作统一清除后重配；只清此页，星色、龙气不足及阵亡重开另行保存。
        if(json.optInt("version",0)<7){c.dragonActionRounds.clear();c.veinActionRounds.clear();c.actionPriority.clear();c.jointActions.clear();c.dragonActions=new RecorderDragonActionCondition();c.veinActions=new RecorderDragonActionCondition();}
        if(json.optInt("version",0)==7)c.migrateCombinations();
        else if(json.optInt("version",0)>=8)c.combinations=RecorderJointActionCondition.parse(json.optJSONObject("combinations"));
        return c;
    }
    /** 按旧优先级的真实结果展开四种组合，各列表复制后可独立调整。 */
    private void migrateCombinations(){
        for(int round:actionRounds())if(joint(round))for(boolean inVein:new boolean[]{true,false}){
            RecorderDragonActionCondition.Branches target=new RecorderDragonActionCondition.Branches();
            for(boolean inRange:new boolean[]{true,false}){
                RecorderDragonActionCondition.Branches dragon=dragonActions.rounds.get(round),vein=veinActions.rounds.get(round);
                List<String> source;
                if(inVein&&(veinFirst(round)||!inRange))source=vein==null?Collections.emptyList():vein.matched;
                else if(inRange)source=dragon==null?Collections.emptyList():dragon.matched;
                else source=jointActions.getOrDefault(round,Collections.emptyList());
                (inRange?target.matched:target.unmatched).addAll(source);
            }
            combinations.at(inVein).rounds.put(round,target);
        }
        actionPriority.clear();jointActions.clear();
    }
    private static void read(JSONArray array,Set<Integer> target)throws JSONException{if(array!=null)for(int n=0;n<array.length();n++)target.add(array.getInt(n));}
    static RecorderRoundConditions load(Context context,RecorderFollowTable table){
        try{RecorderRoundConditions c=parse(context.getSharedPreferences("recorder-round-conditions",0).getString(RecorderLoopScript.key(table),"{}"));if(zhugeColumn(table)<0){c.dragonRounds.clear();c.veinTargets.clear();c.dragonActionRounds.clear();c.veinActionRounds.clear();}if(RecorderFazhengCondition.column(table)<0)c.fazheng.rounds.clear();c.validateBase(table);c.prepareActions(table,c.actionRounds());return c;}
        catch(Exception invalid){return new RecorderRoundConditions();}
    }
    void save(Context context,RecorderFollowTable table)throws JSONException{
        validate(table);if(!context.getSharedPreferences("recorder-round-conditions",0).edit().putString(RecorderLoopScript.key(table),json().toString()).commit())throw new IllegalArgumentException("回合识别条件保存失败");
    }
}
