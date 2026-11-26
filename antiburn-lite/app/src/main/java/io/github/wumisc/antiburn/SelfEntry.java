package io.github.wumisc.antiburn;

import android.app.Application;

import io.github.wumisc.antiburn.compat.callbacks.XC_LoadPackage;
import io.github.wumisc.antiburn.compat.XposedBridge;
import io.github.libxposed.api.XposedModule;

/**
 * 新一代代码的入口（由 {@link SelfReload} 通过新 APK 的 DexClassLoader 反射调用）。
 *
 * <p>框架不会替我们的自换代码发任何生命周期回调，所以这里必须把绑定、上下文、
 * 业务引擎派发全做一遍 —— 与 {@code LibXposedEntry.onPackageReady} 等价。</p>
 */
public final class SelfEntry {

    private SelfEntry() {
    }

    /** 必须 public static：反射调用。 */
    public static boolean install(XposedModule module, Application app, String processName) {
        try {
            XposedBridge.bind(module);
            AppContextHolder.capture(app);
            SelfReload.arm(module);

            XC_LoadPackage.LoadPackageParam lp = new XC_LoadPackage.LoadPackageParam();
            lp.packageName = app.getPackageName();
            lp.processName = processName == null || processName.isEmpty()
                    ? app.getPackageName() : processName;
            lp.classLoader = app.getClassLoader();
            lp.appInfo = app.getApplicationInfo();
            lp.isFirstApplication = true;
            new XposedInit().handleLoadPackage(lp);
            XposedInit.diagForce("SELFLOAD installed pkg=" + lp.packageName + " proc=" + lp.processName);
            SelfReload.note(app, ConfigProvider.METHOD_PUT_RELOAD);
            SelfReload.note(app, ConfigProvider.METHOD_PUT_SEEN);
            return true;
        } catch (Throwable t) {
            XposedInit.diagForce("SELFLOAD install-fail " + t);
            return false;
        }
    }
}
