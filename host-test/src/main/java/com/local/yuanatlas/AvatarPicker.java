package com.local.yuanatlas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 只读取用户在系统选图器中授权的图片，不向网页开放任意文件路径。 */
public final class AvatarPicker {
    static final int REQUEST = 2002;
    private final Activity activity;
    private final WebView webView;
    private boolean pending;
    AvatarPicker(Activity activity, WebView webView) { this.activity = activity; this.webView = webView; }
    @JavascriptInterface public void choose() {
        activity.runOnUiThread(() -> {
            if (pending) return;
            try {
                String url = webView.getUrl();
                if (url == null || !url.startsWith("https://appassets.androidplatform.net/assets/www/index.html")) throw new Exception();
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
        // 解码及缩小在后台完成，预览最大2048像素；最终256像素圆形裁剪由页面统一处理。
        new Thread(() -> {
            Bitmap bitmap = null;
            // 第三方来源必须返回用户授权的内容 URI，不接受任意本地文件或网络地址。
            if (!"content".equals(uri.getScheme())) { finish(null, "此图片来源无法读取，请从系统文件或下载目录选择"); return; }
            try (InputStream input = activity.getContentResolver().openInputStream(uri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (input == null) throw new Exception();
                byte[] buffer = new byte[8192]; int length;
                while ((length = input.read(buffer)) != -1) {
                    if (bytes.size() + length > 12 * 1024 * 1024) throw new Exception();
                    bytes.write(buffer, 0, length);
                }
                byte[] source = bytes.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(source, 0, source.length, options);
                // 选择器展示所有图片来源，实际内容仍只接受原有三种格式，避免误收 GIF、SVG 等。
                if (!"image/png".equals(options.outMimeType) && !"image/jpeg".equals(options.outMimeType)
                    && !"image/webp".equals(options.outMimeType)) throw new Exception();
                if (options.outWidth <= 0 || options.outHeight <= 0 || (long) options.outWidth * options.outHeight > 50000000) throw new Exception();
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, options);
                if (bitmap == null) throw new Exception();
                Matrix transform = new Matrix();
                int orientation = 1;
                // 旧Android对部分PNG/WebP不支持EXIF；没有方向资料仍可正常裁剪。
                try { orientation = new ExifInterface(new ByteArrayInputStream(source)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1); } catch (Exception ignored) { }
                switch (orientation) {
                    case 2: transform.setScale(-1, 1); break;
                    case 3: transform.setRotate(180); break;
                    case 4: transform.setScale(1, -1); break;
                    case 5: transform.setRotate(90); transform.postScale(-1, 1); break;
                    case 6: transform.setRotate(90); break;
                    case 7: transform.setRotate(-90); transform.postScale(-1, 1); break;
                    case 8: transform.setRotate(-90); break;
                }
                Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
                if (oriented != bitmap) { bitmap.recycle(); bitmap = oriented; }
                ByteArrayOutputStream png = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
                finish("data:image/png;base64," + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP), null);
            } catch (Exception | OutOfMemoryError e) { finish(null, "无法读取图片，请选择12MB以内的PNG、JPG或WebP图片"); }
            finally {
                if (bitmap != null) bitmap.recycle();
                if ((activity.getPackageName()+".selection").equals(uri.getAuthority())) {
                    try { activity.getContentResolver().delete(uri,null,null); } catch(Exception ignored) { }
                }
            }
        }, "yuan-avatar-reader").start();
        return true;
    }
    private void finish(String data, String error) {
        activity.runOnUiThread(() -> {
            pending = false;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            try {
                JSONObject detail = new JSONObject().put("data", data == null ? "" : data).put("error", error == null ? "" : error);
                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-avatar-picked',{detail:" + detail.toString() + "}))", null);
            } catch (Exception ignored) { }
        });
    }
}
