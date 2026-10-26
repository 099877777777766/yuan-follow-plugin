package com.local.yuanatlas;

import java.util.*;
import org.json.*;

/** 规则只绑定本表的法正；空回合集合表示关闭，不影响其他密探。 */
final class RecorderFazhengCondition {
    static final String[] ACTIONS={"stop","↑","↓","A","continue"};
    static final String[] LABELS={"停止跟打","↑ 上滑后继续","↓ 下拉后继续","A 普攻后继续","继续原表"};
    final Set<Integer> rounds=new TreeSet<>();
    String action="stop";
    final List<Integer> targets=new ArrayList<>(Arrays.asList(10,11,12));
    boolean detailed;
    static int column(RecorderFollowTable table){
        int found=-1;
        for(int c=0;c<table.agents.length;c++)if(table.agents[c]!=null&&table.agents[c].replaceAll("\\s","").contains("法正")){
            if(found>=0)return -1;found=c;
        }
        return found;
    }
    void validate(RecorderFollowTable table){
        if(targets.isEmpty()&&!rounds.isEmpty()||targets.size()>3)throw new IllegalArgumentException("请选择 1～3 种目标圣物");
        if(new HashSet<>(targets).size()!=targets.size())throw new IllegalArgumentException("目标圣物不能重复勾选");
        for(int id:targets)if(id<0||id>=13)throw new IllegalArgumentException("圣物目标无效");
        if(!Arrays.asList(ACTIONS).contains(action))throw new IllegalArgumentException("请选择圣物命中后的操作");
        if(!RecorderRoundConditions.rounds(table).containsAll(rounds))throw new IllegalArgumentException("圣物检测请选择当前表中的回合");
        if(!rounds.isEmpty()&&column(table)<0)throw new IllegalArgumentException("圣物检测需要且仅能有一列法正");
    }
    RecorderFazhengCondition copy(){RecorderFazhengCondition value=new RecorderFazhengCondition();value.rounds.addAll(rounds);value.action=action;value.targets.clear();value.targets.addAll(targets);value.detailed=detailed;return value;}
    JSONObject json()throws JSONException{return new JSONObject().put("rounds",new JSONArray(rounds)).put("action",action).put("targets",new JSONArray(targets)).put("detailed",detailed);}
    static RecorderFazhengCondition parse(JSONObject json)throws JSONException{
        RecorderFazhengCondition value=new RecorderFazhengCondition();if(json==null)return value;
        // 旧版三红规则迁移为三个神器组成的组合技，保留回合与命中后操作。
        // 兼容旧版数量配置：重复目标合并为一种，回合、动作与详细模式保持原值。
        JSONArray ids=json.optJSONArray("targets");if(ids!=null){value.targets.clear();for(int i=0;i<ids.length();i++){int id=ids.getInt(i);if(!value.targets.contains(id))value.targets.add(id);}}
        value.detailed=json.optBoolean("detailed",false);
        value.action=json.optString("action","stop");JSONArray rounds=json.optJSONArray("rounds");
        if(rounds!=null)for(int i=0;i<rounds.length();i++)value.rounds.add(rounds.getInt(i));return value;
    }
    int[] targetIds(){int[] ids=new int[targets.size()];for(int i=0;i<ids.length;i++)ids[i]=targets.get(i);return ids;}
    /** 无序种类集合命中；新物品在上，未来 k 次下拉只会留下当前顶部的 3-k 件。 */
    Decision decideItems(int pulls,int[] items){
        if(pulls<1||pulls>5||items==null||items.length!=Math.min(pulls,3)||targets.isEmpty()||targets.size()>3||new HashSet<>(targets).size()!=targets.size())return Decision.UNKNOWN;
        for(int id:items)if(id<0||id>=13)return Decision.UNKNOWN;
        if(missing(items,items.length)==0)return Decision.MATCH;
        for(int k=1;k<=5-pulls;k++)if(missing(items,Math.min(items.length,Math.max(0,3-k)))<=k)return Decision.CONTINUE;
        return Decision.RESTART;
    }
    private int missing(int[] items,int retain){int[] need=new int[13];for(int id:targets){if(id<0||id>=13)return 99;need[id]=1;}for(int i=0;i<retain;i++)if(need[items[i]]>0)need[items[i]]--;int n=0;for(int c:need)n+=c;return n;}
    enum Decision {CONTINUE, MATCH, RESTART, UNKNOWN}
}
