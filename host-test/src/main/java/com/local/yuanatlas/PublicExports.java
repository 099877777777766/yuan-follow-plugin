package com.local.yuanatlas;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** 用户主动导出到公共目录；登记系统文件库，供图库、文件管理器和支持的桌面搜索发现。 */
final class PublicExports {
    static final int SAVE_DOCUMENT = 2021, STORAGE_PERMISSION = 2022;
    static final String FOLDER = "鸢家百宝袋";
    interface Writer { void write(OutputStream output) throws Exception; }
    interface Completion { void finish(String status, String location); }
    private final Activity activity;
    // 所有状态切换在主线程进行，ZIP 和图片共用锁，避免权限/选择器回调串到另一份导出。
    private Job pending;
    private static final class Job {
        final String mime, name; final Writer writer; final Completion completion;
        Job(String mime, String label, Writer writer, Completion completion) {
            this.mime = mime; this.writer = writer; this.completion = completion;
            // 卡池小包使用内容名称，其他导出继续使用原来的唯一名称。
            name = "application/zip".equals(mime) && label.endsWith(".zip") ? label : FOLDER + "-" + label + "-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 8) + ("image/png".equals(mime) ? ".png" : "image/jpeg".equals(mime) ? ".jpg" : "application/json".equals(mime) ? ".json" : ".zip");
        }
    }
    PublicExports(Activity activity) { this.activity = activity; }

    void save(String mime, String label, Writer writer, Completion completion) {
        activity.runOnUiThread(() -> {
            if (pending != null || activity.isFinishing() || activity.isDestroyed()
                || !("image/png".equals(mime) || "image/jpeg".equals(mime) || "application/zip".equals(mime) || "application/json".equals(mime))) {
                completion.finish("error", ""); return;
            }
            Job job = new Job(mime, label, writer, completion); pending = job;
            if (Build.VERSION.SDK_INT < 29 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                // 只有 Android 7–9 需要旧存储权限，拒绝后仍可通过系统窗口导出。
                try { activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION); }
                catch (Exception e) { chooseLocation(job); }
            } else writePublic(job);
        });
    }

    // JPG隐写必须按原字节进入图库，不能重绘或转成PNG。
    static boolean isImage(String mime) { return "image/png".equals(mime) || "image/jpeg".equals(mime); }
    static String directory(String mime) {
        return (isImage(mime) ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS) + "/" + FOLDER;
    }

    private void writePublic(Job job) {
        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    writeMedia(activity.getContentResolver(), job.mime, job.name, job.writer);
                } else {
                    File file = writeLegacy(job);
                    // 扫描会登记图片及普通文件；具体桌面是否索引仍由厂商系统决定。
                    try { MediaScannerConnection.scanFile(activity, new String[]{file.getAbsolutePath()}, new String[]{job.mime}, null); }
                    catch (RuntimeException ignored) { /* 文件已完整保存；扫描服务异常不应触发第二份导出。 */ }
                }
                finish(job, "saved", directory(job.mime) + "/" + job.name);
            } catch (Exception e) {
                activity.runOnUiThread(() -> chooseLocation(job));
            }
        }, "yuan-public-export").start();
    }

    @android.annotation.TargetApi(29)
    static Uri writeMedia(ContentResolver resolver, String mime, String name, Writer writer) throws Exception {
        Uri collection = isImage(mime) ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory(mime));
        // 完整写入并关闭流以后才发布，搜索和图库不会读到半份 ZIP 或 PNG。
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("无法创建公共文件");
        try {
            try (OutputStream stream = resolver.openOutputStream(uri, "wt")) {
                if (stream == null) throw new IOException("无法打开公共文件");
                writer.write(stream);
            }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) throw new IOException("无法发布公共文件");
            return uri;
        } catch (Exception e) {
            // 只清理此次新建的媒体记录，绝不覆盖或删除用户已有导出。
            try { resolver.delete(uri, null, null); } catch (Exception ignored) { }
            throw e;
        }
    }

    private File writeLegacy(Job job) throws Exception {
        File root = Environment.getExternalStoragePublicDirectory(isImage(job.mime) ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS);
        File folder = new File(root, FOLDER);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建导出目录");
        File file = new File(folder, job.name);
        if (!file.createNewFile()) throw new IOException("文件名已存在");
        try (OutputStream stream = new FileOutputStream(file)) { job.writer.write(stream); }
        catch (Exception e) { file.delete(); throw e; }
        return file;
    }

    private void chooseLocation(Job job) {
        if (pending != job) return;
        if (activity.isFinishing() || activity.isDestroyed()) { finish(job, "error", ""); return; }
        try {
            Toast.makeText(activity, "请手动选择保存位置，建议选择下载或图片目录", Toast.LENGTH_LONG).show();
            activity.startActivityForResult(SystemFilePicker.save(job.mime, job.name), SAVE_DOCUMENT);
        } catch (Exception e) { finish(job, "error", ""); }
    }

    boolean permissions(int request, int[] results) {
        if (request != STORAGE_PERMISSION) return false;
        Job job = pending;
        if (job != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) writePublic(job);
            else chooseLocation(job);
        }
        return true;
    }

    boolean result(int request, int result, Intent data) {
        if (request != SAVE_DOCUMENT) return false;
        Job job = pending;
        if (job == null) return true;
        if (result != Activity.RESULT_OK) { finish(job, "cancelled", ""); return true; }
        Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) { finish(job, "error", ""); return true; }
        new Thread(() -> {
            try {
                try (OutputStream stream = activity.getContentResolver().openOutputStream(uri, "wt")) {
                    if (stream == null) throw new IOException();
                    job.writer.write(stream);
                }
                // 系统“另存为”允许改名；显示提供方实际返回的名字，而非预填文件名。
                finish(job, "saved", "系统所选位置/" + displayName(activity.getContentResolver(), uri));
            } catch (Exception e) {
                // CREATE_DOCUMENT 新建的文件写入失败时尝试移除，避免留下损坏的空文件。
                try { DocumentsContract.deleteDocument(activity.getContentResolver(), uri); } catch (Exception ignored) { }
                finish(job, "error", "");
            }
        }, "yuan-document-export").start();
        return true;
    }

    static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "已保存文件";
    }

    private void finish(Job job, String status, String location) {
        activity.runOnUiThread(() -> {
            if (pending != job) return;
            pending = null;
            job.completion.finish(status, location);
        });
    }
}
