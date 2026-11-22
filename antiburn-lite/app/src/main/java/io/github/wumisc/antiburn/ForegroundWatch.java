package io.github.wumisc.antiburn;

import android.content.Context;

/**
 * v3.0.24：前台目标应用的实时判断。
 *
 * <p>两个来源，互为补位：</p>
 * <ul>
 *   <li><b>无障碍</b>：{@link BurnInMaskService#onAccessibilityEvent} 每次
 *       {@code TYPE_WINDOW_STATE_CHANGED} 把包名送进来，是唯一「实时」的通道，
 *       但只在无障碍引擎开启时才有。</li>
 *   <li><b>LSPosed</b>：目标 App 进程注入后经 {@link ConfigProvider#METHOD_PUT_LOG}
 *       回传，App 进程在 prefs 里记时间戳（{@link ConfigProvider#lastInject}）。
 *       不是实时，但覆盖「只用 LSPosed 引擎」的用户。</li>
 * </ul>
 *
 * <p>只保留「最近一个目标 App」，不维护历史：通知文案要的就是这一条，
 * 记多了既费内存也没人看。非目标应用（桌面、设置、微信）只更新前台包名，
 * 不会覆盖 {@link #targetPkg} —— 否则从抖音切到桌面再切回来，通知会被刷成「其它」。</p>
 */
public final class ForegroundWatch {

    /** 上一次上报的目标包名与时间（毫秒）。 */
    private static volatile String targetPkg;
    private static volatile long targetAt;

    /** kind → 代表包名：prefs 只存 kind，通知要显示中文名与包名归类。 */
    private static final String[][] KIND_PKG = {
            {"douyin", "com.ss.android.ugc.aweme"},
            {"drama", "com.phoenix.read.oversea.gp"},
            {"kuaishou", "com.smile.gifmaker"},
            {"bilibili", "tv.danmaku.bili"},
    };

    private ForegroundWatch() {
    }

    /** 无障碍回调入口：只记录，不做任何 IO/通知操作（避免拖慢事件分发）。 */
    static void onForeground(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        if (!VideoApps.isTarget(pkg)) {
            return;
        }
        long now = System.currentTimeMillis();
        // 同一个 App 反复进出（短视频详情页来回切）不重复刷新
        boolean changed = targetPkg == null || !targetPkg.equals(pkg);
        targetPkg = pkg;
        targetAt = now;
        if (changed) {
            GuardService.refreshIfDue(ctx);
        }
    }

    /** 最近一次见到的目标 App 包名；24 小时内没有记录返回 null。 */
    static String recentTarget(Context ctx) {
        long now = System.currentTimeMillis();
        if (targetPkg != null && now - targetAt < 24L * 3600_000L) {
            return targetPkg;
        }
        String kind = newestInjectKind(ctx);
        if (kind == null) {
            return null;
        }
        for (String[] kv : KIND_PKG) {
            if (kv[0].equals(kind)) {
                return kv[1];
            }
        }
        return null;
    }

    /** 与 {@link #recentTarget} 对应的时间戳；无记录返回 0。 */
    static long recentTargetAt(Context ctx) {
        long now = System.currentTimeMillis();
        if (targetPkg != null && now - targetAt < 24L * 3600_000L) {
            return targetAt;
        }
        long best = 0L;
        for (String[] kv : KIND_PKG) {
            long ts = ConfigProvider.lastInject(ctx, kv[0]);
            if (ts > best) {
                best = ts;
            }
        }
        return best > 0L && now - best < 24L * 3600_000L ? best : 0L;
    }

    private static String newestInjectKind(Context ctx) {
        long now = System.currentTimeMillis();
        long best = 0L;
        String kind = null;
        for (String[] kv : KIND_PKG) {
            long ts = ConfigProvider.lastInject(ctx, kv[0]);
            if (ts > best) {
                best = ts;
                kind = kv[0];
            }
        }
        return best > 0L && now - best < 24L * 3600_000L ? kind : null;
    }
}
