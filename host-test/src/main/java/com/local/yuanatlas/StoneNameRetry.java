package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 漏字时仅重读名称小区域；两个放大倍率独立读到同一可信星名才补全。 */
final class StoneNameRetry {
    private static boolean level(JSONObject line){return StoneCaptureFrame.clean(line.optString("text")).matches(".*\\d{1,2}级.*");}
    private static double x(JSONObject line){return line.optDouble("x")+line.optDouble("width")/2;}
    private static double y(JSONObject line){return line.optDouble("y");}
    private static double median(List<Double> values){Collections.sort(values);return values.get(values.size()/2);}
    private static boolean inside(JSONObject line,Rect box){return box.contains((int)x(line),(int)(y(line)+line.optDouble("height")/2));}
    private static final class Patch {
        final Rect rect;final JSONObject level;final List<JSONObject> old=new ArrayList<>();
        Patch(Rect r,JSONObject l){rect=r;level=l;}
    }
    static JSONArray recover(Bitmap bitmap,JSONArray lines,TextRecognizer recognizer,Rect overlay)throws Exception{
        int width=bitmap.getWidth();StoneCaptureLayout layout;
        // 必须先有背包结构和足够的完整名称作为几何依据，普通图片或小裁剪不猜网格。
        try{layout=new StoneCaptureLayout(bitmap,lines,overlay,null);}catch(StoneCaptureFrame.LayoutException e){return lines;}
        double top=(y(layout.main.line)+layout.main.line.optDouble("height")/2+y(layout.aux.line)+layout.aux.line.optDouble("height")/2)/2+width*.075;
        double bottom=y(layout.footer.line)-width*.04;
        double safeBottom=y(layout.footer.line)+layout.footer.line.optDouble("height")/2-width*.22;
        List<JSONObject> names=new ArrayList<>(),levels=new ArrayList<>();
        for(int n=0;n<lines.length();n++){
            JSONObject line=lines.getJSONObject(n);
            if(y(line)<top||y(line)+line.optDouble("height")>bottom)continue;
            // 渐隐行不能参与估算字高和名称偏移，否则会改变中间清晰区域的补读裁剪。
            if(!StoneCaptureFrame.trustedCanonical(line.optString("text")).isEmpty()
                &&(y(line)+line.optDouble("height")<=safeBottom||StoneTextVisibility.clearName(bitmap,line.optDouble("x"),y(line),line.optDouble("width"),line.optDouble("height"))))names.add(line);
            if(level(line))levels.add(line);
        }
        List<Double> dx=new ArrayList<>(),dy=new ArrayList<>(),heights=new ArrayList<>();
        for(JSONObject l:levels){
            JSONObject nearest=null;
            for(JSONObject name:names)if(y(name)>y(l)&&y(name)-y(l)<width*.25&&Math.abs(x(name)-x(l))<width*.095
                &&(nearest==null||y(name)<y(nearest)))nearest=name;
            if(nearest!=null){dx.add(x(l)-x(nearest));dy.add(y(nearest)-y(l));heights.add(nearest.optDouble("height"));}
        }
        if(dx.size()<4)return lines;
        double offsetX=median(dx),offsetY=median(dy),textHeight=median(heights);
        List<Patch> patches=new ArrayList<>();
        for(JSONObject l:levels){
            double cx=x(l)-offsetX,ny=y(l)+offsetY;
            if(y(l)-width*.02<top||ny+textHeight>bottom)continue;
            // 到底没有下一屏时，允许清晰末行补读；渐隐/空白仍不能靠推测名称放行。
            if(ny+textHeight>safeBottom&&!StoneTextVisibility.clearName(bitmap,cx-textHeight*1.1,ny,textHeight*2.2,textHeight))continue;
            if(overlay!=null&&cx+width*.105>overlay.left&&cx-width*.105<overlay.right&&ny+textHeight>overlay.top&&y(l)-width*.02<overlay.bottom)continue;
            boolean found=false;
            for(JSONObject name:names)if(Math.abs(x(name)-cx)<width*.065&&Math.abs(y(name)-ny)<width*.05){found=true;break;}
            if(found)continue;
            Rect rect=new Rect((int)(cx-width*.067),(int)(ny-textHeight),(int)(cx+width*.067),(int)Math.min(bottom,ny+textHeight*1.9));
            if(rect.left<0||rect.right>width||rect.top<top||rect.bottom>bottom)continue;
            Patch patch=new Patch(rect,l);
            for(int n=0;n<lines.length();n++)if(inside(lines.getJSONObject(n),rect)&&!level(lines.getJSONObject(n)))patch.old.add(lines.getJSONObject(n));
            // 重试未成功的格子留给后续帧，不能先以“缺星名”占用空间去重编号。
            l.put("nameRetryPending",true);for(JSONObject old:patch.old)old.put("nameRetryPending",true);
            patches.add(patch);
        }
        if(patches.isEmpty())return lines;
        // 每帧最多补读8格，拼成小图统一调用模型；2次模型调用不会随漏字数量增长。
        List<Patch> batch=patches.subList(0,Math.min(8,patches.size()));
        List<JSONObject> first,second;
        try{first=read(bitmap,batch,recognizer,2);second=read(bitmap,batch,recognizer,3);}
        catch(Exception e){return lines;} // 局部补读失败不丢掉已成功的整屏OCR。
        JSONArray result=new JSONArray();List<JSONObject> removed=new ArrayList<>();
        for(int n=0;n<batch.size();n++){
            JSONObject a=first.get(n),b=second.get(n);Patch patch=batch.get(n);
            if(a==null||b==null||!StoneCaptureFrame.trustedCanonical(a.getString("text")).equals(StoneCaptureFrame.trustedCanonical(b.getString("text"))))continue;
            StringBuilder original=new StringBuilder();for(JSONObject old:patch.old){if(original.length()>0)original.append(' ');original.append(old.optString("text"));}
            b.put("originalText",original.toString()).put("nameRetried",true);
            patch.level.remove("nameRetryPending");removed.addAll(patch.old);result.put(b);
        }
        for(int n=0;n<lines.length();n++)if(!removed.contains(lines.getJSONObject(n)))result.put(lines.getJSONObject(n));
        return result;
    }
    private static List<JSONObject> read(Bitmap source,List<Patch> patches,TextRecognizer recognizer,int scale)throws Exception{
        int cellWidth=0,cellHeight=0,pad=16;
        for(Patch patch:patches){cellWidth=Math.max(cellWidth,patch.rect.width()*scale+pad*2);cellHeight=Math.max(cellHeight,patch.rect.height()*scale+pad*2);}
        Bitmap sheet=Bitmap.createBitmap(cellWidth,cellHeight*patches.size(),Bitmap.Config.ARGB_8888);
        try{
            // 保留名称周围的深色背景，白色拼图边框会干扰浅金色字的分割。
            Canvas canvas=new Canvas(sheet);canvas.drawColor(source.getPixel(patches.get(0).rect.left,patches.get(0).rect.top));Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            for(int n=0;n<patches.size();n++){
                Rect r=patches.get(n).rect;
                canvas.drawBitmap(source,r,new Rect(pad,n*cellHeight+pad,pad+r.width()*scale,n*cellHeight+pad+r.height()*scale),paint);
            }
            Text text=Tasks.await(recognizer.process(InputImage.fromBitmap(sheet,0)),15,TimeUnit.SECONDS);
            List<JSONObject> result=new ArrayList<>();
            for(int n=0;n<patches.size();n++){
                Patch patch=patches.get(n);JSONObject picked=null;boolean conflict=false;JSONArray readings=new JSONArray();
                Rect tile=new Rect(pad,n*cellHeight+pad,pad+patch.rect.width()*scale,n*cellHeight+pad+patch.rect.height()*scale);
                for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
                    Rect box=line.getBoundingBox();if(box==null||!tile.contains(box))continue;
                    readings.put(line.getText());
                    String canonical=StoneCaptureFrame.trustedCanonical(line.getText());if(canonical.isEmpty())continue;
                    if(picked!=null&&!canonical.equals(StoneCaptureFrame.trustedCanonical(picked.optString("text")))){conflict=true;continue;}
                    picked=new JSONObject().put("text",line.getText()).put("x",patch.rect.left+(box.left-pad)/(double)scale)
                        .put("y",patch.rect.top+(box.top-tile.top)/(double)scale).put("width",box.width()/(double)scale).put("height",box.height()/(double)scale);
                }
                // 保存局部模型原文，方便对照两次识别，并与实际写入的星名区分。
                JSONArray evidence=patch.level.optJSONArray("nameRetryReadings");if(evidence==null){evidence=new JSONArray();patch.level.put("nameRetryReadings",evidence);}
                evidence.put(new JSONObject().put("scale",scale).put("texts",readings));
                result.add(conflict?null:picked);
            }
            return result;
        }finally{sheet.recycle();}
    }
}
