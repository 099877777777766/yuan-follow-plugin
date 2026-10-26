package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Rect;

/** 只比较战斗主体的低分辨率帧差，不做 OCR，也不重新定位卡牌或圆圈。 */
final class RecorderActionMotion {
    // AOSP 截图请求间隔下限为 333 ms；使用 400 ms，给不同系统留出余量。
    static final int INTERVAL_MS=400,STABLE_MS=900;
    private static final int WIDTH=96,HEIGHT=192;
    private Sample previous;
    private Rect sampleViewport;
    private int sourceWidth,sourceHeight;
    private long previousAt,quietSince=-1;
    private boolean stalled;
    private int largeFrames,cardMotionFrames,repeatedFrames,unknownFrames;private double peakChange;
    static final class Sample {
        final int[] pixels;final Rect excluded;final int width,height;
        Sample(int[] pixels,Rect excluded,int width,int height){this.pixels=pixels;this.excluded=excluded;this.width=width;this.height=height;}
    }
    static Sample sample(Bitmap frame,Rect overlay){
        try(GameViewport v=GameViewport.open(frame)){return sampleGame(v.image,v.local(overlay));}
    }
    /** 同一次行动只定位一次采样区域；暗背景/技能闪光不能逐帧改变裁图大小并清空稳定计时。 */
    Sample sampleFrame(Bitmap frame,Rect overlay){
        if(sampleViewport==null||sourceWidth!=frame.getWidth()||sourceHeight!=frame.getHeight()){
            // 真正更换截图尺寸时重新定位，旧尺寸的平稳时间不能沿用。
            if(sampleViewport!=null)missing();
            sourceWidth=frame.getWidth();sourceHeight=frame.getHeight();sampleViewport=GameViewport.detect(frame,overlay);
            // 战斗顶部暗背景会被误当成上下黑边；纵向保持截图坐标，避免把常驻能量圈推入主体观察区。
            // 左右留边仍只在首帧排除，后续光效不改变同一次等待的采样坐标。
            sampleViewport.top=0;sampleViewport.bottom=sourceHeight;
        }
        Bitmap game=sampleViewport.equals(new Rect(0,0,sourceWidth,sourceHeight))?frame:Bitmap.createBitmap(frame,sampleViewport.left,sampleViewport.top,sampleViewport.width(),sampleViewport.height());
        Rect excluded=overlay==null?null:new Rect(overlay);
        if(excluded!=null){if(excluded.intersect(sampleViewport))excluded.offset(-sampleViewport.left,-sampleViewport.top);else excluded=null;}
        try{return sampleGame(game,excluded);}finally{if(game!=frame)game.recycle();}
    }
    private static Sample sampleGame(Bitmap frame,Rect overlay){
        Bitmap small=Bitmap.createScaledBitmap(frame,WIDTH,HEIGHT,true);
        int[] pixels=new int[WIDTH*HEIGHT];small.getPixels(pixels,0,WIDTH,0,0,WIDTH,HEIGHT);
        if(small!=frame)small.recycle();
        Rect excluded=new Rect();
        if(overlay!=null&&!overlay.isEmpty()){
            // 同时排除前后两帧的浮窗范围，拖动浮窗或高亮单元格不算技能动画。
            excluded.set((int)Math.floor(overlay.left*WIDTH/(double)frame.getWidth())-2,
                (int)Math.floor(overlay.top*HEIGHT/(double)frame.getHeight())-2,
                (int)Math.ceil(overlay.right*WIDTH/(double)frame.getWidth())+2,
                (int)Math.ceil(overlay.bottom*HEIGHT/(double)frame.getHeight())+2);
        }
        return new Sample(pixels,excluded,frame.getWidth(),frame.getHeight());
    }
    /** 负数表示有效区域不足；不把大浮窗遮挡或尺寸变化当作静止。 */
    static double difference(Sample a,Sample b){
        if(a.width!=b.width||a.height!=b.height)return -1;
        int count=0,changed=0,nonBlank=0;
        // 避开顶部回合文字、底部卡牌/能量圆圈、右侧菜单，保留技能主体。
        for(int y=27;y<123;y++)for(int x=4;x<89;x++){
            if(a.excluded.contains(x,y)||b.excluded.contains(x,y))continue;
            int p=a.pixels[y*WIDTH+x],q=b.pixels[y*WIDTH+x];
            int r=q>>16&255,g=q>>8&255,blue=q&255;
            if(Math.max(r,Math.max(g,blue))>18&&Math.min(r,Math.min(g,blue))<237)nonBlank++;
            int delta=Math.abs((p>>16&255)-r)+Math.abs((p>>8&255)-g)+Math.abs((p&255)-blue);
            if(delta>=60)changed++;count++;
        }
        // 整屏白闪、黑屏加载不代表动作完成；等下一帧，最终仍受等待上限约束。
        return count<600||nonBlank<count*.08?-1:changed/(double)count;
    }
    /** 下方大招前奏可能很亮，上方敌人却几乎不动；复用小图补看跨卡牌的大幅光效。 */
    static double cardDifference(Sample a,Sample b){
        if(a.width!=b.width||a.height!=b.height)return -1;
        int count=0,changed=0;
        for(int y=128;y<181;y++)for(int x=4;x<89;x++){
            if(a.excluded.contains(x,y)||b.excluded.contains(x,y))continue;
            int p=a.pixels[y*WIDTH+x],q=b.pixels[y*WIDTH+x];
            int delta=Math.abs((p>>16&255)-(q>>16&255))+Math.abs((p>>8&255)-(q>>8&255))+Math.abs((p&255)-(q&255));
            if(delta>=60)changed++;count++;
        }
        // 只关注大部分卡牌被光效覆盖；一张卡的常驻圈、光点和短暂明暗闪动不延长等待。
        return count<2500?0:changed/(double)count;
    }
    /** 卡顿/重复截图不是动作结束：用同一张小图的细微变化确认游戏画面仍在更新。 */
    private static boolean updating(Sample a,Sample b){
        int count=0,changed=0;
        // 此处包含卡牌和光效，只判断有没有更新，不识别卡牌状态；排除系统栏和前后浮窗。
        for(int y=20;y<184;y++)for(int x=4;x<89;x++){
            if(a.excluded.contains(x,y)||b.excluded.contains(x,y))continue;
            int p=a.pixels[y*WIDTH+x],q=b.pixels[y*WIDTH+x];
            int delta=Math.abs((p>>16&255)-(q>>16&255))+Math.abs((p>>8&255)-(q>>8&255))+Math.abs((p&255)-(q&255));
            // 忽略录屏压缩/截图的微小噪声，防止停帧被少量像素抖动伪装成持续更新。
            if(delta>=12)changed++;count++;
        }
        return count>=600&&changed>=Math.max(24,count*.002);
    }
    boolean observe(Sample current,long now){
        double change=previous==null?-1:difference(previous,current);
        boolean cardMoving=previous!=null&&cardDifference(previous,current)>=.65;
        boolean repeated=change>=0&&!updating(previous,current);
        // 停帧会清空稳定时间，恢复更新后重新观察，不能把卡住的一秒计作动画结束。
        // 长发、衣袖及常驻光点属于待机小幅变化；18% 在浮窗遮挡一部分主体后过敏。
        // 保留连续 900ms、停帧和空白检测，只让明显大范围变化重新计算稳定窗口。
        if(change>=.26)largeFrames++;if(cardMoving)cardMotionFrames++;if(repeated)repeatedFrames++;if(change<0)unknownFrames++;peakChange=Math.max(peakChange,change);
        if(change<0||change>=.26||cardMoving||now-previousAt>1000||repeated)quietSince=-1;
        else if(quietSince<0)quietSince=stalled?now:previousAt;
        stalled=repeated;
        previous=current;previousAt=now;
        return quietSince>=0&&now-quietSince>=STABLE_MS;
    }
    void missing(){previous=null;quietSince=-1;stalled=false;}
    String diagnostic(){return "大幅变化帧="+largeFrames+"，卡牌大幅光效帧="+cardMotionFrames+"，重复帧="+repeatedFrames+"，无效帧="+unknownFrames+"，峰值帧差="+Math.round(peakChange*100)+"%";}
}
