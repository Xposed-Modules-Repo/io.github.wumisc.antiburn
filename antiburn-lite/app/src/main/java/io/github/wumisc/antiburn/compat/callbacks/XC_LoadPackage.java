package io.github.wumisc.antiburn.compat.callbacks;

import android.content.pm.ApplicationInfo;

/**
 * 实验分支（LSPosed API 102 / libxposed）兼容壳 —— legacy 的包加载参数容器。
 *
 * <p>libxposed 的 {@code XposedModuleInterface.PackageLoadedParam} 没有
 * {@code processName}，也没有可直接读的 {@code packageName} 字段，
 * 由 {@code LibXposedEntry} 组装成这个对象再交给现有的 {@code XposedInit}。</p>
 */
public final class XC_LoadPackage {

    public static final class LoadPackageParam {
        /** 目标包名。 */
        public String packageName;
        /** 进程名；拿不到时等于 packageName。 */
        public String processName;
        /** 目标进程的 ClassLoader，现有 Hook 全部靠它找类。 */
        public ClassLoader classLoader;
        /** 目标应用信息，可能为 null。 */
        public ApplicationInfo appInfo;
        /** 是否是该包的首个进程。 */
        public boolean isFirstApplication;
    }
}
