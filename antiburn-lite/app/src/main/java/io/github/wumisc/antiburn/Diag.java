package io.github.wumisc.antiburn;

/**
 * v3.0.29：不依赖 Xposed API 的诊断写入器。
 *
 * <p>真机 3.0.28 日志给出了明确的闪退现场：</p>
 * <pre>
 * FATAL java.lang.NoClassDefFoundError: Lio/github/wumisc/antiburn/XposedInit;
 *   Caused by: ClassNotFoundException: de.robv.android.xposed.IXposedHookLoadPackage
 *   at AppComponentFactory.instantiateReceiver(...)
 * </pre>
 *
 * <p>原因：{@code BootReceiver}（以及 Config 的配置回写路径）会调
 * {@code XposedInit.diagForce()}，而 {@code XposedInit implements IXposedHookLoadPackage}。
 * xposed API 是 compileOnly —— 只在 LSPosed 注入的进程里由框架提供，普通 App 进程里没有。
 * 于是「开机广播 / 覆盖安装广播」一进来，类加载就抛 NoClassDefFoundError，整个进程被杀。</p>
 *
 * <p>本类只做一件事：把日志写到和 hook 侧同一个共享日志文件，但不引用任何
 * {@code de.robv.android.xposed} 类型。App 进程（BootReceiver / Config / 前台服务）
 * 一律走这里；XposedInit.diagForce 只保留给真正被注入的进程。</p>
 */
public final class Diag {

    private Diag() {
    }

    /** 关键事件：不受「诊断日志」开关影响，必须落盘。 */
    public static void force(String s) {
        try {
            ConfStore.appendRawLine(System.currentTimeMillis() + " [app] " + s);
        } catch (Throwable ignored) {
            // 日志写不出去绝不能反过来影响主流程
        }
    }

    /** 详细日志：受设置里的「诊断日志」开关控制。 */
    public static void d(String s) {
        try {
            if (!Config.enableLog) {
                return;
            }
        } catch (Throwable ignored) {
            // Config 未初始化时按开启处理
        }
        force(s);
    }
}
