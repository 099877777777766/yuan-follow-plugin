package com.local.yuanatlas;

import android.graphics.*;
import java.util.Arrays;

/** 只筛掉没有文字对比度的图或已由 OCR 否定且像素完全相同的图，不以颜色猜测胜利。 */
final class RecorderVictoryProbe {
    private int[] previous,work;
    private Rect previousArea;
    private long checkedAt;
    private boolean negative;
    int skipped,requested;
    void clear(){previous=null;work=null;previousArea=null;negative=false;checkedAt=0;}
    boolean shouldRead(Bitmap frame,Rect overlay,long now){
        Rect area=RecorderVictoryReader.region(frame);int size=area.width()*area.height();
        if(work==null||work.length!=size)work=new int[size];
        frame.getPixels(work,0,area.width(),area.left,area.top,area.width(),area.height());
        if(overlay!=null){Rect clipped=new Rect(overlay);if(clipped.intersect(area))for(int y=clipped.top;y<clipped.bottom;y++)
            Arrays.fill(work,(y-area.top)*area.width()+clipped.left-area.left,(y-area.top)*area.width()+clipped.right-area.left,Color.BLACK);}
        int low=255,high=0;
        // 检查全部像素，细小的“确定”笔画也会触发 OCR；不用稀疏采样漏掉小字。
        for(int color:work){int light=Math.max(Color.red(color),Math.max(Color.green(color),Color.blue(color)));low=Math.min(low,light);high=Math.max(high,light);}
        if(high-low<12){negative=false;skipped++;return false;}
        if(negative&&now-checkedAt<5000&&area.equals(previousArea)&&Arrays.equals(previous,work)){skipped++;return false;}
        int[] old=previous;previous=work;work=old;previousArea=area;negative=false;requested++;return true;
    }
    void completed(boolean noButton,long now){negative=noButton;checkedAt=now;}
}
