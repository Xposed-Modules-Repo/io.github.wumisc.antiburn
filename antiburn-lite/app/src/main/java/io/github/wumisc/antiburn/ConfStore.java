package io.github.wumisc.antiburn;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置存储（v1.7-fixed）。
 *
 * 原版问题：
 *  1. 写入是「echo b64 | base64 -d > /data/local/tmp/antiburn.conf」——先清空目标文件再写，
 *     su 中途失败/超时就会留下一个空文件，所有开关丢失；多个保存并发时还会互相覆盖。
 *  2. 只写 /data/local/tmp。SystemUI / 抖音进程被 SELinux 挡住读 shell_data_file，
 *     Config.reload() 永远读不到，表现为「设置保存了但完全不生效」。
 *  3. 白名单页在 conf 尚不存在时直接失败，第一次用永远保存不了。
 *  4. 自定义诗句是多行文本，直接写进「一行一个 key」的 conf 会把后续内容切成非法行。
 *
 * 修复：
 *  - 每次写入同时落一份「应用内镜像」files/antiburn.conf（无需 root，永久可读），
 *    由 ConfigProvider 暴露给被 hook 的进程；root 通道继续保留，兼容外部直接改 conf。
 *  - root 写入改为「写 .tmp → mv 原子替换」，失败不破坏原文件。
 *  - 所有改写走 updateEx()：保留未知 key 与注释，不再整表重建导致其它设置丢失。
 *  - 提供默认配置，conf 不存在时自动生成，白名单/磁贴首次点击即可保存。
 */
public final class ConfStore {

    public static final String CONF = "/data/local/tmp/antiburn.conf";
    private static final String CONF_TMP = CONF + ".tmp";
    private static final String MIRROR_NAME = "antiburn.conf";
    private static final String MIRROR_TMP_NAME = "antiburn.conf.tmp";
    /**
     * 公共存储镜像：Android 11+ 包可见性可能让被 hook 的第三方 App 解析不到导出的
     * ContentProvider，所以额外落一份纯文本到 Download（隐藏文件），hook 侧可直接 FileReader。
     */
    public static final String EXTERNAL_MIRROR = "/sdcard/Download/.antiburn.conf";

    /**
     * 日志目录：公共存储下用户能直接看到的位置（文件管理器 / 数据线 / 电脑都能取）。
     * Android 11+ 普通应用无权在 /sdcard/Android/ 下自建目录，只能由 App 侧用 su 预建，
     * 之后 SystemUI(system uid) 才能写入 —— 具体见 XposedInit 的路径探测。
     */
    public static final String LOG_DIR = "/storage/emulated/0/Android/Prevent burn-in";
    public static final String LOG_FILE = LOG_DIR + "/antiburn.log";
    /** 兜底目录：/data/local/tmp 下自建子目录并放开权限，保证 system uid 总有地方写 */
    public static final String LOG_DIR_FALLBACK = "/data/local/tmp/antiburn";

    /** root 写入成功且读回校验通过 */
    public static final int WRITE_OK = 0;
    /** root 不可用/写入失败，但应用内镜像已保存（Hook 侧通过 ConfigProvider 仍可生效） */
    public static final int WRITE_MIRROR_ONLY = 1;
    /** 镜像也写不进去，彻底失败 */
    public static final int WRITE_FAILED = 2;

    private static volatile Context appCtx;
    private static final Object LOCK = new Object();

    /**
     * root / 外部镜像的同步队列。
     *
     * <p>旧实现把 3 次 su（写 conf、读回校验、写外部镜像）全部放在 save 的同步路径里，
     * 每次 su 最多等 4s，最坏一次保存要阻塞 12s。而 su 在 KernelSU / Magisk 上
     * 每次都要走授权通道，本就慢。用户点一下开关就卡住，于是「保存不住」的投诉里
     * 有一半其实是「保存没完成就被杀了」。
     *
     * <p>关键事实：应用内镜像才是唯一权威来源（Hook 侧经 ConfigProvider 读它），
     * root conf 与外部镜像只是给「外部工具/第三方进程」的兼容副本。
     * 既然是副本，就不该阻塞用户操作。
     *
     * <p>因此改为：保存 = 写镜像（毫秒级，磁记录级同步），su 通道交给这里的
     * 单线程 worker 合并执行 —— 连续点 10 个开关只产生 1 次 su，且永远只写最新内容。
     */
    private static final ExecutorService SYNC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "antiburn-conf-sync");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<String> pendingSync = new AtomicReference<>();
    private static final AtomicBoolean syncQueued = new AtomicBoolean(false);
    /** root 通道最后一次同步结果，供界面显示真实状态（不再影响「保存成功」判定）。 */
    private static volatile String lastSyncState = "未同步";
    private static volatile long rootCheckedAt;
    private static volatile boolean rootCached;

    private ConfStore() {
    }

    /** 必须在应用进程入口调用（AbApplication / Activity / TileService / Provider 都会调）。 */
    public static void init(Context ctx) {
        if (ctx == null) {
            return;
        }
        Context a = ctx.getApplicationContext();
        appCtx = (a != null) ? a : ctx;
    }

    private static File mirrorFile() {
        Context c = appCtx;
        return c == null ? null : new File(c.getFilesDir(), MIRROR_NAME);
    }

    private static File mirrorTmpFile() {
        Context c = appCtx;
        return c == null ? null : new File(c.getFilesDir(), MIRROR_TMP_NAME);
    }

    /** 默认配置：与 AbState / Config 的默认值保持一致。 */
    public static String defaultContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("enable_status=1\n");
        sb.append("enable_shift=1\n");
        sb.append("enable_navigation=0\n");
        sb.append("enable_poem=0\n");
        sb.append("enable_ball=0\n");
        sb.append("enable_log=0\n");
        sb.append("enable_icon=0\n");
        sb.append("ui_style=aosp\n");
        sb.append("enable_douyin_top=0\n");
        sb.append("enable_douyin_bottom=0\n");
        sb.append("enable_douyin_like=0\n");
        sb.append("enable_douyin_right_top=0\n");
        sb.append("enable_douyin_left_top=0\n");
        sb.append("enable_live=1\n");
        sb.append("enable_drama_top=0\n");
        sb.append("enable_drama_bottom=0\n");
        sb.append("enable_drama_like=0\n");
        sb.append("enable_drama_right_top=0\n");
        sb.append("enable_drama_left_top=0\n");
        sb.append("hide_duration=30\n");
        sb.append("show_duration=10\n");
        sb.append("whitelist=\n");
        return sb.toString();
    }

    /** 读应用内镜像（无 root、无 IPC，瞬时完成）。 */
    public static String readMirror() {
        File f = mirrorFile();
        if (f == null || !f.exists() || f.length() <= 0) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return s.trim().isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只写应用内镜像（供 ConfigProvider 把旧 conf 同步到镜像）。 */
    public static boolean writeMirrorOnly(String content) {
        if (content == null) {
            return false;
        }
        synchronized (LOCK) {
            return writeMirror(content);
        }
    }

    private static boolean writeMirror(String content) {
        File tmp = mirrorTmpFile();
        File dst = mirrorFile();
        if (tmp == null || dst == null) {
            return false;
        }
        try {
            try (FileOutputStream fo = new FileOutputStream(tmp, false)) {
                fo.write(content.getBytes(StandardCharsets.UTF_8));
                fo.flush();
                fo.getFD().sync();
            }
            if (tmp.renameTo(dst)) {
                return true;
            }
            // 极少数文件系统 rename 覆盖失败：删除后重试
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            return tmp.renameTo(dst);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 读取配置全文：以应用内镜像为唯一权威来源（App 的每次保存都会同时更新它），
     * 这样 UI / 磁贴 / 白名单页 / Hook 侧看到的是同一份内容。
     * 镜像不存在时（从旧版本升级、首次安装）才回退到 su 读 conf；迁移由
     * migrateIfNeededAsync() 在应用启动时完成。外部手工改 /data/local/tmp 不再被读取。
     */
    public static String read() {
        String mirror = readMirror();
        if (mirror != null && !mirror.trim().isEmpty()) {
            return mirror;
        }
        String out = execSuCapture("cat " + CONF + " 2>/dev/null");
        return (out != null && !out.trim().isEmpty()) ? out : null;
    }

    /** 兼容旧调用：是否「至少保存成功」。 */
    public static boolean write(String content) {
        return writeEx(content) != WRITE_FAILED;
    }

    /**
     * 原子写入配置全文。返回 WRITE_OK / WRITE_FAILED。
     *
     * <p>语义变更（v2.1）：返回 WRITE_OK 表示「已保存且下个周期必然生效」，
     * 不再要求 root 写入成功 —— 因为生效链路走的是应用内镜像 + ConfigProvider，
     * 与 /data/local/tmp 无关。root 副本由后台线程同步，失败只影响外部工具读取，
     * 不影响模块自身功能，因此不再让用户为此看到一条吓人的失败提示。
     *
     * <p>性能：写入路径只有一次本地文件写 + fsync，没有 su、没有 IPC、没有回读校验
     * （回读校验原本是为了确认 su 写成功，现在镜像写完即成功，无需校验）。
     */
    public static int writeEx(String content) {
        if (content == null) {
            return WRITE_FAILED;
        }
        // 每次写入都盖一个单调递增的版本号：Hook 侧（SystemUI）用它判断
        // 「手上这份配置是不是比我已经生效的那份更旧」，彻底堵死「重启后先读到旧副本」
        // 导致总开关自己变回关闭的问题。
        final String stamped = stampRev(content);
        synchronized (LOCK) {
            if (writeMirror(stamped)) {
                // 镜像已落盘 = 保存成功。副本同步异步进行，绝不阻塞调用线程。
                scheduleSync(stamped);
                appendAudit("WRITE ok rev=" + revOf(stamped)
                        + " status=" + valueOf(stamped, "enable_status")
                        + " by=" + caller());
                return WRITE_OK;
            }
        }
        // 私有目录写不进去（存储隔离 / 空间不足 / 目录被系统清理）：
        // 交给后台 root 通道再救一次，但仍然如实返回失败 ——
        // 界面必须提示「没存住」，而不是让用户下次打开才发现开关又回去了。
        scheduleSync(stamped);
        appendAudit("WRITE fail rev=" + revOf(stamped) + " by=" + caller());
        return WRITE_FAILED;
    }

    /** 在首行写入 rev=<毫秒时间戳>，并去掉旧 rev 行（保持「一个 key 一行」的格式）。 */
    private static String stampRev(String content) {
        StringBuilder sb = new StringBuilder("rev=")
                .append(System.currentTimeMillis())
                .append('\n');
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("rev=") || line.trim().isEmpty()) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 取配置文本里的 rev；老版本没有该键时返回 0（视作最旧）。 */
    public static long revOf(String content) {
        if (content == null) {
            return 0L;
        }
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("rev=")) {
                try {
                    return Long.parseLong(t.substring(4).trim());
                } catch (Throwable ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    /** 取某个 key 的值，取不到返回 "?"。仅用于审计日志。 */
    private static String valueOf(String content, String key) {
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + "=")) {
                return t.substring(key.length() + 1).trim();
            }
        }
        return "?";
    }

    /**
     * 保存审计日志：每次写配置留下「谁写的 / 版本号 / 关键值」。
     *
     * <p>「开关记不住」这类问题靠猜没用 —— 必须能回答「19:31:02 那次 0 是谁写进去的」。
     * 审计文件放在应用私有目录（无 SELinux 麻烦），导出日志时由 su 一并复制到
     * /storage/emulated/0/Android/Prevent burn-in/ 下。</p>
     */
    public static void appendAudit(String s) {
        File f = auditFile();
        if (f == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (f.length() > 64 * 1024L) {
                File old = new File(f.getAbsolutePath() + ".1");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
            }
            try (FileOutputStream fo = new FileOutputStream(f, true)) {
                fo.write((now + " " + s + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File auditFile() {
        Context c = appCtx;
        return c == null ? null : new File(c.getFilesDir(), "save-audit.log");
    }

    /** 找出第一个「非 ConfStore 自己」的调用方，用来区分 App / 磁贴 / 白名单页 / Provider。 */
    private static String caller() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (!cn.equals(ConfStore.class.getName()) && !cn.startsWith("java.")) {
                    String simple = cn.substring(cn.lastIndexOf('.') + 1);
                    return simple + "." + e.getMethodName() + ":" + e.getLineNumber();
                }
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /**
     * 把内容投递给同步 worker。连续多次调用只保留最新一份，worker 会自动合并。
     * 任何异常都被吞掉：副本同步失败不能影响「已保存」这个事实。
     */
    private static void scheduleSync(String content) {
        pendingSync.set(content);
        if (syncQueued.getAndSet(true)) {
            return; // 已有 worker 在跑，它循环时会取到最新内容
        }
        try {
            SYNC.execute(() -> {
                try {
                    while (true) {
                        String c = pendingSync.getAndSet(null);
                        if (c == null) {
                            syncQueued.set(false);
                            // 收尾竞态：清标记的瞬间可能又有人投递，抢回标记继续跑
                            if (pendingSync.get() != null && syncQueued.compareAndSet(false, true)) {
                                continue;
                            }
                            return;
                        }
                        syncToRoot(c);
                    }
                } catch (Throwable t) {
                    syncQueued.set(false);
                    lastSyncState = "同步异常: " + t.getClass().getSimpleName();
                }
            });
        } catch (Throwable t) {
            // 线程池拒绝（极罕见）：放弃副本同步，但保存本身已成功
            syncQueued.set(false);
            lastSyncState = "同步线程不可用";
        }
    }

    /**
     * 把同一份内容写到 root conf 与外部镜像。两条通道合并成「一次 su 调用」，
     * 相比旧实现的 3 次 fork，进程创建开销降到 1/3。
     */
    private static void syncToRoot(String content) {
        String b64 = Base64.encodeToString(
                content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        // 先写 .tmp 再 mv，任一环节失败原文件保持完整；两条通道互不影响，末尾 true 保证退出码为 0
        String cmd = "{ echo '" + b64 + "' | base64 -d > " + CONF_TMP
                + " && chmod 644 " + CONF_TMP
                + " && mv -f " + CONF_TMP + " " + CONF
                + " && chmod 644 " + CONF + "; } 2>/dev/null; "
                + "{ echo '" + b64 + "' | base64 -d > " + EXTERNAL_MIRROR + ".tmp"
                + " && mv -f " + EXTERNAL_MIRROR + ".tmp " + EXTERNAL_MIRROR
                + " && chmod 644 " + EXTERNAL_MIRROR + "; } 2>/dev/null; true";
        boolean ok = execSu(cmd);
        rootCheckedAt = System.currentTimeMillis();
        rootCached = ok;
        lastSyncState = ok ? "已同步 root 副本" : "root 不可用，仅用应用内配置（功能正常）";
    }

    /** 最近一次 root 副本同步的结果描述，供界面展示；不影响功能判定。 */
    public static String syncState() {
        return lastSyncState;
    }

    /**
     * 旧版本升级迁移：旧版只写 /data/local/tmp，没有应用内镜像，
     * 会导致 ConfigProvider 返回 null、模块跑默认值。启动时异步补齐一次。
     * su 会阻塞，必须在后台线程执行。
     */
    public static void migrateIfNeededAsync() {
        if (readMirror() != null) {
            return;
        }
        Thread t = new Thread(() -> {
            String conf = execSuCapture("cat " + CONF + " 2>/dev/null");
            if (conf != null && !conf.trim().isEmpty()) {
                writeMirrorOnly(conf);
            }
        }, "antiburn-conf-migrate");
        t.setDaemon(true);
        t.start();
    }

    /** 原子地取反 enable_status，返回新的 "1"/"0"；失败返回 null。供快捷磁贴使用，避免读改写竞态。 */
    public static String flipStatus() {
        synchronized (LOCK) {
            String conf = read();
            // 旧实现：读不到配置时把 on 当成 true，于是「第一次点磁贴」写进去的是 0，
            // 用户以为点开了、实际是关掉。改为读不到时按「关闭」处理，首次点击必然是开启。
            boolean on = false;
            boolean known = false;
            if (conf != null) {
                for (String line : conf.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("enable_status=")) {
                        on = t.substring(t.indexOf('=') + 1).trim().equals("1");
                        known = true;
                        break;
                    }
                }
            }
            String next = on ? "0" : "1";
            appendAudit("TILE flip from=" + (known ? (on ? "1" : "0") : "unknown") + " to=" + next);
            return update("enable_status", next) ? next : null;
        }
    }

    /**
     * 以「保留其它配置」的方式更新若干 key。
     * updates 中 value 为 null 表示删除该 key（用于清理旧别名 blacklist）。
     * 重复 key 只保留第一次出现的位置，注释行原样保留。
     */
    public static int updateEx(Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return WRITE_FAILED;
        }
        synchronized (LOCK) {
            // 只读镜像，绝不在写入路径上触发 su：首次安装镜像不存在时直接基于默认配置生成，
            // 旧版 conf 的迁移由 migrateIfNeededAsync() 在应用启动后异步完成。
            String content = readMirror();
            if (content == null || content.trim().isEmpty()) {
                content = defaultContent();
            }
            StringBuilder sb = new StringBuilder();
            Set<String> done = new HashSet<>();
            for (String line : content.split("\n", -1)) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.startsWith("#")) {
                    sb.append(line).append('\n');
                    continue;
                }
                int i = t.indexOf('=');
                String key = (i < 0) ? t : t.substring(0, i).trim();
                if (updates.containsKey(key)) {
                    if (done.add(key)) {
                        String v = updates.get(key);
                        if (v != null) {
                            sb.append(key).append('=').append(v).append('\n');
                        }
                    }
                    // 同名重复行 / 旧别名行一律丢弃
                } else {
                    sb.append(line).append('\n');
                }
            }
            for (Map.Entry<String, String> e : updates.entrySet()) {
                if (!done.contains(e.getKey()) && e.getValue() != null) {
                    sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                }
            }
            return writeEx(sb.toString());
        }
    }

    /**
     * 原子更新若干 key，并顺手删掉旧别名（如 blacklist）。
     * 供 UI 侧「只写变化字段」使用：不再整表重建，避免把磁贴/其它页面刚写的值覆盖回去。
     */
    public static int updateExRemoving(Map<String, String> updates, Set<String> removeKeys) {
        Map<String, String> m = new LinkedHashMap<>();
        if (updates != null) {
            m.putAll(updates);
        }
        if (removeKeys != null) {
            for (String k : removeKeys) {
                m.put(k, null); // value=null 在 updateEx 里表示删除该 key
            }
        }
        return updateEx(m);
    }

    /** 更新单个 key。 */
    public static boolean update(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return updateEx(m) != WRITE_FAILED;
    }

    /**
     * 回读校验：确认刚写入的键值真的落在镜像里。
     *
     * <p>这是「显示已保存、实际没保存」的最后一道防线。文件写失败、fsync 没落地、
     * 被其它进程覆盖，都会在这里暴露，而不是等用户下次打开发现开关又变回去了。
     * 返回空集合才允许界面提示保存成功。
     */
    public static Set<String> verify(Map<String, String> expect) {
        Set<String> bad = new HashSet<>();
        if (expect == null || expect.isEmpty()) {
            return bad;
        }
        String content = readMirror();
        if (content == null) {
            bad.addAll(expect.keySet());
            return bad;
        }
        Map<String, String> actual = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int i = t.indexOf('=');
            if (i < 0) {
                continue;
            }
            actual.put(t.substring(0, i).trim(), t.substring(i + 1).trim());
        }
        for (Map.Entry<String, String> e : expect.entrySet()) {
            String want = e.getValue();
            String got = actual.get(e.getKey());
            if (want == null) {
                if (got != null) {
                    bad.add(e.getKey());
                }
            } else if (!want.equals(got)) {
                bad.add(e.getKey());
            }
        }
        return bad;
    }

    /**
     * 准备共享目录（需要 root）：日志要落在用户能直接看到的位置。
     *
     * <p>Android 11+ 普通应用无法在 /sdcard/Android/ 下建目录，只能借 su；
     * 同时把兜底目录设成 0777，保证 SystemUI(system uid) 也有地方可写。</p>
     */
    public static boolean prepareSharedDirs() {
        // 路径含空格，必须整体加引号，否则 shell 会当成两个参数
        String cmd = "mkdir -p \"" + LOG_DIR + "\" \"" + LOG_DIR_FALLBACK + "\" 2>/dev/null; "
                + "chmod 0777 \"" + LOG_DIR + "\" \"" + LOG_DIR_FALLBACK + "\" 2>/dev/null; true";
        return execSu(cmd);
    }

    /**
     * 把兜底目录里的日志汇总到用户指定目录，返回写入结果描述。
     * 用于「SystemUI 没有 /sdcard 写权限、只能写 /data/local/tmp」的设备。
     */
    public static String exportLogs() {
        // 保存审计日志在应用私有目录：root 才能读，一并复制到用户可见目录，
        // 「谁把开关改回去」直接看这一段就够了
        File audit = auditFile();
        String auditCmd = audit == null ? ""
                : "cat \"" + audit.getAbsolutePath() + "\" >> \"" + LOG_FILE + "\" 2>/dev/null; ";
        String cmd = "mkdir -p \"" + LOG_DIR + "\" 2>/dev/null; " + auditCmd
                + "for f in \"" + LOG_DIR_FALLBACK + "\"/*.log; do "
                + "[ -f \"$f\" ] && cat \"$f\" >> \"" + LOG_FILE + "\" 2>/dev/null; done; "
                + "chmod 0666 \"" + LOG_FILE + "\" 2>/dev/null; "
                + "ls -l \"" + LOG_FILE + "\" 2>/dev/null; true";
        String out = execSuCapture(cmd);
        return out == null ? "" : out.trim();
    }

    /**
     * 仅替换白名单行（whitelist=/blacklist=），不动其它配置。
     * conf 不存在时自动生成默认配置，解决「第一次打开白名单页永远保存失败」。
     */
    public static boolean replaceWhitelist(Set<String> whitelist) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("whitelist", join(whitelist));
        m.put("blacklist", null); // 移除旧别名，避免两套名单互相打架
        return updateEx(m) != WRITE_FAILED;
    }

    /**
     * 从配置全文里取白名单：whitelist 优先（即使为空也优先），仅当不存在时才用旧别名 blacklist。
     * 与 AbConf.load() / Config.parse() 的语义保持一致，避免两套名单共存时 UI 与 Hook 各读各的。
     */
    public static String whitelistFrom(String content) {
        if (content == null) {
            return "";
        }
        String whitelist = null;
        String blacklist = null;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("whitelist=")) {
                whitelist = t.substring(t.indexOf('=') + 1).trim();
            } else if (t.startsWith("blacklist=")) {
                blacklist = t.substring(t.indexOf('=') + 1).trim();
            }
        }
        if (whitelist != null) {
            return whitelist;
        }
        return blacklist != null ? blacklist : "";
    }

    /** 解析逗号分隔的白名单文本为去重、去空白的包名集合。 */
    public static Set<String> parseWhitelist(String text) {
        Set<String> set = new LinkedHashSet<>();
        if (text != null) {
            for (String p : text.split(",")) {
                p = p.trim();
                if (!p.isEmpty()) {
                    set.add(p);
                }
            }
        }
        return set;
    }

    private static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (p == null || p.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p.trim());
        }
        return sb.toString();
    }

    /** 多行值编码成单行：\ → \\，换行 → \n（字面两字符）。 */
    public static String encodeValue(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r", "")
                .replace("\\", "\\\\")
                .replace("\n", "\\n");
    }

    /** 与 encodeValue 对应的解码。 */
    public static String decodeValue(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') {
                    out.append('\n');
                } else if (n == '\\') {
                    out.append('\\');
                } else {
                    out.append('\\').append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 检测 root 是否可用（供界面显示状态）。会阻塞，务必在 IO 线程调用。
     * 结果缓存 60s：界面每次重组都 fork 一次 su 是纯浪费，且会拖慢首屏。
     */
    public static boolean hasRoot() {
        long now = System.currentTimeMillis();
        if (rootCheckedAt != 0L && now - rootCheckedAt < 60_000L) {
            return rootCached;
        }
        String uid = execSuCapture("id -u 2>/dev/null");
        boolean ok = uid != null && uid.trim().startsWith("0");
        rootCheckedAt = now;
        rootCached = ok;
        return ok;
    }

    /**
     * 重启 SystemUI，让刚保存的配置立刻重新注入生效。
     * SystemUI 是 persistent 进程，被 kill 后系统会立刻自动拉起，状态栏会短暂重绘。
     *
     * 用 pkill -f 搭配「方括号正则」：命令行里写的是 com[.]android[.]systemui，
     * 正则能匹配进程名 com.android.systemui，但字符串本身不含该明文，
     * 因此不会把执行这条命令的 su shell 自己也杀掉（经典自杀陷阱）。
     * killall 按 comm 匹配（内核截断 15 字符），只作为没有 pkill 的 ROM 的兜底。
     * 不用 am force-stop：会把 SystemUI 置为 stopped 状态，反而可能不自动恢复。
     */
    public static boolean restartSystemUi() {
        String[] cmds = {
                "pkill -f 'com[.]android[.]systemui' 2>/dev/null",
                "killall com.android.systemui 2>/dev/null",
        };
        for (String cmd : cmds) {
            if (execSu(cmd)) {
                return true;
            }
        }
        return false;
    }

    public static boolean execSu(String cmd) {
        return execSuCapture(cmd) != null;
    }

    /**
     * 执行 su 命令并返回 stdout（退出码非 0 / 超时 / 无 root 返回 null）。
     * 独立线程消费 stdout + stderr，避免未读流把 su 进程缓冲区塞满而卡死。
     */
    public static String execSuCapture(String cmd) {
        return runSu("su", cmd);
    }

    private static String runSu(String suPath, String cmd) {
        Process p = null;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            p = Runtime.getRuntime().exec(new String[]{suPath, "-c", cmd});
            final Process fp = p;
            Thread so = new Thread(() -> {
                try (InputStream is = fp.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } catch (Throwable ignore) {
                }
            });
            so.setDaemon(true);
            so.start();
            Thread se = new Thread(() -> {
                try (InputStream is = fp.getErrorStream()) {
                    byte[] buf = new byte[4096];
                    while (is.read(buf) > 0) {
                        // discard
                    }
                } catch (Throwable ignore) {
                }
            });
            se.setDaemon(true);
            se.start();
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                // su 卡在授权弹窗：杀掉后也要尝试绝对路径兜底，不能直接放弃
                p.destroy();
                return tryAltSu(suPath, cmd);
            }
            so.join(1500);
            se.join(500);
            if (p.exitValue() != 0) {
                return null;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignore) {
                }
            }
            return tryAltSu(suPath, cmd);
        }
    }

    /** 少数 ROM 的 app 进程 PATH 里没有 su，补几个常见绝对路径；仅首次调用时递归一层。 */
    private static String tryAltSu(String suPath, String cmd) {
        if (!"su".equals(suPath)) {
            return null;
        }
        String[] alts = {
                "/system/bin/su", "/system/xbin/su",
                "/sbin/su", "/debug_ramdisk/su",
        };
        for (String alt : alts) {
            if (new File(alt).exists()) {
                String r = runSu(alt, cmd);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
