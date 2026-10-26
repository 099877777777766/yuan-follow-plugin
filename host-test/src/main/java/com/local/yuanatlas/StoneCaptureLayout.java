package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONObject;

/** 固定背包控件的已确认位置；OCR漏字时仍须用当前图像核验，不能无条件沿用旧坐标。 */
final class StoneCaptureLayout {
    final Anchor title,main,aux,footer;
    final int recovered;
    StoneCaptureLayout(Bitmap bitmap,JSONArray lines,Rect overlay,StoneCaptureLayout previous)throws Exception{
        JSONObject t=null,m=null,a=null,f=null;
        JSONArray controls=controlLines(lines,bitmap.getWidth(),overlay);
        for(int i=0;i<controls.length();i++){
            JSONObject line=controls.getJSONObject(i);String text=StoneCaptureFrame.clean(line.getString("text"));
            double y=line.getDouble("y"),x=StoneCaptureFrame.center(line);
            if(StoneCaptureFrame.backpackTitle(text)&&y<bitmap.getHeight()*.35)t=line;
            // 标签只能来自页面上方，列表正文或浮窗文字不能覆盖真实标签的位置。
            if(y<bitmap.getHeight()*.4&&text.length()==2){
                if(text.startsWith("主")&&x<bitmap.getWidth()*.5)m=line;
                if(text.startsWith("辅")&&x>bitmap.getWidth()*.2&&x<bitmap.getWidth()*.8)a=line;
            }
            if(StoneCaptureFrame.decomposeLabel(text)&&y>bitmap.getHeight()*.65&&x<bitmap.getWidth()*.3)f=line;
        }
        title=resolve("标题",t,previous==null?null:previous.title,bitmap,overlay);
        main=resolve("主星标签",m,previous==null?null:previous.main,bitmap,overlay);
        aux=resolve("辅星标签",a,previous==null?null:previous.aux,bitmap,overlay);
        footer=resolve("分解按钮",f,previous==null?null:previous.footer,bitmap,overlay);
        recovered=(t==null?1:0)+(m==null?1:0)+(a==null?1:0)+(f==null?1:0);
    }
    /** 只为定位合并被 OCR 拆开的固定控件；不改写原始文字，也不合并背包物品。 */
    private static JSONArray controlLines(JSONArray lines,int width,Rect overlay)throws Exception{
        JSONArray result=new JSONArray();java.util.List<JSONObject> parts=new java.util.ArrayList<>();
        for(int i=0;i<lines.length();i++){
            JSONObject line=lines.getJSONObject(i);
            double x=line.getDouble("x"),y=line.getDouble("y"),w=line.getDouble("width"),h=line.getDouble("height");
            // 补识别也会直接使用布局定位，因此这里独立排除浮窗内的标题/按钮文字。
            if(w<=0||h<=0||overlay!=null&&x<overlay.right&&x+w>overlay.left&&y<overlay.bottom&&y+h>overlay.top)continue;
            result.put(line);String text=StoneCaptureFrame.clean(line.getString("text"));
            if(text.matches("[星石背包主辅分解]{1,3}"))parts.add(line);
        }
        parts.sort(java.util.Comparator.comparingDouble(p->p.optDouble("x")));
        for(JSONObject first:parts){
            String text=StoneCaptureFrame.clean(first.getString("text"));
            double left=first.getDouble("x"),top=first.getDouble("y"),right=left+first.getDouble("width"),bottom=top+first.getDouble("height");
            double h=first.getDouble("height"),cy=top+h/2;
            for(JSONObject next:parts){
                double nx=next.getDouble("x"),ny=next.getDouble("y"),nh=next.getDouble("height");
                if(nx<right||nx-right>Math.min(width*.035,h*.9)||Math.abs(ny+nh/2-cy)>Math.min(h,nh)*.45||nh/h<.6||nh/h>1.65)continue;
                String joined=text+StoneCaptureFrame.clean(next.getString("text"));
                // 只接受合法控件的前缀，禁止把分散的“星石”与远处“背包”拼起来。
                if(!"星石背包".startsWith(joined)&&!"主星".startsWith(joined)&&!"辅星".startsWith(joined)&&!"分解".startsWith(joined))continue;
                text=joined;right=nx+next.getDouble("width");top=Math.min(top,ny);bottom=Math.max(bottom,ny+nh);
                if(text.equals("星石背包")||text.equals("主星")||text.equals("辅星")||text.equals("分解")){
                    result.put(new JSONObject().put("text",text).put("x",left).put("y",top).put("width",right-left).put("height",bottom-top));
                    break;
                }
            }
        }
        return result;
    }
    private static Anchor resolve(String label,JSONObject line,Anchor old,Bitmap bitmap,Rect overlay)throws Exception{
        if(line!=null)return new Anchor(bitmap,line);
        if(old!=null&&old.matches(bitmap,overlay))return old;
        throw new StoneCaptureFrame.LayoutException("未识别到"+label+"，且当前图像无法核验原位置");
    }
    static final class Anchor {
        private static final int COLS=48,ROWS=16;
        final JSONObject line;
        final int width,height;
        final Rect bounds;
        final int[] samples;
        Anchor(Bitmap bitmap,JSONObject value)throws Exception{
            line=new JSONObject(value.toString());width=bitmap.getWidth();height=bitmap.getHeight();
            int pad=Math.max(2,(int)(width*.008));
            bounds=new Rect(Math.max(0,(int)line.getDouble("x")-pad),Math.max(0,(int)line.getDouble("y")-pad),
                Math.min(width,(int)Math.ceil(line.getDouble("x")+line.getDouble("width"))+pad),
                Math.min(height,(int)Math.ceil(line.getDouble("y")+line.getDouble("height"))+pad));
            samples=sample(bitmap);
        }
        private int[] sample(Bitmap bitmap){
            int[] values=new int[COLS*ROWS];
            for(int y=0;y<ROWS;y++)for(int x=0;x<COLS;x++)
                values[y*COLS+x]=bitmap.getPixel(bounds.left+x*(bounds.width()-1)/(COLS-1),bounds.top+y*(bounds.height()-1)/(ROWS-1));
            return values;
        }
        boolean matches(Bitmap bitmap,Rect overlay){
            if(width!=bitmap.getWidth()||height!=bitmap.getHeight()||bounds.isEmpty()||overlay!=null&&Rect.intersects(bounds,overlay))return false;
            int[] now=sample(bitmap);long total=0;int changed=0,min=255,max=0;
            for(int i=0;i<samples.length;i++){
                int error=0,light=0;
                for(int shift:new int[]{16,8,0}){int old=(samples[i]>>shift)&255;error+=Math.abs(old-((now[i]>>shift)&255));light+=old;}
                total+=error;if(error>90)changed++;min=Math.min(min,light/3);max=Math.max(max,light/3);
            }
            // 空白背景不能充当标签证据；同时约束平均误差和变化像素占比，拒绝变暗弹窗、切页和遮挡。
            return max-min>=35&&total/(double)(samples.length*3)<=10&&changed<=samples.length*.12;
        }
    }
}
