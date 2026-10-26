package com.local.yuanatlas;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** 按已校验的视频布局执行自动采集；切换应用、停止使迟到回调失效。 */
public final class RuYuanCaptureService extends FollowAccessibilityService {
    static volatile RuYuanCaptureService instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    // 截图像素转换在单一后台线程中执行，避免每 400 ms 阻塞主线程或同时分配多份大图。
    private static final java.util.concurrent.ExecutorService screenshotWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"yuan-screenshot"));
    private final java.util.concurrent.atomic.AtomicBoolean screenshotPending=new java.util.concurrent.atomic.AtomicBoolean();
    private final RecorderFrameSampler frameSampler=new RecorderFrameSampler();
    private volatile boolean destroyed;
    private long lastWindowError;
    private static final String GAME_WINDOW_UNAVAILABLE="未找到所选游戏窗口，请回到游戏后继续";
    private LinearLayout panel,menu;
    private ImageButton menuIcon,pauseIcon;
    private boolean menuExpanded=true;
    private TextView label,selector;
    private WindowManager windows;
    private WindowManager.LayoutParams panelPosition;
    private RecorderRoundOverlay recorderRounds;
    private final RecorderRunForeground recorderForeground=new RecorderRunForeground(this);
    private float dragStartX,dragStartY;
    private int dragWindowX,dragWindowY,dragPointer=-1;
    private boolean iconDragging;
    private String target="",previousText="";
    private volatile boolean active;
    private boolean busy;
    private int generation;
    private RuYuanAutoCapture automatic;
    private StoneCaptureSession stoneSession;
    private TeamCaptureSession teamSession;
    private boolean teams(){return "team-v1".equals(autoConfig.optString("mode"));}
    boolean teamRunning(){return active&&teams();}
    private boolean stones(){return "stones-v2".equals(autoConfig.optString("mode"));}
    boolean stoneRunning(){return active&&stones();}
    private boolean automaticDone(){return teams()?teamSession==null||teamSession.done():stones()?stoneSession==null||stoneSession.done():automatic==null||automatic.done();}
    private JSONObject autoConfig=new JSONObject(),pendingConfig=new JSONObject();
    private boolean autoMode,paused;
    private long gestureEnd;
    private int windowEventToken=-1;
    private final CaptureWindowGuard autoWindow=new CaptureWindowGuard(new CaptureWindowGuard.Host(){
        public boolean valid(int token){return active&&autoMode&&!paused&&token==generation;}
        public String target(){return target;}
        public String focused(){return focusedApplicationPackage();}
        public long now(){return android.os.SystemClock.uptimeMillis();}
        public void later(Runnable work,long delay){handler.postDelayed(work,delay);}
        public void pause(String reason){pauseAuto(reason);}
    });
    private double stoneWidth,stoneHeight,stoneLeft,stoneTop;
    @Override public void onCreate(){super.onCreate();CaptureDiagnostics.install(this);}
    @Override protected void onServiceConnected(){super.onServiceConnected();windows=(WindowManager)getSystemService(WINDOW_SERVICE);/* 完成窗口初始化后才向其他线程公布可用实例。 */instance=this;CaptureDiagnostics.event(this,"无障碍服务已连接");}
    @Override boolean running(){return active;}
    @Override boolean isRecorderGame(String name){return RuYuanCapture.isRecorderGame(this,name);}
    void showRecorderRounds(java.util.List<RecorderRoundImage.Row> rows){
        closeRecorderRounds();
        recorderRounds=new RecorderRoundOverlay(this,rows);
        recorderRounds.show();
    }
    private RecorderLanBridge recorderLiveOwner;
    boolean ownsRecorderLive(RecorderLanBridge owner){return recorderLiveOwner==owner;}
    void showRecorderLive(RecorderFollowTable table,RecorderLanBridge owner){showRecorderFollow(table);recorderLiveOwner=owner;recorderForeground.setLive(true);}
    void updateRecorderLive(RecorderFollowTable table){if(recorderLiveOwner!=null&&recorderRounds!=null)recorderRounds.updateLive(table);}
    void recorderLiveAvailable(boolean available,String reason){if(recorderLiveOwner!=null&&recorderRounds!=null)recorderRounds.liveAvailable(available,reason);}
    void closeRecorderRounds(){
        RecorderLanBridge owner=recorderLiveOwner;recorderLiveOwner=null;
        RecorderRoundOverlay current=recorderRounds;recorderRounds=null;if(current!=null)current.close();
        recorderForeground.setLive(false);if(owner!=null)owner.overlayClosed();
    }
    void recorderRunState(boolean running){recorderForeground.setRunning(running);}
    boolean recorderForegroundActive(){return recorderForeground.active();}
    void pauseRecorderFromNotification(){
        CaptureDiagnostics.event(this,"用户从运行通知暂停跟打");
        if(recorderRounds!=null)recorderRounds.pauseFromNotification();
        recorderRunState(false);
    }
    void showRecorderFollow(RecorderFollowTable table){showRecorderFollow(table,null);}
    void showRecorderFollow(RecorderFollowTable table,Runnable returnToRecorder){
        closeRecorderRounds();recorderRounds=new RecorderRoundOverlay(this,table);
        recorderRounds.show();
    }
    void updateRecorderTheme(RecorderOverlayTheme theme){if(recorderRounds!=null)recorderRounds.applyTheme(theme);}
    void begin(String packageName){
        closeRecorderRounds();
        JSONObject config=pendingConfig;pendingConfig=new JSONObject();end(false);autoConfig=config;target=packageName;active=true;previousText="";generation++;
        // 根窗口随内容包裹，收起时只保留图标的触摸范围，不遮挡下方游戏。
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.HORIZONTAL);panel.setGravity(Gravity.CENTER_VERTICAL);panel.setPadding(dp(6),dp(6),dp(6),dp(6));
        menuIcon=new ImageButton(this);menuIcon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));menuIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);menuIcon.setPadding(0,0,0,0);
        GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(0xfffff9ed);circle.setStroke(dp(1),0xffb6a784);menuIcon.setBackground(circle);menuIcon.setOutlineProvider(ViewOutlineProvider.BACKGROUND);menuIcon.setClipToOutline(true);menuIcon.setElevation(dp(4));
        panel.addView(menuIcon,new LinearLayout.LayoutParams(dp(44),dp(44)));
        // 所有采集模式共用单行浮窗；长提示横向滚动，不挤出第二行。
        menu=new LinearLayout(this);menu.setOrientation(LinearLayout.HORIZONTAL);menu.setGravity(Gravity.CENTER_VERTICAL);menu.setPadding(dp(4),0,dp(4),0);menu.setBackground(surface(0xfafff9ed,14));menu.setElevation(dp(3));
        LinearLayout.LayoutParams menuLayout=new LinearLayout.LayoutParams(0,dp(44),1);menuLayout.leftMargin=dp(4);panel.addView(menu,menuLayout);
        label=new TextView(this);label.setTextColor(0xff64584a);label.setTextSize(12);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);label.setMarqueeRepeatLimit(-1);label.setSelected(true);menu.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        menuExpanded=true;menuIcon.setContentDescription("收起采集功能");menuIcon.setOnClickListener(v->{menuExpanded=!menuExpanded;menu.setVisibility(menuExpanded?View.VISIBLE:View.GONE);menuIcon.setContentDescription(menuExpanded?"收起采集功能":"展开采集功能");});
        automatic=null;stoneSession=null;teamSession=null;autoMode=false;paused=false;
        // 播放按钮共用开始和恢复入口，运行中切换为暂停；结束始终使用独立停止图标。
        pauseIcon=iconButton(android.R.drawable.ic_media_play,"开始识别",()->{if(automaticDone()){startAutomatic();return;}if(paused){try{if(stoneSession!=null){StoneGameCapture.append(this,stoneSession);stoneSession.resume();}paused=false;autoMode=true;movePanelToTop();runAuto(RuYuanAutoCapture.Action.read("恢复当前步骤"));}catch(Exception e){status("草稿保存失败，请释放空间后继续");}}else pauseAuto("已暂停，点击播放图标继续");});
        iconButton(R.drawable.capture_stop,"结束并查看结果",()->end(true));panelPosition=params(-2,-2);panelPosition.gravity=Gravity.TOP|Gravity.LEFT;
        android.content.SharedPreferences position=getSharedPreferences("capture-overlay",MODE_PRIVATE);
        panelPosition.x=position.getInt("x",0);panelPosition.y=position.getInt("y",60);windows.addView(panel,panelPosition);
        // 仅图标负责拖动；超过系统触摸阈值后不再触发点击，菜单按钮保持原操作。
        menuIcon.setOnTouchListener(this::dragIcon);
        panel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->clampPanel());
        panel.post(this::clampPanel);
        status(teams()?"打开当前队伍第一名密探立绘页，点播放后自动录入5人":stones()?"进入星石背包后点播放。仅采集橙色主辅星；结束后核对保存":"进入要录入的密探立绘页，点击播放；之后自动采集全部栏目");
    }
    private boolean dragIcon(View view,MotionEvent event){
        if(panel==null||panelPosition==null)return false;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){dragPointer=event.getPointerId(0);dragStartX=event.getRawX();dragStartY=event.getRawY();dragWindowX=panelPosition.x;dragWindowY=panelPosition.y;iconDragging=false;view.setPressed(true);return true;}
        if(dragPointer<0)return true;
        int index=event.findPointerIndex(dragPointer);
        if(action==MotionEvent.ACTION_MOVE&&index>=0){
            float dx=event.getRawX()+event.getX(index)-event.getX()-dragStartX,dy=event.getRawY()+event.getY(index)-event.getY()-dragStartY;
            int slop=ViewConfiguration.get(this).getScaledTouchSlop();
            if(!iconDragging&&dx*dx+dy*dy>slop*slop){iconDragging=true;view.setPressed(false);}
            if(iconDragging){panelPosition.x=dragWindowX+Math.round(dx);panelPosition.y=dragWindowY+Math.round(dy);clampPanel();windows.updateViewLayout(panel,panelPosition);}
        }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_POINTER_UP&&event.getPointerId(event.getActionIndex())==dragPointer){
            boolean click=action==MotionEvent.ACTION_UP&&!iconDragging;dragPointer=-1;view.setPressed(false);
            rememberPanel();if(click)view.performClick();
        }
        return true;
    }
    /** 展开菜单、屏幕旋转或屏幕尺寸变化后，把整个可见菜单限制在可用屏幕内。 */
    private void clampPanel(){
        if(panel==null||panelPosition==null||panel.getWidth()==0)return;
        int width,height;
        if(Build.VERSION.SDK_INT>=30){android.view.WindowMetrics metrics=windows.getCurrentWindowMetrics();android.graphics.Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());width=metrics.getBounds().width()-insets.left-insets.right;height=metrics.getBounds().height()-insets.top-insets.bottom;}
        else{android.graphics.Point size=new android.graphics.Point();windows.getDefaultDisplay().getSize(size);width=size.x;height=size.y;}
        int desiredWidth=menuExpanded?Math.min(width,dp(560)):dp(56);
        if(panelPosition.width!=desiredWidth){panelPosition.width=desiredWidth;windows.updateViewLayout(panel,panelPosition);}
        int x=Math.max(0,Math.min(panelPosition.x,Math.max(0,width-desiredWidth))),y=Math.max(0,Math.min(panelPosition.y,Math.max(0,height-panel.getHeight())));
        if(x!=panelPosition.x||y!=panelPosition.y){panelPosition.x=x;panelPosition.y=y;windows.updateViewLayout(panel,panelPosition);rememberPanel();}
    }
    private void rememberPanel(){if(panelPosition!=null)getSharedPreferences("capture-overlay",MODE_PRIVATE).edit().putInt("x",panelPosition.x).putInt("y",panelPosition.y).apply();}
    /** 识别开始或恢复时置顶并保存新位置，结束后不恢复拖动前的位置。 */
    private void movePanelToTop(){if(panel==null||panelPosition==null)return;panelPosition.x=0;panelPosition.y=0;windows.updateViewLayout(panel,panelPosition);rememberPanel();}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(panel!=null)panel.post(this::clampPanel);}
    void configure(JSONObject config){if(running())throw new IllegalStateException("已有采集任务运行");pendingConfig=config;}
    private void startAutomatic(){
        if(!active||busy||!automaticDone())return;
        if(!inGame()){status(stones()?"请先进入游戏星石背包":"请先进入所选游戏密探立绘资料页");return;}
        movePanelToTop();
        if(teams()){try{teamSession=new TeamCaptureSession(autoConfig);TeamGameCapture.checkpoint(this,teamSession.result);autoMode=true;paused=false;runAuto(RuYuanAutoCapture.Action.read("读取当前队伍第1名密探"));}catch(Exception e){status("无法创建配队采集："+e.getMessage());}return;}
        try{if(stones()){stoneSession=new StoneCaptureSession(autoConfig);StoneGameCapture.checkpoint(this,stoneSession.result);autoMode=true;paused=false;runAuto(RuYuanAutoCapture.Action.read("识别星石背包"));return;}automatic=new RuYuanAutoCapture(autoConfig);autoMode=true;paused=false;RuYuanCapture.checkpoint(this,automatic.result);runAuto(RuYuanAutoCapture.Action.read("识别密探基础资料"));}
        catch(Exception e){status("无法创建录入任务："+e.getMessage());}
    }
    private void runAuto(RuYuanAutoCapture.Action action){
        if(!active||!autoMode||paused)return;busy=true;final int token=++generation;status(action.label);
        handler.postDelayed(()->autoWindow.run(token,()->{
            if("read".equals(action.kind)){autoScreenshot(token);return;}
            double viewWidth=teams()?teamSession.width:stones()?stoneWidth:automatic.width,viewTop=teams()?teamSession.top:stones()?stoneTop:automatic.top,viewHeight=teams()?teamSession.contentHeight:stones()?stoneHeight:automatic.contentHeight;
            double viewLeft=teams()?teamSession.left:stones()?stoneLeft:automatic.left;
            float x=(float)(viewLeft+viewWidth*action.x),y=(float)(viewTop+viewHeight*action.y);
            Path path=new Path();path.moveTo(x,y);if("swipe".equals(action.kind))path.lineTo(x,(float)(viewTop+viewHeight*action.toY));
            int duration="hold".equals(action.kind)?3000:"swipe".equals(action.kind)?(stones()?1000:550):90;
            GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build();
            dispatchAutoGesture(token,action,gesture,duration);
        }),350);
        handler.postDelayed(()->{if(token==generation&&busy)pauseAuto("本步识别超时，已保留进度");},100000);
    }
    /** 窗口恢复只重试尚未发送的手势；已经完成的点击只接续截图，不重复点击。 */
    private void dispatchAutoGesture(int token,RuYuanAutoCapture.Action action,GestureDescription gesture,int duration){
        autoWindow.run(token,()->{
            final View gesturePanel=panel;if(teams())gesturePassthrough(gesturePanel,true);
            Runnable send=()->{
                if(token!=generation||!active||paused){gesturePassthrough(gesturePanel,false);return;}
                // 透传生效前窗口也可能变化；等待时先恢复按钮触摸，用户仍能暂停或结束。
                if(!inGame()){
                    gesturePassthrough(gesturePanel,false);
                    autoWindow.run(token,()->handler.postDelayed(()->dispatchAutoGesture(token,action,gesture,duration),CaptureWindowGuard.RETRY_MS));return;
                }
                gestureEnd=android.os.SystemClock.uptimeMillis()+duration;
                try{
                    boolean sent=dispatchGesture(gesture,new GestureResultCallback(){
                        @Override public void onCompleted(GestureDescription g){gesturePassthrough(gesturePanel,false);if(token==generation&&!"hold".equals(action.kind))handler.postDelayed(()->autoScreenshot(token),stones()?1000:700);}
                        @Override public void onCancelled(GestureDescription g){gesturePassthrough(gesturePanel,false);if(token==generation)pauseAuto("手势被中断，保留进度；恢复游戏页面后可继续");}
                    },handler);
                    if(!sent){gesturePassthrough(gesturePanel,false);pauseAuto("系统未执行自动手势，请检查无障碍服务");}
                    else if("hold".equals(action.kind))handler.postDelayed(()->autoScreenshot(token),1400);
                }catch(RuntimeException error){gesturePassthrough(gesturePanel,false);pauseAuto("系统未执行自动手势，已保留进度");}
            };
            // WindowManager 的触摸区域更新异步生效，等窗口更新后再注入触摸。
            if(teams())handler.postDelayed(send,120);else send.run();
        });
    }
    private void gesturePassthrough(View expected,boolean enabled){
        if(panel==null||panel!=expected||panelPosition==null)return;
        int flags=enabled?panelPosition.flags|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE:panelPosition.flags&~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if(flags!=panelPosition.flags){panelPosition.flags=flags;windows.updateViewLayout(panel,panelPosition);}
    }
    private void autoScreenshot(int token){
        autoWindow.run(token,()->autoScreenshotReady(token));
    }
    private void autoScreenshotReady(int token){
        // Android 14+ 已用窗口截图排除浮窗；旧系统按截图时真实浮窗范围排除 OCR 文本。
        final Rect ignoredOverlay=ocrOverlayBounds();
        captureGameScreenshot(new GameScreenshotCallback(){
            @Override public void onFailure(String error){
                if(token!=generation||!active||paused)return;
                // 截图入口再次查询窗口，若两次查询间瞬断，则等待后重新截图。
                if(GAME_WINDOW_UNAVAILABLE.equals(error)||!inGame())autoWindow.run(token,()->handler.postDelayed(()->autoScreenshot(token),CaptureWindowGuard.RETRY_MS));else pauseAuto(error);
            }
            @Override public void onSuccess(Bitmap bitmap){
                if(token!=generation||!active||paused){bitmap.recycle();return;}
                // 丢弃窗口不明时返回的旧帧；恢复后重读当前步骤，避免 busy 一直等到超时。
                if(!inGame()){bitmap.recycle();autoWindow.run(token,()->handler.postDelayed(()->autoScreenshot(token),CaptureWindowGuard.RETRY_MS));return;}
                boolean identity=!teams()&&!stones()&&"main".equals(automatic.stage());
                handler.postDelayed(()->{if(token==generation&&panel!=null)panel.setVisibility(View.VISIBLE);},Math.max(0,gestureEnd-android.os.SystemClock.uptimeMillis()+100));
                new Thread(()->{
                    try{
                        if(stones()){
                            try(com.google.mlkit.vision.text.TextRecognizer recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())){
                                StoneCaptureFrame frame=StoneCaptureFrame.read(bitmap,recognizer,ignoredOverlay,stoneSession==null?null:stoneSession.layoutReference());
                                handler.post(()->acceptStone(token,frame));
                            }
                            return;
                        }
                        JSONObject page=teams()?TeamCaptureVision.read(bitmap,ignoredOverlay,teamSession.stage(),autoConfig.getJSONArray("agents"),teamSession.currentName(),teamSession.codeYuan,teamSession.captureBounds(bitmap.getWidth(),bitmap.getHeight())):RuYuanVision.read(bitmap,identity,automatic.codeYuan,identity||"fate-home".equals(automatic.stage()),ignoredOverlay);
                        // 仅在立绘页保留原分辨率图片，技能对照截图仍使用轻量预览。
                        if(identity){ByteArrayOutputStream portrait=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,95,portrait);page.put("portrait","data:image/jpeg;base64,"+android.util.Base64.encodeToString(portrait.toByteArray(),android.util.Base64.NO_WRAP));}
                        float scale=Math.min(1,720f/bitmap.getWidth());Bitmap preview=Bitmap.createScaledBitmap(bitmap,Math.round(bitmap.getWidth()*scale),Math.round(bitmap.getHeight()*scale),true);
                        ByteArrayOutputStream jpeg=new ByteArrayOutputStream();preview.compress(Bitmap.CompressFormat.JPEG,82,jpeg);if(preview!=bitmap)preview.recycle();
                        page.put("preview","data:image/jpeg;base64,"+android.util.Base64.encodeToString(jpeg.toByteArray(),android.util.Base64.NO_WRAP));
                        handler.postDelayed(()->{if(teams())acceptTeam(token,page);else acceptAuto(token,page);},Math.max(0,gestureEnd-android.os.SystemClock.uptimeMillis()+150));
                    }catch(Exception|OutOfMemoryError e){handler.post(()->{
                        if(token!=generation||!active||paused)return;
                        if(stones()&&stoneSession!=null&&e instanceof StoneCaptureFrame.LayoutException){
                            try{runAuto(stoneSession.retryLayout((StoneCaptureFrame.LayoutException)e));return;}
                            catch(Exception repeated){pauseAuto(repeated.getMessage());return;}
                        }
                        pauseAuto(stones()&&e.getMessage()!=null?e.getMessage():"识别暂时失败，已保留进度；可继续或结束后补充");
                    });}
                    finally{bitmap.recycle();}
                },"ruyuan-auto-ocr").start();
            }
        });
    }
    // 使用插件的截图回调类型。
    /** 图鉴自动采集、手动补录与星石共用真实浮窗范围，不能按固定设备尺寸猜测。 */
    private Rect ocrOverlayBounds(){
        if(Build.VERSION.SDK_INT>=34||panel==null||!panel.isShown())return null;
        int[] location=new int[2];panel.getLocationOnScreen(location);
        return new Rect(location[0],location[1],location[0]+panel.getWidth(),location[1]+panel.getHeight());
    }
    /** 所有游戏识别共用截图入口：浮窗常驻，Android 14+ 只采集目标应用窗口。 */
    void captureGameScreenshot(GameScreenshotCallback callback){
        captureGameScreenshot(target,callback);
    }
    /** 只读回合识别传入当前游戏，不改变图鉴采集任务的目标或状态。 */
    void captureGameScreenshot(String packageName,GameScreenshotCallback callback){
        captureScreenshot(packageName,0,callback);
    }
    void captureMotionScreenshot(String packageName,GameScreenshotCallback callback){captureScreenshot(packageName,96,callback);}
    void captureBattleScreenshot(String packageName,GameScreenshotCallback callback){captureScreenshot(packageName,RecorderEnemyVision.WIDTH,callback);}
    /** 横屏平板的整屏缩图仍要保留竖屏游戏里的血条细线，缩放因子与浮窗范围共用。 */
    static int sampleFrameWidth(int requested,Rect display){
        return requested==RecorderEnemyVision.WIDTH?Math.round(requested*display.width()/Math.min(display.width(),display.height()*.45f)):requested;
    }
    /** 帧差用 96px、血条用 448px 等比小图；完整截图保持屏幕坐标，供回合识别及定位。 */
    private void captureScreenshot(String packageName,int sampleWidth,GameScreenshotCallback callback){
        if(Build.VERSION.SDK_INT<30){callback.onFailure("自动识别需要 Android 11 及以上");return;}
        GameWindow game=focusedWindow();
        if(game==null||!packageName.equals(game.name)){callback.onFailure(GAME_WINDOW_UNAVAILABLE);return;}
        if(destroyed){callback.onFailure("无障碍服务已断开");return;}
        Rect displayBounds=windows.getMaximumWindowMetrics().getBounds();
        int sampledWidth=sampleFrameWidth(sampleWidth,displayBounds);
        TakeScreenshotCallback capture=new TakeScreenshotCallback(){
            @Override public void onFailure(int error){screenshotPending.set(false);handler.post(()->{if(!destroyed)callback.onFailure("游戏窗口截屏失败（"+error+"），请回到游戏后继续");});}
            @Override public void onSuccess(ScreenshotResult result){
                Bitmap hardware=null,software=null,frame=null;
                try{
                    if(destroyed)return;
                    hardware=Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),result.getColorSpace());
                    if(hardware==null)throw new IllegalStateException("无法读取截图");
                    if(sampleWidth>0){
                        frame=frameSampler.sample(hardware,displayBounds,Build.VERSION.SDK_INT>=34?game.bounds:displayBounds,sampledWidth);
                    }else{
                    software=hardware.copy(Bitmap.Config.ARGB_8888,false);
                    if(Build.VERSION.SDK_INT>=34){
                        // 动画采样直接绘制到小图，不再先分配一张全屏填充图再缩小。
                        int width=sampleWidth>0?sampledWidth:displayBounds.width(),height=sampleWidth>0?Math.round(displayBounds.height()*sampledWidth/(float)displayBounds.width()):displayBounds.height();
                        frame=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                        Canvas canvas=new Canvas(frame);canvas.drawColor(Color.BLACK);
                        canvas.scale(width/(float)displayBounds.width(),height/(float)displayBounds.height());
                        canvas.drawBitmap(software,null,Build.VERSION.SDK_INT>=34?game.bounds:displayBounds,new Paint(Paint.FILTER_BITMAP_FLAG));
                    }else{frame=software;software=null;}
                    }
                }catch(Exception|OutOfMemoryError e){if(frame!=null)frame.recycle();frame=null;}
                finally{if(hardware!=null)hardware.recycle();if(software!=null)software.recycle();result.getHardwareBuffer().close();screenshotPending.set(false);}
                Bitmap ready=frame;
                handler.post(()->{
                    if(destroyed){if(ready!=null)ready.recycle();return;}
                    try{if(ready==null)callback.onFailure("无法读取游戏截图，已保留进度");else callback.onSuccess(ready);}
                    catch(RuntimeException|OutOfMemoryError error){
                        if(ready!=null&&!ready.isRecycled())ready.recycle();CaptureDiagnostics.recordCrash(RuYuanCaptureService.this,error);
                        CaptureDiagnostics.event(RuYuanCaptureService.this,"截图处理异常，暂停并保留浮窗");
                        if(active)pauseAuto("截图处理异常，进度已保留");if(recorderRounds!=null)recorderRounds.interrupt();
                    }
                });
            }
        };
        if(!screenshotPending.compareAndSet(false,true)){callback.onFailure("截图仍在处理，请稍后重试");return;}
        try{
            if(Build.VERSION.SDK_INT>=34)takeScreenshotOfWindow(game.id,screenshotWorker,capture);
            else takeScreenshot(Display.DEFAULT_DISPLAY,screenshotWorker,capture);
        }catch(RuntimeException error){screenshotPending.set(false);callback.onFailure("系统暂时无法截屏");}
    }
    private static final class GameWindow{
        final String name;final int id;final Rect bounds;
        GameWindow(String name,int id,Rect bounds){this.name=name;this.id=id;this.bounds=bounds;}
    }
    /** 复制需要的窗口信息后释放全部窗口对象，兼容仍使用对象池的旧 Android。 */
    private GameWindow focusedWindow(){
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> list=null;
        try{
            list=getWindows();for(var window:list){
                if(window.getType()!=android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION||!window.isFocused())continue;
                AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
                try{Rect bounds=new Rect();window.getBoundsInScreen(bounds);return new GameWindow(root.getPackageName()==null?"":root.getPackageName().toString(),window.getId(),bounds);}finally{root.recycle();}
            }
        }catch(RuntimeException error){
            long now=android.os.SystemClock.uptimeMillis();if(now-lastWindowError>30000){lastWindowError=now;CaptureDiagnostics.event(this,"读取系统窗口失败："+error.getClass().getSimpleName());}
        }finally{if(list!=null)for(var window:list)window.recycle();}return null;
    }
    String focusedApplicationPackage(){GameWindow window=focusedWindow();return window==null?"":window.name;}
    /** 原生状态机只返回动作，草稿持久化完成后才允许下一次手势。 */
    private void acceptStone(int token,StoneCaptureFrame frame){
        if(token!=generation||!active||paused)return;
        try{
            stoneWidth=frame.width;stoneHeight=frame.height;stoneLeft=frame.screenLeft;stoneTop=frame.screenTop;
            RuYuanAutoCapture.Action next=stoneSession.accept(frame);
            StoneGameCapture.append(this,stoneSession);StoneGameCapture.checkpoint(this,stoneSession.result);busy=false;
            if(next==null)end(true);else runAuto(next);
        }catch(Exception e){pauseAuto(e.getMessage()==null?"星石采集失败，草稿保留":e.getMessage());}
    }
    private void acceptAuto(int token,JSONObject page){
        if(token!=generation||!active||paused)return;
        try{
            RuYuanAutoCapture.Action next;
            next=automatic.accept(page);RuYuanCapture.checkpoint(this,automatic.result);busy=false;
            if(next==null){autoMode=false;status("自动采集结束，正在打开结果供补充");end(true);}
            else runAuto(next);
        }catch(Exception e){
            if(e instanceof RuYuanAutoCapture.LayoutException){
                // 仅记录定位状态和尺寸，不记录截图或密探正文；区分文字入口漏识别与真实边线失败。
                if("fate-grid".equals(automatic.stage()))CaptureDiagnostics.event(this,
                    "命盘定位 game="+automatic.result.optString("game")+" frame="+page.optInt("width")+"x"+page.optInt("height")
                    +" content="+page.optDouble("left")+","+page.optDouble("top")+","+page.optDouble("contentWidth",page.optDouble("width"))+","+page.optDouble("contentHeight")
                    +" detection="+page.optString("fateGridStatus","unknown")+" overlay="+page.optString("ocrOverlay","unknown"));
                try{runAuto(automatic.retryLayout((RuYuanAutoCapture.LayoutException)e));return;}
                catch(Exception repeated){pauseAuto(repeated.getMessage());return;}
            }
            pauseAuto(e.getMessage()==null?"页面无法定位，已保留进度":e.getMessage());
        }
    }
    private void acceptTeam(int token,JSONObject page){
        if(token!=generation||!active||paused||teamSession==null)return;
        try{RuYuanAutoCapture.Action next=teamSession.accept(page);TeamGameCapture.checkpoint(this,teamSession.result);busy=false;if(next==null)end(true);else runAuto(next);}
        catch(Exception e){
            if(e instanceof TeamCaptureSession.LayoutException){
                try{runAuto(teamSession.retryFrame((TeamCaptureSession.LayoutException)e));return;}
                catch(Exception repeated){pauseAuto(repeated.getMessage());return;}
            }
            pauseAuto(e.getMessage()==null?"配队识别失败，草稿已保留":e.getMessage());
        }
    }
    private void pauseAuto(String reason){generation++;busy=false;paused=true;gesturePassthrough(panel,false);CaptureDiagnostics.event(this,"自动采集暂停："+reason);status(reason);if(panel!=null)panel.setVisibility(View.VISIBLE);try{if(teamSession!=null){teamSession.result.put("phase","paused");TeamGameCapture.checkpoint(this,teamSession.result);}if(stoneSession!=null){stoneSession.result.put("phase","paused");StoneGameCapture.checkpoint(this,stoneSession.result);}if(automatic!=null){automatic.result.put("phase","paused");automatic.warning(reason);RuYuanCapture.checkpoint(this,automatic.result);}}catch(Exception ignored){}}
    private WindowManager.LayoutParams params(int w,int h){return new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable surface(int color,int radius){GradientDrawable background=new GradientDrawable();background.setColor(color);background.setCornerRadius(dp(radius));return background;}
    private ImageButton iconButton(int drawable,String description,Runnable action){
        ImageButton b=new ImageButton(this);b.setImageResource(drawable);b.setContentDescription(description);b.setColorFilter(0xff326b60);b.setBackground(surface(0xffedf3ee,9));b.setPadding(dp(10),dp(10),dp(10),dp(10));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(dp(44),dp(44));layout.leftMargin=dp(4);menu.addView(b,layout);b.setOnClickListener(v->action.run());return b;
    }
    private void status(String text){if(teamSession!=null)text=teamSession.identityLabel()+text;RuYuanCapture.message=text;if(label!=null){label.setText(text);label.setContentDescription(text);}if(pauseIcon!=null){boolean ready=automaticDone();pauseIcon.setImageResource(ready||paused?android.R.drawable.ic_media_play:android.R.drawable.ic_media_pause);pauseIcon.setContentDescription(ready?"开始识别":paused?"继续识别":"暂停识别");}}
    private boolean inGame(){
        // 浮窗被点击时会成为活动窗口；只检查有焦点的应用窗口，不把自己的浮窗当成切换游戏。
        return !target.isEmpty()&&target.equals(focusedApplicationPackage());
    }
    private void pick(boolean hold){
        if(!active||busy||selector!=null)return;if(!inGame()){status("当前不是选定的游戏页面");return;}
        selector=new TextView(this);selector.setText(hold?"点选要长按查看的命盘（10秒未选取消）":"点选要打开的技能（10秒未选取消）");selector.setTextColor(0xffffffff);selector.setBackgroundColor(0x22302010);selector.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);selector.setPadding(10,10,10,60);
        windows.addView(selector,params(-1,-1));final int token=generation;final TextView currentSelector=selector;
        selector.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_UP){float x=e.getRawX(),y=e.getRawY();removeSelector();Path path=new Path();path.moveTo(x,y);capture(path,hold?2200:80);}return true;});
        handler.postDelayed(()->{if(token==generation&&selector==currentSelector){removeSelector();status("未选择位置，已取消");}},10000);
    }
    private void removeSelector(){if(selector!=null){windows.removeView(selector);selector=null;}if(panel!=null)panel.setVisibility(View.VISIBLE);}
    private void capture(Path path,int duration){
        if(!active||busy||Build.VERSION.SDK_INT<30)return;if(!inGame()){status("已离开游戏，未执行操作");return;}
        if(RuYuanCapture.count(this)>=40){status("已达40页，请回App核对并清理");return;}movePanelToTop();busy=true;final int token=generation;
        if(path==null){handler.postDelayed(()->screenshot(token),350);return;}
        handler.postDelayed(()->{
            if(!active||token!=generation)return;if(!inGame()){fail(token,"已切换应用，取消手势");return;}
            GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build();
            boolean sent=dispatchGesture(g,new GestureResultCallback(){@Override public void onCompleted(GestureDescription gesture){if(duration<2000)handler.postDelayed(()->screenshot(token),650);}@Override public void onCancelled(GestureDescription gesture){fail(token,"手势被中断，请重试");}},handler);
            if(!sent)fail(token,"系统未执行手势，请手动操作后采集");
            // 命盘提示可能在松手后消失，因此在按住期间截屏。
            else if(duration>=2000)handler.postDelayed(()->screenshot(token),1100);
        },150);
    }
    private void screenshot(int token){
        if(!active||token!=generation)return;if(!inGame()){fail(token,"已离开游戏，停止采集");return;}
        final Rect ignoredOverlay=ocrOverlayBounds();
        captureGameScreenshot(new GameScreenshotCallback(){
            @Override public void onFailure(String error){fail(token,error);}
            @Override public void onSuccess(Bitmap copy){
                if(!active||token!=generation||!inGame()){copy.recycle();fail(token,"画面已切换，请重新采集");return;}
                status("正在离线识别…");panel.setVisibility(View.VISIBLE);
                new Thread(()->{
                    try(com.google.mlkit.vision.text.TextRecognizer r=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())){
                        // 手动补录同样不收起浮窗；只在离线OCR副本排除其文字。
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy.compress(Bitmap.CompressFormat.PNG,100,bytes);
                        JSONObject page=StoneOcr.recognize(new ByteArrayInputStream(bytes.toByteArray()),r,null,ignoredOverlay);
                        ByteArrayOutputStream preview=new ByteArrayOutputStream();float scale=Math.min(1,640f/copy.getWidth());Bitmap small=Bitmap.createScaledBitmap(copy,Math.round(copy.getWidth()*scale),Math.round(copy.getHeight()*scale),true);
                        try{small.compress(Bitmap.CompressFormat.JPEG,80,preview);}finally{if(small!=copy)small.recycle();}
                        page.put("preview","data:image/jpeg;base64,"+android.util.Base64.encodeToString(preview.toByteArray(),android.util.Base64.NO_WRAP));copy.recycle();handler.post(()->accept(token,page));
                    }
                    catch(Exception|OutOfMemoryError e){if(!copy.isRecycled())copy.recycle();handler.post(()->fail(token,"识别失败，之前的截图仍保留"));}
                },"yuan-catalog-capture").start();
            }
        });
    }
    private void accept(int token,JSONObject page){
        if(!active||token!=generation)return;
        try{org.json.JSONArray lines=page.getJSONArray("lines");StringBuilder text=new StringBuilder();for(int i=0;i<lines.length();i++)text.append(lines.getJSONObject(i).getString("text").replaceAll("\\s",""));String current=text.toString();
            if(current.isEmpty()){fail(token,"未识别到文字，请打开资料弹窗");return;}if(current.equals(previousText)){fail(token,"与上一页文字相同，未重复保存");return;}
            RuYuanCapture.append(this,page);previousText=current;busy=false;panel.setVisibility(View.VISIBLE);status("已保存 "+RuYuanCapture.count(this)+" 页，回App核对后导入");
        }catch(Exception e){fail(token,e.getMessage()==null?"保存失败":e.getMessage());}
    }
    private void fail(int token,String text){if(token!=generation||!active)return;generation++;busy=false;status(text);if(panel!=null)panel.setVisibility(View.VISIBLE);}
    void end(boolean back){
        if(teamSession!=null)try{if(!teamSession.done())teamSession.finish("采集提前结束，请核对已采集成员并补充缺项");TeamGameCapture.checkpoint(this,teamSession.result);}catch(Exception e){status("配队草稿保存失败，请检查存储空间");}
        if(stoneSession!=null)try{StoneGameCapture.append(this,stoneSession);if(!stoneSession.done())stoneSession.finish("采集提前结束，可能有漏项，请核对后保存");StoneGameCapture.checkpoint(this,stoneSession.result);}catch(Exception e){status("部分草稿保存失败，请核对已保存结果");}
        if(automatic!=null&&!automatic.done())try{automatic.finish("采集提前结束，已获取的内容保留，缺失部分可补充");RuYuanCapture.checkpoint(this,automatic.result);}catch(Exception ignored){}
        stoneSession=null;teamSession=null;generation++;active=false;busy=false;autoMode=false;handler.removeCallbacksAndMessages(null);removeSelector();if(panel!=null){windows.removeView(panel);panel=null;label=null;menu=null;menuIcon=null;pauseIcon=null;}
        if(back){status("采集结束，请查看并补充结果");startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));}
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        // 跟打浮窗独立于采集 active 状态，必须先处理系统面板窗口变化。
        if(recorderRounds!=null&&(event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED||event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))recorderRounds.refreshSystemUi();
        if(!active||event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return;
        if(autoMode&&!paused){
            // 页面/浮窗变化也会发窗口事件。只认实际焦点窗口；同一步仅保留一个复查任务。
            final int token=generation;if(windowEventToken==token)return;windowEventToken=token;
            autoWindow.run(token,()->{if(windowEventToken==token)windowEventToken=-1;});
        }else if(!autoMode&&!inGame()){generation++;busy=false;removeSelector();}
    }
    @Override public void onInterrupt(){
        CaptureDiagnostics.event(this,"收到系统中断，暂停动作并保留浮窗");
        if(active){pauseAuto("系统中断了自动操作，进度已保留");removeSelector();gesturePassthrough(panel,false);}
        if(recorderRounds!=null)recorderRounds.interrupt();
    }
    @Override public void onDestroy(){
        destroyed=true;CaptureDiagnostics.event(this,"无障碍服务断开");
        // 共享截图线程保留接收迟到回调的能力，回调会释放硬件缓冲；避免 shutdown 后丢失释放机会。
        try{end(false);closeRecorderRounds();}finally{recorderRunState(false);if(instance==this)instance=null;super.onDestroy();}
    }
}
