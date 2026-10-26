package com.local.yuanatlas;

import android.graphics.*;

/** 同一帧读取所选条件；双条件必须均明确，再按四组合选动作，仍需两帧一致。 */
final class RecorderConditionalActionDecision {
    static RecorderConditionVision.Reading read(Bitmap frame,Rect overlay,RecorderFollowVision.Layout layout,RecorderFollowTable table,RecorderRoundConditions rules,int round){
        String star=rules.starAt(round);boolean dragonGuard=rules.dragonRounds.contains(round);int column=RecorderRoundConditions.zhugeColumn(table);
        RecorderConditionVision.Reading value=star!=null||dragonGuard?RecorderConditionVision.read(frame,overlay,layout,column,star!=null,dragonGuard):new RecorderConditionVision.Reading(null,-1);
        if(star!=null&&value.star==null||dragonGuard&&value.dragon<0){value.pending="星色或龙气未识别清楚";return value;}
        // 原有重开条件先执行；失败不再计算本回合的动作分支。
        if(star!=null&&!star.equals(value.star)||dragonGuard&&!rules.acceptsDragon(value.dragon))return value;
        // 龙脉重开优先于条件动作；通过后复用同帧背景读数，避免重复识别。
        if(rules.veinTargets.containsKey(round)){
            RecorderDragonVeinVision.Reading vein=RecorderDragonVeinVision.read(frame,overlay,layout.viewport);value.vein=vein.state;
            if(vein.state==RecorderDragonVeinVision.UNKNOWN){value.pending="龙脉背景："+vein.reason;return value;}
            if(!rules.acceptsVein(round,value.vein))return value;
        }
        if(!rules.actionRequired(round))return value;
        if(rules.dragonActionRounds.contains(round)){
            if(value.dragon<0)value.dragon=RecorderConditionVision.read(frame,overlay,layout,column,false,true).dragon;
            if(value.dragon<0){value.pending="龙气数量未识别清楚";return value;}
            value.actionMatched=rules.dragonActions.matches(round,value.dragon);
        }
        if(rules.veinActionRounds.contains(round)&&value.vein==RecorderDragonVeinVision.UNKNOWN){
            RecorderDragonVeinVision.Reading vein=RecorderDragonVeinVision.read(frame,overlay,layout.viewport);value.vein=vein.state;
            if(vein.state==RecorderDragonVeinVision.UNKNOWN){value.pending="龙脉背景："+vein.reason;return value;}
        }
        // 只有两项都确定，才发布组合；未知不当作范围外或龙脉外。
        if(rules.joint(round))value.actionSource=value.vein==RecorderDragonVeinVision.INSIDE?"joint-inside":"joint-outside";
        else if(rules.veinActionRounds.contains(round)){value.actionSource="vein";value.actionMatched=value.vein==RecorderDragonVeinVision.INSIDE;}
        else value.actionSource="dragon";
        return value;
    }
}
