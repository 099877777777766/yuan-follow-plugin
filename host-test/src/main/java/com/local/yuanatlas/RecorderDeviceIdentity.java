package com.local.yuanatlas;

import android.content.Context;
import android.util.AtomicFile;
import android.webkit.JavascriptInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.HashSet;

/** 本机及共享密钥独立保存于noBackup目录，不参与角色迁移。 */
public final class RecorderDeviceIdentity {
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    private final AtomicFile keysFile;
    public RecorderDeviceIdentity(Context context) {
        this(new File(context.getNoBackupFilesDir(), "recorder-device-id"));
    }
    // 测试使用独立临时文件，避免改写真实设备标识。
    RecorderDeviceIdentity(File target) {
        file = new AtomicFile(target);
        keysFile = new AtomicFile(new File(target.getPath() + "-keys.json"));
    }
    private static boolean valid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }
    private void writeKeys(JSONObject value) throws Exception {
        FileOutputStream stream = null;
        try {
            byte[] data = value.toString().getBytes(StandardCharsets.UTF_8);
            stream = keysFile.startWrite(); stream.write(data); keysFile.finishWrite(stream); stream = null;
            if (!java.util.Arrays.equals(data, keysFile.readFully())) throw new Exception();
        } finally { if (stream != null) keysFile.failWrite(stream); }
    }
    private JSONObject loadKeys() throws Exception {
        JSONObject identity = new JSONObject(getId());
        if (!identity.optBoolean("ok")) throw new Exception();
        String own = identity.getString("id");
        File base = keysFile.getBaseFile(), backup = new File(base.getPath() + ".bak");
        if (!base.exists() && !backup.exists()) {
            JSONObject initial = new JSONObject().put("own", own).put("active", own).put("keys", new JSONArray().put(own));
            writeKeys(initial); return initial;
        }
        if (base.length() > 8192 || backup.length() > 8192) throw new Exception();
        JSONObject value = new JSONObject(new String(keysFile.readFully(), StandardCharsets.UTF_8));
        JSONArray keys = value.getJSONArray("keys"); HashSet<String> known = new HashSet<>();
        if (keys.length() > 20 || !own.equals(value.getString("own"))) throw new Exception();
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.getString(i);
            if (!valid(key) || !known.add(key)) throw new Exception();
        }
        if (!known.contains(own) || !known.contains(value.getString("active"))) throw new Exception();
        return value;
    }
    @JavascriptInterface public String readKeys() { return updateKeys(null, false); }
    @JavascriptInterface public String importKey(String key) { return updateKeys(key, true); }
    @JavascriptInterface public String selectKey(String key) { return updateKeys(key, false); }
    private String updateKeys(String key, boolean importing) {
        synchronized (LOCK) {
            try {
                JSONObject value = loadKeys();
                if (key != null) {
                    if (!valid(key)) return "{\"ok\":false,\"error\":\"密钥格式不正确\"}";
                    JSONArray keys = value.getJSONArray("keys"); boolean found = false;
                    for (int i = 0; i < keys.length(); i++) if (key.equals(keys.getString(i))) found = true;
                    if (!found) {
                        if (!importing) throw new Exception();
                        if (keys.length() >= 20) return "{\"ok\":false,\"error\":\"最多保存20把密钥\"}";
                        keys.put(key);
                    }
                    // 导入即切换后续导出的默认密钥；保留全部旧密钥供历史图解密。
                    value.put("active", key); writeKeys(value);
                }
                return new JSONObject().put("ok", true).put("value", value).toString();
            } catch (Exception e) { return "{\"ok\":false,\"error\":\"本机密钥无法读取或保存\"}"; }
        }
    }
    @JavascriptInterface public String getId() {
        synchronized (LOCK) {
            FileOutputStream stream = null;
            try {
                File base = file.getBaseFile(), backup = new File(base.getPath() + ".bak");
                String id;
                if (base.exists() || backup.exists()) {
                    if (base.length() > 128 || backup.length() > 128) throw new Exception();
                    id = new String(file.readFully(), StandardCharsets.UTF_8);
                    if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) throw new Exception();
                } else {
                    id = UUID.randomUUID().toString();
                    stream = file.startWrite(); stream.write(id.getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); stream = null;
                    if (!id.equals(new String(file.readFully(), StandardCharsets.UTF_8))) throw new Exception();
                }
                return new JSONObject().put("ok", true).put("id", id).toString();
            } catch (Exception e) {
                if (stream != null) file.failWrite(stream);
                // 损坏时不得重建UUID，否则以前的本机图片将失去归属。
                return "{\"ok\":false,\"error\":\"设备标识无法读取或保存\"}";
            }
        }
    }
}
