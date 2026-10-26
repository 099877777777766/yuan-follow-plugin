package com.local.yuanatlas;

import android.graphics.Bitmap;
import java.util.*;

/** 普通攻略图片的网格定位。仅返回原图裁切边界，不解释操作、属性或自动跟打权限。 */
final class RecorderManualGrid {
    private static int light(int color){return (color>>16&255)+(color>>8&255)+(color&255);}
    static RecorderRoundImage.Layout find(Bitmap image,int height){
        int width=image.getWidth(),gap=Math.max(2,Math.round(width/480f));
        int[] center=new int[width],above=new int[width],below=new int[width];
        List<int[]> rules=new ArrayList<>();int[] run=null;int longest=0;
        // 只找比上下两侧都深、贯穿大半张表的细线，避免把浅棕底色和整行文字当成边框。
        for(int y=gap;y<height-gap;y++){
            image.getPixels(center,0,width,0,y,width,1);image.getPixels(above,0,width,0,y-gap,width,1);image.getPixels(below,0,width,0,y+gap,width,1);
            int votes=0;
            for(int x=0;x<width;x+=2)if(light(center[x])+65<Math.min(light(above[x]),light(below[x])))votes+=2;
            if(votes>width*.55f){
                if(run==null)run=new int[]{y,y,votes};else{run[1]=y;run[2]=Math.max(run[2],votes);}
                longest=Math.max(longest,votes);
            }else if(run!=null){rules.add(run);run=null;}
        }
        if(run!=null)rules.add(run);
        // 立绘表头通常只覆盖五列，数据行横线还覆盖右侧备注；用最长横线组排除高立绘表头。
        final int required=Math.round(longest*.84f);rules.removeIf(rule->rule[2]<required);
        if(rules.size()<3||rules.size()>201)return null;
        List<int[]> bands=new ArrayList<>();
        for(int n=1;n<rules.size();n++){
            int top=(rules.get(n-1)[0]+rules.get(n-1)[1])/2,bottom=(rules.get(n)[0]+rules.get(n)[1])/2;
            if(bottom-top<Math.max(12,width/100))return null;
            bands.add(new int[]{top,bottom});
        }
        // 在多行空白位置累计竖线支持度，颜色格、JPEG 压缩和某一行的手写标注不会改变列宽。
        int[] scores=new int[width];int samples=0,stride=Math.max(1,bands.size()/16);
        for(int n=0;n<bands.size();n+=stride)for(int part=1;part<=4;part++){
            int[] band=bands.get(n);int y=band[0]+(band[1]-band[0])*part/5;samples++;
            image.getPixels(center,0,width,0,y,width,1);
            for(int x=gap;x<width-gap;x++)if(light(center[x])+55<Math.min(light(center[x-gap]),light(center[x+gap])))scores[x]++;
        }
        List<Integer> edges=new ArrayList<>();int best=-1;
        for(int x=gap;x<width-gap;x++){
            if(scores[x]>=samples*.65f){if(best<0||scores[x]>scores[best])best=x;}
            else if(best>=0){edges.add(best);best=-1;}
        }
        if(best>=0)edges.add(best);
        // 从标签列之后寻找五个等宽操作格；备注列可以任意宽，不套用本 App 的固定比例。
        for(int start=1;start+5<edges.size();start++){
            int left=edges.get(start-1),label=edges.get(start),note=edges.get(start+5);
            float cell=(note-label)/5f;boolean regular=cell>=width*.035f&&label-left>=cell*.4f&&label-left<=cell*1.8f;
            for(int n=1;n<5;n++)regular&=Math.abs(edges.get(start+n)-(label+n*cell))<=Math.max(3,cell*.075f);
            int right=start+6<edges.size()?edges.get(start+6):note;
            if(regular&&right-left>width*.55f)return new RecorderRoundImage.Layout(bands,left,label,note,right,true);
        }
        return null;
    }
}
