package com.local.yuanatlas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 操作表选图保留原像素；头像选择器会缩小长图，不能用于多二维码识别。 */
public final class RecorderImagePicker {
    // MainActivity 按请求编号分发结果；2011 已用于图鉴/卡池 ZIP，不能复用。
    static final int REQUEST = 2012;
    static final int MAX_BYTES = 12 * 1024 * 1024;
    private final Activity activity;
    private final WebView webView;
    private boolean pending;
    RecorderImagePicker(Activity activity, WebView webView) { this.activity = activity; this.webView = webView; }

    @JavascriptInterface public void chooseImage() {
        activity.runOnUiThread(() -> {
            if (pending) return;
            try {
                String url = webView.getUrl(), start = "https://appassets.androidplatform.net/assets/www/index.html";
                if (url == null || !(url.equals(start) || url.startsWith(start + "#"))) throw new Exception();
                pending = true;
                SystemFilePicker.openImage(activity, REQUEST);
            } catch (Exception e) { finish(null, "无法打开图片选择器"); }
        });
    }
    boolean result(int request, int result, Intent data) {
        if (request != REQUEST) return false;
        if (!pending) return true;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) { finish(null, null); return true; }
        final Uri uri = data.getData();
        new Thread(() -> {
            if (!"content".equals(uri.getScheme())) { finish(null, "请从系统文件或相册选择图片"); return; }
            try (InputStream input = activity.getContentResolver().openInputStream(uri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (input == null) throw new Exception();
                byte[] buffer = new byte[8192]; int length;
                while ((length = input.read(buffer)) != -1) {
                    if (bytes.size() + length > MAX_BYTES) throw new Exception();
                    bytes.write(buffer, 0, length);
                }
                byte[] source = bytes.toByteArray();
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
                if (!"image/png".equals(bounds.outMimeType) && !"image/jpeg".equals(bounds.outMimeType) && !"image/webp".equals(bounds.outMimeType)) throw new Exception();
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > 50_000_000) throw new Exception();
                // 仅读取尺寸校验，再传递用户选择的原始字节，不解码缩小或重新压缩。
                finish("data:" + bounds.outMimeType + ";base64," + Base64.encodeToString(source, Base64.NO_WRAP), null);
            } catch (Exception | OutOfMemoryError e) { finish(null, "无法读取图片，请选择12MB以内的PNG、JPG或WebP图片"); }
            finally {
                if ((activity.getPackageName() + ".selection").equals(uri.getAuthority())) {
                    try { activity.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                }
            }
        }, "yuan-recorder-image-reader").start();
        return true;
    }
    private void finish(String data, String error) {
        activity.runOnUiThread(() -> {
            pending = false;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            try {
                JSONObject detail = new JSONObject().put("data", data == null ? "" : data).put("error", error == null ? "" : error);
                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-recorder-image',{detail:" + detail + "}))", null);
            } catch (Exception ignored) { }
        });
    }
}
