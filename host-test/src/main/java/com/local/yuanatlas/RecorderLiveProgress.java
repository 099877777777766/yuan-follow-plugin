package com.local.yuanatlas;

import java.util.*;

/** 一局内已执行位置的账本。动作文字/颜色改变不代表重新执行；条件分支逐项记账。 */
final class RecorderLiveProgress {
    private final Map<String,Integer> offsets=new HashMap<>();
    private String key(RecorderFollowTable.Step step){return step.rowId+":"+step.order;}
    void completed(RecorderFollowTable.Step step){offsets.merge(key(step),step.branchOffset,Math::max);}
    boolean done(RecorderFollowTable.Step step){return offsets.getOrDefault(key(step),-1)>=step.branchOffset;}
    void clear(){offsets.clear();}
    void remember(List<RecorderFollowTable.Step> queue,int cursor){for(int i=0;i<Math.min(cursor,queue.size());i++)completed(queue.get(i));}
}
