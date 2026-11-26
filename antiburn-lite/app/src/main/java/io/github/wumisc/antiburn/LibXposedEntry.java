package io.github.wumisc.antiburn;

import android.app.Application;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Executable;

import io.github.wumisc.antiburn.compat.XposedBridge;
import io.github.wumisc.antiburn.compat.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 实验分支入口（LSPosed API 102 / libxposed）。
 *
 * <p>与稳定版的差别只有这一层：稳定版走 legacy {@code xposed_init} +
 * {@code IXposedHookLoadPackage}；这里继承 {@link XposedModule}，
 * 由框架回调 {@code onPackageLoaded / onPackageReady}，再把参数组装成
 * legacy 的 {@code XC_LoadPackage.LoadPackageParam} 交给同一个 {@link XposedInit}。</p>
 *
 * <p>Hook 时机取 {@code onPackageReady}：那时拿到的 ClassLoader 才是真正加载应用代码的那个
 * （自定义 AppComponentFactory 时和 defaultClassLoader 不是同一个），比 onPackageLoaded 更稳。</p>
 */
public class LibXposedEntry extends XposedModule {

    private static final String TAG = "AntiburnApi102";

    /** 同一进程只跑一次，防止 onPackageReady 对同包多次回调造成重复 Hook。 */
    private volatile boolean started;
    /** Application 抓取 Hook 每进程只装一次。 */
    private boolean appCaptureInstalled;

    /**
     * 抓本进程唯一的 Application。
     *
     * <p>挂在 {@code Application.<init>} 而不是 onCreate：构造器不走虚分派，
     * SystemUIApplication、抖音 / 快手各自的 Application 子类都必然先调
     * {@code super()}，所以这一个 Hook 覆盖所有进程，也不怕子类重写 onCreate。
     * 抓到的实例交给 {@link AppContextHolder}，Hook 代码里的
     * currentApplication() 才有得用（libxposed 没有 legacy 的 AndroidAppHelper）。</p>
     */
    private void installApplicationCapture() {
        if (appCaptureInstalled) {
            return;
        }
        appCaptureInstalled = true;
        try {
            Executable ctor = Application.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            io.github.libxposed.api.XposedInterface.HookHandle h = hook(ctor).intercept(chain -> {
                Object app = chain.getThisObject();
                if (app != null) {
                    AppContextHolder.capture(app);
                }
                return chain.proceed();
            });
            if (h != null) {
                XposedBridge.registerHandle(h);
            }
            safeLog("Application capture hook installed");
        } catch (Throwable t) {
            safeLog("Application capture hook failed: " + t);
        }
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        XposedBridge.bind(this);
        safeLog("module loaded, process=" + param.getProcessName()
                + " api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        // 绑定 + 抢在 Application 实例化之前装抓取 Hook；真正的业务 Hook 等
        // onPackageReady，那时拿到的 ClassLoader 才是加载宿主代码的那个
        XposedBridge.bind(this);
        installApplicationCapture();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        XposedBridge.bind(this);
        if (started) {
            return;
        }
        started = true;

        XC_LoadPackage.LoadPackageParam lp = new XC_LoadPackage.LoadPackageParam();
        lp.packageName = param.getPackageName();
        lp.processName = currentProcessName(lp.packageName);
        lp.classLoader = param.getClassLoader();
        lp.appInfo = param.getApplicationInfo();
        lp.isFirstApplication = param.isFirstPackage();

        safeLog("package ready pkg=" + lp.packageName + " proc=" + lp.processName
                + " first=" + lp.isFirstApplication);

        try {
            new XposedInit().handleLoadPackage(lp);
            safeLog("hook dispatched ok pkg=" + lp.packageName);
        } catch (Throwable t) {
            safeLog("hook dispatched FAIL pkg=" + lp.packageName + " : " + t);
        }
    }

    /**
     * 热重载前（旧代码里执行）：默认实现返回 false = 取消热重载，这就是「覆盖安装后
     * 必须重启系统界面」的根因之一。这里返回 true，并把 Application 交给下一代
     * （它是宿主类加载器对象，不属于被丢弃的旧模块类加载器，可以安全跨代）。</p>
     */
    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        // 先确认能交接，再退役 —— 顺序反了会出现「已经停掉旧代码、又因为拿不到上下文取消重载」
        // 的植物人状态：钩子被我们自己撤了，框架却没换新代码。
        Application app = AppContextHolder.peek();
        if (app == null) {
            safeLog("hot reloading rejected: no Application");
            return false;
        }
        try {
            param.setSavedInstanceState(app);
            safeLog("hot reloading, savedApp=true");
        } catch (Throwable t) {
            safeLog("hot reloading save failed: " + t);
            return false;
        }
        // 旧一代的周期任务 / 浮层 / 线程在这里收干净：onHotReloaded 在新类加载器里执行，
        // 那时旧对象已经摸不到了。
        try {
            HotReloadGuard.shutdown();
        } catch (Throwable t) {
            safeLog("hot reload retire failed: " + t);
        }
        return true;
    }

    /**
     * 热重载完成（新代码里执行）：框架不会重放 onPackageLoaded / onPackageReady，
     * 必须自己把这一代的 Hook 装上。
     *
     * <ol>
     *   <li>撤掉上一代所有钩子（否则两代同时生效：隐藏会抖、日志会翻倍、旧类加载器永远回收不掉）；</li>
     *   <li>接手 Application —— 新类加载器的静态字段是空的，不能再指望构造器 Hook 重抓；</li>
     *   <li>用新代码重新装 Application 抓取与业务 Hook（等价于一次 onPackageReady）；</li>
     *   <li>给 App 进程记一条「已热重载」时间，界面据此不再提示「系统界面跑的是旧版」。</li>
     * </ol>
     */
    @Override
    public void onHotReloaded(HotReloadedParam param) {
        safeLog("hot reloaded, process=" + param.getProcessName());
        XposedBridge.bind(this);

        int removed = 0;
        try {
            for (HookHandle h : param.getOldHookHandles()) {
                try {
                    h.unhook();
                    removed++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            safeLog("hot reload unhook failed: " + t);
        }

        Application app = null;
        try {
            Object saved = param.getSavedInstanceState();
            if (saved instanceof Application) {
                app = (Application) saved;
                AppContextHolder.capture(app);
            }
        } catch (Throwable t) {
            safeLog("hot reload state failed: " + t);
        }
        if (app == null) {
            app = AppContextHolder.peek();
        }

        // 与模块自换代码互斥：本进程若已经由 SelfReload 装过同一条 APK 的钩子，
        // 框架这一代就不要再装 —— 两代同时生效会出现双浮层、日志翻倍、状态栏抽搐。
        if (app != null) {
            try {
                if ("com.android.systemui".equals(param.getProcessName())
                        && SelfReload.frameworkShouldSkip(app)) {
                    safeLog("hot reload skipped: self generation already active");
                    started = true;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        // 新一代 = 全新类加载器，这些标志位本来就是初始值；显式复位只为表达意图
        started = false;
        appCaptureInstalled = false;
        installApplicationCapture();

        if (removed > 0 || app != null) {
            safeLog("hot reload unhooked=" + removed + " app=" + (app != null));
        }
        if (app == null) {
            safeLog("hot reload: 没有 Application，跳过重装钩子（本次热重载对隐藏逻辑无效）");
            return;
        }
        try {
            XC_LoadPackage.LoadPackageParam lp = new XC_LoadPackage.LoadPackageParam();
            lp.packageName = app.getPackageName();
            lp.processName = currentProcessName(lp.packageName);
            ClassLoader host = app.getClassLoader();
            lp.classLoader = host != null ? host : Application.class.getClassLoader();
            lp.appInfo = app.getApplicationInfo();
            lp.isFirstApplication = true;
            new XposedInit().handleLoadPackage(lp);
            // 成功后才认账：重装钩子失败时保持 started=false，
            // 至少还能在后续 onPackageReady（若框架补发）里再来一次，
            // 而不是留一个「自认为已启动、其实零钩子」的死状态。
            started = true;
            safeLog("hot reload redispatch ok pkg=" + lp.packageName);
            noteHotReload(app);
        } catch (Throwable t) {
            safeLog("hot reload redispatch FAIL: " + t);
        }
    }

    /** 告诉 App 进程「这一代已经是新代码了」，橙卡据此不再误报旧版。 */
    private void noteHotReload(Application app) {
        try {
            Bundle req = new Bundle();
            req.putString(ConfigProvider.KEY_TOKEN, ConfigProvider.TOKEN);
            app.getContentResolver().call(
                    Uri.parse("content://" + ConfigProvider.AUTHORITY),
                    ConfigProvider.METHOD_PUT_RELOAD, null, req);
            safeLog("hot reload noted to app");
        } catch (Throwable t) {
            safeLog("hot reload note failed: " + t);
        }
    }

    /** 模块自身日志：绝不因为日志异常影响宿主进程。 */
    private void safeLog(String msg) {
        try {
            log(android.util.Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
            XposedBridge.log(msg);
        }
    }

    /** 进程名优先用系统 API，拿不到就退包名。 */
    private static String currentProcessName(String fallback) {
        try {
            String p = Application.getProcessName();
            if (p != null && !p.isEmpty()) {
                return p;
            }
        } catch (Throwable ignored) {
            // 退包名
        }
        return fallback;
    }
}
