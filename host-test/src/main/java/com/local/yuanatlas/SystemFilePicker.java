package com.local.yuanatlas;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.MediaStore;

/** 文件入口共用系统文档窗口；目录、搜索和第三方来源由手机系统提供。 */
final class SystemFilePicker {
    private SystemFilePicker() { }

    static Intent openArchive() {
        // 部分文件管理器把 ZIP 标成其他 MIME；先允许搜索全部文件，再由 ZIP 解析器校验内容。
        return openDocument("*/*");
    }

    static void openArchive(Activity activity, int request) {
        activity.startActivityForResult(new Intent(activity, FlatArchivePicker.class), request);
    }

    static void openArchiveDocument(Activity activity, int request) {
        openWithFallback(activity, openArchive(), request);
    }

    static void openJsonDocument(Activity activity, int request) {
        // JSON 在部分文件应用中标为 text/plain，允许选择后按扩展名及内容校验。
        openWithFallback(activity, openDocument("*/*"), request);
    }

    private static void openWithFallback(Activity activity, Intent document, int request) {
        try { activity.startActivityForResult(document, request); }
        catch (ActivityNotFoundException | SecurityException e) {
            // 厂商禁用文档组件时允许选择其他文件应用，仍只接收本次用户授权的文件。
            Intent content = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(document.getType());
            activity.startActivityForResult(Intent.createChooser(content, "选择文件来源"), request);
        }
    }

    private static Intent openDocument(String mime) {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(mime);
    }

    static void openImage(Activity activity, int request) {
        // 图片与 ZIP 都默认使用应用内平铺视图，跨机型保持一致，并支持文件名搜索。
        activity.startActivityForResult(new Intent(activity,FlatArchivePicker.class).putExtra("images",true),request);
    }

    static void openImageDocument(Activity activity, int request) {
        // 用户主动选择其他来源时优先打开免全盘授权的系统全部照片视图。
        try {
            activity.startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), request);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) { }
        openWithFallback(activity, openDocument("image/*"), request);
    }

    static Intent save(String mime, String filename) {
        // 保存到用户选定的共享目录，文件名可在系统窗口修改；不写入应用私有目录。
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
            .putExtra(Intent.EXTRA_TITLE, filename);
    }
}
