package com.local.yuanatlas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** 图鉴独立于角色：SQLite 存储配置、固定目录保存不可变图片，系统选择器流式传输 ZIP。 */
public final class CatalogFiles {
    private final Activity activity; private final WebView web;
    private final File folder, images; private final AppDatabase database;
    private boolean busy; private final PublicExports exports;
    static final int IMPORT=2011;
    private static final int IMAGE_LIMIT=8*1024*1024;
    public CatalogFiles(Activity activity, WebView web, PublicExports exports) {
        this.exports=exports;
        this.activity=activity; this.web=web; folder=new File(activity.getFilesDir(),"catalog"); images=new File(folder,"images"); database=AppDatabase.open(activity);
    }
    private String response(boolean ok,Object value,String error) { try{return new JSONObject().put("ok",ok).put("value",value==null?JSONObject.NULL:value).put("error",error).toString();}catch(Exception e){return "{\"ok\":false}";} }
    private String existing() throws Exception { return database.readCatalog(); }
    @JavascriptInterface public synchronized String migrationSource(){try{return response(true,database.catalogMigrationSource(),"");}catch(Exception e){return response(false,null,"图鉴迁移源无法读取，原数据已保留");}}
    @JavascriptInterface public synchronized String migrate(String text,String beforeShared){try{database.migrateCatalog(text,beforeShared);return response(true,null,"");}catch(Exception e){return response(false,null,"图鉴迁移失败，原数据已保留");}}
    @JavascriptInterface public synchronized String read(){try{return response(true,existing(),"");}catch(Exception e){return response(false,null,"图鉴配置无法读取，原文件已保留");}}
    @JavascriptInterface public synchronized String write(int revision,String text){
        try{
            if(text==null||text.length()>16*1024*1024)throw new IOException();byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>16*1024*1024)throw new IOException();
            String old=existing();int current=old==null?0:new JSONObject(old).getInt("revision");if(current!=revision)return response(false,null,"图鉴已变化，请重新打开页面");
            JSONObject next=new JSONObject(text);if(next.getInt("schemaVersion")!=1||next.getInt("revision")!=revision+1)throw new IOException();
            // 抽卡图与皮肤复用不可变文件目录，写配置前确认每张图片真实存在。
            // Fragment uploads share immutable image storage and are checked before committing JSON.
            JSONObject fragmentImages=next.optJSONObject("drawFragmentImages");
            if(fragmentImages!=null)for(Iterator<String> it=fragmentImages.keys();it.hasNext();){String id=it.next();if(!fragmentImages.isNull(id))imageFile(fragmentImages.getJSONObject(id).getString("src"));}
            JSONObject drawImages=next.optJSONObject("drawImages");
            if(drawImages!=null)for(Iterator<String> it=drawImages.keys();it.hasNext();){String id=it.next();if(!drawImages.isNull(id))imageFile(drawImages.getString(id));}
            JSONObject games=next.getJSONObject("games");
            for(String game:new String[]{"codeYuan","ruYuan"}){
                JSONObject data=games.getJSONObject(game);data.getJSONObject("agents");data.getJSONArray("priority");JSONObject portraits=data.getJSONObject("portraits");
                for(Iterator<String> a=portraits.keys();a.hasNext();){JSONObject skins=portraits.getJSONObject(a.next());for(Iterator<String> s=skins.keys();s.hasNext();){JSONObject pair=skins.getJSONObject(s.next());imageFile(pair.getString("atlas"));imageFile(pair.getString("team"));}}
            }
            // 卡池与图鉴共用原子配置文件，额外检查引用的自定义横幅已落盘。
            if(next.has("poolCatalog")){
                JSONObject pools=next.getJSONObject("poolCatalog");
                for(String game:new String[]{"codeYuan","ruYuan"}){
                    org.json.JSONArray list=pools.getJSONArray(game);
                    for(int i=0;i<list.length();i++){JSONObject pool=list.getJSONObject(i);if(pool.has("banner"))imageFile(pool.getString("banner"));}
                }
            }
            // 密探按主键存入数据库；版本核验和整批更新在一个事务内完成。
            database.writeCatalog(revision,text);return response(true,null,"");
        }catch(Exception e){return response(false,null,"图鉴未保存，原数据已保留");}
    }
    private File imageFile(String ref) throws Exception {if(!ref.matches("catalog-image:[a-f0-9]{64}\\.(jpg|png|webp)"))throw new IOException();File f=new File(images,ref.substring(14));if(!f.isFile())throw new IOException();return f;}
    private synchronized String putBytes(byte[] bytes) throws Exception {
        if(bytes.length>IMAGE_LIMIT)throw new IOException();BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
        if(opt.outWidth<1||opt.outHeight<1||(long)opt.outWidth*opt.outHeight>50000000)throw new IOException();String ext="image/png".equals(opt.outMimeType)?"png":"image/jpeg".equals(opt.outMimeType)?"jpg":"image/webp".equals(opt.outMimeType)?"webp":null;if(ext==null)throw new IOException();
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",b&255));
        if(!images.isDirectory()&&!images.mkdirs())throw new IOException();File file=new File(images,hash+"."+ext);
        if(!file.exists()){AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(Exception e){if(out!=null)atomic.failWrite(out);throw e;}}
        return "catalog-image:"+file.getName();
    }
    @JavascriptInterface public String putImage(String data){try{if(data==null||data.length()>12*1024*1024||!data.matches("(?s)^data:image/(jpeg|png|webp);base64,.*"))throw new IOException();return response(true,putBytes(Base64.decode(data.substring(data.indexOf(',')+1),Base64.DEFAULT)),"");}catch(Exception e){return response(false,null,"图片格式无效或未保存");}}
    public WebResourceResponse imageResponse(String name){try{File f=imageFile("catalog-image:"+name);return new WebResourceResponse(name.endsWith(".png")?"image/png":name.endsWith(".webp")?"image/webp":"image/jpeg",null,new FileInputStream(f));}catch(Exception e){return null;}}
    private void event(JSONObject data){activity.runOnUiThread(()->{if(!activity.isFinishing()&&!activity.isDestroyed())web.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-catalog-transfer',{detail:"+data.toString()+"}))",null);});}
    private void errorEvent(String message){try{event(new JSONObject().put("error",message));}catch(Exception ignored){}}
    private void fail(String message){synchronized(this){busy=false;}errorEvent(message);}
    private boolean begin(){synchronized(this){if(busy)return false;busy=true;return true;}}
    @JavascriptInterface public void importArchive(){if(!begin()){errorEvent("已有图鉴传输正在进行");return;}activity.runOnUiThread(()->{try{SystemFilePicker.openArchive(activity,IMPORT);}catch(Exception e){fail("无法打开文件选择器");}});}
    private InputStream source(String ref)throws Exception{
        if(ref.startsWith("catalog-image:"))return new FileInputStream(imageFile(ref));
        String name=ref.substring(ref.lastIndexOf('/')+1);
        if(!name.matches("[a-zA-Z0-9_-]+\\.(jpg|png|webp)")||!(ref.startsWith("./assets/")||ref.startsWith("/assets/")||ref.startsWith("assets/")||ref.startsWith("https://appassets.androidplatform.net/assets/www/assets/")))throw new IOException();
        return activity.getAssets().open("www/assets/"+name);
    }
    private long copy(InputStream in,OutputStream out,long limit)throws Exception{byte[] buffer=new byte[32768];long count=0;int n;while((n=in.read(buffer))!=-1){count+=n;if(count>limit)throw new IOException();out.write(buffer,0,n);}return count;}
    @JavascriptInterface public void exportArchive(String manifest,String sources){
        if(!begin()){errorEvent("已有图鉴传输正在进行");return;}
        new Thread(()->{File zip=new File(activity.getCacheDir(),"catalog-export.zip");try{
            if(manifest==null||manifest.getBytes(StandardCharsets.UTF_8).length>64*1024*1024)throw new IOException();JSONObject metadata=new JSONObject(manifest);String format=metadata.getString("format");if(!"yuan-atlas".equals(format)&&!"yuan-pools".equals(format)&&!"yuan-app-backup".equals(format))throw new IOException();
            JSONObject refs=new JSONObject(sources);if(refs.length()>4000)throw new IOException();
            try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zip))){out.putNextEntry(new ZipEntry("manifest.json"));out.write(manifest.getBytes(StandardCharsets.UTF_8));out.closeEntry();for(Iterator<String> it=refs.keys();it.hasNext();){String name=it.next();if(!name.matches("images/[a-zA-Z0-9_-]+\\.(jpg|png|webp)"))throw new IOException();out.putNextEntry(new ZipEntry(name));try(InputStream in=source(refs.getString(name))){copy(in,out,IMAGE_LIMIT);}out.closeEntry();}}
            // 完整 ZIP 流式写入公共下载目录，不将整个压缩包传过 JS 桥或放入内存。
            String exportName=metadata.optString("exportFileName", "");
            // 新网页传入已预览的名称；旧页面没有该字段时保留原来的命名规则。
            if(!exportName.endsWith(".zip")||exportName.getBytes(StandardCharsets.UTF_8).length>240||exportName.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*"))exportName="yuan-pools".equals(format)?"卡池模板":"draw-images".equals(metadata.optString("kind"))?"抽卡图片":"图鉴";
            exports.save("application/zip",exportName,out->{try(InputStream in=new FileInputStream(zip)){copy(in,out,2L*1024*1024*1024);}},(status,location)->{
                zip.delete();synchronized(this){busy=false;}
                try{if("saved".equals(status))event(new JSONObject().put("saved",true).put("location",location));
                    else if("cancelled".equals(status))event(new JSONObject().put("cancelled",true));
                    else errorEvent("图鉴导出失败，请检查存储空间后重试");}catch(Exception ignored){}
            });
        }catch(Exception e){zip.delete();fail("图鉴导出失败，请检查图片或存储空间");}},"catalog-export").start();
    }
    public boolean result(int request,int result,Intent data){
        if(request!=IMPORT)return false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null){synchronized(this){busy=false;}try{event(new JSONObject().put("cancelled",true));}catch(Exception ignored){}return true;}
        if(!"content".equals(data.getData().getScheme())){fail("此文件来源无法读取，请从系统文件或下载目录选择");return true;}
        new Thread(()->{try{
            File temp=new File(activity.getCacheDir(),"catalog-import.zip");
            try{
                try(InputStream in=activity.getContentResolver().openInputStream(data.getData());OutputStream out=new FileOutputStream(temp)){if(in==null)throw new IOException();copy(in,out,2L*1024*1024*1024);}
                String manifest=null;JSONObject refs=new JSONObject();Set<String> names=new HashSet<>();long total=0;
                try(ZipInputStream in=new ZipInputStream(new FileInputStream(temp))){ZipEntry entry;while((entry=in.getNextEntry())!=null){String name=entry.getName();if(names.size()>4000||!names.add(name)||!(name.equals("manifest.json")||name.matches("images/[a-zA-Z0-9_-]+\\.(jpg|png|webp)")))throw new IOException();ByteArrayOutputStream bytes=new ByteArrayOutputStream();total+=copy(in,bytes,name.equals("manifest.json")?64L*1024*1024:IMAGE_LIMIT);if(total>2L*1024*1024*1024)throw new IOException();in.closeEntry();if(name.equals("manifest.json"))manifest=new String(bytes.toByteArray(),StandardCharsets.UTF_8);else refs.put(name,putBytes(bytes.toByteArray()));}}
                if(manifest==null)throw new IOException();new JSONObject(manifest);synchronized(this){busy=false;}event(new JSONObject().put("manifest",manifest).put("images",refs));
            }finally{
                temp.delete();
                // 平铺选择器只交付自己的私有副本；读取完清理副本，其他来源的原文件保持不变。
                if((activity.getPackageName()+".selection").equals(data.getData().getAuthority())){
                    try{activity.getContentResolver().delete(data.getData(),null,null);}catch(Exception ignored){}
                }
            }
        }catch(Exception e){fail("压缩包无效、图片损坏或存储空间不足，原图鉴未改变");}},"catalog-transfer").start();return true;
    }
}
