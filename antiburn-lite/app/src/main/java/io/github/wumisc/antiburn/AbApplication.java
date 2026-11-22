package io.github.wumisc.antiburn;

import android.app.Application;

/**
 * 应用入口：尽早初始化 ConfStore 的应用内镜像路径，并装全局崩溃兜底。
 * Activity / TileService / ContentProvider / 无障碍服务都可能在 Application 之外被拉起，
 * 这里统一兜底，保证任何进程第一次写配置时镜像一定可写。
 *
 * <p><b>v2.8.1 崩溃可观测</b>：无障碍服务跑在本进程，一旦未捕获异常，系统只有 logcat
 * 能看到堆栈，而用户能导出的 antiburn.log 里一条都没有 —— 前几轮「无障碍崩溃」
 * 判不了根因就卡在这里。现在 UEH 把完整堆栈（含 Caused by）写进日志文件，
 * 另存一份到应用内部目录，下次启动自动回放，确保即使共享目录当时不可写也不丢。</p>
 */
public final class AbApplication extends Application {

    private static final String PENDING_CRASH = "pending_crash.log";

    @Override
    public void onCreate() {
        installCrashHandler();
        replayPendingCrash();
        super.onCreate();
        try {
            ConfStore.init(this);
        } catch (Throwable ignored) {
        }
        // 旧版本（只写 /data/local/tmp）升级后没有应用内镜像，
        // 会导致 ConfigProvider 取不到配置、Hook 侧跑默认值。启动时后台补齐一次。
        try {
            ConfStore.migrateIfNeededAsync();
        } catch (Throwable ignored) {
        }
        // v3.0.11 P1：覆盖安装会按 Manifest 重置 android:enabled，可能让已禁用的 alias 复活；
        // 这里读独立 prefs 幂等校正。组件校正失败不阻断启动（用户仍可从 LSPosed 进入后手动切开关恢复）。
        try {
            AppVisibilityStore.syncOnStartup(this);
        } catch (Throwable ignored) {
        }
        // v3.0.33：装完新版自动把四个视频 App 杀掉，逼 LSPosed 重新注入新 dex。
        try {
            restartTargetAppsOnce();
        } catch (Throwable ignored) {
        }
    }

    /**
     * v3.0.33：每个版本只做一次 —— 用 root 把抖音 / 红果 / 快手 / B站的进程杀掉。
     *
     * <p><b>这是「改了十几个版本，快手还是老样子」的真正解法。</b>
     * LSPosed 只在进程启动时注入一次：用户覆盖安装 APK 后，快手后台进程一直活着，
     * 里面跑的还是上一个版本的 dex。真机日志实锤 —— {@code injAge=kuaishou=68m}、
     * {@code HOOK-STALE app_ver=3.0.17}，也就是 3.0.18 到 3.0.32 的所有改动
     * 对快手一次都没生效过。用户被要求「重启手机」，但没人会为一个模块天天重启。</p>
     *
     * <p>这里改成：打开模块 App 就自动杀一次（按 versionName 判重，同版本不重复杀）。
     * 用户装完点开模块 → 后台已杀干净 → 再打开快手就是新代码，不需要重启手机。</p>
     *
     * <p>非 root / 未授权时 execSu 返回 false，静默跳过，不影响任何主流程。</p>
     */
    private void restartTargetAppsOnce() {
        final String ver = BuildConfig.VERSION_NAME;
        android.content.SharedPreferences sp =
                getSharedPreferences("antiburn_app", MODE_PRIVATE);
        if (ver.equals(sp.getString("killed_for", ""))) {
            return;      // 这个版本已经杀过，别每次启动都打断用户
        }
        sp.edit().putString("killed_for", ver).apply();
        new Thread(() -> {
            try {
                String list = ConfStore.execSuCapture("pm list packages", 8_000L);
                if (list == null) {
                    Diag.force("KILL ver=" + ver + " 跳过：无 root 或授权被拒");
                    return;
                }
                StringBuilder killed = new StringBuilder();
                for (String line : list.split("\n")) {
                    String p = line.trim();
                    if (!p.startsWith("package:")) {
                        continue;
                    }
                    p = p.substring("package:".length()).trim();
                    if (VideoApps.of(p) == VideoApps.Kind.NONE) {
                        continue;
                    }
                    if (ConfStore.execSu("am force-stop " + p)) {
                        killed.append(p).append(' ');
                    }
                }
                Diag.force("KILL ver=" + ver + " " + killed);
            } catch (Throwable ignored) {
            }
        }, "ab-restart-targets").start();
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            writeCrash(thread, ex);
            // 不吞崩溃：交回系统默认处理，让进程按正常流程退出（吞掉会变成僵死 ANR）
            if (prev != null) {
                prev.uncaughtException(thread, ex);
            }
        });
    }

    /** 崩溃时：尽力写共享日志 + 必写应用内部 pending 文件。全程 try/catch，绝不二次抛出。 */
    private void writeCrash(Thread thread, Throwable ex) {
        String text = formatCrash(thread, ex);
        // v3.0.25：崩溃当场就尝试 su 追加（旧版直写分区存储必失败，等于没写）
        try {
            ConfStore.appendRawLine(text);
        } catch (Throwable ignored) {
        }
        try {
            java.io.File dir = getFilesDir();
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(
                        new java.io.File(dir, PENDING_CRASH), false)) {
                    fo.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 下次启动把上次崩溃回放进主日志（用户导出即可看到），成功才删除 pending。 */
    private void replayPendingCrash() {
        try {
            java.io.File dir = getFilesDir();
            if (dir == null) {
                return;
            }
            java.io.File pend = new java.io.File(dir, PENDING_CRASH);
            if (!pend.exists() || pend.length() <= 0) {
                return;
            }
            byte[] buf = new byte[(int) pend.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(pend)) {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            }
            String replay = System.currentTimeMillis()
                    + " [app/" + android.os.Process.myPid()
                    + "] CRASH-REPLAY 上次进程崩溃堆栈（回放）\n"
                    + new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            // v3.0.25：旧版这里直接 FileOutputStream 写 /sdcard/Android/...，
            // 分区存储下必然失败，可失败后照样 pend.delete()，于是崩溃堆栈永远回放不出来
            // —— 用户反复闪退，日志里一条 CRASH 都没有。改走 su 追加，只有真写成功才删。
            if (ConfStore.appendRawLine(replay)) {
                pend.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String formatCrash(Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder(1024);
        try {
            sb.append(System.currentTimeMillis())
                    .append(" [app/").append(android.os.Process.myPid()).append(']')
                    .append(" FATAL thread=").append(thread == null ? "?" : thread.getName())
                    .append(" ").append(ex == null ? "unknown" : ex.getClass().getName());
            if (ex != null && ex.getMessage() != null) {
                sb.append(": ").append(ex.getMessage());
            }
            sb.append('\n');
            if (ex != null) {
                StackTraceElement[] st = ex.getStackTrace();
                for (int i = 0; st != null && i < st.length && i < 40; i++) {
                    sb.append("  at ").append(st[i]).append('\n');
                }
                Throwable cause = ex.getCause();
                int depth = 0;
                while (cause != null && depth++ < 4) {
                    sb.append("  Caused by: ").append(cause.getClass().getName());
                    if (cause.getMessage() != null) {
                        sb.append(": ").append(cause.getMessage());
                    }
                    sb.append('\n');
                    StackTraceElement[] cs = cause.getStackTrace();
                    for (int i = 0; cs != null && i < cs.length && i < 12; i++) {
                        sb.append("    at ").append(cs[i]).append('\n');
                    }
                    cause = cause.getCause();
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }
}
