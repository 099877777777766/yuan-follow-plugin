package com.local.yuanatlas;

import android.graphics.*;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;

/** 胜利/失败共用结算入口；确认结算布局且同帧没有回合标题，才返回可点击的“确定”。 */
final class RecorderVictoryReader {
    static final class Result {
        final PointF confirm;final boolean defeated;
        Result(PointF confirm,boolean defeated){this.confirm=confirm;this.defeated=defeated;}
    }
    static Rect region(Bitmap frame){
        Rect v=GameViewport.detect(frame);
        // 失败页与带“下一层”的胜利页都使用左右双按钮；保留上方奖励标题以区别普通弹窗。
        return new Rect(v.left+Math.round(v.width()*.07f),v.top+Math.round(v.height()*.70f),
            v.left+Math.round(v.width()*.94f),v.top+Math.round(v.height()*.98f));
    }
    static Task<PointF> find(TextRecognizer recognizer,Bitmap frame,Rect overlay){
        return read(recognizer,frame,overlay,false).continueWith(task->task.isSuccessful()&&task.getResult()!=null?task.getResult().confirm:null);
    }
    static Task<Result> read(TextRecognizer recognizer,Bitmap frame,Rect overlay,boolean roundMissing){
        Rect viewport=GameViewport.detect(frame),bounds=region(frame);
        Bitmap crop=CaptureOcrImage.crop(frame,bounds,overlay);
        float scale=Math.max(1,720f/crop.getWidth());Bitmap input;
        try{input=Bitmap.createScaledBitmap(crop,Math.round(crop.getWidth()*scale),Math.round(crop.getHeight()*scale),true);}
        catch(RuntimeException|OutOfMemoryError error){crop.recycle();throw error;}
        Bitmap title;
        try{title=roundMissing?null:RecorderRoundAutoReader.region(frame,overlay);}
        catch(RuntimeException|OutOfMemoryError error){if(input!=crop)input.recycle();crop.recycle();throw error;}
        // 调用方会立即释放整帧，只持有这两张小裁图；仅找到结算候选时额外读取回合。
        try{return recognizer.process(InputImage.fromBitmap(input,0)).continueWithTask(task->{
            Result found=task.isSuccessful()?match(task.getResult(),bounds,viewport,scale):null;
            if(found==null||title==null)return Tasks.forResult(found);
            return RecorderRoundOcr.recognize(recognizer,title).continueWith(round->
                round.isSuccessful()&&round.getResult()==null?found:null);
        }).continueWith(task->{
            try{return task.isSuccessful()?task.getResult():null;}
            finally{if(title!=null)title.recycle();if(input!=crop)input.recycle();crop.recycle();}
        });}catch(RuntimeException|OutOfMemoryError error){if(title!=null)title.recycle();if(input!=crop)input.recycle();crop.recycle();throw error;}
    }
    private static Result match(Text text,Rect bounds,Rect viewport,float scale){
        RectF confirm=null,retry=null,nextFloor=null;boolean rewards=false;
        for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
            // 金色边框偶尔被 OCR 当作竖线；仅去除标点，不能把“再次挑战”当确定。
            String value=line.getText().replaceAll("[\\s\\p{P}\\p{S}]","");Rect box=line.getBoundingBox();if(box==null)continue;
            RectF at=new RectF(bounds.left+box.left/scale,bounds.top+box.top/scale,bounds.left+box.right/scale,bounds.top+box.bottom/scale);
            float y=(at.centerY()-viewport.top)/viewport.height();
            if(value.equals("获得奖励")&&y<.85f)rewards=true;
            if(y<.84f||y>.97f)continue;
            if(value.equals("确定")){if(confirm!=null)return null;confirm=at;}
            if(value.equals("再次挑战"))retry=at;
            if(value.equals("下一层"))nextFloor=at;
        }
        if(confirm==null)return null;
        float x=(confirm.centerX()-viewport.left)/viewport.width();
        // 楼层胜利不能套用“确定必须居中”的旧规则；奖励及右侧下一层共同证明是结算页。
        // 同时出现冲突按钮、缺少布局证据时保持未知，不能仅凭左侧确定就点击。
        if(retry!=null&&nextFloor!=null)return null;
        boolean defeated=sideBySide(confirm,retry,viewport);
        boolean floorVictory=rewards&&sideBySide(confirm,nextFloor,viewport);
        boolean centeredVictory=rewards&&retry==null&&nextFloor==null&&x>.40f&&x<.60f;
        if(!defeated&&!floorVictory&&!centeredVictory)return null;
        // 下一层只用于验证布局；循环始终点击本次“确定”的实际文字位置。
        return new Result(new PointF(confirm.centerX(),confirm.centerY()),defeated);
    }
    private static boolean sideBySide(RectF confirm,RectF right,Rect viewport){
        float x=(confirm.centerX()-viewport.left)/viewport.width();
        return right!=null&&x>.12f&&x<.45f&&right.centerX()>viewport.left+viewport.width()*.55f
            &&Math.abs(right.centerY()-confirm.centerY())<Math.max(confirm.height(),right.height())*1.5f;
    }
}
