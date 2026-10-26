package com.local.yuanatlas;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.webkit.WebView;import org.json.*;import java.util.concurrent.*;
/** 在原 App 的隔离副本中验证已有 JS 桥接到新插件，无真实游戏手势。 */
public final class HostPluginChecks extends Instrumentation {
 private Activity activity;private WebView web;
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 private String js(String code)throws Exception{String[] value={null};CountDownLatch done=new CountDownLatch(1);runOnMainSync(()->web.evaluateJavascript(code,result->{value[0]=result;done.countDown();}));check(done.await(10,TimeUnit.SECONDS),"JavaScript timeout");return value[0];}
 private Object field(Object object,String name)throws Exception{java.lang.reflect.Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 private boolean contains(View view,String text){if(view instanceof android.widget.TextView&&((android.widget.TextView)view).getText().toString().contains(text))return true;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),text))return true;}return false;}
 @Override public void onStart(){Bundle result=new Bundle();try{
 activity=startActivitySync(new Intent().setClassName(getTargetContext(),"com.local.yuanatlas.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
 web=(WebView)field(activity,"webView");
 // Instrumentation 会重启被测进程；重新绑定专用模拟器的服务，且不抑制正常无障碍服务。
 var automation=getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
 for(String command:new String[]{"settings put secure enabled_accessibility_services null","settings put secure enabled_accessibility_services com.local.yuanatlas.plugintest/com.local.yuanatlas.RuYuanCaptureService","settings put secure accessibility_enabled 1"})try(var descriptor=automation.executeShellCommand(command);var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)){input.readAllBytes();Thread.sleep(1000);}
 long until=System.currentTimeMillis()+15000;
 while(RuYuanCaptureService.instance==null&&System.currentTimeMillis()<until)Thread.sleep(100);
 check(RuYuanCaptureService.instance!=null,"host accessibility service is not connected");
 until=System.currentTimeMillis()+20000;
 while(System.currentTimeMillis()<until){String ready=js("document.readyState+':'+location.href");if(ready.contains("complete")&&!ready.contains("about:blank"))break;Thread.sleep(100);}
 Thread.sleep(2000);
 check(js("typeof window.YuanRecorderRounds.openLocalTable").contains("function"),"existing JS entry absent");
 String table;try(var input=getContext().getAssets().open("table.json")){table=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
 String accepted=js("window.__pluginResult=null;window.addEventListener('yuan-recorder-local-table',e=>window.__pluginResult=e.detail);YuanRecorderRounds.openLocalTable("+JSONObject.quote(table)+",6001)");
 check("\"\"".equals(accepted),"host rejected import: "+accepted);
 until=System.currentTimeMillis()+10000;String bridge="null";
 while(System.currentTimeMillis()<until){bridge=js("JSON.stringify(window.__pluginResult)");if(bridge.contains("ok"))break;Thread.sleep(100);}
 check(bridge.contains("true"),"host import failed: "+bridge);
 Object overlay=field(RuYuanCaptureService.instance,"recorderRounds");check(overlay!=null,"plugin overlay missing");
 View settings=(View)field(overlay,"settingsButton");runOnMainSync(settings::performClick);
 Dialog dialog=(Dialog)field(overlay,"settingsDialog");check(dialog!=null,"settings absent");
 runOnMainSync(()->{check(!contains(dialog.getWindow().getDecorView(),"调整表操作"),"editor entry leaked");check(contains(dialog.getWindow().getDecorView(),"行动时序"),"execution settings missing");dialog.dismiss();});
 // 无原图附件的转发图片必须经过插件私有 WebView 纹理解码及原生认证。
 CountDownLatch imageDone=new CountDownLatch(1);String[] imageCode={null};
 runOnMainSync(()->YuanFollow.openImage(activity,"com.yuan.follow.demo",android.net.Uri.parse("content://com.yuan.follow.plugin.test.fixtures/follow-texture-pixels.jpg"),(ok,c,m)->{imageCode[0]=c+":"+m;imageDone.countDown();}));
 check(imageDone.await(40,TimeUnit.SECONDS),"native image import timeout");check(imageCode[0].startsWith("OK:"),"native image failed: "+imageCode[0]);
 check(field(RuYuanCaptureService.instance,"recorderRounds")!=null,"image did not open overlay");
 // SDK 的关闭/取消链路也通过同一宿主服务运行。
 CountDownLatch cancelled=new CountDownLatch(1);String[] code={null};
 runOnMainSync(()->{YuanFollow.openTable(activity,"com.yuan.follow.demo",table,(ok,c,m)->{code[0]=c;cancelled.countDown();});YuanFollow.close();});
 check(cancelled.await(10,TimeUnit.SECONDS),"cancel callback missing");check("CANCELLED".equals(code[0]),"cancel result: "+code[0]);Thread.sleep(500);
 check(field(RuYuanCaptureService.instance,"recorderRounds")==null,"late import reopened overlay");
 result.putString("stream","\nPASS current App WebView bridge -> plugin overlay\nPASS execution settings present, editor absent\nPASS content URI -> private WebView texture decode -> native authentication -> overlay\nPASS pending import cancellation, no late reopen\n");finish(-1,result);
 }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);}}
}
