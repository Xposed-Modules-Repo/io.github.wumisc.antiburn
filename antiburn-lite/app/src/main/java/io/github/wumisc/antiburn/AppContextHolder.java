package io.github.wumisc.antiburn;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Field;

/**
 * 当前进程的 Application 容器（API 102 实验分支专用）。
 *
 * <p>legacy Xposed 有 {@code AndroidAppHelper.currentApplication()}，它由框架在
 * 自己的 ClassLoader 里提供，不受 Android 11+ 隐藏 API 限制；libxposed 没有等价物，
 * 而在抖音 / 快手这类 untrusted_app 进程里直接反射
 * {@code ActivityThread.currentApplication()} 会被挡掉 —— 那意味着注入进程的日志
 * 一条都发不出去（3.2.22 之前就是这个症状）。</p>
 *
 * <p>所以改成「自己抓」：模块在 {@code onPackageLoaded}（Application 还没被实例化）
 * 时挂 {@code Application.<init>} —— 构造器不走虚分派，任何 Application 子类
 * （SystemUIApplication、抖音的 Application…）都一定会经过它，after 回调里拿到的
 * {@code thisObject} 就是这个进程唯一的 Application，且全程不碰隐藏 API。</p>
 *
 * <p>兜底顺序：构造器抓到的实例（且已 attach）→ 静态 {@code currentApplication()} →
 * {@code currentActivityThread().getApplication()} → {@code mInitialApplication} 字段。
 * 四条都失败才返回 null，此时日志会按 {@link XposedInit} 既有的链路退到 su 写盘。</p>
 */
public final class AppContextHolder {

    private static volatile Application captured;

    private AppContextHolder() {
    }

    /** 供热重载跨代传递：旧一代抓到的 Application 是宿主类加载器对象，可以安全交给新代码。 */
    static Application peek() {
        return captured;
    }

    /** 构造器 Hook 回调调用；只认第一个实例。 */
    static void capture(Object app) {
        if (captured == null && app instanceof Application) {
            captured = (Application) app;
        }
    }

    /** 从不抛异常；拿不到就是 null，调用方各自降级。 */
    public static Context currentApplication() {
        Application app = captured;
        if (usable(app)) {
            return app;
        }
        // Application 还没 attach 时 base context 为 null，此时下面的反射兜底更靠谱
        Object byStatic = invokeStatic("android.app.ActivityThread", "currentApplication");
        if (byStatic instanceof Context) {
            return (Context) byStatic;
        }
        Object thread = invokeStatic("android.app.ActivityThread", "currentActivityThread");
        Context byGetter = invokeInstance(thread, "getApplication");
        if (byGetter != null) {
            return byGetter;
        }
        Context byField = readInitialApplication(thread);
        if (byField != null) {
            return byField;
        }
        return usable(app) ? app : null;
    }

    private static boolean usable(Application app) {
        if (app == null) {
            return false;
        }
        try {
            return app.getBaseContext() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object invokeStatic(String cls, String method) {
        try {
            return Class.forName(cls).getMethod(method).invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context invokeInstance(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Object v = target.getClass().getMethod(method).invoke(target);
            return v instanceof Context ? (Context) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context readInitialApplication(Object thread) {
        if (thread == null) {
            return null;
        }
        try {
            Field f = thread.getClass().getDeclaredField("mInitialApplication");
            f.setAccessible(true);
            Object v = f.get(thread);
            return v instanceof Context ? (Context) v : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
