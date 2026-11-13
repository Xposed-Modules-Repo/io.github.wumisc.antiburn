package io.github.wumisc.antiburn;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

    /**
     * v2.7.4：本模块亲手改成透明的子视图。
     *
     * <p>旧版恢复时把父容器的所有子视图一律 setAlpha(1)，会把 ROM 自己设成透明的
     * 装饰（如 Flyme 的挖孔黑边、HyperOS 的瞬时隐藏层）也一并点亮，看起来像
     * 「状态栏闪一下」。改成只还原我们改过的那批，别人的 alpha 不碰。</p>
     */
    private static final List<View> dimmed = new ArrayList<>();

    /** 窗口视图被 SystemUI 重建后，重新抓取的节流时间戳 */
    private static long lastRescanAt = 0;

    /** 魅族 Flyme：状态栏布局与 AOSP 差异最大的一家，容器 id 优先级单独一套 */
    private static final boolean FLYME_ROM = detectFlyme();

    private Controller() {
    }

    static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        XposedInit.diag("SYSINIT sdk=" + android.os.Build.VERSION.SDK_INT
                + " release=" + android.os.Build.VERSION.RELEASE
                + " brand=" + android.os.Build.BRAND + " device=" + android.os.Build.DEVICE
                + " flyme=" + FLYME_ROM
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

        // ---- 状态栏本体（Flyme 专用补充）----
        // Flyme 10/11 的状态栏布局仍源自 AOSP，但窗口类不一定叫 StatusBarWindowView；
        // PhoneStatusBarView 才是它真正的状态栏容器（魅族在它内部画时钟 mz_clock、
        // 环形电池与挖孔黑边）。抓到它以后整条栏的子视图才能逐个透明，
        // 而不是退化成「整窗透明、连下拉面板一起没了」。
        if (FLYME_ROM) {
            Class<?> psbCls = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.phone.PhoneStatusBarView", cl);
            if (psbCls != null) {
                try {
                    XposedHelpers.findAndHookMethod(psbCls, "onAttachedToWindow",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        View v = (View) param.thisObject;
                                        // 用「整条栏」替换掉更外层的窗口根；若当前抓到的
                                        // 已经是栏内更精确的容器（contents），则保留它。
                                        if (statusView == null || isDescendant(v, statusView)) {
                                            statusView = v;
                                            findNotifyArea(v);
                                            Log.i(TAG, "flyme status bar: " + desc(v));
                                            startCycle();
                                        }
                                    } catch (Throwable t) {
                                        hookFail("PhoneStatusBarView.attach", t);
                                    }
                                }
                            });
                } catch (Throwable t) {
                    Log.w(TAG, "hook PhoneStatusBarView failed", t);
                }
            } else {
                Log.w(TAG, "PhoneStatusBarView not found (flyme)");
            }
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

        // 保险：5 秒后若状态栏 / 导航栏还没抓全，主动枚举窗口根再试一次
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (statusView == null || navView == null) {
                    Log.w(TAG, "views missing after 5s, rescan: status=" + (statusView != null)
                            + " nav=" + (navView != null));
                    XposedInit.diagForce("SYS RESCAN 5s status=" + (statusView != null)
                            + " nav=" + (navView != null));
                    lastRescanAt = 0;
                    checkViewsAlive();
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
            // v2.7.4：Flyme 优先整条 PhoneStatusBarView（id=status_bar）。
            // 魅族把时钟（mz_clock）、环形电池放在 status_bar_contents 之外，
            // 只抓 contents 会剩下半条栏继续烧屏；抓整条再按「通知区路径」保留通知，
            // 既不误伤通知，又能把时钟 / 电量 / 信号一起透明掉。
            // 不再把 notification_icon_area 当状态栏候选：它是通知图标区本身，
            // 选中它等于「只隐藏通知、留下时钟」，与设计意图正好相反。
            String[] iconIds = FLYME_ROM
                    ? new String[]{"status_bar", "mz_status_bar", "status_bar_contents",
                    "status_bar_icons"}
                    : new String[]{"status_bar_contents", "status_bar_icons", "status_bar",
                    "mz_status_bar"};
            for (String id : iconIds) {
                int rid = idOf(root, id);
                Log.i(TAG, "probe id " + id + " -> " + rid);
                if (rid != 0) {
                    View v = root.findViewById(rid);
                    if (v != null) {
                        Log.i(TAG, "status container " + id + ": " + desc(v)
                                + " children=" + vChildNames(v));
                        return v;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "find by id error", t);
        }
        return null;
    }

    /**
     * 按 id 名解析资源，兼容自定义 ROM 换包名。
     *
     * <p>旧版只查 {@code com.android.systemui}。多数 ROM 没问题，但主题 / 换肤后的
     * SystemUI 可能把资源代理到自己的包，Flyme 的部分组件（com.flyme.systemuitools）
     * 也是独立包。多试几个包名比「找不到就退化成整窗透明」稳妥。</p>
     */
    private static int idOf(View root, String name) {
        Resources r = root.getResources();
        String own = null;
        try {
            Context c = root.getContext();
            own = c == null ? null : c.getPackageName();
        } catch (Throwable ignored) {
        }
        String[] pkgs = {"com.android.systemui", own, "com.meizu.flyme.systemui"};
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            try {
                int rid = r.getIdentifier(name, "id", p);
                if (rid != 0) {
                    return rid;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
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
            int rid = idOf(root, "navigation_bar");
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
            int rid = idOf(v, "navigation_bar");
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
        if (root == null) {
            return;
        }
        // 优先按 id 找：AOSP 的 notification_icon_area 常常嵌在 left_side 里，
        // 只扫直接子节点永远扫不到，于是「保留通知」实际变成了「整条栏全隐藏」。
        for (String id : new String[]{"notification_icon_area", "notificationIcons",
                "status_bar_notification_icons"}) {
            int rid = idOf(root, id);
            if (rid != 0) {
                View v = root.findViewById(rid);
                if (v != null) {
                    notifyArea = v;
                    Log.i(TAG, "notify area by id " + id + ": " + desc(v));
                    return;
                }
            }
        }
        // 次选：按类名递归找（限制深度，避免把下拉面板里的通知行当成图标区）
        notifyArea = findNotificationNode(root, 0);
        if (notifyArea != null) {
            Log.i(TAG, "notify area by class: " + desc(notifyArea));
        }
    }

    private static View findNotificationNode(View v, int depth) {
        if (depth > 3 || !(v instanceof android.view.ViewGroup)) {
            return null;
        }
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            String n = c.getClass().getName();
            if (n.contains("Notification") || n.contains("notification")) {
                return c;
            }
            View deep = findNotificationNode(c, depth + 1);
            if (deep != null) {
                return deep;
            }
        }
        return null;
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
        scheduleCycle(1000);
    }

    /** v2.7.3：本轮是否已排下一次，供 finally 判断要不要兜底重排。 */
    private static volatile boolean cycleScheduled = false;

    /** 唯一周期调度入口，避免多路径重复排队。 */
    private static void scheduleCycle(long delayMs) {
        cycleScheduled = true;
        handler.postDelayed(cycleRunnable, delayMs);
    }

    private static final Runnable cycleRunnable = new Runnable() {
        @Override
        public void run() {
            cycleScheduled = false;
            try {
                tick();
            } catch (Throwable t) {
                Log.e(TAG, "tick error", t);
                XposedInit.diag("SYS TICK ERROR " + t);
            } finally {
                // v2.7.3 核心修复：SystemUI 侧同样不能让一次异常停掉整个周期，
                // 否则状态栏 / 导航栏的隐藏与像素微移会永久静止。
                if (!cycleScheduled && handler != null) {
                    scheduleCycle(2000);
                }
            }
        }
    };

    /**
     * v2.7.4：视图存活检查 + 失效重抓。
     *
     * <p>SystemUI 在切换深色模式、改分辨率 / 字体、Flyme 换主题、折叠屏展开等场景
     * 会整棵重建状态栏窗口，旧引用变成一个已经 detach 的 View：alpha 改得再勤，
     * 屏幕上也毫无变化，用户看到的是「设置还开着，但完全没有效果」。
     * 旧版从 startCycle 之后再也不更新引用，只能重启 SystemUI 才能恢复。
     * 这里每轮校验一次 attach 状态，掉了就丢掉旧引用并主动重扫。</p>
     */
    private static void checkViewsAlive() {
        if (statusView != null && !statusView.isAttachedToWindow()) {
            XposedInit.diag("SYS STATUS VIEW DETACHED, re-acquire");
            statusView = null;
            notifyArea = null;
            dimmed.clear();
            // 新窗口是可见的，旧 hidden=true 会让下一轮跳过 applyHide，
            // 表现为「重抓到了但一直不隐藏」。复位后按当前相位重新施加。
            hidden = false;
        }
        if (navView != null && !navView.isAttachedToWindow()) {
            XposedInit.diag("SYS NAV VIEW DETACHED, re-acquire");
            navView = null;
            hidden = false;
        }
        if (statusView != null && navView != null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRescanAt < 3000) {
            return;
        }
        lastRescanAt = now;
        rescanWindows();
    }

    /**
     * 从 SystemUI 自己的 WindowManagerGlobal 里找窗口根。
     *
     * <p>为什么需要这层：View.onAttachedToWindow 的兜底扫描只能抓到「钩子装好之后」
     * 才 attach 的视图。LSPosed 在某些 ROM 上注入较晚（Flyme 的系统界面启动更早），
     * 状态栏早已 attach 完毕，扫描永远等不到第二次，最终退化成「整窗透明」或完全无效。
     * 直接枚举当前进程的窗口根，不依赖任何厂商类名，也不依赖 attach 时序。</p>
     */
    private static void rescanWindows() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            Object raw = f.get(inst);
            if (!(raw instanceof List)) {
                return;
            }
            List<?> views = new ArrayList<>((List<?>) raw);
            for (Object o : views) {
                if (!(o instanceof View)) {
                    continue;
                }
                View root = (View) o;
                String n = root.getClass().getName();
                if (statusView == null && n.contains("StatusBar")) {
                    View bar = findStatusBarView(root);
                    statusView = bar != null ? bar : root;
                    findNotifyArea(statusView);
                    XposedInit.diagForce("SYS rescan status=" + desc(statusView));
                    startCycle();
                } else if (navView == null && n.contains("NavigationBar")) {
                    navView = root;
                    XposedInit.diagForce("SYS rescan nav=" + desc(root));
                    startCycle();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "rescan windows failed: " + t);
        }
    }

    /** child 是否在 ancestor 这棵子树里（含自身）。 */
    private static boolean isDescendant(View child, View ancestor) {
        View cur = child;
        while (cur != null) {
            if (cur == ancestor) {
                return true;
            }
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** 魅族 Flyme 判定：厂商 / 品牌 / 版本串任一命中即可。 */
    private static boolean detectFlyme() {
        try {
            String s = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND + " "
                    + android.os.Build.DISPLAY + " " + android.os.Build.PRODUCT)
                    .toLowerCase(Locale.ROOT);
            return s.contains("meizu") || s.contains("flyme");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void tick() {
        checkViewsAlive();
        // 拿到宿主 Context 才能经 ConfigProvider 跨进程读配置（SELinux 挡住直接读 /data/local/tmp）
        View ctxView = statusView != null ? statusView : navView;
        if (ctxView != null) {
            Config.attach(ctxView.getContext());
        }
        Config.reload();

        boolean screenOn = isScreenOn();
        String top = currentTopPackage();
        // v2.7.2：抖音 / 红果 直播间暂停信号。由 App 进程在识别到直播页时写文件
        // （带包名 + 15 秒有效期），这里只读不写，包名不匹配或过期即视为无信号。
        // v2.7.3：改为读内存缓存（后台线程刷新）。原实现每轮在主线程同步读三个路径的文件，
        // 任一 IO 阻塞都会拖慢甚至打断 tick —— 与「退出直播后彻底不工作」是同一类风险。
        boolean livePause = LivePause.readCached(top);
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
                    + " lp=" + livePause
                    + " ver=" + BuildConfig.VERSION_NAME);
        }

        // 暂停条件：灭屏 / 该前台包按当前名单模式不需要干预。
        // 名单模式与总开关的解释统一在 Config.shouldIntervene 内完成（含 black 空集兜底）。
        // v2.5：总开关（防烧屏保护）是总闸。旧版条件是 (!status && !nav)，
        // 只要导航栏开关开着，总开关关了隐藏动作照跑；enableShift 更是完全不看总开关，
        // 真机日志里 status=false 时 SHIFT 仍连续跑了 25 分钟。现在关掉即全部停止。
        // v2.7.2：直播间暂停与灭屏 / 名单豁免同级 —— 命中即恢复系统栏并停掉像素偏移。
        if (!screenOn || !intervene || livePause) {
            if (hidden) {
                applyShow();
                hidden = false;
            }
            if (PixelShift.isActive()) {
                PixelShift.shutdown();
                // v2.7.2：直播暂停时 pauseReason 会返回「直播中」，便于日志直接区分原因
                XposedInit.diag("SHIFT 停止：" + pauseReason(screenOn, intervene, top, livePause));
            }
            // 暂停期间古诗浮层同样必须移除（applyShow 只恢复系统栏，不清理 Overlay）
            // 2 秒轮询，等恢复条件满足后继续周期
            scheduleCycle(2000);
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
        scheduleCycle(Math.min(2000L, Math.max(100L, remain)));
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
        View sv = statusView;
        if (sv == null) {
            return;
        }
        if (!(sv instanceof android.view.ViewGroup) || notifyArea == null) {
            // 未识别出通知区：维持整窗透明（原行为）
            if (sv.getVisibility() != View.GONE && sv.getAlpha() != 0f) {
                sv.setAlpha(0f);
                Log.i(TAG, "status transparent (no notify area)");
            }
            return;
        }
        // 父容器恢复为可见，再沿「通知区路径」逐层只隐藏兄弟节点
        if (sv.getAlpha() != 1f) {
            sv.setAlpha(1f);
        }
        List<View> path = pathTo(sv, notifyArea);
        if (path == null) {
            // 通知区已经不在当前视图树里（SystemUI 重建过窗口 / 换了主题）。
            // 旧版此时会把整条栏全隐藏，用户表现为「通知图标也没了」。
            // 先就地重找一次，还找不到才退化成整条透明，下一轮 checkViewsAlive 再兜底。
            Log.w(TAG, "notify area detached, re-resolve");
            findNotifyArea(sv);
            path = (notifyArea != null) ? pathTo(sv, notifyArea) : null;
        }
        if (path == null || path.size() < 2) {
            notifyArea = null;
            if (sv.getAlpha() != 0f) {
                sv.setAlpha(0f);
                Log.i(TAG, "status transparent (notify path missing)");
            }
            return;
        }
        dimAlong(path, 0);
        setAlphaTracked(notifyArea, 1f, false);
    }

    /** 从 root 到 target 的完整路径（root → … → target）；target 不在 root 下返回 null。 */
    private static List<View> pathTo(View root, View target) {
        List<View> path = new ArrayList<>();
        View cur = target;
        while (cur != null) {
            path.add(0, cur);
            if (cur == root) {
                return path;
            }
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof View)) {
                return null;
            }
            cur = (View) p;
        }
        return null;
    }

    /**
     * 沿路径隐藏：路径上的容器保持 alpha=1，其余兄弟节点透明。
     *
     * <p>只「保留通知区的直接父容器」会把同容器里的时钟一起留下（Flyme 的
     * mz_clock 就在左侧容器里）；只隐藏直接子节点又会让通知跟着消失。
     * 正确做法是沿路径逐层剥：每一层保留通往通知区的那一支，其余全部透明。</p>
     */
    private static void dimAlong(List<View> path, int idx) {
        View parent = path.get(idx);
        if (!(parent instanceof android.view.ViewGroup)) {
            return;
        }
        View keep = (idx + 1 < path.size()) ? path.get(idx + 1) : null;
        android.view.ViewGroup g = (android.view.ViewGroup) parent;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() == View.GONE) {
                continue;
            }
            if (c == keep) {
                setAlphaTracked(c, 1f, false);
                // 只在「中间容器」上继续下钻；通知区本身要整棵保留，
                // 再往下走会把通知图标自己 setAlpha(0)，等于没保住通知。
                if (c != notifyArea) {
                    dimAlong(path, idx + 1);
                }
            } else {
                setAlphaTracked(c, 0f, true);
            }
        }
    }

    /** 改 alpha 并记账：只有我们改过的视图才会在恢复时被点亮。 */
    private static void setAlphaTracked(View v, float alpha, boolean track) {
        if (v.getAlpha() != alpha) {
            v.setAlpha(alpha);
        }
        if (track && !dimmed.contains(v)) {
            dimmed.add(v);
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
     * <p>父容器 alpha 恢复不等于子容器恢复：隐藏是逐个 setAlpha(0) 的，
     * 关掉「状态栏」开关时必须同样逐个还原。只看父容器会漏判，
     * 导致关了开关图标还是不见。</p>
     */
    private static boolean statusChildrenHidden() {
        return !dimmed.isEmpty() || (statusView != null && statusView.getAlpha() != 1f);
    }

    /**
     * 恢复状态栏父容器与「被我们透明化」的子容器。
     *
     * <p>v2.7.4：不再无差别把所有子视图 alpha 设回 1。ROM 自己管理的装饰
     * （Flyme 挖孔黑边、HyperOS 的瞬时隐藏层）本来就不是我们改的，
     * 一律点亮会让状态栏在恢复瞬间闪一下。</p>
     */
    private static void restoreStatusChildren() {
        for (int i = 0; i < dimmed.size(); i++) {
            View v = dimmed.get(i);
            try {
                if (v != null && v.getAlpha() != 1f) {
                    v.setAlpha(1f);
                }
            } catch (Throwable ignored) {
            }
        }
        dimmed.clear();
        if (statusView != null && statusView.getAlpha() != 1f) {
            statusView.setAlpha(1f);
            Log.i(TAG, "status shown");
        }
    }

    /**
     * 暂停原因。原来无论灭屏、名单命中还是总开关关闭都打印「总开关关闭」，
     * 真机日志里会出现「status=true 却报总开关关闭」这类自相矛盾的记录，
     * 排障时把人引向错误方向。三种原因必须分开写。
     */
    private static String pauseReason(boolean screenOn, boolean intervene, String top,
                                      boolean livePause) {
        if (!screenOn) {
            return "灭屏";
        }
        if (!Config.enableStatus) {
            return "总开关关闭";
        }
        if (livePause) {
            return "直播中暂停 top=" + top;
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
