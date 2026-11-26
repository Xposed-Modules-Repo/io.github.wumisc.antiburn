package io.github.wumisc.antiburn;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
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
    /** 桌面别名后缀。清单里以「命名空间包名」绝对名声明，不一定是 applicationId。 */
    private static final String ALIAS_SUFFIX = ".LauncherAlias";
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

    /**
     * 写隐藏开关并即时幂等校正 alias 组件状态（DONT_KILL_APP）。
     *
     * @return 组件状态是否真的切换成功；false 时调用方必须把开关退回原值 ——
     *         否则界面显示「已隐藏」而图标还在，比报错更难排查。
     */
    public static boolean setHideLauncherIcon(Context c, boolean hide) {
        // v3.2.25：改成 commit。这条 prefs 与紧随其后的组件切换是「一对」，
        // apply 的异步落盘在进程被回收/崩溃时会丢，重启后开关自己变回关。
        // v3.2.25 修：回滚必须回到**真实旧值**。以前写的是 !hide，
        // 只在「取反」调用时成立；对幂等重放（hide 与旧值相同）失败时会把开关翻反。
        final boolean old = isHideLauncherIcon(c);
        prefs(c).edit().putBoolean(K_HIDE_LAUNCHER_ICON, hide).commit();
        if (applyLauncherVisibility(c, hide)) {
            return true;
        }
        prefs(c).edit().putBoolean(K_HIDE_LAUNCHER_ICON, old).commit();
        return false;
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
    public static boolean applyLauncherVisibility(Context c, boolean hide) {
        Context app = c.getApplicationContext();
        ComponentName alias = launcherAlias(app);
        PackageManager pm = app.getPackageManager();
        int target = hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                          : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        try {
            if (pm.getComponentEnabledSetting(alias) != target) {
                // DONT_KILL_APP：避免禁用组件时杀掉进程，中断配置写 / 磁贴刷新并触发 launcher 重启黑屏。
                pm.setComponentEnabledSetting(alias, target, PackageManager.DONT_KILL_APP);
            }
            return pm.getComponentEnabledSetting(alias) == target;
        } catch (Throwable t) {
            // v3.2.25：这里以前会直接抛 IllegalArgumentException —— 实验分支的
            // applicationId（…antiburn.api102）与清单命名空间（…antiburn）不同，
            // 用 getPackageName() 拼出的组件名在包里不存在，一点开关就崩、重开又变回关。
            // 现在先查清单里的真实别名，查不到才退回拼接；任何异常都不冒到 UI。
            return false;
        }
    }

    /**
     * 解析桌面别名的真实组件名。
     *
     * <p>清单里的 {@code .LauncherAlias} 是相对**命名空间包名**展开的，而
     * {@code getPackageName()} 返回的是 applicationId —— 两者不一致时
     * （实验分支、渠道改包名工具、双开）就会拼出一个不存在的组件。</p>
     */
    private static ComponentName launcherAlias(Context app) {
        String pkg = app.getPackageName();
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(
                    pkg, PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (pi != null && pi.activities != null) {
                for (ActivityInfo ai : pi.activities) {
                    if (ai != null && ai.name != null && ai.name.endsWith(ALIAS_SUFFIX)) {
                        return new ComponentName(pkg, ai.name);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 查不到就走下面的命名空间兜底
        }
        // v3.2.35：兜底不能用 applicationId 拼类名。清单 .LauncherAlias 相对**命名空间**
        // （io.github.wumisc.antiburn）展开，而 getPackageName() 是 applicationId
        // （实验分支 …antiburn.api102）。用本类所在包推导命名空间，拼出的组件才真实存在，
        // 否则 setComponentEnabledSetting 抛 IllegalArgumentException 一点开关就崩。
        String cls = AppVisibilityStore.class.getName();
        String ns = cls.substring(0, cls.length() - ".AppVisibilityStore".length());
        return new ComponentName(pkg, ns + ALIAS_SUFFIX);
    }

    /** P1：启动同步——读 prefs 校正 alias；只写系统组件状态，不写任何配置。 */
    public static void syncOnStartup(Context c) {
        applyLauncherVisibility(c, isHideLauncherIcon(c));
    }
}
