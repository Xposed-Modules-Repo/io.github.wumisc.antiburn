package io.github.wumisc.antiburn;

import android.os.Process;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口
 *  - com.android.systemui：状态栏 / 导航栏周期透明化
 *  - com.ss.android.ugc.aweme（抖音）：视频页各区域周期透明化
 *
 * v2.0.1 日志修复：
 *  1. 旧版 diag() 第一行就是 `if (!Config.enableLog) return;`，而 Config.enableLog 默认 false、
 *     又依赖配置链路正常工作 —— 一旦「保存不住」读不到配置，日志就一条都没有，
 *     连「模块到底有没有注入」都无法判断。现在关键事件走 diagForce()，不受开关影响。
 *  2. 旧版只会写 /sdcard/Download。SystemUI（system uid）能写，抖音/红果是普通 app，
 *     Android 10+ 分区存储必然失败，退回 Log 后 logcat 又被限流，等于没有日志。
 *     现在探测一次可写路径并缓存；非 system 进程再经 su 低频落盘到 /data/local/tmp。
 *  3. 日志文件加 512KB 上限，超限自动轮转为 .1，避免长期开启撑爆存储。
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "Antiburn";

    /** 单个诊断日志上限，超过后轮转为 .1 重新写，避免常年开启撑爆存储 */
    private static final long MAX_LOG_BYTES = 512 * 1024L;
    /**
     * 诊断日志候选路径，按优先级探测。
     *
     * <p>首选用户指定的 /storage/emulated/0/Android/Prevent burn-in/：这个目录 App 侧
     * 会用 su 预建并放开权限，SystemUI 能直接写；随后是 /data/local/tmp 兜底目录。
     * 注意这里存的是「目录」，具体文件名在 {@link #writeNow} 里拼。</p>
     */
    private static final String[] LOG_PATHS = {
            ConfStore.LOG_DIR + "/antiburn.log",
            "/sdcard/Android/Prevent burn-in/antiburn.log",
            ConfStore.LOG_DIR_FALLBACK + "/antiburn.log",
            "/data/local/tmp/antiburn_diag.log",
    };
    /** 探测失败后的冷却时间：过了这段时间允许重探（App 可能刚把目录建好） */
    private static final long PROBE_RETRY_MS = 30_000L;
    /** 非 system 进程经 su 落盘的最小间隔，避免每次 tick 都 fork 一个 su */
    private static final long SU_WRITE_INTERVAL_MS = 10_000L;

    private static volatile String logPath;
    private static volatile boolean probed = false;
    /** 上次探测全部失败的时间戳；超过冷却期允许重探（App 可能刚建好目录） */
    private static volatile long probeFailAt = 0L;
    private static volatile long lastSuWrite = 0L;

    /**
     * 写日志用的单线程：Controller.tick() 跑在 SystemUI 主线程，旧版 diag() 直接同步 fopen/fwrite，
     * 等于每 5 秒在主线程做一次文件 IO。改成异步串行写，主线程只入队。
     * 队列上限 512 条，超出直接丢弃（诊断日志不值得为它拖垮 SystemUI）。
     */
    private static final java.util.concurrent.ExecutorService LOG_WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "antiburn-diag-writer");
                t.setDaemon(true);
                return t;
            });
    private static final java.util.concurrent.atomic.AtomicInteger QUEUED =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * 关键事件（注入成功 / 初始化异常）：始终落盘，不受「诊断日志」开关控制。
     * 这是判断「模块有没有被注入」的唯一依据，不能被配置链路故障连带弄丢。
     */
    public static void diagForce(String s) {
        write(s);
    }

    /**
     * 详细日志（周期状态 / 白名单命中 / 控件捕获）：受设置界面「诊断日志」开关控制。
     */
    public static void diag(String s) {
        try {
            if (!Config.enableLog) {
                return;
            }
        } catch (Throwable ignored) {
            // Config 未初始化时按开启处理
        }
        write(s);
    }

    private static void write(String s) {
        if (QUEUED.get() > 512) {
            return; // 写盘严重滞后时丢弃，绝不阻塞调用线程
        }
        // 线程名必须在调用点取，异步写线程里拿到的只有 "antiburn-diag-writer"
        final String origin = Process.myPid() + "/" + Thread.currentThread().getName();
        QUEUED.incrementAndGet();
        try {
            LOG_WRITER.execute(() -> {
                try {
                    writeNow(origin, s);
                } finally {
                    QUEUED.decrementAndGet();
                }
            });
        } catch (Throwable t) {
            QUEUED.decrementAndGet();
            Log.i(TAG, "DIAG " + s);
        }
    }

    private static void writeNow(String origin, String s) {
        String line = System.currentTimeMillis() + " [" + origin + "] " + s + "\n";
        String path = logPath();
        if (path != null) {
            try {
                File f = new File(path);
                if (f.length() > MAX_LOG_BYTES) {
                    File old = new File(path + ".1");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(old);
                }
                try (FileOutputStream fo = new FileOutputStream(f, true)) {
                    fo.write(line.getBytes(StandardCharsets.UTF_8));
                }
                return;
            } catch (Throwable ignored) {
                // 路径突然不可写（存储卸载 / 权限变更）时落到下面的兜底
            }
        }
        Log.i(TAG, "DIAG " + s);
        // 抖音 / 红果是普通 app，写不了 /sdcard；用 root 通道低频补一条，
        // 保证「打开开关后仍然没有任何日志」这种事不再发生。
        if (Process.myUid() != Process.SYSTEM_UID) {
            long now = System.currentTimeMillis();
            if (now - lastSuWrite > SU_WRITE_INTERVAL_MS) {
                lastSuWrite = now;
                String b64 = Base64.encodeToString(
                        line.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                // 两个目录都补一份：兜底目录保证一定有，用户目录方便直接取
                ConfStore.execSu("mkdir -p \"" + ConfStore.LOG_DIR_FALLBACK + "\" \""
                        + ConfStore.LOG_DIR + "\" 2>/dev/null; "
                        + "echo " + b64 + " | base64 -d >> \"" + ConfStore.LOG_DIR_FALLBACK
                        + "/antiburn.log\" 2>/dev/null; "
                        + "echo " + b64 + " | base64 -d >> \"" + ConfStore.LOG_FILE
                        + "\" 2>/dev/null; true");
            }
        }
    }

    /**
     * 探测可写日志路径并缓存。
     *
     * <p>不能用 canWrite() 或「能打开就算可写」：/sdcard 走 FUSE，权限判定延迟到真正
     * write 时才报 EPERM，SystemUI 又是 platform_app，很容易出现「打开成功、写入失败」。
     * 所以这里真的写一行探测内容，写得进去才采用。</p>
     *
     * <p>探测失败不是永久结论：App 可能在 SystemUI 之后才把目录建好，冷却一段时间后重探。</p>
     */
    private static String logPath() {
        if (probed) {
            if (logPath != null) {
                return logPath;
            }
            if (System.currentTimeMillis() - probeFailAt < PROBE_RETRY_MS) {
                return null;
            }
        }
        synchronized (XposedInit.class) {
            if (probed && logPath != null) {
                return logPath;
            }
            if (probed && System.currentTimeMillis() - probeFailAt < PROBE_RETRY_MS) {
                return null;
            }
            String hit = null;
            for (String p : LOG_PATHS) {
                try {
                    File f = new File(p);
                    File parent = f.getParentFile();
                    if (parent == null || !parent.exists()) {
                        continue;
                    }
                    byte[] probe = ("# antiburn probe " + System.currentTimeMillis() + "\n")
                            .getBytes(StandardCharsets.UTF_8);
                    try (FileOutputStream fo = new FileOutputStream(f, true)) {
                        fo.write(probe);
                        fo.flush();
                    }
                    hit = p;
                    break;
                } catch (Throwable ignored) {
                    // 下一个候选
                }
            }
            logPath = hit;
            probed = true;
            if (hit == null) {
                probeFailAt = System.currentTimeMillis();
            }
            return hit;
        }
    }

    /**
     * 供 Hook 侧写自定义诊断文件（如直播页 View 树）。
     * 旧版 DouyinHook.writeTreeFile() 直接写 /sdcard，抖音进程没有存储权限，文件从来不会生成。
     * 这里按「用户目录 → 兜底目录」顺序尝试，失败再经 su 写入。
     */
    public static boolean writeSharedFile(String name, String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String[] paths = {
                ConfStore.LOG_DIR + "/" + name,
                ConfStore.LOG_DIR_FALLBACK + "/" + name,
                "/data/local/tmp/" + name,
        };
        for (String p : paths) {
            try {
                try (FileOutputStream fo = new FileOutputStream(p, false)) {
                    fo.write(data);
                }
                return true;
            } catch (Throwable ignored) {
            }
        }
        String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
        // execSu 返回 boolean（进程是否成功退出），不要再和 null 比较
        return ConfStore.execSu("mkdir -p \"" + ConfStore.LOG_DIR_FALLBACK + "\" \"" + ConfStore.LOG_DIR
                + "\" 2>/dev/null; echo " + b64 + " | base64 -d > \"" + ConfStore.LOG_DIR + "/" + name
                + "\" 2>/dev/null || echo " + b64 + " | base64 -d > /data/local/tmp/" + name);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        String proc = lpparam.processName;
        if ("com.android.systemui".equals(pkg)) {
            diagForce("INJECTED SystemUI process=" + proc);
            Log.i(TAG, "loaded into SystemUI, starting...");
            try {
                Controller.init(lpparam);
            } catch (Throwable t) {
                Log.e(TAG, "init failed", t);
                diagForce("INIT FAILED SystemUI: " + Log.getStackTraceString(t));
            }
        } else if ("com.ss.android.ugc.aweme".equals(pkg)) {
            diagForce("INJECTED Douyin process=" + proc);
            Log.i(TAG, "loaded into Douyin, starting...");
            try {
                DouyinHook.init(lpparam);
            } catch (Throwable t) {
                Log.e(TAG, "douyin init failed", t);
                diagForce("INIT FAILED Douyin: " + Log.getStackTraceString(t));
            }
        } else if ("com.phoenix.read".equals(pkg) || "com.phoenix.read.oversea.gp".equals(pkg)) {
            // 红果短剧（抖音旗下，国内版 com.phoenix.read / 谷歌版 com.phoenix.read.oversea.gp）：
            // 复用抖音同一套视频页各区域隐藏逻辑
            diagForce("INJECTED Drama process=" + proc);
            Log.i(TAG, "loaded into Hongguo Drama, starting...");
            try {
                DouyinHook.init(lpparam);
            } catch (Throwable t) {
                Log.e(TAG, "drama init failed", t);
                diagForce("INIT FAILED Drama: " + Log.getStackTraceString(t));
            }
        }
    }
}
