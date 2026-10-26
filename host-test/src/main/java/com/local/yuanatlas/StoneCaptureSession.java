package com.local.yuanatlas;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 新采集状态机：每个动作后先等两帧稳定，再判位移；确认过的位置才进入核对草稿。 */
final class StoneCaptureSession {
    final JSONObject result=new JSONObject();
    StoneCaptureFrame previous,settling;
    private String kind="main",stage="select";
    private int probes,rewinds,steps,unstable,uncertain,layoutRetries;
    private int contentRetries;
    private boolean awaitingContent;
    private double offset;
    // 首次尚未知游戏倍率，先按四倍估计，避免第一步就滑掉所有可读重叠行。
    private double scrollGain=4,lastScanPixels;
    private boolean recovering;
    private int recoverySteps,recoveryAttempts,rewindRecovery;
    private double scanRows=3,rewindScale=1;
    private final List<double[]> seen=new ArrayList<>();
    private final List<double[]> deferred=new ArrayList<>();
    JSONObject pendingPage;
    StoneCaptureSession(JSONObject config)throws Exception{
        String game=config.getString("game"),role=config.getString("roleId");
        if(!("ruYuan".equals(game)||"codeYuan".equals(game))||role.isEmpty())throw new Exception("请先选择角色");
        result.put("version",2).put("sessionId",UUID.randomUUID().toString()).put("game",game).put("roleId",role).put("phase","collecting").put("count",0).put("warnings",new JSONArray());
    }
    boolean done(){return stage.equals("done");}
    StoneCaptureFrame layoutReference(){return settling!=null?settling:previous;}
    void finish(String reason)throws Exception{stage="done";result.put("phase","review");if(!reason.isEmpty())result.getJSONArray("warnings").put(reason);}
    void resume(){settling=null;unstable=0;uncertain=0;rewinds=0;layoutRetries=0;contentRetries=0;recoverySteps=0;recoveryAttempts=0;rewindRecovery=0;}
    /** 偶发OCR漏字只重新截图，保留已确认位置；重试期间不滑动、不新增结果。 */
    RuYuanAutoCapture.Action retryLayout(StoneCaptureFrame.LayoutException error)throws Exception{
        settling=null;
        if(++layoutRetries>3)throw new Exception("连续无法定位背包："+error.getMessage()+"；已保留进度，请恢复背包页面后继续，采集浮窗无需关闭");
        return read("重新定位星石背包（"+layoutRetries+"/3）");
    }
    private String label(){return kind.equals("main")?"主星":"辅星";}
    private RuYuanAutoCapture.Action read(String label){return RuYuanAutoCapture.Action.read(label);}
    /** 完整可见但补读未成功的格子先原地重读，避免首行随下一次滑动离屏后永久漏收。 */
    private RuYuanAutoCapture.Action retryContent(StoneCaptureFrame frame)throws Exception{
        JSONArray lines=frame.page.getJSONArray("lines");boolean pending=false;
        for(int n=0;n<lines.length();n++){
            JSONObject line=lines.getJSONObject(n);
            if(line.optBoolean("nameRetryPending")&&line.optBoolean("captureDeferred")&&!line.optBoolean("captureOccluded")){pending=true;break;}
        }
        // 真正遮挡和渐隐不靠重复OCR解决；同一位置最多重读两次，仍失败沿用原有漏项提示。
        if(!pending){contentRetries=0;return null;}
        if(contentRetries++>=2)return null;
        awaitingContent=true;
        return read("补读"+label()+"未读完整的星名（"+contentRetries+"/2）");
    }
    /** 恢复手势不更新previous/offset；始终与最后确认的原帧比较，避免把不确定页当成新基准。 */
    private RuYuanAutoCapture.Action recoverOverlap(StoneCaptureFrame f)throws Exception{
        if(++recoverySteps>4)throw new Exception("小幅回拉后仍无法唯一确认滑动距离，已保留进度，未保存不确定画面；可继续重试或结束核对");
        // 留出最小触摸距离，避免低于游戏拖动阈值而连续回拉都没有生效。
        double pixels=Math.max(f.width*.035,Math.min(f.rowHeight()*.2/scrollGain,(f.bottom-f.top)*.025));
        double start=(f.top+(f.bottom-f.top)*.45)/f.height;lastScanPixels=0;
        return new RuYuanAutoCapture.Action("swipe","找回"+label()+"重叠行（"+recoverySteps+"/4）",.5,start,start+pixels/f.height);
    }
    private RuYuanAutoCapture.Action swipe(StoneCaptureFrame f,boolean down,boolean probe){
        double span=(f.bottom-f.top)/f.height,base=f.top/f.height;
        boolean scan=!down&&!probe;
        // 先限制物品的实际位移预算，再换算触摸距离；不能拿手指的画面占比代替可读行重叠。
        double distance=scan?Math.min(Math.min(f.scanBudget(),scanRows*f.rowHeight())/scrollGain,Math.min(4*f.rowHeight(),(f.bottom-f.top)*.68))/f.height:span*(probe?.055:.16*rewindScale);
        double start=base+span*(down?.32:scan?.88:.72);
        lastScanPixels=scan?distance*f.height:0;
        double x=probe?new double[]{.32,.68,.5}[(probes-1)%3]:.5;
        return new RuYuanAutoCapture.Action("swipe",probe?"确认"+label()+(down?"顶部":"底部")+"（"+probes+"/3）":down?"回到"+label()+"顶部":"采集"+label()+"，已保存 "+result.optInt("count")+" 屏",x,start,start+(down?distance:-distance));
    }
    RuYuanAutoCapture.Action accept(StoneCaptureFrame frame)throws Exception{
        layoutRetries=0;
        pendingPage=null;if(done())return null;
        if(++steps>800)throw new Exception("达到本次采集步数上限，请结束后核对已有结果");
        // 先检查是否真的切换到目标标签；连续两帧稳定后才执行任何滑动。
        if(!frame.kind.equals(kind)){
            if(!stage.equals("select"))throw new Exception("主辅星标签发生变化，已暂停，请切回"+label());
            if(++uncertain>3)throw new Exception("标签切换未成功，请手动切到"+label()+"后继续");
            settling=null;return RuYuanAutoCapture.Action.tap("切换到"+label(),kind.equals("main")?frame.mainX:frame.auxX,frame.tabY);
        }
        if(settling==null){settling=frame;return read("等待"+label()+"画面稳定");}
        boolean bothEmpty=settling.empty&&frame.empty;
        StoneCaptureFrame.Motion still=settling.motionTo(frame);settling=frame;
        if(!bothEmpty&&(!still.certain||Math.abs(still.distance)>frame.width*.008)){
            if(++unstable>=5)throw new Exception("画面仍在移动或被遮挡，已暂停；停稳后继续");return read("等待滚动停稳");
        }
        unstable=0;settling=null;
        if(stage.equals("select")){
            uncertain=0;previous=frame;stage="rewind";probes=0;rewinds=0;
            if(frame.empty)return nextKind(frame);return swipe(frame,true,false);
        }
        if(frame.empty)throw new Exception("列表突然变空，请恢复原背包后继续");
        StoneCaptureFrame.Motion motion=previous.motionTo(frame);
        if(!motion.certain){
            if(recovering&&motion.recoverable)return recoverOverlap(frame);
            if(++uncertain>=2&&motion.recoverable){
                if(stage.equals("rewind")&&rewindRecovery<3){
                    // 尚未开始保存，回顶无需累计绝对坐标；重新取稳定帧，缩短回顶步长且不计作顶部。
                    rewindRecovery++;rewindScale*=.5;previous=frame;probes=0;uncertain=0;
                    return swipe(frame,true,false);
                }
                if(stage.equals("scan")&&!recovering&&recoveryAttempts<2){
                    recoveryAttempts++;recovering=true;recoverySteps=0;uncertain=0;probes=0;
                    scanRows=Math.max(.5,scanRows*.5);scrollGain=Math.max(4,scrollGain*1.5);
                    return recoverOverlap(frame);
                }
            }
            if(uncertain>=3)throw new Exception("无法唯一确认滑动距离，已暂停，未重复保存本屏；请保持画面后继续");
            return read("重新确认滑动距离（"+uncertain+"/3）");
        }
        uncertain=0;
        boolean stationary=Math.abs(motion.distance)<frame.width*.008;
        if(recovering){
            // 允许本程序回拉造成的已确认负位移；沿用旧绝对坐标去重，回拉画面不新增记录。
            recovering=false;recoverySteps=0;offset+=motion.distance;previous=frame;probes=0;lastScanPixels=0;
            if(motion.distance>frame.width*.008)collect(frame);
            return swipe(frame,false,false);
        }
        if(stage.equals("rewind")){
            previous=frame;
            if(stationary){if(++probes>=4){result.put(kind+"TopConfirmed",true);stage="scan";probes=0;offset=0;seen.clear();contentRetries=0;collect(frame);RuYuanAutoCapture.Action retry=retryContent(frame);return retry!=null?retry:swipe(frame,false,false);}return swipe(frame,true,true);}
            probes=0;if(++rewinds>=30)throw new Exception("尚未确认顶部，已暂停；请手动回到顶部后继续");return swipe(frame,true,false);
        }
        if(stationary){
            previous=frame;
            boolean contentRead=awaitingContent;awaitingContent=false;
            // 同一位置的遮挡解除后也允许补采，空间去重确保清晰物品不会重复保存。
            collect(frame);
            RuYuanAutoCapture.Action retry=retryContent(frame);if(retry!=null)return retry;
            // 原地补读期间未执行滑动，静止不能算一次“已到底”的手势证据。
            if(contentRead)return swipe(frame,false,false);
            if(++probes>=4){
                result.put(kind+"BottomConfirmed",true);
                if(!deferred.isEmpty())result.getJSONArray("warnings").put(label()+"仍有遮挡或文字未读完整的物品未收录，请调整画面后补充截图核对");
                return nextKind(frame);
            }
            return swipe(frame,false,true);
        }
        if(motion.distance<0)throw new Exception("背包向反方向移动，已暂停，请勿在自动采集中手动滚动");
        if(motion.distance>=frame.rowHeight()*.5)recoveryAttempts=0;
        // 实测倍率上浮25%留余量，倍率升高立即收短；放长每次最多50%，避免校准噪声导致突然大滑。
        // 不截断高倍率，否则大于四倍的游戏会一直被低估；到列表底部的截断位移也不能骤然放大手势。
        if(lastScanPixels>0)scrollGain=Math.max(scrollGain/1.5,Math.max(.25,motion.distance/lastScanPixels*1.25));
        // 只承认重叠区内可唯一匹配的实际位移；相同名称/等级不参与实例去重。
        offset+=motion.distance;previous=frame;probes=0;contentRetries=0;collect(frame);
        RuYuanAutoCapture.Action retry=retryContent(frame);return retry!=null?retry:swipe(frame,false,false);
    }
    private RuYuanAutoCapture.Action nextKind(StoneCaptureFrame frame)throws Exception{
        if(kind.equals("auxiliary")){finish("");return null;}
        kind="auxiliary";stage="select";settling=null;previous=null;seen.clear();deferred.clear();offset=0;uncertain=0;contentRetries=0;scrollGain=4;lastScanPixels=0;
        awaitingContent=false;
        recovering=false;recoverySteps=0;recoveryAttempts=0;rewindRecovery=0;scanRows=3;rewindScale=1;
        return RuYuanAutoCapture.Action.tap("切换到辅星",frame.auxX,frame.tabY);
    }
    private void collect(StoneCaptureFrame frame)throws Exception{
        JSONObject page=new JSONObject(frame.page.toString());JSONArray lines=page.getJSONArray("lines");int added=0;
        for(int i=0;i<lines.length();i++){
            JSONObject line=lines.getJSONObject(i);if(!line.has("orange")&&!line.optBoolean("captureDeferred"))continue;
            double x=StoneCaptureFrame.center(line)-(line.has("captureAnchorY")?frame.width*.045:0),y=line.optDouble("captureAnchorY",line.getDouble("y"))+offset;
            if(contains(seen,x,y,frame.width))continue;
            if(line.optBoolean("captureDeferred")){
                // 延期位置单独记录，绝不能进入seen，否则下一屏完整显示也会被误判为重复。
                if(!contains(deferred,x,y,frame.width))deferred.add(new double[]{x,y});
                continue;
            }
            deferred.removeIf(point->Math.abs(x-point[0])<frame.width*.07&&Math.abs(y-point[1])<frame.width*.065);
            seen.add(new double[]{x,y});
            if(line.getBoolean("orange")){line.put("captureItemId",kind+"-"+seen.size());added++;}
        }
        if(added==0)return;
        if(result.getInt("count")>=250)throw new Exception("已保存250屏，请结束后核对");
        page.put("capture",new JSONObject().put("version",2).put("sessionId",result.getString("sessionId")).put("kind",kind));
        page.put("index",result.getInt("count")+1);pendingPage=page;
    }
    private static boolean contains(List<double[]> points,double x,double y,int width){
        for(double[] point:points)if(Math.abs(x-point[0])<width*.07&&Math.abs(y-point[1])<width*.065)return true;
        return false;
    }
}
