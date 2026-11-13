package io.github.wumisc.antiburn;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 抖音 / 红果直播间临时暂停信号。
 *
 * <p>App 进程负责识别直播页并写信号，SystemUI 只读取与当前前台包匹配的内容。信号带墙钟时间戳，
 * 15 秒无心跳自动失效，防止宿主异常退出后系统栏永久停在恢复状态。此状态不写入用户配置，
 * 退出直播后原有开关与周期相位自然继续。</p>
 *
 * <p><b>v2.7.3 关键约束</b>：读写全部放到后台守护线程，主线程只在内存缓存上取值。
 * 原因：v2.7.2 在本模块的 tick（主线程）里同步读写共享目录，路径不可写时还会退到
 * {@code su}，而宿主进程（抖音）并没有被授权 su —— 一次卡住就足以拖垮整轮 tick，
 * 真机表现正是「进直播间再退出后隐藏彻底不工作」。这里同时去掉 su 兜底：
 * 写不进去就放弃并记一次失败，等下个心跳再试，绝不在主线程 fork 进程。</p>
 */
public final class LivePause {
    static final String FILE_NAME = "antiburn_live.state";
    static final long TTL_MS = 15000L;
    private static final long HEARTBEAT_MS = 5000L;
    /** 读缓存刷新间隔：SystemUI 每轮 tick 只读内存，最多每秒回源一次。 */
    private static final long REFRESH_MS = 1000L;
    private static final int MAX_BYTES = 256;

    /** 共享路径：宿主进程能写、SystemUI 能读的交集，按可靠性排序。 */
    private static final String[] PATHS = {
            "/data/local/tmp/" + FILE_NAME,
            ConfStore.LOG_DIR_FALLBACK + "/" + FILE_NAME,
            ConfStore.LOG_DIR + "/" + FILE_NAME,
    };

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "antiburn-live-io");
        t.setDaemon(true);
        return t;
    });

    // ---------- 发布侧（App 进程） ----------
    private static volatile boolean published = false;
    private static volatile boolean lastActive = false;
    private static volatile long lastPublishAt = 0L;
    /** 三个路径都写不进去：本进程内不再反复尝试，避免每 5 秒空转一次。 */
    private static volatile boolean writeBroken = false;

    // ---------- 读取侧（SystemUI 进程） ----------
    private static volatile String cachedPkg = "";
    private static volatile boolean cachedValue = false;
    private static volatile long cachedAt = 0L;
    private static volatile long refreshAt = 0L;

    private LivePause() {
    }

    static boolean supports(String pkg) {
        return "com.ss.android.ugc.aweme".equals(pkg)
                || "com.phoenix.read".equals(pkg)
                || "com.phoenix.read.oversea.gp".equals(pkg);
    }

    /** 纯函数：编码指定应用的直播状态；active=false 写时间戳 0，立即撤销。 */
    static String encode(String pkg, boolean active, long nowMs) {
        return (pkg == null ? "" : pkg.trim()) + "\n" + (active ? nowMs : 0L) + "\n";
    }

    /** 纯函数：仅当前前台包匹配、时间戳有效且未过期的信号才暂停。 */
    static boolean isActive(String text, String topPkg, long nowMs) {
        if (text == null || !supports(topPkg)) {
            return false;
        }
        try {
            String[] lines = text.trim().split("\\n");
            if (lines.length < 2 || !topPkg.equals(lines[0].trim())) {
                return false;
            }
            long ts = Long.parseLong(lines[1].trim());
            return ts > 0L && nowMs >= ts && nowMs - ts <= TTL_MS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * App 侧发布：状态变化立即写，直播期间每 5 秒心跳，退出时写 0 立即恢复。
     *
     * <p>只在内存里做节流判断，真正的写盘交给后台线程 —— 调用方通常在主线程 tick 内。</p>
     */
    static void publish(String pkg, boolean active) {
        if (!supports(pkg)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (published && active == lastActive
                && (!active || now - lastPublishAt < HEARTBEAT_MS)) {
            return;
        }
        published = true;
        lastActive = active;
        lastPublishAt = now;
        final String content = encode(pkg, active, now);
        submit(() -> writeDirect(content));
    }

    /** 后台直写：不走 su、不阻塞调用线程；全部失败就标记并放弃本轮。 */
    static boolean writeDirect(String content) {
        if (writeBroken) {
            return false;
        }
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        for (String path : PATHS) {
            try (FileOutputStream out = new FileOutputStream(path, false)) {
                out.write(data);
                return true;
            } catch (Throwable ignored) {
                // 换下一个共享路径
            }
        }
        writeBroken = true;
        return false;
    }

    /** 同步读（保留给测试与一次性诊断；tick 内请用 {@link #readCached(String)}）。 */
    static boolean readActive(String topPkg, long nowMs) {
        if (!supports(topPkg)) {
            return false;
        }
        return isActive(readText(), topPkg, nowMs);
    }

    /** 读三个共享路径，返回首个存在的有效内容；读不到返回空串。 */
    static String readText() {
        for (String path : PATHS) {
            File f = new File(path);
            try {
                if (!f.isFile() || f.length() <= 0 || f.length() > MAX_BYTES) {
                    continue;
                }
                try (FileInputStream in = new FileInputStream(f)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[MAX_BYTES];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
                // 换下一个共享路径
            }
        }
        return "";
    }

    /**
     * SystemUI 侧读取：只取内存缓存，最多每秒投递一次后台回源。
     * 主线程永不因权限、磁盘或宿主异常而卡住。
     */
    static boolean readCached(String topPkg) {
        if (!supports(topPkg)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - refreshAt >= REFRESH_MS) {
            refreshAt = now;
            final String want = topPkg;
            submit(() -> {
                boolean v = isActive(readText(), want, System.currentTimeMillis());
                cachedPkg = want;
                cachedValue = v;
                cachedAt = System.currentTimeMillis();
            });
        }
        if (topPkg == null || !topPkg.equals(cachedPkg)) {
            return false;
        }
        return cachedValue && System.currentTimeMillis() - cachedAt <= TTL_MS;
    }

    private static void submit(Runnable task) {
        try {
            IO.execute(() -> {
                try {
                    task.run();
                } catch (Throwable ignored) {
                    // 后台任务异常绝不影响调用方
                }
            });
        } catch (Throwable ignored) {
            // 线程池已关闭 / 拒绝：按无信号处理
        }
    }
}
