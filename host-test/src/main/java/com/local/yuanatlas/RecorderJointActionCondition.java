package com.local.yuanatlas;

import java.util.*;
import org.json.*;

/** 双条件的笛卡尔积：龙脉内外各自保留龙气范围内、范围外两套固定行动位。 */
final class RecorderJointActionCondition {
    final RecorderDragonActionCondition inside=new RecorderDragonActionCondition(),outside=new RecorderDragonActionCondition();
    RecorderDragonActionCondition at(boolean inVein){return inVein?inside:outside;}
    void prepare(RecorderFollowTable table,Set<Integer> rounds){inside.prepare(table,rounds);outside.prepare(table,rounds);}
    List<String> actions(int round,boolean inVein,boolean inRange){
        RecorderDragonActionCondition.Branches b=at(inVein).rounds.get(round);
        if(b==null)throw new IllegalArgumentException("请配置第 "+round+" 回合的四种条件动作");
        return inRange?b.matched:b.unmatched;
    }
    void validate(RecorderFollowTable table,int round){inside.validateBranches(table,round,true,"龙脉内且龙气范围内","龙脉内且龙气范围外");outside.validateBranches(table,round,true,"龙脉外且龙气范围内","龙脉外且龙气范围外");}
    RecorderJointActionCondition copy(){RecorderJointActionCondition c=new RecorderJointActionCondition();for(boolean in:new boolean[]{true,false})for(Map.Entry<Integer,RecorderDragonActionCondition.Branches> e:at(in).rounds.entrySet())c.at(in).rounds.put(e.getKey(),e.getValue().copy());return c;}
    JSONObject json()throws JSONException{return new JSONObject().put("inside",inside.json()).put("outside",outside.json());}
    static RecorderJointActionCondition parse(JSONObject json)throws JSONException{RecorderJointActionCondition c=new RecorderJointActionCondition();if(json!=null)for(boolean in:new boolean[]{true,false}){JSONObject rows=json.optJSONObject(in?"inside":"outside");if(rows!=null)c.at(in).rounds.putAll(RecorderDragonActionCondition.parse(rows).rounds);}return c;}
}
