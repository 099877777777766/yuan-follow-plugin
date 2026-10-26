package com.local.yuanatlas;

import java.util.Map;

/** 嘲讽只临时改变游戏目标；独立保留进入嘲讽前的编号，不累计无法生效的横滑。 */
final class RecorderTauntState {
    private boolean active;
    private int target,returnTarget;
    boolean active(){return active;}
    int target(){return target;}
    int returnTarget(){return returnTarget;}
    /** 返回需要恢复的原编号；相邻选中回合视为连续嘲讽，不覆盖最初返回点。 */
    int sync(int round,Map<Integer,Integer> rounds,int current){
        Integer next=rounds.get(round);
        if(next!=null){if(!active)returnTarget=current;active=true;target=next;return 0;}
        int restore=active?returnTarget:0;reset();return restore;
    }
    void reset(){active=false;target=0;returnTarget=0;}
}
