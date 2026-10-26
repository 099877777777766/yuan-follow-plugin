package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

/** 用横向边线、纵向边线及深色内框共同定位弹窗，不以文字差异猜测正文范围。 */
final class RuYuanPopup {
    static Rect locate(Bitmap original,Rect content){return locate(original,content,false);}
    static Rect locate(Bitmap original,Rect content,boolean codeYuan){
        int w=640,h=Math.round(original.getHeight()*640f/original.getWidth());
        Bitmap image=Bitmap.createScaledBitmap(original,w,h,true);float[] light=new float[w*h];int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);
        if(image!=original)image.recycle();
        for(int i=0;i<pixels.length;i++)light[i]=Color.red(pixels[i])*.3f+Color.green(pixels[i])*.6f+Color.blue(pixels[i])*.1f;
        float scale=640f/original.getWidth();int start=Math.max(4,Math.round((content.top+content.height()*.04f)*scale)),end=Math.min(h-4,Math.round((content.top+content.height()*(codeYuan?.88f:.69f))*scale));
        List<int[]> edges=new ArrayList<>();
        for(int y=start;y<end;y++){
            int first=-1,last=-1,count=0;
            for(int x=0;x<w;x++){
                int c=pixels[y*w+x];float value=light[y*w+x];boolean hit=value-Math.min(light[(y-3)*w+x],light[(y+3)*w+x])>9&&value>35&&value<200&&Color.red(c)>Color.blue(c)*.72;
                if(!hit)continue;
                if(last>=0&&x-last>7){add(edges,y,first,last,count);first=-1;count=0;}
                if(first<0)first=x;last=x;count++;
            }add(edges,y,first,last,count);
        }
        if(edges.size()>400)return null;
        double best=0;Rect chosen=null;
        for(int[] upper:edges)for(int[] lower:edges){
            int top=upper[0],bottom=lower[0];if(bottom-top<40||bottom-top>(codeYuan?1000:600)||Math.abs(upper[2]-lower[2])>25)continue;
            double side=0;int right=0;
            for(int x=Math.max(3,Math.min(upper[2],lower[2])-5);x<Math.min(w-3,Math.max(upper[2],lower[2])+5);x++){
                double score=vertical(light,w,x,top+8,bottom-8);if(score>side){side=score;right=x;}
            }
            if(side<.70)continue;
            int left=Math.max(0,Math.min(upper[1],lower[1]));double leftScore=0;
            for(int x=Math.max(3,right-535);x<right-425;x++){double score=vertical(light,w,x,top+12,bottom-12);if(score>leftScore){leftScore=score;left=x;}}
            if(leftScore<.55)left=Math.max(left,right-500);
            int dark=0,total=0;for(int y=top+8;y<bottom-8;y+=4)for(int x=left+20;x<right-10;x+=4){total++;if(light[y*w+x]<95)dark++;}
            double fraction=total==0?0:(double)dark/total;if(fraction<.78)continue;
            double score=(bottom-top)*(right-left)*side*fraction;
            // 仅内缩一个标准像素；窄弹窗正文可紧贴右边，不能用大留白截掉末字。
            if(score>best){best=score;chosen=new Rect(Math.max(0,Math.round((left+1)/scale)),Math.round((top+1)/scale),Math.min(original.getWidth(),Math.round((right-1)/scale)),Math.round((bottom-1)/scale));}
        }
        return chosen;
    }
    private static void add(List<int[]> edges,int y,int left,int right,int count){if(count>250&&right-left>370&&right-left<565&&(double)count/(right-left)>.70)edges.add(new int[]{y,left,right});}
    private static double vertical(float[] light,int w,int x,int top,int bottom){if(bottom<=top)return 0;int hits=0;for(int y=top;y<bottom;y++){float p=light[y*w+x];if(p-Math.min(light[y*w+x-3],light[y*w+x+3])>8&&p>35&&p<200)hits++;}return(double)hits/(bottom-top);}
}
