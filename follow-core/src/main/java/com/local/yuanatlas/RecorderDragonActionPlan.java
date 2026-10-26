package com.local.yuanatlas;

import java.util.*;

/** 仅生成本局的执行副本，原表、编码、备注和其余密探的步骤保持原样。 */
final class RecorderDragonActionPlan {
    final List<RecorderFollowTable.Step> steps;
    final int observed;final boolean matched;
    RecorderDragonActionPlan(RecorderFollowTable table,int round,RecorderDragonActionCondition rule,int observed){
        this(table,round,validated(table,round,rule,observed),observed,rule.matches(round,observed));
    }
    private static List<String> validated(RecorderFollowTable table,int round,RecorderDragonActionCondition rule,int observed){rule.validate(table,round);return rule.actions(round,observed);}
    RecorderDragonActionPlan(RecorderFollowTable table,int round,List<String> actions,int observed,boolean matched){
        RecorderDragonActionCondition.validateActions(table,round,RecorderDragonActionCondition.slots(table,round),actions,"条件动作");this.observed=observed;this.matched=matched;
        int column=RecorderRoundConditions.zhugeColumn(table);
        List<RecorderFollowTable.Step> original=table.steps(round);
        List<RecorderFollowTable.Step> result=new ArrayList<>();int action=0;
        for(int n=0;n<original.size();n++){
            RecorderFollowTable.Step step=original.get(n);
            if(step.column!=column){result.add(step);continue;}
            // 槽位仍一一对应；空动作直接移出执行队列，不切敌、不发手势、不追加等待。
            // 后面的动作保留原序号、单元格内索引及颜色，不能因跳过而重新编号。
            String replacement=actions.get(action++);if(replacement.isEmpty())continue;
            result.add(new RecorderFollowTable.Step(step,replacement,0,step.entry));
        }
        steps=Collections.unmodifiableList(result);
    }
    /** 设置变化或首次判定时，把待执行位置映射到新队列，不能重放已经完成的其他密探。 */
    static int remap(List<RecorderFollowTable.Step> original,List<RecorderFollowTable.Step> before,int cursor,List<RecorderFollowTable.Step> after){
        if(cursor>=before.size())return after.size();
        RecorderFollowTable.Step pending=before.get(cursor);int position=original.indexOf(pending.source());
        if(position<0)throw new IllegalArgumentException("条件动作的原始出手位置已失效");
        for(int n=0;n<after.size();n++){
            RecorderFollowTable.Step step=after.get(n);int index=original.indexOf(step.source());
            if(index>position||index==position&&step.branchOffset>=pending.branchOffset)return n;
        }
        return after.size();
    }
}
