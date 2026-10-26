package com.local.yuanatlas;

import android.graphics.*;
import java.util.*;

/** 只定位中央长血条与四个外围血条；不读取属性、人物或名字来确定敌人编号。 */
final class RecorderEnemyVision {
    static final int WIDTH=448,UNKNOWN=-1,ABSENT=0,PRESENT=1;
    // 五个屏幕投影：远左、近左、中央、近右、远右；编号由已知当前目标和前后跟踪确定。
    static final int[] OFFSET={-1,-2,0,2,1};
    private static final int[][] AREAS={{0,60,90},{75,132,130},{176,320,0},{330,388,130},{400,448,90}};
    private static final int[] BAR_START={-50,73,170,325,398},BAR_END={64,136,328,390,512};
    static final class Reading {
        final int center;final int[] states,slots;final float anchor;final int[] scene;final float[][] health;
        // 原分辨率中央填充长度，仅比较同一投影前后，不与被压缩/裁切的外围血条直接比长度。
        final float centerFill;
        final int selected;
        Reading(int center,int[] states,int[] slots,float anchor,int[] scene,float[][] health){this(center,states,slots,anchor,scene,health,Float.NaN);}
        Reading(int center,int[] states,int[] slots,float anchor,int[] scene,float[][] health,float centerFill){this(center,states,slots,anchor,scene,health,centerFill,center);}
        private Reading(int center,int[] states,int[] slots,float anchor,int[] scene,float[][] health,float centerFill,int selected){this.center=center;this.states=states;this.slots=slots;this.anchor=anchor;this.scene=scene;this.health=health;this.centerFill=centerFill;this.selected=selected;}
        Reading cameraOnly(int selected){return new Reading(center,states,slots,anchor,scene,health,centerFill,selected);}
        /** 把屏幕位置投影到原始编号；配置中不存在的位置有血条时，这个候选落点不成立。 */
        Reading at(int target,int count){
            if(target<1||target>count)return null;
            int[] mapped=new int[count];float[][] bars=new float[count][];Arrays.fill(mapped,UNKNOWN);
            for(int n=0;n<5;n++){
                int original=(target-1+OFFSET[n]+5)%5+1;
                if(original>count){if(slots[n]==PRESENT)return null;continue;}
                mapped[original-1]=slots[n];bars[original-1]=health[n];
            }
            return new Reading(target,mapped,slots,anchor,scene,bars,centerFill);
        }
    }
    static boolean configured(RecorderFollowSettings settings){return settings.enemies>=1&&settings.enemies<=5;}
    static Reading read(Bitmap frame,Rect overlay){
        return read(frame,overlay,Float.NaN);
    }
    /** 跨帧 anchor 是未裁顶部、按游戏宽度归一到 WIDTH 的高度，不是裁后图的 y。 */
    static Reading read(Bitmap frame,Rect overlay,float expectedAnchor){
        try(GameViewport v=GameViewport.open(frame)){
            // 黑边检测会随顶部特效、压缩和截图尺寸改变裁切起点。同一屏幕血条的
            // 裁后 y 因此可能相差几十像素；跨帧锁定必须用未裁顶部的同一坐标系。
            // 先把基线换回本帧局部坐标搜索，再恢复屏幕高度；真实边缘仍须通过原校验。
            float offset=v.bounds.top*WIDTH/(float)v.bounds.width();
            Reading local=readGame(v.image,v.local(overlay),expectedAnchor-offset);
            return local==null?null:new Reading(local.center,local.states,local.slots,local.anchor+offset,local.scene,local.health,local.centerFill);
        }
    }
    private static Reading readGame(Bitmap frame,Rect overlay,float expectedAnchor){
        if(frame.getWidth()<300||frame.getHeight()<frame.getWidth()*1.5||frame.getHeight()>frame.getWidth()*2.8)return null;
        int height=Math.round(frame.getHeight()*WIDTH/(float)frame.getWidth());
        Bitmap small=frame.getWidth()==WIDTH?frame:Bitmap.createScaledBitmap(frame,WIDTH,height,true);
        Rect excluded=new Rect();
        if(overlay!=null)excluded.set((int)Math.floor(overlay.left*WIDTH/(double)frame.getWidth())-2,(int)Math.floor(overlay.top*height/(double)frame.getHeight())-2,(int)Math.ceil(overlay.right*WIDTH/(double)frame.getWidth())+2,(int)Math.ceil(overlay.bottom*height/(double)frame.getHeight())+2);
        int rows=Math.min(height,Math.round(height*.55f));int[] pixels;
        try{pixels=new int[WIDTH*rows];small.getPixels(pixels,0,WIDTH,0,0,WIDTH,rows);}finally{if(small!=frame)small.recycle();}
        float[] light=new float[pixels.length];for(int n=0;n<pixels.length;n++){int c=pixels[n];light[n]=(c>>16&255)*.299f+(c>>8&255)*.587f+(c&255)*.114f;}
        int barBottom=-1;float bestBorder=-Float.MAX_VALUE;
        // 从长血条底边及上方血量字形的明暗结构定位，不做文字或属性识别。
        for(int y=Math.max(24,(int)(height*.10f));y<height*.27f&&y+4<rows;y++){
            // 先在已确认的真实行附近挑候选，避免全图最强的状态栏假边缘抢走有效血条。
            // 附近也没有合格边缘时仍返回未知，不强行把固定坐标当作血条。
            if(!Float.isNaN(expectedAnchor)&&Math.abs(y-expectedAnchor)>6)continue;
            if(Rect.intersects(excluded,new Rect(170,y-26,330,y+5)))continue;
            int hits=0;for(int x=176;x<320;x++){float line=Math.max(light[(y-1)*WIDTH+x],light[y*WIDTH+x]),outside=(light[(y+2)*WIDTH+x]+light[(y+3)*WIDTH+x])*.5f;if(line>25&&line-outside>7)hits++;}
            float score=hits/144f;if(score<=.80f)continue;
            float rank=Float.isNaN(expectedAnchor)?score:score*.1f-Math.abs(y-expectedAnchor);
            if(rank<=bestBorder)continue;
            int pale=0;for(int yy=y-12;yy<y-2;yy++)for(int x=182;x<305;x++){int c=pixels[yy*WIDTH+x],r=c>>16&255,g=c>>8&255,b=c&255;int min=Math.min(r,Math.min(g,b)),max=Math.max(r,Math.max(g,b));if(min>120&&max-min<85)pale++;}
            if(pale<(Float.isNaN(expectedAnchor)?18:8)||pale>700)continue;
            bestBorder=rank;barBottom=y;
        }
        if(barBottom<0)return null;
        int[] slots=new int[5],bottoms=new int[5];float[][] health=new float[5][];
        for(int n=0;n<5;n++){
            Bar bar=n==2?new Bar(PRESENT,barBottom):bar(pixels,light,rows,excluded,AREAS[n][0],AREAS[n][1],barBottom+AREAS[n][2]);
            slots[n]=bar.state;bottoms[n]=bar.bottom;
            // 外围发光能量点可能让框的上沿比底沿更强。它只用于确认“有框”，
            // 血量仍从相对中央固定的填充行读取，不能拿上方状态图标当成空血。
            health[n]=bar.state==PRESENT?health(pixels,rows,n,barBottom+AREAS[n][2]):null;
        }
        // 只比较血条框附近的小块亮度；人物和技能仍在动，不应让已经清晰的血条一直无法确认。
        int[] scene=new int[5*16*6];int n=0;
        for(int slot=0;slot<5;slot++)for(int y=0;y<6;y++)for(int x=0;x<16;x++){
            int px=AREAS[slot][0]+(AREAS[slot][1]-AREAS[slot][0]-1)*x/15,py=bottoms[slot]-11+y*3;
            scene[n++]=slots[slot]!=PRESENT||py<0||py>=rows||excluded.contains(px,py)?-1:Math.round(light[py*WIDTH+px]);
        }
        return new Reading(0,new int[0],slots,barBottom,scene,health,centerFill(frame,barBottom));
    }
    /** 同一中央血条的细微增长会被外围宽区间掩盖；保留原图像素，避免低血量时整像素取整。 */
    private static float centerFill(Bitmap frame,int bottom){
        float scale=frame.getWidth()/(float)WIDTH;
        int left=Math.round(172*scale),right=Math.round(326*scale),top=Math.round((bottom-8)*scale),end=Math.round((bottom-1)*scale);
        if(top<0||end>frame.getHeight()||end<=top||right<=left)return Float.NaN;
        int width=right-left;int[] colors=new int[width*(end-top)];frame.getPixels(colors,0,width,left,top,width,end-top);
        float[] red=new float[width];
        for(int x=0;x<width;x++)for(int y=0;y<end-top;y++){int c=colors[y*width+x];red[x]=Math.max(red[x],(c>>16&255)-Math.max(c>>8&255,c&255));}
        int start=Math.max(1,Math.round(2*scale)),stop=Math.min(width,Math.round(6*scale));if(stop<=start)return Float.NaN;
        float[] plateau=Arrays.copyOfRange(red,start,stop);Arrays.sort(plateau);float strength=plateau[plateau.length/2];
        // 空血、遮挡或红色光效不提供精细长度证据；普通血条仍走原有宽区间判定。
        if(strength<65)return Float.NaN;
        float mass=0;int tail=0;
        for(int x=0;x<width;x++){mass+=Math.max(0,Math.min(1,(red[x]/strength-.3f)/.7f));if(x>width*.8f&&red[x]>strength*.4f)tail++;}
        if(tail>width*.03f)return Float.NaN;
        return mass/scale;
    }
    private static final class Bar {final int state,bottom;Bar(int state,int bottom){this.state=state;this.bottom=bottom;}}
    private static Bar bar(int[] pixels,float[] light,int height,Rect excluded,int left,int right,int expected){
        if(right-left<25||expected-23<0||expected+9>=height||Rect.intersects(excluded,new Rect(left,expected-23,right,expected+9)))return new Bar(UNKNOWN,expected);
        float best=0;int cyanBest=0,bottom=expected;
        for(int y=expected-8;y<expected+5;y++){
            int hits=0,cyan=0;
            for(int x=left;x<right;x++){
                float line=Math.max(light[(y-1)*WIDTH+x],light[y*WIDTH+x]),inside=0;
                for(int k=y-6;k<y-2;k++)inside+=light[k*WIDTH+x]*.25f;
                float outside=(light[(y+2)*WIDTH+x]+light[(y+3)*WIDTH+x])*.5f;
                if(line-inside>9&&line-outside>5&&inside<70)hits++;
                for(int k=y-14;k<y-3;k++){int c=pixels[k*WIDTH+x],r=c>>16&255,g=c>>8&255,b=c&255;if(g>r*1.10&&b>r*1.06&&g>95&&b>95)cyan++;}
            }
            float score=hits/(float)(right-left);if(score>best){best=score;cyanBest=cyan;bottom=y;}
        }
        int state=best>=.60f||best>=.35f&&cyanBest>=3?PRESENT:best<.30f&&cyanBest<3?ABSENT:UNKNOWN;
        return new Bar(state,bottom);
    }
    /** 红色填充仅作血条前后对应的辅助特征，绝不以血量低/填充消失直接判阵亡。 */
    private static float[] health(int[] pixels,int height,int slot,int bottom){
        int left=Math.max(0,BAR_START[slot]+2),right=Math.min(WIDTH,BAR_END[slot]-2),last=-1;
        for(int x=left;x<right;x++){
            int red=0;for(int y=Math.max(0,bottom-8);y<Math.min(height,bottom-1);y++){int c=pixels[y*WIDTH+x],r=c>>16&255,g=c>>8&255,b=c&255;if(r>90&&r-g>40&&r-b>35)red++;}
            if(red>=2)last=x;
        }
        float width=BAR_END[slot]-BAR_START[slot];
        if(last<0)return new float[]{0,Math.max(.06f,-BAR_START[slot]/width+.06f)};
        float fraction=(last-BAR_START[slot])/width;
        return new float[]{Math.max(0,fraction-.07f),BAR_END[slot]>WIDTH&&last>=WIDTH-4?1:Math.min(1,fraction+.07f)};
    }
    static boolean consistent(Reading a,Reading b){
        if(a==null||b==null||a.center!=b.center||Math.abs(a.anchor-b.anchor)>3||!Arrays.equals(a.states,b.states))return false;
        int count=0,changed=0;for(int n=0;n<a.scene.length;n++)if(a.scene[n]>=0&&b.scene[n]>=0){count++;if(Math.abs(a.scene[n]-b.scene[n])>25)changed++;}
        return count>=64&&changed<count*.25f;
    }
}
