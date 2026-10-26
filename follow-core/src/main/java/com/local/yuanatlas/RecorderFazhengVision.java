package com.local.yuanatlas;

import android.graphics.*;

/** 读取法正卡牌左下三个圆盘的具体圣物，排除血条、立绘和圆盘外的特效。 */
final class RecorderFazhengVision {
    // 兼容原有颜色回归；实际跟打使用圣物 ID。
    static int[] read(Bitmap frame,Rect overlay,RecorderFollowVision.Layout layout,int column,int pulls){int[] ids=readItems(frame,overlay,layout,column,pulls);if(ids!=null)for(int i=0;i<ids.length;i++)ids[i]=ids[i]>=10?1:0;return ids;}
    static int[] readItems(Bitmap frame,Rect overlay,RecorderFollowVision.Layout layout,int column,int pulls){
        if(column<0||column>=5||pulls<1||pulls>5||frame.getWidth()!=layout.width||frame.getHeight()!=layout.height)return null;
        int[] colors=readAt(frame,overlay,layout,column,pulls);
        if(colors!=null)return colors;
        // 进局动画可能把缓存底边定位到血条内侧；读不到时用当前帧重新定位，再检查完整圆盘。
        // 仅用于本次圣物读取，不改执行器的手势坐标；遮挡/动画仍返回未知，由两帧确认和超时控制处理。
        RecorderFollowVision.Layout current=RecorderFollowVision.locateRelicCards(frame,layout.viewport);
        if(current!=null&&!current.cards.equals(layout.cards)){colors=readAt(frame,overlay,current,column,pulls);if(colors!=null)return colors;}
        // 等分卡列与实际边框可能有少量横向差异，右侧列更明显。只在重定位后的同一列内
        // 平移整组槽位，不单独扩大模板阈值、不逐槽找不同位置；身份冲突仍返回未知。
        if(current!=null){
            int[] recovered=null;float cell=current.cards.width()/5f;
            for(int shift:new int[]{-3,-2,2,3}){
                int[] candidate=readAt(frame,overlay,current,column,pulls,0,shift*cell*.03f);
                if(candidate==null)continue;
                if(recovered!=null&&!java.util.Arrays.equals(recovered,candidate))return null;
                recovered=candidate;
            }
            if(recovered!=null)return recovered;
        }
        // 金色血条/卡内横线可能在重新定位后仍被选为底边。只在不足半个槽距的范围校正整组，
        // 不逐槽独立搜索，避免一个图标被重复计入两槽；候选身份冲突时仍视为无法确认。
        int[] recovered=null;float cell=layout.cards.width()/5f;
        for(int shift=-4;shift<=4;shift++)if(shift!=0){
            int[] candidate=readAt(frame,overlay,layout,column,pulls,shift*cell*.03f);
            if(candidate==null)continue;
            if(recovered!=null&&!java.util.Arrays.equals(recovered,candidate))return null;
            recovered=candidate;
        }
        return recovered;
    }
    private static int[] readAt(Bitmap frame,Rect overlay,RecorderFollowVision.Layout layout,int column,int pulls){return readAt(frame,overlay,layout,column,pulls,0);}
    private static int[] readAt(Bitmap frame,Rect overlay,RecorderFollowVision.Layout layout,int column,int pulls,float offset){return readAt(frame,overlay,layout,column,pulls,offset,0);}
    private static int[] readAt(Bitmap frame,Rect overlay,RecorderFollowVision.Layout layout,int column,int pulls,float offset,float horizontal){
        float cell=layout.cards.width()/5f;
        float cx=layout.cards.left+cell*(column+.20f)+horizontal,bottom=layout.cards.bottom-cell*.49f+offset;
        int count=Math.min(pulls,3);int[] result=new int[count];
        for(int n=0;n<count;n++){
            float cy=bottom-cell*.34f*(count-1-n),radius=cell*.14f;
            Rect bounds=new Rect((int)(cx-radius-cell*.06f),(int)(cy-radius-cell*.06f),(int)Math.ceil(cx+radius+cell*.06f),(int)Math.ceil(cy+radius+cell*.06f));
            // 被浮窗挡住或超出截图不能解释成非红物品，也不能据此退出。
            if(bounds.left<0||bounds.top<0||bounds.right>frame.getWidth()||bounds.bottom>frame.getHeight()||overlay!=null&&Rect.intersects(bounds,overlay))return null;
            int id=RecorderFazhengIconMatcher.read(frame,cx,cy,cell);
            if(id<0)return null;result[n]=id;
        }
        return result;
    }
}
