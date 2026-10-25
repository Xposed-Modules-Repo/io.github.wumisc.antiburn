package io.github.wumisc.antiburn;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 桌面图标壳：仅用于在桌面显示一个图标入口（MAIN + LAUNCHER）。
 * 用户可在配置页关闭图标（此组件被禁用，桌面即不显示）。
 * 打开后直接跳转到真正的配置页 MainActivity。
 */
public class IconActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            startActivity(new Intent(this, MainActivity.class));
        } catch (Throwable t) {
            // ignore
        }
        finish();
    }
}
