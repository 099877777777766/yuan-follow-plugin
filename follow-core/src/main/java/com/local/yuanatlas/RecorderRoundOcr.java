package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import java.util.ArrayList;
import java.util.List;

/** 先定位回合标题，再放大复核细斜杠，避免把 1/30 当成第 130 回合。 */
final class RecorderRoundOcr {
    static Task<RecorderBattleRound.Reading> recognize(TextRecognizer recognizer,Bitmap region){
        return recognize(recognizer,region,null);
    }
    static Task<RecorderBattleRound.Reading> recognize(TextRecognizer recognizer,Bitmap region,java.util.function.Consumer<Rect> located){
        return recognizer.process(InputImage.fromBitmap(region,0)).continueWithTask(first->{
            Text text=first.getResult();
            if(located!=null)located.accept(titleBounds(region,text));
            RecorderBattleRound.Reading reading=RecorderBattleRound.read(text);
            // 两个正整数漏掉斜杠至少会拼成两位数；明确分数及单个数字不必重复 OCR，避免每步增加等待。
            if(reading!=null&&(reading.limit>0||reading.current<10))return Tasks.forResult(reading);
            Bitmap title=title(region,text);
            // 浅色天空与绿色标题对比不足时，OCR 可能整行漏读；仅在未定位到标题时补一次绿色字增强。
            if(title==null)return greenFallback(recognizer,region,located);
            try{
                return recognizer.process(InputImage.fromBitmap(title,0))
                    .continueWith(second->reconcile(reading,RecorderBattleRound.read(second.getResult())))
                    .addOnCompleteListener(done->title.recycle());
            }catch(Exception error){title.recycle();throw error;}
        });
    }
    private static Task<RecorderBattleRound.Reading> greenFallback(TextRecognizer recognizer,Bitmap region,java.util.function.Consumer<Rect> located){
        Bitmap contrast=greenText(region);
        try{return recognizer.process(InputImage.fromBitmap(contrast,0)).continueWithTask(task->{
            Text text=task.getResult();if(located!=null)located.accept(titleBounds(region,text));
            RecorderBattleRound.Reading reading=RecorderBattleRound.read(text);
            if(reading==null||reading.limit>0||reading.current<10)return Tasks.forResult(reading);
            // 多位数字仍复核原图细斜杠，不因增强把 1/30 直接接受为 130。
            Bitmap focused=title(region,text);if(focused==null)return Tasks.forResult(null);
            try{return recognizer.process(InputImage.fromBitmap(focused,0)).continueWith(next->reconcile(reading,RecorderBattleRound.read(next.getResult()))).addOnCompleteListener(done->focused.recycle());}
            catch(RuntimeException|OutOfMemoryError error){focused.recycle();throw error;}
        }).addOnCompleteListener(done->contrast.recycle());}
        catch(RuntimeException|OutOfMemoryError error){contrast.recycle();throw error;}
    }
    private static Bitmap greenText(Bitmap region){
        Bitmap contrast=region.copy(Bitmap.Config.ARGB_8888,true);
        try{
        int width=contrast.getWidth(),height=contrast.getHeight();int[] pixels=new int[width*height];
        contrast.getPixels(pixels,0,width,0,0,width,height);
        // 用绿相对红、蓝的优势提取文字，避免把蓝天、白云和白色波次一并增强。
        for(int p=0;p<pixels.length;p++){
            int color=pixels[p],red=(color>>16)&255,green=(color>>8)&255,blue=color&255;
            // 温和拉伸，避免把“回”的内部留白涂满，导致压缩图被读成“1台”。
            int gray=255-Math.max(0,Math.min(255,Math.min(green-red,green-blue)*3));
            pixels[p]=0xff000000|(gray<<16)|(gray<<8)|gray;
        }
        contrast.setPixels(pixels,0,width,0,0,width,height);
        return contrast;}
        catch(RuntimeException|OutOfMemoryError error){contrast.recycle();throw error;}
    }
    /** 只允许完整分数与其漏斜杠形式互相校正；不能凭数字长度或表格行数猜回合。 */
    static RecorderBattleRound.Reading reconcile(RecorderBattleRound.Reading first,RecorderBattleRound.Reading focused){
        if(focused==null)return null;
        if(first==null)return focused;
        if(first.current==focused.current&&first.limit==focused.limit)return focused;
        RecorderBattleRound.Reading fraction=first.limit>0?first:focused;
        RecorderBattleRound.Reading plain=first.limit>0?focused:first;
        if(fraction.limit>0&&plain.limit==0&&(plain.current==fraction.current||
            String.valueOf(plain.current).equals(String.valueOf(fraction.current)+fraction.limit)))return fraction;
        // 两次识别得到不同的完整回合时，此帧不用于翻页或派发手势。
        return null;
    }
    private static Rect titleBounds(Bitmap region,Text text){
        List<Text.Line> lines=new ArrayList<>();for(Text.TextBlock block:text.getTextBlocks())lines.addAll(block.getLines());
        Text.Line anchor=null;
        for(Text.Line line:lines){
            String value=line.getText();
            if(!RecorderBattleRound.LABEL.matcher(value).find()||value.contains("剩余")||value.contains("剩餘")||value.contains(":")||value.contains("："))continue;
            if(anchor!=null)return null;
            anchor=line;
        }
        if(anchor==null||anchor.getBoundingBox()==null)return null;
        Rect bounds=new Rect(anchor.getBoundingBox());
        // 中文和数字可能分块；保留紧邻右侧的完整数字，去掉同一行左侧的波次文字。
        for(Text.Line line:lines){
            Rect other=line.getBoundingBox();if(line==anchor||other==null)continue;
            int height=Math.max(bounds.height(),other.height());
            if(other.left>=bounds.right-height/4&&other.left-bounds.right<=height*4&&Math.abs(bounds.centerY()-other.centerY())<=height*.6f)
                bounds.union(other);
        }
        for(Text.Element element:anchor.getElements())if(RecorderBattleRound.LABEL.matcher(element.getText()).find()&&element.getBoundingBox()!=null){bounds.left=element.getBoundingBox().left;break;}
        int pad=Math.max(2,bounds.height()/2);bounds.inset(-pad,-pad);
        if(!bounds.intersect(0,0,region.getWidth(),region.getHeight()))return null;
        return bounds;
    }
    private static Bitmap title(Bitmap region,Text text){
        Rect bounds=titleBounds(region,text);if(bounds==null)return null;
        Bitmap crop=Bitmap.createBitmap(region,bounds.left,bounds.top,bounds.width(),bounds.height());
        Bitmap scaled=null,result=null;
        try{
            scaled=Bitmap.createScaledBitmap(crop,Math.max(1,Math.round(crop.getWidth()*160f/crop.getHeight())),160,true);
            result=scaled.copy(Bitmap.Config.ARGB_8888,true);
            int width=result.getWidth(),height=result.getHeight();int[] pixels=new int[width*height];
            result.getPixels(pixels,0,width,0,0,width,height);
            // 绿色回合字的绿通道保留细斜杠和抗锯齿；反相拉伸为浅底深字，不作会丢笔画的硬二值化。
            for(int p=0;p<pixels.length;p++){
                int green=(pixels[p]>>8)&255,gray=255-Math.max(0,Math.min(255,(green-50)*255/150));
                pixels[p]=0xff000000|(gray<<16)|(gray<<8)|gray;
            }
            result.setPixels(pixels,0,width,0,0,width,height);
            Bitmap output=result;result=null;return output;
        }finally{
            if(result!=null)result.recycle();if(scaled!=null&&scaled!=crop&&scaled!=region)scaled.recycle();if(crop!=region)crop.recycle();
        }
    }
}
