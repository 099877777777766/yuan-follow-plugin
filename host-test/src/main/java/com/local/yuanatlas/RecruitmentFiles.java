package com.local.yuanatlas;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/** 招募 JSON 只读取用户本次选择的文件，保存复用公共导出，不暴露任意路径接口。 */
public final class RecruitmentFiles {
    private static final int IMPORT_JSON = 2031;
    private static final int MAX_BYTES = 6000000, MAX_CHARS = 2000000;
    private static final String START = "https://appassets.androidplatform.net/assets/www/index.html";
    private final Activity activity;
    private final WebView web;
    private final PublicExports exports;
    private boolean busy;

    RecruitmentFiles(Activity activity, WebView web, PublicExports exports) {
        this.activity = activity; this.web = web; this.exports = exports;
    }
    private boolean begin() {
        String url = web.getUrl();
        if (busy || url == null || !(url.equals(START) || url.startsWith(START + "#"))) return false;
        busy = true; return true;
    }
    private void resultEvent(JSONObject detail) {
        activity.runOnUiThread(() -> {
            busy = false;
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                web.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-recruitment-file',{detail:" + detail + "}))", null);
            }
        });
    }
    private void fail(String message) {
        try { resultEvent(new JSONObject().put("error", message)); } catch (Exception ignored) { }
    }
    @JavascriptInterface public void importJson() {
        activity.runOnUiThread(() -> {
            if (!begin()) return;
            try { SystemFilePicker.openJsonDocument(activity, IMPORT_JSON); }
            catch (Exception e) { fail("无法打开 JSON 文件选择器"); }
        });
    }
    @JavascriptInterface public void exportJson(String text) {
        activity.runOnUiThread(() -> {
            if (!begin()) return;
            new Thread(() -> {
                try {
                    if (text == null || text.length() > MAX_CHARS) throw new IllegalArgumentException();
                    JSONObject json = new JSONObject(text);
                    // 两种记录复用受控 JSON 保存入口，仍严格限制格式与版本。
                    boolean stones = "yuan-star-stones".equals(json.optString("format"));
                    if ((!stones && !"yuan-recruitment".equals(json.optString("format"))) || json.optInt("version") != 1) throw new IllegalArgumentException();
                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    exports.save("application/json", stones ? "星石记录" : "招募记录", output -> output.write(bytes), (status, location) -> {
                        try {
                            if ("saved".equals(status)) resultEvent(new JSONObject().put("saved", true).put("location", location));
                            else if ("cancelled".equals(status)) resultEvent(new JSONObject().put("cancelled", true));
                            else fail("JSON 导出失败，请检查存储空间或重新选择位置");
                        } catch (Exception e) { fail("JSON 导出失败"); }
                    });
                } catch (Exception e) { fail("JSON 数据无效或超过 200 万字符，无法导出"); }
            }, "yuan-recruitment-export").start();
        });
    }
    boolean result(int request, int code, Intent data) {
        if (request != IMPORT_JSON) return false;
        if (code != Activity.RESULT_OK || data == null || data.getData() == null) {
            try { resultEvent(new JSONObject().put("cancelled", true)); } catch (Exception ignored) { }
            return true;
        }
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { fail("请从系统文件中选择 JSON 文件"); return true; }
        new Thread(() -> {
            try {
                String name = PublicExports.displayName(activity.getContentResolver(), uri);
                if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) { fail("请选择 .json 文件"); return; }
                try (InputStream input = activity.getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (input == null) throw new IllegalArgumentException();
                    byte[] buffer = new byte[8192]; int size;
                    while ((size = input.read(buffer)) != -1) {
                        if (output.size() + size > MAX_BYTES) { fail("JSON 文件不能超过 6 MB，请分批导入"); return; }
                        output.write(buffer, 0, size);
                    }
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
                    if (text.length() > MAX_CHARS) { fail("JSON 超过 200 万字符，请分批导入"); return; }
                    // 这里只读取，字段校验、预览和角色写入全部交给现有前端流程。
                    resultEvent(new JSONObject().put("name", name).put("text", text));
                }
            } catch (Exception e) { fail("JSON 文件读取失败，请检查权限并使用 UTF-8 编码"); }
        }, "yuan-recruitment-import").start();
        return true;
    }
}
