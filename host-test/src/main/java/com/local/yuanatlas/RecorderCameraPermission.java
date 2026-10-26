package com.local.yuanatlas;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;

/** 仅内置页面扫码时申请相机，不授权麦克风或第三方网页。 */
final class RecorderCameraPermission extends WebChromeClient {
    private static final int CAMERA_REQUEST = 7481;
    private final Activity activity;
    private PermissionRequest pending;
    private boolean requesting;
    RecorderCameraPermission(Activity activity) { this.activity = activity; }

    static boolean allowed(Uri origin, String[] resources) {
        return origin != null && "https".equals(origin.getScheme())
            && "appassets.androidplatform.net".equals(origin.getHost())
            && (origin.getPort() == -1 || origin.getPort() == 443)
            && resources != null && resources.length == 1
            && PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resources[0]);
    }
    @Override public void onPermissionRequest(PermissionRequest request) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || requesting
                || !allowed(request.getOrigin(), request.getResources())) { request.deny(); return; }
            cancel(); pending = request;
            if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) grant();
            else {
                requesting = true;
                try { activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST); }
                catch (RuntimeException error) { requesting = false; cancel(); }
            }
        });
    }
    @Override public void onPermissionRequestCanceled(PermissionRequest request) {
        // WebView 已取消的请求不能在系统授权稍后返回时被重新批准。
        activity.runOnUiThread(() -> { if (pending == request) pending = null; });
    }
    boolean permissions(int request) {
        if (request != CAMERA_REQUEST) return false;
        requesting = false;
        if (!activity.isFinishing() && !activity.isDestroyed()
            && activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) grant();
        else cancel();
        return true;
    }
    private void grant() {
        PermissionRequest request = pending; pending = null;
        if (request != null) request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
    }
    void cancel() {
        PermissionRequest request = pending; pending = null;
        if (request != null) request.deny();
    }
}
