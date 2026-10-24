package io.github.wumisc.antiburn;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.util.List;

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

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static boolean hidden = false;
    private static boolean started = false;

    private Controller() {
    }

    static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;

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

        // 兜底3：全量扫描——任何 View attach 时按类名/id 识别导航栏，
        // 无论澎湃把类改成什么名字都能抓到
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (navView != null) {
                                return;
                            }
                            View v = (View) param.thisObject;
                            if (isNavView(v)) {
                                navView = v;
                                Log.i(TAG, "nav view caught (View scan): " + desc(v));
                                startCycle();
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
            handler.postDelayed(cycleRunnable, Math.max(1, Config.showSec) * 1000L);
        } else {
            applyHide();
            hidden = true;
            handler.postDelayed(cycleRunnable, Math.max(1, Config.hideSec) * 1000L);
        }
    }

    private static void applyHide() {
        // 状态栏：用 alpha=0 透明化而非 GONE。
        // 关键：透明不点亮像素（防烧屏效果不变），但视图保留触摸与下拉把手，
        // 第三方 App 里也能正常下拉通知栏/控制中心。
        if (Config.enableStatus && statusView != null) {
            if (statusView.getVisibility() != View.GONE && statusView.getAlpha() != 0f) {
                statusView.setAlpha(0f);
                Log.i(TAG, "status transparent (下拉保留)");
            }
        }
        if (Config.enableNav && navView != null) {
            if (navView.getVisibility() != View.GONE && navView.getAlpha() != 0f) {
                navView.setAlpha(0f);
                Log.i(TAG, "nav transparent");
            }
        }
    }

    private static void applyShow() {
        if (statusView != null && statusView.getAlpha() != 1f) {
            statusView.setAlpha(1f);
            Log.i(TAG, "status shown");
        }
        if (navView != null && navView.getAlpha() != 1f) {
            navView.setAlpha(1f);
            Log.i(TAG, "nav shown");
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
