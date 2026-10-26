package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONObject;

/** 游戏有效画面与屏幕坐标之间的唯一边界；平板黑边不参与识别比例和手势计算。 */
final class GameViewport implements AutoCloseable {
    final Bitmap image;
    final Rect bounds;
    private final Bitmap source;

    private GameViewport(Bitmap source,Rect bounds){
        this.source=source;this.bounds=new Rect(bounds);
        // mutable Bitmap 的全尺寸 createBitmap 也可能复制；显式复用，避免重复标准化。
        image=bounds.equals(new Rect(0,0,source.getWidth(),source.getHeight()))?source:Bitmap.createBitmap(source,bounds.left,bounds.top,bounds.width(),bounds.height());
    }
    static GameViewport open(Bitmap source){return new GameViewport(source,detect(source));}
    // 图鉴和配队保留已有的纵向布局校准，只先去掉左右留边。
    static GameViewport horizontal(Bitmap source){Rect r=detect(source);r.top=0;r.bottom=source.getHeight();return new GameViewport(source,r);}
    Rect local(Rect screen){
        if(screen==null)return null;
        Rect r=new Rect(screen);if(!r.intersect(bounds))return null;r.offset(-bounds.left,-bounds.top);return r;
    }
    @Override public void close(){if(image!=source)image.recycle();}

    static Rect detect(Bitmap image){return detect(image,18);}
    static Rect detect(Bitmap image,int threshold){
        return detect(image,threshold,null);
    }
    /** 忽略浮窗覆盖的采样点；不能把浮窗当游戏内容，也不能把遮挡区补黑后当成黑边证据。 */
    static Rect detect(Bitmap image,Rect overlay){
        Rect excluded=overlay==null||overlay.isEmpty()?null:new Rect(overlay);
        // 压缩与缩小后的亮色边缘可能越出整数坐标一两个像素，一并排除，避免细线撑宽画面。
        if(excluded!=null)excluded.inset(-Math.max(2,image.getWidth()/500),-Math.max(2,image.getWidth()/500));
        return detect(image,18,excluded);
    }
    private static Rect detect(Bitmap image,int threshold,Rect overlay){
        int w=image.getWidth(),h=image.getHeight(),left=0,right=w,top=0,bottom=h;
        // 纵向采样跳过顶部工具栏，并允许黑边上少量悬浮图标/JPEG噪声。
        // 连续整列才算留边；不按设备宽高比强行裁掉游戏内容。
        int minimum=Math.max(8,Math.round(h*.36f)),limit=Math.max(0,w-minimum);
        while(left<limit&&darkColumn(image,left,threshold,overlay))left++;
        while(right>left+minimum&&darkColumn(image,right-1,threshold,overlay))right--;
        if((left==limit&&limit>0&&darkColumn(image,left,threshold,overlay))||(right<=left+minimum&&darkColumn(image,right-1,threshold,overlay)))return new Rect(0,0,w,h);
        // 手机录屏常带一两个暗色编码像素，不能因此改动原有 OCR 尺度。
        int minimumBar=Math.max(3,w/1000);
        if(left<minimumBar)left=0;
        if(w-right<minimumBar)right=w;
        while(top<h/6&&darkRow(image,left,right,top,threshold,overlay))top++;
        while(bottom>h*5/6&&darkRow(image,left,right,bottom-1,threshold,overlay))bottom--;
        // 黑屏/加载遮罩不构成边界证据，保持完整画面让上层返回未知。
        if(top==h/6||bottom==h*5/6)return new Rect(left,0,right,h);
        return new Rect(left,top,right,bottom);
    }
    private static boolean visible(int color,int threshold){return Math.max(Color.red(color),Math.max(Color.green(color),Color.blue(color)))>threshold;}
    private static boolean darkColumn(Bitmap image,int x,int threshold,Rect overlay){
        int colored=0,total=0,expected=0,h=image.getHeight();
        for(int y=h/8;y<h*7/8;y+=Math.max(1,h/180)){expected++;if(overlay!=null&&overlay.contains(x,y))continue;total++;if(visible(image.getPixel(x,y),threshold))colored++;}
        return total>=expected*.35&&total>0&&colored<total*.12;
    }
    private static boolean darkRow(Bitmap image,int left,int right,int y,int threshold,Rect overlay){
        int colored=0,total=0,expected=0;for(int x=left;x<right;x+=Math.max(1,(right-left)/160)){expected++;if(overlay!=null&&overlay.contains(x,y))continue;total++;if(visible(image.getPixel(x,y),threshold))colored++;}
        return total>=expected*.35&&total>0&&colored<total*.08;
    }
    /** 局部 OCR 返回原图坐标，预览、裁剪及点击始终使用同一坐标系。 */
    JSONObject restore(JSONObject page)throws Exception{
        int localWidth=page.getInt("width"),localHeight=page.getInt("height");
        page.put("width",source.getWidth()).put("height",source.getHeight())
            .put("left",bounds.left+page.optDouble("left",0)).put("contentWidth",page.optDouble("contentWidth",localWidth))
            .put("top",bounds.top+page.optDouble("top",0)).put("contentHeight",page.optDouble("contentHeight",localHeight));
        for(String key:new String[]{"lines","popupLines","detailLines"})offset(page.optJSONArray(key));
        JSONArray cells=page.optJSONArray("codeCells");if(cells!=null)for(int i=0;i<cells.length();i++)offset(cells.optJSONArray(i));
        JSONObject popup=page.optJSONObject("popup");if(popup!=null){for(String key:new String[]{"left","right"})popup.put(key,popup.getDouble(key)+bounds.left);for(String key:new String[]{"top","bottom"})popup.put(key,popup.getDouble(key)+bounds.top);}
        JSONObject grid=page.optJSONObject("fateGrid");if(grid!=null){for(String key:new String[]{"left","right"})grid.put(key,grid.getDouble(key)+bounds.left);for(String key:new String[]{"top","bottom"})grid.put(key,grid.getDouble(key)+bounds.top);}
        return page;
    }
    private void offset(JSONArray lines)throws Exception{if(lines!=null)for(int i=0;i<lines.length();i++){JSONObject line=lines.getJSONObject(i);line.put("x",line.getDouble("x")+bounds.left).put("y",line.getDouble("y")+bounds.top);}}
}
