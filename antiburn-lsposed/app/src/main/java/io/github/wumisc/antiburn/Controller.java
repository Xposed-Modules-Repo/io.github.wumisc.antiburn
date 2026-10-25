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
 *  - 灭屏或前台 App 命中白名单时暂停切换（防误伤）
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
                                navView = (View) param.thisObject;
                                Log.i(TAG, "nav view found (NavigationBarView): " + desc(navView));
                                startCycle();
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
                                if (navView != null) {
                                    return;
                                }
                                View frame = (View) param.thisObject;
                                View nb = findNavById(frame);
                                navView = nb != null ? nb : frame;
                                Log.i(TAG, "nav view found (NavigationBarFrame): " + desc(navView));
                                startCycle();
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

    private static void startCycle() {
        if (started) {
            return;
        }
        started = true;
        Log.i(TAG, "cycle started");
        handler.removeCallbacks(cycleRunnable);
        handler.postDelayed(cycleRunnable, 3000);
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
        Config.reload();

        boolean screenOn = isScreenOn();
        String top = currentTopPackage();
        boolean inWhitelist = Config.whitelist.contains(top);

        // 暂停条件：灭屏 / 前台命中白名单 / 全部功能关闭
        if (!screenOn || inWhitelist || (!Config.enableStatus && !Config.enableNav)) {
            if (hidden) {
                applyShow();
                hidden = false;
            }
            // 2 秒轮询，等恢复条件满足后继续周期
            handler.postDelayed(cycleRunnable, 2000);
            return;
        }

        if (hidden) {
            applyShow();
            hidden = false;
            updatePoem();
            handler.postDelayed(cycleRunnable, Math.max(1, Config.showSec) * 1000L);
        } else {
            applyHide();
            hidden = true;
            updatePoem();
            handler.postDelayed(cycleRunnable, Math.max(1, Config.hideSec) * 1000L);
        }
    }

    // ---------- 隐藏期间滚动励志古诗 ----------

    private static View poemScroll; // 隐藏窗口时状态栏位置的滚动古诗 Overlay

    private static void updatePoem() {
        if (hidden && Config.enablePoem) {
            addPoemScroll();
        } else {
            removePoemScroll();
        }
    }

    private static void addPoemScroll() {
        if (poemScroll != null) {
            return;
        }
        try {
            Context ctx = statusView.getContext();
            // 异步确保缓存为最新一批（每 3 天联网换一批）；同步读当前 5 首兜底显示
            PoemProvider.ensureCacheAsync();
            PoemScrollView v = new PoemScrollView(ctx, PoemProvider.loadCached());
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            // 精确贴合状态栏：取状态栏在屏幕上的真实坐标与尺寸
            int[] loc = new int[2];
            statusView.getLocationOnScreen(loc);
            int w = statusView.getWidth() > 0 ? statusView.getWidth()
                    : ctx.getResources().getDisplayMetrics().widthPixels;
            int h = statusView.getHeight() > 0 ? statusView.getHeight() : dp(ctx, 56);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = loc[0];
            // 弹幕：一行横向滚动，显示在状态栏区域内（隐藏期替代状态栏内容），不遮挡 App 界面
            lp.y = loc[1];
            wm.addView(v, lp);
            poemScroll = v;
            Log.i(TAG, "poem scroll on statusbar x=" + loc[0] + " y=" + loc[1]
                    + " w=" + w + " h=" + h);
        } catch (Throwable t) {
            Log.w(TAG, "add poem failed", t);
            poemScroll = null;
        }
    }

    /** 从 SystemUI 根视图取挖孔（灵动岛）中心 Y；拿不到返回 -1 走状态栏位置 */
    private static int findCutoutCenterY() {
        try {
            View root = statusView.getRootView();
            if (root == null) {
                return -1;
            }
            android.view.WindowInsets insets = root.getRootWindowInsets();
            if (insets == null) {
                return -1;
            }
            android.view.DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout == null) {
                return -1;
            }
            java.util.List<android.graphics.Rect> rects = cutout.getBoundingRects();
            if (rects == null || rects.isEmpty()) {
                return -1;
            }
            int sumY = 0;
            for (android.graphics.Rect r : rects) {
                sumY += r.centerY();
            }
            return sumY / rects.size();
        } catch (Throwable t) {
            Log.w(TAG, "cutout lookup failed", t);
            return -1;
        }
    }

    private static void removePoemScroll() {
        if (poemScroll == null) {
            return;
        }
        try {
            WindowManager wm = (WindowManager) poemScroll.getContext()
                    .getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(poemScroll);
        } catch (Throwable ignored) {
        }
        poemScroll = null;
    }

    private static int dp(Context ctx, float d) {
        return (int) (d * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 状态栏隐藏时轮播励志古诗：一次显示一首，自动折行最多 3 行，
     *  随机顺序播放（播完一轮重新洗牌），背景反色自适应（浅背景→深字，深背景→浅字）。 */
    static class PoemScrollView extends View {
        private static final long FRAME_MS = 40;    // 25fps（与弹幕版滚动一致，流畅省电）
        private static final float SPEED_PX = 75f;  // 每秒滚动像素

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler h = new Handler(Looper.getMainLooper());
        private final java.util.List<String> playList = new ArrayList<>();
        private int idx = 0;
        private String text = "";
        private float textW = 0f;
        private float scrollX = 0f;   // 已累计滚动距离 px
        private int fgColor = 0xFFFFFFFF;
        private long lastColor = 0;
        private long lastTick = 0;

        private final Runnable tickR = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                if (now - lastColor > 5000) {
                    lastColor = now;
                    updateColor();
                }
                // 弹幕推进：从右向左滚动（dt 限幅，防止 SystemUI 卡顿恢复后跳变）
                float dt = Math.min((now - lastTick) / 1000f, 0.25f);
                lastTick = now;
                scrollX += SPEED_PX * dt;
                if (textW > 0 && scrollX > getWidth() + textW + dp(getContext(), 16)) {
                    nextPoem();
                }
                invalidate();
                h.postDelayed(this, FRAME_MS);
            }
        };

        PoemScrollView(Context c, java.util.List<String> list) {
            super(c);
            playList.addAll(list == null || list.isEmpty()
                    ? java.util.Collections.singletonList("") : list);
            textPaint.setTextSize(dp(c, 14));
            textPaint.setFakeBoldText(true);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            lastColor = 0;
            Collections.shuffle(playList, new Random());
            idx = 0;
            updateColor();
            showPoem();
            h.postDelayed(tickR, FRAME_MS);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            h.removeCallbacks(tickR);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (text.isEmpty()) {
                return;
            }
            textPaint.setColor(fgColor);
            float cy = (getHeight() - (textPaint.ascent() + textPaint.descent())) / 2f;
            canvas.drawText(text, dp(getContext(), 12) - scrollX, cy, textPaint);
        }

        private void nextPoem() {
            idx = (idx + 1) % playList.size();
            if (idx == 0) {
                // 一轮播完重新洗牌，保证下一轮顺序随机且不连续重复
                Collections.shuffle(playList, new Random());
            }
            showPoem();
        }

        /** 载入下一首：弹幕从右端重新开始 */
        private void showPoem() {
            String t = playList.get(idx);
            if (t == null) {
                t = "";
            }
            text = t.trim();
            textW = textPaint.measureText(text);
            scrollX = 0f;
            lastTick = System.currentTimeMillis();
            invalidate();
        }

        /** 采样状态栏背景亮度：浅背景→深字，深背景→浅字 */
        private void updateColor() {
            int bg = sampleStatusBg();
            boolean light = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000 >= 128;
            fgColor = light ? 0xFF111111 : 0xFFFFFFFF;
        }

        private int sampleStatusBg() {
            try {
                android.graphics.drawable.Drawable d = statusView.getBackground();
                if (d instanceof android.graphics.drawable.ColorDrawable) {
                    return ((android.graphics.drawable.ColorDrawable) d).getColor();
                }
            } catch (Throwable ignored) {
            }
            return 0xFF1A1A1A; // 拿不到按深色背景，显示浅色文字
        }
    }

    private static void applyHide() {
        // 状态栏：用 alpha=0 透明化而非 GONE。
        // 关键：透明不点亮像素（防烧屏效果不变），但视图保留触摸与下拉把手，
        // 第三方 App 里也能正常下拉通知栏/控制中心。
        // 开关关闭时立即恢复（不等到显示窗口，否则用户关了开关还一直隐藏）
        if (statusView != null) {
            if (Config.enableStatus) {
                hideStatusKeepNotify();
            } else if (statusView.getAlpha() != 1f) {
                // 开关关闭：父与子容器全部恢复（子容器透明不随父恢复，须逐个还原）
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

    /** 获取前台应用包名（SystemUI 为系统进程，可查全局任务） */
    private static String currentTopPackage() {
        try {
            Object atms = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityTaskManager"), "getService");
            Object tasks = XposedHelpers.callMethod(atms, "getTasks", 1, false);
            if (tasks instanceof List) {
                List<?> list = (List<?>) tasks;
                if (!list.isEmpty()) {
                    Object task = list.get(0);
                    // 优先取 topActivity：当前最顶部的 Activity 所在包，才是真正的"前台 App"。
                    // 旧实现取 baseIntent（task 根 Activity）在部分机型上拿不到前台包，白名单会失效。
                    try {
                        ComponentName top = (ComponentName) XposedHelpers.getObjectField(task, "topActivity");
                        if (top != null && top.getPackageName() != null) {
                            return top.getPackageName();
                        }
                    } catch (Throwable ignored) {
                    }
                    Intent base = (Intent) XposedHelpers.getObjectField(task, "baseIntent");
                    if (base != null && base.getComponent() != null) {
                        return base.getComponent().getPackageName();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getTopPackage error: " + t);
        }
        return "";
    }
}
