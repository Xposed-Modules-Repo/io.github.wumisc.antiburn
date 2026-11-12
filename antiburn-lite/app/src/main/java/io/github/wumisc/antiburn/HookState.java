package io.github.wumisc.antiburn;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

/**
 * 判断「系统界面里正在跑的 Hook 是不是当前这一版」。
 *
 * <p>要解决的问题只有一个：本模块的代码是注入到系统界面（SystemUI）进程里跑的，
 * 而 SystemUI 是常驻进程 —— 装包、覆盖安装都不会让它重启。用户装完新版打开 App，
 * 看到的界面已经是新版，但真正执行隐藏逻辑的还是几分钟前甚至几天前加载的旧 dex。
 * 典型表现是「新加的开关点了没反应」「改的文案在日志里还是老的」，而 App 界面一切正常，
 * 用户只能以为功能坏了。</p>
 *
 * <p>判定方法是纯粹的时间比较：SystemUI 进程的启动时刻 vs 本 APK 的安装/更新时刻。
 * 进程比 APK 老，就说明它加载的是旧包。这个方法不需要读 logcat、不需要 root 之外
 * 的任何特殊权限，也不会被「用户没开日志」影响。</p>
 *
 * <p>只在 root 已授权时才能探测：读别人的 /proc 目录需要 root。</p>
 */
public final class HookState {

    private static final String TAG = "AntiburnHookState";

    /** 探不到（没 root、拿不到进程、读不到安装时间）：不显示任何提示，避免误报警 */
    public static final int UNKNOWN = 0;
    /** 进程比 APK 新：SystemUI 里跑的确实是这一版 */
    public static final int FRESH = 1;
    /** 进程比 APK 老：SystemUI 里跑的是旧版，新装的改动不会生效 */
    public static final int STALE = 2;

    /**
     * 判定容差。
     *
     * <p>{@code stat -c %Y} 只给到秒，{@code lastUpdateTime} 给到毫秒；再加上「装完立刻
     * 重启」时两个动作可能落在同一秒，留 5 秒余量，避免把刚重启好的状态误报成旧版。</p>
     */
    static final long TOLERANCE_MS = 5_000L;

    /** 探测结果缓存时长。ON_RESUME 每次都会重探，没有缓存会让切一次界面 fork 一次 su。 */
    private static final long CACHE_MS = 30_000L;

    public int state = UNKNOWN;
    /** SystemUI 主进程 pid；0 表示没探到 */
    public int pid = 0;
    /** 本 APK 的安装/更新时间（毫秒） */
    public long apkUpdateMs = 0L;
    /** SystemUI 进程启动时间（毫秒） */
    public long procStartMs = 0L;
    /** 给界面直接展示的原因，只在 UNKNOWN 时有内容 */
    public String note = "";

    private static volatile HookState sLast;
    private static volatile long sAt;

    private HookState() {
    }

    /** 作废缓存。「重新检测」按钮用。 */
    public static void invalidate() {
        sAt = 0L;
        sLast = null;
    }

    public boolean stale() {
        return state == STALE;
    }

    /** 系统界面已经跑了多久（毫秒）。探不到时返回 0。 */
    public long runningForMs() {
        if (procStartMs <= 0L) {
            return 0L;
        }
        long d = System.currentTimeMillis() - procStartMs;
        return d > 0L ? d : 0L;
    }

    public static HookState probe(Context ctx) {
        long now = System.currentTimeMillis();
        HookState cached = sLast;
        if (cached != null && sAt != 0L && now - sAt < CACHE_MS) {
            return cached;
        }

        HookState s = new HookState();
        s.apkUpdateMs = apkUpdateTime(ctx);
        if (s.apkUpdateMs <= 0L) {
            s.note = "读不到本应用的安装时间";
            sAt = now;
            sLast = s;
            return s;
        }

        String out = ConfStore.execSuCapture(probeScript(), 8_000L);
        long[] hit = parse(out);
        if (hit == null) {
            // 有 root 但拿不到 SystemUI 进程：可能是 ROM 改了进程名（部分厂商改成
            // com.miui.systemui 之类），也可能是 su 这一侧被 SELinux 挡住。
            // 不猜，按「探不到」处理，界面不提示。
            s.note = "没找到系统界面进程";
            sAt = now;
            sLast = s;
            return s;
        }
        s.pid = (int) hit[0];
        s.procStartMs = hit[1] * 1000L;
        s.state = decide(s.apkUpdateMs, s.procStartMs);
        sAt = now;
        sLast = s;
        return s;
    }

    /** 读本 APK 的安装/更新时间。取不到返回 0。 */
    static long apkUpdateTime(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.lastUpdateTime;
        } catch (Throwable t) {
            Log.w(TAG, "read lastUpdateTime failed", t);
            return 0L;
        }
    }

    /**
     * 判定：进程启动晚于 APK 更新时间就是新版。
     *
     * <p>两个时间都来自同一个系统时钟，所以即使用户改过时间，相对先后关系仍然成立。</p>
     */
    /** public 仅为让 JVM 回归测试能直接钉住判定结果，不对外承诺 API。 */
    public static int decide(long apkUpdateMs, long procStartMs) {
        if (apkUpdateMs <= 0L || procStartMs <= 0L) {
            return UNKNOWN;
        }
        return procStartMs + TOLERANCE_MS >= apkUpdateMs ? FRESH : STALE;
    }

    // ---- 探测脚本 ----

    /** 分段标记：脚本输出里用它切段，避免多行文本互相污染 */
    /** public 仅为让 JVM 回归测试能直接钉住解析结果，不对外承诺 API。 */
    public static final String SEP = "==HS==";

    /**
     * 一次 su 拿到「所有 systemui 进程的 pid + 启动时刻」。
     *
     * <p>先试 {@code ps -A -o PID,ARGS}（Android 8+ 的 toybox 支持），失败再退回裸
     * {@code ps -A}。不用遍历 {@code /proc/<pid>} 逐个 grep —— 那样每个进程 fork 一次，
     * 几百个进程要跑好几秒，而这段代码挂在首屏卡片的路径上。</p>
     *
     * <p>{@code stat -c %Y /proc/<pid>} 取的是进程目录的修改时间，即进程创建时刻。
     * 这在 Linux 上成立且不需要读 /proc/<pid>/stat 里的 clock ticks 再换算 uptime。</p>
     */
    public static String probeScript() {
        return "pids=$(ps -A -o PID,ARGS 2>/dev/null | grep -i 'com\\.android\\.systemui' | awk '{print $1}');"
                + " if [ -z \"$pids\" ]; then"
                + " pids=$(ps -A 2>/dev/null | grep -i 'com\\.android\\.systemui' | awk '{print $1}');"
                + " fi;"
                + " echo '" + SEP + "';"
                + " for p in $pids; do echo \"$p $(stat -c %Y /proc/$p 2>/dev/null)\"; done;"
                + " echo '" + SEP + "';"
                + " echo ab-hs-done";
    }

    /**
     * 解析探测输出，返回 {@code {pid, 启动秒}}；拿不到返回 null。
     *
     * <p>同一时刻通常只有主进程和（可能的）子进程两行，取启动最早的那个：子进程由主进程
     * fork 出来，最早的一定是主进程。子进程的启动时间比主进程晚，用主进程判断更保守 ——
     * 主进程是新的，子进程必然也是新的。</p>
     */
    public static long[] parse(String out) {
        if (out == null || out.isEmpty()) {
            return null;
        }
        long bestPid = 0L;
        long bestSec = 0L;
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.contains(SEP) || line.startsWith("ab-hs")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            long pid;
            long sec;
            try {
                pid = Long.parseLong(parts[0].trim());
                sec = Long.parseLong(parts[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid <= 0L || sec <= 0L) {
                continue;
            }
            if (bestSec == 0L || sec < bestSec) {
                bestSec = sec;
                bestPid = pid;
            }
        }
        return bestPid == 0L ? null : new long[]{bestPid, bestSec};
    }

    /**
     * 重启系统界面，让新装的 dex 被重新加载。
     *
     * <p>返回内容含 {@code ab-restart-ok} 即命令已执行；SystemUI 由系统自动拉起，
     * 通常 2~5 秒后恢复，期间屏幕会黑一下、通知栏消失，属正常。</p>
     */
    public static String restartSystemUi() {
        String cmd = "pids=$(ps -A -o PID,ARGS 2>/dev/null | grep -i 'com\\.android\\.systemui' | awk '{print $1}');"
                + " if [ -z \"$pids\" ]; then"
                + " pids=$(ps -A 2>/dev/null | grep -i 'com\\.android\\.systemui' | awk '{print $1}');"
                + " fi;"
                + " for p in $pids; do kill -9 \"$p\" 2>/dev/null; done;"
                + " echo ab-restart-ok";
        return ConfStore.execSuCapture(cmd, 15_000L);
    }
}
