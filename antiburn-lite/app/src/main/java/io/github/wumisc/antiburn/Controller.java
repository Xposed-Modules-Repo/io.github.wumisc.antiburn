package io.github.wumisc.antiburn;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 核心控制器：
 *  - hook SystemUI 状态栏窗口/导航栏视图，拿到视图引用
 *  - 周期切换 隐藏/显示（时长走配置）
 *  - 灭屏或前台 App 在白名单时暂停切换（白名单语义：名单内应用前台时不隐藏系统栏）
 */
public final class Controller {

    private static final String TAG = XposedInit.TAG;

    // 目标视图
    private static volatile View statusView;   // 状态栏内容容器（status_bar_container 等）
    private static volatile View navView;      // 导航栏视图（NavigationBarView）
    private static volatile View notifyArea;   // 状态栏通知图标区（隐藏期间保留显示）

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static boolean hidden = false;
    private static boolean started = false;
    private static long lastWlDiag = 0; // 白名单诊断日志节流

    private Controller() {
    }

    static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        XposedInit.diag("SYSINIT sdk=" + android.os.Build.VERSION.SDK_INT
                + " release=" + android.os.Build.VERSION.RELEASE
                + " brand=" + android.os.Build.BRAND + " device=" + android.os.Build.DEVICE
                + " base.os=" + (android.os.Build.VERSION.BASE_OS == null ? "?" : android.os.Build.VERSION.BASE_OS));

        // ---- 状态栏窗口 ----
        Class<?> winCls = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.window.StatusBarWindowView", cl);
        if (winCls != null) {
            XposedHelpers.findAndHookMethod(winCls, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // Hook 回调里的异常会一路冒到宿主主线程：SystemUI 因此崩溃重启，
                            // LSPosed 连崩几次就把模块丢进安全模式（用户看到的是「莫名其妙
                            // 进了安全模式，所有模块都失效」）。宿主是别人的进程，
                            // 我们没有任何理由让它替我们崩。
                            try {
                                View win = (View) param.thisObject;
                                View bar = findStatusBarView(win);
                                if (bar != null) {
                                    statusView = bar;
                                    findNotifyArea(bar);
                                    Log.i(TAG, "status bar view found: " + desc(bar));
                                } else {
                                    // 兜底：找不到内容容器就用窗口本身（会连下拉面板一起隐藏，慎用）
                                    statusView = win;
                                    Log.w(TAG, "status_bar_container not found, fallback to window");
                                }
                                startCycle();
                            } catch (Throwable t) {
                                hookFail("StatusBarWindowView.attach", t);
                            }
                        }
                    });
        } else {
            Log.e(TAG, "StatusBarWindowView not found");
        }

        // ---- 导航栏（多候选，澎湃 4.0 类名可能不同）----
        Class<?> navCls = XposedHelpers.findClassIfExists(
                "com.android.systemui.navigationbar.NavigationBarView", cl);
        if (navCls != null) {
            try {
                XposedHelpers.findAndHookMethod(navCls, "onAttachedToWindow",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    navView = (View) param.thisObject;
                                    Log.i(TAG, "nav view found (NavigationBarView): " + desc(navView));
                                    startCycle();
                                } catch (Throwable t) {
                                    hookFail("NavigationBarView.attach", t);
                                }
                            }
                        });
            } catch (Throwable t) {
                Log.w(TAG, "hook NavigationBarView failed", t);
            }
        } else {
            Log.w(TAG, "NavigationBarView not found");
        }

        // 候选2：NavigationBarFrame 容器，attach 后按 id 找 navigation_bar
        Class<?> frameCls = XposedHelpers.findClassIfExists(
                "com.android.systemui.navigationbar.NavigationBarFrame", cl);
        if (frameCls != null) {
            try {
                XposedHelpers.findAndHookMethod(frameCls, "onAttachedToWindow",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (navView != null) {
                                        return;
                                    }
                                    View frame = (View) param.thisObject;
                                    View nb = findNavById(frame);
                                    navView = nb != null ? nb : frame;
                                    Log.i(TAG, "nav view found (NavigationBarFrame): " + desc(navView));
                                    startCycle();
                                } catch (Throwable t) {
                                    hookFail("NavigationBarFrame.attach", t);
                                }
                            }
                        });
            } catch (Throwable t) {
                Log.w(TAG, "hook NavigationBarFrame failed", t);
            }
        } else {
            Log.w(TAG, "NavigationBarFrame not found");
        }

        // 兜底3：全量扫描——任何 View attach 时按类名/id 识别导航栏与状态栏。
        // 无论澎湃把类改成什么名字（HyperOS3/4 差异）都能抓到。
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 这是全 View 扫描：宿主里每一次 attach 都会进这里，
                            // 是全模块最热的回调，也是最该包住的那个。
                            try {
                                View v = (View) param.thisObject;
                                if (v == null) {
                                    return;
                                }
                                // 导航栏
                                if (navView == null && isNavView(v)) {
                                    navView = v;
                                    Log.i(TAG, "nav view caught (View scan): " + desc(v));
                                    startCycle();
                                    return;
                                }
                                // 状态栏兜底：类名含 StatusBar 且非导航（HyperOS3 类名若不同也能抓）
                                if (statusView == null && isStatusBarWindow(v)) {
                                    View bar = findStatusBarView(v);
                                    if (bar != null) {
                                        statusView = bar;
                                        Log.i(TAG, "status bar view caught (View scan): " + desc(bar));
                                        startCycle();
                                    }
                                }
                            } catch (Throwable t) {
                                hookFail("View.attach scan", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "hook View.onAttachedToWindow failed", t);
        }

        // 保险：5 秒后若状态栏还没抓到，尝试按 id 在系统窗口里扫一遍
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (statusView == null) {
                    Log.w(TAG, "status view still null after 5s");
                }
            }
        }, 5000);
    }

    /**
     * 在状态栏窗口内找"图标内容"视图（时钟/信号/电池等）。
     * 只隐藏这一层，左右两侧的下拉把手保留，下拉通知栏/控制中心不受影响。
     * 候选 id 链：status_bar_contents → status_bar_icons → notification_icon_area
     * 找不到再退到 status_bar 内容层；最后才退到整窗。
     */
    private static View findStatusBarView(View root) {
        try {
            android.content.res.Resources r = root.getResources();
            String[] iconIds = {
                    "status_bar_contents",
                    "status_bar_icons",
                    "notification_icon_area"
            };
            for (String id : iconIds) {
                int rid = r.getIdentifier(id, "id", "com.android.systemui");
                Log.i(TAG, "probe id " + id + " -> " + rid);
                if (rid != 0) {
                    View v = root.findViewById(rid);
                    if (v != null) {
                        Log.i(TAG, "icon container: " + desc(v) + " children=" + vChildNames(v));
                        return v;
                    }
                }
            }
            // 次选：状态栏内容层（仍保留窗口根的下拉区域）
            int rid = r.getIdentifier("status_bar", "id", "com.android.systemui");
            Log.i(TAG, "probe id status_bar -> " + rid);
            if (rid != 0) {
                View v = root.findViewById(rid);
                if (v != null) {
                    return v;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "find by id error", t);
        }
        return null;
    }

    /** 打印子视图 id/类名，便于定位灵动岛等特殊视图 */
    private static String vChildNames(View v) {
        if (!(v instanceof android.view.ViewGroup)) {
            return "not-a-group";
        }
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            sb.append('[').append(c.getClass().getSimpleName()).append(' ')
                    .append(c.getId()).append("] ");
        }
        return sb.toString();
    }

    /** 按 id 在导航栏容器里找 navigation_bar 视图 */
    private static View findNavById(View root) {
        try {
            int rid = root.getResources().getIdentifier(
                    "navigation_bar", "id", "com.android.systemui");
            if (rid != 0) {
                View v = root.findViewById(rid);
                if (v != null) {
                    return v;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findNavById error", t);
        }
        return null;
    }

    /** 按类名/id 判断是否为导航栏视图（排除状态栏相关类） */
    private static boolean isNavView(View v) {
        String n = v.getClass().getName();
        if (n.contains("NavigationBar")) {
            // 排除状态栏窗口类（不含 NavigationBar 字样，双保险）
            return true;
        }
        try {
            int rid = v.getResources().getIdentifier(
                    "navigation_bar", "id", "com.android.systemui");
            if (rid != 0 && v.getId() == rid) {
                return true;
            }
        } catch (Throwable t) {
            // ignore
        }
        return false;
    }

    /** 判断是否状态栏窗口根（类名含 StatusBar 且非导航），用于 View 扫描兜底 */
    private static boolean isStatusBarWindow(View v) {
        String n = v.getClass().getName();
        if (n.contains("StatusBar") && !n.contains("NavigationBar")) {
            // 只把"可能是窗口根"的容器作为候选，避免误伤普通 StatusBarView 子项
            return true;
        }
        return false;
    }

    /** 查找状态栏内"通知图标区"（隐藏状态栏静态元素时保留它，让通知照常可见） */
    private static void findNotifyArea(View root) {
        notifyArea = null;
        if (!(root instanceof android.view.ViewGroup)) {
            return;
        }
        android.view.ViewGroup g = (android.view.ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            String n = c.getClass().getName();
            // Android 12+ 通知图标容器：NotificationIconAreaView / NotificationIconContainer
            if (n.contains("Notification") || n.contains("notification")) {
                notifyArea = c;
                Log.i(TAG, "notify area: " + desc(c));
                return;
            }
        }
        // 兜底：按 id notification_icon_area
        try {
            int rid = root.getResources().getIdentifier(
                    "notification_icon_area", "id", "com.android.systemui");
            if (rid != 0) {
                View v = root.findViewById(rid);
                if (v != null) {
                    notifyArea = v;
                    Log.i(TAG, "notify area by id: " + desc(v));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String desc(View v) {
        if (v == null) {
            return "null";
        }
        return v.getClass().getSimpleName() + " id=" + v.getId()
                + " pkg=" + (v.getContext() != null ? v.getContext().getPackageName() : "?");
    }

    /**
     * Hook 回调里的异常兜底：记一条 logcat + 一条 diag，然后咽掉。
     *
     * <p>必须是 Throwable 而不是 Exception —— 宿主 View 里出现的
     * LinkageError / StackOverflowError / OOM 同样会把 SystemUI 打崩，
     * 而这三种在「模块被安全模式禁用」的现场里最常见。</p>
     */
    private static void hookFail(String where, Throwable t) {
        Log.w(TAG, "hook callback failed: " + where, t);
        try {
            XposedInit.diag("HOOK FAIL " + where + " "
                    + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        } catch (Throwable ignored) {
        }
    }

    private static void startCycle() {
        if (started) {
            return;
        }
        started = true;
        Log.i(TAG, "cycle started");
        handler.removeCallbacks(cycleRunnable);
        handler.postDelayed(cycleRunnable, 1000);
    }

    private static final Runnable cycleRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                Log.e(TAG, "tick error", t);
            }
        }
    };

    private static void tick() {
        // 拿到宿主 Context 才能经 ConfigProvider 跨进程读配置（SELinux 挡住直接读 /data/local/tmp）
        if (statusView != null) {
            Config.attach(statusView.getContext());
        }
        Config.reload();

        boolean screenOn = isScreenOn();
        String top = currentTopPackage();
        // v2.8：名单模式（白/黑/关闭）的解释权全部收进 shouldIntervene，
        // 这里不再出现 inWhitelist 这类把某一种模式写死的变量。
        boolean intervene = Config.shouldIntervene(top);
        boolean inList = top != null && Config.list.contains(top);

        // 名单诊断（5s 节流）：让用户从 diag 文件直接核对「前台包 vs 名单」是否命中
        long nowWl = SystemClock.elapsedRealtime();
        if (nowWl - lastWlDiag >= 5000) {
            lastWlDiag = nowWl;
            XposedInit.diag("SYS top=" + top + " mode=" + Config.listMode
                    + " list=" + Config.list + " hit=" + inList
                    + " intervene=" + intervene + " status=" + Config.enableStatus
                    + " nav=" + Config.enableNav + " shift=" + Config.enableShift
                    + " sb=" + Config.enableStatusBar
                    + " ui=" + Config.uiStyle + " src=" + Config.lastSource
                    + " rev=" + Config.lastRev + " cache=" + (Config.cacheReady() ? "ok" : "none")
                    + " fetch=" + Config.fetchState
                    // eng：哪条引擎在跑。升级期最容易出现的怪象就是「用户切了 overlay，
                    // SystemUI 里还是旧 Hook 在动手」，没有这个字段只能靠版本号猜。
                    + " eng=" + Config.engine
                    + " ver=" + BuildConfig.VERSION_NAME);
        }

        // 暂停条件：灭屏 / 该前台包按当前名单模式不需要干预。
        // 名单模式与总开关的解释统一在 Config.shouldIntervene 内完成（含 black 空集兜底）。
        // v2.5：总开关（防烧屏保护）是总闸。旧版条件是 (!status && !nav)，
        // 只要导航栏开关开着，总开关关了隐藏动作照跑；enableShift 更是完全不看总开关，
        // 真机日志里 status=false 时 SHIFT 仍连续跑了 25 分钟。现在关掉即全部停止。
        if (!screenOn || !intervene) {
            if (hidden) {
                applyShow();
                hidden = false;
            }
            if (PixelShift.isActive()) {
                PixelShift.shutdown();
                XposedInit.diag("SHIFT 停止：" + pauseReason(screenOn, intervene, top));
            }
            // 暂停期间古诗浮层同样必须移除（applyShow 只恢复系统栏，不清理 Overlay）
            // 2 秒轮询，等恢复条件满足后继续周期
            handler.postDelayed(cycleRunnable, 2000);
            return;
        }

        // 统一周期相位：与抖音/红果（DouyinHook）共用「系统单调时钟对周期取模」判断此刻该隐藏还是显示，
        // 并在同一相位边界切换——状态栏/导航栏/抖音/红果四者完全同步，不再"这个隐藏完了那个才显示"。
        long now = SystemClock.elapsedRealtime();
        long hideMs = Math.max(1, Config.hideSec) * 1000L;
        long showMs = Math.max(1, Config.showSec) * 1000L;
        long pos = now % (hideMs + showMs);
        boolean shouldHide = pos < hideMs;

        if (shouldHide) {
            // 开关本身变了也要重跑一次：hideSec 动辄 30 秒，如果只在 !hidden 时应用，
            // 用户中途关掉「状态栏」要等整个隐藏相位结束才看得到图标回来。
            boolean cfgChanged = lastStatusBarSwitch != Config.enableStatusBar
                    || lastNavSwitch != Config.enableNav;
            if (!hidden || cfgChanged) {
                applyHide();
                hidden = true;
            }
        } else {
            if (hidden) {
                applyShow();
                hidden = false;
            }
        }
        // 像素偏移（与 AOSP BurnInProtectionController 同思路）：把状态栏 item 缓慢平移 1~2px，
        // 视觉几乎无感，但轮廓边缘不再固定压在同一批像素上。与「隐藏」互补 ——
        // 隐藏减少点亮时间，偏移分摊轮廓，两者叠加才接近厂商级防护。
        // 用 FeatureGuard 隔离：偏移逻辑若在某个 ROM 上反复抛异常，只熔断这一项，
        // 不影响隐藏/古诗等其它功能，更不会把 SystemUI 打崩。
        if (Config.enableShift) {
            FeatureGuard.guard("sys.shift", () -> {
                if (!Config.uiStyle.equals(lastShiftStyle)) {
                    lastShiftStyle = Config.uiStyle;
                    PixelShift.setStyle(Config.uiStyle);
                    // v2.7：日志带上归一后的 id 与三家参数，便于真机逐家核对
                    XposedInit.diagForce("SHIFT style=" + PixelShift.styleState());
                }
                PixelShift.bind(statusView, navView);
                PixelShift.tick();
            });
        } else if (PixelShift.isActive()) {
            PixelShift.shutdown();
            XposedInit.diag("SHIFT 已关闭，位移归零");
        }

        // 调度：优先切到下一相位边界保持同步，但最迟 2s 重读一次配置。
        // 旧版直接等到相位边界（最长 hideSec 秒），导致改了开关要等几十秒才生效。
        long remain = shouldHide ? (hideMs - pos) : (hideMs + showMs - pos);
        handler.postDelayed(cycleRunnable, Math.min(2000L, Math.max(100L, remain)));
    }

    /** 上一次应用到 PixelShift 的 UI 方案，变化时才重设并打日志 */
    private static String lastShiftStyle = "";

    /** 上一次 applyHide 时两个系统栏开关的状态，用于「开关变了立刻重画」 */
    private static boolean lastStatusBarSwitch = true;
    private static boolean lastNavSwitch = false;

    private static void applyHide() {
        // 状态栏：用 alpha=0 透明化而非 GONE。
        // 关键：透明不点亮像素（防烧屏效果不变），但视图保留触摸与下拉把手，
        // 第三方 App 里也能正常下拉通知栏/控制中心。
        // v2.7：tick() 在 !enableStatus 时已提前 return + applyShow()，
        // 能走到这里总闸必然是开的。删掉旧版恒真的 if (enableStatus) 冗余判断，
        // 状态栏隐藏不再与总闸语义重复；恢复统一交给暂停分支处理。
        if (statusView != null) {
            if (Config.enableStatusBar) {
                hideStatusKeepNotify();
            } else if (statusView.getAlpha() != 1f || statusChildrenHidden()) {
                // 用户在功能页关掉了「状态栏」，但导航栏仍要按周期隐藏。
                // 旧版这里没有分支：总闸一开必然调用 hideStatusKeepNotify()，
                // 于是「关掉状态栏」这个诉求根本无处表达，真机反馈为
                // 「总开关一开，状态栏和导航栏百分百一起隐藏」。
                restoreStatusChildren();
                Log.i(TAG, "status shown (switch off)");
            }
        }
        if (navView != null) {
            if (Config.enableNav) {
                if (navView.getVisibility() != View.GONE && navView.getAlpha() != 0f) {
                    navView.setAlpha(0f);
                    Log.i(TAG, "nav transparent");
                }
            } else if (navView.getAlpha() != 1f) {
                navView.setAlpha(1f);
                Log.i(TAG, "nav shown (switch off)");
            }
        }
        lastStatusBarSwitch = Config.enableStatusBar;
        lastNavSwitch = Config.enableNav;
    }

    /** 隐藏状态栏静态元素（时钟/信号/电池等），但保留通知图标区，通知照常可见 */
    private static void hideStatusKeepNotify() {
        if (!(statusView instanceof android.view.ViewGroup) || notifyArea == null) {
            // 未识别出通知区：维持整窗透明（原行为）
            if (statusView.getVisibility() != View.GONE && statusView.getAlpha() != 0f) {
                statusView.setAlpha(0f);
                Log.i(TAG, "status transparent (no notify area)");
            }
            return;
        }
        // 父容器恢复为可见，逐子容器透明（通知区除外）
        if (statusView.getAlpha() != 1f) {
            statusView.setAlpha(1f);
        }
        android.view.ViewGroup g = (android.view.ViewGroup) statusView;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == notifyArea) {
                if (c.getAlpha() != 1f) {
                    c.setAlpha(1f);
                    Log.i(TAG, "notify area kept visible");
                }
            } else {
                if (c.getVisibility() != View.GONE && c.getAlpha() != 0f) {
                    c.setAlpha(0f);
                }
            }
        }
    }

    private static void applyShow() {
        restoreStatusChildren();
        if (navView != null && navView.getAlpha() != 1f) {
            navView.setAlpha(1f);
            Log.i(TAG, "nav shown");
        }
    }

    /**
     * 状态栏里是否有子容器仍处于透明态。
     *
     * <p>父容器 alpha 恢复不等于子容器恢复：hideStatusKeepNotify 是逐个 setAlpha(0) 的，
     * 关掉「状态栏」开关时必须同样逐个还原。只看父容器会漏判，
     * 导致关了开关图标还是不见。</p>
     */
    private static boolean statusChildrenHidden() {
        if (!(statusView instanceof android.view.ViewGroup)) {
            return false;
        }
        android.view.ViewGroup g = (android.view.ViewGroup) statusView;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c != notifyArea && c.getAlpha() != 1f) {
                return true;
            }
        }
        return false;
    }

    /** 恢复状态栏父容器与被透明化的子容器（父恢复不等于子恢复，须逐个还原） */
    private static void restoreStatusChildren() {
        if (statusView == null) {
            return;
        }
        if (statusView.getAlpha() != 1f) {
            statusView.setAlpha(1f);
            Log.i(TAG, "status shown");
        }
        if (statusView instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) statusView;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getAlpha() != 1f) {
                    c.setAlpha(1f);
                }
            }
        }
    }

    /**
     * 暂停原因。原来无论灭屏、名单命中还是总开关关闭都打印「总开关关闭」，
     * 真机日志里会出现「status=true 却报总开关关闭」这类自相矛盾的记录，
     * 排障时把人引向错误方向。三种原因必须分开写。
     */
    private static String pauseReason(boolean screenOn, boolean intervene, String top) {
        if (!screenOn) {
            return "灭屏";
        }
        if (!Config.enableStatus) {
            return "总开关关闭";
        }
        if (!intervene) {
            return "当前模式不干预该前台包 mode=" + Config.listMode + " top=" + top;
        }
        return "未知条件";
    }

    private static boolean isScreenOn() {
        try {
            if (statusView == null) {
                return true;
            }
            Context ctx = statusView.getContext();
            Object pm = ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return true;
            }
            // 反射调用 PowerManager.isInteractive()，避免编译期依赖
            return (Boolean) pm.getClass().getMethod("isInteractive").invoke(pm);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 获取前台应用包名（SystemUI 为系统进程，可查全局任务）。
     * 多层兜底：不同 ROM（HyperOS3/4、原生、其他定制）反射路径可能失败，逐级降级，
     * 任一成功即返回，避免黑名单因取不到前台包而失效。
     */
    private static String currentTopPackage() {
        // 候选0：getFocusedRootTaskInfo() → 当前聚焦任务（API31+，Android12+/HyperOS 最可靠）
        try {
            Object atms = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityTaskManager"), "getService");
            Object info = XposedHelpers.callMethod(atms, "getFocusedRootTaskInfo");
            if (info != null) {
                String top = topActivityOf(info);
                if (top != null) {
                    return top;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getFocusedRootTaskInfo failed: " + t);
        }
        // 候选1：ActivityTaskManager.getService().getTasks(1, false) → topActivity（Android 10+ 主流）
        try {
            Object atms = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityTaskManager"), "getService");
            Object tasks = XposedHelpers.callMethod(atms, "getTasks", 1, false);
            if (tasks instanceof List && !((List<?>) tasks).isEmpty()) {
                Object task = ((List<?>) tasks).get(0);
                String top = topActivityOf(task);
                if (top != null) {
                    return top;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ATMS.getTasks(1,false) failed: " + t);
        }
        // 候选2：getTasks(1, true)（withResolvedActivity 为 true 时字段更完整）
        try {
            Object atms = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityTaskManager"), "getService");
            Object tasks = XposedHelpers.callMethod(atms, "getTasks", 1, true);
            if (tasks instanceof List && !((List<?>) tasks).isEmpty()) {
                Object task = ((List<?>) tasks).get(0);
                String top = topActivityOf(task);
                if (top != null) {
                    return top;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ATMS.getTasks(1,true) failed: " + t);
        }
        // 候选3：ActivityManager.getRunningTasks(1)（老 API，部分 ROM 仍可用）
        try {
            Object am = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityManager"), "getService");
            Object tasks = XposedHelpers.callMethod(am, "getRunningTasks", 1);
            if (tasks instanceof List && !((List<?>) tasks).isEmpty()) {
                Object task = ((List<?>) tasks).get(0);
                String top = topActivityOf(task);
                if (top != null) {
                    return top;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "AM.getRunningTasks failed: " + t);
        }
        return "";
    }

    /**
     * 从 RunningTaskInfo / RootTaskInfo 取前台 Activity 包名。
     * 优先 topActivity 字段；再反射扫描字段，但按语义分优先级：
     *   - 字段名含 activity/top 的 ComponentName/Intent 优先（当前页）；
     *   - baseIntent/root 只作最后兜底（它是任务入口，不是当前页，误取会导致白名单永远不中）；
     * 最后才退 baseIntent。
     */
    private static String topActivityOf(Object task) {
        if (task == null) {
            return null;
        }
        // 快速路径：topActivity（AOSP 标准字段）
        String fast = readComponentPkg(task, "topActivity");
        if (fast != null) {
            return fast;
        }
        // 反射扫描全部字段：优先"当前活动"相关字段，baseIntent/root 作兜底
        String genericPkg = null;
        String baseIntentPkg = null;
        try {
            for (java.lang.reflect.Field f : task.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val;
                try {
                    val = f.get(task);
                } catch (Throwable ignored) {
                    continue;
                }
                String n = f.getName().toLowerCase();
                String p;
                if (val instanceof ComponentName) {
                    p = pkgOf((ComponentName) val);
                } else if (val instanceof Intent) {
                    p = pkgOf((Intent) val);
                } else {
                    continue;
                }
                if (p == null) {
                    continue;
                }
                if (n.contains("baseintent") || n.contains("root")) {
                    baseIntentPkg = p;
                } else if (n.contains("activity") || n.contains("top")) {
                    return p; // 明确是当前活动字段，直接返回
                } else {
                    genericPkg = p; // 未分类字段暂存，最后兜底
                }
            }
        } catch (Throwable ignored) {
        }
        if (genericPkg != null) {
            return genericPkg;
        }
        // 最后兜底：baseIntent（仅当上面全失败）
        String base = readComponentPkg(task, "baseIntent");
        if (base == null) {
            base = baseIntentPkg;
        }
        return base;
    }

    private static String readComponentPkg(Object task, String field) {
        try {
            ComponentName c = (ComponentName) XposedHelpers.getObjectField(task, field);
            return pkgOf(c);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String pkgOf(ComponentName c) {
        if (c == null || c.getPackageName() == null || c.getPackageName().isEmpty()) {
            return null;
        }
        return c.getPackageName();
    }

    private static String pkgOf(Intent in) {
        if (in == null || in.getComponent() == null) {
            return null;
        }
        String p = in.getComponent().getPackageName();
        return (p == null || p.isEmpty()) ? null : p;
    }
}
