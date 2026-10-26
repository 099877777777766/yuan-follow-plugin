package com.local.yuanatlas;

import java.util.Arrays;

/** 本场战斗的固定占位编号。阵亡只从滑动环中移除，不压缩编号或改写颜色配置。 */
final class RecorderEnemyState {
    private boolean[] alive;
    private boolean[] pending;
    private int current,arrival;
    private boolean inFlight;
    private long revision;

    RecorderEnemyState(int count,int initial){reset(count,initial);}
    int count(){return alive.length;}
    int current(){return current;}
    /** 游戏自行解除嘲讽并返回，不发送横滑；原目标已阵亡时沿存活环顺延。 */
    void restoreTarget(int position){current=resolve(position);revision++;}
    long revision(){return revision;}
    // 按钮立即显示待应用的纠正；正在飞行的手势仍按发送时的路线完成。
    boolean alive(int position){return position>0&&position<=count()&&(pending==null?alive:pending)[position-1];}
    void setAlive(int position,boolean value){
        if(position<1||position>count())return;
        boolean[] next=(pending==null?alive:pending).clone();next[position-1]=value;
        revision++;update(next);
    }
    /** 供后续阵亡识别接入：截图时记录 revision，期间用户纠正或重置后拒绝旧结果。 */
    boolean applyRecognition(boolean[] snapshot,long expectedRevision){
        if(snapshot==null||snapshot.length!=count()||expectedRevision!=revision)return false;
        revision++;update(snapshot.clone());return true;
    }
    /** 血条对照同时提交阵亡及实际中央目标，避免先顺延再多跳一次；手动置灰的编号不复活。 */
    boolean applyObservation(boolean[] snapshot,int center,long expectedRevision){
        if(inFlight||expectedRevision!=revision||snapshot==null||snapshot.length!=count())return false;
        int actual=center>=1&&center<=count()&&snapshot[center-1]?center:0;
        if(Arrays.equals(alive,snapshot)&&(actual==0||actual==current))return false;
        revision++;update(snapshot.clone());if(actual>0)current=actual;return true;
    }
    private void update(boolean[] next){
        if(inFlight){pending=next;return;}
        alive=next;current=resolve(current);
    }
    void reset(int count,int initial){
        check(count,initial);alive=new boolean[count];Arrays.fill(alive,true);
        pending=null;inFlight=false;arrival=0;current=initial;revision++;
    }
    /** 设置改变时保留仍存在的占位状态，新增加的位置默认存活。 */
    void configure(int count,int initial){
        check(count,initial);finishGesture(false);
        int previous=alive.length;alive=Arrays.copyOf(alive,count);
        for(int i=previous;i<count;i++)alive[i]=true;
        current=resolve(initial);revision++;
    }
    private static void check(int count,int initial){
        if(count<1||count>5||initial<1||initial>count)throw new IllegalArgumentException("敌方占位范围为 1–5");
    }
    /** 阵亡目标顺延到下一个存活的原始编号，末尾循环；全部阵亡时返回 0。 */
    int resolve(int position){
        int start=position>=1&&position<=count()?position:1;
        for(int offset=0;offset<count();offset++){int candidate=(start-1+offset)%count()+1;if(alive[candidate-1])return candidate;}
        return 0;
    }
    int step(int from,int direction){
        if(from<1||from>count())return resolve(1);
        for(int offset=1;offset<=count();offset++){
            int candidate=(from-1+(direction>0?offset:-offset)+count())%count()+1;
            if(alive[candidate-1])return candidate;
        }
        return 0;
    }
    /** 正数左滑、负数右滑；仅计算存活占位，等长时沿用左滑。 */
    int route(int destination){
        destination=resolve(destination);if(current==0||destination==0||current==destination)return 0;
        int left=0,right=0,at=current;
        do{at=step(at,1);left++;}while(at!=destination);
        at=current;do{at=step(at,-1);right++;}while(at!=destination);
        return left<=right?left:-right;
    }
    void beginGesture(int destination){inFlight=true;arrival=destination;}
    /** 先确认本次滑动原本的落点，再更新阵亡，避免中途纠正造成重复跳位。 */
    void finishGesture(boolean reached){
        if(reached&&arrival>0)current=arrival;
        arrival=0;inFlight=false;
        if(pending!=null){alive=pending;pending=null;}
        current=resolve(current);
    }
}
