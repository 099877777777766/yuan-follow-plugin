package com.yuan.follow.uni;
import android.app.Activity;import android.content.Context;import android.net.Uri;import com.local.yuanatlas.YuanFollow;
import io.dcloud.feature.uniapp.common.UniModule;import io.dcloud.feature.uniapp.annotation.UniJSMethod;import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import java.util.Map;import java.util.HashMap;
/** uni-app Android 原生插件；只委托核心 API，不暴露编辑器或任意脚本执行。 */
public final class YuanFollowModule extends UniModule {
 private YuanFollow.Listener listener;
 private Context context(){return mUniSDKInstance.getContext();}
 private void reply(UniJSCallback cb,boolean ok,String code,String message){if(cb==null)return;Map<String,Object> value=new HashMap<>();value.put("ok",ok);value.put("code",code);value.put("message",message);cb.invoke(value);}
 private String field(Map<String,Object> options,String name){Object value=options==null?null:options.get(name);return value==null?"":value.toString();}
 private Activity activity(){if(!(context() instanceof Activity))throw new IllegalStateException("当前页面不是 Android Activity");return (Activity)context();}
 @UniJSMethod(uiThread=true) public void status(UniJSCallback cb){reply(cb,true,"OK",YuanFollow.status(context()));}
 @UniJSMethod(uiThread=true) public void openSettings(UniJSCallback cb){try{YuanFollow.openAccessibilitySettings(activity());reply(cb,true,"OK","已打开系统设置");}catch(Exception e){reply(cb,false,"SETTINGS_FAILED",e.getMessage());}}
 @UniJSMethod(uiThread=true) public void openTable(Map<String,Object> options,UniJSCallback cb){String json=field(options,"table");Object value=options==null?null:options.get("table");if(value instanceof Map)json=new org.json.JSONObject((Map)value).toString();YuanFollow.openTable(context(),field(options,"gamePackage"),json,(ok,code,message)->reply(cb,ok,code,message));}
 @UniJSMethod(uiThread=true) public void openCode(Map<String,Object> options,UniJSCallback cb){YuanFollow.openCode(context(),field(options,"gamePackage"),field(options,"code"),(ok,code,message)->reply(cb,ok,code,message));}
 @UniJSMethod(uiThread=true) public void chooseImage(Map<String,Object> options,UniJSCallback cb){try{YuanFollow.chooseImage(activity(),field(options,"gamePackage"),(ok,code,message)->reply(cb,ok,code,message));}catch(Exception e){reply(cb,false,"IMPORT_FAILED",e.getMessage());}}
 @UniJSMethod(uiThread=true) public void openImage(Map<String,Object> options,UniJSCallback cb){try{YuanFollow.openImage(activity(),field(options,"gamePackage"),Uri.parse(field(options,"uri")),(ok,code,message)->reply(cb,ok,code,message));}catch(Exception e){reply(cb,false,"IMPORT_FAILED",e.getMessage());}}
 @UniJSMethod(uiThread=true) public void pause(){YuanFollow.pause();}
 @UniJSMethod(uiThread=true) public void close(){YuanFollow.close();}
 @UniJSMethod(uiThread=true) public void diagnostics(UniJSCallback cb){reply(cb,true,"OK",YuanFollow.diagnostics(context()));}
 @UniJSMethod(uiThread=true) public void subscribe(UniJSCallback cb){unsubscribe();if(cb==null)return;listener=(type,message)->{Map<String,Object> event=new HashMap<>();event.put("type",type);event.put("message",message);cb.invokeAndKeepAlive(event);};YuanFollow.addListener(listener);}
 @UniJSMethod(uiThread=true) public void unsubscribe(){if(listener!=null){YuanFollow.removeListener(listener);listener=null;}}
 @Override public void onActivityDestroy(){unsubscribe();super.onActivityDestroy();}
}
