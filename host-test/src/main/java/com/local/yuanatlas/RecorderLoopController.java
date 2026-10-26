package com.local.yuanatlas;

import android.app.Dialog;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.function.*;

/** 浮窗循环入口：无录制或未勾选时不点结算确定；条件退出可直接衔接已有录制。 */
final class RecorderLoopController implements RecorderFollowRunner.Loop {
    private final RuYuanCaptureService service;private final String key;private final Supplier<String> focused;
    private final Runnable stopFollow,recorded;private final Consumer<Boolean> recording,passThrough;private final Consumer<String> status;
    private final Handler handler=new Handler(Looper.getMainLooper());private final RecorderMacroPlayer player;
    private RecorderLoopScript script;private RecorderTouchRecorder recorder;private RecorderOverlayTheme theme;private Dialog dialog;
    private LinearLayout controls;private CheckBox enabled;private TextView record,manage;private int generation;
    RecorderLoopController(RuYuanCaptureService service,RecorderFollowTable table,RecorderOverlayTheme theme,Supplier<String> focused,Runnable stopFollow,Runnable recorded,Consumer<Boolean> recording,Consumer<Boolean> passThrough,Supplier<Rect> overlayBounds,Consumer<String> status){
        this.service=service;this.key=RecorderLoopScript.key(table);this.theme=theme;this.focused=focused;this.stopFollow=stopFollow;this.recorded=recorded;this.recording=recording;this.passThrough=passThrough;this.status=status;script=RecorderLoopScript.load(service,key);
        RecorderMacroPlayer.Sink nativeSink=RecorderMacroPlayer.nativeSink(service);
        player=new RecorderMacroPlayer((path,duration,done)->{
            int token=generation;RectF pathBounds=new RectF();path.computeBounds(pathBounds,true);pathBounds.inset(-4,-4);Rect bounds=overlayBounds.get();
            boolean blocked=bounds!=null&&RectF.intersects(pathBounds,new RectF(bounds));if(blocked)passThrough.accept(true);
            Runnable send=()->{if(token!=generation)return;
                if(script==null||!script.gamePackage.equals(focused.get())){passThrough.accept(false);done.accept(false);return;}
                try{nativeSink.send(path,duration,ok->{if(token!=generation)return;passThrough.accept(false);done.accept(ok);});}
                catch(Exception e){passThrough.accept(false);done.accept(false);}
            };if(blocked)handler.postDelayed(send,120);else send.run();
        },focused);
    }
    private int dp(int value){return Math.round(value*service.getResources().getDisplayMetrics().density);}
    LinearLayout controls(){
        controls=new LinearLayout(service);controls.setGravity(Gravity.CENTER_VERTICAL);
        enabled=new CheckBox(service);enabled.setText("循环跟打");enabled.setTextSize(11);enabled.setPadding(0,0,0,0);enabled.setMinHeight(0);enabled.setContentDescription("开启循环跟打");
        controls.addView(enabled,new LinearLayout.LayoutParams(-2,-1));
        record=button("录制操作",this::record);controls.addView(record,new LinearLayout.LayoutParams(dp(76),dp(28)));
        manage=button("",this::edit);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(28),1);p.leftMargin=dp(6);controls.addView(manage,p);
        enabled.setOnCheckedChangeListener((v,checked)->{stopFollow.run();if(checked&&script==null){enabled.setChecked(false);toast("先录制并保存操作，才会点击结算确定并循环");}});
        applyTheme(theme);refresh();return controls;
    }
    private TextView button(String text,Runnable click){TextView button=new TextView(service);button.setText(text);button.setTextSize(11);button.setGravity(Gravity.CENTER);button.setSingleLine(true);button.setOnClickListener(v->click.run());return button;}
    private GradientDrawable surface(){GradientDrawable bg=new GradientDrawable();bg.setColor(theme.soft);bg.setCornerRadius(dp(8));bg.setStroke(dp(1),theme.line);return bg;}
    void applyTheme(RecorderOverlayTheme theme){this.theme=theme;if(controls==null)return;enabled.setTextColor(theme.ink);enabled.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));for(TextView view:new TextView[]{record,manage}){view.setTextColor(theme.accent);view.setBackground(surface());}}
    private void refresh(){if(manage!=null){record.setText(script==null?"录制操作":"重新录制");manage.setText(script==null?"暂无录制":"操作清单 · "+script.actions.size()+" 步");}}
    public boolean enabled(){return enabled!=null&&enabled.isChecked()&&script!=null&&!script.actions.isEmpty();}
    public void stop(){generation++;handler.removeCallbacksAndMessages(null);player.stop();passThrough.accept(false);refresh();}
    public void play(PointF confirm,Runnable complete,Consumer<String> error){
        if(!enabled()){error.accept("未开启循环或未保存录制操作");return;}
        if(!script.gamePackage.equals(focused.get())){error.accept("当前游戏与录制操作不一致");return;}
        try{
            RecorderLoopScript playback=RecorderLoopScript.parse(script.json().toString());
            Rect bounds=((WindowManager)service.getSystemService(android.content.Context.WINDOW_SERVICE)).getMaximumWindowMetrics().getBounds();
            // 确定只作为本次结算的第一步，不写入用户录制，防止每轮重复录入。
            if(confirm!=null){RecorderLoopScript.Action click=new RecorderLoopScript.Action("tap",0,80);click.points.add(new RecorderLoopScript.Point(confirm.x*script.width/bounds.width(),confirm.y*script.height/bounds.height(),0));playback.actions.add(0,click);}
            player.play(playback,bounds.width(),bounds.height(),text->{status.accept(text);manage.setText(text);},()->{refresh();complete.run();},error);
        }catch(Exception e){error.accept("录制操作无法读取："+e.getMessage());}
    }
    private void record(){
        if(Build.VERSION.SDK_INT<30){toast("循环录制需要 Android 11 及以上");return;}
        String game=focused.get();if(game==null||game.isEmpty()){toast("先进入游戏，再点击录制操作；从结算确定之后的页面开始录制");return;}
        stopFollow.run();if(dialog!=null)dialog.dismiss();recording.accept(true);
        recorder=new RecorderTouchRecorder(service,theme,focused,value->{
            recorder=null;recording.accept(false);
            try{value.save(service,key);script=value;recorded.run();refresh();toast("已保存 "+script.actions.size()+" 步，可开启循环跟打");}catch(Exception e){toast(e.getMessage());}
        },()->{recorder=null;recording.accept(false);});
        try{recorder.start(game);recorder.setSystemUiCovered(systemUiCovered);}catch(Exception e){recorder.close();recorder=null;recording.accept(false);toast("无法开始录制："+e.getMessage());}
    }
    private EditText number(LinearLayout parent,String label,long value){
        TextView title=new TextView(service);title.setText(label);title.setTextSize(12);title.setTextColor(theme.muted);parent.addView(title);
        EditText input=new EditText(service);input.setText(Long.toString(value));input.setTextColor(theme.ink);input.setTextSize(13);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setContentDescription(label);
        input.setPadding(dp(10),0,dp(10),0);input.setBackgroundTintList(null);GradientDrawable bg=surface();bg.setColor(theme.bg);input.setBackground(bg);input.setHighlightColor(theme.soft);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(40));params.topMargin=dp(4);params.bottomMargin=dp(8);parent.addView(input,params);return input;
    }
    private boolean systemUiCovered;
    void setSystemUiCovered(boolean covered){
        systemUiCovered=covered;
        if(dialog!=null&&dialog.getWindow().getDecorView().isAttachedToWindow())dialog.getWindow().getDecorView().setVisibility(covered?android.view.View.INVISIBLE:android.view.View.VISIBLE);
        if(recorder!=null)recorder.setSystemUiCovered(covered);
    }
    private void edit(){
        if(script==null){toast("先从结算确定后的页面录制操作");return;}stopFollow.run();
        dialog=new Dialog(service);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        LinearLayout body=new LinearLayout(service);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(16),dp(18),dp(16));GradientDrawable bg=surface();bg.setColor(theme.surface);body.setBackground(bg);
        TextView heading=button("循环操作 · "+script.actions.size()+" 步",()->{});heading.setTextSize(17);heading.setTextColor(theme.accent);body.addView(heading);
        TextView hint=new TextView(service);hint.setText("结算确定或条件退出后，按下面顺序执行。间隔从上一操作结束计时；单位 ms。录制的起点应为退出或结算后的同一页面。");hint.setTextSize(12);hint.setTextColor(theme.muted);hint.setPadding(0,dp(10),0,dp(10));body.addView(hint);
        ScrollView scroll=new ScrollView(service);LinearLayout list=new LinearLayout(service);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);body.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(380),service.getResources().getDisplayMetrics().heightPixels/2)));
        EditText[] delays=new EditText[script.actions.size()],durations=new EditText[script.actions.size()];
        for(int n=0;n<script.actions.size();n++){
            RecorderLoopScript.Action a=script.actions.get(n);RecorderLoopScript.Point first=a.points.get(0),last=a.points.get(a.points.size()-1);
            TextView label=new TextView(service);label.setText((n+1)+". "+a.label()+"  ("+Math.round(first.x)+","+Math.round(first.y)+") → ("+Math.round(last.x)+","+Math.round(last.y)+")");label.setTextColor(theme.ink);label.setPadding(0,dp(10),0,dp(4));list.addView(label);
            delays[n]=number(list,"第 "+(n+1)+" 步前等待",a.delay);durations[n]=number(list,"第 "+(n+1)+" 步持续时间",a.duration);
        }
        EditText tail=number(list,"操作全部完成后等待",script.tailDelay);
        LinearLayout buttons=new LinearLayout(service);buttons.setPadding(0,dp(12),0,0);body.addView(buttons);
        TextView cancel=button("取消",()->dialog.dismiss());cancel.setTextColor(theme.accent);buttons.addView(cancel,new LinearLayout.LayoutParams(0,dp(40),1));
        TextView save=button("保存",()->{
            try{
                RecorderLoopScript draft=RecorderLoopScript.parse(script.json().toString());draft.tailDelay=Long.parseLong(tail.getText().toString());
                for(int n=0;n<draft.actions.size();n++){
                    RecorderLoopScript.Action a=draft.actions.get(n);long duration=Long.parseLong(durations[n].getText().toString());
                    if(duration<1||duration>android.accessibilityservice.GestureDescription.getMaxGestureDuration())throw new IllegalArgumentException("单次手势时长需在系统支持范围 1–60000 ms 内");
                    a.delay=Long.parseLong(delays[n].getText().toString());
                    for(int p=0;p<a.points.size();p++){RecorderLoopScript.Point point=a.points.get(p);a.points.set(p,new RecorderLoopScript.Point(point.x,point.y,Math.round((double)point.time*duration/a.duration)));}a.duration=duration;
                }draft.save(service,key);script=draft;dialog.dismiss();refresh();
            }catch(Exception e){toast(e instanceof NumberFormatException?"请输入有效的整数时间":e.getMessage());}
        });save.setTextColor(theme.on);GradientDrawable primary=surface();primary.setColor(theme.accent);save.setBackground(primary);buttons.addView(save,new LinearLayout.LayoutParams(0,dp(40),1));
        dialog.setContentView(body);dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.show();setSystemUiCovered(systemUiCovered);dialog.getWindow().setLayout(Math.min(service.getResources().getDisplayMetrics().widthPixels-dp(24),dp(420)),-2);
    }
    private void toast(String text){Toast.makeText(service,text,Toast.LENGTH_LONG).show();}
    void close(){stop();if(recorder!=null){recorder.close();recorder=null;}if(dialog!=null)dialog.dismiss();}
}
