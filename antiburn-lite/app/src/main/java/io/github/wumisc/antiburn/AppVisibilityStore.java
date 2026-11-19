package io.github.wumisc.antiburn;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/**
 * v3.0.11 P1/P2：本 App 自身可见性持久化（桌面图标 / 最近任务）。
 *
 * <p>独立 SharedPreferences，与 Hook 配置文件及跨进程配置镜像完全隔离：
 * 不占既有状态的布尔位序（历史 Bool 0..42 零改），不进越界哈希。
 * 本类只碰本 App 自身组件开关，绝不影响防烧屏周期 / 无障碍 / 磁贴。</p>
 *
 * <p>注：本类注释与代码刻意不出现两类全局配置对象的类名，以便存储隔离守卫按字面计数。</p>
 */
public final class AppVisibilityStore {
    private static final String PREFS = "antiburn_visibility";
    private static final String K_HIDE_LAUNCHER_ICON = "hide_launcher_icon";
    private static final String K_HIDE_RECENTS = "hide_recents";

    private AppVisibilityStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 桌面图标是否已隐藏；默认 false（图标可见，不破坏既有功能）。 */
    public static boolean isHideLauncherIcon(Context c) {
        return prefs(c).getBoolean(K_HIDE_LAUNCHER_ICON, false);
    }

    /** 写隐藏开关并即时幂等校正 alias 组件状态（DONT_KILL_APP）。 */
    public static void setHideLauncherIcon(Context c, boolean hide) {
        prefs(c).edit().putBoolean(K_HIDE_LAUNCHER_ICON, hide).apply();
        applyLauncherVisibility(c, hide);
    }

    /** 最近任务是否已隐藏；默认 false（正常出现在最近任务）。 */
    public static boolean isHideRecents(Context c) {
        return prefs(c).getBoolean(K_HIDE_RECENTS, false);
    }

    /** 只写 prefs、不触发生效；生效由调用方（顶层 applyRecentsVisibility）完成。 */
    public static void setHideRecents(Context c, boolean hide) {
        prefs(c).edit().putBoolean(K_HIDE_RECENTS, hide).apply();
    }

    /**
     * P1：按开关幂等校正 alias 组件状态。
     * 只切 .LauncherAlias，绝不切本体 .MainActivity（本体常驻 enabled 是防锁死的结构保证）。
     */
    public static void applyLauncherVisibility(Context c, boolean hide) {
        Context app = c.getApplicationContext();
        ComponentName alias = new ComponentName(app, app.getPackageName() + ".LauncherAlias");
        PackageManager pm = app.getPackageManager();
        int target = hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                          : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        if (pm.getComponentEnabledSetting(alias) != target) {
            // DONT_KILL_APP：避免禁用组件时杀掉进程，中断配置写 / 磁贴刷新并触发 launcher 重启黑屏。
            pm.setComponentEnabledSetting(alias, target, PackageManager.DONT_KILL_APP);
        }
    }

    /** P1：启动同步——读 prefs 校正 alias；只写系统组件状态，不写任何配置。 */
    public static void syncOnStartup(Context c) {
        applyLauncherVisibility(c, isHideLauncherIcon(c));
    }
}
