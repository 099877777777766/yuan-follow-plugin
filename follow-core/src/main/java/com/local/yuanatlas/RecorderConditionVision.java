package com.local.yuanatlas;

import android.graphics.*;
import java.util.*;

/** 只读星光小区域和诸葛亮所在卡列，不读其他密探、不 OCR 图标内文字。 */
final class RecorderConditionVision {
    static final class Reading {
        final String star;int dragon;int vein=RecorderDragonVeinVision.UNKNOWN;
        String actionSource="",pending="";boolean actionMatched;
        Reading(String star,int dragon){this.star=star;this.dragon=dragon;}
    }
    static String starName(String value){return "purple".equals(value)?"紫星":"orange".equals(value)?"橙星":"blue".equals(value)?"蓝星":"未识别";}
    static Reading read(Bitmap frame,Rect overlay,RecorderFollowVision.Layout original,int column,boolean star,boolean dragon){
        // 卡牌坐标先扣除游戏窗口偏移，再缩放到固定识别宽度。
        Rect viewport=original.viewport;
        Bitmap clean=CaptureOcrImage.crop(frame,viewport,overlay),small=null;
        try{
            small=Bitmap.createScaledBitmap(clean,444,Math.round(clean.getHeight()*444f/clean.getWidth()),true);
            float scale=444f/viewport.width();RectF cards=new RectF(original.cards);cards.offset(-viewport.left,-viewport.top);cards.left*=scale;cards.right*=scale;cards.top*=scale;cards.bottom*=scale;
            String color=star?star(small):null;
            // 既有手机原帧的星光位置按包含上下黑边的画面校准。裁边后未命中时，
            // 补查原纵向坐标；横向仍去掉平板留边，且只画一张 444 宽小图。
            if(star&&color==null&&(viewport.top>0||viewport.bottom<frame.getHeight()))color=starWithVerticalBars(frame,viewport,overlay);
            return new Reading(color,dragon?dragon(small,cards,column):-1);
        }finally{if(small!=null&&small!=clean)small.recycle();if(clean!=frame)clean.recycle();}
    }
    private static String starWithVerticalBars(Bitmap frame,Rect viewport,Rect overlay){
        Rect source=new Rect(viewport.left,0,viewport.right,frame.getHeight());
        float scale=444f/source.width();Bitmap sample=Bitmap.createBitmap(444,Math.max(1,Math.round(source.height()*scale)),Bitmap.Config.ARGB_8888);
        try{
            Canvas canvas=new Canvas(sample);canvas.drawBitmap(frame,source,new Rect(0,0,sample.getWidth(),sample.getHeight()),new Paint(Paint.FILTER_BITMAP_FLAG));
            if(overlay!=null){Rect clipped=new Rect(overlay);if(clipped.intersect(source)){Paint black=new Paint();black.setColor(Color.BLACK);canvas.drawRect((clipped.left-source.left)*scale,clipped.top*scale,(clipped.right-source.left)*scale,clipped.bottom*scale,black);}}
            return star(sample);
        }finally{sample.recycle();}
    }
    static String star(Bitmap frame){
        int w=frame.getWidth(),h=frame.getHeight();float[] hsv=new float[3];double[] votes=new double[3];
        // 星的白色中心会闪动，以中心周围有饱和度的光晕判色，白芯不投票。
        int left=(int)(w*.105f),right=(int)(w*.195f),top=(int)(h*.15f),bottom=(int)(h*.21f);
        int bright=0,colored=0;
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){
            int color=frame.getPixel(x,y);Color.colorToHSV(color,hsv);
            float dx=(x-w*.148f)/(w*.042f),dy=(y-h*.18f)/(w*.055f);if(dx*dx+dy*dy>1)continue;
            if(hsv[2]>.82f)bright++;
            if(hsv[1]<.22f||hsv[2]<.4f)continue;
            int kind=hsv[0]>=170&&hsv[0]<=240?0:hsv[0]>=265&&hsv[0]<=330?1:hsv[0]>=22&&hsv[0]<=65?2:-1;
            if(kind>=0){votes[kind]+=hsv[1]*hsv[2];colored++;}
        }
        int best=0;for(int n=1;n<3;n++)if(votes[n]>votes[best])best=n;
        double total=votes[0]+votes[1]+votes[2];
        if(bright<5||colored<12||total<7||votes[best]<total*.72)return null;
        return new String[]{"blue","purple","orange"}[best];
    }
    // 用户原始录屏中完整龙气的 12×14 亮度模板，减均值并归一化；匹配形状而非简单数金色亮点。
    private static final float[] GLYPH={
        -.04654f,-.03524f,-.04424f,-.03591f,-.01562f,-.0163f,-.01562f,-.05541f,-.0704f,-.0659f,-.06928f,-.07377f,
        -.04488f,-.03691f,-.00464f,-.00798f,.01891f,.05851f,.0302f,-.03246f,-.06226f,-.07854f,-.07772f,-.08024f,
        -.03488f,.01454f,.04533f,.0721f,.13752f,.16811f,.14528f,.06075f,-.04579f,-.08001f,-.07968f,-.08505f,
        .02557f,.10874f,.11095f,.1122f,.12978f,.1339f,.11724f,.13023f,.02653f,-.06269f,-.08529f,-.08419f,
        .09299f,.15008f,.14817f,.08771f,.00249f,-.01866f,.0463f,.13906f,.0522f,-.05231f,-.07315f,-.07421f,
        .12778f,.15259f,.08189f,.01903f,-.02416f,-.04315f,.03631f,.14249f,.06082f,-.03898f,-.04583f,-.07471f,
        .12851f,.11894f,.0119f,.01529f,-.02181f,-.04532f,.05297f,.13416f,.04583f,.00266f,-.00036f,-.08452f,
        .09115f,-.03189f,-.05348f,.01881f,.02434f,.06463f,.15791f,.05675f,-.01845f,.05361f,.03765f,-.08402f,
        -.00064f,-.0674f,-.06352f,.04749f,.11282f,.08961f,.06501f,-.00196f,.01425f,.09445f,-.00549f,-.09755f,
        -.09119f,-.08534f,-.0352f,.1108f,.1483f,.04316f,-.04887f,-.06255f,.03814f,.07356f,-.04597f,-.10188f,
        -.08983f,-.08848f,.02163f,.13578f,.03286f,-.05141f,-.07878f,.01161f,.07369f,-.03101f,-.10075f,-.10424f,
        -.09062f,-.08724f,.00533f,.10285f,.02841f,-.02951f,.02993f,.07622f,-.00631f,-.09238f,-.12271f,-.10875f,
        -.09229f,-.08941f,-.05192f,.0391f,.04001f,.02873f,.0767f,.02934f,-.07906f,-.10504f,-.10772f,-.08141f,
        -.09229f,-.08824f,-.07938f,-.05677f,-.04187f,-.02914f,-.05064f,-.07104f,-.09652f,-.11172f,-.09323f,-.05143f};
    private static float gray(int c){return Color.red(c)*.299f+Color.green(c)*.587f+Color.blue(c)*.114f;}
    private static float sample(float[] pixels,int w,int h,float x,float y){
        int ix=(int)Math.floor(x),iy=(int)Math.floor(y);if(ix<0||iy<0||ix+1>=w||iy+1>=h)return 0;
        float dx=x-ix,dy=y-iy;return pixels[iy*w+ix]*(1-dx)*(1-dy)+pixels[iy*w+ix+1]*dx*(1-dy)+pixels[(iy+1)*w+ix]*(1-dx)*dy+pixels[(iy+1)*w+ix+1]*dx*dy;
    }
    private static double match(float[] pixels,int w,int h,float cx,float cy,float cell){
        double sum=0,squares=0,dot=0;
        for(int y=0;y<14;y++)for(int x=0;x<12;x++){
            float value=sample(pixels,w,h,cx+((x+.5f)/12-.5f)*cell*.199f,cy+((y+.5f)/14-.5f)*cell*.21f);
            sum+=value;squares+=value*value;dot+=value*GLYPH[y*12+x];
        }
        double norm=squares-sum*sum/168;return norm<168*36?-1:dot/Math.sqrt(norm);
    }
    static int dragon(Bitmap frame,RectF cards,int column){
        if(column<0||column>=5)return -1;
        int w=frame.getWidth(),h=frame.getHeight();float cell=cards.width()/5,cx=cards.left+cell*(column+.146f);
        int top=Math.max(0,(int)(cards.top+cards.height()*.34f)),bottom=Math.min(h-1,(int)(cards.bottom-cell*.09f));
        int[] colors=new int[w*h];frame.getPixels(colors,0,w,0,0,w,h);float[] pixels=new float[colors.length];for(int n=0;n<colors.length;n++)pixels[n]=gray(colors[n]);
        ArrayList<float[]> peaks=new ArrayList<>();double uncertain=0;
        for(int y=top;y<=bottom;y++){
            double best=-1;for(int dx=-3;dx<=3;dx++)best=Math.max(best,match(pixels,w,h,cx+dx,y,cell));
            uncertain=Math.max(uncertain,best);if(best>=.76)peaks.add(new float[]{y,(float)best});
        }
        peaks.sort((a,b)->Float.compare(b[1],a[1]));ArrayList<Float> positions=new ArrayList<>();
        for(float[] peak:peaks){boolean near=false;for(float y:positions)if(Math.abs(y-peak[0])<cell*.16f)near=true;if(!near)positions.add(peak[0]);}
        Collections.sort(positions);
        if(!positions.isEmpty()){
            // 连续堆叠且最后一枚位于卡底上方，防止衣饰或卡牌边框的偶然相似纹理被计数。
            float last=positions.get(positions.size()-1);if(cards.bottom-last>cell*.48f)return -1;
            for(int n=1;n<positions.size();n++){float gap=positions.get(n)-positions.get(n-1);if(gap<cell*.16f||gap>cell*.27f)return -1;}
            return positions.size()<=6?positions.size():-1;
        }
        // 没匹配到图标只在本列底部棕色底纹清晰时算 0；浮窗/全黑/大特效按未知重读。
        int brown=0,total=0;for(int y=Math.max(top,(int)(cards.bottom-cell*.65f));y<cards.bottom-cell*.18f;y++)for(int x=(int)(cx-cell*.055f);x<cx+cell*.055f;x++){
            if(x<0||x>=w||y<0||y>=h)continue;int c=colors[y*w+x],r=Color.red(c),g=Color.green(c),b=Color.blue(c);total++;if(r>55&&r>g*1.15&&g>b*1.12)brown++;
        }
        return uncertain<.6&&total>0&&brown>total*.75?0:-1;
    }
}
