package io.github.wumisc.antiburn;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Set;

/**
 * 配置读取（Hook 侧，运行在 SystemUI / 抖音 / 红果进程内）。
 *
 * 配置格式：/data/local/tmp/antiburn.conf，一行一个 key=value。
 *
 * 关键修复：旧版直接 FileReader 读 /data/local/tmp，但 SELinux 禁止 appdomain
 * 读 shell_data_file，导致 reload() 永远失败、模块始终跑默认值（"保存了没效果"）。
 * 现在按以下顺序取配置，且全部在主线程之外异步预取，避免 binder/su 阻塞 SystemUI：
 *   1) 直接读 conf（在允许的设备上最快，且兼容外部手工改配置）
 *   2) ConfigProvider（应用内镜像，绝大多数机型的实际生效通道）
 *   3) su -c cat（30s 节流，兼容 root 允许被 hook 进程调用的设备）
 * 另外：缺失的 key 保留上一次的值，不再被"重置成默认值"；
 * custom_poems 支持 \n 转义的多行值，并兼容旧版直接写多行的脏配置。
 */
public final class Config {

    public static final String CONF = ConfStore.CONF;

    public static volatile boolean enableStatus = true;
    public static volatile boolean enableNav = false;
    /**
     * 像素偏移（防烧屏的温和模式）。默认开启：它视觉无感，且与「隐藏」模式互补 ——
     * 隐藏降低点亮时间，偏移则把轮廓分摊到相邻像素，两者叠加才是完整防护。
     * 默认值与旧配置兼容：老用户 conf 里没有 enable_shift 时沿用此默认 true。
     */
    public static volatile boolean enableShift = true;
    public static volatile boolean enableDouyinTop = false;
    public static volatile boolean enableDouyinBottom = false;
    public static volatile boolean enableDouyinLike = false;
    public static volatile boolean enableDouyinRt = false;
    public static volatile boolean enableDouyinLt = false;
    public static volatile boolean enableDramaTop = false;
    public static volatile boolean enableDramaBottom = false;
    public static volatile boolean enableDramaLike = false;
    public static volatile boolean enableDramaRt = false;
    public static volatile boolean enableDramaLt = false;
    public static volatile boolean enableLive = true;
    public static volatile boolean enablePoem = false;
    public static volatile boolean enableBall = false;
    public static volatile String customPoems = "";
    public static volatile boolean enableIcon = false;
    public static volatile boolean enableLog = false;
    /** 目标 UI 适配方案（conf 里的 ui_style，与 profiles/*.json 对应） */
    public static volatile String uiStyle = "aosp";
    public static volatile int hideSec = 30;
    public static volatile int showSec = 10;
    public static volatile Set<String> whitelist = new HashSet<>();

    private static long lastRead = 0;
    private static String lastText = null;
    private static volatile Context hostContext;
    /** 只来自 ConfigProvider 的权威配置；文件 / su 兜底绝不写进这里 */
    private static volatile String remoteText;
    /** 文件 / su 兜底通道读到的配置，与 provider 严格分开，避免旧副本被当成权威值 */
    private static volatile String directText;
    /** 预取通道状态，写进 CONF apply 日志便于定位 provider 为何不通 */
    public static volatile String fetchState = "init";
    private static volatile boolean fetcherStarted = false;
    private static volatile long lastSuTry = 0;
    /** provider 的 Binder 调用可能永久阻塞：同一时刻只允许一个在途调用 */
    private static final java.util.concurrent.atomic.AtomicBoolean callBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 超时后仍卡在 Binder 里的线程数上限，避免每轮新开导致 daemon 线程无限累积 */
    private static final java.util.concurrent.atomic.AtomicInteger liveCalls =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_LIVE_CALLS = 3;
    private static volatile long lastCallReset = 0L;
    /** 已经生效的配置版本号（来自 conf 的 rev=），用于拒绝旧副本回滚新值 */
    private static volatile long appliedRev = 0L;
    /**
     * v2.5：appliedRev 以前只在进程内存里，SystemUI 一重启就归零，于是冷启动首帧
     * 无论多旧的 /data/local/tmp 副本都会被当成「第一份配置」直接应用（真机日志
     * 19:06:47 的 `src=conf rev=1794393553914`，那是 28 分钟前的值）。
     * 现在把已生效 rev 落到 SystemUI 自己的 files 目录，attach() 时读回来做初值。
     */
    private static volatile File revFile;
    private static volatile boolean revLoaded;
    /** SystemUI 自己的配置快照：provider 冷启动要几秒，这段时间靠它保证不读到旧值 */
    private static volatile String cacheText;
    /** 上次尝试读快照的时间：读失败要允许重试，不能像旧版 cacheLoaded 一次性闭锁 */
    private static volatile long lastCacheRead;
    private static volatile File cacheFile;
    /** 上次打印「拒绝过期配置」的时间，避免每 2 秒刷一条 */
    private static volatile long lastRejectLog;
    /** 当前生效配置来自哪个通道：provider / cache / conf */
    public static volatile String lastSource = "-";
    public static volatile long lastRev = 0L;

    private Config() {
    }

    /** 由 Controller / DouyinHook 在拿到 View 后调用，用于 ContentResolver 跨进程读取。 */
    public static void attach(Context ctx) {
        if (ctx == null) {
            return;
        }
        if (cacheFile == null) {
            cacheFile = prepareCacheFile(ctx);
        }
        if (!revLoaded) {
            revLoaded = true;
            long persisted = readPersistedRev();
            if (persisted > 0L && persisted > appliedRev) {
                appliedRev = persisted;
            }
        }
        if (hostContext != null) {
            return;
        }
        Context a = ctx.getApplicationContext();
        hostContext = (a != null) ? a : ctx;
    }

    /**
     * 配置快照文件放在 SystemUI 自己的数据目录里。
     *
     * <p>为什么需要它：provider 调用会冷启动模块 App 进程，HyperOS 上实测要 5~15 秒。
     * 这段时间旧版只能去读 /data/local/tmp/antiburn.conf，而那是 su 异步同步的副本，
     * 可能停在几天前的版本 —— 表现就是「重启 SystemUI 后总开关自己变回关闭」。
     * SystemUI 以 system uid 运行，写自己的 files 目录没有任何 SELinux 阻力，
     * 于是把「上次真正生效的配置」落在本地，冷启动窗口内先用它，绝不会回退到旧值。</p>
     */
    private static File prepareCacheFile(Context ctx) {
        try {
            File dir = ctx.getFilesDir();
            if (dir == null) {
                return null;
            }
            // v2.4：不再用 myUid() == SYSTEM_UID 硬判断。国产 ROM 的 SystemUI
            // 未必跑在 uid 1000（真机日志里该判断让快照 5.8 小时都没建立）。
            // 快照只是「写自己进程的 files 目录」，任何进程写自己的那份都安全，
            // 且配置自带 rev，旧快照不会覆盖新值。改成真实写探针即可。
            File probe = new File(dir, "antiburn-cache.probe");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(probe, false)) {
                fo.write(new byte[]{1});
                fo.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            revFile = new File(dir, "antiburn-applied.rev");
            return new File(dir, "antiburn-cache.conf");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 后台预取：provider 读取与 su 兜底都不放在主线程执行。 */
    private static void ensureFetcher() {
        if (fetcherStarted) {
            return;
        }
        synchronized (Config.class) {
            if (fetcherStarted) {
                return;
            }
            fetcherStarted = true;
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        fetchRemote();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(1500);
                    } catch (Throwable ignored) {
                        return;
                    }
                }
            }, "antiburn-conf-fetcher");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * 取一次配置，分别更新 provider 通道与文件兜底通道。
     *
     * <p>v2.4 关键修复：ContentResolver.call() 在 HyperOS 上可能永久阻塞
     * （provider 进程冷启动 / 被冻结），旧实现没有超时，一次卡住就让整个预取线程
     * 再也不循环 —— 真机日志实测 5.8 小时内 2585 次读取全部落到 /data/local/tmp
     * 的 su 副本。现在把 call 放进独立线程并限时 2.5 秒，超时就走文件兜底，
     * 预取线程永远活着。同时文件兜底只写 directText，绝不覆盖 remoteText，
     * 避免把滞后的旧副本误当成 provider 的权威值。</p>
     */
    private static void fetchRemote() {
        Context ctx = hostContext;
        if (ctx == null) {
            ctx = reflectApplication();
        }
        String state;
        long now = System.currentTimeMillis();

        // 5 分钟才强制放开一次，且卡住的线程累计到 MAX_LIVE_CALLS 就彻底不再新建：
        // 旧版每 60 秒无条件放开，会每 60 秒泄漏一个永久阻塞的 daemon 线程。
        if (now - lastCallReset > 300000L && liveCalls.get() < MAX_LIVE_CALLS) {
            lastCallReset = now;
            callBusy.set(false);
        }

        if (ctx == null) {
            state = "no-context";
        } else if (liveCalls.get() >= MAX_LIVE_CALLS) {
            state = "provider-exhausted";
        } else if (callBusy.compareAndSet(false, true)) {
            final Context c = ctx;
            final String[] out = new String[1];
            liveCalls.incrementAndGet();
            Thread call = new Thread(() -> {
                try {
                    Bundle req = new Bundle();
                    req.putString(ConfigProvider.KEY_TOKEN, ConfigProvider.TOKEN);
                    Bundle b = c.getContentResolver().call(
                            Uri.parse("content://" + ConfigProvider.AUTHORITY),
                            ConfigProvider.METHOD_GET, null, req);
                    if (b != null) {
                        out[0] = b.getString(ConfigProvider.KEY_CONF);
                    }
                } catch (Throwable ignored) {
                } finally {
                    callBusy.set(false);
                    liveCalls.decrementAndGet();
                }
            }, "antiburn-provider-call-" + liveCalls.get());
            call.setDaemon(true);
            call.start();
            try {
                call.join(2500L);
            } catch (Throwable ignored) {
            }
            if (out[0] != null && !out[0].trim().isEmpty()) {
                remoteText = out[0];
                state = "provider";
            } else {
                state = call.isAlive() ? "provider-timeout" : "provider-empty";
                if (call.isAlive()) {
                    // 给 Binder 一个中断机会；不响应也无妨，liveCalls 会拦住后续新建
                    call.interrupt();
                }
            }
        } else {
            state = "provider-busy";
        }

        String direct = readDirect();
        if (direct != null && !direct.trim().isEmpty()) {
            directText = direct;
            state += "+direct";
        } else {
            // 低频尝试 su cat（避免每 1.5s fork 一次 su）
            if (now - lastSuTry > 30000L) {
                lastSuTry = now;
                String s = ConfStore.execSuCapture("cat " + CONF + " 2>/dev/null");
                if (s == null || s.trim().isEmpty()) {
                    s = ConfStore.execSuCapture(
                            "cat /data/user/0/io.github.wumisc.antiburn/files/antiburn.conf 2>/dev/null"
                                    + " || cat /data/data/io.github.wumisc.antiburn/files/antiburn.conf 2>/dev/null");
                }
                if (s != null && !s.trim().isEmpty()) {
                    directText = s;
                    state += "+su";
                }
            }
        }
        fetchState = state;
    }

    private static Context reflectApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                attach((Context) app);
                return hostContext;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> g = Class.forName("android.app.AppGlobals");
            Object app = g.getMethod("getInitialApplication").invoke(null);
            if (app instanceof Context) {
                attach((Context) app);
                return hostContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 直接读文件（在后台线程或短耗时路径调用）。
     * /data/local/tmp 常被 SELinux 挡住，因此再兜底读公共存储镜像。
     */
    private static String readDirect() {
        String[] paths = {
                CONF,
                ConfStore.EXTERNAL_MIRROR,
                "/storage/emulated/0/Download/.antiburn.conf",
        };
        for (String path : paths) {
            try {
                File f = new File(path);
                if (!f.exists() || f.length() <= 0 || f.length() > 65536) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 重读配置。多通道按「版本号」仲裁，而不是按固定优先级。
     *
     * <p>旧逻辑是 provider 拿不到就直接读文件 —— 而文件是 su 异步同步的副本，
     * SystemUI 刚启动、模块 App 进程还没被 provider 唤醒的那几秒里，读到的是几天前的值，
     * 于是总开关先被设成「关」，等 provider 终于响应才改回「开」。
     * 现在每份配置都带 rev（写入时间戳），只接受 rev 不低于已生效版本的那份，
     * 旧副本无论来自哪个通道都直接丢弃。</p>
     */
    public static synchronized void reload() {
        long now = System.currentTimeMillis();
        if (now - lastRead < 700) {
            return;
        }
        lastRead = now;
        ensureFetcher();

        String provider = remoteText;
        String cache = cacheText();
        String best = null;
        String src = null;
        long rev = Long.MIN_VALUE;

        if (provider != null && !provider.trim().isEmpty()) {
            best = provider;
            src = "provider";
            rev = ConfStore.revOf(provider);
        }
        if (cache != null && !cache.trim().isEmpty()) {
            long r = ConfStore.revOf(cache);
            // 快照比 provider 更新时用快照（provider 背后可能是被回滚过的旧镜像）
            if (best == null || r > rev) {
                best = cache;
                src = "cache";
                rev = r;
            }
        }
        if (best == null) {
            // provider 与快照都还没有（首次安装 / 冷启动窗口）时用文件兜底。
            // 优先复用后台线程已读好的 directText，避免主线程再做一次文件 IO。
            String direct = directText;
            if (direct == null || direct.trim().isEmpty()) {
                direct = readDirect();
            }
            if (direct != null && !direct.trim().isEmpty()) {
                best = direct;
                src = "conf";
                rev = ConfStore.revOf(direct);
            }
        }
        if (best == null) {
            return; // 全通道失败：保留现有值，绝不回退默认值
        }
        if (rev < appliedRev) {
            // 手上这份比已经生效的更旧：不解析、不覆盖，等更新的版本来。
            // appliedRev 现在由 SystemUI 本地文件持久化，冷启动首帧的旧 su 副本
            // 会被这里直接挡掉，不再出现「重启后先按几天前的配置跑一阵」。
            long t = System.currentTimeMillis();
            if (t - lastRejectLog > 30000L) {
                lastRejectLog = t;
                XposedInit.diagForce("CONF reject stale src=" + src + " rev=" + rev
                        + " applied=" + appliedRev + " fetch=" + fetchState);
            }
            return;
        }
        if (rev == appliedRev && best.equals(lastText)) {
            return;
        }
        boolean newVersion = rev > appliedRev;
        parse(best);
        lastText = best;
        appliedRev = rev;
        lastSource = src;
        lastRev = rev;
        writePersistedRev(rev);
        // 只允许 provider 来源刷新快照。旧版把 conf 兜底也写进快照，等于把滞后的
        // su 副本「洗白」成本地权威值，冷启动会一直读它。
        if ("provider".equals(src)) {
            writeCache(best);
        }
        // 用户改了配置就给熔断一次重试机会：否则某 ROM 上偶发 3 次异常后
        // 本进程内像素偏移永久停用，改设置也救不回来。
        if (newVersion) {
            FeatureGuard.resetAll();
        }
        XposedInit.diagForce("CONF apply src=" + src + " rev=" + rev
                + " status=" + enableStatus + " nav=" + enableNav
                + " shift=" + enableShift + " ui=" + uiStyle
                + " wl=" + whitelist.size() + " fetch=" + fetchState
                + " cache=" + (cacheText != null ? "ok" : "none"));
    }

    /** 读回上次真正生效的 rev；文件不存在 / 损坏都当 0（首次安装）。 */
    private static long readPersistedRev() {
        File f = revFile;
        if (f == null || !f.exists()) {
            return 0L;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            return line == null ? 0L : Long.parseLong(line.trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 原子写回已生效 rev，失败不影响主流程。 */
    private static void writePersistedRev(long rev) {
        File f = revFile;
        if (f == null) {
            return;
        }
        try {
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp, false)) {
                fo.write(Long.toString(rev).getBytes("UTF-8"));
                fo.flush();
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(f);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读配置快照。
     *
     * <p>v2.5：去掉旧版 `cacheLoaded` 一次性门闩 —— 首次 attach 时写探针若临时失败，
     * 后续 attach 重试成功也不会再读文件，冷启动兜底快照永久缺失。
     * 改成「内存已有就直接返回；否则每 3 秒最多重试一次」。</p>
     */
    private static String cacheText() {
        if (cacheText != null) {
            return cacheText;
        }
        long now = System.currentTimeMillis();
        if (now - lastCacheRead < 3000L) {
            return null;
        }
        lastCacheRead = now;
        File f = cacheFile;
        if (f != null && f.exists() && f.length() > 0 && f.length() <= 65536) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                if (sb.length() > 0) {
                    cacheText = sb.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return cacheText;
    }

    /** 原子写快照（.tmp + rename），失败不影响主流程。 */
    private static void writeCache(String text) {
        File f = cacheFile;
        if (f == null) {
            return;
        }
        try {
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp, false)) {
                fo.write(text.getBytes("UTF-8"));
                fo.flush();
                fo.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(f);
            }
            cacheText = text;
        } catch (Throwable ignored) {
        }
    }

    /** 供 UI/诊断用：快照是否已经建立。 */
    public static boolean cacheReady() {
        return cacheText != null;
    }

    private static void parse(String text) {
        // 缺失的 key 沿用上次的值（而不是被硬编码默认值覆盖），旧配置升级时不会丢开关
        boolean es = enableStatus, en = enableNav;
        boolean sh = enableShift;
        boolean dtop = enableDouyinTop, dbot = enableDouyinBottom, dlike = enableDouyinLike;
        boolean drt = enableDouyinRt, dlt = enableDouyinLt;
        boolean drTop = enableDramaTop, drBot = enableDramaBottom, drLike = enableDramaLike;
        boolean drRt = enableDramaRt, drLt = enableDramaLt;
        boolean live = enableLive, poem = enablePoem, ball = enableBall;
        boolean ic = enableIcon, lg = enableLog;
        String style = uiStyle;
        String custom = customPoems;
        int h = hideSec, s = showSec;
        // whitelist 优先；仅当它不存在时才用旧别名 blacklist，与 App 侧保持一致
        boolean whitePresent = false;
        boolean blackPresent = false;
        Set<String> wl = new HashSet<>();
        Set<String> wlLegacy = new HashSet<>();
        // 兼容旧版：custom_poems 曾被直接写成多行，后续行没有 '='，这里回收成诗句
        StringBuilder orphan = new StringBuilder();
        String customDecoded = null;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int i = line.indexOf('=');
            if (i < 0) {
                if (customDecoded != null) {
                    if (orphan.length() > 0) {
                        orphan.append('\n');
                    }
                    orphan.append(line);
                }
                continue;
            }
            String k = line.substring(0, i).trim();
            String v = line.substring(i + 1).trim();
            switch (k) {
                case "enable_status": es = "1".equals(v); break;
                case "enable_shift": sh = "1".equals(v); break;
                case "enable_navigation": en = "1".equals(v); break;
                case "ui_style": style = normStyle(v); break;
                case "enable_douyin_top": dtop = "1".equals(v); break;
                case "enable_douyin_bottom": dbot = "1".equals(v); break;
                case "enable_douyin_like": dlike = "1".equals(v); break;
                case "enable_douyin_right_top": drt = "1".equals(v); break;
                case "enable_douyin_left_top": dlt = "1".equals(v); break;
                case "enable_drama_top": drTop = "1".equals(v); break;
                case "enable_drama_bottom": drBot = "1".equals(v); break;
                case "enable_drama_like": drLike = "1".equals(v); break;
                case "enable_drama_right_top": drRt = "1".equals(v); break;
                case "enable_drama_left_top": drLt = "1".equals(v); break;
                case "enable_live": live = "1".equals(v); break;
                case "enable_poem": poem = "1".equals(v); break;
                case "enable_ball": ball = "1".equals(v); break;
                case "enable_icon": ic = "1".equals(v); break;
                case "enable_log": lg = "1".equals(v); break;
                case "custom_poems":
                    customDecoded = ConfStore.decodeValue(v);
                    break;
                case "hide_duration": h = parseInt(v, h); break;
                case "show_duration": s = parseInt(v, s); break;
                case "whitelist":
                    whitePresent = true;
                    for (String p : v.split(",")) {
                        p = p.trim();
                        if (!p.isEmpty()) {
                            wl.add(p);
                        }
                    }
                    break;
                case "blacklist":
                    blackPresent = true;
                    for (String p : v.split(",")) {
                        p = p.trim();
                        if (!p.isEmpty()) {
                            wlLegacy.add(p);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        if (customDecoded != null) {
            custom = orphan.length() > 0
                    ? customDecoded + "\n" + orphan.toString()
                    : customDecoded;
        }

        enableStatus = es;
        enableNav = en;
        enableShift = sh;
        enableDouyinTop = dtop;
        enableDouyinBottom = dbot;
        enableDouyinLike = dlike;
        enableDouyinRt = drt;
        enableDouyinLt = dlt;
        enableDramaTop = drTop;
        enableDramaBottom = drBot;
        enableDramaLike = drLike;
        enableDramaRt = drRt;
        enableDramaLt = drLt;
        enableLive = live;
        enablePoem = poem;
        enableBall = ball;
        enableIcon = ic;
        enableLog = lg;
        uiStyle = style;
        customPoems = custom;
        // 与 App 侧 coerceIn 保持一致，防止外部写入超大值让周期变成几十小时
        hideSec = Math.min(3600, Math.max(1, h));
        showSec = Math.min(3600, Math.max(1, s));
        // 配置里没写白名单行时保持原值，不因一次解析把已有名单清空
        if (whitePresent) {
            whitelist = wl;
        } else if (blackPresent) {
            whitelist = wlLegacy;
        }
        // 日志开关关闭时不输出（原来无条件 Log.i 会把白名单/前台包名写进 logcat）
        if (enableLog) {
            Log.i(XposedInit.TAG, "config reloaded: status=" + es + " nav=" + en
                    + " dyTop=" + dtop + " dyBottom=" + dbot + " dyLike=" + dlike
                    + " live=" + live + " poem=" + poem + " ball=" + ball
                    + " hide=" + hideSec + "s show=" + showSec + "s whitelist=" + whitelist);
        }
    }

    /** 未知的 ui_style 一律归到 aosp，避免外部写入脏值把适配逻辑带偏 */
    private static String normStyle(String v) {
        String s = (v == null) ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
        switch (s) {
            case "hyperos":
            case "magicos":
            case "coloros":
            case "originos":
            case "harmonyos":
            case "aosp":
                return s;
            default:
                return "aosp";
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
