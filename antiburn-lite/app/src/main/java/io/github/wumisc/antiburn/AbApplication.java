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
        try {
            java.io.File f = new java.io.File(ConfStore.LOG_FILE);
            java.io.File parent = f.getParentFile();
            if (parent != null && (parent.exists() || parent.mkdirs())) {
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true)) {
                    fo.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
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
            java.io.File f = new java.io.File(ConfStore.LOG_FILE);
            java.io.File parent = f.getParentFile();
            if (parent != null && (parent.exists() || parent.mkdirs())) {
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true)) {
                    fo.write(replay.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
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
