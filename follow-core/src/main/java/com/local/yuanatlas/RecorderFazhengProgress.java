package com.local.yuanatlas;

/** 同回合暂停/续行保留拉取数与待检测状态；新回合及新局才重置。 */
final class RecorderFazhengProgress {
    int round=-1,pulls,epoch;long readyAt;boolean pendingCheck,finished;
    RecorderFollowTable.Step anchor,pending;
    void sync(int value){if(round!=value){clear();round=value;}}
    void clear(){epoch++;readyAt=0;round=-1;pulls=0;pendingCheck=false;finished=false;anchor=null;pending=null;}
    void begin(RecorderFollowTable.Step step){anchor=step;pending=step;}
    boolean owns(RecorderFollowTable.Step step){return pending==step;}
    void sent(RecorderFollowTable.Step step){
        if(step.action.equals("↓"))pulls++;
        if(owns(step)){pending=null;pendingCheck=!finished;}
    }
    void repeat(){pending=new RecorderFollowTable.Step(anchor,"↓",-100,anchor.entry);}
    void matched(String action){
        pendingCheck=false;finished=true;
        if(action.equals("↑")||action.equals("A")||action.equals("↓")&&pulls<5)pending=new RecorderFollowTable.Step(anchor,action,-101,anchor.entry);
    }
}
