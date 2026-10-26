package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

/** 从命盘细边线定位四行四列；短屏只改变网格位置，不沿用长屏的固定高度。 */
final class RuYuanFateGrid {
    static Rect locate(Bitmap original,Rect content){
        return locate(original,content,false);
    }
    /** 配队装备页是正方形命盘；图鉴改命页仍按原来的扁矩形范围验证。 */
    static Rect locate(Bitmap original,Rect content,boolean equipment){
        int w=640,h=Math.round(original.getHeight()*640f/original.getWidth());
        Bitmap image=Bitmap.createScaledBitmap(original,w,h,true);
        int[] colors=new int[w*h];image.getPixels(colors,0,w,0,0,w,h);
        if(image!=original)image.recycle();
        float[] light=new float[colors.length];
        for(int i=0;i<colors.length;i++)light[i]=Color.red(colors[i])*.3f+Color.green(colors[i])*.6f+Color.blue(colors[i])*.1f;
        float scale=640f/original.getWidth();
        int start=Math.max(4,Math.round((content.top+content.height()*.025f)*scale));
        int end=Math.min(h-4,Math.round((content.top+content.height()*(equipment?.82f:.59f))*scale));
        double[] rows=new double[h];
        // 中间两行没有横贯整盘的横线，只取左右两侧的宫格线，避开中央连线。
        for(int y=start;y<end;y++)rows[y]=Math.min(horizontal(light,colors,w,y,24,144),horizontal(light,colors,w,y,520,610));
        List<Integer> edges=peaks(rows,start,end,.55);
        double best=0;int top=0,bottom=0;
        for(int first:edges)for(int last:edges){
            double step=(last-first)/4.0;
            if(step<w*(equipment?.22:.14)||step>w*(equipment?.27:.22))continue;
            double score=0;boolean complete=true;
            for(int n=0;n<5;n++){
                double match=near(rows,first+step*n,3);
                if(match<.55){complete=false;break;}score+=match;
            }
            if(complete&&score>best){best=score;top=first;bottom=last;}
        }
        if(best==0)return null;
        double rowHeight=(bottom-top)/4.0;
        double[] columns=new double[w];
        for(int x=4;x<w-4;x++)columns[x]=Math.min(
            vertical(light,colors,w,x,top+(int)(rowHeight*.12),top+(int)(rowHeight*.88)),
            vertical(light,colors,w,x,bottom-(int)(rowHeight*.88),bottom-(int)(rowHeight*.12)));
        List<Integer> sides=peaks(columns,4,w-4,.55);
        best=0;int left=0,right=0;
        for(int first:sides)for(int second:sides){
            int step=second-first;
            if(first>w*.07||step<w*.21||step>w*.28)continue;
            double score=0;boolean complete=true;int matched=0;
            // 旧录像右侧边框可能已在截图外；至少四条等距竖线才能推导第四列。
            for(int n=0;n<5;n++){
                int x=first+step*n;if(x>=w-4)continue;
                double match=near(columns,x,4);
                // 装备页录屏有一条内部竖线几乎透明；四条独立等距边线已足够约束第五条。
                if(match<.55){if(!equipment){complete=false;break;}}else{score+=match;matched++;}
            }
            if(complete&&matched>=4&&score>best){best=score;left=first;right=first+step*4;}
        }
        if(best==0)return null;
        return new Rect(Math.round(left/scale),Math.round(top/scale),Math.round(right/scale),Math.round(bottom/scale));
    }
    private static boolean edge(float[] light,int[] colors,int index,int offset){
        int c=colors[index];float value=light[index];
        return value>30&&value<225&&Color.red(c)>Color.blue(c)*.70&&value-Math.min(light[index-offset],light[index+offset])>8;
    }
    private static double horizontal(float[] light,int[] colors,int w,int y,int left,int right){
        int hits=0;for(int x=left;x<right;x++)if(edge(light,colors,y*w+x,w*3))hits++;
        return(double)hits/(right-left);
    }
    private static double vertical(float[] light,int[] colors,int w,int x,int top,int bottom){
        int hits=0;for(int y=top;y<bottom;y++)if(edge(light,colors,y*w+x,3))hits++;
        return bottom>top?(double)hits/(bottom-top):0;
    }
    private static double near(double[] scores,double position,int radius){
        double best=0;for(int i=Math.max(0,(int)Math.round(position)-radius);i<=Math.min(scores.length-1,(int)Math.round(position)+radius);i++)best=Math.max(best,scores[i]);return best;
    }
    private static List<Integer> peaks(double[] scores,int start,int end,double minimum){
        List<Integer> result=new ArrayList<>();
        for(int i=start;i<end;i++)if(scores[i]>=minimum){
            int best=i;while(i+1<end&&scores[i+1]>=minimum){i++;if(scores[i]>scores[best])best=i;}
            result.add(best);
        }
        return result;
    }
}
