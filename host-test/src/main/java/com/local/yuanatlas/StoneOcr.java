package com.local.yuanatlas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.exifinterface.media.ExifInterface;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 中文模型打包在 APK 内，图片只在设备内解码识别，不调用远程识别服务。 */
public final class StoneOcr {
    static final int REQUEST = 2014;
    private final Activity activity;
    private final WebView webView;
    private String pending;
    private final java.util.Map<String,java.io.File> sources = new java.util.HashMap<>();
    StoneOcr(Activity activity, WebView webView) { this.activity = activity; this.webView = webView; }
    private boolean localPage() {
        String url = webView.getUrl(), start = "https://appassets.androidplatform.net/assets/www/index.html";
        return url != null && (url.equals(start) || url.startsWith(start + "#"));
    }
    @JavascriptInterface public void chooseImages(String requestId) {
        activity.runOnUiThread(() -> {
            if (!localPage() || pending != null || requestId == null || requestId.length() > 100) return;
            pending = requestId;
            for(java.io.File file:sources.values())file.delete(); sources.clear();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { activity.startActivityForResult(intent, REQUEST); }
            catch (Exception first) {
                try { activity.startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQUEST); }
                catch (Exception e) { finish(new JSONArray(), "无法打开系统图片选择器", false); }
            }
        });
    }
    boolean result(int request, int result, Intent data) {
        if (request != REQUEST) return false;
        if (pending == null) return true;
        if (result != Activity.RESULT_OK || data == null) { finish(new JSONArray(), "", true); return true; }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i=0;i<data.getClipData().getItemCount();i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        // 不设置固定张数上限；逐张识别并回传，避免整批图片积成一个巨大回调。
        new Thread(() -> {
            JSONArray pages = new JSONArray();
            try (TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())) {
                for (int i=0;i<uris.size();i++) {
                    JSONObject page;
                    try {
                        Uri uri = uris.get(i);
                        if (!"content".equals(uri.getScheme())) throw new Exception("请从系统相册或文件选择截图");
                        String sourceId=java.util.UUID.randomUUID().toString();
                        java.io.File original=new java.io.File(activity.getCacheDir(), "stone-ocr-"+sourceId);
                        try (InputStream input=activity.getContentResolver().openInputStream(uri);java.io.FileOutputStream output=new java.io.FileOutputStream(original)) {
                            byte[] buffer=new byte[8192];int read,total=0;if(input==null)throw new Exception();
                            while((read=input.read(buffer))!=-1){total+=read;if(total>20*1024*1024)throw new Exception();output.write(buffer,0,read);}
                        } catch(Exception e){original.delete();throw e;}
                        sources.put(sourceId,original);
                        try (InputStream input=new java.io.FileInputStream(original)) { page=recognize(input,recognizer); }
                        page.put("sourceId",sourceId);
                    } catch (Exception | OutOfMemoryError e) { page = new JSONObject(); page.put("error", "第"+(i+1)+"张无法识别，请换用清晰的20MB以内截图"); }
                    page.put("index", i+1); sendPage(page);
                }
                finish(pages, "", false);
            } catch (Exception e) { finish(pages, "本机识别失败，请重试", false); }
        }, "yuan-stone-ocr").start();
        return true;
    }
    // 与设备回归测试共用入口：限制字节及像素，避免超长图耗尽内存。
    static JSONObject recognize(InputStream input, TextRecognizer recognizer) throws Exception { return recognize(input,recognizer,null); }
    static JSONObject recognize(InputStream input, TextRecognizer recognizer, double[] crop) throws Exception {
        return recognize(input,recognizer,crop,null);
    }
    static JSONObject recognize(InputStream input, TextRecognizer recognizer, double[] crop, Rect overlay) throws Exception {
        if (input == null) throw new Exception("图片无法读取");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n=input.read(buffer))!=-1) { if (bytes.size()+n>20*1024*1024) throw new Exception("图片过大"); bytes.write(buffer,0,n); }
        byte[] data=bytes.toByteArray(); BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,bounds);
        if (bounds.outWidth<=0 || bounds.outHeight<=0 || (long)bounds.outWidth*bounds.outHeight>50_000_000) throw new Exception("图片尺寸无效");
        BitmapFactory.Options opts=new BitmapFactory.Options(); opts.inSampleSize=1;
        while (Math.max(bounds.outWidth,bounds.outHeight)/opts.inSampleSize>4096) opts.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,opts);
        if (bitmap==null) throw new Exception("图片解码失败");
        try {
            ExifInterface exif=new ExifInterface(new ByteArrayInputStream(data)); android.graphics.Matrix matrix=new android.graphics.Matrix();
            int rotation=exif.getRotationDegrees(); if(rotation!=0) matrix.postRotate(rotation); if(exif.isFlipped()) matrix.postScale(-1,1);
            if (!matrix.isIdentity()) { Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true); if(rotated!=bitmap){bitmap.recycle();bitmap=rotated;} }
            if(crop!=null){
                int left=(int)Math.round(crop[0]*bitmap.getWidth()),top=(int)Math.round(crop[1]*bitmap.getHeight());
                int right=Math.min(bitmap.getWidth(),(int)Math.round((crop[0]+crop[2])*bitmap.getWidth())),bottom=Math.min(bitmap.getHeight(),(int)Math.round((crop[1]+crop[3])*bitmap.getHeight()));
                if(right-left<32||bottom-top<32)throw new Exception("裁剪区域过小");
                Bitmap cut=Bitmap.createBitmap(bitmap,left,top,right-left,bottom-top);if(cut!=bitmap){bitmap.recycle();bitmap=cut;}
            }
            // 原图预览与手动裁剪边界不变，识别和品质判断使用有效游戏画面。
            try(GameViewport viewport=GameViewport.open(bitmap)){
            Bitmap game=viewport.image;Rect gameOverlay=viewport.local(overlay);
            // 边界必须先从原图确定，再遮住浮窗，避免把浮窗排除区误认作黑边。
            Text text;Bitmap textImage=CaptureOcrImage.excluding(game,gameOverlay);
            try{text=Tasks.await(recognizer.process(InputImage.fromBitmap(textImage,0)),90,TimeUnit.SECONDS);}
            finally{if(textImage!=game)textImage.recycle();}
            JSONArray lines=new JSONArray();
            for(Text.TextBlock block:text.getTextBlocks()) for(Text.Line line:block.getLines()) {
                Rect r=line.getBoundingBox(); if(r==null)continue;
                // 多列被识别为一整行时拆成元素，避免把相邻星名和等级拼成一颗。
                if(r.width()>game.getWidth()*.35 && line.getElements().size()>1) for(Text.Element element:line.getElements()) add(lines,element.getText(),element.getBoundingBox());
                else add(lines,line.getText(),r);
            }
            // 先补齐固定控件，再补读物品；否则一次标题漏字会连带跳过整个星名补读流程。
            lines=StoneControlRetry.recover(game,lines,recognizer,gameOverlay);
            lines=StoneNameRetry.recover(game,lines,recognizer,gameOverlay);
            // 预览限制长边和宽度；OCR仍使用上面的原尺寸，避免多图回调携带过大的Base64。
            float scale=Math.min(1,Math.min(640f/bitmap.getWidth(),1600f/bitmap.getHeight())); Bitmap preview=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);
            ByteArrayOutputStream jpeg=new ByteArrayOutputStream(); preview.compress(Bitmap.CompressFormat.JPEG,80,jpeg); if(preview!=bitmap)preview.recycle();
            StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))hash.append(String.format("%02x",b&255));
            JSONObject page=new JSONObject().put("width",game.getWidth()).put("height",game.getHeight()).put("hash",hash.toString()+(crop==null?"":java.util.Arrays.toString(crop))).put("lines",lines).put("preview","data:image/jpeg;base64,"+Base64.encodeToString(jpeg.toByteArray(),Base64.NO_WRAP));
            // 手动截图导入也必须执行品质过滤，不能只在游戏自动采集时标注橙色。
            page.put("qualityFiltered",true);
            try{new StoneCaptureFrame(game,page,gameOverlay);}
            catch(StoneCaptureFrame.LayoutException e){
                if(crop!=null)StoneQuality.annotateCrop(game,lines);
                // 非完整背包且不是用户主动裁剪时不猜品质，前端只接收明确标橙的物品。
            }
            return viewport.restore(page);
            }
        } finally { bitmap.recycle(); }
    }
    /** 裁剪坐标对应原图，重复裁剪始终从原图开始，不叠加损失。 */
    @JavascriptInterface public void cropImage(String requestId,String sourceId,int index,double x,double y,double w,double h){activity.runOnUiThread(()->{
        if(!localPage()||pending!=null)return;pending=requestId;
        java.io.File file=sources.get(sourceId);
        if(file==null||!Double.isFinite(x+y+w+h)||x<0||y<0||w<=0||h<=0||x+w>1.00001||y+h>1.00001){finish(new JSONArray(),"裁剪区域无效，请重新选择",false);return;}
        new Thread(()->{
            try(TextRecognizer recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());InputStream input=new java.io.FileInputStream(file)){
                JSONObject page=recognize(input,recognizer,new double[]{x,y,w,h});page.put("sourceId",sourceId).put("index",index);finish(new JSONArray().put(page),"",false);
            }catch(Exception|OutOfMemoryError e){finish(new JSONArray(),"裁剪识别失败，原截图和结果已保留",false);}
        },"yuan-stone-crop").start();
    });}
    @JavascriptInterface public void clearImages(){activity.runOnUiThread(()->{if(!localPage()||pending!=null)return;for(java.io.File file:sources.values())file.delete();sources.clear();});}
    private static void add(JSONArray lines,String text,Rect r)throws Exception{if(r!=null)lines.put(new JSONObject().put("text",text).put("x",r.left).put("y",r.top).put("width",r.width()).put("height",r.height()));}
    private void sendPage(JSONObject page) {
        final String id=pending;
        activity.runOnUiThread(()->{
            if(activity.isFinishing()||activity.isDestroyed()||!localPage())return;
            try {
                JSONObject detail=new JSONObject().put("requestId",id).put("pages",new JSONArray().put(page)).put("partial",true);
                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-stone-ocr',{detail:"+detail+"}))",null);
            } catch(Exception ignored) {}
        });
    }
    private void finish(JSONArray pages,String error,boolean cancelled){activity.runOnUiThread(()->{
        String id=pending;pending=null;if(activity.isFinishing()||activity.isDestroyed()||!localPage())return;
        try {JSONObject detail=new JSONObject().put("requestId",id).put("pages",pages).put("error",error).put("cancelled",cancelled);webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-stone-ocr',{detail:"+detail+"}))",null);}catch(Exception ignored){}
    });}
}
