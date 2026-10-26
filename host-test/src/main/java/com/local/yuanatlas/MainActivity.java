package com.local.yuanatlas;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.webkit.WebViewAssetLoader;
import java.io.ByteArrayInputStream;
import org.json.JSONObject;

/** APK 只加载内置 H5；导出写入公共目录并登记系统文件库。 */
public class MainActivity extends ScreenOrientationActivity {
    static final String RECORDER_RETURN_ROUTE="recorderReturnRoute";
    /** 原生回跳只允许编辑页与已知参数，不能借此打开任意网页或其他功能。 */
    static boolean validRecorderReturnRoute(String route){
        return route!=null&&route.length()<2048&&route.matches("/pages/recorder/editor\\?game=(codeYuan|ruYuan)(&id=[A-Za-z0-9_%~.!()*'-]*)?(&panelCollapsed=[01])?");
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        String route=intent.getStringExtra(RECORDER_RETURN_ROUTE);
        if(validRecorderReturnRoute(route)&&webView!=null){
            // 相同页面不重载，保留原编辑位置和表内容；其他页面则定位回来源表。
            webView.evaluateJavascript("if(location.hash!=="+JSONObject.quote("#"+route)+")location.hash="+JSONObject.quote(route)+";",null);
        }
    }
    private WebView webView;
    private boolean drawFullscreen;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/www/index.html";
    private PublicExports publicExports;
    private boolean savingImage;
    private boolean backPending;
    private AvatarPicker avatarPicker;
    private RecorderImagePicker recorderImagePicker;
    private RecorderRoundLauncher recorderRoundLauncher;
    private CatalogFiles catalogFiles;
    private RecruitmentFiles recruitmentFiles;
    private StoneOcr stoneOcr;
    private RecorderLanBridge recorderLan;
    private RecorderCameraPermission recorderCamera;
    private android.window.OnBackInvokedCallback backCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        CaptureDiagnostics.install(this);
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.WHITE);
        webView = new WebView(this);
        container.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(container);
        // Android 15 强制边到边后仍为内容留出状态栏和手势导航空间。
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            // 抽卡场景覆盖整屏；退出场景后仍沿用原有系统栏安全边距。
            if (drawFullscreen) {
                view.setPadding(0, 0, 0, 0);
                return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
            }
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        container.requestApplyInsets();
        webView.setBackgroundColor(Color.WHITE);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        recorderCamera = new RecorderCameraPermission(this);
        webView.setWebChromeClient(recorderCamera);
        publicExports = new PublicExports(this);
        webView.addJavascriptInterface(new ImageSaver(), "YuanImages");
        webView.addJavascriptInterface(new DrawDisplay(), "YuanDisplay");
        webView.addJavascriptInterface(new RoleFiles(this), "YuanRoles");
        // 临时编辑窗口不能接管主窗口持有的实时同步连接。
        if(!temporaryRecorderEditor()){
            recorderLan = RecorderLanBridge.attach(this, webView);
            webView.addJavascriptInterface(recorderLan, "YuanRecorderLan");
        }
        webView.addJavascriptInterface(new AppBackupFiles(this, webView), "YuanAppBackup");
        RecorderDeviceIdentity recorderDevice = new RecorderDeviceIdentity(this);
        recorderDevice.getId(); // 首次启动持久化，失败由导入导出入口明确提示。
        webView.addJavascriptInterface(recorderDevice, "YuanRecorderDevice");
        avatarPicker = new AvatarPicker(this, webView);
        webView.addJavascriptInterface(avatarPicker, "YuanAvatar");
        recorderImagePicker = new RecorderImagePicker(this, webView);
        webView.addJavascriptInterface(recorderImagePicker, "YuanRecorderQr");
        recorderRoundLauncher = new RecorderRoundLauncher(this, webView);
        webView.addJavascriptInterface(recorderRoundLauncher, "YuanRecorderRounds");
        catalogFiles = new CatalogFiles(this, webView, publicExports);
        webView.addJavascriptInterface(catalogFiles, "YuanCatalog");
        recruitmentFiles = new RecruitmentFiles(this, webView, publicExports);
        webView.addJavascriptInterface(recruitmentFiles, "YuanRecruitment");
        stoneOcr = new StoneOcr(this, webView);
        webView.addJavascriptInterface(stoneOcr, "YuanStoneOcr");
        webView.addJavascriptInterface(new RuYuanCapture(this), "YuanRuYuanCapture");
        webView.addJavascriptInterface(new StoneGameCapture(this), "YuanStoneGameCapture");
        webView.addJavascriptInterface(new TeamGameCapture(this), "YuanTeamGameCapture");
        configureEditorBridge(webView);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && HOST.equals(uri.getHost()) && uri.getPath().startsWith("/catalog/images/")) {
                    WebResourceResponse image = catalogFiles.imageResponse(uri.getPath().substring("/catalog/images/".length()));
                    if (image != null) return image;
                }
                WebResourceResponse local = loader.shouldInterceptRequest(request.getUrl());
                if (local != null) return local;
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || !uri.getPath().startsWith("/assets/www/");
            }
        });
        // Activity 被系统回收后也优先恢复这次悬浮窗的来源表。
        if (state == null || webView.restoreState(state) == null || validRecorderReturnRoute(getIntent().getStringExtra(RECORDER_RETURN_ROUTE))) webView.loadUrl(startUrl());
        // Android 13 起由系统返回分发器接收侧滑和返回键，较旧系统沿用 onBackPressed。
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    // 会话编辑窗口复用相同资源、图鉴和返回处理，只替换初始页面与专用桥接。
    protected boolean temporaryRecorderEditor(){return false;}
    protected String startUrl(){
        String route=getIntent().getStringExtra(RECORDER_RETURN_ROUTE);
        return validRecorderReturnRoute(route)?START+"#"+route:START;
    }
    protected void configureEditorBridge(WebView view){}

    private class DrawDisplay {
        @JavascriptInterface public void setDrawFullscreen(boolean enabled) {
            runOnUiThread(() -> {
                String current = webView.getUrl();
                if (current == null || !(current.equals(START) || current.startsWith(START + "#"))) return;
                drawFullscreen = enabled;
                View decor = getWindow().getDecorView();
                if (Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) {
                        controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                        if (enabled) controller.hide(WindowInsets.Type.systemBars());
                        else controller.show(WindowInsets.Type.systemBars());
                    }
                } else {
                    decor.setSystemUiVisibility(enabled ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE : View.SYSTEM_UI_FLAG_VISIBLE);
                }
                // 切换时同步触发布局，让 WebView 使用完整高度或恢复安全区域。
                ((View) webView.getParent()).requestApplyInsets();
            });
        }
    }

    private void imageResult(String status) {
        imageResult(status, "");
    }
    private void imageResult(String status, String location) {
        if (isFinishing() || isDestroyed()) return;
        try {
            JSONObject detail = new JSONObject().put("status", status).put("location", location);
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('yuan-image-save',{detail:" + detail + "}))", null);
        } catch (Exception ignored) { }
    }
    private class ImageSaver {
        // 页面来源固定、导航封闭；没有文件路径参数和任意文件读取接口。
        @JavascriptInterface public void savePng(String data) {
            saveNamedPng(data, "配队");
        }
        // 配队表名仅作为文件名前缀；目录和高清 PNG 写入仍沿用公共导出器。
        @JavascriptInterface public void saveNamedTeamPng(String data, String name) {
            String safe = name == null ? "" : name.replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
            if (safe.length() > 80) safe = safe.substring(0, 80);
            saveNamedPng(data, safe.isEmpty() ? "配队" : "配队-" + safe);
        }
        @JavascriptInterface public void saveOperationPng(String data) {
            saveNamedPng(data, "操作记录");
        }
        // 表名仅作受限文件名前缀，过滤路径字符；继续通过公共导出器写入原 PNG。
        @JavascriptInterface public void saveNamedOperationPng(String data, String name) {
            String safe = name == null ? "" : name.replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
            if (safe.length() > 80) safe = safe.substring(0, 80);
            saveNamedPng(data, safe.isEmpty() ? "操作记录" : "操作记录-" + safe);
        }
        // 文件名前缀由原生代码固定，沿用原有来源与PNG校验，不接受页面传入路径。
        // 招募长图沿用系统保存流程，使用独立文件名。
        @JavascriptInterface public void saveRecruitmentPng(String data) { saveNamedPng(data, "招募记录"); }
        @JavascriptInterface public void saveAtlasPng(String data) { saveNamedPng(data, "图鉴头像姓名对照表"); }

        private void saveNamedPng(String data, String prefix) {
            saveNamedImage(data, prefix, "image/png");
        }
        // 独立能力名称让旧安装包明确提示更新，避免JPG交给只接受PNG的旧接口。
        @JavascriptInterface public void saveNamedOperationJpeg(String data, String name) {
            String safe = name == null ? "" : name.replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
            if (safe.length() > 80) safe = safe.substring(0, 80);
            saveNamedImage(data, safe.isEmpty() ? "操作记录" : "操作记录-" + safe, "image/jpeg");
        }
        // 配队JPG沿用公共原字节保存器，不能重新编码而破坏隐藏的加密数据。
        @JavascriptInterface public void saveNamedTeamJpeg(String data, String name) {
            String safe = name == null ? "" : name.replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
            if (safe.length() > 80) safe = safe.substring(0, 80);
            saveNamedImage(data, safe.isEmpty() ? "配队" : "配队-" + safe, "image/jpeg");
        }
        private void saveNamedImage(String data, String prefix, String mime) {
            runOnUiThread(() -> {
                if (savingImage) return;
                try {
                    String current = webView.getUrl();
                    if (current == null || !(current.equals(START) || current.startsWith(START + "#"))
                        || data == null || data.length() > ("image/jpeg".equals(mime) ? 17 : ("招募记录".equals(prefix) || "图鉴头像姓名对照表".equals(prefix)) ? 32 : 8) * 1024 * 1024 || !data.startsWith("data:" + mime + ";base64,")) throw new IllegalArgumentException();
                    byte[] bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT);
                    byte[] signature = {(byte)137,80,78,71,13,10,26,10};
                    if (bytes.length < 8) throw new IllegalArgumentException();
                    if ("image/jpeg".equals(mime)) {
                        if (bytes.length > 12 * 1024 * 1024 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216 || (bytes[2] & 255) != 255) throw new IllegalArgumentException();
                    } else for (int i = 0; i < 8; i++) if (bytes[i] != signature[i]) throw new IllegalArgumentException();
                    savingImage = true;
                    // 原样写入图片字节；任何重新压缩都可能破坏JPG隐写数据。
                    publicExports.save(mime, prefix, stream -> stream.write(bytes), (status, location) -> {
                        savingImage = false; imageResult(status, location);
                    });
                } catch (Exception error) { savingImage = false; imageResult("error"); }
            });
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (publicExports != null && publicExports.result(request, result, data)) return;
        if (stoneOcr != null && stoneOcr.result(request, result, data)) return;
        if (avatarPicker != null && avatarPicker.result(request, result, data)) return;
        if (recorderImagePicker != null && recorderImagePicker.result(request, result, data)) return;
        if (recorderRoundLauncher != null && recorderRoundLauncher.result(request, result, data)) return;
        if (catalogFiles != null && catalogFiles.result(request, result, data)) return;
        if (recruitmentFiles != null && recruitmentFiles.result(request, result, data)) return;
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (recorderCamera != null && recorderCamera.permissions(request)) return;
        if (publicExports != null) publicExports.permissions(request, results);
    }

    @Override public void onBackPressed() {
        handleBack();
    }
    private void handleBack() {
        if (backPending || isFinishing()) return;
        backPending = true;
        // 未加载完不退出；脚本异常也留在当前页，避免丢失尚未保存的输入。
        webView.evaluateJavascript("(function(){try{return window.__yuanBack ? window.__yuanBack() : true}catch(e){return true}})()", handled -> {
            if ("false".equals(handled)) finish();
            // 合并短时间连续返回，避免同一次手势跨过两页。
            webView.postDelayed(() -> backPending = false, 300);
        });
    }
    // 原生应用关联完成后通知当前表单刷新，不重载页面以免丢失待核对内容。
    void refreshCaptureGames(){if(webView!=null)webView.evaluateJavascript("window.dispatchEvent(new Event('yuan-capture-resume'))",null);}
    @Override protected void onResume(){super.onResume();if(webView!=null)webView.postDelayed(()->{if(!isFinishing())webView.evaluateJavascript("window.dispatchEvent(new Event('yuan-capture-resume'))",null);},400);}
    @Override protected void onSaveInstanceState(Bundle state) { webView.saveState(state); super.onSaveInstanceState(state); }
    @Override protected void onStop() {
        // 进入后台后撤销未完成的授权，并通知当前扫码页释放摄像头。
        if (recorderCamera != null) recorderCamera.cancel();
        if (webView != null) webView.evaluateJavascript("window.dispatchEvent(new Event('yuan-recorder-camera-hidden'))", null);
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (recorderCamera != null) recorderCamera.cancel();
        if (recorderLan != null) recorderLan.detach(this);
        if(recorderRoundLauncher!=null)recorderRoundLauncher.destroy();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        webView.destroy(); super.onDestroy();
    }
}
