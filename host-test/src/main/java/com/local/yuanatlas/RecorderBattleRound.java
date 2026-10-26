package com.local.yuanatlas;

import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只读取战斗标题的“回合 N”或“回合 N/M”，不把波次和效果剩余回合当作当前回合。 */
final class RecorderBattleRound {
    // 已复现 OCR 在低分辨率读成“同合”、浅色背景读成“個合/画合”；数字仍按完整标题解析。
    // 标题定位与解析共用同一模式，避免读数成功却定位不到标题，反复保留旧回合。
    static final Pattern LABEL=Pattern.compile("[回同個画]\\s*合");
    private static final Pattern COUNTER=Pattern.compile(LABEL.pattern()+"\\s*([0-9]{1,3})\\s*(?:/\\s*([0-9]{1,3}))?\\s*$");
    static final class Reading {
        final int current,limit;
        Reading(int current,int limit){this.current=current;this.limit=limit;}
    }
    static Reading parse(String text){
        // 数字之间的空白可能是漏识别的分隔符，不能先去掉空白再拼成一个回合数。
        String value=Normalizer.normalize(text,Normalizer.Form.NFKC);
        if(value.contains("剩余")||value.contains("剩餘"))return null;
        Matcher match=COUNTER.matcher(value);if(!match.find())return null;
        int current=Integer.parseInt(match.group(1)),limit=match.group(2)==null?0:Integer.parseInt(match.group(2));
        if(current<1||match.group(2)!=null&&(limit<1||current>limit))return null;
        return new Reading(current,limit);
    }
    static Reading read(Text text){
        List<Text.Line> lines=new ArrayList<>();for(Text.TextBlock block:text.getTextBlocks())lines.addAll(block.getLines());
        Reading found=null;
        for(Text.Line line:lines){
            List<String> candidates=new ArrayList<>();candidates.add(line.getText());
            Rect first=line.getBoundingBox();
            // 部分机型把“回合”和数字分成两个文字块，只连接同一行中紧邻的右侧文字。
            if(first!=null)for(Text.Line other:lines){
                Rect second=other.getBoundingBox();if(other==line||second==null)continue;
                int height=Math.max(first.height(),second.height());
                if(second.left>=first.right-height/4&&second.left-first.right<=height*4&&Math.abs(first.centerY()-second.centerY())<=height*.6f)
                    candidates.add(line.getText()+" "+other.getText());
            }
            for(String candidate:candidates){
                Reading next=parse(candidate);if(next==null)continue;
                if(found!=null&&(found.current!=next.current||found.limit>0&&next.limit>0&&found.limit!=next.limit))return null;
                if(found==null||next.limit>0)found=next;
            }
        }
        return found;
    }
    /** 连续两次一致才确认；相同回合不反复覆盖用户手动选择的续行。 */
    static final class Stability {
        private int candidate=-1,count,last=-1;
        int observe(int round){
            if(candidate!=round){candidate=round;count=1;return 0;}
            count=Math.min(2,count+1);if(count<2)return 0;
            if(last==round)return 1;
            last=round;return 2;
        }
        void miss(){candidate=-1;count=0;}
        void reset(){miss();last=-1;}
    }
}
