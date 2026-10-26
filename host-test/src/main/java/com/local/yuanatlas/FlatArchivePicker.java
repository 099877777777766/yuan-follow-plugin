package com.local.yuanatlas;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.AbsListView;
import android.widget.GridView;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 跨公共目录的图片网格和图鉴列表：扫描只读，选择后仅把选中文件复制到私有临时目录。 */
public final class FlatArchivePicker extends ScreenOrientationActivity {
    private static final int ACCESS = 2031, DOCUMENT = 2032;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails = Executors.newFixedThreadPool(2);
    private final LruCache<String,Bitmap> thumbnailCache=new LruCache<String,Bitmap>(8*1024*1024){protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount();}};
    private volatile boolean closed;
    private volatile int generation;
    private boolean copying, scanning, images;
    private TextView status;
    private EditText search;
    private Button grant, other;
    private AbsListView list;
    private final List<File> files = new ArrayList<>(), shown = new ArrayList<>();
    private final Rows rows = new Rows();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        images=getIntent().getBooleanExtra("images",false);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), 0, dp(16), 0);
        // 原生页面也避开状态栏、刘海与手势区域，列表随手机和平板宽度伸展。
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(dp(16)+bars.left,bars.top,dp(16)+bars.right,bars.bottom);
            } else view.setPadding(dp(16),insets.getSystemWindowInsetTop(),dp(16),insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = new TextView(this); title.setText(images?"选择图片":"选择图鉴压缩包"); title.setTextSize(21); title.setPadding(0,dp(20),0,dp(12)); root.addView(title);
        // 图片直接浏览全部结果，把搜索和文件名占用的空间留给图片；ZIP 保留搜索。
        if(!images){search = new EditText(this); search.setSingleLine(true); search.setHint("搜索文件名或原目录"); root.addView(search);}
        status = new TextView(this); status.setPadding(0,dp(images?4:12),0,dp(images?4:12)); root.addView(status);
        grant = new Button(this); grant.setText("授权扫描手机文件"); grant.setOnClickListener(v -> requestAccess()); root.addView(grant);
        other = new Button(this); other.setText("从其他来源选择文件"); other.setOnClickListener(v -> {
            if(copying)return;
            try { if(images)SystemFilePicker.openImageDocument(this,DOCUMENT);else SystemFilePicker.openArchiveDocument(this,DOCUMENT); }
            catch(Exception e){status.setText("无法打开其他文件来源，请先授权扫描手机文件");}
        }); root.addView(other);
        if(images){GridView grid=new GridView(this);grid.setNumColumns(GridView.AUTO_FIT);grid.setColumnWidth(dp(80));grid.setHorizontalSpacing(dp(3));grid.setVerticalSpacing(dp(3));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);list=grid;}else list = new ListView(this);
        list.setAdapter(rows); root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,view,position,id) -> { if(!copying)select(shown.get(position)); });
        if(search!=null)search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){filter();}public void afterTextChanged(Editable e){}});
        setContentView(root); root.requestApplyInsets(); reload();
        if(!hasAccess() && state==null) new AlertDialog.Builder(this).setTitle(images?"平铺手机中的图片":"平铺手机中的 ZIP 文件")
            .setMessage("允许访问公共文件夹后，可以直接列出其中的匹配文件，不必逐层打开目录。扫描不会移动或删除原文件。")
            .setPositiveButton("去授权",(dialog,which)->requestAccess()).setNegativeButton("暂不授权",null).show();
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private boolean hasAccess(){return Build.VERSION.SDK_INT>=30 ? Environment.isExternalStorageManager() : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;}
    private void requestAccess(){
        if(copying)return;
        if(hasAccess()){reload();return;}
        try {
            if(Build.VERSION.SDK_INT>=30){
                try{startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())),ACCESS);}
                catch(Exception e){startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),ACCESS);}
            }else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},ACCESS);
        }catch(Exception e){status.setText("此机型无法打开文件授权，请使用其他来源选择文件");}
    }

    private List<File> roots(){
        List<File> roots=new ArrayList<>();roots.add(Environment.getExternalStorageDirectory());
        // 包含可访问的 SD 卡；旧系统从系统返回的应用外部目录推导对应存储卷。
        if(Build.VERSION.SDK_INT>=30){
            StorageManager manager=(StorageManager)getSystemService(STORAGE_SERVICE);
            if(manager!=null)for(StorageVolume volume:manager.getStorageVolumes())if(volume.getDirectory()!=null)roots.add(volume.getDirectory());
        }else for(File directory:getExternalFilesDirs(null))if(directory!=null){String path=directory.getAbsolutePath();int at=path.indexOf("/Android/data/");if(at>0)roots.add(new File(path.substring(0,at)));}
        return roots;
    }

    private void reload(){
        if(copying||closed)return;
        final int request=++generation;files.clear();shown.clear();rows.notifyDataSetChanged();
        boolean access=hasAccess();grant.setVisibility(access?View.GONE:View.VISIBLE);
        if(!access){scanning=false;status.setText("尚未授权，不能扫描公共文件夹。也可以从其他来源单独选取文件。");return;}
        scanning=true;
        status.setText(images?"正在查找公共文件夹中的图片…":"正在查找公共文件夹中的 ZIP 文件…");
        worker.execute(()->{
            try{
                List<File> found=FlatArchiveIndex.scan(roots(),()->closed||generation!=request,images);
                runOnUiThread(()->{if(closed||generation!=request)return;scanning=false;files.addAll(found);filter();});
            }catch(Exception e){runOnUiThread(()->{if(!closed&&generation==request){scanning=false;status.setText("扫描失败，请检查存储权限或从其他来源选择文件");}});}
        });
    }

    private void filter(){
        if(copying)return;
        String query=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);shown.clear();
        for(File file:files)if(file.getName().toLowerCase(Locale.ROOT).contains(query)||file.getParent().toLowerCase(Locale.ROOT).contains(query))shown.add(file);
        rows.notifyDataSetChanged();
        if(hasAccess())status.setText(scanning?"正在查找公共文件夹中的匹配文件…":files.isEmpty()?"没有找到可访问的匹配文件":shown.isEmpty()?"没有匹配的文件":"共 "+files.size()+" 个"+(images?"图片":"ZIP 文件")+"，显示 "+shown.size()+" 个");
    }

    private void select(File source){
        copying=true;list.setEnabled(false);if(search!=null)search.setEnabled(false);other.setEnabled(false);grant.setEnabled(false);status.setText("正在读取："+source.getName());
        worker.execute(()->{
            File staged=null;
            try{
                File folder=new File(getCacheDir(),"flat-picker");if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException();
                String suffix=source.getName().substring(source.getName().lastIndexOf('.')).toLowerCase(Locale.ROOT);
                staged=new File(folder,UUID.randomUUID()+suffix);
                // 只向原有导入器提供私有临时副本的 URI，不把全盘路径开放给网页。
                try(FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(staged)){
                    byte[] buffer=new byte[32768];long total=0;int n;
                    while((n=in.read(buffer))!=-1){if(closed)throw new java.io.IOException();total+=n;if(total>(images?12L:300L)*1024*1024)throw new java.io.IOException("too large");out.write(buffer,0,n);}
                }
                File selected=staged;Uri uri=FileProvider.getUriForFile(this,getPackageName()+".selection",selected);
                runOnUiThread(()->{if(closed){selected.delete();return;}setResult(RESULT_OK,new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));finish();});
            }catch(Exception e){
                if(staged!=null)staged.delete();
                runOnUiThread(()->{if(closed)return;copying=false;list.setEnabled(true);if(search!=null)search.setEnabled(true);other.setEnabled(true);grant.setEnabled(true);status.setText(images?"图片无法读取，请选择 12MB 以内的 PNG、JPG 或 WebP":"文件无法读取，请选择 300MB 以内且仍然存在的 ZIP 文件");});
            }
        });
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==ACCESS){reload();return;}
        if(request==DOCUMENT&&result==RESULT_OK&&data!=null){setResult(RESULT_OK,data);finish();}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==ACCESS)reload();}
    @Override protected void onDestroy(){closed=true;generation++;worker.shutdownNow();thumbnails.shutdownNow();thumbnailCache.evictAll();super.onDestroy();}

    private View imageCell(File file,View recycled){
        ImageView image;
        if(recycled instanceof ImageView)image=(ImageView)recycled;
        else{
            // 单元格随列宽保持正方形，FIT_CENTER 完整显示长图/横图，不裁切或拉伸。
            image=new ImageView(this){@Override protected void onMeasure(int widthSpec,int heightSpec){int side=getDefaultSize(dp(80),widthSpec);setMeasuredDimension(side,side);}};
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(0xfff1f1f1);
        }
        image.setContentDescription(file.getName()+"，"+file.getParent());
        String key=file.getPath();image.setTag(key);image.setImageBitmap(null);Bitmap cached=thumbnailCache.get(key);
        if(cached!=null)image.setImageBitmap(cached);
        else thumbnails.execute(()->{
            if(closed)return;
            try{
                // 仅读取可见网格的缩略图并限制解码尺寸，不能把所有原图一次性加载进内存。
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(key,options);options.inSampleSize=1;
                while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>256)options.inSampleSize*=2;
                options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeFile(key,options);
                if(bitmap!=null)runOnUiThread(()->{if(closed)return;thumbnailCache.put(key,bitmap);if(key.equals(image.getTag()))image.setImageBitmap(bitmap);});
            }catch(Exception | OutOfMemoryError ignored){/* 损坏图片保持占位，选择后由原导入器明确反馈。 */}
        });
        return image;
    }

    private final class Rows extends BaseAdapter {
        public int getCount(){return shown.size();}public Object getItem(int position){return shown.get(position);}public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){
            if(images)return imageCell(shown.get(position),recycled);
            LinearLayout row;
            if(recycled instanceof LinearLayout)row=(LinearLayout)recycled;
            else{row=new LinearLayout(FlatArchivePicker.this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(0,dp(14),0,dp(14));TextView name=new TextView(FlatArchivePicker.this);name.setTextSize(17);row.addView(name);TextView detail=new TextView(FlatArchivePicker.this);detail.setTextSize(13);detail.setPadding(0,dp(6),0,0);row.addView(detail);}
            File file=shown.get(position);((TextView)row.getChildAt(0)).setText(file.getName());
            ((TextView)row.getChildAt(1)).setText(String.format(Locale.ROOT,"%.2f MB · %s",file.length()/1048576.0,file.getParent()));return row;
        }
    }
}
