package com.local.yuanatlas;

import android.graphics.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** 百分号只通过原图复核修复，禁止按技能数值或全局替换 96 猜测。 */
final class CapturePercent {
    static String read(TextRecognizer recognizer,Bitmap source,Rect box,String raw)throws Exception{
        if(!raw.matches(".*[0-9]+(?:96|06|9[%％]|％)(?![0-9]).*"))return raw;
        Rect area=new Rect(Math.max(0,box.left-4),Math.max(0,box.top-4),Math.min(source.getWidth(),box.right+4),Math.min(source.getHeight(),box.bottom+4));
        if(area.isEmpty())return raw;
        String a=recognizerText(recognizer,source,area,3),b=recognizerText(recognizer,source,area,4);
        
        String fixed=reconcile(raw,a,b);
        if(!fixed.equals(raw))return fixed;
        return reconcile(raw,recognizerText(recognizer,source,area,5),recognizerText(recognizer,source,area,6));
    }
    private static String recognizerText(TextRecognizer recognizer,Bitmap source,Rect box,int scale)throws Exception{
        Bitmap mask=Bitmap.createBitmap(box.width()+16,box.height()+16,Bitmap.Config.ARGB_8888);mask.eraseColor(Color.WHITE);
        float[] hsv=new float[3];
        // 先提取黄绿色数值；失败后复核整行明度，兼容录屏压缩造成的褪色。
        for(int y=0;y<box.height();y++)for(int x=0;x<box.width();x++){
            Color.colorToHSV(source.getPixel(box.left+x,box.top+y),hsv);
            if(scale>=5 ? hsv[2]>.30 : hsv[0]>=35&&hsv[0]<=85&&hsv[1]>.32&&hsv[2]>.22)mask.setPixel(x+8,y+8,Color.BLACK);
        }
        int factor=scale>=5?scale-2:scale;
        Bitmap large=Bitmap.createScaledBitmap(mask,mask.getWidth()*factor,mask.getHeight()*factor,false);
        try{String value=Tasks.await(recognizer.process(InputImage.fromBitmap(large,0)),30,TimeUnit.SECONDS).getText();return value;}
        finally{large.recycle();mask.recycle();}
    }
    static String reconcile(String raw,String first,String second){
        Pattern percent=Pattern.compile("(?<![0-9.])([0-9]+(?:\\.[0-9]+)?)\\s*[%％]");
        java.util.Set<String> confirmed=new java.util.HashSet<>();Matcher other=percent.matcher(second);while(other.find())confirmed.add(other.group(1));
        Matcher candidate=percent.matcher(first);String result=raw;
        while(candidate.find()){
            String value=candidate.group(1);if(!confirmed.contains(value))continue;
            // 只有复核出的同一个数字加百分号，才能覆盖原识别中的数字加 96。
            result=result.replaceAll("(?<![0-9.])"+Pattern.quote(value)+"(?:96|06|9[%％]|％)(?![0-9])",Matcher.quoteReplacement(value+"%"));
        }
        return result;
    }
}
