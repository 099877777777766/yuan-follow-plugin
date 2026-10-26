package com.local.yuanatlas;

import android.graphics.*;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;

/** 退出只在不满足条件后启用，确认按钮必须与离开战斗的文字同时出现。 */
final class RecorderBattleExitReader {
    static final class Screen {final PointF confirm;final boolean dialog,battle;Screen(PointF point,boolean dialog,boolean battle){confirm=point;this.dialog=dialog;this.battle=battle;}}
    static Task<Screen> read(TextRecognizer recognizer,Bitmap frame,Rect overlay){
        Rect viewport=GameViewport.detect(frame);
        return readRegion(recognizer,frame,overlay,viewport).continueWithTask(task->{
            // 低分辨率弹窗裁去黑边后，OCR 可能合并按钮或丢失细字。
            // 裁图没找到按钮时只补查一次完整帧，仍须同时匹配撤退正文和右侧确定。
            Rect full=new Rect(0,0,frame.getWidth(),frame.getHeight());
            if(task.isSuccessful()&&task.getResult().confirm==null&&!viewport.equals(full))
                return readRegion(recognizer,frame,overlay,full).continueWith(fallback->
                        fallback.isSuccessful()&&fallback.getResult().confirm!=null?fallback.getResult():task.getResult());
            return task;
        });
    }
    private static Task<Screen> readRegion(TextRecognizer recognizer,Bitmap frame,Rect overlay,Rect viewport){
        Bitmap clean=CaptureOcrImage.crop(frame,viewport,overlay);
        try{return recognizer.process(InputImage.fromBitmap(clean,0)).continueWith(task->{
            try{
                if(!task.isSuccessful())return new Screen(null,false,true);
                String all=task.getResult().getText().replaceAll("\\s","");
                boolean context=all.matches("(?s).*(退出|离开|撤退|放弃|结束战斗).*"),battle=all.contains("回合")||all.contains("波次");
                boolean pageText=false;for(Text.TextBlock block:task.getResult().getTextBlocks())for(Text.Line line:block.getLines()){
                    Rect b=line.getBoundingBox();if(b!=null&&b.centerY()>clean.getHeight()*.2f&&b.centerY()<clean.getHeight()*.95f&&line.getText().replaceAll("\\s","").length()>=2)pageText=true;
                }
                // 黑屏、仅状态栏或加载页不能作为已退出的证据，避免录制提前点击过渡画面。
                battle=battle||!pageText||all.contains("加载中");
                PointF candidate=null;
                if(context)for(Text.TextBlock block:task.getResult().getTextBlocks())for(Text.Line line:block.getLines()){
                    // 金色按钮边框在缩小截图里可能被 OCR 读成“|确定”；只去掉装饰符号，保留文字精确匹配。
                    String text=line.getText().replaceAll("[^\\p{L}\\p{N}]","");Rect b=line.getBoundingBox();
                    // 本游戏撤退弹窗的肯定按钮在右半边；不选正文里的“撤退”或左侧取消区域。
                    if(b==null||b.centerX()<=clean.getWidth()*.5f||b.centerY()<clean.getHeight()*.25f||b.centerY()>clean.getHeight()*.85f)continue;
                    if(text.matches("确定|确认|确认退出|退出战斗|退出|撤退"))candidate=new PointF(viewport.left+b.exactCenterX(),viewport.top+b.exactCenterY());
                }
                return new Screen(candidate,context,battle);
            }finally{if(clean!=frame)clean.recycle();}
        });}catch(Exception|OutOfMemoryError failure){if(clean!=frame)clean.recycle();throw failure;}
    }
}
