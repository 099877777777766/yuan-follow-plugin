package com.local.yuanatlas;

import android.graphics.*;
import com.google.android.gms.tasks.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;
import java.util.*;

/** 只检查选中的我方卡列；黑白卡用像素，退场文字只对无明确结果的小区域 OCR。 */
final class RecorderAllyDeathVision {
    static final int UNKNOWN=-1,ALIVE=0,DEAD=1;
    static final class Reading {
        final int[] states={UNKNOWN,UNKNOWN,UNKNOWN,UNKNOWN,UNKNOWN};
        final String[] reasons=new String[5];
    }
    /** 仅存本次检查刚确认的文字小图；新截图完全相同才复用，不做相似图猜测。 */
    static final class TextCache {
        private Bitmap image;private int column=-1,width;private String reason;private boolean closed;
        int width(int slot){return !closed&&image!=null&&column==slot?width:180;}
        boolean matches(int slot,Bitmap candidate){return !closed&&image!=null&&column==slot&&image.sameAs(candidate);}
        void remember(int slot,Bitmap text,String value){
            if(closed)return;Bitmap copy;try{copy=text.copy(Bitmap.Config.ARGB_8888,false);}catch(OutOfMemoryError error){return;}
            if(image!=null)image.recycle();image=copy;column=slot;width=text.getWidth();reason=value;
        }
        void close(){closed=true;if(image!=null){image.recycle();image=null;}}
    }
    static Task<Reading> read(TextRecognizer recognizer,Bitmap frame,Rect overlay,RecorderFollowVision.Layout layout,int mask){
        return read(recognizer,frame,overlay,layout,mask,null);
    }
    static Task<Reading> read(TextRecognizer recognizer,Bitmap frame,Rect overlay,RecorderFollowVision.Layout layout,int mask,TextCache cache){
        Reading result=new Reading();Task<Reading> task=Tasks.forResult(result);
        for(int column=0;column<5;column++)if((mask&(1<<column))!=0){final int slot=column;
            task=task.continueWithTask(previous->{
                try{
                // 任一所选密探阵亡即可结束本次采样，剩余卡列无需继续 OCR。
                for(int state:result.states)if(state==DEAD)return Tasks.forResult(result);
                // 布局来自本回合定位，尺寸变化或浮窗覆盖时保留未知，不能把遮挡判成阵亡。
                if(frame.getWidth()!=layout.width||frame.getHeight()!=layout.height)return Tasks.forResult(result);
                float cell=layout.cards.width()/5;Rect bounds=new Rect(Math.round(layout.cards.left+cell*(slot+.035f)),Math.round(layout.cards.top),Math.round(layout.cards.left+cell*(slot+.965f)),Math.round(layout.cards.bottom));
                if(bounds.width()<35||bounds.height()<90||bounds.left<0||bounds.top<0||bounds.right>frame.getWidth()||bounds.bottom>frame.getHeight()||overlay!=null&&Rect.intersects(overlay,bounds))return Tasks.forResult(result);
                Bitmap card=Bitmap.createBitmap(frame,bounds.left,bounds.top,bounds.width(),bounds.height());
                int state;try{state=pixels(card);}finally{card.recycle();}result.states[slot]=state;
                if(state!=UNKNOWN){result.reasons[slot]=state==DEAD?"黑白立绘":"存活卡牌";return Tasks.forResult(result);}
                // 只保留卡牌中部的竖排文字，排除上下菱形装饰；保留原色和字形边缘，压缩图不做硬二值化。
                // 识别范围限定本列，避免把旁边密探的“退场”归给该列。
                Rect region=new Rect(Math.round(layout.cards.left+cell*(slot+.22f)),Math.round(layout.cards.top+layout.cards.height()*.30f),Math.round(layout.cards.left+cell*(slot+.78f)),Math.round(layout.cards.top+layout.cards.height()*.64f));
                return readText(recognizer,frame,region,cache==null?180:cache.width(slot),true,result,slot,cache);
                }catch(Exception|OutOfMemoryError error){result.reasons[slot]="卡牌检查失败";return Tasks.forResult(result);}
            });
        }
        return task;
    }
    private static Task<Reading> readText(TextRecognizer reader,Bitmap frame,Rect region,int width,boolean retry,Reading result,int slot,TextCache cache){
        Bitmap text=Bitmap.createBitmap(width,Math.max(1,Math.round(region.height()*width/(float)region.width())),Bitmap.Config.ARGB_8888);
        try{
            new Canvas(text).drawBitmap(frame,region,new Rect(0,0,text.getWidth(),text.getHeight()),new Paint(Paint.FILTER_BITMAP_FLAG));
            if(cache!=null&&cache.matches(slot,text)){result.states[slot]=DEAD;result.reasons[slot]=cache.reason;text.recycle();return Tasks.forResult(result);}
            return reader.process(InputImage.fromBitmap(text,0)).continueWithTask(ocr->{
                try{if(ocr.isSuccessful()){
                    String words=ocr.getResult().getText().replaceAll("\\s","");
                    result.reasons[slot]=words.isEmpty()?"未读到退场文字":"未确认退场文字";
                    if(words.contains("退场")||words.contains("退場")||words.contains("放逐")){result.states[slot]=DEAD;result.reasons[slot]=words.contains("放逐")?"放逐":"退场";if(cache!=null)cache.remember(slot,text,result.reasons[slot]);}
                }else result.reasons[slot]="文字识别失败";}finally{text.recycle();}
                // 缩放/JPEG 抗锯齿会让“退”误读成形近字，只在未确认时换一个小尺度再读一次。
                // 必须完整读到指定词，不用“返/週”等模糊匹配触发撤退。
                if(ocr.isSuccessful()&&result.states[slot]==UNKNOWN&&retry){
                    try{return readText(reader,frame,region,width==180?128:180,false,result,slot,cache);}catch(Exception|OutOfMemoryError error){result.reasons[slot]="文字复核失败";}
                }
                return Tasks.forResult(result);
            });
        }catch(Exception|OutOfMemoryError error){text.recycle();return Tasks.forResult(result);}
    }
    private static int pixels(Bitmap card){
        int w=card.getWidth(),h=card.getHeight(),neutral=0,total=0,gold=0,red=0,base=0;double sum=0,square=0;
        int[] colors=new int[w*h];card.getPixels(colors,0,w,0,0,w,h);
        for(int y=(int)(h*.10f);y<h*.88f;y+=Math.max(1,h/220))for(int x=(int)(w*.10f);x<w*.9f;x+=Math.max(1,w/90)){
            int c=colors[y*w+x],r=Color.red(c),g=Color.green(c),b=Color.blue(c),max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
            if(max-min<=Math.max(13,max*.11f))neutral++;float light=(r+g+b)/3f;sum+=light;square+=light*light;total++;
        }
        for(int y=(int)(h*.91f);y<h;y+=Math.max(1,h/260))for(int x=(int)(w*.08f);x<w*.92f;x+=Math.max(1,w/90)){
            int c=colors[y*w+x],r=Color.red(c),g=Color.green(c),b=Color.blue(c);base++;
            if(r>100&&g>65&&g>b*1.2f&&r>b*1.3f)gold++;
            if(r>100&&r>g*1.35f&&r>b*1.25f)red++;
        }
        if(total==0||base==0)return UNKNOWN;double deviation=Math.sqrt(Math.max(0,square/total-Math.pow(sum/total,2)));
        // 白发/暗色立绘仍有彩色边框和血条。退场卡没有这些结构，交给文字识别，不能按“没血”判死。
        if(neutral>total*.92f&&deviation>18&&gold<base*.025f&&red<base*.01f)return DEAD;
        if(deviation>15&&neutral<total*.90f&&(red>base*.025f||gold>base*.15f))return ALIVE;
        return UNKNOWN;
    }
}
