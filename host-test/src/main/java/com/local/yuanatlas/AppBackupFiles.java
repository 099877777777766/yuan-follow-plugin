package com.local.yuanatlas;

import android.app.Activity;
import android.webkit.WebView;
import android.webkit.JavascriptInterface;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** 全量恢复由 SQLite 事务与 Web 日志两阶段提交协调，崩溃后可回退。 */
public final class AppBackupFiles {
 private final Activity activity; private final WebView web; private final AtomicFile journal; private final AppDatabase database;
 AppBackupFiles(Activity activity, WebView web){this.activity=activity;this.web=web;database=AppDatabase.open(activity);journal=new AtomicFile(new File(activity.getFilesDir(),"app-migration-rollback.json"));}
 private AtomicFile target(String name) throws Exception {
  switch(name){
   case "roles":return new AtomicFile(new File(activity.getFilesDir(),"roles/state.json"));
   case "catalog":return new AtomicFile(new File(activity.getFilesDir(),"catalog/state.json"));
   case "identity":return new AtomicFile(new File(activity.getNoBackupFilesDir(),"recorder-device-id"));
   case "keys":return new AtomicFile(new File(activity.getNoBackupFilesDir(),"recorder-device-id-keys.json"));
   default:throw new IOException("Unknown migration target");
  }
 }
 private boolean exists(AtomicFile f){return f.getBaseFile().exists()||new File(f.getBaseFile()+".bak").exists();}
 private void write(AtomicFile file,byte[] bytes)throws Exception{
  File parent=file.getBaseFile().getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException();
  FileOutputStream out=null;try{out=file.startWrite();out.write(bytes);file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
 }
 private void origin()throws Exception{
  // JavaScript 桥回调不在 UI 线程，读取 WebView 地址必须切回主线程。
  final String[] address={null};
  if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())address[0]=web.getUrl();
  else {java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);activity.runOnUiThread(()->{try{address[0]=web.getUrl();}finally{latch.countDown();}});if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException();}
  String url=address[0],start="https://appassets.androidplatform.net/assets/www/index.html";if(url==null||!(url.equals(start)||url.startsWith(start+"#")))throw new IOException();
 }
 private String ok(){return "{\"ok\":true}";}
 private String error(){return "{\"ok\":false,\"error\":\"整机资料恢复未完成，请保留迁移包并重试\"}";}
 private void rollback()throws Exception{
  if(!exists(journal))return;JSONObject old=new JSONObject(new String(journal.readFully(),StandardCharsets.UTF_8));
  if(old.optBoolean("database")){
   // 跨进程重启也从日志恢复数据库快照，不重新生成旧角色/图鉴 JSON 文件。
   JSONObject roles=new JSONObject(new String(Base64.decode(old.getString("roles"),Base64.NO_WRAP),StandardCharsets.UTF_8));
   JSONObject catalog=old.isNull("catalog")?null:new JSONObject(new String(Base64.decode(old.getString("catalog"),Base64.NO_WRAP),StandardCharsets.UTF_8));
   database.restore(roles,catalog);
   for(String key:new String[]{"identity","keys"}){AtomicFile file=target(key);if(old.isNull(key))file.delete();else write(file,Base64.decode(old.getString(key),Base64.NO_WRAP));}
  }else{
   // 升级前已经中断的旧版恢复先还原原文件，随后由启动迁移接管。
   for(String key:new String[]{"roles","catalog","identity","keys"}){AtomicFile file=target(key);if(old.isNull(key))file.delete();else write(file,Base64.decode(old.getString(key),Base64.NO_WRAP));}
   database.prepareLegacyRecovery();
  }
  journal.delete();
 }
 private void verifyImages(Object value)throws Exception{
  if(value instanceof JSONObject){JSONObject obj=(JSONObject)value;for(Iterator<String> it=obj.keys();it.hasNext();)verifyImages(obj.get(it.next()));}
  else if(value instanceof JSONArray){JSONArray arr=(JSONArray)value;for(int i=0;i<arr.length();i++)verifyImages(arr.get(i));}
  else if(value instanceof String&&((String)value).startsWith("catalog-image:")){
   String ref=(String)value;if(!ref.matches("catalog-image:[a-f0-9]{64}\\.(jpg|png|webp)")||!new File(activity.getFilesDir(),"catalog/images/"+ref.substring(14)).isFile())throw new IOException();
  }
 }
 @JavascriptInterface public synchronized String stage(String text){
  try{
   origin();if(exists(journal)||text==null||text.getBytes(StandardCharsets.UTF_8).length>64*1024*1024)throw new IOException();
   JSONObject value=new JSONObject(text);if(!"yuan-app-backup".equals(value.optString("format"))||value.optInt("version")!=1)throw new IOException();
   JSONObject roles=value.getJSONObject("roles"),catalog=value.getJSONObject("library"),keys=value.getJSONObject("keys");
   if(roles.getInt("schemaVersion")!=1||roles.getJSONArray("roles").length()>100||catalog.getInt("schemaVersion")!=1)throw new IOException();
   String own=keys.getString("own"),active=keys.getString("active"),pattern="[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
   JSONArray list=keys.getJSONArray("keys");Set<String> known=new HashSet<>();if(list.length()>20)throw new IOException();for(int i=0;i<list.length();i++){String k=list.getString(i);if(!k.matches(pattern)||!known.add(k))throw new IOException();}if(!known.contains(own)||!known.contains(active))throw new IOException();
   verifyImages(catalog);verifyImages(value.getJSONArray("images"));
   JSONObject old=new JSONObject().put("database",true);
   String previousRoles=database.readRoles(),previousCatalog=database.readCatalog();
   old.put("roles",Base64.encodeToString(previousRoles.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));
   old.put("catalog",previousCatalog==null?JSONObject.NULL:Base64.encodeToString(previousCatalog.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));
   for(String name:new String[]{"identity","keys"}){AtomicFile file=target(name);old.put(name,exists(file)?Base64.encodeToString(file.readFully(),Base64.NO_WRAP):JSONObject.NULL);}
   // 先落回退日志；角色与图鉴在同一 SQLite 事务提交，设备密钥仍由两阶段恢复保护。
   write(journal,old.toString().getBytes(StandardCharsets.UTF_8));
   try{database.restore(roles,catalog);for(String name:new String[]{"identity","keys"}){String data="identity".equals(name)?own:keys.toString();write(target(name),data.getBytes(StandardCharsets.UTF_8));}}catch(Exception e){rollback();throw e;}
   return ok();
  }catch(Exception e){return error();}
 }
 @JavascriptInterface public synchronized String finish(boolean commit){try{origin();if(commit)journal.delete();else rollback();return ok();}catch(Exception e){return error();}}
}
