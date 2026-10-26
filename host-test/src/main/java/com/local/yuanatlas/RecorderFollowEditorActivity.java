package com.local.yuanatlas;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONObject;

/** 设置按钮打开完整操作记录仪；离开即销毁内存草稿，原主窗口及存档保持原样。 */
public final class RecorderFollowEditorActivity extends MainActivity {
    private String token(){String value=getIntent().getStringExtra("followSession");return value==null?"":value;}
    @Override protected boolean temporaryRecorderEditor(){return true;}
    @Override protected String startUrl(){return super.startUrl()+"#/pages/recorder/editor?followSession="+android.net.Uri.encode(token());}
    @Override protected void configureEditorBridge(WebView view){view.addJavascriptInterface(new Bridge(view),"YuanRecorderFollowEditor");}
    private final class Bridge {
        private final WebView view;
        Bridge(WebView view){this.view=view;}
        private RecorderFollowEditSession session(String requested){
            if(!token().equals(requested))throw new IllegalArgumentException("跟打编辑会话不匹配");
            return RecorderFollowEditSession.require(requested);
        }
        @JavascriptInterface public String read(String requested){
            try{return session(requested).read();}
            catch(Exception error){try{return new JSONObject().put("error",error.getMessage()).toString();}catch(Exception ignored){return "{}";}}
        }
        @JavascriptInterface public void apply(String requested,String payload){runOnUiThread(()->{
            boolean ok=false;String error="";
            try{session(requested).apply(payload);ok=true;}catch(Exception failure){error=failure.getMessage()==null?"应用修改失败":failure.getMessage();}
            try{JSONObject detail=new JSONObject().put("token",requested).put("ok",ok).put("error",error);
                view.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-recorder-edit-result',{detail:"+detail+"}))",null);
            }catch(Exception ignored){}
        });}
        @JavascriptInterface public void close(String requested){runOnUiThread(()->{if(token().equals(requested)){closeSession();finish();}});}
    }
    private void closeSession(){try{RecorderFollowEditSession.require(token()).close();}catch(IllegalArgumentException ignored){}}
    @Override protected void onDestroy(){if(isFinishing())closeSession();super.onDestroy();}
}
