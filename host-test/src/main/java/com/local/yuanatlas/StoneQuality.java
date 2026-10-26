package com.local.yuanatlas;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** 品质取自星石主体：金色框、红点和头像都不能单独作为橙色证据。 */
final class StoneQuality {
    static boolean orange(Bitmap bitmap,double cx,double cy,double radius){
        int total=0,warm=0,gold=0,cool=0,core=0,coreWarm=0;
        for(int dy=-10;dy<=10;dy++)for(int dx=-10;dx<=10;dx++){
            int distance=dx*dx+dy*dy;if(distance>100)continue;
            int x=(int)(cx+dx*radius/10),y=(int)(cy+dy*radius/10);
            if(x<0||y<0||x>=bitmap.getWidth()||y>=bitmap.getHeight())continue;
            int pixel=bitmap.getPixel(x,y),r=(pixel>>16)&255,g=(pixel>>8)&255,b=pixel&255;
            boolean red=r>135&&r>g*1.18&&g>b*1.15&&r-b>55;
            total++;if(red)warm++;
            if(r>140&&g>110&&Math.min(r,g)>b*1.18)gold++;
            if(b>r*1.12||g>r*1.12&&g>b*1.05)cool++;
            if(distance<=25){core++;if(red)coreWarm++;}
        }
        // 至少有大片金黄底色和中心橙红结构；紫/蓝/绿占明显比例时直接拒绝。
        // 圆盘不完整或位置不可靠也不放行，避免把旁边的红点、小块金色当成橙石。
        return total>=280&&core>=70&&gold/(double)total>=.45&&warm/(double)total>=.12
            &&coreWarm/(double)core>=.18&&cool/(double)total<.12;
    }
    /** 手动裁剪可能没有标题/标签：用本格等级到名称的距离定位主体，不按裁剪宽度猜图标大小。 */
    static void annotateCrop(Bitmap bitmap,JSONArray lines)throws Exception{
        List<JSONObject> levels=new ArrayList<>();
        for(int n=0;n<lines.length();n++){
            JSONObject line=lines.getJSONObject(n);line.remove("orange");
            if(StoneCaptureFrame.clean(line.optString("text")).matches(".*\\d{1,2}级.*"))levels.add(line);
        }
        for(int n=0;n<lines.length();n++){
            JSONObject name=lines.getJSONObject(n);if(!StoneCaptureFrame.star(name.optString("text")))continue;
            JSONObject picked=null;double cx=StoneCaptureFrame.center(name),ny=name.getDouble("y"),height=name.getDouble("height");
            for(JSONObject level:levels){
                double dy=ny-level.getDouble("y");
                if(dy>height*2&&dy<height*9&&Math.abs(StoneCaptureFrame.center(level)-cx)<height*3.5
                    &&(picked==null||level.getDouble("y")>picked.getDouble("y")))picked=level;
            }
            if(picked==null)continue;
            double span=ny-picked.getDouble("y");
            name.put("orange",orange(bitmap,cx,ny-span*.60,span*.34));
        }
    }
}
