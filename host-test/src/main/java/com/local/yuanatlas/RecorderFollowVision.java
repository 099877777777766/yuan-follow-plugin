package com.local.yuanatlas;

import android.graphics.*;

/** 定位竖屏五列卡牌及圆圈；执行器在进局时缓存已识别的圈，不在每步操作前反复检查。 */
final class RecorderFollowVision {
    private static final float[] COS=new float[32],SIN=new float[32];
    static{for(int i=0;i<32;i++){COS[i]=(float)Math.cos(i*2*Math.PI/32);SIN[i]=(float)Math.sin(i*2*Math.PI/32);}}
    static final class Layout {
        final RectF cards;final int width,height;final Rect viewport;
        Layout(RectF cards,int w,int h){this(cards,w,h,new Rect(0,0,w,h));}
        Layout(RectF cards,int w,int h,Rect viewport){this.cards=cards;width=w;height=h;this.viewport=new Rect(viewport);}
        float x(float value){return viewport.left+viewport.width()*value;}
        float y(float value){return viewport.top+viewport.height()*value;}
        PointF portrait(int column){return new PointF(cards.left+cards.width()*(column+.5f)/5f,cards.top+cards.height()*.48f);}
        // 卡牌上方的能量图标和卡内“圈”按钮不同；用已定位的卡宽推算图标上沿，不增加截图/识别。
        float upperIconsTop(){return Math.max(0,cards.top-cards.width()/5f*.80f);}
    }
    /** 开始画面被技能特效遮住边框时，使用游戏竖屏五列的常规相对位置。 */
    static Layout standard(int width,int height){return new Layout(new RectF(width*.018f,height*.695f,width*.982f,height*.95f),width,height);}
    static Layout standard(Bitmap frame){
        return standard(frame,GameViewport.detect(frame));
    }
    static Layout standard(Bitmap frame,Rect v){
        Layout local=standard(v.width(),v.height());local.cards.offset(v.left,v.top);
        return new Layout(local.cards,frame.getWidth(),frame.getHeight(),v);
    }
    private static boolean gold(int color){int r=Color.red(color),g=Color.green(color),b=Color.blue(color);return r>105&&g>85&&b<r*.85&&g>b*1.06;}
    private static float light(int color){return Color.red(color)*.299f+Color.green(color)*.587f+Color.blue(color)*.114f;}
    static Layout locate(Bitmap image){
        return locate(image,GameViewport.detect(image));
    }
    static Layout locate(Bitmap image,Rect viewport){return locate(image,viewport,false);}
    /** 圣物兜底读取允许卡框贴近游戏区域下沿；仍校验金色横边及五列竖边。 */
    static Layout locateRelicCards(Bitmap image,Rect viewport){return locate(image,viewport,true);}
    private static Layout locate(Bitmap image,Rect viewport,boolean includeBottomEdge){
        Bitmap game=Bitmap.createBitmap(image,viewport.left,viewport.top,viewport.width(),viewport.height());
        try{
            Layout local=locateGame(game,includeBottomEdge);if(local==null)return null;
            local.cards.offset(viewport.left,viewport.top);return new Layout(local.cards,image.getWidth(),image.getHeight(),viewport);
        }finally{if(game!=image)game.recycle();}
    }
    private static Layout locateGame(Bitmap image,boolean includeBottomEdge){
        int w=image.getWidth(),h=image.getHeight();if(h<w*1.45f||h>w*2.6f)return null;
        // 五张卡的顶部和底部都必须有跨列的金色边界，避免把战斗外画面当作按钮。
        // 黑边裁剪可能恰好截至卡牌底框；原来的 98% 上限会漏掉真底边，误选卡内装饰线。
        int top=line(image,.63f,.79f),bottom=line(image,.88f,includeBottomEdge?1f:.98f);
        if(top<0||bottom<0||bottom-top<h*.15f)return null;
        RectF cards=new RectF(w*.018f,top,w*.982f,bottom);
        for(int c=0;c<5;c++){
            int hits=0,x=Math.round(cards.left+cards.width()*c/5f);
            for(int y=top+5;y<bottom-5;y+=Math.max(1,h/150)){
                // 紫色、灰色卡框同样有效，检测局部明暗边缘，不要求每列都是金色。
                boolean edge=false;for(int dx=-Math.max(2,w/160);dx<Math.max(3,w/70);dx++){
                    int at=Math.max(2,Math.min(w-3,x+dx));
                    if(Math.abs(light(image.getPixel(at+2,y))-light(image.getPixel(at-2,y)))>28){edge=true;break;}
                }
                if(edge)hits++;
            }
            if(hits<8)return null;
        }
        return new Layout(cards,w,h);
    }
    private static int line(Bitmap image,float from,float to){
        int w=image.getWidth(),h=image.getHeight(),best=-1;float score=.30f;
        for(int y=(int)(h*from);y<(int)(h*to);y++){
            int hits=0,count=0;for(int x=(int)(w*.025);x<w*.975;x+=Math.max(1,w/220)){count++;if(gold(image.getPixel(x,y)))hits++;}
            float current=hits/(float)count;if(current>score){score=current;best=y;}
        }return best;
    }
    static PointF circle(Bitmap image,Layout layout,int column){
        // 用已确认的边界裁圆环，浮窗遮挡不能改变这一帧的坐标基准。
        Rect v=layout.viewport;Bitmap game=Bitmap.createBitmap(image,v.left,v.top,v.width(),v.height());
        try{RectF cards=new RectF(layout.cards);cards.offset(-v.left,-v.top);
            PointF point=circleGame(game,new Layout(cards,game.getWidth(),game.getHeight()),column);
            return point==null?null:new PointF(point.x+v.left,point.y+v.top);
        }finally{if(game!=image)game.recycle();}
    }
    private static PointF circleGame(Bitmap image,Layout layout,int column){
        // 将高分辨率圆环统一到同一采样尺度，避免环边刚好落在采样点之间而漏检。
        // 首次定位及缺失列短时补识别共用这一尺度；返回坐标仍是原始屏幕坐标。
        if(image.getWidth()>448){
            float scale=448f/image.getWidth();
            Bitmap small=Bitmap.createScaledBitmap(image,448,Math.round(image.getHeight()*scale),true);
            try{
                float sy=small.getHeight()/(float)image.getHeight();RectF c=layout.cards;
                Layout scaled=new Layout(new RectF(c.left*scale,c.top*sy,c.right*scale,c.bottom*sy),small.getWidth(),small.getHeight());
                PointF found=findCircle(small,scaled,column);
                return found==null?null:new PointF(found.x/scale,found.y/sy);
            }finally{small.recycle();}
        }
        return findCircle(image,layout,column);
    }
    private static PointF findCircle(Bitmap image,Layout layout,int column){
        float cx=layout.portrait(column).x,cell=layout.cards.width()/5;
        // 一次读取本列的小区域，在内存中计算圆周，避免数百万次跨 JNI 读取像素阻塞浮窗。
        Pixels pixels=new Pixels(image,new Rect(Math.max(0,(int)(cx-cell*.48f)),Math.max(0,(int)(layout.cards.top+layout.cards.height()*.68f-cell*.3f)),Math.min(image.getWidth(),(int)(cx+cell*.48f+1)),Math.min(image.getHeight(),(int)(layout.cards.bottom+1))));
        double best=0;PointF found=null;
        // 不匹配圈内图标，只检查闭合圆环。限制在该密探血条上方，排除属性/状态图标。
        for(float y=layout.cards.top+layout.cards.height()*.68f;y<layout.cards.bottom-layout.cards.height()*.12f;y+=Math.max(1,image.getWidth()/500f))
            for(float x=cx-cell*.14f;x<=cx+cell*.14f;x+=Math.max(1,image.getWidth()/500f))
                for(float radius=cell*.17f;radius<=cell*.27f;radius+=Math.max(1,image.getWidth()/600f)){
                    int hits=0;double strength=0;
                    for(int a=0;a<32;a++){
                        // 发光环边有厚度，保留闭合圆周要求，同时容纳缩放后的边缘偏移。
                        float peak=0,outer=pixels.sample(x,y,radius*1.28f,a),inner=pixels.sample(x,y,radius*.72f,a);
                        for(float k=.90f;k<=1.101f;k+=.04f)peak=Math.max(peak,pixels.sample(x,y,radius*k,a));
                        float edge=peak-Math.max(outer,inner);if(edge>18){hits++;strength+=Math.min(80,edge);}
                    }
                    if(hits>=23&&strength>best){best=strength;found=new PointF(x,y);}
                }
        return found;
    }
    private static final class Pixels{
        final Rect bounds;final float[] values;
        Pixels(Bitmap image,Rect bounds){this.bounds=bounds;int[] colors=new int[bounds.width()*bounds.height()];image.getPixels(colors,0,bounds.width(),bounds.left,bounds.top,bounds.width(),bounds.height());values=new float[colors.length];for(int i=0;i<colors.length;i++)values[i]=light(colors[i]);}
        float sample(float x,float y,float radius,int angle){int px=Math.round(x+COS[angle]*radius)-bounds.left,py=Math.round(y+SIN[angle]*radius)-bounds.top;return px<0||py<0||px>=bounds.width()||py>=bounds.height()?0:values[py*bounds.width()+px];}
    }
}
