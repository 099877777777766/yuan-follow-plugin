package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.mlkit.vision.text.TextRecognizer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 一帧只负责页面定位、像素匹配和品质标注，不执行手势、不写入存档。 */
final class StoneCaptureFrame {
    final JSONObject page;
    final int width,height;
    int screenLeft,screenTop;
    final double top,bottom,mainX,auxX,tabY;
    final double captureBottom;
    final double contentBottom;
    final String kind;
    final int[] pixels;
    static final int COLS=96,ROWS=240;
    final List<JSONObject> names=new ArrayList<>();
    final List<JSONObject> anchors=new ArrayList<>();
    final boolean empty;
    final StoneCaptureLayout layout;

    /** 页面暂时未定位时允许重新读帧，和存盘、内存等实际故障分开处理。 */
    static final class LayoutException extends Exception {LayoutException(String message){super(message);}}
    static boolean decomposeLabel(String text){return clean(text).replaceAll("[^\\p{IsHan}]","").equals("分解");}

    static String clean(String value){
        String from="陽陰貪機軍輔馬祿紅鸞鉞鈴羅臺廟級貴貞殺",to="阳阴贪机军辅马禄红鸾钺铃螺台庙级贵贞杀";
        String s=value.replaceAll("[\\s|丨]","");for(int i=0;i<from.length();i++)s=s.replace(from.charAt(i),to.charAt(i));return s;
    }
    static boolean star(String s){return !canonical(s).isEmpty();}
    /** 录像压缩会把标题识别为“星右背包”“屋石背包”；只容忍四字标题中的一字差异。 */
    static boolean backpackTitle(String text){
        String value=clean(text).replaceAll("[^\\p{IsHan}]","");
        for(int start=0;start+4<=value.length();start++){
            int different=0;for(int n=0;n<4;n++)if(value.charAt(start+n)!="星石背包".charAt(n))different++;
            if(different<=1)return true;
        }
        return false;
    }
    private static final String[] STAR_NAMES="天府,天相,天梁,天同,天机,太阴,太阳,武曲,巨门,贪狼,破军,七杀,紫微,廉贞,左辅,右弼,文昌,文曲,天魁,天钺,禄存,天马,擎羊,陀螺,火星,铃星,地空,地劫,红鸾,三台,解神,天巫,天刑,恩光,天贵,天姚,阴煞,八座".split(",");
    /** 局部补识别只接受合法名称和核实过的词典，不能把一字近似候选作为确切答案。 */
    static String trustedCanonical(String s){
        s=clean(s);
        if(s.length()!=2)return "";
        for(String name:STAR_NAMES)if(name.equals(s))return name;
        // 与前端词典保持一致（src/tests/stone-ocr-dictionary.test.ts 校验），原始文字仍保留。
        String[][] aliases={{"红弯","红鸾"},{"石弼","右弼"},{"t杀","七杀"},{"T杀","七杀"},{"例煞","阴煞"},{"天餓","天钺"},{"天饿","天钺"},{"天俄","天钺"},{"红莺","红鸾"},{"擊羊","擎羊"}};
        for(String[] alias:aliases)if(alias[0].equals(s))return alias[1];
        return "";
    }
    static String canonical(String s){
        s=clean(s);String trusted=trustedCanonical(s);if(!trusted.isEmpty())return trusted;
        if(s.length()!=2)return "";
        int near=0;String candidate="";for(String name:STAR_NAMES){int mismatch=0;for(int i=0;i<2;i++)if(name.charAt(i)!=s.charAt(i))mismatch++;if(mismatch==1){near++;candidate=name;}}
        // 仅唯一的一字差异可作为待核对候选，原文字不改写，前端仍要求人工确认。
        return near==1?candidate:"";
    }
    static StoneCaptureFrame read(Bitmap bitmap,TextRecognizer recognizer)throws Exception{
        return read(bitmap,recognizer,null);
    }
    static StoneCaptureFrame read(Bitmap bitmap,TextRecognizer recognizer,Rect overlay)throws Exception{
        return read(bitmap,recognizer,overlay,null);
    }
    static StoneCaptureFrame read(Bitmap bitmap,TextRecognizer recognizer,Rect overlay,StoneCaptureFrame reference)throws Exception{
        try(GameViewport viewport=GameViewport.open(bitmap)){
            Bitmap game=viewport.image;Rect ignored=viewport.local(overlay);
            ByteArrayOutputStream out=new ByteArrayOutputStream();game.compress(Bitmap.CompressFormat.PNG,100,out);
            // 游戏窗口移动或改变尺寸时，旧像素锚点不能恢复到新位置。
            if(reference!=null&&(reference.screenLeft!=viewport.bounds.left||reference.screenTop!=viewport.bounds.top||reference.width!=game.getWidth()||reference.height!=game.getHeight()))reference=null;
            StoneCaptureFrame frame=new StoneCaptureFrame(game,StoneOcr.recognize(new ByteArrayInputStream(out.toByteArray()),recognizer,null,ignored),ignored,reference);
            frame.screenLeft=viewport.bounds.left;frame.screenTop=viewport.bounds.top;return frame;
        }
    }
    StoneCaptureFrame(Bitmap bitmap,JSONObject data)throws Exception{
        this(bitmap,data,null);
    }
    StoneCaptureFrame(Bitmap bitmap,JSONObject data,Rect overlay)throws Exception{
        this(bitmap,data,overlay,null);
    }
    StoneCaptureFrame(Bitmap bitmap,JSONObject data,Rect overlay,StoneCaptureFrame reference)throws Exception{
        page=data;width=bitmap.getWidth();height=bitmap.getHeight();
        boolean emptyText=false;
        JSONArray lines=data.getJSONArray("lines");
        // 重读/重建帧时必须重新判断可见性，不能继承上一帧的品质或已采集标记。
        for(int i=0;i<lines.length();i++){
            JSONObject line=lines.getJSONObject(i);
            line.remove("orange");line.remove("captureItemId");line.remove("captureAnchorY");line.remove("captureDeferred");line.remove("captureOccluded");
        }
        if(overlay!=null&&!overlay.isEmpty()){
            // Android 11～13 的整屏截图包含常驻浮窗；剔除其真实窗口范围内的文字，预览原图不改动。
            JSONArray gameLines=new JSONArray();
            for(int i=0;i<lines.length();i++){
                JSONObject line=lines.getJSONObject(i);
                double x=line.getDouble("x"),y=line.getDouble("y");
                if(x<overlay.right&&x+line.getDouble("width")>overlay.left&&y<overlay.bottom&&y+line.getDouble("height")>overlay.top)continue;
                gameLines.put(line);
            }
            lines=gameLines;page.put("lines",lines);
        }
        for(int i=0;i<lines.length();i++){
            JSONObject line=lines.getJSONObject(i);String s=clean(line.getString("text"));
            if(s.contains("暂无")||s.contains("没有星石"))emptyText=true;
        }
        layout=new StoneCaptureLayout(bitmap,lines,overlay,reference==null?null:reference.layout);
        JSONObject main=layout.main.line,aux=layout.aux.line,title=layout.title.line,footer=layout.footer.line;
        // 仅记录定位恢复数量，不向原始OCR中伪造缺失文字，星石内容仍全部来自当前帧。
        page.put("layoutRecoveredAnchors",layout.recovered);
        // 容错标题仍须与同一行主辅星标签、下方分解工具栏同时满足几何关系。
        if(title.getDouble("y")+title.getDouble("height")>=Math.min(main.getDouble("y"),aux.getDouble("y"))||center(main)>=center(aux)||footer.getDouble("y")<height*.65)throw new LayoutException("暂未确认星石背包结构，请保持背包页面后重试");
        mainX=center(main)/width;auxX=center(aux)/width;tabY=(main.getDouble("y")+main.getDouble("height")/2)/height;
        // 使用文字中心，避免OCR高度相差1像素时跨越取整边界，令列表区域突变一整档。
        double mainCenterY=main.getDouble("y")+main.getDouble("height")/2,auxCenterY=aux.getDouble("y")+aux.getDouble("height")/2;
        top=(mainCenterY+auxCenterY)/2+width*.075;
        bottom=footer.getDouble("y")+footer.getDouble("height")/2-width*.14;
        // 列表下缘可能渐隐，但不能无条件丢弃完整清晰的末行；安全带内再检查实际字形。
        captureBottom=bottom-width*.08;
        // 采集边界与滚动匹配区域分开：完整末行可延伸至工具栏上方，手势和位移基准不变。
        contentBottom=footer.getDouble("y")-width*.04;
        if(bottom-top<width*.45||Math.abs(main.getDouble("y")-aux.getDouble("y"))>width*.04)throw new LayoutException("背包布局无法确认，请保持竖屏完整画面");
        // 标签文字两侧的金色底用于确认实际选中项，不能仅凭点击成功就认为切换成功。
        double m=tabGold(bitmap,main),a=tabGold(bitmap,aux);
        if(Math.abs(m-a)<.12)throw new LayoutException("暂未确认选中的主辅星标签，请保持背包页面后重试");
        kind=m>a?"main":"auxiliary";
        for(int i=0;i<lines.length();i++){
            JSONObject line=lines.getJSONObject(i);String s=clean(line.getString("text"));double y=line.getDouble("y");
            if(y<top||y+line.getDouble("height")>contentBottom)continue;
            if(star(s)||s.matches(".*\\d{1,2}级.*"))anchors.add(line);
            if(star(s)){
                // 图标完整可见才标注品质；顶部半颗延迟到其他完整帧，不从文字颜色推断品质。
                if(y-width*.18<top){line.put("captureDeferred",true).put("captureOccluded",true);continue;}
                names.add(line);
                boolean visible=fullyVisible(center(line),y-width*.18,y+line.getDouble("height"),overlay,
                    StoneTextVisibility.clearName(bitmap,line.getDouble("x"),y,line.getDouble("width"),line.getDouble("height")));
                if(!visible)line.put("captureOccluded",true);
                if(line.optBoolean("nameRetryPending")||!visible){
                    line.put("captureDeferred",true);continue;
                }
                line.put("orange",orange(bitmap,center(line),y-width*.115,width*.065));
            }
        }
        for(JSONObject level:anchors){
            String text=clean(level.getString("text"));if(!text.matches(".*\\d{1,2}级.*"))continue;
            double y=level.getDouble("y"),x=center(level);boolean paired=false;
            for(JSONObject name:names)if(name.getDouble("y")>y&&name.getDouble("y")-y<width*.25&&Math.abs(center(name)-x)<width*.095){paired=true;break;}
            if(!paired&&y>=top){
                level.put("captureAnchorY",y+width*.16);
                // 名称漏识别可保留待补，但物品区域被裁切/遮挡时不能先占用位置。
                boolean visible=fullyVisible(x-width*.045,y,y+width*.20,overlay);
                if(!visible)level.put("captureOccluded",true);
                if(!level.optBoolean("nameRetryPending")&&visible)
                    level.put("orange",orange(bitmap,x-width*.045,y+width*.065,width*.06));
                else level.put("captureDeferred",true);
            }
        }
        empty=emptyText&&anchors.isEmpty();
        if(anchors.size()<2&&!empty)throw new LayoutException("可见星石定位不足，请保持背包列表可见，不能把识别失败当作空列表");
        pixels=new int[COLS*ROWS];
        for(int y=0;y<ROWS;y++)for(int x=0;x<COLS;x++)pixels[y*COLS+x]=bitmap.getPixel((int)(width*(.075+.85*x/(COLS-1))),Math.min(height-1,(int)(top+(bottom-top)*y/(ROWS-1))));
    }
    static double center(JSONObject line)throws Exception{return line.getDouble("x")+line.getDouble("width")/2;}
    /** 检查整颗星石（图标、等级和名称），浮窗只盖到图标也必须延期。 */
    private boolean fullyVisible(double x,double itemTop,double itemBottom,Rect overlay){
        return fullyVisible(x,itemTop,itemBottom,overlay,false);
    }
    private boolean fullyVisible(double x,double itemTop,double itemBottom,Rect overlay,boolean clearName){
        if(itemTop<top||itemBottom>contentBottom||itemBottom>captureBottom&&!clearName)return false;
        return overlay==null||overlay.isEmpty()||x+width*.105<=overlay.left||x-width*.105>=overlay.right
            ||itemBottom<=overlay.top||itemTop>=overlay.bottom;
    }
    /** 合并同一行的四颗星石，用相邻行间距控制滑动，避免把不同屏幕尺寸当成固定像素。 */
    double rowHeight(){
        List<Double> positions=new ArrayList<>(),gaps=new ArrayList<>();
        for(JSONObject name:names)positions.add(name.optDouble("y"));
        java.util.Collections.sort(positions);double previous=Double.NaN;
        for(double y:positions){
            if(Double.isNaN(previous)){previous=y;continue;}
            if(y-previous<width*.08)continue;
            double gap=y-previous;if(gap>=width*.15&&gap<=width*.35)gaps.add(gap);previous=y;
        }
        java.util.Collections.sort(gaps);
        return gaps.isEmpty()?width*.23:gaps.get(gaps.size()/2);
    }
    /** 只用图标和星名均完整的行计算位移预算；底部只有图标/等级的一行不能充当重叠。 */
    double scanBudget(){
        List<Double> positions=new ArrayList<>(),rows=new ArrayList<>();
        // 内容待补读不等于实际遮挡；仍可定位的完整文字行继续用于位移，避免无谓退回半行慢滑。
        for(JSONObject name:names)if(!name.optBoolean("captureOccluded"))positions.add(name.optDouble("y"));
        java.util.Collections.sort(positions);
        for(double y:positions)if(rows.isEmpty()||y-rows.get(rows.size()-1)>=width*.08)rows.add(y);
        double row=rowHeight();
        // 少量物品或OCR缺行时只作短滑，不能把默认行距当成实际存在的完整行。
        if(rows.size()<3)return Math.min(row*.25,(bottom-top)*.055);
        // 倒数第二行移动后仍须完整处于顶部内侧，额外留0.1行容纳OCR和像素采样误差。
        double overlap=rows.get(rows.size()-2)-top-width*.18-row*.1;
        return Math.max(0,Math.min(3*row,Math.min(overlap,(bottom-top)*.68)));
    }
    private static double tabGold(Bitmap b,JSONObject line)throws Exception{
        int gold=0,n=0;double cx=center(line),cy=line.getDouble("y")+line.getDouble("height")/2;
        for(int y=-4;y<=4;y++)for(int side:new int[]{-1,1})for(int x=0;x<12;x++){
            int px=(int)(cx+side*(line.getDouble("width")/2+b.getWidth()*(.025+x*.003))),py=(int)(cy+y*b.getWidth()*.002);
            if(px<0||py<0||px>=b.getWidth()||py>=b.getHeight())continue;int c=b.getPixel(px,py),r=(c>>16)&255,g=(c>>8)&255,bl=c&255;n++;if(r>155&&g>115&&r>bl*1.2)gold++;
        }return gold/(double)Math.max(1,n);
    }
    static boolean orange(Bitmap b,double cx,double cy,double radius){
        return StoneQuality.orange(b,cx,cy,radius);
    }
    static final class Motion {
        final boolean certain,recoverable;final double distance,error;
        Motion(boolean c,double d,double e){this(c,d,e,false);}
        Motion(boolean c,double d,double e,boolean r){certain=c;distance=d;error=e;recoverable=r;}
    }
    private double nameAgreement(StoneCaptureFrame next,double distance){
        int total=0,matched=0;
        try{for(JSONObject item:next.names){
            double y=item.getDouble("y")+distance,x=center(item);
            // 新进入的底部和顶部半颗不属于共同可见范围，不能计入分母。
            if(y<top+width*.18||y+item.getDouble("height")>bottom)continue;
            total++;String name=canonical(clean(item.getString("text")));
            for(JSONObject old:names)if(Math.abs(center(old)-x)<width*.065&&Math.abs(old.getDouble("y")-y)<width*.025&&canonical(clean(old.getString("text"))).equals(name)){matched++;break;}
        }}catch(Exception e){return 0;}
        int minimum=Math.min(4,Math.min(names.size(),next.names.size()));
        return total>=Math.max(1,minimum)?matched/(double)total:0;
    }
    /** 多列 RGB 图案联合匹配，寻找正负位移；第二个近似解意味着重复纹理，必须暂停。 */
    Motion motionTo(StoneCaptureFrame next){
        if(width!=next.width||height!=next.height||!kind.equals(next.kind)||Math.abs(top-next.top)>3||Math.abs(bottom-next.bottom)>3)return new Motion(false,0,999);
        // 一屏约五至六行，四行位移可能超过半屏；仍至少保留四分之一屏用于交叉核对。
        int limit=ROWS*3/4;
        double best=Double.MAX_VALUE;int shift=0;double[] errors=new double[limit*2+1];
        for(int d=-limit;d<=limit;d++){
            long sum=0;int count=0;
            // 忽略边缘渐隐区域，只比较两帧真正重叠的区域。
            for(int y=12;y<ROWS-12;y+=2){int old=y+d;if(old<12||old>=ROWS-12)continue;
                for(int x=0;x<COLS;x+=2){int p=pixels[old*COLS+x],q=next.pixels[y*COLS+x];sum+=Math.abs(((p>>16)&255)-((q>>16)&255))+Math.abs(((p>>8)&255)-((q>>8)&255))+Math.abs((p&255)-(q&255));count+=3;}}
            double pixelError=sum/(double)Math.max(1,count);double agreement=nameAgreement(next,d*(bottom-top)/(ROWS-1));double error=pixelError+18*(1-agreement);errors[d+limit]=error;if(error<best){best=error;shift=d;}
        }
        double alternative=Double.MAX_VALUE;for(int d=-limit;d<=limit;d++)if(Math.abs(d-shift)>8)alternative=Math.min(alternative,errors[d+limit]);
        // 低误差但不唯一只允许尝试找回重叠，不能用于位置累计；突变图像不发恢复手势。
        return new Motion(best<20&&nameAgreement(next,shift*(bottom-top)/(ROWS-1))>=.65&&alternative>best+2.5,shift*(bottom-top)/(ROWS-1),best,
            best<35&&names.size()>=4&&next.names.size()>=4);
    }
}
