package io.github.wumisc.antiburn;

/**
 * 热重载退役总闸（v3.2.27）。
 *
 * <p>libxposed 的 {@code onHotReloading()} 跑在**旧一代**代码里，只有在这里才能
 * 碰得到旧一代的静态字段和视图引用；等进到 {@code onHotReloaded()} 已经是新类加载器，
 * 旧对象一只都摸不到。</p>
 *
 * <p>框架只负责 unhook 方法钩子，**不会**：
 * 撤掉我们排进主线程的周期任务、还原 setAlpha(0)/挪过位置的控件、移除挂在
 * SystemUI / 视频 App 上的浮层与悬浮球、停掉配置预取与日志写线程。
 * 这些不收尾，热重载的结果就是「钩子换新了，但旧一代还在后台干活」：
 * 日志翻倍、状态栏反复闪、两个悬浮球、旧类加载器永久泄漏。</p>
 */
final class HotReloadGuard {

    private HotReloadGuard() {
    }

    /** 只由 LibXposedEntry.onHotReloading 在旧一代里调用；任何一步失败都不能阻断热重载。 */
    static void shutdown() {
        try {
            Controller.shutdownForReload();
        } catch (Throwable ignored) {
        }
        try {
            DouyinHook.shutdownForReload();
        } catch (Throwable ignored) {
        }
        try {
            SysOverlay.release();
        } catch (Throwable ignored) {
        }
        try {
            PixelShift.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            Config.stopForReload();
        } catch (Throwable ignored) {
        }
        try {
            LivePause.stopForReload();
        } catch (Throwable ignored) {
        }
        try {
            XposedInit.stopForReload();
        } catch (Throwable ignored) {
        }
    }
}
