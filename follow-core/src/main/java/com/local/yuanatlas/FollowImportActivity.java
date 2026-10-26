package com.local.yuanatlas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.*;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.json.JSONObject;

/** 私有导入页面承载纹理解码；仅加载插件自带资源，无宿主网页和编辑器入口。 */
public final class FollowImportActivity extends Activity {
    private int request;private WebView decoder;private boolean completed;
    private final Handler handler=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state){super.onCreate(state);request=getIntent().getIntExtra("request",-1);
        if(state!=null||!YuanFollow.current(request)){finish();return;}
        TextView notice=new TextView(this);notice.setText("正在读取跟打图片…");notice.setPadding(32,48,32,32);setContentView(notice);
        Uri uri=getIntent().getData();if(uri!=null)read(uri);else try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),701);}catch(RuntimeException e){fail("PICKER_FAILED",e.getMessage());}
    }
    @Override public void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code!=701)return;if(result!=RESULT_OK||data==null||data.getData()==null){fail("CANCELLED","已取消选图");return;}read(data.getData());}
    private void read(Uri uri){new Thread(()->{
        try(InputStream stream=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(stream==null)throw new IllegalArgumentException("无法打开图片");byte[] buffer=new byte[8192];int n;
            while((n=stream.read(buffer))!=-1){if(out.size()+n>12*1024*1024)throw new IllegalArgumentException("图片不能超过12 MB");out.write(buffer,0,n);}
            byte[] bytes=out.toByteArray();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            if(bounds.outWidth<1||bounds.outHeight<1||(long)bounds.outWidth*bounds.outHeight>50_000_000L)throw new IllegalArgumentException("图片尺寸无效或过大");
            if(!"image/png".equals(bounds.outMimeType)&&!"image/jpeg".equals(bounds.outMimeType)&&!"image/webp".equals(bounds.outMimeType))throw new IllegalArgumentException("只支持 PNG、JPEG、WebP");
            // 原图附件和纹理是两个独立副本；附件损坏仍尝试纹理，不降级为普通手抄。
            try{String code=RecorderFollowTable.embedded(bytes);if(code!=null){RecorderFollowTable table=RecorderFollowTable.decode(this,code);runOnUiThread(()->accept(table));return;}}catch(Exception ignored){}
            String data="data:"+bounds.outMimeType+";base64,"+android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP);
            runOnUiThread(()->decodeTexture(data));
        }catch(Exception|OutOfMemoryError error){runOnUiThread(()->fail("INVALID_IMAGE",error.getMessage()));}
    },"yuan-follow-image").start();}
    @SuppressWarnings("SetJavaScriptEnabled") private void decodeTexture(String data){
        if(isFinishing()||isDestroyed()||!YuanFollow.current(request)){finish();return;}
        if(androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this)==null){fail("WEBVIEW_MISSING","设备未安装可用的 Android WebView");return;}
        decoder=new WebView(this);decoder.getSettings().setJavaScriptEnabled(true);decoder.getSettings().setAllowFileAccess(false);decoder.getSettings().setAllowContentAccess(false);decoder.getSettings().setBlockNetworkLoads(true);
        decoder.addJavascriptInterface(new Object(){
            @JavascriptInterface public void result(String code,String error){runOnUiThread(()->{
                if(completed||!YuanFollow.current(request)){finish();return;}
                if(code==null||code.isEmpty()){fail("INVALID_IMAGE",error==null||error.isEmpty()?"图片中没有有效跟打码":error);return;}
                new Thread(()->{try{RecorderFollowTable table=RecorderFollowTable.decode(FollowImportActivity.this,code);runOnUiThread(()->accept(table));}catch(Exception e){runOnUiThread(()->fail("INVALID_CODE",e.getMessage()));}},"yuan-follow-decode").start();
            });}
        },"FollowDecoder");
        decoder.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));}
            @Override public void onPageFinished(WebView view,String url){if(!completed)view.evaluateJavascript("window.decodeFollow("+JSONObject.quote(data)+")",null);}
        });
        try(InputStream asset=getAssets().open("yuan-follow/decoder.html")){decoder.loadDataWithBaseURL("https://yuan-follow.invalid/",readHtml(asset),"text/html","UTF-8",null);}
        catch(Exception e){fail("DECODER_MISSING","插件图片解码资源缺失");return;}
        handler.postDelayed(()->fail("TIMEOUT","图片解码超时"),30000);
    }
    private static String readHtml(InputStream stream)throws java.io.IOException{try(ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] chunk=new byte[8192];int n;while((n=stream.read(chunk))!=-1){if(out.size()+n>256*1024)throw new java.io.IOException("解码资源过大");out.write(chunk,0,n);}return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}}
    private void accept(RecorderFollowTable table){if(completed||!YuanFollow.current(request)){finish();return;}completed=true;YuanFollow.loaded(request,table);finish();}
    private void fail(String code,String message){if(completed)return;completed=true;YuanFollow.fail(request,code,message);finish();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);if(decoder!=null){decoder.removeJavascriptInterface("FollowDecoder");decoder.destroy();decoder=null;}if(!completed)YuanFollow.fail(request,"CANCELLED","图片导入页面已关闭");super.onDestroy();}
}
