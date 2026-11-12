package io.github.wumisc.antiburn;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 运行环境探测：这台机器能用哪条引擎。
 *
 * <h3>为什么要探测而不是让用户猜</h3>
 * <p>v2.9 的教训是「入口消失但功能还在跑」——用户看不到状态栏偏移磁贴，以为功能没了，
 * 实际上是入口被删。反过来同样糟：把不可用的选项藏起来，用户只会反复点、反复失败。
 * 所以这里探测各项能力，UI 把不可用的选项**灰显并写明原因**，而不是隐藏。</p>
 *
 * <h3>v2.6.1 修正：LSPosed 必须用 su 去查</h3>
 * <p>上一版用 {@code new File("/data/adb/lspd").exists()} 判断 LSPosed 是否存在，这是错的：
 * {@code /data/adb} 是 {@code 0700 root:root}，应用进程即便是 root 用户在跑，
 * 只要没拿到 su 提权就读不到，{@code exists()} 恒为 false。结果就是
 * 「配了 root、也装了 LSPosed 的用户，永远看到『有 root 但没装 LSPosed』」。
 * 现在改为：先拿到 su，再用 su 列模块目录 —— 这才是唯一能看见 {@code /data/adb} 的身份。</p>
 *
 * <h3>为什么 root 要分三态</h3>
 * <p>「没有 root」和「装了 root 管理但本应用没被授权」对用户是两件完全不同的事，
 * 处置办法也不同（前者换引擎，后者去 KernelSU/Magisk 里点允许）。
 * 只回一个 boolean 会把后者也显示成「未检测到 root」，用户会以为这模块不支持他的手机。</p>
 */
public final class Capability {

    private static final String TAG = "AntiburnCapability";

    /** su 不可用（没 root，或 root 管理不可达） */
    public static final int ROOT_NONE = 0;
    /** su 已授权，{@code id -u} 返回 0 */
    public static final int ROOT_GRANTED = 1;
    /** 存在 su，但被拒绝（未授权 / 拒绝 / 授权弹窗超时） */
    public static final int ROOT_DENIED = 2;

    /** root 三态，见 {@link #ROOT_NONE} 等 */
    public int rootState = ROOT_NONE;
    /** 系统里存在 LSPosed 安装痕迹（不代表本模块已在该作用域启用） */
    public boolean lsposedPresent;
    /** 装了 Shizuku App */
    public boolean shizukuInstalled;
    /** Shizuku 服务在跑（binder 可达） */
    public boolean shizukuReady;
    /** 已获得 Shizuku 授权 */
    public boolean shizukuGranted;
    /** 本应用的无障碍服务当前已开启 */
    public boolean a11yEnabled;
    /** 探测过程留下的说明，界面在不可用时原样展示，便于用户自查 */
    public String note = "";

    /**
     * root / LSPosed 探测结果的进程内缓存。
     *
     * <p>这两个结论只能靠 fork su 拿到，而 EngineCard 每次 ON_RESUME（从设置页返回）
     * 都会重探一次。没有缓存时用户每切回来一次就要被弹一次授权框 —— 未授权状态下
     * 尤其难受，他明明刚在 KernelSU 里点了拒绝，回来又弹一遍。</p>
     */
    private static volatile int sRootState = -1;
    private static volatile boolean sLsposed;
    private static volatile String sNote = "";
    private static volatile long sAt;

    private Capability() {
    }

    /** 作废缓存，让下一次 {@link #probe} 真的去 fork su（「重新检测」按钮用）。 */
    public static void invalidate() {
        sAt = 0L;
        sRootState = -1;
    }

    /** su 是否已授权（等价于旧版的 root 布尔值） */
    public boolean root() {
        return rootState == ROOT_GRANTED;
    }

    /**
     * 探测全部能力。会 fork su，必须在 IO 线程调用。
     */
    public static Capability probe(Context ctx) {
        Capability c = new Capability();

        long now = System.currentTimeMillis();
        // 已授权缓存 60s（环境不会突变）；未授权只缓存 10s（用户随时可能去点允许）
        long ttl = sRootState == ROOT_GRANTED ? 60_000L : 10_000L;
        if (sAt != 0L && now - sAt < ttl) {
            c.rootState = sRootState;
            c.lsposedPresent = sLsposed;
            c.note = sNote;
        } else {
            // 一次 su 把「uid + LSPosed 痕迹」全查回来：分开查要 fork 两次，
            // 每次 100ms~1s，而这段代码就挂在首屏卡片的渲染路径上。
            String out = ConfStore.execSuCapture(rootProbeScript(), 8_000L);
            if (out != null) {
                c.rootState = parseUid(out);
            } else {
                // execSuCapture 返回 null 的两种情况无法区分（su 不存在 / 被拒绝），
                // 用一个不依赖授权的旁证分开：这些路径普通应用也能 stat 到存在性。
                c.rootState = suBinaryLikelyPresent() ? ROOT_DENIED : ROOT_NONE;
            }
            if (c.rootState == ROOT_GRANTED) {
                c.lsposedPresent = parseLsposed(out);
                if (!c.lsposedPresent) {
                    c.note = "su 已授权，但 /data/adb 下没有 LSPosed 模块目录";
                }
            } else {
                c.lsposedPresent = lsposedViaPackage(ctx);
                c.note = c.rootState == ROOT_DENIED
                        ? "su 被拒绝，申请后可重新检测"
                        : "";
            }
            // 模块被注入到自身进程时（用户手动勾选了本应用），即使读不到 /data/adb 也能确认
            if (!c.lsposedPresent && xposedBridgeLoadable()) {
                c.lsposedPresent = true;
            }
            sRootState = c.rootState;
            sLsposed = c.lsposedPresent;
            sNote = c.note;
            sAt = now;
        }

        c.shizukuInstalled = isPackageInstalled(ctx, "moe.shizuku.privileged.api");
        c.shizukuReady = c.shizukuInstalled && ShizukuBridge.isBinderAlive();
        c.shizukuGranted = c.shizukuReady && ShizukuBridge.hasPermission();
        c.a11yEnabled = a11yEnabled(ctx);
        return c;
    }

    /**
     * 一条 su 命令做完 root 判定与 LSPosed 痕迹采集。
     *
     * <p>分段用 {@code ==AB==} 而不是换行：目录、module.prop 路径、包名都是多行输出，
     * 混在一起无法分辨哪一行属于哪一段。三个痕迹来源也刻意都留着 ——
     * 有的 ROM 只留模块目录、有的把 Manager 换成了独立包、有的两者都清掉只剩 lspd。</p>
     */
    public static String rootProbeScript() {
        return "id -u 2>/dev/null; echo ==AB==;"
                // 1) LSPosed 运行时目录（Zygisk/Riru 版共用）
                + " ls -d /data/adb/lspd 2>/dev/null;"
                // 2) 模块目录：模块 ID 在各大分支里不统一（lsposed / zygisk_lsposed /
                //    lsposed_zygisk / JingMatrix 的 lsposed_legacy），用通配兜住
                + " ls -d /data/adb/modules/*lsp* 2>/dev/null;"
                // 3) 模块 ID 被用户改过时，读 module.prop 的 name 行兜底
                + " grep -l -i 'lsposed' /data/adb/modules/*/module.prop 2>/dev/null;"
                // 4) Manager 以独立应用存在（非寄生模式）
                + " ls -d /data/app/*/org.lsposed.manager* 2>/dev/null;"
                + " echo ==AB==; echo end";
    }

    /** 解析脚本第一段：{@code id -u} 的输出。public 仅为让 JVM 回归测试能直接钉住解析结果，不对外承诺 API。 */
    public static int parseUid(String out) {
        int cut = out.indexOf("==AB==");
        String head = cut >= 0 ? out.substring(0, cut) : out;
        for (String line : head.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            // id -u 正常只输出一行 0；有的 su 会先打一行提示，所以逐行找
            if ("0".equals(t)) {
                return ROOT_GRANTED;
            }
            if (t.matches("\\d+")) {
                return ROOT_NONE;
            }
        }
        return ROOT_NONE;
    }

    /** 解析第二段：任一痕迹命中即认为装了 LSPosed。public 仅为让 JVM 回归测试能直接钉住解析结果，不对外承诺 API。 */
    public static boolean parseLsposed(String out) {
        int a = out.indexOf("==AB==");
        if (a < 0) {
            return false;
        }
        int b = out.indexOf("==AB==", a + 6);
        String body = b > a ? out.substring(a + 6, b) : out.substring(a + 6);
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String low = t.toLowerCase(java.util.Locale.ROOT);
            // 注意 lspd：LSPosed 的运行时目录叫 /data/adb/lspd，名字里没有 "lsposed"。
            // 只匹配 "lsposed" 会漏掉这条最关键的痕迹（回归测试抓到过一次）。
            if (low.contains("lsposed") || low.contains("lspatch")
                    || low.equals("/data/adb/lspd") || low.startsWith("/data/adb/lspd/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 不依赖 su 的旁证：su 二进制存在，说明这台机器大概率装过 root 方案。
     *
     * <p>只用来区分「su 被拒绝」与「根本没有 su」，不做授权判定的替代 ——
     * 二进制在但没授权时仍然不能用，界面照旧要走「去授权」而不是「可以用」。</p>
     */
    private static boolean suBinaryLikelyPresent() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
                // KernelSU / APatch 把管理端放在这里；目录存在即说明有 root 方案
                "/data/adb/ksu", "/data/adb/ap", "/data/adb/magisk",
        };
        for (String p : paths) {
            try {
                if (new File(p).exists()) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 个别 ROM 对受限路径抛 SecurityException，当作不存在
            }
        }
        return false;
    }

    /** 包管理器里能不能看到 LSPosed Manager（寄生模式下看不到，只是补充证据）。 */
    private static boolean lsposedViaPackage(Context ctx) {
        return isPackageInstalled(ctx, "org.lsposed.manager")
                || isPackageInstalled(ctx, "org.lsposed.lspatch")
                || isPackageInstalled(ctx, "org.lsposed.lspatch.loader");
    }

    private static boolean xposedBridgeLoadable() {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isPackageInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 本应用的无障碍服务是否已开启。
     *
     * <p>直接读 Secure 设置而不是问 {@code AccessibilityManager}：后者返回的是
     * 「系统认为已启用」的列表，在服务被 ROM 杀掉但设置没清空时仍为 true，
     * 而我们要回答的是「用户/系统配置上有没有开」，读设置才是那个答案。
     * 实际是否连上另有 {@link BurnInMaskService#connected} 判断。</p>
     */
    public static boolean a11yEnabled(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) {
                return false;
            }
            String comp = ShizukuBridge.serviceComponent(ctx);
            for (String item : enabled.split(":")) {
                if (comp.equalsIgnoreCase(item.trim())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "read a11y setting failed", t);
            return false;
        }
    }

    /** Hook 引擎是否值得推荐：su 已授权且存在 LSPosed。 */
    public boolean hookUsable() {
        return rootState == ROOT_GRANTED && lsposedPresent;
    }

    /** 一行中文摘要，直接显示在运行方式卡片上。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        switch (rootState) {
            case ROOT_GRANTED:
                sb.append("已 root");
                break;
            case ROOT_DENIED:
                sb.append("root 未授权");
                break;
            default:
                sb.append("无 root");
                break;
        }
        if (lsposedPresent) {
            sb.append(" · 已装 LSPosed");
        }
        if (shizukuGranted) {
            sb.append(" · Shizuku 已授权");
        } else if (shizukuReady) {
            sb.append(" · Shizuku 未授权");
        } else if (shizukuInstalled) {
            sb.append(" · Shizuku 未运行");
        }
        sb.append(a11yEnabled ? " · 无障碍已开" : " · 无障碍未开");
        return sb.toString();
    }
}
