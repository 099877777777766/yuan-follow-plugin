package com.local.yuanatlas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 系统选图后在本机识别图片；悬浮窗与图片导入存档是两个独立入口。 */
final class RecorderRoundLauncher {
    static final int REQUEST=2013;
    private final Activity activity;
    private final WebView webView;
    private volatile boolean pending;
    private volatile int localGeneration;
    private Bitmap waitingBitmap;
    private String waitingWarning="";
    private int imageToken;
    private android.app.AlertDialog recoveryDialog;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    RecorderRoundLauncher(Activity activity,WebView webView){this.activity=activity;this.webView=webView;}
    @JavascriptInterface public void setTheme(String palette){
        try{
            RecorderOverlayTheme theme=RecorderOverlayTheme.save(activity,palette);
            activity.runOnUiThread(()->{RuYuanCaptureService service=RuYuanCaptureService.instance;if(service!=null)service.updateRecorderTheme(theme);});
        }catch(Exception ignored){/* 非法主题不覆盖已有配色，也不影响选图及跟打会话。 */}
    }
    /** 设置中的授权不代表服务已绑定；页面可短暂等待 disconnected，其他状态立即处理。 */
    @JavascriptInterface public String connectionState(){
        RuYuanCaptureService service=RuYuanCaptureService.instance;
        return service==null?(CaptureDiagnostics.enabled(activity)?"disconnected":"disabled"):service.running()?"busy":"ready";
    }
    @JavascriptInterface public String status(){
        switch(connectionState()){
            case "disabled":return "请先开启鸢家百宝袋无障碍服务";
            case "disconnected":return "无障碍开关已开启，但服务未连接。请点“检查无障碍服务”，在系统设置中关闭后重新开启鸢家百宝袋服务，再返回重试。";
            case "busy":return "请先结束当前游戏采集";
            default:return "";
        }
    }
    @JavascriptInterface public void diagnostics(){activity.runOnUiThread(()->{
        String report=CaptureDiagnostics.report(activity);android.widget.TextView text=new android.widget.TextView(activity);text.setText(report);text.setTextSize(12);text.setTextIsSelectable(true);int padding=Math.round(18*activity.getResources().getDisplayMetrics().density);text.setPadding(padding,padding,padding,padding);
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(text);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(activity).setTitle("运行诊断").setView(scroll).setNegativeButton("关闭",null).setPositiveButton("复制诊断",(d,w)->{
            ((android.content.ClipboardManager)activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("鸢家百宝袋运行诊断",report));
            android.widget.Toast.makeText(activity,"诊断信息已复制",android.widget.Toast.LENGTH_SHORT).show();
        }).create();dialog.show();RecorderOverlayTheme theme=RecorderOverlayTheme.load(activity);text.setTextColor(theme.ink);scroll.setBackgroundColor(theme.surface);dialog.getButton(-1).setTextColor(theme.accent);dialog.getButton(-2).setTextColor(theme.accent);
    });}
    @JavascriptInterface public void settings(){activity.runOnUiThread(()->{
        if(activity.isFinishing()||activity.isDestroyed())return;
        if(!"disconnected".equals(connectionState())){openAccessibilitySettings();return;}
        if(recoveryDialog!=null&&recoveryDialog.isShowing())return;
        // Android 只允许用户从系统设置重新启用无障碍服务，不能用 startService 伪造连接。
        recoveryDialog=new android.app.AlertDialog.Builder(activity).setTitle("重新连接无障碍服务")
            .setMessage("系统保留了开启状态，但服务尚未连接。\n\n1. 在系统无障碍设置中找到“鸢家百宝袋”。\n2. 先关闭服务，再重新开启并确认系统提示。\n3. 返回 App，再次打开悬浮窗。\n\n若仍未连接，请复制运行诊断后重启设备再试。")
            .setNegativeButton("取消",null).setNeutralButton("运行诊断",(d,w)->diagnostics())
            .setPositiveButton("前往设置",(d,w)->openAccessibilitySettings()).create();
        // 关闭回调可能晚于下一次打开，旧弹窗不能清除新弹窗的引用。
        recoveryDialog.setOnDismissListener(d->{if(recoveryDialog==d)recoveryDialog=null;});recoveryDialog.show();
    });}
    private void openAccessibilitySettings(){
        try{activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
        catch(android.content.ActivityNotFoundException|SecurityException error){
            CaptureDiagnostics.event(activity,"无法打开无障碍设置："+error.getClass().getSimpleName());
            publish("无法打开系统无障碍设置，请手动进入系统设置，关闭后重新开启鸢家百宝袋无障碍服务");
            android.widget.Toast.makeText(activity,"请手动进入系统设置中的无障碍服务",android.widget.Toast.LENGTH_LONG).show();
        }
    }
    @JavascriptInterface public String chooseImage(){
        String problem=status();if(!problem.isEmpty())return problem;
        activity.runOnUiThread(()->{
            if(pending)return;
            try{
                pending=true;
                Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivityForResult(picker,REQUEST);
            }
            catch(Exception e){pending=false;publish("无法打开图片选择器");}
        });
        return "";
    }
    /** 已保存操作表直达同一悬浮窗，保持其手动/自动授权；只载入，不自动发出手势。 */
    @JavascriptInterface public synchronized String openLocalTable(String payload,int token){return openTable(payload,token,null);}
    /** 编辑页专用入口：返回目标只接受本机操作表路由，不进入图片分享协议。 */
    @JavascriptInterface public synchronized String openEditorTable(String payload,int token,String route){
        if(!MainActivity.validRecorderReturnRoute(route))return "操作记录仪返回地址无效";
        return openTable(payload,token,route);
    }
    private String openTable(String payload,int token,String route){
        String problem=status();if(!problem.isEmpty())return problem;
        if(pending)return "正在处理操作表，请稍后再试";
        if(payload==null||payload.length()>1024*1024)return "操作表数据过大";
        pending=true;int generation=++localGeneration;
        new Thread(()->{
            RecorderFollowTable table=null;String failure="";
            try{table=RecorderFollowTable.parseLocal(payload);}catch(Exception e){failure=e.getMessage()==null?"操作表读取失败":e.getMessage();}
            RecorderFollowTable loaded=table;String error=failure;
            activity.runOnUiThread(()->{
                if(generation!=localGeneration||activity.isFinishing()||activity.isDestroyed())return;
                boolean ok=false;String message=error;
                try{
                    if(loaded==null)throw new IllegalArgumentException(error);
                    RuYuanCaptureService service=RuYuanCaptureService.instance;
                    if(service==null||service.running())throw new IllegalArgumentException("无障碍服务已关闭或采集任务已开始");
                    // 回调仅属于这次悬浮窗；普通选图、本地表列表及实时同步都不带返回入口。
                    Runnable back=route==null?null:()->{
                        Intent intent=new Intent(service,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        intent.putExtra(MainActivity.RECORDER_RETURN_ROUTE,route);
                        service.closeRecorderRounds();
                        service.startActivity(intent);
                    };
                    service.showRecorderFollow(loaded,back);ok=true;
                    message=loaded.allowAutoFollow?"操作表已载入，切换到游戏后勾选开始跟打":"操作表已载入，切换到游戏即可手动抄表";
                }catch(Exception e){message=e.getMessage()==null?"悬浮窗打开失败":e.getMessage();}
                finally{pending=false;publishLocal(token,ok,message);}
            });
        },"yuan-recorder-local-table").start();
        return "";
    }
    private void publishLocal(int token,boolean ok,String message){
        try{JSONObject detail=new JSONObject().put("token",token).put("ok",ok).put("message",message);
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-recorder-local-table',{detail:"+detail+"}))",null);
        }catch(Exception ignored){}
    }
    boolean result(int request,int code,Intent data){
        if(request!=REQUEST)return false;
        if(!pending)return true;
        if(code!=Activity.RESULT_OK||data==null||data.getData()==null){pending=false;publish("已取消选图");return true;}
        Uri uri=data.getData();
        new Thread(()->read(uri),"yuan-recorder-round-reader").start();return true;
    }
    private void read(Uri uri){
        Bitmap bitmap=null;
        try(InputStream input=activity.getContentResolver().openInputStream(uri);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            if(input==null)throw new IllegalArgumentException("无法读取图片");
            byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1){if(output.size()+n>12*1024*1024)throw new IllegalArgumentException("图片不能超过12 MB");output.write(buffer,0,n);}
            byte[] bytes=output.toByteArray();BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(options.outWidth<400||options.outHeight<300||(long)options.outWidth*options.outHeight>50_000_000L)throw new IllegalArgumentException("请选择完整、清晰的操作表图片");
            if(!"image/png".equals(options.outMimeType)&&!"image/jpeg".equals(options.outMimeType)&&!"image/webp".equals(options.outMimeType))throw new IllegalArgumentException("请选择 JPG、PNG 或 WebP 图片");
            String warning="";
            // 优先从原始图片文件取加密码；仅该入口解码，不向存档导入器开放。
            try{
                String code=RecorderFollowTable.embedded(bytes);
                if(code!=null){RecorderFollowTable table=RecorderFollowTable.decode(activity,code);activity.runOnUiThread(()->showFollow(table));return;}
            }catch(Exception error){warning=error.getMessage()==null?"跟打码解码失败":error.getMessage();}
            options.inJustDecodeBounds=false;options.inSampleSize=1;
            while(options.outWidth/options.inSampleSize>1800||(long)(options.outWidth/options.inSampleSize)*(options.outHeight/options.inSampleSize)>8_000_000)options.inSampleSize*=2;
            bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(bitmap==null)throw new IllegalArgumentException("图片解码失败");
            Bitmap source=bitmap;
            String decodeWarning=warning;
            String mime=options.outMimeType;
            activity.runOnUiThread(()->{
                if(activity.isFinishing()||activity.isDestroyed()){source.recycle();pending=false;return;}
                // 像素纹理提取复用导出端的纠错算法；只回传密文，原生仍须独立认证解密。
                // 新版表的附件和底部纹理是独立副本；附件损坏也必须尝试 v2/v1 纹理后再降级手抄。
                waitingBitmap=source;waitingWarning=decodeWarning;int token=++imageToken;
                try{
                    JSONObject detail=new JSONObject().put("token",token).put("data","data:"+mime+";base64,"+android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));
                    webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-recorder-follow-image',{detail:"+detail+"}))",null);
                    handler.postDelayed(()->acceptImageCode(token,"","图片编码读取超时"),15000);
                }catch(Exception error){acceptImageCode(token,"","");}
            });
        }catch(Exception|OutOfMemoryError e){if(bitmap!=null)bitmap.recycle();pending=false;publish(e.getMessage()==null?"图片识别失败":e.getMessage());}
        finally{if((activity.getPackageName()+".selection").equals(uri.getAuthority()))try{activity.getContentResolver().delete(uri,null,null);}catch(Exception ignored){}}
    }
    @JavascriptInterface public void acceptImageCode(int token,String code,String error){activity.runOnUiThread(()->{
        if(token!=imageToken||waitingBitmap==null)return;
        Bitmap bitmap=waitingBitmap;waitingBitmap=null;handler.removeCallbacksAndMessages(null);
        // 没有读到纹理时保留原附件错误；恢复成功则无需把旧副本的错误带入悬浮表。
        String warning=error==null||error.isEmpty()?waitingWarning:error;waitingWarning="";
        if(code!=null&&!code.isEmpty()){
            try{RecorderFollowTable table=RecorderFollowTable.decode(activity,code);bitmap.recycle();showFollow(table);return;}
            catch(Exception failure){errorMessage(bitmap,failure.getMessage());return;}
        }
        recognize(bitmap,warning);
    });}
    private void errorMessage(Bitmap bitmap,String error){recognize(bitmap,error==null?"跟打码解码失败":error);}
    void destroy(){if(recoveryDialog!=null)recoveryDialog.dismiss();handler.removeCallbacksAndMessages(null);imageToken++;localGeneration++;if(waitingBitmap!=null){waitingBitmap.recycle();waitingBitmap=null;}waitingWarning="";pending=false;}
    private void showFollow(RecorderFollowTable table){
        try{RuYuanCaptureService service=RuYuanCaptureService.instance;
            if(service==null||service.running())throw new IllegalArgumentException("无障碍服务已关闭或采集任务已开始");
            service.showRecorderFollow(table);publish("操作表已载入，切换到游戏后勾选开始跟打");
        }catch(Exception error){publish(error.getMessage());}finally{pending=false;}
    }
    private static String visibleCode(com.google.mlkit.vision.text.Text text){
        java.util.List<com.google.mlkit.vision.text.Text.Line> lines=new java.util.ArrayList<>();
        for(var block:text.getTextBlocks())lines.addAll(block.getLines());
        lines.sort(java.util.Comparator.comparingInt(line->line.getBoundingBox()==null?0:line.getBoundingBox().top));
        StringBuilder code=new StringBuilder();boolean found=false;
        for(var line:lines){String value=line.getText().replaceAll("\\s","").replace('：',':');
            int start=value.indexOf("YRAF1:");if(start>=0){found=true;value=value.substring(start);}
            if(found){if(!value.matches("[A-Za-z0-9_:-]+"))break;code.append(value);}
        }return found?code.toString():null;
    }
    private void recognize(Bitmap bitmap,String warning){
        var client=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        final String[] notice={warning};
        client.process(InputImage.fromBitmap(bitmap,0))
            .onSuccessTask(text->{
                // 历史导出只有底部可见码；必须完整通过 AES 校验，绝不修猜密文或授权。
                if(notice[0].isEmpty())try{String code=visibleCode(text);if(code!=null){showFollow(RecorderFollowTable.decode(activity,code));return com.google.android.gms.tasks.Tasks.forResult((java.util.List<RecorderRoundImage.Row>)null);}}
                catch(Exception error){notice[0]=error.getMessage()==null?"跟打码无法解码":error.getMessage();}
                return RecorderRoundImage.readWithRetry(bitmap,text,client);
            }).addOnSuccessListener(rows->{
            try{
                if(rows==null)return;
                RuYuanCaptureService service=RuYuanCaptureService.instance;
                if(service==null||service.running()){
                    for(var row:rows){row.operations.recycle();if(row.note!=null)row.note.recycle();}
                    throw new IllegalArgumentException("无障碍服务已关闭或采集任务已开始");
                }
                service.showRecorderRounds(rows);
                publish(notice[0].isEmpty()?"操作表已载入，切换到游戏即可手动抄表":notice[0]+"；已载入手动抄表");
            }catch(Exception e){publish(e.getMessage()==null?"行识别失败":e.getMessage());}
            finally{bitmap.recycle();pending=false;client.close();}
        }).addOnFailureListener(error->{bitmap.recycle();pending=false;client.close();publish(error instanceof IllegalArgumentException?error.getMessage():"文字识别失败，请换一张清晰的图片");});
    }
    private void publish(String message){activity.runOnUiThread(()->{
        try{JSONObject value=new JSONObject().put("message",message);
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-recorder-rounds',{detail:"+value+"}))",null);
        }catch(Exception ignored){}
    });}
}
