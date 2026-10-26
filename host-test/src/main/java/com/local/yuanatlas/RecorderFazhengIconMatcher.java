package com.local.yuanatlas;

import android.graphics.*;

/** 用图鉴原图的亮度形状识别 13 种圣物；颜色只辅助区分，不能将所有红色视为同一件。 */
final class RecorderFazhengIconMatcher {
    private static final int N=RecorderFazhengRelics.SHAPES[0].length;
    private static final double[] MEAN=new double[13],NORM=new double[13];
    static {for(int i=0;i<13;i++){for(int v:RecorderFazhengRelics.SHAPES[i])MEAN[i]+=v;MEAN[i]/=N;for(int v:RecorderFazhengRelics.SHAPES[i])NORM[i]+=Math.pow(v-MEAN[i],2);}}
    static int read(Bitmap frame,float cx,float cy,float cell){
        double[] best=scores(frame,cx,cy,cell);
        int winner=-1;double first=0,second=0;for(int i=0;i<13;i++){if(best[i]>first){second=first;first=best[i];winner=i;}else second=Math.max(second,best[i]);}
        return first>=.80&&first-second>=.025?winner:-1;
    }
    static double[] scores(Bitmap frame,float cx,float cy,float cell){
        double[] best=new double[13];int[] values=new int[N];float[] hsv=new float[3];
        for(int dy=-3;dy<=3;dy++)for(int dx=-3;dx<=3;dx++)for(float scale:new float[]{.88f,1f,1.12f}){
            float x0=cx+dx*cell*.015f,y0=cy+dy*cell*.015f,r=cell*.125f*scale;
            if(x0-r<0||y0-r<0||x0+r>=frame.getWidth()||y0+r>=frame.getHeight())continue;
            int ring=0,dark=0,bright=0,index=0;double sum=0;double[] groups=new double[4];
            for(int y=-10;y<=10;y++)for(int x=-10;x<=10;x++){
                int d=x*x+y*y;if(d>100)continue;
                int color=frame.getPixel(Math.round(x0+x*r/10),Math.round(y0+y*r/10));Color.colorToHSV(color,hsv);
                if(d>80){ring++;if(hsv[2]<.43f)dark++;continue;}
                int v=Math.max(Color.red(color),Math.max(Color.green(color),Color.blue(color)));values[index++]=v;sum+=v;if(v>153)bright++;
                if(hsv[1]>.18f&&hsv[2]>.16f){float h=hsv[0];int group=h<=20||h>=335?3:h>=25&&h<=75?2:h>=175&&h<250?0:h>=250&&h<335?1:-1;if(group>=0)groups[group]+=hsv[1]*hsv[2];}
            }
            // 必须有暗色闭合圆盘和明亮图案，不能从立绘或空卡中硬选最相近的圣物。
            if(dark<ring*.70||bright<N*.025)continue;
            double mean=sum/N,norm=0,total=0;for(int v:values)norm+=(v-mean)*(v-mean);for(double g:groups)total+=g;if(norm<10000)continue;
            for(int id=0;id<13;id++){
                double dot=0;int[] shape=RecorderFazhengRelics.SHAPES[id];for(int i=0;i<N;i++)dot+=(values[i]-mean)*(shape[i]-MEAN[id]);
                double correlation=dot/Math.sqrt(norm*NORM[id]);if(correlation<.74)continue;
                double score=correlation+(total>0?.10*groups[RecorderFazhengRelics.GROUPS[id]]/total:0);best[id]=Math.max(best[id],score);
            }
        }
        return best;
    }
}
