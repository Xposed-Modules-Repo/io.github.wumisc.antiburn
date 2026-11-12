package io.github.wumisc.antiburn;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/**
 * 线路 B：借 Shizuku 的 shell 身份补上无障碍引擎做不到的两件事。
 *
 * <h3>B 不是第三种「引擎」</h3>
 * <p>Shizuku 只能拿到 shell（uid 2000）权限，改不了 SystemUI 内部布局，所以它**不能**
 * 替代 Hook 做「真隐藏」或「像素微移」。它能做的是把无 root 方案里最烦的那几步自动化：</p>
 * <ol>
 *   <li><b>开通无障碍</b>：用户原本要进「设置 → 无障碍 → 已下载的服务 → 找到本应用 → 打开」，
 *       四个层级、五种 ROM 路径各不相同。用 shell 身份改
 *       {@code enabled_accessibility_services} 一次到位，失败率远低于手工找菜单。</li>
 *   <li><b>真隐藏状态栏（实验）</b>：{@code cmd statusbar send-disable-flag statusbar}
 *       会让状态栏整条禁用——这比遮盖更彻底（那块像素不再合成），但副作用明显
 *       （下拉通知栏失效），因此默认关闭、界面上明确标为实验项。</li>
 * </ol>
 *
 * <h3>为什么所有方法都不抛异常</h3>
 * <p>Shizuku 未安装时 {@code Shizuku.pingBinder()} 会抛 {@code IllegalStateException}，
 * 服务被杀死后调用会抛 {@code RemoteException}，权限被撤销时会抛 {@code SecurityException}。
 * 这些都不是「程序错误」，而是「当前不可用」这一正常状态。全部收进返回值，
 * 让 UI 只处理布尔与字符串。</p>
 */
public final class ShizukuBridge {

    private static final String TAG = "AntiburnShizuku";

    /** 请求码，与 onRequestPermissionResult 对应 */
    public static final int REQUEST_CODE = 0x5B1;

    private ShizukuBridge() {
    }

    /** Shizuku 服务是否在跑（装了 App 且服务已启动） */
    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已获得授权 */
    public static boolean hasPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            // pre-v11 没有权限模型，binder 活着就等价于有权限
            if (Shizuku.isPreV11()) {
                return true;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 弹授权请求。
     *
     * <p>必须在 Activity 里调用：Shizuku 的授权对话框依附于当前 Activity。
     * 返回 false 表示没能发起（未安装 / 服务未启动 / 已被永久拒绝），
     * 调用方据此给出可操作的提示，而不是静默失败。</p>
     */
    public static boolean requestPermission(Activity activity) {
        if (activity == null) {
            return false;
        }
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            if (Shizuku.isPreV11()) {
                return true;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // 用户之前拒绝过：Shizuku 规定此时 requestPermission 会被直接回调拒绝，
                // 需要用户去 Shizuku App 里手动授权。返回 false 让 UI 给明确指引。
                return false;
            }
            Shizuku.requestPermission(REQUEST_CODE);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
            return false;
        }
    }

    /**
     * 以 shell 身份执行一条命令，返回合并后的 stdout（命令自带 {@code 2>&1} 会一并带回）。
     *
     * @return 命令输出；{@code null} 表示不可用或执行失败（区别记在 {@link #lastError()}）
     */
    public static String exec(String cmd) {
        lastError = "";
        if (cmd == null || cmd.isEmpty()) {
            lastError = "空命令";
            return null;
        }
        if (!hasPermission()) {
            lastError = isBinderAlive() ? "未获得 Shizuku 授权" : "Shizuku 服务未运行";
            return null;
        }
        Process p = null;
        try {
            p = spawnShell(new String[]{"sh", "-c", cmd});
        } catch (Throwable t) {
            lastError = "启动 shell 失败：" + t.getClass().getSimpleName();
            Log.w(TAG, "newProcess failed", t);
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                // 防御：异常命令刷屏时不要无限吃内存
                if (bos.size() > 64 * 1024) {
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "read stdout failed", t);
        }
        try {
            p.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "waitFor failed", t);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private static String lastError = "";

    /**
     * 以 shell 身份起一个进程。
     *
     * <p>{@code Shizuku.newProcess} 在 api 13.x 里被改成了 <b>private</b>（官方意图是让开发者
     * 改用 UserService），但 UserService 需要额外打一个能被 app_process 加载的 dex、注册
     * ServiceConnection、自己维护 binder 协议——为了跑两条 {@code settings} 命令，
     * 那套机制的复杂度与失败面都远大于收益。</p>
     *
     * <p>这里用反射调那个 private 静态方法。安全性上没有问题：Shizuku 是普通库类，
     * 不受 Android hidden API 名单限制，{@code setAccessible(true)} 总能成功。
     * 真正的风险是「未来某个版本把方法改名」，因此调用方必须能接受 null 并降级为
     * 「请手动在设置里开启无障碍」——{@link #exec} 的返回契约已经这么设计了。</p>
     */
    private static Process spawnShell(String[] cmd) throws Throwable {
        java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        Object p = m.invoke(null, new Object[]{cmd, null, null});
        if (p instanceof Process) {
            return (Process) p;
        }
        throw new IllegalStateException("newProcess 返回了非 Process 对象");
    }

    /** 最近一次 {@link #exec} 失败的原因，供 UI 直接展示 */
    public static String lastError() {
        return lastError;
    }

    // ---- 授权结果回调 ----

    /** 授权结果回调。用自定义接口而不是把 Shizuku 的监听器类型暴露给 UI， */
    /** 是为了让界面层完全不依赖 rikka.* 包：Shizuku 未安装时那些类可能加载失败。 */
    public interface PermissionCallback {
        void onResult(boolean granted);
    }

    private static rikka.shizuku.Shizuku.OnRequestPermissionResultListener listener;

    /**
     * 注册授权结果监听，返回是否注册成功。
     *
     * <p>失败（Shizuku 未安装 / binder 未就绪）时返回 false，界面据此提示
     * 「先安装并启动 Shizuku」，而不是让用户点完授权按钮后毫无反应。</p>
     */
    public static boolean watchPermission(PermissionCallback cb) {
        if (cb == null) {
            return false;
        }
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            if (listener == null) {
                listener = (requestCode, grantResult) -> {
                    // 只关心自己发的请求码，别的模块/页面发的请求不在这里处理
                    if (requestCode == REQUEST_CODE) {
                        PermissionCallback c = pendingCallback;
                        if (c != null) {
                            c.onResult(grantResult == PackageManager.PERMISSION_GRANTED);
                        }
                    }
                };
                Shizuku.addRequestPermissionResultListener(listener);
            }
            pendingCallback = cb;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "watchPermission failed", t);
            return false;
        }
    }

    private static volatile PermissionCallback pendingCallback;

    /** 注销监听。可以重复调用。 */
    public static void unwatchPermission() {
        pendingCallback = null;
        if (listener != null) {
            try {
                Shizuku.removeRequestPermissionResultListener(listener);
            } catch (Throwable ignored) {
                // binder 已经死了，监听自然失效
            }
            listener = null;
        }
    }

    // ---- 具体能力 ----

    /** 本应用无障碍服务的组件名（写进 Secure 设置用的格式） */
    public static String serviceComponent(Context ctx) {
        return ctx.getPackageName() + "/" + BurnInMaskService.class.getName();
    }

    /**
     * 用 shell 身份开通本应用的无障碍服务。
     *
     * <p>不能直接覆盖 {@code enabled_accessibility_services}——那会把用户已经开启的
     * 其它无障碍服务（读屏、自动化工具）一并关掉。先读现值，追加自己的组件，再写回。
     * 已存在就不重复追加，避免列表无限变长。</p>
     *
     * @return 执行输出；null 表示不可用
     */
    public static String enableAccessibility(Context ctx) {
        String comp = serviceComponent(ctx);
        String cmd = "cur=$(settings get secure enabled_accessibility_services); "
                + "case \"$cur\" in "
                + "  *" + comp + "*) ;; "
                + "  null|\"\") settings put secure enabled_accessibility_services '" + comp + "' ;; "
                + "  *) settings put secure enabled_accessibility_services \"$cur:" + comp + "\" ;; "
                + "esac; "
                + "settings put secure accessibility_enabled 1; "
                + "echo ab-ok";
        return exec(cmd);
    }

    /**
     * 用 shell 身份关闭本应用的无障碍服务（只摘掉自己，不动别人的）。
     *
     * <p>做法是按冒号切成多行、用 {@code grep -v -F} 精确剔除自己那一行、再拼回去。
     * 不用 sed 是因为组件名里带 {@code .}（正则任意字符）与 {@code /}（sed 分隔符冲突），
     * 两种转义叠在一起极易写出「看起来对、跑起来删错项」的命令。</p>
     */
    public static String disableAccessibility(Context ctx) {
        String comp = serviceComponent(ctx);
        String cmd = "cur=$(settings get secure enabled_accessibility_services); "
                + "case \"$cur\" in "
                + "  *" + comp + "*) "
                + "    new=$(echo \"$cur\" | tr ':' '\\n' | grep -v -F '" + comp + "' | tr '\\n' ':'); "
                + "    new=${new%:}; "
                + "    settings put secure enabled_accessibility_services \"$new\" ;; "
                + "  *) echo not-enabled ;; "
                + "esac; "
                + "echo ab-ok";
        return exec(cmd);
    }

    /**
     * 读当前无障碍服务列表（用于界面显示「已由 Shizuku 开通」）。
     */
    public static String queryAccessibility(Context ctx) {
        return exec("settings get secure enabled_accessibility_services 2>&1");
    }

    /**
     * 实验：整条禁用状态栏。
     *
     * <p>{@code send-disable-flag statusbar} 用的是 systemui 的 disable flag，
     * 本来是给「设置向导」这类全屏流程用的。禁用后状态栏不显示、也不可下拉，
     * 对防烧屏是彻底的，但会让用户暂时看不到时间和通知。</p>
     *
     * @param hide true = 禁用，false = 恢复
     */
    public static String setStatusBarDisabled(boolean hide) {
        String cmd = hide
                ? "cmd statusbar send-disable-flag statusbar 2>&1; echo ab-ok"
                : "cmd statusbar clear-disable-flag statusbar 2>&1; echo ab-ok";
        return exec(cmd);
    }

    /** 兜底恢复：任何情况下都希望状态栏能回来，所以单独留一个不带判断的恢复入口。 */
    public static String restoreStatusBar() {
        return exec("cmd statusbar clear-disable-flag statusbar 2>&1; echo ab-ok");
    }
}
