package com.local.yuanatlas;

import android.graphics.*;
import java.util.*;

/** 前后清晰画面确认存活/阵亡；自动误判可被后续血条纠正，手动设置本场优先。 */
final class RecorderEnemyObserver {
    private final boolean[] seen=new boolean[5],manual=new boolean[5];
    private final int[] missing=new int[5];private final long[] since=new long[5];
    private final int[] present=new int[5];private final long[] presentSince=new long[5];
    private RecorderEnemyVision.Reading previous;private long previousAt;
    private RecorderEnemyVision.Reading confirmed;private int clearCount;private long clearSince;
    private float confirmedFill=Float.NaN;
    private float battleAnchor=Float.NaN;
    private int observedCurrent;
    private Comparison comparison;
    private RecorderEnemyVision.Reading last;
    private String ignoredReason="";
    private boolean roundCensus,roundConfirmed,roundInitial,roundForced,censusEstablished;
    /** 开局及每轮首步核对全部占位，包含本局还没出现过的小怪位置。 */
    void beginRound(){beginRound(false);}
    void beginRound(boolean forcedTarget){interrupt();roundCensus=true;roundConfirmed=false;roundInitial=!censusEstablished;roundForced=forcedTarget;}
    void endRound(){roundCensus=false;interrupt();}
    boolean roundConfirmed(){return roundConfirmed;}
    /** 前后快照的原始编号间隔不等于实际滑动次数；完整绕圈无法由两张画面反推。 */
    static final class Comparison {
        final int center,from,forward;final int[] absent;
        Comparison(RecorderEnemyVision.Reading before,RecorderEnemyVision.Reading after){
            center=after.selected;from=before==null?0:before.selected;forward=from==0?0:(center-from+after.states.length)%after.states.length;
            int count=0;if(before!=null)for(int p=0;p<after.states.length;p++)if(before.states[p]==1&&after.states[p]==0)count++;
            absent=new int[count];int n=0;if(before!=null)for(int p=0;p<after.states.length;p++)if(before.states[p]==1&&after.states[p]==0)absent[n++]=p+1;
        }
    }
    Comparison comparison(){return comparison;}
    RecorderEnemyVision.Reading lastReading(){return last;}
    String ignoredReason(){return ignoredReason;}
    /** 只复核近期已经看到的缺失/跳位，不让完全看不清的画面阻塞下一动作。 */
    boolean pending(long now){
        if(previous==null||now-previousAt>1200)return false;
        for(int n:missing)if(n>0)return true;
        // 手势已完成的主动切敌以执行器编号为准，不为尚未刷新的旧快照多等 800ms。
        return previous.selected!=observedCurrent;
    }
    /** 一张模糊图不是血条重新出现；短暂空缺保留证据，超过一轮采样余量才失效。 */
    void gap(long now){last=null;comparison=null;if(previous!=null&&now-previousAt>1200)interrupt();}
    void reset(){roundCensus=false;roundConfirmed=false;censusEstablished=false;Arrays.fill(seen,false);Arrays.fill(manual,false);confirmed=null;confirmedFill=Float.NaN;battleAnchor=Float.NaN;interrupt();}
    void reconfigured(){roundCensus=false;censusEstablished=false;Arrays.fill(seen,false);confirmed=null;confirmedFill=Float.NaN;battleAnchor=Float.NaN;interrupt();}
    // 跨操作、跨回合保留上次确认的画面，用于比较中央目标跳转和外围血条缺失。
    void interrupt(){previous=null;previousAt=0;clearCount=0;comparison=null;last=null;Arrays.fill(missing,0);Arrays.fill(present,0);}
    void corrected(int position){if(position>=1&&position<=5)manual[position-1]=true;interrupt();}
    /** 返回完整快照交给已有 revision 校验；未知或存活状态不变时返回 null。 */
    boolean[] observe(Bitmap frame,Rect overlay,boolean[] alive,int current,long now){
        return observe(RecorderEnemyVision.read(frame,overlay,battleAnchor),alive,current,now);
    }
    /** 嘲讽镜头下仅确认已配置的中央嘲讽者存在，不把外围重排当成其他敌人阵亡。 */
    private boolean[] observeForced(RecorderEnemyVision.Reading raw,boolean[] alive,int current,long now){
        if(raw==null||current<1||current>alive.length||!Float.isNaN(battleAnchor)&&Math.abs(raw.anchor-battleAnchor)>6){gap(now);return null;}
        RecorderEnemyVision.Reading reading=raw.at(current,alive.length);if(reading==null){gap(now);return null;}
        boolean stable=now-previousAt<=1200&&RecorderEnemyVision.consistent(previous,reading);
        if(!stable||clearCount==0){clearCount=1;clearSince=now;}else clearCount++;
        previous=reading;previousAt=now;last=reading;
        if(clearCount<3||now-clearSince<800)return null;
        roundConfirmed=true;
        if(alive[current-1]||manual[current-1])return null;
        boolean[] result=alive.clone();result[current-1]=true;seen[current-1]=true;return result;
    }
    /** 当前编号来自起始设置及已经完成的切敌手势，绝不从属性或人物外观推断。 */
    private RecorderEnemyVision.Reading resolve(RecorderEnemyVision.Reading raw,boolean[] alive,int current,long now){
        if(raw==null){ignoredReason="未读到清晰的中央血条，保留敌方状态";return null;}
        if(current<1||current>alive.length)return null;
        // 血条行在本场战斗中基本固定。强光下状态图标的红色底边也像长血条，
        // 不能用它重定位整组占位，否则会把仍存活的中央敌人“跳位”成其他编号。
        if(!Float.isNaN(battleAnchor)&&Math.abs(raw.anchor-battleAnchor)>6){
            ignoredReason="血条定位高度跳变 "+Math.round(battleAnchor)+"→"+Math.round(raw.anchor)+"，保留敌方状态";
            return null;
        }
        for(int slot:raw.slots)if(slot==RecorderEnemyVision.UNKNOWN){ignoredReason="外围血条被遮挡或不清晰，保留敌方状态";return null;}
        List<RecorderEnemyVision.Reading> candidates=new ArrayList<>();
        RecorderEnemyVision.Reading expected=raw.at(current,alive.length);
        // 首次完整回合核对使用用户提供的起始目标建立基线；超时/暂停后仍可继续建立。
        // 不能先要求所有配置占位已出现，
        // 否则“先一个首领、后召唤小怪”的空位永远无法确认。
        if(roundCensus&&roundInitial&&expected!=null)return expected;
        // 刚开始/恢复跟打时，完整布局与已知存活列表相符即可建立基线；
        // 不把尚未采样的已阵亡位置当作可能复活，导致所有后续画面一直无法对应。
        if(confirmed==null&&expected!=null){
            boolean matches=true;for(int p=0;p<alive.length;p++)if((expected.states[p]==RecorderEnemyVision.PRESENT)!=alive[p])matches=false;
            if(matches)return expected;
        }
        if(plausible(expected,alive))candidates.add(expected);
        for(int distance=1;distance<alive.length;distance++){
            int next=(current-1+distance)%alive.length+1;
            RecorderEnemyVision.Reading candidate=raw.at(next,alive.length);if(candidate==null)continue;
            // 自动跳位只沿原始编号向后：当前及跳过的位置都须缺少血条，不能随意旋转画面改号。
            boolean skipped=true;
            for(int step=0;step<distance;step++)if(candidate.states[(current-1+step)%alive.length]!=RecorderEnemyVision.ABSENT){skipped=false;break;}
            if(skipped&&plausible(candidate,alive))candidates.add(candidate);
        }
        if(candidates.isEmpty())return cameraReading(raw,alive,current);
        if(candidates.size()==1)return candidates.get(0);
        // 同款敌人仍可利用其他位置的血条填充区间对照。填充只帮助匹配，空血不能直接判死。
        // 多个映射同样成立时不猜死亡编号；这一分支不阻塞跟打，也不请求额外截图。
        if(confirmed==null)return null;
        // 中央目标换成另一条相近的低血量血条时，±7% 的外围区间无法区分。
        // 仅在外围确实减少、已有中央基线、只有一个顺向存活落点且原图填充明确增长时排除“仍是原目标”。
        // 空血本身从不判死，仍须后续三帧/800 ms 的同一位置缺失才能提交。
        RecorderEnemyVision.Reading moved=null;int forwardChoices=0;
        for(RecorderEnemyVision.Reading c:candidates)if(c.center!=current&&alive[c.center-1]){
            boolean revived=false;for(int p=0;p<alive.length;p++)if(!alive[p]&&c.states[p]==RecorderEnemyVision.PRESENT)revived=true;
            if(!revived){moved=c;forwardChoices++;}
        }
        int oldBars=0,newBars=0,living=0;for(int s:confirmed.slots)if(s==1)oldBars++;for(int s:raw.slots)if(s==1)newBars++;for(boolean s:alive)if(s)living++;
        // 已经有明确跳位候选后给抗锯齿/亮度抖动留半像素余量；仍要求同一布局，
        // 且不是过期证据或临时敌方镜头。回到原长度不会延续这个候选。
        boolean continuing=moved!=null&&previous!=null&&now-previousAt<=1200&&previous.selected==moved.center&&Arrays.equals(previous.states,moved.states);
        if(confirmed.center==current&&living==2&&oldBars==2&&newBars==1&&forwardChoices==1&&
            !Float.isNaN(raw.centerFill)&&!Float.isNaN(confirmedFill)&&raw.centerFill-confirmedFill>(continuing?1f:1.5f))return moved;
        float best=Float.MAX_VALUE,second=Float.MAX_VALUE;RecorderEnemyVision.Reading winner=null;
        for(RecorderEnemyVision.Reading candidate:candidates){
            float score=0;
            for(int p=0;p<alive.length;p++){
                float[] before=confirmed.health[p],after=candidate.health[p];
                if(before==null||after==null)continue;
                float increase=Math.max(0,after[0]-before[1]-.08f);
                float decrease=Math.max(0,before[0]-after[1]-.08f);
                score+=increase*6+decrease*(p+1==current?.4f:1.5f);
            }
            if(score<best){second=best;best=score;winner=candidate;}else second=Math.min(second,score);
        }
        return second-best>=.18f?winner:null;
    }
    /** 敌方行动会转镜头，但不会自动改玩家选中的存活目标；这种画面只用来核对外围占位。 */
    private RecorderEnemyVision.Reading cameraReading(RecorderEnemyVision.Reading raw,boolean[] alive,int current){
        if(confirmed==null)return null;RecorderEnemyVision.Reading unique=null;
        for(int center=1;center<=alive.length;center++){
            if(center==current||!alive[center-1])continue;
            RecorderEnemyVision.Reading reading=raw.at(center,alive.length);
            // 已知选中目标必须仍有外围血条；镜头模式不能据此宣告它阵亡或让旧阵亡位置复活。
            if(reading==null||reading.states[current-1]!=RecorderEnemyVision.PRESENT||!plausible(reading,alive))continue;
            boolean returned=false;for(int p=0;p<alive.length;p++)if(!alive[p]&&reading.states[p]==RecorderEnemyVision.PRESENT)returned=true;
            if(returned)continue;if(unique!=null)return null;unique=reading;
        }
        return unique==null?null:unique.cameraOnly(current);
    }
    /** 技能镜头短暂转向会使血条看似一处复活、另一处死亡；不把这种整组错位当作两次状态变化。 */
    private boolean plausible(RecorderEnemyVision.Reading reading,boolean[] alive){
        if(reading==null)return false;boolean returned=false,gone=false;
        for(int p=0;p<alive.length;p++)if(!manual[p]){
            if(!alive[p]&&reading.states[p]==RecorderEnemyVision.PRESENT)returned=true;
            if(alive[p]&&seen[p]&&reading.states[p]==RecorderEnemyVision.ABSENT)gone=true;
        }
        return !(returned&&gone);
    }
    /** 像素读取与编号跟踪分开，便于验证同属性、相同外观和连续跳过多个阵亡位置。 */
    boolean[] observe(RecorderEnemyVision.Reading raw,boolean[] alive,int current,long now){
        comparison=null;ignoredReason="";observedCurrent=current;
        if(roundCensus&&roundForced)return observeForced(raw,alive,current,now);
        RecorderEnemyVision.Reading reading=resolve(raw,alive,current,now);
        if(reading==null){gap(now);return null;}
        last=reading;
        boolean stable=now-previousAt<=1200&&RecorderEnemyVision.consistent(previous,reading);
        boolean samePosition=previous!=null&&now-previousAt<=1200&&previous.center==reading.center&&Math.abs(previous.anchor-reading.anchor)<=3;
        // 只让完整、无遮挡的布局累积阵亡证据；模糊帧本身不增加确认次数。
        boolean clear=true;for(int value:reading.states)if(value==RecorderEnemyVision.UNKNOWN)clear=false;
        // 首张清晰图只作前后对照的基线；当前目标校正仍需后续连续清晰画面确认。
        if(confirmed==null&&clear){confirmed=reading;confirmedFill=reading.centerFill;battleAnchor=reading.anchor;}
        if(!clear)clearCount=0;
        else if(!stable||clearCount==0){clearCount=1;clearSince=now;}else clearCount++;
        boolean[] result=alive.clone();boolean changed=false;
        for(int n=0;n<alive.length;n++){
            if(reading.states[n]==RecorderEnemyVision.PRESENT){
                seen[n]=true;missing[n]=0;
                if(!alive[n]&&!manual[n]&&clear){
                    if(!stable||present[n]==0){present[n]=1;presentSince[n]=now;}else present[n]++;
                    // 自动结果可被后续确凿血条纠正；同样要求连续画面，特效的一闪不直接复活。
                    if(present[n]>=3&&now-presentSince[n]>=800){result[n]=true;changed=true;present[n]=0;}
                }else present[n]=0;
                continue;
            }
            present[n]=0;
            if(!alive[n]||manual[n]||!seen[n]&&!roundCensus||!clear||reading.states[n]!=RecorderEnemyVision.ABSENT){missing[n]=0;continue;}
            // 该原始位置持续缺少血条即可积累；其他位置血量变化不应重置它的确认。
            if(!samePosition||previous.states[n]!=RecorderEnemyVision.ABSENT||missing[n]==0){missing[n]=1;since[n]=now;}else missing[n]++;
            if(missing[n]>=3&&now-since[n]>=800){result[n]=false;changed=true;missing[n]=0;}
        }
        if(changed||clearCount>=3&&now-clearSince>=800){
            if(roundCensus&&clearCount>=3&&now-clearSince>=800){roundConfirmed=true;censusEstablished=true;}
            comparison=new Comparison(confirmed,reading);
            // 红填充刚消失或被光效遮住时没有精细值，不能冲掉本目标最后一次有效长度。
            if(confirmed==null||confirmed.center!=reading.center||!Float.isNaN(reading.centerFill))confirmedFill=reading.centerFill;
            confirmed=reading;
        }
        previous=reading;previousAt=now;return changed?result:null;
    }
}
