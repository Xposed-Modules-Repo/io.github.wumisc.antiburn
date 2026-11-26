package io.github.wumisc.antiburn.compat;

import io.github.wumisc.antiburn.compat.callbacks.XC_LoadPackage;

/**
 * 实验分支（LSPosed API 102 / libxposed）兼容壳 —— legacy 模块入口接口。
 * 现有的 {@code XposedInit} 就是实现这个接口，保持原样即可。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
