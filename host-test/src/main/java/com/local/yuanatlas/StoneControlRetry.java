package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

/** 整屏控件漏字时局部放大重读，仍由背包布局校验决定是否通过，绝不伪造控件。 */
final class StoneControlRetry {
 static JSONArray recover(Bitmap bitmap,JSONArray lines,TextRecognizer recognizer,Rect overlay)throws Exception{
  if(bitmap.getHeight()<bitmap.getWidth()*1.4)return lines; // 小裁剪不按完整竖屏背包推测控件区域。
  try{new StoneCaptureLayout(bitmap,lines,overlay,null);return lines;}
  catch(StoneCaptureFrame.LayoutException missing){/* 冷启动没有历史锚点，也可从当前像素重新识别。 */}
  JSONArray result=new JSONArray(lines.toString());int w=bitmap.getWidth(),h=bitmap.getHeight();
  Rect[] areas={new Rect(0,(int)(h*.06),w,(int)(h*.30)),new Rect(0,(int)(h*.78),(int)(w*.35),(int)(h*.99))};
  for(Rect area:areas){
   Bitmap crop=Bitmap.createBitmap(bitmap,area.left,area.top,area.width(),area.height());
   Bitmap enlarged=Bitmap.createScaledBitmap(crop,area.width()*2,area.height()*2,true);
   try{
    Text text=Tasks.await(recognizer.process(InputImage.fromBitmap(enlarged,0)),15,TimeUnit.SECONDS);
    for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
     add(result,line.getText(),line.getBoundingBox(),area,overlay);
     // 控件可能被拆成相邻元素；交由现有几何合并规则处理，不能拼接远处文字。
     if(line.getElements().size()>1)for(Text.Element element:line.getElements())add(result,element.getText(),element.getBoundingBox(),area,overlay);
    }
   }catch(Exception failed){/* 局部模型失败时保留原始整屏结果，外层继续正常定位重试。 */}
   finally{if(enlarged!=crop)enlarged.recycle();crop.recycle();}
  }
  return result;
 }
 private static void add(JSONArray lines,String text,Rect box,Rect area,Rect overlay)throws Exception{
  if(box==null)return;String clean=StoneCaptureFrame.clean(text);
  if(!StoneCaptureFrame.backpackTitle(clean)&&!clean.matches("[星石背包主辅分解]{1,3}")&&!StoneCaptureFrame.decomposeLabel(clean))return;
  double x=area.left+box.left/2.0,y=area.top+box.top/2.0,w=box.width()/2.0,h=box.height()/2.0;
  if(overlay!=null&&x<overlay.right&&x+w>overlay.left&&y<overlay.bottom&&y+h>overlay.top)return;
  for(int n=0;n<lines.length();n++){
   JSONObject old=lines.getJSONObject(n);
   if(StoneCaptureFrame.clean(old.optString("text")).equals(clean)&&Math.abs(old.optDouble("x")-x)<8&&Math.abs(old.optDouble("y")-y)<8)return;
  }
  lines.put(new JSONObject().put("text",text).put("x",x).put("y",y).put("width",w).put("height",h).put("controlRetried",true));
 }
}
