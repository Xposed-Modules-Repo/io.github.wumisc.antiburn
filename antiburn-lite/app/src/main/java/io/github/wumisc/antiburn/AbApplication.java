package io.github.wumisc.antiburn;

import android.app.Application;

/**
 * 应用入口：尽早初始化 ConfStore 的应用内镜像路径。
 * Activity / TileService / ContentProvider 都可能在 Application 之外被拉起，
 * 这里统一兜底，保证任何进程第一次写配置时镜像一定可写。
 */
public final class AbApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ConfStore.init(this);
        // 旧版本（只写 /data/local/tmp）升级后没有应用内镜像，
        // 会导致 ConfigProvider 取不到配置、Hook 侧跑默认值。启动时后台补齐一次。
        ConfStore.migrateIfNeededAsync();
    }
}
