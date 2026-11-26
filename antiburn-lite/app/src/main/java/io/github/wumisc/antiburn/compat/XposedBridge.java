package io.github.wumisc.antiburn.compat;

import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 实验分支（LSPosed API 102 / libxposed）兼容壳 —— legacy XposedBridge 的替身。
 *
 * <p>运行流程：{@code LibXposedEntry.onPackageLoaded} 先把 {@link XposedModule} 绑进来，
 * 之后 legacy 风格的代码调 {@code XposedBridge.hookAllMethods(...)} / {@code XposedHelpers}，
 * 全部转成 libxposed 的 {@code module.hook(executable).intercept(hooker)}。</p>
 *
 * <p>钩子执行的顺序按 legacy 语义：before → 原方法 → after。如果 before 里
 * {@code setResult}，就不再执行原方法；after 里 {@code setResult} 覆盖返回值。</p>
 */
public final class XposedBridge {

    private static final String TAG = "AntiburnApi102";

    /** 不要写成 public static final 常量给外部读 —— legacy 的用法都是直接调方法。 */
    public static volatile int API_VERSION = 102;

    private static volatile XposedModule module;

    /**
     * 本代创建的全部 HookHandle。
     *
     * <p>框架的 onHotReloaded 会把旧句柄列表给我们，但「模块自己发起的就地换代码」
     * （SelfReload）没有任何人替我们记 —— 不记就撤不掉，两代钩子会同时生效。</p>
     */
    private static final List<XposedInterface.HookHandle> HANDLES =
            Collections.synchronizedList(new ArrayList<>());

    private XposedBridge() {
    }

    /** 由 {@code LibXposedEntry} 在每个目标进程加载时调用一次。 */
    public static void bind(XposedModule m) {
        module = m;
    }

    public static boolean isBound() {
        return module != null;
    }

    public static XposedModule currentModule() {
        return module;
    }

    /** 供入口类登记「不是通过 XC_MethodHook 装、但同样要能撤」的句柄。 */
    public static void registerHandle(XposedInterface.HookHandle h) {
        if (h != null) {
            HANDLES.add(h);
        }
    }

    /** 撤掉本代所有钩子（SelfReload 换代码前调用）；返回成功撤掉的条数。 */
    public static int unhookAll() {
        int n = 0;
        synchronized (HANDLES) {
            for (XposedInterface.HookHandle h : HANDLES) {
                try {
                    h.unhook();
                    n++;
                } catch (Throwable ignored) {
                }
            }
            HANDLES.clear();
        }
        return n;
    }

    /** legacy：Hook 某个类的所有同名方法。 */
    public static void hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {
        if (clazz == null || methodName == null || callback == null) {
            return;
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                hookMethod(m, callback);
            }
        }
    }

    /** legacy：Hook 单个成员。返回是否成功（legacy 返回 Unhook，这里不需要）。 */
    public static boolean hookMethod(Member member, XC_MethodHook callback) {
        XposedModule m = module;
        if (m == null || !(member instanceof Executable) || callback == null) {
            return false;
        }
        try {
            XposedInterface.HookHandle h = m.hook((Executable) member).intercept(new Hooker(callback));
            if (h != null) {
                HANDLES.add(h);
            }
            return true;
        } catch (Throwable t) {
            log("hook fail " + member + " : " + t);
            return false;
        }
    }

    /** legacy：写 Xposed 日志。有模块实例走模块日志，否则退 logcat。 */
    public static void log(String msg) {
        XposedModule m = module;
        try {
            if (m != null) {
                m.log(Log.INFO, TAG, msg);
                return;
            }
        } catch (Throwable ignored) {
            // 落到 logcat
        }
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
            // 什么都不做
        }
    }

    public static void log(Throwable t) {
        log(t == null ? "null throwable" : Log.getStackTraceString(t));
    }

    /** 把 legacy 回调包成 libxposed 的 Hooker。 */
    private static final class Hooker implements XposedInterface.Hooker {
        private final XC_MethodHook cb;

        Hooker(XC_MethodHook cb) {
            this.cb = cb;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
            p.method = chain.getExecutable();
            p.thisObject = chain.getThisObject();
            List<Object> in = chain.getArgs();
            p.args = in == null ? new Object[0] : in.toArray();

            Object result = null;
            Throwable err = null;

            try {
                XC_MethodHook.callBefore(cb, p);
            } catch (Throwable t) {
                // legacy 里 before 抛异常会直接冒给调用方；这里同样抛，但不让框架把进程带走
                err = t;
            }

            if (err == null && !p.hasResult()) {
                try {
                    result = chain.proceed(p.args);
                } catch (Throwable t) {
                    err = t;
                }
            }

            if (err != null) {
                p.setThrowable(err);
            }
            try {
                XC_MethodHook.callAfter(cb, p);
            } catch (Throwable t) {
                // after 里的异常按 legacy 语义吞掉：绝不因为我们的回调让宿主崩
                log("afterHook error " + t);
            }

            if (p.hasResult()) {
                return p.getResult();
            }
            if (err != null) {
                throw err;
            }
            return result;
        }
    }
}
