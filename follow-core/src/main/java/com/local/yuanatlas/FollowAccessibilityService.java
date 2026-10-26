package com.local.yuanatlas;
import android.accessibilityservice.AccessibilityService;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
/** 独立跟打服务；原 App 继承此类共享截图和执行生命周期。 */
public class FollowAccessibilityService extends AccessibilityService {
    static volatile FollowAccessibilityService instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService screenshotWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"yuan-follow-screenshot"));
    private final java.util.concurrent.atomic.AtomicBoolean screenshotPending=new java.util.concurrent.atomic.AtomicBoolean();
    private final RecorderFrameSampler frameSampler=new RecorderFrameSampler();
    private volatile boolean destroyed;
    private long lastWindowError;
    private static final String GAME_WINDOW_UNAVAILABLE="未找到所选游戏窗口，请回到游戏后继续";
    private WindowManager windows;
    protected RecorderRoundOverlay recorderRounds;
    protected final RecorderRunForeground recorderForeground=new RecorderRunForeground(this);
    public interface GameScreenshotCallback {void onSuccess(Bitmap bitmap);void onFailure(String error);}
    @Override public void onCreate(){super.onCreate();CaptureDiagnostics.install(this);}
    @Override protected void onServiceConnected(){super.onServiceConnected();windows=(WindowManager)getSystemService(WINDOW_SERVICE);destroyed=false;instance=this;YuanFollow.event("connected","");}
    boolean running(){return false;}
    boolean isRecorderGame(String name){return YuanFollow.isTarget(this,name);}
    void showRecorderRounds(java.util.List<RecorderRoundImage.Row> rows){closeRecorderRounds();recorderRounds=new RecorderRoundOverlay(this,rows);recorderRounds.show();}
    void showRecorderFollow(RecorderFollowTable table){closeRecorderRounds();recorderRounds=new RecorderRoundOverlay(this,table);recorderRounds.show();YuanFollow.event("loaded",table.tableName);}
    void closeRecorderRounds(){RecorderRoundOverlay current=recorderRounds;recorderRounds=null;try{if(current!=null)current.close();}finally{recorderForeground.setLive(false);recorderForeground.setRunning(false);if(current!=null)YuanFollow.event("closed","");}}
    void recorderRunState(boolean running){recorderForeground.setRunning(running);YuanFollow.event(running?"running":"paused","");}
    boolean recorderForegroundActive(){return recorderForeground.active();}
    void pauseRecorderFromNotification(){if(recorderRounds!=null)recorderRounds.pauseFromNotification();recorderRunState(false);}
    void updateRecorderTheme(RecorderOverlayTheme theme){if(recorderRounds!=null)recorderRounds.applyTheme(theme);}
    protected void screenshotProcessingFailed(){if(recorderRounds!=null)recorderRounds.interrupt();}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){if(event!=null&&recorderRounds!=null&&(event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED||event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))recorderRounds.refreshSystemUi();}
    @Override public void onInterrupt(){if(recorderRounds!=null)recorderRounds.interrupt();YuanFollow.event("interrupted","");}
    @Override public void onDestroy(){destroyed=true;try{closeRecorderRounds();}finally{if(instance==this)instance=null;YuanFollow.event("disconnected","");super.onDestroy();}}
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
                        if(ready!=null&&!ready.isRecycled())ready.recycle();CaptureDiagnostics.recordCrash(FollowAccessibilityService.this,error);
                        CaptureDiagnostics.event(FollowAccessibilityService.this,"截图处理异常，暂停并保留浮窗");
                        screenshotProcessingFailed();
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
}
