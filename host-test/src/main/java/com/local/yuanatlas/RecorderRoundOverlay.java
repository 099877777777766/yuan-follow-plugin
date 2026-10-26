package com.local.yuanatlas;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** 游戏上方的当前回合浮窗；回合数、操作和备注并排，窗口高度始终贴合这一行。 */
final class RecorderRoundOverlay {
    private final RuYuanCaptureService service;
    private final List<RecorderRoundImage.Row> rows;
    private RecorderFollowTable table;
    private Runnable returnToRecorder;
    // 返回动作独立于调整表操作；沿用服务关闭流程以停止手势、识别和循环。
    void setRecorderReturn(Runnable action){returnToRecorder=action;}
    private RecorderFollowRunner followRunner;
    private RecorderFollowSettings followSettings;
    private RecorderRoundConditions roundConditions;
    private RecorderLoopController loopController;
    private LinearLayout loopControls;
    private LinearLayout toolbar,autoLine;
    private TextView modeButton,roleName,handle,settingsButton;
    private TextView resumeButton;
    private RecorderFollowTable.Step selectedResumeStep;
    private int expandedResumeRow=-1,expandedResumeColumn=-1;
    private RecorderOverlayTheme theme;
    private final java.util.ArrayList<ImageButton> themedButtons=new java.util.ArrayList<>();
    private boolean followMode;
    private RecorderFollowTable.Step activeStep;
    private android.app.Dialog settingsDialog;
    private RecorderFollowEditSession editSession;
    private RecorderConditionSettingsView conditionsEditor;
    private final WindowManager windows;
    private WindowManager.LayoutParams position;
    private ResizablePanel panel;
    private FrameLayout contentViewport;
    private RoundPage currentPage;
    private ImageButton collapseButton,appIcon,resetButton,previousButton,nextButton;
    private boolean collapsed;
    private boolean systemUiCovered,recordingLoop;
    private final RecorderSystemUiGuard systemUiGuard;
    private int expandedWidth;
    private ValueAnimator transition;
    private ValueAnimator followTransition;
    private ValueAnimator anchorTransition;
    private float followAnchorBottom;
    private int restingX,restingY;
    private boolean followPlacement;
    private android.graphics.drawable.BitmapDrawable followToggleGhost;
    private boolean compactFollow;
    private float compactFraction;
    private final RecorderRoundAutoReader.FrameSource autoSource;
    private RecorderRoundAutoReader autoReader;
    private CheckBox autoToggle;
    private TextView autoStatus;
    private int pendingAutoIndex=-1;
    private int index,referenceContentWidth,labelWidth,operationWidth,noteWidth;
    private static final int MIN_WINDOW_DP=250,EDGE_DP=8;
    /** 四角保留拖拽热区，不绘制角标；面板使用统一的不透明背景。 */
    private static final class ResizablePanel extends LinearLayout {
        private final float density;
        private OnTouchListener resizeTouch;
        private int resizeCorner;
        private boolean resizeEnabled=true;
        ResizablePanel(android.content.Context context){
            super(context);
            density=context.getResources().getDisplayMetrics().density;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            // 收起时整个窗口就是图标，四角触摸也用于拖动或展开，不能触发缩放。
            if(!resizeEnabled){resizeCorner=0;return false;}
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                float hit=24*density,x=event.getX(),y=event.getY();
                int horizontal=x<hit?1:(x>getWidth()-hit?2:0);
                int vertical=y<hit?4:(y>getHeight()-hit?8:0);
                resizeCorner=horizontal!=0&&vertical!=0?horizontal|vertical:0;
            }
            return resizeCorner!=0||super.onInterceptTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(resizeCorner==0||resizeTouch==null)return super.onTouchEvent(event);
            boolean handled=resizeTouch.onTouch(this,event);
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)resizeCorner=0;
            return handled;
        }
    }
    /** 每页只有一行，切换时两行在同一个裁切区域内上下移动。 */
    private static final class RoundPage {
        final LinearLayout view;
        final int height;
        View header;int headerHeight;
        int renderedHeight;
        RoundPage(LinearLayout view,int height){
            this.view=view;this.height=height;
        }
    }
    RecorderRoundOverlay(RuYuanCaptureService service,List<RecorderRoundImage.Row> rows){
        this(service,rows,null);
    }
    RecorderRoundOverlay(RuYuanCaptureService service,RecorderFollowTable table){
        this(service,tableRows(table),null);this.table=table;followMode=table.allowAutoFollow;
    }
    // 结构化表沿用原悬浮窗导航与尺寸模型，内容用文字绘制，不生成密探立绘。
    private static List<RecorderRoundImage.Row> tableRows(RecorderFollowTable table){
        java.util.ArrayList<RecorderRoundImage.Row> rows=new java.util.ArrayList<>();
        for(RecorderFollowTable.Row row:table.rows)rows.add(new RecorderRoundImage.Row(row.label,Bitmap.createBitmap(5,1,Bitmap.Config.ARGB_8888),null));return rows;
    }
    RecorderRoundOverlay(RuYuanCaptureService service,List<RecorderRoundImage.Row> rows,RecorderRoundAutoReader.FrameSource frameSource){
        this.service=service;this.rows=rows;windows=(WindowManager)service.getSystemService(android.content.Context.WINDOW_SERVICE);
        systemUiGuard=new RecorderSystemUiGuard(service,this::setSystemUiCovered);
        theme=RecorderOverlayTheme.load(service);
        autoSource=frameSource!=null?frameSource:new RecorderRoundAutoReader.FrameSource(){
            @Override public String focusedGame(){String name=service.focusedApplicationPackage();return RuYuanCapture.isRecorderGame(service,name)?name:"";}
            @Override public void capture(String name,RuYuanCaptureService.GameScreenshotCallback callback){service.captureGameScreenshot(name,callback);}
            @Override public void captureMotion(String name,RuYuanCaptureService.GameScreenshotCallback callback){service.captureMotionScreenshot(name,callback);}
            @Override public void captureBattle(String name,RuYuanCaptureService.GameScreenshotCallback callback){service.captureBattleScreenshot(name,callback);}
            @Override public Rect screenBounds(){return windows.getMaximumWindowMetrics().getBounds();}
            @Override public Rect battleOverlayBounds(){
                Rect bounds=overlayBounds();if(bounds==null)return null;Rect display=windows.getMaximumWindowMetrics().getBounds();
                float scale=RuYuanCaptureService.sampleFrameWidth(RecorderEnemyVision.WIDTH,display)/(float)display.width();
                return new Rect((int)Math.floor(bounds.left*scale),(int)Math.floor(bounds.top*scale),(int)Math.ceil(bounds.right*scale),(int)Math.ceil(bounds.bottom*scale));
            }
            @Override public Rect motionOverlayBounds(){
                Rect bounds=overlayBounds();if(bounds==null)return null;
                Rect display=windows.getMaximumWindowMetrics().getBounds();
                return new Rect(Math.round(bounds.left*96f/display.width()),Math.round(bounds.top*96f/display.width()),Math.round(bounds.right*96f/display.width()),Math.round(bounds.bottom*96f/display.width()));
            }
            @Override public Rect overlayBounds(){
                if(Build.VERSION.SDK_INT>=34||panel==null)return null;
                int[] location=new int[2];panel.getLocationOnScreen(location);return new Rect(location[0],location[1],location[0]+panel.getWidth(),location[1]+panel.getHeight());
            }
        };
    }
    private int dp(int value){return Math.round(value*service.getResources().getDisplayMetrics().density);}
    private int screenWidth(){return Build.VERSION.SDK_INT>=30?windows.getMaximumWindowMetrics().getBounds().width():service.getResources().getDisplayMetrics().widthPixels;}
    private int screenHeight(){return Build.VERSION.SDK_INT>=30?windows.getMaximumWindowMetrics().getBounds().height():service.getResources().getDisplayMetrics().heightPixels;}
    private int maxWindowWidth(){return Math.max(1,screenWidth()-dp(EDGE_DP*2));}
    private int minWindowWidth(){return Math.min(dp(returnToRecorder==null?MIN_WINDOW_DP:292),maxWindowWidth());}
    private int paddingSize(){return dp(8)*2;}
    // 分别取整后相加，和真实控件高度一致，避免非整数屏幕密度留下 1～2 像素误差。
    private int fixedHeight(){return paddingSize()+dp(36)+dp(4)+controlRowHeight()*(table!=null&&followMode?2:1);}
    private int controlRowHeight(){return Math.round(dp(28)*(1-compactFraction))+Math.round(dp(4)*(1-compactFraction));}
    private int pageHeight(RoundPage page){return page.height-Math.round(page.headerHeight*compactFraction);}
    private GradientDrawable surface(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));d.setStroke(dp(1),theme.line);return d;}
    private TextView label(String text){TextView t=new TextView(service);t.setText(text);t.setTextColor(theme.ink);t.setTextSize(13);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private ImageButton button(int icon,String description,Runnable action){
        ImageButton b=new ImageButton(service);b.setImageResource(icon);b.setContentDescription(description);b.setTooltipText(description);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);b.setPadding(dp(9),dp(9),dp(9),dp(9));b.setBackground(surface(theme.bg));b.setColorFilter(theme.accent);b.setOnClickListener(v->action.run());themedButtons.add(b);return b;
    }
    private ImageView image(){ImageView v=new ImageView(service);v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setAdjustViewBounds(false);return v;}
    private GradientDrawable iconSurface(){GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(theme.surface);circle.setStroke(dp(1),theme.tableLine);return circle;}
    /** 只重绘外观，不重建执行器，不改变勾选、回合、当前步骤或已缩放的窗口位置。 */
    void applyTheme(RecorderOverlayTheme next){
        theme=next;if(panel==null)return;settleFollowTransition();settleTransition();
        if(!collapsed)panel.setBackground(surface(theme.surface));
        for(ImageButton button:themedButtons){button.setColorFilter(theme.accent);button.setBackground(surface(theme.bg));}
        handle.setTextColor(theme.ink);modeButton.setTextColor(theme.accent);modeButton.setBackground(surface(theme.soft));
        autoToggle.setTextColor(theme.ink);autoToggle.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));
        roleName.setTextColor(theme.muted);settingsButton.setTextColor(theme.accent);appIcon.setBackground(iconSurface());
        resumeButton.setTextColor(theme.on);resumeButton.setBackground(surface(theme.accent));
        if(loopController!=null)loopController.applyTheme(theme);
        rebuildPage();
        if(settingsDialog!=null&&settingsDialog.isShowing())styleSettings(settingsDialog.findViewById(android.R.id.content));
        if(conditionsEditor!=null)conditionsEditor.applyTheme();
    }
    // 按裁切原图的宽高比计算行高；极长行只缩小，不横向或纵向拉伸。
    private int scaledHeight(Bitmap bitmap,int width){
        int natural=Math.max(1,Math.round((float)width*bitmap.getHeight()/bitmap.getWidth()));
        return Math.min(service.getResources().getDisplayMetrics().heightPixels/4,natural);
    }
    void show(){
        int width=maxWindowWidth();
        referenceContentWidth=width-paddingSize();
        // 本 App 的操作区固定五列；备注列与操作列使用同一图像比例，并在空备注行保留列宽。
        int sourceOperations=rows.get(0).operations.getWidth(),sourceNote=0;
        for(RecorderRoundImage.Row row:rows)if(row.note!=null)sourceNote=Math.max(sourceNote,row.note.getWidth());
        float sourceLabel=sourceOperations/5f,total=sourceLabel+sourceOperations+sourceNote;
        labelWidth=Math.round(referenceContentWidth*sourceLabel/total);
        noteWidth=Math.round(referenceContentWidth*sourceNote/total);
        operationWidth=referenceContentWidth-labelWidth-noteWidth;
        if(table!=null){int inner=referenceContentWidth-dp(1)*2;labelWidth=inner/8;noteWidth=inner/4;operationWidth=inner-labelWidth-noteWidth;}
        panel=new ResizablePanel(service);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(8),dp(8),dp(8),dp(8));panel.setBackground(surface(theme.surface));
        // 左上角收起按钮独立可点，标题保留拖动区域；其他控制按钮仍固定在右上角。
        toolbar=new LinearLayout(service);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setClipChildren(false);toolbar.setClipToPadding(false);
        LinearLayout.LayoutParams toolbarParams=new LinearLayout.LayoutParams(-1,dp(36));toolbarParams.bottomMargin=dp(4);panel.addView(toolbar,toolbarParams);
        collapseButton=button(R.drawable.recorder_round_collapse,"收起为 App 图标",this::collapseToIcon);toolbar.addView(collapseButton,new LinearLayout.LayoutParams(dp(36),-1));
        if(returnToRecorder!=null){
            ImageButton back=button(android.R.drawable.ic_media_previous,"返回操作记录仪",()->{if(returnToRecorder!=null)returnToRecorder.run();});
            back.setTag("return-recorder");toolbar.addView(back,new LinearLayout.LayoutParams(dp(36),-1));
        }
        handle=label("操作回合");handle.setContentDescription("操作回合，按住拖动悬浮窗");handle.setSingleLine(true);handle.setEllipsize(android.text.TextUtils.TruncateAt.END);handle.setTextSize(12);handle.setTypeface(null,android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams handleParams=new LinearLayout.LayoutParams(0,-1,1);handleParams.leftMargin=dp(4);toolbar.addView(handle,handleParams);
        resetButton=button(R.drawable.recorder_round_reset,"重新抄，回到第一回合",()->{autoToggle.setChecked(false);settleFollowTransition();clearResumeSelection();if(followRunner!=null)followRunner.reset();activeStep=null;moveTo(0);refreshHighlight();});toolbar.addView(resetButton,new LinearLayout.LayoutParams(dp(36),-1));
        previousButton=button(R.drawable.recorder_round_previous,"上回合",()->moveTo(index-1));LinearLayout.LayoutParams previousParams=new LinearLayout.LayoutParams(dp(36),-1);previousParams.leftMargin=dp(4);toolbar.addView(previousButton,previousParams);
        nextButton=button(R.drawable.recorder_round_next,"下回合",()->moveTo(index+1));LinearLayout.LayoutParams nextParams=new LinearLayout.LayoutParams(dp(36),-1);nextParams.leftMargin=dp(4);toolbar.addView(nextButton,nextParams);
        ImageButton close=button(R.drawable.recorder_round_close,"关闭悬浮窗",service::closeRecorderRounds);LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(dp(36),-1);closeParams.leftMargin=dp(4);toolbar.addView(close,closeParams);
        // 自动识别默认关闭，勾选后只读游戏右上角；可随时取消并继续手动翻回合。
        autoLine=new LinearLayout(service);autoLine.setGravity(Gravity.CENTER_VERTICAL);autoLine.setClipChildren(false);autoLine.setClipToPadding(false);
        LinearLayout.LayoutParams autoParams=new LinearLayout.LayoutParams(-1,dp(28));autoParams.bottomMargin=dp(4);panel.addView(autoLine,autoParams);
        modeButton=label(followMode?"自动跟打 ▾":"手动抄表 ▾");modeButton.setTextSize(11);modeButton.setGravity(Gravity.CENTER);modeButton.setTextColor(theme.accent);modeButton.setBackground(surface(theme.soft));modeButton.setContentDescription("切换手动抄表、自动跟打");
        modeButton.setOnClickListener(v->{
            if(table==null||!table.allowAutoFollow){Toast.makeText(service,"该表不开放自动跟打",Toast.LENGTH_SHORT).show();return;}
            autoToggle.setChecked(false);settleFollowTransition();clearResumeSelection();followMode=!followMode;modeButton.setText(followMode?"自动跟打 ▾":"手动抄表 ▾");autoToggle.setText(followMode?"开始跟打":"自动识别回合数");
            if(loopControls!=null)loopControls.setVisibility(followMode?View.VISIBLE:View.GONE);
            rebuildPage();
        });autoLine.addView(modeButton,new LinearLayout.LayoutParams(dp(76),-1));
        autoToggle=new CheckBox(service);autoToggle.setText(followMode?"开始跟打":"自动识别回合数");autoToggle.setTextSize(11);autoToggle.setTextColor(theme.ink);
        autoToggle.setSingleLine(true);
        autoToggle.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));autoToggle.setPadding(0,0,0,0);autoToggle.setMinHeight(0);
        autoLine.addView(autoToggle,new LinearLayout.LayoutParams(-2,-1));
        autoStatus=label("");autoStatus.setVisibility(View.GONE);
        roleName=label(table==null?"":table.roleName);roleName.setTextColor(theme.muted);roleName.setTextSize(10);roleName.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);roleName.setSingleLine(true);roleName.setEllipsize(android.text.TextUtils.TruncateAt.END);roleName.setTooltipText(table==null?"":table.roleName);
        autoLine.addView(roleName,new LinearLayout.LayoutParams(0,-1,1));
        // 点选后在原设置行提供继续入口，不增加浮窗行数；窄窗暂时让出模式和作者区域。
        resumeButton=label("继续跟打");resumeButton.setTextSize(11);resumeButton.setGravity(Gravity.CENTER);resumeButton.setTextColor(theme.on);resumeButton.setBackground(surface(theme.accent));resumeButton.setVisibility(View.GONE);
        resumeButton.setOnClickListener(v->{if(selectedResumeStep!=null&&!autoToggle.isChecked())autoToggle.setChecked(true);});
        LinearLayout.LayoutParams resumeParams=new LinearLayout.LayoutParams(0,-1,1);resumeParams.leftMargin=dp(4);resumeParams.rightMargin=dp(4);autoLine.addView(resumeButton,resumeParams);
        settingsButton=label("⚙");settingsButton.setTextColor(theme.accent);settingsButton.setGravity(Gravity.CENTER);settingsButton.setTextSize(18);settingsButton.setContentDescription("跟打设置");settingsButton.setOnClickListener(v->openFollowSettings());
        // 纯图片手抄没有密探与动作数据，不能进入依赖这些数据的跟打设置。
        settingsButton.setVisibility(table==null?View.GONE:View.VISIBLE);
        autoLine.addView(settingsButton,new LinearLayout.LayoutParams(dp(28),-1));
        followSettings=RecorderFollowSettings.load(service,table);
        roundConditions=table==null?new RecorderRoundConditions():RecorderRoundConditions.load(service,table);
        if(table!=null&&(table.allowAutoFollow||table.live))followRunner=new RecorderFollowRunner(table,followSettings,autoSource,RecorderFollowRunner.nativeGestures(service,this::windowBounds),new RecorderFollowRunner.Listener(){
            public void round(int round){activeStep=null;for(int r=0;r<table.rows.size();r++)if(table.rows.get(r).round==round&&!table.rows.get(r).continuation){followRow(r);break;}}
            public void highlight(RecorderFollowTable.Step step){activeStep=step;followRow(step.row);}
            public void status(String text){setAutoStatus(text);}
            public void enemiesChanged(){refreshEnemies();}
            public void layout(RecorderFollowVision.Layout layout){setFollowAnchor(layout);}
            public void trace(String event){CaptureDiagnostics.event(service,event);}
            public void runningChanged(boolean running){service.recorderRunState(running);}
            public void intermission(boolean waiting){if(waiting)collapseToIcon();else expandFromIcon();}
            public void battleReset(){resetBattlePage();}
            public void planChanged(){if(contentViewport!=null){clearResumeSelection();activeStep=null;rebuildPage();}}
            public void stopped(String reason){autoToggle.setChecked(false);setAutoStatus(reason);Toast.makeText(service,reason,Toast.LENGTH_LONG).show();}
        });
        if(followRunner!=null){
            followRunner.setConditions(roundConditions);
            loopController=new RecorderLoopController(service,table,theme,autoSource::focusedGame,()->autoToggle.setChecked(false),
                ()->{followRunner.reset();clearResumeSelection();activeStep=null;settleFollowTransition();settleTransition();moveTo(0);refreshHighlight();},
                recording->{recordingLoop=recording;updatePanelVisibility();},this::macroPassthrough,this::windowBounds,this::setAutoStatus);
            followRunner.setLoop(loopController);loopControls=loopController.controls();loopControls.setVisibility(followMode?View.VISIBLE:View.GONE);
            LinearLayout.LayoutParams loopParams=new LinearLayout.LayoutParams(-1,dp(28));loopParams.bottomMargin=dp(4);panel.addView(loopControls,loopParams);
        }
        autoReader=new RecorderRoundAutoReader(autoSource,new RecorderRoundAutoReader.Listener(){
            @Override public void onRound(int round,boolean changed){acceptAutoRound(round,changed);}
            @Override public void onStatus(String text){setAutoStatus(text);}
        });
        autoToggle.setOnCheckedChangeListener((button,checked)->{
            pendingAutoIndex=-1;
            if(checked&&table!=null&&table.live&&!liveReady){autoToggle.setChecked(false);Toast.makeText(service,"等待有效的实时同步数据",Toast.LENGTH_SHORT).show();return;}
            if(checked&&Build.VERSION.SDK_INT<30){autoToggle.setChecked(false);Toast.makeText(service,"自动识别需要 Android 11 及以上",Toast.LENGTH_SHORT).show();return;}
            if(checked){
                closeResumeChoices();
                setAutoStatus("等待读取游戏回合");
                if(followMode&&followRunner!=null){
                    RecorderFollowTable.Step selected=selectedResumeStep;
                    if(selected!=null)followRunner.resumeFrom(selected);else followRunner.start();
                    if(followRunner.running()){
                        if(selected!=null)activeStep=selected;
                        selectedResumeStep=null;refreshHighlight();
                    }
                }else autoReader.start();
            }
            else{
                autoReader.stop();
                if(followRunner!=null){followRunner.stop();syncPausedFollowProgress();}
                setAutoStatus("");
            }
            // 移动同一个勾选框，保留监听器和状态；执行器同步拒绝启动时按最终勾选状态展开。
            setFollowCompact(followMode&&followRunner!=null&&autoToggle.isChecked(),true);
            updateButtons();
        });
        // 只有把手接管拖动，按钮和游戏内其余区域仍能正常点击。
        handle.setOnTouchListener(new View.OnTouchListener(){float startX,startY;int originX,originY;
            @Override public boolean onTouch(View v,MotionEvent e){if(position==null)return false;
                // 跟打时锁定面板位置；停止后恢复拖动，收成小图标时仍可自由移动。
                if(compactFollow||followTransition!=null)return true;
                if(e.getAction()==MotionEvent.ACTION_DOWN){startX=e.getRawX();startY=e.getRawY();originX=position.x;originY=position.y;return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){position.x=Math.max(dp(EDGE_DP),Math.min(screenWidth()-dp(EDGE_DP)-position.width,originX+Math.round(e.getRawX()-startX)));position.y=Math.max(dp(EDGE_DP),Math.min(screenHeight()-dp(EDGE_DP)-panel.getHeight(),originY+Math.round(e.getRawY()-startY)));windows.updateViewLayout(panel,position);return true;}
                return e.getAction()==MotionEvent.ACTION_UP;
            }});
        // 回合动画仅移动内容区域；工具栏位置固定，内容容器不产生滚动条。
        contentViewport=new FrameLayout(service);contentViewport.setClipChildren(true);contentViewport.setClipToPadding(true);
        contentViewport.setVerticalScrollBarEnabled(false);contentViewport.setHorizontalScrollBarEnabled(false);
        panel.addView(contentViewport,new LinearLayout.LayoutParams(-1,0));
        installAppIcon();
        position=new WindowManager.LayoutParams(width,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        // 与游戏截图/手势共用屏幕坐标；进入全屏、状态栏显隐或窗口移动时不再推算变化中的系统偏移。
        if(Build.VERSION.SDK_INT>=30){position.flags|=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;position.setFitInsetsTypes(0);position.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;}
        position.gravity=Gravity.TOP|Gravity.LEFT;position.x=dp(8);position.y=dp(65);
        installCornerResize();
        currentPage=createPage(0);contentViewport.addView(currentPage.view,new FrameLayout.LayoutParams(referenceContentWidth,pageHeight(currentPage)));
        resizeContent();
        updateButtons();windows.addView(panel,position);systemUiGuard.start();
    }
    private void installAppIcon(){
        appIcon=new ImageButton(service);appIcon.setImageDrawable(service.getApplicationInfo().loadIcon(service.getPackageManager()));
        appIcon.setContentDescription("展开悬浮窗抄表，按住可拖动");appIcon.setTooltipText("展开悬浮窗抄表");
        appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);appIcon.setPadding(dp(3),dp(3),dp(3),dp(3));
        appIcon.setBackground(iconSurface());appIcon.setClipToOutline(true);appIcon.setVisibility(View.GONE);appIcon.setOnClickListener(v->expandFromIcon());
        panel.addView(appIcon,new LinearLayout.LayoutParams(dp(48),dp(48)));
        appIcon.setOnTouchListener(new View.OnTouchListener(){
            float startX,startY;int originX,originY;boolean dragged;
            final int slop=ViewConfiguration.get(service).getScaledTouchSlop();
            @Override public boolean onTouch(View view,MotionEvent event){
                if(position==null||!collapsed)return false;
                switch(event.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        startX=event.getRawX();startY=event.getRawY();originX=position.x;originY=position.y;dragged=false;return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx=event.getRawX()-startX,dy=event.getRawY()-startY;
                        if(dx*dx+dy*dy>slop*slop)dragged=true;
                        if(dragged){
                            position.x=Math.max(dp(EDGE_DP),Math.min(screenWidth()-dp(EDGE_DP)-position.width,originX+Math.round(dx)));
                            position.y=Math.max(dp(EDGE_DP),Math.min(screenHeight()-dp(EDGE_DP)-position.height,originY+Math.round(dy)));
                            windows.updateViewLayout(panel,position);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        // 拖动结束不展开；只有点击才恢复面板，避免图标移动后突然挡住游戏。
                        if(!dragged)view.performClick();return true;
                    case MotionEvent.ACTION_CANCEL:return true;
                    default:return true;
                }
            }
        });
    }
    private void collapseToIcon(){
        if(panel==null||collapsed)return;
        settleFollowTransition();settleTransition();expandedWidth=position.width;collapsed=true;panel.resizeEnabled=false;panel.resizeCorner=0;
        for(int i=0;i<panel.getChildCount();i++)panel.getChildAt(i).setVisibility(View.GONE);
        appIcon.setVisibility(View.VISIBLE);panel.setPadding(0,0,0,0);panel.setBackground(null);
        // 真实窗口一并缩为图标大小，原面板区域继续把触摸交给下方游戏。
        position.width=dp(48);position.height=dp(48);windows.updateViewLayout(panel,position);
    }
    /** 执行器重开一局时同步清空页码和高亮；收起期间直接重建，避免旧页在展开后闪现。 */
    private void resetBattlePage(){
        if(panel==null)return;
        settleFollowTransition();settleTransition();activeStep=null;selectedResumeStep=null;
        expandedResumeRow=-1;expandedResumeColumn=-1;pendingAutoIndex=-1;index=0;
        rebuildPage();updateButtons();
    }
    private void expandFromIcon(){
        if(panel==null||!collapsed)return;
        collapsed=false;panel.resizeEnabled=true;
        for(int i=0;i<panel.getChildCount();i++)panel.getChildAt(i).setVisibility(View.VISIBLE);
        if(loopControls!=null)loopControls.setVisibility(followMode?View.VISIBLE:View.GONE);
        appIcon.setVisibility(View.GONE);panel.setPadding(dp(8),dp(8),dp(8),dp(8));panel.setBackground(surface(theme.surface));
        position.width=Math.max(minWindowWidth(),Math.min(maxWindowWidth(),expandedWidth));
        position.x=Math.max(dp(EDGE_DP),Math.min(position.x,screenWidth()-dp(EDGE_DP)-position.width));
        applyFollowLayout(compactFraction);windows.updateViewLayout(panel,position);
        // 自动识别在收起期间继续运行，只记录最新回合，展开后再更新可见内容。
        applyPendingAutoRound();
    }
    private void installCornerResize(){
        panel.resizeTouch=new View.OnTouchListener(){
            float touchX,touchY;
            int left,top,right,bottom,corner;
            @Override public boolean onTouch(View view,MotionEvent event){
                if(position==null)return false;
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                    settleFollowTransition();settleTransition();touchX=event.getRawX();touchY=event.getRawY();corner=panel.resizeCorner;
                    left=position.x;top=position.y;right=left+position.width;bottom=top+panel.getHeight();return true;
                }
                if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
                    float dx=(event.getRawX()-touchX)*((corner&1)!=0?-1:1);
                    float dy=(event.getRawY()-touchY)*((corner&4)!=0?-1:1);
                    int originWidth=right-left,originHeight=bottom-top;
                    // 四角均可向横向或纵向拖动，但只产生一个缩放值；不能单独拉出空白高度。
                    float change=Math.abs(dx)>=Math.abs(dy)?dx:dy*originWidth/Math.max(1,originHeight);
                    int edge=dp(EDGE_DP);
                    int availableWidth=(corner&1)!=0?right-edge:screenWidth()-edge-left;
                    int availableHeight=(corner&4)!=0?bottom-edge:screenHeight()-edge-top;
                    int heightBound=paddingSize()+(int)Math.floor(Math.max(0,availableHeight-fixedHeight()+.499)*(double)referenceContentWidth/pageHeight(currentPage));
                    int maximum=Math.min(maxWindowWidth(),Math.min(availableWidth,heightBound));
                    int minimum=Math.min(minWindowWidth(),maximum);
                    position.width=Math.max(minimum,Math.min(maximum,Math.round(originWidth+change)));
                    int height=fixedHeight()+renderedHeight(currentPage,position.width);
                    // 固定对角，另一角按内容的等比尺寸移动；到上限或下限后停止变化。
                    position.x=(corner&1)!=0?right-position.width:left;
                    position.y=(corner&4)!=0?bottom-height:top;
                    resizeContent();windows.updateViewLayout(panel,position);return true;
                }
                if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){panel.post(RecorderRoundOverlay.this::applyPendingAutoRound);return true;}
                return false;
            }
        };
    }
    private int renderedHeight(RoundPage page,int width){return Math.max(1,Math.round(pageHeight(page)*(float)(width-paddingSize())/referenceContentWidth));}
    private void scalePage(RoundPage page){
        int width=position.width-paddingSize();
        float scale=(float)width/referenceContentWidth;
        // 操作和备注共同等比缩放；高度始终来自内容，不再保留可独立拉长的窗口高度。
        page.view.setPivotX(0);page.view.setPivotY(0);page.view.setScaleX(scale);page.view.setScaleY(scale);
        page.view.setTranslationX((width-referenceContentWidth*scale)/2f);
        if(page.header!=null){
            LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)page.header.getLayoutParams();
            int extent=page.headerHeight-Math.round(page.headerHeight*compactFraction);
            params.bottomMargin=extent>0?dp(1):0;params.height=Math.max(0,extent-params.bottomMargin);
            page.header.setLayoutParams(params);page.header.setAlpha(1-compactFraction);page.header.setVisibility(extent>0?View.VISIBLE:View.GONE);
        }
        if(page.view.getLayoutParams()==null)page.view.setLayoutParams(new FrameLayout.LayoutParams(referenceContentWidth,pageHeight(page)));
        else{page.view.getLayoutParams().height=pageHeight(page);page.view.requestLayout();}
        page.renderedHeight=Math.max(1,Math.round(pageHeight(page)*scale));
    }
    private void resizeContent(){
        if(currentPage==null)return;
        layoutFollowToolbar();
        scalePage(currentPage);
        fitContent(currentPage.renderedHeight);
    }
    private void fitContent(int height){
        position.height=WindowManager.LayoutParams.WRAP_CONTENT;
        contentViewport.getLayoutParams().height=height;
        int x=position.x,y=position.y;
        if(followPlacement){
            // 收缩/展开共用同一动画进度，底边最终贴在图标上沿；缩放和翻回合也保持该底边。
            int anchoredY=Math.round(followAnchorBottom)-fixedHeight()-height;
            x=Math.round(restingX+((screenWidth()-position.width)/2f-restingX)*compactFraction);
            y=Math.round(restingY+(anchoredY-restingY)*compactFraction);
        }
        x=Math.max(dp(EDGE_DP),Math.min(x,screenWidth()-dp(EDGE_DP)-position.width));
        y=Math.max(dp(EDGE_DP),Math.min(y,screenHeight()-dp(EDGE_DP)-fixedHeight()-height));
        boolean moved=y!=position.y||x!=position.x;position.x=x;position.y=y;
        contentViewport.requestLayout();panel.invalidate();
        if(moved&&panel.isAttachedToWindow())windows.updateViewLayout(panel,position);
    }
    /** 复用回合开始时缓存的卡牌定位；不同截图尺寸统一换算到屏幕坐标。 */
    private void setFollowAnchor(RecorderFollowVision.Layout layout){
        if(panel==null||!compactFollow)return;
        float target=layout.upperIconsTop()*windows.getMaximumWindowMetrics().getBounds().height()/layout.height-dp(2);
        if(anchorTransition!=null){anchorTransition.cancel();anchorTransition=null;}
        if(Math.abs(target-followAnchorBottom)<1)return;
        ValueAnimator animator=ValueAnimator.ofFloat(followAnchorBottom,target);anchorTransition=animator;
        animator.setDuration(220);animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value->{if(panel!=null&&anchorTransition==animator){followAnchorBottom=(float)value.getAnimatedValue();if(!collapsed)resizeContent();}});
        animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){if(anchorTransition==animation)anchorTransition=null;}});
        animator.start();
    }
    private void settleTransition(){
        if(transition==null)return;
        ValueAnimator running=transition;transition=null;running.cancel();
        contentViewport.removeAllViews();currentPage.view.setTranslationY(0f);
        contentViewport.addView(currentPage.view,new FrameLayout.LayoutParams(referenceContentWidth,pageHeight(currentPage)));
        resizeContent();updateButtons();
    }
    /** 跟打紧凑态的高度也同步改变真实窗口，收起后不留下挡住游戏的透明区域。 */
    private void applyFollowLayout(float fraction){
        compactFraction=fraction;
        for(LinearLayout line:new LinearLayout[]{autoLine,loopControls}){
            if(line==null)continue;
            LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)line.getLayoutParams();
            params.height=Math.round(dp(28)*(1-fraction));params.bottomMargin=Math.round(dp(4)*(1-fraction));line.setLayoutParams(params);
            boolean visible=!collapsed&&(fraction<1||followTransition!=null)&&(line!=loopControls||followMode);
            line.setVisibility(visible?View.VISIBLE:View.GONE);
        }
        // 勾选框本体参加位置动画，其他设置先淡出，为移动路径让出空间。
        float alpha=Math.max(0,1-fraction*1.8f);
        modeButton.setAlpha(alpha);roleName.setAlpha(alpha);settingsButton.setAlpha(alpha);
        resumeButton.setAlpha(alpha);
        if(loopControls!=null)loopControls.setAlpha(alpha);
        if(!collapsed)resizeContent();
    }
    private void layoutFollowToolbar(){
        if(toolbar==null||position==null)return;
        // 执行中上下回合按钮本就禁用，收起它们给停止勾选留出完整点击区域。
        boolean narrow=position.width<dp(340);
        float hidden=compactFollow&&narrow?1:compactFraction;
        for(View arrow:new View[]{previousButton,nextButton}){
            LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)arrow.getLayoutParams();int width=Math.round(dp(36)*(1-hidden)),margin=Math.round(dp(4)*(1-hidden));
            if(params.width!=width||params.leftMargin!=margin){params.width=width;params.leftMargin=margin;arrow.setLayoutParams(params);}
            arrow.setVisibility(hidden>=1?View.GONE:View.VISIBLE);arrow.setAlpha((arrow.isEnabled()?1f:.45f)*(1-hidden));
        }
        // 箭头占用的宽度随动画缩放，避免展开时与正在移位的重置按钮重叠。
        int size=Math.round(dp(36)+(narrow?dp(28)-dp(36):0)*compactFraction);
        for(View button:new View[]{collapseButton,resetButton,toolbar.getChildAt(toolbar.getChildCount()-1)}){
            if(button.getLayoutParams().width!=size){button.getLayoutParams().width=size;button.requestLayout();}
        }
        handle.setTextSize(narrow?12-compactFraction:12);
    }
    private void resetFollowTranslations(){
        if(followToggleGhost!=null&&panel!=null)panel.getOverlay().remove(followToggleGhost);followToggleGhost=null;autoToggle.setAlpha(1);
        for(View view:new View[]{autoToggle,handle,collapseButton,resetButton,toolbar.getChildAt(toolbar.getChildCount()-1)}){view.setTranslationX(0);view.setTranslationY(0);}
        toolbar.setTranslationZ(0);
    }
    private void positionFollowGhost(){
        if(followToggleGhost==null||panel==null)return;int[] at=new int[2],root=new int[2];autoToggle.getLocationOnScreen(at);panel.getLocationOnScreen(root);
        followToggleGhost.setBounds(at[0]-root[0],at[1]-root[1],at[0]-root[0]+autoToggle.getWidth(),at[1]-root[1]+autoToggle.getHeight());
    }
    private void settleFollowTransition(){if(followTransition!=null)followTransition.end();}
    /** 先测量新布局，再从原屏幕位置平滑移动同一组控件；快速反向切换从当前画面接续。 */
    private void setFollowCompact(boolean compact,boolean animate){
        if(panel==null||table==null)return;
        if(compactFollow==compact&&(followTransition!=null||compactFraction==(compact?1f:0f)))return;
        if(compact&&!followPlacement){
            restingX=position.x;restingY=position.y;
            Rect display=windows.getMaximumWindowMetrics().getBounds();
            followAnchorBottom=RecorderFollowVision.standard(display.width(),display.height()).upperIconsTop()-dp(2);followPlacement=true;
        }
        if(!compact&&anchorTransition!=null){anchorTransition.cancel();anchorTransition=null;}
        View[] moving={autoToggle};
        int[][] before=new int[moving.length][2];for(int n=0;n<moving.length;n++)moving[n].getLocationOnScreen(before[n]);
        if(followTransition!=null){ValueAnimator old=followTransition;followTransition=null;old.cancel();}
        settleTransition();resetFollowTranslations();compactFollow=compact;
        Bitmap togglePicture=null;
        if(animate&&!collapsed&&panel.isAttachedToWindow()&&autoToggle.getWidth()>0){
            // 跨越旧父行边界时由面板顶层绘制小快照，避开原生控件的父行裁剪；结束即恢复同一勾选框。
            autoToggle.jumpDrawablesToCurrentState();togglePicture=Bitmap.createBitmap(autoToggle.getWidth(),autoToggle.getHeight(),Bitmap.Config.ARGB_8888);autoToggle.draw(new android.graphics.Canvas(togglePicture));
        }
        ((android.view.ViewGroup)autoToggle.getParent()).removeView(autoToggle);
        (compact?toolbar:autoLine).addView(autoToggle,compact?2:1,new LinearLayout.LayoutParams(-2,dp(compact?36:28)));
        layoutFollowToolbar();
        if(!animate||collapsed||!panel.isAttachedToWindow()){applyFollowLayout(compact?1f:0f);if(!compact)followPlacement=false;return;}
        if(togglePicture!=null){
            followToggleGhost=new android.graphics.drawable.BitmapDrawable(service.getResources(),togglePicture);int[] root=new int[2];panel.getLocationOnScreen(root);
            followToggleGhost.setBounds(before[0][0]-root[0],before[0][1]-root[1],before[0][0]-root[0]+togglePicture.getWidth(),before[0][1]-root[1]+togglePicture.getHeight());
            panel.getOverlay().add(followToggleGhost);autoToggle.setAlpha(0);
        }
        float start=compactFraction,target=compact?1:0;
        ValueAnimator animator=ValueAnimator.ofFloat(0,1);followTransition=animator;animator.setDuration(300);animator.setInterpolator(new DecelerateInterpolator());
        float[] dx=new float[moving.length],dy=new float[moving.length];
        animator.addUpdateListener(value->{
            if(followTransition!=animator||panel==null)return;float progress=(float)value.getAnimatedValue();
            applyFollowLayout(start+(target-start)*progress);
            for(int n=0;n<moving.length;n++){moving[n].setTranslationX(dx[n]*(1-progress));moving[n].setTranslationY(dy[n]*(1-progress));}
            positionFollowGhost();
        });
        animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){
            if(followTransition!=animation||panel==null)return;followTransition=null;resetFollowTranslations();applyFollowLayout(target);if(!compact)followPlacement=false;updateButtons();applyPendingAutoRound();
        }});
        applyFollowLayout(start);toolbar.setTranslationZ(dp(2));
        panel.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){
            if(panel!=null)panel.getViewTreeObserver().removeOnPreDrawListener(this);
            if(panel==null||followTransition!=animator||animator.isStarted())return true;
            for(int n=0;n<moving.length;n++){int[] at=new int[2];moving[n].getLocationOnScreen(at);dx[n]=before[n][0]-at[0];dy[n]=before[n][1]-at[1];moving[n].setTranslationX(dx[n]);moving[n].setTranslationY(dy[n]);}
            animator.start();return true;
        }});
    }
    private RoundPage createPage(int rowIndex){
        if(table!=null)return createTablePage(rowIndex);
        RecorderRoundImage.Row current=rows.get(rowIndex);
        int currentHeight=Math.max(dp(34),scaledHeight(current.operations,operationWidth));
        if(current.note!=null)currentHeight=Math.max(currentHeight,scaledHeight(current.note,noteWidth));
        LinearLayout page=new LinearLayout(service);page.setOrientation(LinearLayout.HORIZONTAL);page.setGravity(Gravity.CENTER_VERTICAL);
        // 回合数、五列操作、原图备注并排；不再把备注放到下一行或嵌套滚动区域。
        TextView currentLabel=label(current.label);currentLabel.setGravity(Gravity.CENTER);currentLabel.setSingleLine(true);
        float textWidth=currentLabel.getPaint().measureText(current.label);
        if(textWidth>labelWidth-dp(4))currentLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,currentLabel.getTextSize()*Math.max(1,labelWidth-dp(4))/textWidth);
        page.addView(currentLabel,new LinearLayout.LayoutParams(labelWidth,-1));
        ImageView currentImage=image();currentImage.setImageBitmap(current.operations);page.addView(currentImage,new LinearLayout.LayoutParams(operationWidth,-1));
        if(noteWidth>0){
            ImageView noteImage=image();noteImage.setImageBitmap(current.note);page.addView(noteImage,new LinearLayout.LayoutParams(noteWidth,-1));
        }
        return new RoundPage(page,currentHeight);
    }
    private Rect windowBounds(){
        if(panel==null)return null;int[] at=new int[2];panel.getLocationOnScreen(at);
        return new Rect(at[0],at[1],at[0]+panel.getWidth(),at[1]+panel.getHeight());
    }
    /** 回放经过浮窗时临时让出触点，回调或停止后立即恢复按钮的触摸能力。 */
    private void macroPassthrough(boolean enabled){
        if(panel==null||position==null||!panel.isAttachedToWindow())return;
        int flags=enabled?position.flags|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE:position.flags&~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if(flags!=position.flags){position.flags=flags;windows.updateViewLayout(panel,position);}
    }
    private TextView tableCell(String content,int width,int fill,boolean operations){
        // 操作格保留每条操作的换行；普通表头和回合使用单行布局，备注由独立滚动容器承载。
        TextView cell=label(operations?content:content.replaceAll("[\\r\\n]+"," "));cell.setGravity(Gravity.CENTER);cell.setSingleLine(!operations);cell.setIncludeFontPadding(false);cell.setPadding(dp(3),0,dp(3),0);cell.setTextSize(12);
        if(operations){cell.setHorizontallyScrolling(false);cell.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);}
        // 按最长一行适配列宽，避免把多条操作当成一条而过度缩小字体。
        fitCellText(cell,width);
        cell.setTooltipText(content);cell.setContentDescription(content);
        cell.setBackgroundColor(fill);return cell;
    }
    /** 备注保持 12sp，不参与缩字；两行视口内换行，超长内容可纵向滚动且不继续撑高表格。 */
    private View tableNoteCell(String content,int fill,boolean header,int width){
        TextView text=label(content);text.setTextSize(12);text.setGravity(Gravity.CENTER);
        text.setSingleLine(false);text.setHorizontallyScrolling(false);text.setIncludeFontPadding(false);
        text.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);text.setPadding(dp(3),0,dp(3),0);
        text.setTextColor(header?theme.accent:theme.muted);
        if(header)text.setTypeface(null,android.graphics.Typeface.BOLD);
        text.setContentDescription(content);text.setTooltipText(content);
        text.setTag(header?"follow-header-note-text":"follow-row-note-text");
        android.widget.ScrollView scroll=new android.widget.ScrollView(service);
        scroll.setTag(header?"follow-header-note-scroll":"follow-row-note-scroll");
        scroll.setFillViewport(true);scroll.setHorizontalScrollBarEnabled(false);scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(text,new FrameLayout.LayoutParams(-1,-2));
        FrameLayout cell=new FrameLayout(service);cell.setBackgroundColor(fill);
        // 按实际中文回退字体测量两行高度；getLineHeight() 可能低估，导致第二行被裁切。
        text.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int viewportHeight=text.getLayout().getLineTop(Math.min(2,text.getLineCount()));
        cell.setMinimumHeight(viewportHeight);
        cell.addView(scroll,new FrameLayout.LayoutParams(-1,viewportHeight,Gravity.CENTER));
        return cell;
    }
    private void fitCellText(TextView cell,int width){
        float measured=0;for(String line:cell.getText().toString().split("\\r?\\n",-1))measured=Math.max(measured,cell.getPaint().measureText(line));
        int available=Math.max(1,width-cell.getPaddingLeft()-cell.getPaddingRight()-1);
        if(measured>available)cell.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,cell.getTextSize()*available/measured);
    }
    private void fitOperationText(TextView cell,int width,int height){
        int widthSpec=View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),heightSpec=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED);
        cell.measure(widthSpec,heightSpec);
        if(cell.getMeasuredHeight()<=height)return;
        // 列宽和 46dp 行高固定，在原字号上限内寻找能完整容纳所有操作行的最大字号。
        float low=0,high=cell.getTextSize();
        for(int n=0;n<12;n++){
            float size=(low+high)/2;cell.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,size);cell.measure(widthSpec,heightSpec);
            if(cell.getMeasuredHeight()<=height)low=size;else high=size;
        }
        cell.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,low);
    }
    // 黄色、红色等是攻略的目标语义，保持原色；无色单元格及所有框架色跟随主题。
    private int operationFill(String color){return "none".equals(color)?theme.bg:RecorderFollowTable.fill(color);}
    private RoundPage createTablePage(int rowIndex){
        RecorderFollowTable.Row row=table.rows.get(rowIndex);
        int rule=dp(1);
        LinearLayout page=new RecorderWatermarkedLayout(service,table.roleName,theme.muted);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(rule,rule,rule,rule);
        GradientDrawable outline=new GradientDrawable();outline.setColor(theme.line);outline.setCornerRadius(dp(8));page.setBackground(outline);page.setClipToOutline(true);
        int[] widths=new int[7];widths[0]=labelWidth;widths[6]=noteWidth;int consumed=0;
        for(int c=0;c<5;c++){widths[c+1]=(c+1)*operationWidth/5-consumed;consumed+=widths[c+1];}
        View header=null;
        View headerNote=tableNoteCell(table.headerNote.isEmpty()?"备注":table.headerNote,theme.soft,true,widths[6]);
        View rowNote=tableNoteCell(row.note,theme.surface,false,widths[6]);
        // 保留原行高下限，只在正常字号的两行备注确实放不下时补足高度。
        final int headerHeight=Math.max(dp(30),headerNote.getMinimumHeight()),operationHeight=Math.max(dp(46),rowNote.getMinimumHeight());
        for(int line=0;line<2;line++){
            LinearLayout strip=new LinearLayout(service);strip.setGravity(Gravity.CENTER_VERTICAL);
            if(line==0)header=strip;
            for(int c=0;c<7;c++){
                boolean name=c>0&&c<6;
                String text=c==0?(line==0?"回合":row.label):c==6?(line==0?(table.headerNote.isEmpty()?"备注":table.headerNote):row.note):(line==0?table.agents[c-1]:operationText(rowIndex,c-1));
                int width=widths[c]-(c<6?rule:0),fill=line==0||c==0?theme.soft:name?operationFill(row.colors[c-1]):theme.surface;
                if(c==6){
                    strip.addView(line==0?headerNote:rowNote,new LinearLayout.LayoutParams(width,-1));
                    continue;
                }
                TextView cell=tableCell(text,width,fill,line==1&&name);
                if(line==0||c==0){cell.setTextColor(theme.accent);cell.setTypeface(null,android.graphics.Typeface.BOLD);}
                else if(name){cell.setTextSize(14);cell.setTypeface(null,android.graphics.Typeface.BOLD);}
                else cell.setTextColor(theme.muted);
                fitCellText(cell,width);
                // 测量会创建文字布局，先绑定布局参数，后续设置高亮文字时才能安全重排。
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(width,-1);params.rightMargin=c<6?rule:0;strip.addView(cell,params);
                if(line==1&&name){
                    fitOperationText(cell,width,operationHeight);
                    int column=c-1;cell.setTag("follow-cell-"+column);cell.setOnClickListener(v->chooseResumeStep(rowIndex,column));
                    RecorderFollowTable.Step highlighted=selectedResumeStep!=null?selectedResumeStep:activeStep;
                    if(highlighted!=null&&highlighted.row==rowIndex&&highlighted.column==column)highlightCell(cell,highlighted);
                }
            }
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,line==0?headerHeight:operationHeight);params.bottomMargin=line==0?rule:0;page.addView(strip,params);
        }
        int height=headerHeight+operationHeight+rule*3;
        if(followMode&&expandedResumeRow==rowIndex&&!autoToggle.isChecked()){
            // 步骤直接在表格下展开；横向滚动承载较多条目，不另开遮挡游戏的弹窗。
            LinearLayout choices=new LinearLayout(service);choices.setTag("resume-choices");choices.setGravity(Gravity.CENTER_VERTICAL);choices.setBackgroundColor(theme.surface);
            TextView title=label("步骤");title.setTextSize(10);title.setGravity(Gravity.CENTER);title.setTextColor(theme.accent);title.setTooltipText(table.agents[expandedResumeColumn]+"：选择继续起点");choices.addView(title,new LinearLayout.LayoutParams(labelWidth,-1));
            android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(service);scroll.setHorizontalScrollBarEnabled(true);LinearLayout entries=new LinearLayout(service);entries.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(entries,new FrameLayout.LayoutParams(-2,-1));choices.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
            for(RecorderFollowTable.Step step:resumeChoices(rowIndex,expandedResumeColumn)){
                TextView option=label(step.order+step.action);option.setTag("resume-choice-"+step.entry);option.setContentDescription("从"+step.order+step.action+"继续");option.setGravity(Gravity.CENTER);option.setTextSize(14);option.setTextColor(selectedResumeStep==step?theme.on:theme.accent);option.setBackground(surface(selectedResumeStep==step?theme.accent:theme.bg));option.setPadding(dp(8),0,dp(8),0);option.setMinWidth(dp(48));
                LinearLayout.LayoutParams item=new LinearLayout.LayoutParams(-2,dp(32));item.rightMargin=dp(4);entries.addView(option,item);option.setOnClickListener(v->selectResumeStep(step));
            }
            TextView fold=label("收起");fold.setTag("resume-collapse");fold.setTextSize(10);fold.setGravity(Gravity.CENTER);fold.setTextColor(theme.muted);fold.setOnClickListener(v->closeResumeChoices());choices.addView(fold,new LinearLayout.LayoutParams(dp(38),-1));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(40));params.topMargin=rule;page.addView(choices,params);height+=dp(40)+rule;
            choices.setAlpha(0);choices.animate().alpha(1).setDuration(160).start();
        }
        if(followMode){
            // 存活按钮单独占一行，与五列操作区对齐；普通图片和手抄模式不显示。
            LinearLayout footer=new LinearLayout(service);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setBackgroundColor(theme.surface);
            TextView title=label("敌方");title.setTextSize(10);title.setGravity(Gravity.CENTER);title.setTextColor(theme.muted);
            title.setTooltipText("点击编号切换存活和阵亡");footer.addView(title,new LinearLayout.LayoutParams(labelWidth,-1));
            LinearLayout positions=new LinearLayout(service);positions.setGravity(Gravity.CENTER_VERTICAL);
            footer.addView(positions,new LinearLayout.LayoutParams(operationWidth,-1));
            for(int number=1;number<=followRunner.enemyCount();number++){
                final int position=number;TextView button=label(String.valueOf(position));button.setGravity(Gravity.CENTER);button.setTextSize(12);button.setTypeface(null,android.graphics.Typeface.BOLD);
                button.setTag("enemy-position-"+position);button.setOnClickListener(v->followRunner.toggleEnemy(position));styleEnemy(button,position);
                LinearLayout.LayoutParams item=new LinearLayout.LayoutParams(0,dp(28),1);item.leftMargin=dp(2);item.rightMargin=dp(2);positions.addView(button,item);
            }
            TextView current=label("");current.setTag("enemy-current");current.setTextSize(10);current.setGravity(Gravity.CENTER);current.setTextColor(theme.muted);
            footer.addView(current,new LinearLayout.LayoutParams(noteWidth,-1));styleCurrentEnemy(current);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(38));params.topMargin=rule;page.addView(footer,params);height+=dp(38)+rule;
        }
        RoundPage result=new RoundPage(page,height);result.header=header;result.headerHeight=headerHeight+rule;return result;
    }
    /** 颜色只由占位设置决定，死亡统一置灰；灰色按钮保持可点击以便中途纠正。 */
    private void styleEnemy(TextView button,int position){
        boolean alive=followRunner.enemyAlive(position),selected=alive&&followRunner.currentTarget()==position;
        java.util.ArrayList<Integer> fills=new java.util.ArrayList<>();
        for(String color:RecorderFollowTable.COLORS)if(!"none".equals(color)&&followSettings.targets.getOrDefault(color,0)==position)fills.add(RecorderFollowTable.fill(color));
        GradientDrawable background=new GradientDrawable();
        if(!alive)background.setColor(0xffe1e4e7);
        else if(fills.isEmpty())background.setColor(theme.accent);
        else if(fills.size()==1)background.setColor(fills.get(0));
        else{
            // 多种颜色绑定同一位置时一起显示，避免只显示第一种而误导使用者。
            int[] colors=new int[fills.size()];for(int i=0;i<colors.length;i++)colors[i]=fills.get(i);
            background.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);background.setColors(colors);
        }
        background.setCornerRadius(dp(7));background.setStroke(dp(selected?2:1),selected?theme.accent:alive?theme.line:0xffd4d8dc);
        button.setBackground(background);button.setTextColor(!alive?0xff878e96:fills.isEmpty()?theme.on:0xff25343b);
        button.setPaintFlags(alive?button.getPaintFlags()&~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG:button.getPaintFlags()|android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        button.setSelected(selected);button.setContentDescription(position+"号敌方，"+(alive?"存活，点击标记阵亡":"阵亡，点击恢复存活")+(selected?"，当前目标":""));button.setTooltipText(button.getContentDescription());
    }
    private void styleCurrentEnemy(TextView current){current.setText(followRunner.targetLabel());current.setTooltipText("点击编号切换存活状态；跟打设置中可校正当前目标");}
    private void refreshEnemies(){
        if(contentViewport==null||followRunner==null)return;
        // 翻页动画中的两页同步更新，避免旧页残留点击前的颜色。
        for(int page=0;page<contentViewport.getChildCount();page++){
            View root=contentViewport.getChildAt(page);
            for(int number=1;number<=followRunner.enemyCount();number++){TextView button=root.findViewWithTag("enemy-position-"+number);if(button!=null)styleEnemy(button,number);}
            TextView current=root.findViewWithTag("enemy-current");if(current!=null)styleCurrentEnemy(current);
        }
    }
    private void rebuildPage(){
        if(contentViewport==null)return;settleFollowTransition();settleTransition();currentPage=createPage(index);contentViewport.removeAllViews();
        contentViewport.addView(currentPage.view,new FrameLayout.LayoutParams(referenceContentWidth,pageHeight(currentPage)));if(!collapsed)resizeContent();
    }
    private String operationText(int row,int column){return followMode&&followRunner!=null&&(!table.live||followRunner.displays(table))?followRunner.cell(row,column):table.rows.get(row).cells[column];}
    private boolean liveReady=true;
    /** 显示立即刷新，执行器在手势完成处换队列；保留窗口大小、位置、收起状态及本机跟打设置。 */
    void updateLive(RecorderFollowTable next){
        if(table==null||!table.live||panel==null)return;
        replaceShownTable(next);
    }
    private void replaceShownTable(RecorderFollowTable next){
        long visibleId=table.rows.get(index).id;
        settleFollowTransition();settleTransition();pendingAutoIndex=-1;
        selectedResumeStep=null;activeStep=null;expandedResumeRow=-1;expandedResumeColumn=-1;
        table=next;index=Math.min(index,next.rows.size()-1);
        for(int r=0;r<next.rows.size();r++)if(next.rows.get(r).id==visibleId){index=r;break;}
        for(RecorderRoundImage.Row row:rows){row.operations.recycle();if(row.note!=null)row.note.recycle();}
        rows.clear();rows.addAll(tableRows(next));
        roleName.setText(next.roleName);roleName.setTooltipText(next.roleName);
        if(!next.allowAutoFollow){autoToggle.setChecked(false);followMode=false;modeButton.setText("手动抄表 ▾");autoToggle.setText("自动识别回合数");if(loopControls!=null)loopControls.setVisibility(View.GONE);}
        if(followRunner!=null)followRunner.updateLive(next);
        updateResumeControls();rebuildPage();updateButtons();
    }
    void liveAvailable(boolean available,String reason){
        boolean changed=liveReady!=available;liveReady=available;
        if(!available){
            if(autoToggle!=null)autoToggle.setChecked(false);
            if(followRunner!=null)followRunner.stop();
            setAutoStatus(reason);if(changed)Toast.makeText(service,reason+"，已暂停跟打",Toast.LENGTH_LONG).show();
        }else if(changed)setAutoStatus("实时同步已恢复，可开始跟打");
        if(handle!=null){handle.setText(available?"实时操作表":"同步已暂停");handle.setContentDescription(available?"实时操作表，按住拖动悬浮窗":reason);}
    }
    private void highlightCell(TextView cell,RecorderFollowTable.Step step){
        GradientDrawable background=new GradientDrawable();background.setColor(operationFill(step.color));background.setCornerRadius(dp(4));background.setStroke(dp(2),theme.accent);cell.setBackground(background);
        String text=cell.getText().toString(),needle=step.order+step.action;
        // 数字边界保证点选 1A 时不会错误标亮同格的 11A。
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?<![0-9])"+java.util.regex.Pattern.quote(needle)+"(?![0-9])").matcher(text);
        android.text.SpannableString styled=new android.text.SpannableString(text);int start=match.find()?match.start():-1;
        // 分支可以在同一原出手位置追加重复动作，按实际条目定位，不能总标亮第一个同名动作。
        if(step.original!=null&&followRunner!=null){int offset=0;start=-1;for(RecorderFollowTable.Step entry:followRunner.rowSteps(step.row))if(entry.column==step.column){if(entry==step){start=offset;break;}offset+=(entry.order+entry.action).length()+1;}}
        if(start>=0){styled.setSpan(new android.text.style.BackgroundColorSpan(theme.accent),start,start+needle.length(),0);styled.setSpan(new android.text.style.ForegroundColorSpan(theme.on),start,start+needle.length(),0);styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),start,start+needle.length(),0);}
        cell.setText(styled);cell.setContentDescription((selectedResumeStep==step||!autoToggle.isChecked()?"待继续 ":"正在执行 ")+needle+"，"+table.agents[step.column]);
    }
    /** 1A 已发出而动画尚未结束时，暂停显示实际待执行的 2A；不把它变成手动点选起点。 */
    private void syncPausedFollowProgress(){
        if(!followMode||followRunner==null)return;
        activeStep=followRunner.pendingStep();
        // 下一步可能在续行；直接重建暂停页，避免收起/展开动画挡住这次进度同步。
        if(activeStep!=null&&activeStep.row!=index){index=activeStep.row;rebuildPage();}
        else refreshHighlight();
    }
    private void refreshHighlight(){
        if(table==null||currentPage==null)return;
        for(int c=0;c<5;c++){
            TextView cell=currentPage.view.findViewWithTag("follow-cell-"+c);if(cell==null)continue;
            RecorderFollowTable.Row row=table.rows.get(index);String text=operationText(index,c);cell.setText(text);cell.setContentDescription(text);cell.setBackgroundColor(operationFill(row.colors[c]));
            RecorderFollowTable.Step highlighted=selectedResumeStep!=null?selectedResumeStep:activeStep;
            if(highlighted!=null&&highlighted.row==index&&highlighted.column==c)highlightCell(cell,highlighted);
        }
    }
    private void updateResumeControls(){
        if(resumeButton==null)return;
        boolean selected=followMode&&selectedResumeStep!=null&&!autoToggle.isChecked();
        resumeButton.setVisibility(selected?View.VISIBLE:View.GONE);resumeButton.setEnabled(selected&&transition==null&&followTransition==null);
        modeButton.setVisibility(selected?View.GONE:View.VISIBLE);roleName.setVisibility(selected?View.GONE:View.VISIBLE);
        if(selected){String from=table.rows.get(selectedResumeStep.row).label+" "+selectedResumeStep.order+selectedResumeStep.action;
            resumeButton.setContentDescription("继续跟打，从"+from+"开始");resumeButton.setTooltipText("从"+from+"开始，之前的动作跳过");}
    }
    private void clearResumeSelection(){
        selectedResumeStep=null;closeResumeChoices();updateResumeControls();refreshHighlight();
    }
    private void closeResumeChoices(){
        if(expandedResumeRow<0)return;expandedResumeRow=-1;expandedResumeColumn=-1;rebuildPage();
    }
    private void selectResumeStep(RecorderFollowTable.Step step){
        if(autoToggle.isChecked()||!followMode)return;
        selectedResumeStep=step;closeResumeChoices();updateResumeControls();refreshHighlight();
    }
    private java.util.ArrayList<RecorderFollowTable.Step> resumeChoices(int rowIndex,int column){
        java.util.ArrayList<RecorderFollowTable.Step> choices=new java.util.ArrayList<>();
        for(RecorderFollowTable.Step step:followRunner==null?table.rows.get(rowIndex).steps:followRunner.rowSteps(rowIndex))if(step.column==column)choices.add(step);
        choices.sort(java.util.Comparator.comparingInt(step->step.entry));return choices;
    }
    private void chooseResumeStep(int rowIndex,int column){
        if(!followMode||table==null)return;
        if(autoToggle.isChecked()){Toast.makeText(service,"请先停止跟打，再点选继续位置",Toast.LENGTH_SHORT).show();return;}
        if(transition!=null||followTransition!=null)return;
        RecorderFollowTable.Row row=table.rows.get(rowIndex);
        try{table.steps(row.round);}catch(IllegalArgumentException error){Toast.makeText(service,error.getMessage(),Toast.LENGTH_LONG).show();return;}
        java.util.ArrayList<RecorderFollowTable.Step> choices=resumeChoices(rowIndex,column);
        if(choices.isEmpty())return;
        if(choices.size()==1){if(selectedResumeStep==choices.get(0))clearResumeSelection();else selectResumeStep(choices.get(0));return;}
        if(expandedResumeRow==rowIndex&&expandedResumeColumn==column){closeResumeChoices();return;}
        expandedResumeRow=rowIndex;expandedResumeColumn=column;rebuildPage();updateResumeControls();
    }
    private void followRow(int row){
        if(panel==null)return;
        if(collapsed||panel.resizeCorner!=0||followTransition!=null){pendingAutoIndex=row;return;}
        settleTransition();moveTo(row);refreshHighlight();
    }
    private android.widget.EditText settingInput(LinearLayout parent,String title,int value){
        LinearLayout line=new LinearLayout(service);line.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=label(title);label.setTextSize(12);line.addView(label,new LinearLayout.LayoutParams(0,dp(40),1));
        android.widget.EditText input=new android.widget.EditText(service);input.setSingleLine(true);input.setText(String.valueOf(value));input.setTextSize(13);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setContentDescription(title);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(5)});input.setPadding(dp(10),0,dp(10),0);
        line.addView(input,new LinearLayout.LayoutParams(dp(84),dp(36)));LinearLayout.LayoutParams row=new LinearLayout.LayoutParams(-1,dp(44));row.bottomMargin=dp(4);parent.addView(line,row);return input;
    }
    private void settingsHeading(LinearLayout parent,String title){TextView view=label(title);view.setTag("settings-heading");view.setTextSize(14);view.setTypeface(null,android.graphics.Typeface.BOLD);view.setPadding(0,dp(12),0,dp(8));parent.addView(view);}
    /** 前后延迟共一行，减少重复标签；输入描述沿用原来的单位与校验定位。 */
    private android.widget.EditText[] timingInputs(LinearLayout parent,String action,int pre,int post){
        LinearLayout line=new LinearLayout(service);line.setGravity(Gravity.CENTER_VERTICAL);TextView title=label(action);title.setTextSize(18);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setGravity(Gravity.CENTER);line.addView(title,new LinearLayout.LayoutParams(dp(44),dp(44)));
        android.widget.EditText[] inputs=new android.widget.EditText[2];
        for(int n=0;n<2;n++){android.widget.EditText input=new android.widget.EditText(service);inputs[n]=input;input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(5)});input.setText(String.valueOf(n==0?pre:post));input.setTextSize(13);input.setGravity(Gravity.CENTER);input.setContentDescription(action+(n==0?" 操作前延迟 ms":" 操作后延迟 ms"));input.setPadding(dp(8),0,dp(8),0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);p.leftMargin=dp(6);line.addView(input,p);}
        LinearLayout.LayoutParams row=new LinearLayout.LayoutParams(-1,dp(48));parent.addView(line,row);return inputs;
    }
    private void styleSettings(View view){
        String tag=String.valueOf(view.getTag());
        if("settings-body".equals(tag))view.setBackground(surface(theme.surface));
        if(view instanceof android.widget.CompoundButton){
            android.widget.CompoundButton checkbox=(android.widget.CompoundButton)view;checkbox.setTextColor(theme.ink);checkbox.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));
        }else if(view instanceof android.widget.EditText){
            android.widget.EditText input=(android.widget.EditText)view;input.setTextColor(theme.ink);input.setHighlightColor(theme.soft);input.setBackgroundTintList(null);input.setBackground(surface(theme.bg));
        }else if(view instanceof android.widget.Button){
            android.widget.Button button=(android.widget.Button)view;boolean primary="settings-save".equals(tag);
            button.setTextColor(primary?theme.on:theme.accent);button.setBackground(surface(primary?theme.accent:theme.bg));button.setBackgroundTintList(null);
            button.setAllCaps(false);button.setTextSize(14);button.setStateListAnimator(null);button.setElevation(0);
        }else if(view instanceof TextView){((TextView)view).setTextColor("settings-hint".equals(tag)?theme.muted:"settings-heading".equals(tag)?theme.accent:theme.ink);}
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int c=0;c<group.getChildCount();c++)styleSettings(group.getChildAt(c));}
    }
    /** 编辑前保持暂停；应用只替换当前会话，实时来源更新后拒绝过期草稿。 */
    private void openTableEditor(){
        if(table==null||table.editPayload==null)return;
        autoToggle.setChecked(false);if(followRunner!=null)followRunner.stop();
        final RecorderFollowTable source=table;final String snapshot=source.editPayload;
        editSession=new RecorderFollowEditSession(source,()->panel!=null&&table==source&&snapshot.equals(table.editPayload),next->{
            next.live=source.live;replaceShownTable(next);
            setAutoStatus("操作表已调整，请核对进度后开始或点选动作继续");
        },()->{editSession=null;updatePanelVisibility();});
        if(settingsDialog!=null)settingsDialog.dismiss();updatePanelVisibility();
        try{editSession.open(service);}catch(RuntimeException error){Toast.makeText(service,"无法打开调整表操作，请重试",Toast.LENGTH_LONG).show();}
    }
    private void openFollowSettings(){
        // 在停止回合识别或创建窗口之前拒绝空表；其他回调直接调用时也不会触发空指针。
        if(table==null){Toast.makeText(service,"这张图片仅支持手动抄表，无法设置自动跟打",Toast.LENGTH_SHORT).show();return;}
        autoToggle.setChecked(false);
        // 保存、取消、系统返回、点击外部共用校验；服务销毁时 dismiss 仍可强制释放窗口。
        java.util.function.BooleanSupplier[] mayClose={()->true};
        settingsDialog=new android.app.Dialog(service){
            @Override public void cancel(){if(mayClose[0].getAsBoolean())super.cancel();}
            // 无障碍浮窗的外部触摸可能以 OUTSIDE 事件到达，沿用同一关闭校验。
            @Override public boolean onTouchEvent(MotionEvent event){if(event.getAction()==MotionEvent.ACTION_OUTSIDE){cancel();return true;}return super.onTouchEvent(event);}
        };settingsDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);settingsDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);settingsDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);settingsDialog.setCanceledOnTouchOutside(true);
        LinearLayout body=new LinearLayout(service);body.setTag("settings-body");body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(20),dp(20),dp(16));
        TextView heading=label("跟打设置");heading.setTag("settings-heading");heading.setTextSize(18);heading.setTypeface(null,android.graphics.Typeface.BOLD);body.addView(heading);
        TextView hint=label("时间单位 ms · 保存后生效");hint.setTag("settings-hint");hint.setTextSize(12);hint.setPadding(0,dp(8),0,dp(12));body.addView(hint);
        android.widget.ScrollView scroll=new android.widget.ScrollView(service);scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);LinearLayout fields=new LinearLayout(service);fields.setOrientation(LinearLayout.VERTICAL);scroll.addView(fields);
        // 表单只占标题和操作栏之外的剩余高度，选回合返回或键盘压缩窗口时不会挤掉保存按钮。
        body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        // 第一项直达完整操作记录仪，跟打表本身的修改不保存到 App。
        android.widget.Button editTable=new android.widget.Button(service);editTable.setText("调整表操作");editTable.setContentDescription("调整表操作");editTable.setTag("edit-follow-table");fields.addView(editTable,new LinearLayout.LayoutParams(-1,dp(44)));editTable.setOnClickListener(v->openTableEditor());
        // 行动时序紧随编辑入口，检测类集中在末尾，保存仍统一提交设置草稿。
        settingsHeading(fields,"行动时序 · ms");
        android.widget.EditText[] before=new android.widget.EditText[4],after=new android.widget.EditText[4];
        LinearLayout timingHeader=new LinearLayout(service);timingHeader.setGravity(Gravity.CENTER_VERTICAL);
        for(String title:new String[]{"行动","操作前","操作后"}){TextView item=label(title);item.setTag("settings-hint");item.setTextSize(11);item.setGravity(Gravity.CENTER);timingHeader.addView(item,title.equals("行动")?new LinearLayout.LayoutParams(dp(44),dp(28)):new LinearLayout.LayoutParams(0,dp(28),1));}fields.addView(timingHeader);
        for(int i=0;i<4;i++){android.widget.EditText[] pair=timingInputs(fields,RecorderFollowSettings.ACTIONS[i],followSettings.before[i],followSettings.after[i]);before[i]=pair[0];after[i]=pair[1];}
        android.widget.EditText roundDelay=settingInput(fields,"回合开始延迟 ms",followSettings.roundStartDelay);
        android.widget.EditText motionLimit=settingInput(fields,"动画最多追加等待 ms",followSettings.motionExtraWait);
        android.widget.CheckBox motion=new android.widget.CheckBox(service);motion.setText("按需等待密探行动结束");motion.setContentDescription("按需等待密探行动结束");motion.setTextSize(13);motion.setChecked(followSettings.selectiveMotionWait);fields.addView(motion,new LinearLayout.LayoutParams(-1,dp(40)));
        TextView motionHint=label("");motionHint.setContentDescription("密探行动等待说明");motionHint.setTag("settings-hint");motionHint.setTextSize(11);motionHint.setPadding(0,0,0,dp(12));fields.addView(motionHint);
        Runnable refreshMotion=()->motionHint.setText(motion.isChecked()
            ?"↑ 后等待行动结束；A、↓、圈仅在下一步切敌时等待。达到追加上限后继续，0 不追加；固定延迟及各项检测仍生效。"
            :"A、↑、↓、圈每步后都检测并等待行动结束。达到追加上限后继续，0 不追加；固定延迟及各项检测仍生效。");
        motion.setOnCheckedChangeListener((button,checked)->refreshMotion.run());refreshMotion.run();
        settingsHeading(fields,"敌方位置");
        int currentTarget=followRunner!=null&&followRunner.settingsTarget()>0?followRunner.settingsTarget():followSettings.initialTarget;
        android.widget.EditText enemies=settingInput(fields,"敌人数（1–5）",followSettings.enemies),initial=settingInput(fields,"开始时当前目标位置",currentTarget);
        List<String> usedColors=table.usedColors();android.widget.EditText[] colors=new android.widget.EditText[usedColors.size()];
        for(int i=0;i<usedColors.size();i++){String color=usedColors.get(i);colors[i]=settingInput(fields,RecorderFollowSettings.colorName(color)+" → 占位编号",followSettings.targets.getOrDefault(color,0));}
        TextView colorHint=label("颜色对应敌方编号，0 保持当前目标。");colorHint.setTag("settings-hint");colorHint.setTextSize(11);colorHint.setPadding(0,0,0,dp(12));fields.addView(colorHint);
        TextView slotsHint=label("敌人数请填本局最多占位数，包含后续召唤的小怪。每回合开始重新核对存活与空位。");slotsHint.setTag("settings-hint");slotsHint.setTextSize(11);fields.addView(slotsHint);
        settingsHeading(fields,"嘲讽处理 · 本表");
        RecorderTauntSettingsView taunts=new RecorderTauntSettingsView(service,fields,table,followSettings,settingsDialog,()->theme,()->styleSettings(body));
        settingsHeading(fields,"检测与条件");
        android.widget.CheckBox deaths=new android.widget.CheckBox(service);deaths.setText("自动识别敌方存活 / 阵亡 / 新增");deaths.setContentDescription("自动识别敌方阵亡");deaths.setTextSize(13);deaths.setChecked(followSettings.detectEnemyDeaths);fields.addView(deaths,new LinearLayout.LayoutParams(-1,dp(40)));
        conditionsEditor=new RecorderConditionSettingsView(service,fields,table,roundConditions,settingsDialog,()->theme);
        settingsDialog.setOnDismissListener(dialog->{taunts.close();if(conditionsEditor!=null)conditionsEditor.close();});
        LinearLayout buttons=new LinearLayout(service);buttons.setPadding(0,dp(12),0,0);body.addView(buttons);
        java.util.function.Supplier<RecorderFollowSettings> readSettings=()->{
            RecorderFollowSettings draft=new RecorderFollowSettings();
            // 顺序与屏幕自上而下一致，多个错误时先定位最上方的字段。
            for(int i=0;i<4;i++){draft.before[i]=RecorderSettingsValidation.number(before[i],RecorderFollowSettings.ACTIONS[i]+" 操作前延迟 ms",0,60000);draft.after[i]=RecorderSettingsValidation.number(after[i],RecorderFollowSettings.ACTIONS[i]+" 操作后延迟 ms",0,60000);}
            draft.roundStartDelay=RecorderSettingsValidation.number(roundDelay,"回合开始延迟 ms",0,60000);
            draft.motionExtraWait=RecorderSettingsValidation.number(motionLimit,"动画最多追加等待 ms",0,60000);draft.selectiveMotionWait=motion.isChecked();draft.detectEnemyDeaths=deaths.isChecked();
            draft.enemies=RecorderSettingsValidation.number(enemies,"敌人数",1,5);draft.initialTarget=RecorderSettingsValidation.number(initial,"开始时当前目标位置",1,draft.enemies);
            draft.tauntRounds.putAll(taunts.read(draft.enemies));
            for(int i=0;i<usedColors.size();i++)draft.targets.put(usedColors.get(i),RecorderSettingsValidation.number(colors[i],RecorderFollowSettings.colorName(usedColors.get(i))+"位置",0,draft.enemies));
            conditionsEditor.read();draft.validate();return draft;
        };
        mayClose[0]=()->{try{readSettings.get();return true;}catch(Exception error){RecorderSettingsValidation.report(service,error);return false;}};
        android.widget.Button cancel=new android.widget.Button(service);cancel.setText("取消");buttons.addView(cancel,new LinearLayout.LayoutParams(0,dp(44),1));cancel.setOnClickListener(v->settingsDialog.cancel());
        android.widget.Button save=new android.widget.Button(service);save.setTag("settings-save");save.setText("保存");LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(0,dp(44),1);saveParams.leftMargin=dp(10);buttons.addView(save,saveParams);save.setOnClickListener(v->{
            try{
                RecorderFollowSettings draft=readSettings.get();
                RecorderRoundConditions conditionsDraft=conditionsEditor.read();draft.validate();draft.save(service,table);conditionsDraft.save(service,table);
                roundConditions=conditionsDraft;if(followRunner!=null)followRunner.setConditions(roundConditions);
                followSettings.enemies=draft.enemies;followSettings.initialTarget=draft.initialTarget;followSettings.targets.putAll(draft.targets);
                followSettings.tauntRounds.clear();followSettings.tauntRounds.putAll(draft.tauntRounds);
                followSettings.roundStartDelay=draft.roundStartDelay;
                followSettings.selectiveMotionWait=draft.selectiveMotionWait;followSettings.motionExtraWait=draft.motionExtraWait;
                followSettings.detectEnemyDeaths=draft.detectEnemyDeaths;
                System.arraycopy(draft.before,0,followSettings.before,0,4);System.arraycopy(draft.after,0,followSettings.after,0,4);
                if(followRunner!=null)followRunner.updateInitialTarget();rebuildPage();settingsDialog.dismiss();
            }catch(Exception error){RecorderSettingsValidation.report(service,error);}
        });
        // 初次打开由容器持有焦点，避免数字输入框自动唤起键盘；编辑时缩小表单，保留操作栏。
        body.setFocusableInTouchMode(true);body.requestFocus();
        styleSettings(body);conditionsEditor.applyTheme();settingsDialog.setContentView(body);
        android.view.Window settingsWindow=settingsDialog.getWindow();settingsWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        settingsWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        settingsDialog.show();settingsWindow.setDimAmount(.32f);setSystemUiCovered(systemUiCovered);
        settingsWindow.setLayout(Math.min(screenWidth()-dp(24),dp(420)),Math.min(screenHeight()-dp(48),dp(680)));
    }
    private void moveTo(int target){
        if(panel==null||collapsed||transition!=null||followTransition!=null||target<0||target>=rows.size())return;
        if(target==index)return;
        if(!autoToggle.isChecked())clearResumeSelection();
        RoundPage outgoing=currentPage,incoming=createPage(target);
        scalePage(incoming);
        int direction=target>index?1:-1;
        // 下回合从下方进入，上回合从上方进入；两页首尾相接，避免留白或重叠。
        int distance=direction>0?outgoing.renderedHeight:incoming.renderedHeight;
        incoming.view.setTranslationY(direction*distance);
        contentViewport.addView(incoming.view,new FrameLayout.LayoutParams(referenceContentWidth,pageHeight(incoming)));
        index=target;currentPage=incoming;
        transition=ValueAnimator.ofFloat(0f,1f);transition.setDuration(300);transition.setInterpolator(new DecelerateInterpolator());
        transition.addUpdateListener(animation->{
            float progress=(float)animation.getAnimatedValue();
            outgoing.view.setTranslationY(-direction*distance*progress);
            incoming.view.setTranslationY(direction*distance*(1f-progress));
            // 切换长短行时同步改变窗口高度，缩放后也不能留下上一行的底部空白。
            int contentHeight=Math.round(outgoing.renderedHeight+(incoming.renderedHeight-outgoing.renderedHeight)*progress);
            fitContent(contentHeight);
        });
        transition.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator animation){
                if(panel==null||transition!=animation)return;
                contentViewport.removeView(outgoing.view);incoming.view.setTranslationY(0f);
                transition=null;updateButtons();
                applyPendingAutoRound();
            }
        });
        // 动画期间暂时停用导航，防止连点造成多组页面同时滚动；关闭始终可用。
        updateButtons();transition.start();
    }
    private void updateButtons(){
        boolean idle=transition==null&&!(followRunner!=null&&followRunner.running());
        resetButton.setEnabled(transition==null);resetButton.setAlpha(transition==null?1f:.45f);
        previousButton.setEnabled(idle&&index>0);previousButton.setAlpha(idle&&index>0?1f:.45f);
        nextButton.setEnabled(idle&&index+1<rows.size());nextButton.setAlpha(idle&&index+1<rows.size()?1f:.45f);
        layoutFollowToolbar();
        updateResumeControls();
    }
    private void setAutoStatus(String text){if(autoStatus!=null){autoStatus.setText(text);autoStatus.setContentDescription(text);autoStatus.setTooltipText(text);}}
    private void acceptAutoRound(int round,boolean changed){
        if(panel==null||!autoToggle.isChecked())return;
        int target=-1;
        // 数字回合与“牢狱”等续行分开匹配，不能把第 10 回合错当成第 10 个数组元素。
        for(int i=0;i<rows.size();i++)if(rows.get(i).label.equals(round+"回合")){target=i;break;}
        setAutoStatus(target<0?"操作表无第 "+round+" 回合":"");
        if(!changed)return;
        // 仅记录确认后的变化，现场诊断可区分 OCR 未读到与表格回合不存在，不增加截图频率。
        CaptureDiagnostics.event(service,"手动抄表识别：游戏第"+round+"回合，"+(target<0?"操作表无对应回合":"定位到"+rows.get(target).label));
        // 新回合不在操作表里时，也清除拖动期间积压的旧目标。
        pendingAutoIndex=target;applyPendingAutoRound();
    }
    private void applyPendingAutoRound(){
        if(panel==null||collapsed||transition!=null||followTransition!=null||panel.resizeCorner!=0||!autoToggle.isChecked()||pendingAutoIndex<0)return;
        int target=pendingAutoIndex;pendingAutoIndex=-1;moveTo(target);
    }
    /** 通知暂停与浮窗取消勾选共用停止链路，保留动作游标供用户核对后继续。 */
    void pauseFromNotification(){
        if(autoToggle!=null)autoToggle.setChecked(false);
        if(followRunner!=null)followRunner.stop();
        setAutoStatus("已从通知暂停跟打，表格已保留");
    }
    /** 系统反馈中断只停动作并保留当前表格；它不等于用户关闭悬浮窗或撤销权限。 */
    void interrupt(){
        if(autoToggle!=null)autoToggle.setChecked(false);
        if(autoReader!=null)autoReader.stop();if(followRunner!=null)followRunner.stop();
        if(loopController!=null)loopController.close();recordingLoop=false;updatePanelVisibility();
        setAutoStatus("系统中断了自动操作，表格已保留");
    }
    /** 保留编辑、录制与收起状态；通知栏关闭只解除自己的隐藏原因。 */
    private void updatePanelVisibility(){
        if(panel!=null)panel.setVisibility(editSession!=null?View.GONE:(systemUiCovered||recordingLoop?View.INVISIBLE:View.VISIBLE));
    }
    void refreshSystemUi(){systemUiGuard.refresh();}
    private void setSystemUiCovered(boolean covered){
        systemUiCovered=covered;updatePanelVisibility();
        // 隐藏整个 DecorView，连同触摸区域及对话框遮罩一起让出，不 dismiss 未保存的设置。
        // isShowing() 在 DecorView 隐藏后返回 false；用附着状态区分临时隐藏和真正 dismiss。
        if(settingsDialog!=null&&settingsDialog.getWindow().getDecorView().isAttachedToWindow())settingsDialog.getWindow().getDecorView().setVisibility(covered?View.INVISIBLE:View.VISIBLE);
        if(loopController!=null)loopController.setSystemUiCovered(covered);
    }
    void close(){
        systemUiGuard.close();
        returnToRecorder=null;
        if(editSession!=null)editSession.close();
        pendingAutoIndex=-1;if(autoReader!=null)autoReader.stop();
        if(followRunner!=null)followRunner.stop();if(loopController!=null)loopController.close();if(settingsDialog!=null)settingsDialog.dismiss();
        // 先取消动画回调再移除窗口，避免回调访问已关闭的浮窗或已回收图片。
        if(transition!=null){ValueAnimator running=transition;transition=null;running.cancel();}
        if(followTransition!=null){ValueAnimator running=followTransition;followTransition=null;running.cancel();}
        if(anchorTransition!=null){ValueAnimator running=anchorTransition;anchorTransition=null;running.cancel();}
        if(panel!=null)resetFollowTranslations();
        if(panel!=null){windows.removeView(panel);panel=null;}
        for(RecorderRoundImage.Row row:rows){if(!row.operations.isRecycled())row.operations.recycle();if(row.note!=null&&!row.note.isRecycled())row.note.recycle();}
    }
}
