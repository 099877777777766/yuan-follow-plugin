package com.local.yuanatlas;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

/** 用户视频三色原帧与真实设置窗口回归；只回放截图，不派发游戏手势。 */
final class RecorderStarColorChecks {
    private static final String[] COLORS={"blue","purple","orange"};
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static RecorderFollowTable table(){
        RecorderFollowTable t=new RecorderFollowTable();t.game="ruYuan";t.tableName="三色检测回归";t.roleName="测试";
        Arrays.fill(t.agents,"测试密探");
        for(int n=1;n<=4;n++){RecorderFollowTable.Row r=new RecorderFollowTable.Row();r.round=n;r.label=n+"回合";t.rows.add(r);}return t;
    }
    private static View find(View v,String label){
        if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())||v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return v;
        if(v instanceof ViewGroup)for(int n=0;n<((ViewGroup)v).getChildCount();n++){View found=find(((ViewGroup)v).getChildAt(n),label);if(found!=null)return found;}return null;
    }
    private static void press(Dialog d,String label){View v=find(d.findViewById(android.R.id.content),label);check(v!=null,"missing control "+label);v.performClick();}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static Dialog picker(RecorderConditionSettingsView editor)throws Exception{return (Dialog)field(field(editor,"picker"),"dialog");}
    private static void select(Instrumentation i,Dialog owner,RecorderConditionSettingsView editor,String color,int row,boolean confirm)throws Exception{
        Dialog[] child={null};
        i.runOnMainSync(()->{try{press(owner,RecorderConditionVision.starName(color)+"选择回合");child[0]=picker(editor);}catch(Exception e){throw new RuntimeException(e);}});
        i.waitForIdleSync();
        i.runOnMainSync(()->{
            View body=child[0].findViewById(android.R.id.content);
            check(find(body,RecorderConditionVision.starName(color)+"检测 · 选择回合")!=null,"correct picker title "+color);
            check(find(body,"点击整行可多选，同一回合的续行会一起选中。同回合改选星色将替换原要求。")!=null,"three-color conflict hint");
            if(row<0)press(child[0],"清空检测回合");
            else{View v=body.findViewWithTag("condition-row-"+row);check(v!=null,"visible row "+row);v.performClick();}
            press(child[0],confirm?"确定选择检测回合":"取消选择检测回合");
        });i.waitForIdleSync();
    }
    private static void ui(Instrumentation i)throws Exception{
        Activity activity=i.startActivitySync(new Intent(i.getTargetContext(),com.yuan.follow.demo.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        RecorderFollowTable table=table();Dialog[] owner={null};RecorderConditionSettingsView[] editor={null};
        String prefName="star-colors-"+System.nanoTime();Context base=i.getTargetContext();
        Context isolated=new ContextWrapper(base){@Override public SharedPreferences getSharedPreferences(String ignored,int mode){return base.getSharedPreferences(prefName,mode);}};
        try{
            i.runOnMainSync(()->{activity.setContentView(new LinearLayout(activity));owner[0]=new Dialog(activity);LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
                editor[0]=new RecorderConditionSettingsView(activity,body,table,new RecorderRoundConditions(),owner[0],()->RecorderOverlayTheme.load(activity));
                owner[0].setContentView(body);owner[0].show();editor[0].applyTheme();});i.waitForIdleSync();
            for(int n=0;n<3;n++)select(i,owner[0],editor[0],COLORS[n],n,true);
            i.runOnMainSync(()->{for(int n=0;n<3;n++){TextView summary=(TextView)find(owner[0].findViewById(android.R.id.content),COLORS[n]+"检测回合摘要");check(summary!=null&&summary.getText().toString().equals("第 "+(n+1)+" 回合"),"summary "+COLORS[n]);}});
            RecorderRoundConditions[] saved={null};i.runOnMainSync(()->saved[0]=editor[0].read());saved[0].save(isolated,table);
            RecorderRoundConditions loaded=RecorderRoundConditions.load(isolated,table);check(loaded.starTargets.equals(saved[0].starTargets)&&loaded.starTargets.size()==3,"save and reload all three colors without Zhuge");
            // 内层取消、同回合改色和清空均通过真实按钮操作验证。
            select(i,owner[0],editor[0],"blue",-1,false);
            i.runOnMainSync(()->check(editor[0].read().starTargets.equals(loaded.starTargets),"cancel keeps blue"));
            select(i,owner[0],editor[0],"blue",1,true);
            i.runOnMainSync(()->check(editor[0].read().roundsForStar("purple").isEmpty()&&"blue".equals(editor[0].read().starAt(2)),"blue replaces purple on same round"));
            select(i,owner[0],editor[0],"blue",-1,true);
            i.runOnMainSync(()->check(editor[0].read().starTargets.size()==1&&"orange".equals(editor[0].read().starAt(3)),"clear blue preserves orange"));
            check(RecorderRoundConditions.load(isolated,table).starTargets.equals(loaded.starTargets),"unsaved draft cannot modify persisted rules");
            // 再次打开使用持久化结果，验证蓝、紫、橙摘要都能恢复。
            i.runOnMainSync(()->{owner[0].dismiss();owner[0]=new Dialog(activity);LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
                editor[0]=new RecorderConditionSettingsView(activity,body,table,loaded,owner[0],()->RecorderOverlayTheme.load(activity));owner[0].setContentView(body);owner[0].show();editor[0].applyTheme();
                for(int n=0;n<3;n++)check(((TextView)find(body,COLORS[n]+"检测回合摘要")).getText().toString().equals("第 "+(n+1)+" 回合"),"reopened summary "+COLORS[n]);});i.waitForIdleSync();
            Bitmap shot=i.getUiAutomation().takeScreenshot();check(shot!=null,"settings screenshot");File dir=new File(base.getExternalFilesDir(null),"star-colors");dir.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(dir,"settings.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{shot.recycle();}
            RecorderRoundConditions invalid=loaded.copy();invalid.starTargets.put(1,"green");try{invalid.validate(table);throw new AssertionError("invalid color allowed");}catch(IllegalArgumentException expected){}
        }finally{i.runOnMainSync(()->{if(owner[0]!=null)owner[0].dismiss();activity.finish();});base.deleteSharedPreferences(prefName);}
    }
    private static void verifyCheck(Instrumentation i,Bitmap frame,RecorderFollowVision.Layout layout,String target,String expected)throws Exception{
        RecorderRoundConditions rules=new RecorderRoundConditions();if(target!=null)rules.starTargets.put(1,target);rules.validate(table());
        RecorderConditionCheck checker=new RecorderConditionCheck();CountDownLatch done=new CountDownLatch(1);String[] result={null};int[] count={0};
        try{
            i.runOnMainSync(()->checker.start(1,rules,table(),layout,cb->{count[0]++;cb.onSuccess(frame.copy(Bitmap.Config.ARGB_8888,false));},()->null,()->true,s->{},()->{result[0]="pass";done.countDown();},s->{result[0]="reject";done.countDown();},s->{result[0]="unknown";done.countDown();}));
            check(done.await(8,TimeUnit.SECONDS),"check timeout");check(expected.equals(result[0]),"target="+target+" expected="+expected+" actual="+result[0]);
            if(!expected.equals("unknown"))check(count[0]==(target==null?0:2),"two consistent samples, or no capture when disabled");
        }finally{i.runOnMainSync(checker::stop);}
    }
    /** 与正式跟打相同：特效遮住卡框时，使用当前游戏区域的比例兜底。 */
    private static RecorderFollowVision.Layout layout(Bitmap frame){
        Rect viewport=GameViewport.detect(frame);RecorderFollowVision.Layout result=RecorderFollowVision.locate(frame,viewport);
        return result==null?RecorderFollowVision.standard(frame,viewport):result;
    }
    static String run(Instrumentation i)throws Exception{
        ui(i);
        for(String color:COLORS){
            Bitmap frame=RecorderFollowChecks.fixture(i,"star-colors/"+color+".png");
            try{
                RecorderFollowVision.Layout layout=layout(frame);
                for(int width:new int[]{448,640,1280}){
                    Bitmap scaled=Bitmap.createScaledBitmap(frame,width,Math.round(frame.getHeight()*width/(float)frame.getWidth()),true);
                    try{RecorderFollowVision.Layout scaledLayout=layout(scaled);check(color.equals(RecorderConditionVision.read(scaled,null,scaledLayout,-1,true,false).star),"video color "+color+" width="+width);}finally{if(scaled!=frame)scaled.recycle();}
                }
                for(String target:COLORS)verifyCheck(i,frame,layout,target,color.equals(target)?"pass":"reject");
                verifyCheck(i,frame,layout,null,"pass");
                check(RecorderConditionVision.read(frame,new Rect(0,0,frame.getWidth(),frame.getHeight()/3),layout,-1,true,false).star==null,"covered star stays unknown");
                if(color.equals("blue")){Bitmap blank=Bitmap.createBitmap(frame.getWidth(),frame.getHeight(),Bitmap.Config.ARGB_8888);try{verifyCheck(i,blank,layout,"blue","unknown");}finally{blank.recycle();}}
            }finally{frame.recycle();}
        }
        return "PASS native blue/purple/orange selection, titles, summaries, conflict replacement, clear/cancel, save/reload/reopen, invalid color rejection\nPASS user video at 25s purple / 70s blue / 105s orange, 448/640/1280 widths, all 9 target/observed combinations, two-sample confirmation, disabled skips capture, covered/blank stays unknown";
    }
}
