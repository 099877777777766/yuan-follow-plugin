package com.local.yuanatlas;

import android.accessibilityservice.*;
import android.graphics.*;
import android.os.*;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.*;

/** 卡牌按回合定位，已识别圆圈按整局缓存；操作后只用轻量帧差等待动画平稳。 */
final class RecorderFollowRunner {
    interface Listener {void highlight(RecorderFollowTable.Step step);void status(String text);void stopped(String reason);default void round(int round){}default void enemiesChanged(){}default void layout(RecorderFollowVision.Layout layout){}default void trace(String event){}default void intermission(boolean waiting){}default void battleReset(){}default void runningChanged(boolean running){}default void planChanged(){}}
    interface Gestures {void send(PointF from,PointF to,int duration,java.util.function.Consumer<Boolean> done);Rect overlay();}
    interface Loop {boolean enabled();void play(PointF confirm,Runnable complete,java.util.function.Consumer<String> error);void stop();}
    private final RecorderFollowTable table;private final RecorderRoundAutoReader.FrameSource source;
    private final Gestures gestures;private final Listener listener;private final RecorderFollowSettings settings;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final RecorderMotionWaiter motionWaiter=new RecorderMotionWaiter();
    private final RecorderConditionCheck conditionCheck=new RecorderConditionCheck();
    private final RecorderFazhengCheck fazhengCheck=new RecorderFazhengCheck();
    private final RecorderFazhengProgress fazheng=new RecorderFazhengProgress();
    private boolean fazhengGesturePending;
    // 每个行动完成后重新读取实际回合；同回合继续游标，跨回合从首步开始。
    private boolean roundCheckPending;
    private final RecorderAllyDeathCheck allyDeathCheck=new RecorderAllyDeathCheck();
    private final RecorderCircleCheck circleCheck=new RecorderCircleCheck();
    private final RecorderRoundReader roundReader=new RecorderRoundReader();
    private final RecorderVictoryProbe victoryProbe=new RecorderVictoryProbe();
    private long lastVictoryProbeAt;
    private boolean victoryCapturePending;
    private int captureWidth,captureHeight;
    private RecorderRoundConditions conditions=new RecorderRoundConditions();
    private final Set<Integer> checkedRounds=new HashSet<>();
    private final Map<Integer,RecorderDragonActionPlan> dragonPlans=new HashMap<>();
    private final RecorderEnemyObserver enemyObserver=new RecorderEnemyObserver();
    private final RecorderTauntState taunt=new RecorderTauntState();
    private boolean roundEnemyCheckPending=true,checkingRoundEnemies;
    static final int ROUND_ENEMY_CHECK_MS=2400;
    private int enemyWindow,enemyView;
    private long lastEnemyPositionSkipAt;
    private boolean capturing;private long nextCaptureAt;private int captureGeneration;
    private TextRecognizer recognizer;private boolean running,inFlight,syncRoundOnStart;
    private int generation,round=-1,cursor;private String game="";
    private long missingWindowAt,lastWaitTrace;private String lastWaitStatus="";
    private static final int WINDOW_GRACE_MS=3000;
    private final RecorderEnemyState enemies;
    private Loop loop;private boolean replaying,detectingVictory,awaitFirstRound,betweenBattles;private PointF pendingVictory;
    private long roundReadyAt;
    private RecorderFollowVision.Layout layout;
    private RecorderFollowTable.Step resumePoint;
    // 按需模式下 A/下滑/圈可连续排队；每步模式下任何未判稳动作都须补等待。
    // 暂停保留此标记，避免取消等待后继续时直接切敌；新回合/新局才清空。
    private String pendingMotionAction="";
    private final PointF[] circles=new PointF[5];
    private boolean circlesLocated,circleSurveyPending;private int circleWidth,circleHeight;private String circleGame="";
    private List<RecorderFollowTable.Step> queue=Collections.emptyList();
    private final Set<Integer> completed=new HashSet<>();
    private final RecorderLiveProgress liveProgress=new RecorderLiveProgress();
    private RecorderFollowTable pendingLive;
    private RecorderFollowTable.Step liveGesture;
    /** 主线程接收更新；已派发的手势完成后再替换队列，绝不丢弃它的完成回调。 */
    void updateLive(RecorderFollowTable next){pendingLive=next;if(!inFlight&&!replaying)applyLive();}
    private boolean applyLive(){
        if(pendingLive==null||inFlight||replaying)return false;
        RecorderFollowTable next=pendingLive;pendingLive=null;boolean resume=running;
        liveProgress.remember(queue,cursor);stop("接入实时操作表");
        boolean slotsChanged=!Arrays.equals(table.agents,next.agents);
        table.replace(next);dragonPlans.clear();checkedRounds.clear();if(slotsChanged)clearCircles();
        try{
            if(round>0){queue=table.steps(round);cursor=0;while(cursor<queue.size()&&liveProgress.done(queue.get(cursor)))cursor++;if(cursor<queue.size())completed.remove(round);}
            if(!table.allowAutoFollow){listener.stopped("录入端已关闭自动跟打");return true;}
            if(resume)startSession(null);
        }catch(IllegalArgumentException error){queue=Collections.emptyList();cursor=0;listener.stopped(error.getMessage());}
        return true;
    }
    private void skipLiveCompleted(){if(table.live)while(cursor<queue.size()&&liveProgress.done(queue.get(cursor)))cursor++;}
    boolean displays(RecorderFollowTable shown){return pendingLive==null&&!table.rows.isEmpty()&&!shown.rows.isEmpty()&&table.rows.get(0)==shown.rows.get(0);}
    RecorderFollowRunner(RecorderFollowTable table,RecorderFollowSettings settings,RecorderRoundAutoReader.FrameSource source,Gestures gestures,Listener listener){this.table=table;this.settings=settings;this.source=source;this.gestures=gestures;this.listener=listener;enemies=new RecorderEnemyState(settings.enemies,settings.initialTarget);}
    static Gestures nativeGestures(RuYuanCaptureService service,java.util.function.Supplier<Rect> bounds){return new Gestures(){
        public Rect overlay(){return bounds.get();}
        public void send(PointF from,PointF to,int duration,java.util.function.Consumer<Boolean> done){
            Path path=new Path();path.moveTo(from.x,from.y);if(!from.equals(to))path.lineTo(to.x,to.y);
            GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build();
            if(!service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback(){
                @Override public void onCompleted(GestureDescription g){done.accept(true);}
                @Override public void onCancelled(GestureDescription g){done.accept(false);}
            },null))done.accept(false);
        }
    };}
    boolean running(){return running;}
    /** 待执行动作与动画期间的高亮不同；成功回调后已指向下一步，暂停时用它同步浮窗。 */
    RecorderFollowTable.Step pendingStep(){return cursor<queue.size()?queue.get(cursor):null;}
    void setLoop(Loop loop){this.loop=loop;}
    void setConditions(RecorderRoundConditions value){
        value.validateBase(table);
        // 仅修改延迟等其他设置时，不重新判断本回合龙气或丢失分支中的暂停位置。
        try{if(conditions.json().toString().equals(value.json().toString()))return;}catch(org.json.JSONException ignored){}
        if(round>0){List<RecorderFollowTable.Step> original=table.steps(round);cursor=RecorderDragonActionPlan.remap(original,queue,cursor,original);queue=original;}
        conditions=value.copy();checkedRounds.clear();dragonPlans.clear();listener.planChanged();
    }
    private List<RecorderFollowTable.Step> steps(int round){RecorderDragonActionPlan plan=dragonPlans.get(round);return plan==null?table.steps(round):plan.steps;}
    List<RecorderFollowTable.Step> rowSteps(int row){
        List<RecorderFollowTable.Step> result=new ArrayList<>();RecorderDragonActionPlan plan=dragonPlans.get(table.rows.get(row).round);
        for(RecorderFollowTable.Step step:plan==null?table.rows.get(row).steps:plan.steps)if(step.row==row)result.add(step);return result;
    }
    String cell(int row,int column){
        if(!dragonPlans.containsKey(table.rows.get(row).round)||column!=RecorderRoundConditions.zhugeColumn(table))return table.rows.get(row).cells[column];
        StringJoiner text=new StringJoiner("\n");for(RecorderFollowTable.Step step:rowSteps(row))if(step.column==column)text.add(step.order+step.action);return text.toString();
    }
    private void forgetConditionsFrom(int round){checkedRounds.removeIf(value->value>=round);if(dragonPlans.keySet().removeIf(value->value>=round))listener.planChanged();}
    int enemyCount(){return enemies.count();}
    int currentTarget(){return taunt.active()?taunt.target():enemies.current();}
    int settingsTarget(){return taunt.active()&&taunt.returnTarget()>0?enemies.resolve(taunt.returnTarget()):enemies.current();}
    String targetLabel(){int target=currentTarget();return taunt.active()?(target>0?"嘲讽 "+target:"嘲讽中"):target>0?"当前 "+target:"全部阵亡";}
    boolean enemyAlive(int position){return enemies.alive(position);}
    void toggleEnemy(int position){enemyObserver.corrected(position);enemies.setAlive(position,!enemies.alive(position));listener.enemiesChanged();}
    long enemyRevision(){return enemies.revision();}
    boolean updateRecognizedEnemies(boolean[] alive,long revision){boolean accepted=enemies.applyRecognition(alive,revision);if(accepted)listener.enemiesChanged();return accepted;}
    void updateInitialTarget(){taunt.reset();roundEnemyCheckPending=true;enemyObserver.reconfigured();enemies.configure(settings.enemies,settings.initialTarget);listener.enemiesChanged();}
    private void resetBattle(){roundCheckPending=false;fazheng.clear();taunt.reset();roundEnemyCheckPending=true;checkingRoundEnemies=false;round=-1;roundReadyAt=0;cursor=0;pendingMotionAction="";roundReader.clear();victoryProbe.clear();lastVictoryProbeAt=0;victoryCapturePending=false;enemyObserver.reset();enemies.reset(settings.enemies,settings.initialTarget);completed.clear();liveProgress.clear();checkedRounds.clear();dragonPlans.clear();queue=Collections.emptyList();layout=null;clearCircles();listener.enemiesChanged();listener.battleReset();}
    private void clearCircles(){Arrays.fill(circles,null);circlesLocated=false;circleSurveyPending=false;circleWidth=0;circleHeight=0;circleGame="";}
    void reset(){stop();applyLive();resetBattle();awaitFirstRound=false;}
    void start(){
        startSession(null);
    }
    /** 点选动作作为同回合续打起点；实际回合变化时从首步开始，保留敌方存活及当前目标。 */
    boolean resumeFrom(RecorderFollowTable.Step selected){
        if(running)return false;
        try{
            if(selected==null||selected.row<0||selected.row>=table.rows.size())throw new IllegalArgumentException("请先点选要继续的动作");
            int targetRound=table.rows.get(selected.row).round;
            if(!steps(targetRound).contains(selected))throw new IllegalArgumentException("所选动作不属于当前操作表");
            settings.validate();startSession(selected);return running;
        }catch(IllegalArgumentException error){listener.stopped(error.getMessage());return false;}
    }
    private void startSession(RecorderFollowTable.Step selected){
        stop();
        applyLive();
        if(table.live&&selected!=null){liveProgress.clear();List<RecorderFollowTable.Step> selectedQueue=steps(table.rows.get(selected.row).round);liveProgress.remember(selectedQueue,selectedQueue.indexOf(selected));}
        // 空动作允许跳过；数量、条件类型和槽位结构错误仍需在首条手势前拦截。
        try{settings.validate();conditions.validate(table);}catch(IllegalArgumentException error){listener.stopped(error.getMessage()+"，请打开跟打设置补全");return;}
        running=true;generation++;game="";layout=null;missingWindowAt=0;lastWaitTrace=0;lastWaitStatus="";
        resumePoint=selected;syncRoundOnStart=selected==null;
        roundEnemyCheckPending=true;
        // 手动开始/继续补一个短识别窗口；已经找到的圈不因暂时遮挡丢失。
        circlesLocated=false;
        listener.trace(selected==null?"开始跟打；保留当前进度":"请求继续跟打：第"+table.rows.get(selected.row).round+"回合第"+selected.order+"步/"+(selected.column+1)+"号位"+selected.action);
        listener.trace("敌方阵亡识别="+(detectEnemies()?"开启":"关闭")+"，敌方数量="+settings.enemies);
        recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        // 与真正的会话生命周期绑定，继续、循环等待同样维持前台运行；打开表本身不启动。
        listener.runningChanged(true);
        int token=generation;handler.post(()->poll(token));watch(token);watchVictory(token);
    }
    void stop(){stop("关闭跟打或切换设置/界面");}
    private void stop(String reason){
        // 先保存停止原因和游标，再清理异步任务；自动取消勾选触发的第二次 stop 不重复记日志。
        if(running)listener.trace("跟打停止："+reason+"；"+progress());
        // 断线/用户暂停不能撤回已派发手势；实时模式将该位置记为已发出，恢复时不重复点击。
        if(table.live&&inFlight&&liveGesture!=null){liveProgress.completed(liveGesture);if(cursor<queue.size()&&queue.get(cursor)==liveGesture)cursor++;}
        liveGesture=null;
        boolean interruptedLoop=replaying;boolean wasRunning=running;running=false;inFlight=false;replaying=false;detectingVictory=false;pendingVictory=null;enemies.finishGesture(false);generation++;
        if(wasRunning)listener.runningChanged(false);
        capturing=false;captureGeneration++;resumePoint=null;syncRoundOnStart=false;roundReader.clear();victoryProbe.clear();lastVictoryProbeAt=0;victoryCapturePending=false;captureWidth=0;captureHeight=0;
        enemyWindow++;enemyView++;checkingRoundEnemies=false;enemyObserver.endRound();
        handler.removeCallbacksAndMessages(null);motionWaiter.cancel();conditionCheck.stop();fazhengCheck.stop();allyDeathCheck.stop();circleCheck.stop();if(loop!=null)loop.stop();if(recognizer!=null){recognizer.close();recognizer=null;}
        // 中止局间回放后，下一次开始不能被上一局的 completed 回合列表挡住。
        if(interruptedLoop){resetBattle();awaitFirstRound=true;}listener.enemiesChanged();
        if(pendingLive!=null)applyLive();
    }
    private boolean valid(int token){return running&&token==generation;}
    private String progress(){
        String point=round<0?"尚未读到回合":"第"+round+"回合";
        if(cursor<queue.size()){RecorderFollowTable.Step step=queue.get(cursor);point+="，待第"+step.order+"步/"+(step.column+1)+"号位"+step.action;}else if(round>=0)point+="，本回合操作已完成";
        return point+"，手势执行中="+inFlight+"，循环回放中="+replaying;
    }
    private void fail(String reason){stop(reason);listener.stopped(reason);}
    /** 系统短暂返回空窗口不代表用户离开；等待期间绝不发手势，明确切到其他应用仍立即停止。 */
    private boolean windowReady(int token,Runnable retry){
        if(!valid(token))return false;
        String focused=source.focusedGame();
        if(game.equals(focused)){
            if(missingWindowAt>0)listener.trace("游戏窗口已恢复，等待="+(SystemClock.uptimeMillis()-missingWindowAt)+"ms；"+progress());
            missingWindowAt=0;return true;
        }
        if(focused!=null&&!focused.isEmpty()){fail("已离开游戏，跟打停止");return false;}
        long now=SystemClock.uptimeMillis();
        if(missingWindowAt==0){missingWindowAt=now;listener.trace("暂未读到游戏窗口，保留当前步骤等待；"+progress());}
        if(now-missingWindowAt>=WINDOW_GRACE_MS){fail("持续未读到游戏窗口，跟打已停止，请回到游戏后继续");return false;}
        listener.status("等待读取游戏窗口");
        if(retry!=null)handler.postDelayed(retry,200);
        return false;
    }
    private void watch(int token){handler.postDelayed(()->{if(!valid(token))return;if(!game.isEmpty())windowReady(token,null);if(valid(token))watch(token);},200);}
    /** 回合、胜利和帧差共用截图节奏，避免同时请求触发系统截图限流。 */
    private void capture(int token,RuYuanCaptureService.GameScreenshotCallback callback){
        capture(token,0,0,callback);
    }
    private void capture(int token,int kind,int observation,RuYuanCaptureService.GameScreenshotCallback callback){
        // 0=完整定位帧，1=动画小图，2=动作观察小图，3=无共享帧时的结算小图。
        // 结算补帧独立于动作窗口；动作结束不应把其回调和 pending 标志一起悬空。
        if(!valid(token)||kind==2&&observation!=enemyWindow)return;
        if(kind==3&&(replaying||SystemClock.uptimeMillis()-lastVictoryProbeAt<1200)){callback.onFailure("");return;}
        if(!windowReady(token,()->capture(token,kind,observation,callback)))return;
        long wait=nextCaptureAt-SystemClock.uptimeMillis();
        if(capturing||wait>0){handler.postDelayed(()->capture(token,kind,observation,callback),Math.max(50,wait));return;}
        capturing=true;int request=++captureGeneration;boolean[] settled={false};nextCaptureAt=SystemClock.uptimeMillis()+RecorderActionMotion.INTERVAL_MS;
        RuYuanCaptureService.GameScreenshotCallback response=new RuYuanCaptureService.GameScreenshotCallback(){
            public void onSuccess(Bitmap frame){
                if(settled[0]||request!=captureGeneration){frame.recycle();return;}settled[0]=true;capturing=false;
                if(valid(token)){
                    if(kind==0){captureWidth=frame.getWidth();captureHeight=frame.getHeight();}
                    // 在交还所有权前截取结算区域；文字识别只持有小裁图，调用方仍按原时机释放帧。
                    // 完整帧先读回合，读不到再检查结算；观察小图在结算候选出现后复核同帧回合。
                    if(kind>=2)considerVictory(token,frame,source.battleOverlayBounds(),false);
                    callback.onSuccess(frame);
                }else frame.recycle();
            }
            public void onFailure(String error){if(settled[0]||request!=captureGeneration)return;settled[0]=true;capturing=false;if(valid(token))callback.onFailure(error);}
        };
        handler.postDelayed(()->response.onFailure("截图超时"),2500);
        try{if(kind>=2)source.captureBattle(game,response);else if(kind==1)source.captureMotion(game,response);else source.capture(game,response);}catch(Exception|OutOfMemoryError e){response.onFailure("");}
    }
    /** 独立的轻量结算检测，不让识别结果阻塞动作；提前胜利/失败都能取消剩余操作。 */
    private void watchVictory(int token){
        if(loop==null||!loop.enabled()||!valid(token))return;
        handler.postDelayed(()->{
            if(!valid(token)||replaying)return;
            // 有等待/回合采样时复用它；没有共享帧时才补一张 448 宽图，不再定时读回全屏。
            if(!game.isEmpty()&&!detectingVictory&&!victoryCapturePending&&pendingVictory==null&&!awaitFirstRound&&SystemClock.uptimeMillis()-lastVictoryProbeAt>=1200){
                victoryCapturePending=true;
                try{capture(token,3,0,new RuYuanCaptureService.GameScreenshotCallback(){public void onFailure(String reason){victoryCapturePending=false;}public void onSuccess(Bitmap frame){victoryCapturePending=false;frame.recycle();}});}catch(Exception ignored){victoryCapturePending=false;}
            }
            watchVictory(token);
        },1200);
    }
    private void considerVictory(int token,Bitmap frame,Rect overlay,boolean roundMissing){
        long now=SystemClock.uptimeMillis();
        if(loop==null||!loop.enabled()||replaying||awaitFirstRound||detectingVictory||pendingVictory!=null||captureWidth==0||now-lastVictoryProbeAt<1200)return;
        lastVictoryProbeAt=now;
        try{
            if(!victoryProbe.shouldRead(frame,overlay,now))return;
            Rect display=source.screenBounds();int width=display==null?frame.getWidth():display.width(),height=display==null?frame.getHeight():display.height();
            float sx=width/(float)frame.getWidth(),sy=height/(float)frame.getHeight();
            detectingVictory=true;
            RecorderVictoryReader.read(recognizer,frame,overlay,roundMissing).addOnCompleteListener(task->{
                if(!valid(token))return;detectingVictory=false;
                Rect current=source.screenBounds();
                if(current!=null&&(current.width()!=width||current.height()!=height)){victoryProbe.clear();return;}
                victoryProbe.completed(task.isSuccessful()&&task.getResult()==null,SystemClock.uptimeMillis());
                if(task.isSuccessful()&&task.getResult()!=null){
                    RecorderVictoryReader.Result result=task.getResult();PointF point=result.confirm;
                    pendingVictory=new PointF(point.x*sx,point.y*sy);
                    listener.trace("识别到"+(result.defeated?"失败":"胜利")+"结算，同帧未读到回合；确定坐标=("+Math.round(pendingVictory.x)+","+Math.round(pendingVictory.y)+")，衔接自定义循环");
                    if(!inFlight)beginLoop(token);
                }
            });
        }catch(Exception|OutOfMemoryError error){detectingVictory=false;victoryProbe.clear();}
    }
    private void beginLoop(int token){
        if(!valid(token)||pendingVictory==null||inFlight||loop==null||!loop.enabled())return;
        playLoop(token,pendingVictory);
    }
    /** 收起状态跨越撤退确认、录制回放和加载，直到新局真正准备开始跟表。 */
    private void enterIntermission(){if(!betweenBattles){betweenBattles=true;listener.intermission(true);}}
    /** 结算传入确定坐标；条件退出后传 null，直接接录制，绝不能再点击旧结算坐标。 */
    private void playLoop(int token,PointF confirm){
        if(!valid(token)||inFlight||loop==null||!loop.enabled())return;
        if(!windowReady(token,()->playLoop(token,confirm)))return;
        enterIntermission();
        pendingVictory=null;replaying=true;int next=++generation;
        capturing=false;captureGeneration++;
        enemyWindow++;enemyView++;enemyObserver.interrupt();
        handler.removeCallbacksAndMessages(null);motionWaiter.cancel();conditionCheck.stop();fazhengCheck.stop();allyDeathCheck.stop();circleCheck.stop();watch(next);listener.status(confirm==null?"已退出战斗，执行录制操作":"结算确定，执行录制操作");
        try{loop.play(confirm,()->{
            if(!valid(next))return;resetBattle();awaitFirstRound=true;replaying=false;listener.status("等待新一局起始回合");
            if(!applyLive()){poll(next);watchVictory(next);}
        },reason->{if(valid(next))fail(reason);});}catch(Exception e){fail(e.getMessage()==null?"循环操作失败":e.getMessage());}
    }
    private void later(int token){if(valid(token))handler.postDelayed(()->poll(token),900);}
    private void waiting(int token,String text){
        if(!valid(token))return;
        long now=SystemClock.uptimeMillis();
        // 等待与真正停止分开记录；相同等待只每 15 秒补一条，不挤掉最近动作日志。
        if(!text.equals(lastWaitStatus)||now-lastWaitTrace>=15000){lastWaitStatus=text;lastWaitTrace=now;listener.trace("跟打等待："+text+"；"+progress());}
        listener.status(text);later(token);
    }
    /** 每轮都先识别回合；读不到检查胜利/失败结算，否则等待，不发送本轮操作。 */
    private void poll(int token){
        if(!valid(token))return;String foreground=source.focusedGame();
        if(game.isEmpty()){
            if(foreground==null||foreground.isEmpty()){waiting(token,"进入游戏后开始跟打");return;}
            game=foreground;
        }
        if(!windowReady(token,()->poll(token)))return;
        try{capture(token,new RuYuanCaptureService.GameScreenshotCallback(){
            public void onFailure(String error){waiting(token,"等待读取游戏回合");}
            public void onSuccess(Bitmap frame){
                if(!valid(token)){frame.recycle();return;}
                if(!windowReady(token,()->poll(token))){frame.recycle();return;}
                // 轮间含敌方行动、召唤和强制镜头，不能在回合尚未确认时按旧目标写入存活状态。
                try{roundReader.read(recognizer,frame,source.overlayBounds()).addOnSuccessListener(reading->{
                    if(!valid(token))return;
                    if(reading==null){considerVictory(token,frame,source.overlayBounds(),true);waiting(token,"等待读取游戏回合");return;}
                    if(resumePoint!=null){
                        try{
                            RecorderFollowTable.Step selected=resumePoint;
                            int selectedRound=table.rows.get(selected.row).round;
                            boolean sameRound=reading.current==selectedRound;
                            if(reading.current!=round)pendingMotionAction="";
                            // 先校验实际回合，再提交游标；表中缺少该回合时保留原暂停进度。
                            table.steps(reading.current);
                            if(!sameRound)forgetConditionsFrom(reading.current);
                            List<RecorderFollowTable.Step> nextQueue=steps(reading.current);
                            // 比较的是点选回合，即使执行器原本就在实际回合，也要从首步重新对齐。
                            roundReadyAt=sameRound?0:SystemClock.uptimeMillis()+settings.roundStartDelay;
                            round=reading.current;queue=nextQueue;cursor=sameRound?queue.indexOf(selected):0;
                            syncEnemyRound();
                            cacheLayout(frame);
                            // 确认回合后才提交新进度；等待中取消不能提前清除完成记录。
                            awaitFirstRound=false;completed.removeIf(value->value>=reading.current);
                            resumePoint=null;roundCheckPending=false;listener.round(round);
                            listener.trace(sameRound?"继续跟打：已识别第"+round+"回合，从第"+selected.order+"步/"+(selected.column+1)+"号位"+selected.action+"开始"
                                :"继续跟打：所选第"+selectedRound+"回合，实际识别第"+round+"回合，已切到实际回合首步；"+progress());
                            prepare(token);
                        }catch(IllegalArgumentException error){fail(error.getMessage());}
                        catch(Exception|OutOfMemoryError error){fail("继续位置准备失败，请重新点选动作后继续");}
                        return;
                    }
                    // 录制流程可能包含加载时间；不把上一局末回合当作新一局开始。
                    if(awaitFirstRound){int first=table.rows.get(0).round;if(reading.current!=first){waiting(token,"等待新一局起始回合");return;}awaitFirstRound=false;}
                    // 仅同回合完成后等待；前进或回退都按实际识别回合重新对齐。
                    if(!syncRoundOnStart&&reading.current==round&&completed.contains(round)){waiting(token,"等待下一回合");return;}
                    try{
                        boolean newRound=reading.current!=round;
                        if(newRound){
                            pendingMotionAction="";
                            table.steps(reading.current); // 校验回合有效后才能清理旧分支。
                            // 自动运行也可能前进或回退，旧完成/条件记录不能挡住实际回合。
                            completed.removeIf(value->value>=reading.current);
                            forgetConditionsFrom(reading.current);
                            liveProgress.clear();
                            if(round>=0)listener.trace("回合自动对齐：第"+round+"回合 → 第"+reading.current+"回合，从首个行动开始");
                            // 计时点是识别到新回合的此刻。坐标定位消耗的时间计入等待，不在定位后再额外等一遍。
                            roundReadyAt=SystemClock.uptimeMillis()+settings.roundStartDelay;
                            queue=steps(reading.current);round=reading.current;cursor=0;listener.round(round);
                            roundEnemyCheckPending=true;
                        }
                        // 同回合保留当前待执行游标（含续行及中断手势），不从第一个动作重放。
                        if(syncRoundOnStart){
                            if(!newRound)listener.round(round);
                            listener.trace("开始跟打已对齐："+(newRound?"切到识别回合首步；":"保留当前操作；")+progress());
                        }
                        syncRoundOnStart=false;roundCheckPending=false;
                        if(completed.contains(round)){waiting(token,"等待下一回合");return;}
                        syncEnemyRound();
                        if(layout==null||newRound)cacheLayout(frame);
                        prepare(token);
                    }catch(OutOfMemoryError e){fail("内存不足，已暂停跟打并保留表格");}
                    catch(Exception e){fail(e.getMessage()==null?"跟打已停止":e.getMessage());}
                }).addOnFailureListener(e->waiting(token,"等待读取游戏回合"))
                  .addOnCompleteListener(t->frame.recycle());
                }catch(Exception|OutOfMemoryError e){frame.recycle();waiting(token,"等待读取游戏回合");}
            }
        });}catch(Exception e){waiting(token,"等待读取游戏回合");}
    }
    private void cacheLayout(Bitmap frame){
        Rect overlay=source.overlayBounds();Bitmap clean=CaptureOcrImage.excluding(frame,overlay);
        try{
            Rect previousViewport=layout==null?null:layout.viewport;
            // 横跨平板黑边的浮窗不参与边界采样；识别与兜底必须共用已扣除黑边的坐标。
            Rect viewport=GameViewport.detect(frame,overlay);
            layout=RecorderFollowVision.locate(clean,viewport);
            boolean fallback=layout==null;
            if(fallback)layout=RecorderFollowVision.standard(frame,viewport);
            listener.trace("卡牌定位：截图="+frame.getWidth()+"x"+frame.getHeight()+"，游戏="+viewport+"，卡牌="+layout.cards+"，比例兜底="+fallback);
            // 一局内圈的实际位置固定，跨回合保留；换游戏/屏幕尺寸后旧坐标不再有效。
            if(previousViewport!=null&&!previousViewport.equals(layout.viewport)||circleWidth!=frame.getWidth()||circleHeight!=frame.getHeight()||!circleGame.equals(game))clearCircles();
            if(!circlesLocated){
                for(int column=0;column<5;column++)if(circles[column]==null)circles[column]=RecorderFollowVision.circle(clean,layout,column);
                circlesLocated=true;circleSurveyPending=true;circleWidth=frame.getWidth();circleHeight=frame.getHeight();circleGame=game;
                StringJoiner found=new StringJoiner("、");for(int column=0;column<5;column++)if(circles[column]!=null)found.add(String.valueOf(column+1));
                listener.trace("本局圆圈缓存："+(found.length()==0?"未识别到":found+"号位")+"；跨回合复用，重新抄/新一局清空");
            }
        }finally{if(clean!=frame)clean.recycle();}
        listener.layout(layout);
    }
    private boolean detectEnemies(){return settings.detectEnemyDeaths&&RecorderEnemyVision.configured(settings);}
    /** 必须先确认游戏回合，再应用本表嘲讽规则；恢复只更新记录，游戏已自行返回。 */
    private void syncEnemyRound(){
        fazheng.sync(round);
        boolean was=taunt.active();int old=taunt.target();
        int restore=taunt.sync(round,settings.tauntRounds,enemies.current());
        if(restore>0)enemies.restoreTarget(restore);
        if(was!=taunt.active()||old!=taunt.target()){
            enemyView++;enemyWindow++;enemyObserver.interrupt();
            listener.trace(taunt.active()?"第"+round+"回合嘲讽："+(taunt.target()>0?taunt.target()+"号位":"目标未知")+"，暂停切敌，结束后返回"+taunt.returnTarget()+"号位":"嘲讽结束：恢复嘲讽前目标"+enemies.current()+"号位，等待回合血条核对");
            listener.enemiesChanged();
        }
    }
    private void observeEnemies(Bitmap frame,Rect overlay,long revision){
        if(!detectEnemies()||inFlight||replaying||revision!=enemies.revision())return;
        // 目标未知的嘲讽，以及本回合动作结束后的敌方镜头，都不能按旧位置做投影。
        if(taunt.active()&&(taunt.target()==0||!checkingRoundEnemies)||!checkingRoundEnemies&&completed.contains(round))return;
        try{
            boolean[] alive=new boolean[enemies.count()];for(int p=1;p<=alive.length;p++)alive[p-1]=enemies.alive(p);
            boolean[] result=enemyObserver.observe(frame,overlay,alive,currentTarget(),SystemClock.uptimeMillis());
            // 模糊/遮挡只每 15 秒保留一条摘要，避免光效挤掉动作日志；不增加截图或等待。
            if(!enemyObserver.ignoredReason().isEmpty()&&SystemClock.uptimeMillis()-lastEnemyPositionSkipAt>=15000){
                lastEnemyPositionSkipAt=SystemClock.uptimeMillis();listener.trace(enemyObserver.ignoredReason());
            }
            RecorderEnemyObserver.Comparison comparison=enemyObserver.comparison();int previousTarget=enemies.current();
            if(result==null)result=alive;
            // 从已知当前编号及前后血条位置确认跳位，并同时提交缺失编号；属性不参与识别。
            if(enemies.applyObservation(result,taunt.active()||comparison==null?0:comparison.center,revision)){
                listener.enemiesChanged();StringJoiner dead=new StringJoiner("、"),restored=new StringJoiner("、");for(int p=0;p<result.length;p++){if(alive[p]&&!result[p])dead.add(String.valueOf(p+1));if(!alive[p]&&result[p])restored.add(String.valueOf(p+1));}
                if(dead.length()>0)listener.trace("血条确认阵亡："+dead+"号；当前目标="+enemies.current());
                if(restored.length()>0)listener.trace("血条纠正存活："+restored+"号；当前目标="+enemies.current());
                if(comparison!=null&&(previousTarget!=enemies.current()||dead.length()>0||restored.length()>0))listener.trace("敌方前后对照：上次中央="+comparison.from+"，本次中央="+comparison.center+"，原编号顺向间隔="+comparison.forward+"，新增缺失="+Arrays.toString(comparison.absent)+"；当前目标校正="+previousTarget+"→"+enemies.current());
            }
        }catch(Exception|OutOfMemoryError error){enemyObserver.interrupt();}
    }
    /** 复用同一张等待采样图；血条识别失败仍将图像交给原有帧差判断。 */
    private void captureWaitFrame(int token,int observation,RuYuanCaptureService.GameScreenshotCallback callback){
        long revision=enemies.revision();int view=enemyView;
        capture(token,2,observation,new RuYuanCaptureService.GameScreenshotCallback(){
            public void onFailure(String reason){if(view==enemyView)enemyObserver.gap(SystemClock.uptimeMillis());callback.onFailure(reason);}
            // 等待阶段改变不代表游戏视角改变；上一动作末尾的迟到帧仍可在前置延迟内使用。
            public void onSuccess(Bitmap frame){if(valid(token)&&view==enemyView&&!inFlight&&game.equals(source.focusedGame()))observeEnemies(frame,source.battleOverlayBounds(),revision);callback.onSuccess(frame);}
        });
    }
    private void watchEnemyDelay(int token,int observation){
        if(!valid(token)||observation!=enemyWindow||!detectEnemies())return;
        captureWaitFrame(token,observation,new RuYuanCaptureService.GameScreenshotCallback(){
            public void onFailure(String reason){again();}
            public void onSuccess(Bitmap frame){frame.recycle();again();}
            private void again(){if(valid(token)&&observation==enemyWindow)handler.postDelayed(()->watchEnemyDelay(token,observation),RecorderActionMotion.INTERVAL_MS);}
        });
    }
    private void prepare(int token){
        if(!valid(token)||replaying)return;
        if(circleSurveyPending){
            circleSurveyPending=false;boolean[] missing=new boolean[5];
            // 只补当前及之后的表格里真正会用圈的列，不因其他密探没有圈拖延跟打。
            for(RecorderFollowTable.Row row:table.rows)if(row.round>=round)for(RecorderFollowTable.Step step:row.steps)
                // 条件动作只含 A/↑/↓，被替换的诸葛亮圈不再需要补识别。
                if(step.action.equals("圈")&&circles[step.column]==null&&!(conditions.actionRequired(row.round)&&step.column==RecorderRoundConditions.zhugeColumn(table)))missing[step.column]=true;
            for(boolean needed:missing)if(needed){retryCircles(token,missing,()->prepare(token));return;}
        }
        // 补圈耗时计入配置延迟；延迟结束再检查敌方，避免召唤/嘲讽切镜头尚未完成。
        long remaining=Math.max(0,roundReadyAt-SystemClock.uptimeMillis());
        if(remaining>0){
            listener.status("等待回合开始");
            handler.postDelayed(()->checkRoundEnemies(token),remaining);
        }else checkRoundEnemies(token);
    }
    /** 每回合首步及暂停继续前独立复核；三帧/800ms，超时保留状态，绝不把未知当阵亡。 */
    private void checkRoundEnemies(int token){
        if(!valid(token)||replaying)return;
        if(!roundEnemyCheckPending||!detectEnemies()||taunt.active()&&taunt.target()==0){roundEnemyCheckPending=false;checkRoundConditions(token);return;}
        final int observation=++enemyWindow;final long started=SystemClock.uptimeMillis();boolean[] finished={false};
        checkingRoundEnemies=true;enemyObserver.beginRound(taunt.active()&&taunt.target()>0);
        listener.status("第"+round+"回合核对敌方存活 / 新增");
        Runnable finish=()->{
            if(finished[0]||!valid(token)||observation!=enemyWindow)return;finished[0]=true;
            boolean confirmed=enemyObserver.roundConfirmed();checkingRoundEnemies=false;roundEnemyCheckPending=false;enemyWindow++;enemyView++;enemyObserver.endRound();
            listener.trace("回合开始敌方核对：第"+round+"回合，"+(confirmed?"多帧确认":"未完整确认，保留最近状态")+"，耗时="+(SystemClock.uptimeMillis()-started)+"ms，"+targetLabel());
            checkRoundConditions(token);
        };
        handler.postDelayed(finish,ROUND_ENEMY_CHECK_MS);
        Runnable read=new Runnable(){public void run(){
            if(finished[0]||!valid(token)||observation!=enemyWindow)return;
            captureWaitFrame(token,observation,new RuYuanCaptureService.GameScreenshotCallback(){
                public void onSuccess(Bitmap frame){frame.recycle();again();}
                public void onFailure(String reason){again();}
                private void again(){if(finished[0]||!valid(token)||observation!=enemyWindow)return;if(enemyObserver.roundConfirmed())finish.run();else handler.postDelayed(()->run(),RecorderActionMotion.INTERVAL_MS);}
            });
        }};read.run();
    }
    private void retryCircles(int token,boolean[] columns,Runnable complete){
        enemyWindow++;long started=SystemClock.uptimeMillis();StringJoiner positions=new StringJoiner("、");
        for(int column=0;column<5;column++)if(columns[column])positions.add(String.valueOf(column+1));
        listener.status("确认"+positions+"号位密探的圈");listener.trace("圆圈补识别："+positions+"号位，最多"+RecorderCircleCheck.WAIT_MS+"ms");
        circleCheck.start(columns,layout,callback->capture(token,callback),source::overlayBounds,()->valid(token)&&!replaying,
            (column,point)->{circles[column]=point;listener.trace("圆圈多帧确认："+(column+1)+"号位 ("+Math.round(point.x)+","+Math.round(point.y)+")，本局缓存");},
            ()->{listener.trace("圆圈补识别结束：耗时="+(SystemClock.uptimeMillis()-started)+"ms");complete.run();});
    }
    private void checkRoundConditions(int token){
        if(!valid(token)||replaying)return;
        if(!conditions.required(round)||checkedRounds.contains(round)){next(token);return;}
        enemyWindow++;
        int mask=conditions.deathCells.getOrDefault(round,0);
        if(mask!=0){
            listener.status("第"+round+"回合检测密探阵亡");
            allyDeathCheck.start(recognizer,round,mask,layout,callback->capture(token,callback),source::overlayBounds,()->valid(token)&&!replaying,listener::trace,
                ()->{if(valid(token))checkOtherRoundConditions(token);},reason->exitForCondition(token,reason),reason->{if(valid(token))fail(reason);});
        }else checkOtherRoundConditions(token);
    }
    /** 阵亡检查后检查星色/龙气/龙脉重开规则及四组合条件动作，每回合确认一次。 */
    private void checkOtherRoundConditions(int token){
        if(!valid(token)||replaying)return;
        listener.status("识别第"+round+"回合条件");
        conditionCheck.start(round,conditions,table,layout,callback->capture(token,callback),source::overlayBounds,()->valid(token)&&!replaying,listener::trace,
            reading->{if(valid(token)){
                if(conditions.actionRequired(round)){
                    try{
                        List<String> actions=conditions.branchActions(round,reading.actionSource,reading.actionMatched);
                        RecorderDragonActionPlan plan=new RecorderDragonActionPlan(table,round,actions,reading.dragon,reading.actionMatched);
                        cursor=RecorderDragonActionPlan.remap(table.steps(round),queue,cursor,plan.steps);queue=plan.steps;dragonPlans.put(round,plan);
                        String source=reading.actionSource.equals("vein")?"龙脉条件动作："+RecorderDragonVeinVision.name(reading.vein):reading.actionSource.startsWith("joint-")?"条件动作："+RecorderDragonVeinVision.name(reading.vein)+"，龙气="+reading.dragon+"（"+(reading.actionMatched?"范围内":"范围外")+"）":"龙气条件动作：龙气="+reading.dragon+"，"+(plan.matched?"满足":"不满足")+"条件";
                        listener.trace("第"+round+"回合"+source+"，诸葛亮执行 "+RecorderDragonActionCondition.describeActions(actions)+"；保留原出手位置");
                        listener.planChanged();
                    }catch(IllegalArgumentException error){fail(error.getMessage());return;}
                }
                checkedRounds.add(round);next(token);
            }},reason->exitForCondition(token,reason),reason->{if(valid(token))fail(reason);});
    }
    /** 不满足条件后独立接管退出；取消整轮待发操作和结算观察，停止时仍可中止这条链。 */
    private void exitForCondition(int token,String reason){
        if(!valid(token))return;
        Rect title=roundReader.titleBounds(layout.width,layout.height);
        // 手机与短屏平板的工具栏高度不同；右上已确认的回合标题给出返回按钮所在行。
        // 未取得真实标题位置时停止，不能再凭整屏比例盲点到按钮下方。
        if(title==null){listener.trace("条件退出：当前回合标题位置缺失，未派发返回手势");fail("未定位到左上返回按钮，已停止，请核对游戏画面后重试");return;}
        PointF back=new PointF(layout.x(.09f),title.exactCenterY());
        listener.trace(reason+"，退出战斗");listener.status("条件不满足，退出战斗");
        int next=++generation;replaying=true;pendingVictory=null;detectingVictory=false;capturing=false;captureGeneration++;enemyWindow++;enemyView++;
        handler.removeCallbacksAndMessages(null);motionWaiter.cancel();conditionCheck.stop();fazhengCheck.stop();allyDeathCheck.stop();circleCheck.stop();enemyObserver.interrupt();watch(next);
        listener.trace("条件退出：点击左上返回 ("+Math.round(back.x)+","+Math.round(back.y)+")，回合标题="+title+"，游戏="+layout.viewport);
        long deadline=SystemClock.uptimeMillis()+8000;
        dispatch(next,back,back,90,0,()->{
            enterIntermission();
            handler.postDelayed(()->verifyConditionExit(next,deadline,reason,0,0),700);
        });
    }
    private void verifyConditionExit(int token,long deadline,String reason,int confirmations,int clear){
        if(!valid(token))return;
        if(SystemClock.uptimeMillis()>deadline){fail("未能确认已退出战斗，跟打已停止，请手动核对退出界面"+(confirmations>0?"（已点击确定，等待退出超时）":"（未识别到撤退确定）"));return;}
        capture(token,new RuYuanCaptureService.GameScreenshotCallback(){
            public void onFailure(String text){retry(confirmations,0);}
            private void retry(int taps,int empty){if(valid(token))handler.postDelayed(()->verifyConditionExit(token,deadline,reason,taps,empty),500);}
            public void onSuccess(Bitmap frame){
                try{RecorderBattleExitReader.read(recognizer,frame,source.overlayBounds()).addOnCompleteListener(task->{
                    frame.recycle();if(!valid(token))return;
                    if(!task.isSuccessful()){retry(confirmations,0);return;}
                    RecorderBattleExitReader.Screen screen=task.getResult();
                    // 确认只点一次，之后等待界面消失；不在未看清页面时盲点下一步。
                    if(screen.confirm!=null&&confirmations==0){
                        listener.trace("撤退确认：点击右侧确定 ("+Math.round(screen.confirm.x)+","+Math.round(screen.confirm.y)+")");
                        // 找按钮和等待退出分别计时，避免较晚识别到确定后只剩很短的加载时间。
                        dispatch(token,screen.confirm,screen.confirm,90,0,()->{
                            long exitDeadline=SystemClock.uptimeMillis()+8000;
                            handler.postDelayed(()->verifyConditionExit(token,exitDeadline,reason,1,0),900);
                        });return;
                    }
                    // 必须先点过本次撤退的确定；偶然读到背景/加载页不能直接进入录制。
                    if(confirmations==0){retry(0,0);return;}
                    if(screen.battle||screen.dialog){listener.trace("撤退确认：已点击确定，等待读取退出页面；战斗="+screen.battle+"，弹窗="+screen.dialog);retry(confirmations,0);return;}
                    if(clear<1){retry(confirmations,clear+1);return;}
                    listener.trace("条件退出已完成");
                    if(loop!=null&&loop.enabled())playLoop(token,null);else fail(reason+"，已退出并结束跟打");
                });}catch(Exception|OutOfMemoryError error){frame.recycle();onFailure("");}
            }
        });
    }
    private void next(int token){
        if(!valid(token)||replaying)return;
        if(applyLive())return;
        if(roundCheckPending){poll(token);return;}
        skipLiveCompleted();
        // 此处已经识别起始回合、完成本局定位及回合条件检测；回放完成本身不展开浮窗。
        if(betweenBattles){betweenBattles=false;listener.intermission(false);}
        enemyWindow++;
        // 下拉回调已推进原表游标，末步也必须完成圣物检查和补拉；暂停继续先补检查。
        fazheng.sync(round);
        if(fazhengGesturePending){handler.postDelayed(()->next(token),100);return;}
        if(conditions.fazheng.rounds.contains(round)){
            if(fazheng.pendingCheck){checkFazheng(token);return;}
            if(fazheng.pending!=null){listener.highlight(fazheng.anchor);scheduleTarget(token,fazheng.pending);return;}
        }
        if(cursor>=queue.size()){
            completed.add(round);waiting(token,"等待下一回合");return;
        }
        RecorderFollowTable.Step step=queue.get(cursor);listener.highlight(step);
        if(fazhengEnabled(step)&&step.action.equals("↓")){
            if(fazheng.pulls>=5){listener.trace("法正本回合已下拉 5 次，跳过原表额外 ↓");if(table.live)liveProgress.completed(step);cursor++;handler.post(()->next(token));return;}
            if(!fazheng.finished)fazheng.begin(step);
        }
        // 初帧可能刚好被光效遮住；真正用圈前只补本列一次，确认前不切敌、不点击。
        if(step.action.equals("圈")&&circles[step.column]==null){
            boolean[] missing=new boolean[5];missing[step.column]=true;
            retryCircles(token,missing,()->{
                if(circles[step.column]!=null){next(token);return;}
                String reason="第"+round+"回合第"+step.order+"步：未识别到"+(step.column+1)+"号位密探的圈，跟打已停止";
                listener.trace(reason+"；已短时补识别，未派发本步手势，保留当前步骤");fail(reason);
            });return;
        }
        listener.status("执行 "+step.order+step.action);
        scheduleTarget(token,step);
    }
    private void scheduleTarget(int token,RecorderFollowTable.Step step){
        int before=settings.delay(step.action,true),observation=++enemyWindow;
        // 前置为 0 时交给下一行的复核窗口直接取图，避免先发一次又立刻因窗口换代废弃。
        if(before>0&&detectEnemies()&&(before>=RecorderActionMotion.INTERVAL_MS||enemyObserver.pending(SystemClock.uptimeMillis())))watchEnemyDelay(token,observation);
        handler.postDelayed(()->reviewEnemyThenAct(token,step),before);
    }
    /** 已发现缺失时，先在原前置延迟内复核；仍缺确认帧才补一个有截止时间的短窗口。 */
    private void reviewEnemyThenAct(int token,RecorderFollowTable.Step step){
        if(!valid(token))return;
        if(!detectEnemies()||!enemyObserver.pending(SystemClock.uptimeMillis())){targetThenAct(token,step);return;}
        final int observation=++enemyWindow;final long started=SystemClock.uptimeMillis();boolean[] finished={false};
        Runnable finish=()->{
            if(finished[0])return;finished[0]=true;
            if(!valid(token)||observation!=enemyWindow)return;
            listener.trace("切敌前血条复核：耗时="+(SystemClock.uptimeMillis()-started)+"ms，当前目标="+enemies.current()+"，仍待确认="+enemyObserver.pending(SystemClock.uptimeMillis()));
            enemyWindow++;targetThenAct(token,step);
        };
        handler.postDelayed(finish,800);
        Runnable read=new Runnable(){public void run(){
            if(finished[0]||!valid(token)||observation!=enemyWindow)return;
            captureWaitFrame(token,observation,new RuYuanCaptureService.GameScreenshotCallback(){
                public void onSuccess(Bitmap frame){frame.recycle();again();}
                public void onFailure(String reason){again();}
                private void again(){if(finished[0]||!valid(token))return;if(!enemyObserver.pending(SystemClock.uptimeMillis()))finish.run();else handler.postDelayed(()->run(),RecorderActionMotion.INTERVAL_MS);}
            });
        }};read.run();
    }
    /** 换位和密探手势都先等配置的前置延迟，防止过渡动画吞掉滑动后仍把目标编号累计下去。 */
    private void targetThenAct(int token,RecorderFollowTable.Step step){
        if(!valid(token))return;
        if(pendingVictory!=null){beginLoop(token);return;}
        // 全部置灰时保留当前步骤，用户恢复存活后继续；不截图、不额外发送手势。
        if(enemies.current()==0&&!taunt.active()){listener.status("敌方已全部阵亡，可点击编号纠正");handler.postDelayed(()->targetThenAct(token,step),200);return;}
        int configured=settings.targets.getOrDefault(step.color,0),destination=!taunt.active()&&configured>0?enemies.resolve(configured):0;
        // 暂停继续须补完被中断的等待；按需模式只补开大或切敌前的等待。
        // 等待完成后重新复核目标，不能用等待前的旧编号派发。
        if(motionEnabled()&&!pendingMotionAction.isEmpty()&&(!settings.selectiveMotionWait||pendingMotionAction.equals("↑")||destination>0&&destination!=enemies.current())){
            waitForActionMotion(token,0,"切敌/继续前，上一动作="+pendingMotionAction,()->reviewEnemyThenAct(token,step));return;
        }
        if(destination>0&&destination!=enemies.current()){
            int direction=enemies.route(destination)>0?1:-1;
            int arrival=enemies.step(enemies.current(),direction);
            // 切敌横滑放在战斗画面上部；同时避开已固定在能量图标上方的浮窗。
            float swipeY=layout.y(.32f);Rect overlay=gestures.overlay();
            if(overlay!=null&&overlay.right>layout.x(.26f)&&overlay.left<layout.x(.74f))
                swipeY=Math.min(swipeY,overlay.top-Math.max(20,layout.viewport.height()*.035f));
            PointF from=new PointF(layout.x(.5f),Math.max(layout.y(.20f),swipeY)),to=new PointF(layout.x(direction>0?.26f:.74f),from.y);
            dispatch(token,from,to,350,arrival,()->{
                scheduleTarget(token,step);
            });return;
        }
        act(token,step);
    }
    private void act(int token,RecorderFollowTable.Step step){
        if(!valid(token))return;
        // 卡牌复用本回合位置，圈复用本局位置；帧差只决定等待时间，不改变操作坐标。
        PointF from=step.action.equals("圈")?circles[step.column]:layout.portrait(step.column),to=new PointF(from.x,from.y);
        // 从卡牌中部滑过半张卡的高度，避免短滑动被游戏当成普攻；按卡牌尺寸适配分辨率。
        if(step.action.equals("↑"))to.y-=layout.cards.height()*.50f;
        if(step.action.equals("↓"))to.y+=layout.cards.height()*.50f;
        String operation="跟打 "+round+"回合 第"+step.order+"步/"+(step.column+1)+"号位"+step.action;
        // 仅记录少量步骤事件，用于区分已派发手势、系统完成回调与游戏真正响应，保持原执行节奏。
        listener.trace(operation+" 派发 ("+Math.round(from.x)+","+Math.round(from.y)+")→("+Math.round(to.x)+","+Math.round(to.y)+")，前/后延迟="+settings.delay(step.action,true)+"/"+settings.delay(step.action,false)+"ms，等待策略="+(settings.selectiveMotionWait?"按需等待":"每步等待")+"，追加上限="+settings.motionExtraWait+"ms");
        final boolean owned=fazheng.owns(step);final int epoch=fazheng.epoch;
        liveGesture=table.live&&(!owned||step==fazheng.anchor)?step:null;
        // 系统已发送的拉取无法被“暂停”撤销。即使旧会话已结束，也记录成功回调，恢复时先检测而不重拉。
        Runnable acknowledge=owned?()->{
            if(fazheng.epoch!=epoch||!fazheng.owns(step))return;
            boolean consume=step==fazheng.anchor;fazheng.sent(step);
            if(fazheng.pendingCheck)fazheng.readyAt=SystemClock.uptimeMillis()+Math.max(900,settings.delay(step.action,false));
            if(consume){if(table.live)liveProgress.completed(step);if(cursor<queue.size()&&queue.get(cursor)==step)cursor++;}
        }:null;
        dispatch(token,from,to,step.action.equals("A")||step.action.equals("圈")?90:350,0,acknowledge,()->{
            listener.trace(operation+" 系统手势回调完成");
            roundCheckPending=true;
            // 手势成功即记为已操作；后置延迟和动画等待只控制下一次派发，暂停不能重做这一步。
            // 自动补拉及成功动作不消费原表下一步；第一次下拉才推进原动作游标。
            if(!owned){if(fazhengEnabled(step))fazheng.sent(step);if(table.live)liveProgress.completed(step);cursor++;}
            int delay=settings.delay(step.action,false);pendingMotionAction=step.action;
            // 圣物补拉必须等入栈动画落定，零延迟也不能连续读到上一堆物品就提前重开。
            if(owned&&!fazheng.finished)delay=Math.max(900,delay);
            // 取消按需模式后，A/上滑/下滑/圈（含末步）全部等待；勾选时按实际目标分类。
            String waitReason=!settings.selectiveMotionWait?"每步等待":step.action.equals("↑")?"开大后":cursor<queue.size()&&needsTargetChange(queue.get(cursor))?"下一步需切敌":"";
            if(motionEnabled()&&!waitReason.isEmpty()){
                waitForActionMotion(token,delay,operation+"；"+waitReason,()->next(token));return;
            }
            // 卡牌动作没有改变敌方编号；保留刚看到的缺失，下一动作无需从零确认。
            final int postDelay=delay,observation=++enemyWindow;boolean detect=detectEnemies();
            listener.trace(operation+" 不追加动画等待："+(motionEnabled()?"按需等待：A/↓/圈当前衔接无需切敌":"追加上限为0"));
            if(detect)handler.postDelayed(()->watchEnemyDelay(token,observation),RecorderActionMotion.INTERVAL_MS);
            handler.postDelayed(()->{if(valid(token)){listener.trace(operation+" 固定延迟结束="+postDelay+"ms");next(token);}},postDelay);
        });
    }
    private boolean fazhengEnabled(RecorderFollowTable.Step step){return conditions.fazheng.rounds.contains(round)&&step.column==RecorderFazhengCondition.column(table);}
    /** 检查挂起状态在 stop 后保留，恢复时不会为了重新识别而再下拉一次。 */
    private void checkFazheng(int token){
        if(!valid(token))return;
        long wait=fazheng.readyAt-SystemClock.uptimeMillis();if(wait>0){handler.postDelayed(()->checkFazheng(token),wait);return;}
        listener.status("法正圣物检测：第 "+fazheng.pulls+" 次下拉");
        fazhengCheck.start(fazheng.pulls,RecorderFazhengCondition.column(table),layout,callback->capture(token,callback),source::overlayBounds,()->valid(token)&&!replaying,colors->{
            if(!valid(token))return;
            RecorderFazhengCondition.Decision decision=conditions.fazheng.decideItems(fazheng.pulls,colors);
            listener.trace("法正圣物：下拉="+fazheng.pulls+"，物品从上到下="+RecorderFazhengRelics.names(colors)+"，判定="+decision);
            if(decision==RecorderFazhengCondition.Decision.RESTART){exitForCondition(token,"法正第 "+fazheng.pulls+" 次下拉剩余次数已无法凑齐目标圣物");return;}
            if(decision==RecorderFazhengCondition.Decision.MATCH){
                String action=conditions.fazheng.action;fazheng.matched(action);
                if(action.equals("stop")){fail("法正已命中目标圣物，按设置停止跟打");return;}
                if(action.equals("↓")&&fazheng.pulls>=5)listener.trace("第 5 次已达成圣物，跳过额外下拉以免超过每回合上限");
                next(token);return;
            }
            if(decision==RecorderFazhengCondition.Decision.CONTINUE){fazheng.pendingCheck=false;fazheng.repeat();next(token);return;}
            restartAfterFazhengRead(token,"法正物品状态不明");
        },reason->restartAfterFazhengRead(token,reason));
    }
    /** 圣物读取失败等同点“重新抄”再开始；命中停止和用户暂停仍保留原行为。 */
    private void restartAfterFazhengRead(int token,String reason){
        if(!valid(token)||replaying)return;
        listener.trace("法正圣物检测未完成，自动重新抄："+reason);
        reset();
        listener.status("已自动重新抄，按当前回合首个行动继续");
        start();
    }
    private boolean motionEnabled(){return settings.motionExtraWait>0;}
    private boolean needsTargetChange(RecorderFollowTable.Step step){
        if(taunt.active())return false; // 游戏强制锁敌，颜色变化不能触发无效横滑或改写返回点。
        int configured=settings.targets.getOrDefault(step.color,0),destination=configured>0?enemies.resolve(configured):0;
        return destination>0&&destination!=enemies.current();
    }
    /** 必须等待时沿用原帧差、后置最短延迟及追加上限，切多格只等待一次。 */
    private void waitForActionMotion(int token,int delay,String operation,Runnable complete){
        final int observation=++enemyWindow;boolean detect=detectEnemies(),shared=detect||loop!=null&&loop.enabled();
        listener.trace(operation+"，开始动画等待");
        if(detect&&RecorderMotionWaiter.firstSampleDelay(delay)>0)captureWaitFrame(token,observation,new RuYuanCaptureService.GameScreenshotCallback(){public void onSuccess(Bitmap frame){frame.recycle();}public void onFailure(String reason){}});
        motionWaiter.start(delay,settings.motionExtraWait,callback->{if(shared)captureWaitFrame(token,observation,callback);else capture(token,1,0,callback);},shared?source::battleOverlayBounds:source::motionOverlayBounds,
            ()->valid(token)&&!replaying,listener::status,reason->listener.trace(operation+" "+reason),()->{pendingMotionAction="";complete.run();});
    }
    private void dispatch(int token,PointF from,PointF to,int duration,int arrival,Runnable done){dispatch(token,from,to,duration,arrival,null,done);}
    private void dispatch(int token,PointF from,PointF to,int duration,int arrival,Runnable acknowledge,Runnable done){
        if(!windowReady(token,()->dispatch(token,from,to,duration,arrival,acknowledge,done)))return;
        Rect overlay=gestures.overlay();RectF path=new RectF(Math.min(from.x,to.x)-8,Math.min(from.y,to.y)-8,Math.max(from.x,to.x)+8,Math.max(from.y,to.y)+8);
        if(overlay!=null&&RectF.intersects(path,new RectF(overlay))){listener.trace("跟打手势被浮窗遮挡，未派发");fail("悬浮窗挡住操作位置，请移动后继续");return;}
        // 真正派发手势才废弃旧截图。横向切敌改变投影，清空连续证据但保留前后对照基线。
        enemyView++;enemyWindow++;if(arrival>0)enemyObserver.interrupt();
        inFlight=true;enemies.beginGesture(arrival);
        if(acknowledge!=null)fazhengGesturePending=true;boolean[] settled={false};
        try{gestures.send(from,to,duration,ok->{
            if(settled[0])return;settled[0]=true;
            if(acknowledge!=null){fazhengGesturePending=false;if(ok)acknowledge.run();}
            if(!valid(token))return;inFlight=false;liveGesture=null;enemies.finishGesture(ok);listener.enemiesChanged();
            if(!ok){listener.trace("跟打系统手势取消/拒绝");fail("手势中断，已保留当前操作，可重新勾选跟打或点选动作继续");return;}
            if(pendingVictory!=null){beginLoop(token);return;}
            done.run();
            applyLive();
        });}catch(Exception e){if(acknowledge!=null)fazhengGesturePending=false;inFlight=false;fail("系统未执行手势，跟打停止");}
    }
}
