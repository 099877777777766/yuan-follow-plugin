package com.local.yuanatlas;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;

/** 手机固定竖屏，平板继续遵循系统旋转设置；应用内页面共用同一规则。 */
public abstract class ScreenOrientationActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        applyScreenOrientation(getResources().getConfiguration());
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyScreenOrientation(configuration);
    }

    private void applyScreenOrientation(Configuration configuration) {
        // 使用最小宽度，避免手机转横后因当前宽度变大而被误判为平板。
        // 尺寸变化时重新判断，也支持折叠屏展开进入平板尺寸后解除竖屏限制。
        int orientation = configuration.smallestScreenWidthDp >= 600
            ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (getRequestedOrientation() != orientation) setRequestedOrientation(orientation);
    }
}
