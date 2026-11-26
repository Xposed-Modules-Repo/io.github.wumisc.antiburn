package io.github.wumisc.antiburn;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import dalvik.system.DexClassLoader;
import io.github.wumisc.antiburn.compat.XposedBridge;
import io.github.libxposed.api.XposedModule;

/**
 * 模块自换代码（v3.2.28）。
 *
 * <p><b>为什么需要它</b>：框架热重载只在管理器支持 `autoHotReload` 时才发生，而用户要求
 * 「只有第一次安装才允许重启系统界面」。管理器一旦不给我们热重载，唯一还能让新版代码
 * 进到已在运行的 SystemUI 里的办法，就是模块自己发起：把新 APK 当 dex 载进来，
 * 退役旧一代、装上新一代。这不依赖管理器实现，也不重启 SystemUI 进程。</p>
 *
 * <p><b>代际结构</b>：父加载器用 {@code XposedModule.class.getClassLoader()}（框架加载器），
 * 而不是本代自己的加载器 —— 否则每换一次代码就在 loader 链上套一层，旧代永远回收不掉。
 * 新一代的类从新 APK 加载，框架类、android 类仍由父链提供。</p>
 *
 * <p><b>失败策略</b>：先验证新 APK 能载入 {@link SelfEntry}，验证不过就原地不动、继续用旧代；
 * 验证通过才退役旧代并安装新一代。安装失败不更新基线时间，20 秒后自动重试。</p>
 */
final class SelfReload {

    private static final String MODULE_PKG = "io.github.wumisc.antiburn";
    private static final String ENTRY = "io.github.wumisc.antiburn.SelfEntry";
    private static final long CHECK_MS = 20_000L;

    private static volatile XposedModule module;
    private static volatile long genApkTime = 0L;
    private static volatile String genApkPath = "";
    private static volatile long lastCheckAt = 0L;
    private static volatile boolean busy = false;

    /**
     * 纯判定：要不要自己换代码。抽成静态纯函数是为了能在 JVM 沙盒里钉住所有分支，
     * 不依赖 PackageManager / Binder。
     *
     * @param frameworkReloadAt 框架热重载被记账到的时间（0 = 没发生）；它已经处理过这一版就不重复装
     */
    static boolean needsReload(long genApkTime, String genApkPath,
                               long apkTime, String apkPath, long frameworkReloadAt) {
        if (apkTime <= 0L || apkPath == null || apkPath.isEmpty()) {
            return false;
        }
        if (frameworkReloadAt >= apkTime) {
            return false;
        }
        return apkTime > genApkTime || !apkPath.equals(genApkPath);
    }

    /** 框架这一代要不要放弃装钩（本进程已由 SelfReload 装过同一版）。 */
    static boolean frameworkShouldSkip(Context ctx) {
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(MODULE_PKG, 0);
            long apkTime = pi.lastUpdateTime;
            long[] g = genTimes(ctx);
            return apkTime > 0L && g != null && g[1] >= apkTime;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一次读回 {框架热重载时间, 自换代码时间, 首次跑过时间}；读不到返回 null。 */
    private static long[] genTimes(Context ctx) {
        try {
            android.os.Bundle req = new android.os.Bundle();
            req.putString(ConfigProvider.KEY_TOKEN, ConfigProvider.TOKEN);
            android.os.Bundle b = ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + ConfigProvider.AUTHORITY),
                    ConfigProvider.METHOD_GET_GEN, null, req);
            if (b == null) {
                return null;
            }
            return new long[]{
                    b.getLong(ConfigProvider.KEY_RELOAD_AT, 0L),
                    b.getLong(ConfigProvider.KEY_SELF_AT, 0L),
                    b.getLong(ConfigProvider.KEY_SEEN_AT, 0L)
            };
        } catch (Throwable t) {
            return null;
        }
    }

    private SelfReload() {
    }

    /** 每个进程在当前代装上钩子后调用一次：记下「这一代来自哪个 APK、哪次更新」。 */
    static void arm(XposedModule m) {
        module = m;
        try {
            Context ctx = AppContextHolder.currentApplication();
            if (ctx == null) {
                return;
            }
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(MODULE_PKG, 0);
            genApkTime = pi.lastUpdateTime;
            genApkPath = pi.applicationInfo == null ? "" : pi.applicationInfo.sourceDir;
            XposedInit.diagForce("SELFLOAD arm apk_t=" + genApkTime + " path=" + genApkPath);
            note(ctx, ConfigProvider.METHOD_PUT_SEEN);
        } catch (Throwable t) {
            XposedInit.diag("SELFLOAD arm-fail " + t.getClass().getSimpleName());
        }
    }

    /** 向 App 进程记账（模块已在系统界面跑过 / 刚刚换过代码）；失败不影响功能。 */
    /** 自换代码成功后记账：存「换到的那一版 lastUpdateTime」，与框架热重载互斥。 */
    static void noteSelf(Context ctx, long apkTime) {
        try {
            android.os.Bundle req = new android.os.Bundle();
            req.putString(ConfigProvider.KEY_TOKEN, ConfigProvider.TOKEN);
            req.putLong(ConfigProvider.KEY_CONF, apkTime);
            ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + ConfigProvider.AUTHORITY),
                    ConfigProvider.METHOD_PUT_SELF, null, req);
        } catch (Throwable ignored) {
        }
    }

    static void note(Context ctx, String method) {
        try {
            android.os.Bundle req = new android.os.Bundle();
            req.putString(ConfigProvider.KEY_TOKEN, ConfigProvider.TOKEN);
            ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + ConfigProvider.AUTHORITY), method, null, req);
        } catch (Throwable ignored) {
        }
    }

    /** Controller 每轮 tick 调一次，内部 20 秒节流；只在 SystemUI 里跑。 */
    static void check() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCheckAt < CHECK_MS) {
            return;
        }
        lastCheckAt = now;
        if (busy || module == null) {
            return;
        }
        Context ctx = AppContextHolder.currentApplication();
        if (ctx == null) {
            return;
        }
        PackageInfo pi;
        try {
            pi = ctx.getPackageManager().getPackageInfo(MODULE_PKG, 0);
        } catch (Throwable t) {
            return; // 管理器 / 权限挡住查询：不猜，保持现状
        }
        if (pi == null || pi.applicationInfo == null) {
            return;
        }
        long apkTime = pi.lastUpdateTime;
        String apk = pi.applicationInfo.sourceDir;
        long frameReload = 0L;
        long[] gens = genTimes(ctx);
        if (gens != null) {
            frameReload = gens[0];
        }
        if (!needsReload(genApkTime, genApkPath, apkTime, apk, frameReload)) {
            if (frameReload >= apkTime && apkTime > genApkTime) {
                // 框架已经热重载过这一版：把基线跟上，避免我们 20 秒后再装一遍
                genApkTime = apkTime;
                genApkPath = apk;
                XposedInit.diag("SELFLOAD skip=framework-reloaded");
            }
            return;
        }
        busy = true;
        try {
            reload(ctx, apk, apkTime);
        } catch (Throwable t) {
            XposedInit.diagForce("SELFLOAD fail " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage()));
        } finally {
            busy = false;
        }
    }

    /**
     * 换代码：先验证新 APK 可载入，再退役旧代、安装新代。
     * 没有调用 {@link HotReloadGuard} 之前不会产生任何副作用，验证失败直接放弃。
     */
    private static void reload(Context ctx, String apk, long apkTime) throws Exception {
        // v3.2.32：不能用框架加载器直接当父加载器。Vector 的框架加载器会把
        // de.robv.android.xposed / libxposed API 等“框架类”暴露成自己的混淆实现；
        // 直接做父加载器时，新代解析 XposedModule 会失败（SELFLOAD install-fail）。
        // 这里做一层隔离：只把 libxposed API 放给框架，模块自己的类一律抛
        // ClassNotFoundException，让 DexClassLoader 从新 APK 里取新代码。
        ClassLoader api = XposedModule.class.getClassLoader();
        if (api == null) {
            api = SelfReload.class.getClassLoader();
        }
        String loadPath = apk;
        DexClassLoader child = new DexClassLoader(loadPath, dexDir(ctx), null, new ApiOnlyParent(api));
        // 预检：只加载类，不执行静态初始化之外的任何动作
        Class<?> entry;
        try {
            entry = Class.forName(ENTRY, true, child);
        } catch (ClassNotFoundException cnf) {
            // v3.2.37：SystemUI 等 hook 进程受 SELinux 限制读不到模块 /data/app APK，
            // DexClassLoader 载不到任何类。改经 provider 只读 FD 把 APK 拷到本进程缓存再载。
            String staged = stageApk(ctx);
            if (staged == null) {
                throw cnf;
            }
            loadPath = staged;
            child = new DexClassLoader(loadPath, dexDir(ctx), null, new ApiOnlyParent(api));
            entry = Class.forName(ENTRY, true, child);
        }

        // 先把「旧代是谁」记下来：新一代 install 失败时要原样回滚，不能把我们自己的
        // 基线推进到新版 —— 否则下一轮 20s check 会认为「已经换过了」，永不重试。
        Application app = (Application) AppContextHolder.currentApplication();
        if (app == null) {
            throw new IllegalStateException("no Application");
        }
        String proc = currentProcessName(app);
        long oldGenTime = genApkTime;
        String oldGenPath = genApkPath;

        int removed = XposedBridge.unhookAll();
        HotReloadGuard.shutdown();
        XposedInit.diagForce("SELFLOAD reload apk_t=" + apkTime
                + " unhooked=" + removed + " path=" + new File(loadPath).getName());

        try {
            Object ok = entry.getMethod("install", XposedModule.class, Application.class, String.class)
                    .invoke(null, module, app, proc);
            if (!Boolean.TRUE.equals(ok)) {
                throw new IllegalStateException("install returned false");
            }
            genApkTime = apkTime;
            genApkPath = apk;
            noteSelf(ctx, apkTime);
            XposedInit.diagForce("SELFLOAD done apk_t=" + apkTime);
        } catch (Throwable t) {
            // v3.2.30：旧版在 install 失败后只把 busy 清掉，但本代 Controller 已经被
            // HotReloadGuard 退役，周期不再跑 —— 所谓「20 秒后重试」永远不会发生，
            // 模块会一直躺到 SystemUI 重启。这里立刻用旧代自己的 SelfEntry 重新装回，
            // 并把基线退回旧版；下一轮 check 仍会重试新版。
            boolean rolled = false;
            try {
                rolled = SelfEntry.install(module, app, proc);
            } catch (Throwable ignored) {
            }
            genApkTime = oldGenTime;
            genApkPath = oldGenPath;
            XposedInit.diagForce("SELFLOAD install-fail rollback=" + rolled + " : " + t);
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new RuntimeException(t);
        }
    }

    private static String dexDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "selfreload");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d.getAbsolutePath();
    }

    /**
     * v3.2.37：经 provider 只读 FD 把模块 APK 拷到本进程缓存，返回本地路径。
     * FD 由模块 App 进程打开自己的 base.apk 后经 Binder 传来，不受 /data/app SELinux 限制。
     * 任何一步失败返回 null，调用方退回原异常。
     */
    private static String stageApk(Context ctx) {
        ParcelFileDescriptor fd = null;
        try {
            android.os.Bundle req = new android.os.Bundle();
            req.putString(ConfigProvider.KEY_TOKEN, ConfigProvider.TOKEN);
            android.os.Bundle b = ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + ConfigProvider.AUTHORITY),
                    ConfigProvider.METHOD_GET_APK, null, req);
            if (b == null) {
                return null;
            }
            fd = b.getParcelable(ConfigProvider.KEY_APK_FD);
            if (fd == null) {
                return null;
            }
            File out = new File(dexDir(ctx), "staged.apk");
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
                 FileOutputStream os = new FileOutputStream(out)) {
                fd = null; // 交给 AutoCloseInputStream 关闭
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            XposedInit.diag("SELFLOAD stage-fail " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String currentProcessName(Application app) {
        try {
            String p = app.getProcessName();
            if (p != null && !p.isEmpty()) {
                return p;
            }
        } catch (Throwable ignored) {
        }
        return app.getPackageName();
    }

    /** 只向框架借 libxposed API；模块类全部留给 DexClassLoader 自己加载。 */
    private static final class ApiOnlyParent extends ClassLoader {
        private final ClassLoader api;

        ApiOnlyParent(ClassLoader api) {
            super(null);
            this.api = api;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("io.github.wumisc.antiburn.")) {
                throw new ClassNotFoundException(name);
            }
            if (name.startsWith("io.github.libxposed.api.")) {
                return api.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
