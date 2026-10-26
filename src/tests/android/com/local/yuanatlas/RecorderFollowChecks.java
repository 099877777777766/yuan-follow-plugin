package com.local.yuanatlas;

import android.app.*;
import android.graphics.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/** 使用真实截图、真实加密协议和模拟手势出口验证，不向真实游戏发送测试操作。 */
final class RecorderFollowChecks {
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static byte[] asset(Instrumentation i,String name)throws Exception{
        InputStream stream;try{stream=i.getContext().getAssets().open(name);}catch(Exception e){stream=i.getContext().getAssets().open("recorder-rounds/"+name);}
        try(InputStream input=stream){return input.readAllBytes();}
    }
    static Bitmap fixture(Instrumentation i,String name)throws Exception{byte[] bytes=asset(i,name);return BitmapFactory.decodeByteArray(bytes,0,bytes.length);}
    static String run(Instrumentation i)throws Exception{
        String code=new String(asset(i,"follow-runner-legacy-code.txt"),java.nio.charset.StandardCharsets.UTF_8);
        JSONArray keys=new JSONArray().put("12345678-1234-4567-89ab-123456789abc");
        RecorderFollowTable table=RecorderFollowTable.decode(code,keys);
        String appCode=new String(asset(i,"follow-runner-app-code.txt"),java.nio.charset.StandardCharsets.UTF_8);
        check(RecorderFollowTable.decode(appCode,new JSONArray()).roleName.equals("晴月"),"cross-device code requires no user key");
        android.content.Context noKeys=new android.content.ContextWrapper(i.getTargetContext()){
            @Override public File getNoBackupFilesDir(){throw new AssertionError("new follow code must never access device key storage");}
        };
        check(RecorderFollowTable.decode(noKeys,appCode).rows.size()==11,"native cross-device decoding bypasses device storage");
        check(table.roleName.equals("晴月")&&table.headerNote.equals("绿三黄五")&&table.rows.size()==11,"decoded header/role/rows");
        check(RecorderFollowTable.embedded(asset(i,"follow-runner-image.bin")).equals(code),"original image binary code");
        try{RecorderFollowTable.decode(code,new JSONArray().put("87654321-1234-4567-89ab-123456789abc"));throw new AssertionError("wrong key accepted");}catch(IllegalArgumentException expected){}
        byte[] tampered=android.util.Base64.decode(code.substring(6),android.util.Base64.URL_SAFE);tampered[40]^=1;
        try{RecorderFollowTable.decode("YRAF1:"+android.util.Base64.encodeToString(tampered,11),keys);throw new AssertionError("tampered code accepted");}catch(IllegalArgumentException expected){}
        List<RecorderFollowTable.Step> steps=table.steps(3);check(steps.size()==6,"two custom rows belong to round 3");
        int[] columns={4,0,1,2,3,4};String[] actions={"A","↑","↓","圈","A","A"};
        for(int n=0;n<6;n++)check(steps.get(n).column==columns[n]&&steps.get(n).action.equals(actions[n]),"ordered step "+n);
        check(table.steps(9).size()==1,"next numbered round separate");
        check(RecorderFollowSettings.route(1,5,5)==-1&&RecorderFollowSettings.route(5,1,5)==1&&RecorderFollowSettings.route(5,4,5)==-1,"cyclic shortest target route");
        RecorderFollowSettings invalid=new RecorderFollowSettings();invalid.before[0]=-1;try{invalid.validate();throw new AssertionError("negative delay");}catch(IllegalArgumentException expected){}
        StringBuilder report=new StringBuilder("PASS codec, authentication, continuation order, cyclic targets, settings limits");
        for(String name:new String[]{"unlimited-3.jpg","limited-9.jpg","limited-10.jpg"}){
            Bitmap original=fixture(i,name);
            for(int width:new int[]{1280,640,416}){
                Bitmap frame=Bitmap.createScaledBitmap(original,width,Math.round(original.getHeight()*width/(float)original.getWidth()),true);
                RecorderFollowVision.Layout layout=RecorderFollowVision.locate(frame);check(layout!=null,"card layout "+name+" "+width);
                for(int c=0;c<5;c++){
                    PointF point=RecorderFollowVision.circle(frame,layout,c);boolean expected=name.equals("unlimited-3.jpg")&&c==2;
                    check((point!=null)==expected,"circle detection "+name+" width "+width+" col "+c+" point "+point);
                }
                if(frame!=original)frame.recycle();
            }
            original.recycle();report.append("\nvision ").append(name).append(" at 1280/640/416");
        }
        engine(i,table,report);
        failures(i,table,report);
        return report.toString();
    }
    private static void failures(Instrumentation i,RecorderFollowTable table,StringBuilder report)throws Exception{
        Bitmap frame=fixture(i,"limited-9.jpg");
        // 保留用户取消、离开游戏和实际触点被浮窗遮住的停止检查；动画外观不再是停止条件。
        RecorderFollowTable.Row row=table.rows.get(10);List<RecorderFollowTable.Step> saved=new ArrayList<>(row.steps);
        row.steps.clear();row.steps.add(new RecorderFollowTable.Step(10,2,1,0,"A","none"));
        for(String scenario:new String[]{"overlay-blocked","left-game","cancel-pending"}){
            CountDownLatch done=new CountDownLatch(1);String[] error={""};int[] calls={0};boolean[] focused={true};RecorderFollowRunner[] runner={null};
            RecorderFollowSettings settings=new RecorderFollowSettings();settings.motionExtraWait=0;settings.detectEnemyDeaths=false;Arrays.fill(settings.before,scenario.equals("cancel-pending")?2000:0);Arrays.fill(settings.after,0);
            i.runOnMainSync(()->{
                runner[0]=new RecorderFollowRunner(table,settings,new RecorderRoundAutoReader.FrameSource(){
                    public String focusedGame(){return focused[0]?"fixture-game":"another-application";}
                    public Rect overlayBounds(){return null;}
                    public void capture(String game,FollowAccessibilityService.GameScreenshotCallback callback){callback.onSuccess(frame.copy(Bitmap.Config.ARGB_8888,false));}
                },new RecorderFollowRunner.Gestures(){
                    public Rect overlay(){return scenario.equals("overlay-blocked")?new Rect(0,0,1280,2754):null;}
                    public void send(PointF from,PointF to,int duration,java.util.function.Consumer<Boolean> callback){calls[0]++;callback.accept(true);}
                },new RecorderFollowRunner.Listener(){
                    public void highlight(RecorderFollowTable.Step step){
                        if(scenario.equals("left-game"))focused[0]=false;
                        if(scenario.equals("cancel-pending"))new Handler(Looper.getMainLooper()).post(()->{runner[0].stop();done.countDown();});
                    }
                    public void status(String text){}
                    public void stopped(String reason){error[0]=reason;done.countDown();}
                });runner[0].start();
            });
            check(done.await(15,TimeUnit.SECONDS),scenario+" timed out");i.runOnMainSync(()->runner[0].stop());Thread.sleep(600);
            check(calls[0]==0,scenario+" sent unexpected gesture");
            if(scenario.equals("overlay-blocked"))check(error[0].contains("挡住"),"overlay failure reason");
            if(scenario.equals("left-game"))check(error[0].contains("离开"),"foreground failure reason");
            report.append("\nPASS zero gestures ").append(scenario);
        }
        row.steps.clear();row.steps.addAll(saved);frame.recycle();
    }
    private static void engine(Instrumentation i,RecorderFollowTable table,StringBuilder report)throws Exception{
        Bitmap three=fixture(i,"unlimited-3.jpg"),nine=fixture(i,"limited-9.jpg");
        List<String> events=Collections.synchronizedList(new ArrayList<>());CountDownLatch done=new CountDownLatch(1);
        RecorderFollowRunner[] runner={null};int[] calls={0};boolean[] advance={false};String[] error={null};
        RecorderFollowSettings settings=new RecorderFollowSettings();settings.motionExtraWait=0;settings.detectEnemyDeaths=false;Arrays.fill(settings.before,0);Arrays.fill(settings.after,0);settings.targets.put("yellow",5);
        i.runOnMainSync(()->{
            RecorderRoundAutoReader.FrameSource source=new RecorderRoundAutoReader.FrameSource(){
                public String focusedGame(){return "fixture-game";}
                public Rect overlayBounds(){return null;}
                public void capture(String game,FollowAccessibilityService.GameScreenshotCallback callback){callback.onSuccess((advance[0]?nine:three).copy(Bitmap.Config.ARGB_8888,false));}
            };
            runner[0]=new RecorderFollowRunner(table,settings,source,new RecorderFollowRunner.Gestures(){
                public Rect overlay(){return null;}
                public void send(PointF from,PointF to,int duration,java.util.function.Consumer<Boolean> callback){
                    int n=calls[0]++;events.add("gesture "+n+" "+from+" → "+to);
                    if(n==0)check(to.x>from.x&&Math.abs(to.y-from.y)<1,"target 1→5 swipes right");
                    if(n==2)check(to.y<from.y,"up gesture");if(n==3)check(to.y>from.y,"down gesture");
                    callback.accept(true);
                }
            },new RecorderFollowRunner.Listener(){
                public void highlight(RecorderFollowTable.Step step){events.add("highlight "+step.row+":"+step.column+":"+step.order+step.action);}
                public void status(String status){if(status.equals("等待下一回合")){if(calls[0]==7)advance[0]=true;else if(calls[0]==8)done.countDown();}}
                public void stopped(String reason){error[0]=reason;done.countDown();}
            });runner[0].start();
        });
        check(done.await(55,TimeUnit.SECONDS),"runner timeout: "+events);
        i.runOnMainSync(()->runner[0].stop());
        check(error[0]==null,"runner stopped: "+error[0]+" "+events);check(calls[0]==8,"exactly target swipe + six round3/custom steps + round9 step: "+events);
        Thread.sleep(1000);check(calls[0]==8,"stopped runner does not continue");
        three.recycle();nine.recycle();report.append("\nPASS simulated gesture sink: ").append(events);
    }
}
