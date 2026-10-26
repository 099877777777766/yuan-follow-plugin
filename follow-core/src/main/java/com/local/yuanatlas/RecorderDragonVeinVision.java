package com.local.yuanatlas;

import android.graphics.*;

/** 龙脉背景检测：只比较顶部两块固定纹路，不依赖敌方立绘、星色或龙气数量。 */
final class RecorderDragonVeinVision {
    static final int UNKNOWN=-1, OUTSIDE=0, INSIDE=1;
    static final class Reading {
        final int state;final double score;final String reason;
        Reading(int state,double score,String reason){this.state=state;this.score=score;this.reason=reason;}
    }
    // 从手机样图顶部提取两块 16×12 纹路，避开战斗进度/伤害栏；平板也匹配同一背景。
    private static final int[][] PATTERNS={
        {11,11,11,11,11,13,15,10,4,1,1,1,1,3,13,18,12,12,12,12,12,16,10,2,1,1,1,1,1,11,18,17,11,11,11,12,14,11,3,1,1,1,1,2,10,17,18,17,12,12,12,14,8,3,1,1,1,1,1,9,20,20,17,17,12,13,14,8,1,1,1,1,1,1,6,20,20,17,16,18,12,14,8,2,2,1,1,1,1,4,17,20,15,16,19,18,14,8,2,2,2,2,2,1,5,16,19,16,15,17,17,18,13,3,2,2,2,2,2,2,9,18,18,15,16,17,16,18,3,2,2,2,2,2,2,7,18,19,16,16,16,17,17,18,2,2,2,2,2,2,5,17,19,15,16,16,16,18,18,18,2,2,2,2,2,2,14,20,17,15,16,16,17,17,18,18,2,2,2,2,2,10,20,18,14,15,16,17,18,18,18,18},
        {16,18,17,16,15,15,14,14,14,14,14,14,14,14,14,14,17,18,16,15,14,14,14,14,14,13,13,14,14,14,14,14,18,17,15,15,15,15,15,15,14,14,14,14,14,14,14,14,16,15,15,15,15,15,16,16,15,14,14,14,15,14,14,14,15,15,16,16,16,16,16,16,15,14,14,14,14,14,14,14,16,16,17,16,16,16,16,16,16,15,15,15,15,16,18,18,17,16,17,16,16,16,17,16,16,16,17,21,21,18,9,4,17,17,17,17,16,16,17,16,16,18,21,22,16,7,2,1,18,18,18,17,17,17,18,19,23,22,14,5,1,1,1,1,18,18,18,19,19,21,24,22,13,3,1,1,1,1,1,1,18,18,18,21,25,25,16,6,1,1,1,1,1,1,1,1,18,21,26,23,19,9,2,1,1,1,1,1,1,1,1,1}
    };
    static Reading read(Bitmap frame,Rect overlay,Rect viewport){
        if(frame==null||viewport==null||viewport.width()<240||viewport.height()<viewport.width()*1.2f
            ||!new Rect(0,0,frame.getWidth(),frame.getHeight()).contains(viewport))return unknown("游戏区域无效");
        int width=viewport.width();
        // 背景本身较暗，黑边检测可能多裁几十像素；仅在顶部小范围搜索同一纵向偏移。
        int top=Math.max(0,viewport.top-Math.round(width*.09f));
        int bottom=Math.min(frame.getHeight(),viewport.top+Math.round(width*.14f));
        Rect region=new Rect(viewport.left+Math.round(width*.20f),top,viewport.left+Math.round(width*.62f),bottom);
        if(overlay!=null&&Rect.intersects(region,overlay))return unknown("龙脉背景被悬浮窗遮挡");
        float scale=256f/width;int height=Math.max(1,Math.round((bottom-top)*scale));
        Bitmap small=Bitmap.createBitmap(256,height,Bitmap.Config.ARGB_8888);
        try{
            Canvas canvas=new Canvas(small);canvas.drawBitmap(frame,new Rect(viewport.left,top,viewport.right,bottom),new Rect(0,0,256,height),new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] pixels=new int[256*height];small.getPixels(pixels,0,256,0,0,256,height);
            double best=-1,bestAny=-1;boolean textured=false;
            // 平板背景相对按钮存在少量横向偏移，两块必须在同一个偏移下同时匹配。
            for(int dx=-4;dx<=4;dx++)for(int dy=0;dy+24<height;dy++){
                double both=1,one=-1;boolean valid=true;
                for(int p=0;p<2;p++){
                    double sum=0,squares=0,dot=0,ts=0,tq=0;int bright=0;
                    for(int y=0;y<12;y++)for(int x=0;x<16;x++){
                        // 每个小格取四点平均，抑制 JPEG 噪点，所有候选复用同一张小图。
                        double value=0;for(int sy=0;sy<2;sy++)for(int sx=0;sx<2;sx++){
                            int px=dx+Math.round((.22f+p*.20f+(x+(sx+.5f)/2)/16*.18f)*256);
                            int py=dy+Math.round((.005f+(y+(sy+.5f)/2)/12*.085f)*256);
                            int color=pixels[py*256+px];value+=Color.red(color)*.299+Color.green(color)*.587+Color.blue(color)*.114;
                        }
                        value/=4;int template=PATTERNS[p][y*16+x];sum+=value;squares+=value*value;dot+=value*template;ts+=template;tq+=template*template;if(value>7)bright++;
                    }
                    double variance=squares-sum*sum/192,mean=sum/192;
                    if(variance<192*9||mean>190||bright<45){valid=false;break;}
                    textured=true;double correlation=(dot-sum*ts/192)/Math.sqrt(variance*(tq-ts*ts/192));
                    both=Math.min(both,correlation);one=Math.max(one,correlation);
                }
                if(valid){best=Math.max(best,both);bestAny=Math.max(bestAny,one);}
            }
            // 两块都吻合才判内；仅一块像、亮度不足或过渡帧均交给短时复核。
            int state=best>=.82?INSIDE:textured&&best<.45&&bestAny<.75&&best>-1?OUTSIDE:UNKNOWN;
            return new Reading(state,best,state==UNKNOWN?"背景纹路尚未稳定":state==INSIDE?"两块龙脉纹路吻合":"背景与龙脉纹路不同");
        }finally{small.recycle();}
    }
    private static Reading unknown(String why){return new Reading(UNKNOWN,-1,why);}
    static String name(int state){return state==INSIDE?"龙脉内":state==OUTSIDE?"龙脉外":"未识别";}
}
