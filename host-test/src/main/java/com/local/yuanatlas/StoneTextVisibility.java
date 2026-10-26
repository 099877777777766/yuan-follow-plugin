package com.local.yuanatlas;

import android.graphics.Bitmap;

/** 底部安全带只作预警；完整亮字可以通过，暗淡渐隐文字不能仅凭 OCR 结果通过。 */
final class StoneTextVisibility {
    static boolean clearName(Bitmap bitmap,double x,double y,double width,double height){
        int left=(int)Math.floor(x),top=(int)Math.floor(y),right=(int)Math.ceil(x+width),bottom=(int)Math.ceil(y+height);
        if(left<0||top<0||right>bitmap.getWidth()||bottom>bitmap.getHeight()||right-left<4||bottom-top<4)return false;
        int total=0,gold=0,dark=0,leftGold=0,rightGold=0;long brightSum=0,darkSum=0;
        int step=Math.max(1,Math.min(right-left,bottom-top)/40);
        for(int py=top;py<bottom;py+=step)for(int px=left;px<right;px+=step){
            int c=bitmap.getPixel(px,py),r=(c>>16)&255,g=(c>>8)&255,b=c&255,light=(r+g+b)/3;total++;
            // 录像缩放后的金字可能接近米白色，不能要求与橙色图标相同的高饱和度。
            if(r>170&&g>145&&r>b*1.07&&g>b*1.02){gold++;brightSum+=light;if(px<(left+right)/2)leftGold++;else rightGold++;}
            if(light<110){dark++;darkSum+=light;}
        }
        // 两个字所在半区都要有金色笔画，并与背景形成反差；避免亮点或纯色块冒充完整文字。
        return gold>=total*.065&&gold<total*.65&&leftGold>=total*.02&&rightGold>=total*.02
            &&dark>=total*.2&&brightSum/(double)Math.max(1,gold)-darkSum/(double)Math.max(1,dark)>=65;
    }
}
