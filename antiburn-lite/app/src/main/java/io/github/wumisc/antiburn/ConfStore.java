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
import java.util.concurrent.TimeUnit;

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

    /** root 写入成功且读回校验通过 */
    public static final int WRITE_OK = 0;
    /** root 不可用/写入失败，但应用内镜像已保存（Hook 侧通过 ConfigProvider 仍可生效） */
    public static final int WRITE_MIRROR_ONLY = 1;
    /** 镜像也写不进去，彻底失败 */
    public static final int WRITE_FAILED = 2;

    private static volatile Context appCtx;
    private static final Object LOCK = new Object();

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
        sb.append("enable_navigation=0\n");
        sb.append("enable_poem=0\n");
        sb.append("enable_ball=0\n");
        sb.append("enable_log=0\n");
        sb.append("enable_icon=0\n");
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
     * 原子写入配置全文。返回 WRITE_OK / WRITE_MIRROR_ONLY / WRITE_FAILED。
     * 先写镜像（永远可用），再经 root 写 tmp + mv 原子替换，最后读回校验。
     */
    public static int writeEx(String content) {
        if (content == null) {
            return WRITE_FAILED;
        }
        synchronized (LOCK) {
            boolean mirror = writeMirror(content);
            String b64 = Base64.encodeToString(
                    content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            // 注意：先写 .tmp 再 mv，绝不直接 > 目标文件；任一环节失败原 conf 保持完整。
            String cmd = "{ echo '" + b64 + "' | base64 -d > " + CONF_TMP
                    + " && chmod 644 " + CONF_TMP
                    + " && mv -f " + CONF_TMP + " " + CONF
                    + " && chmod 644 " + CONF + "; } 2>/dev/null";
            boolean rootOk = execSu(cmd);
            boolean verified = false;
            if (rootOk) {
                String back = execSuCapture("cat " + CONF + " 2>/dev/null");
                verified = back != null && back.trim().equals(content.trim());
            }
            // 无论 root conf 是否写成功，都尽力把「同一份内容」写到公共存储镜像。
            // 第三方 App（抖音/红果）可能因 Android 11+ 包可见性解析不到 ContentProvider，
            // 这条文件通道是它们的主要兜底；不能只在 WRITE_OK 时才同步。
            String extCmd = "{ echo '" + b64 + "' | base64 -d > " + EXTERNAL_MIRROR + ".tmp"
                    + " && mv -f " + EXTERNAL_MIRROR + ".tmp " + EXTERNAL_MIRROR
                    + " && chmod 644 " + EXTERNAL_MIRROR + "; } 2>/dev/null";
            execSu(extCmd);
            return verified ? WRITE_OK : (mirror ? WRITE_MIRROR_ONLY : WRITE_FAILED);
        }
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
            boolean on = true;
            if (conf != null) {
                for (String line : conf.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("enable_status=")) {
                        on = t.substring(t.indexOf('=') + 1).trim().equals("1");
                        break;
                    }
                }
            }
            String next = on ? "0" : "1";
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
            String content = read();
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

    /** 更新单个 key。 */
    public static boolean update(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return updateEx(m) != WRITE_FAILED;
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

    /** 检测 root 是否可用（供界面显示状态）。会阻塞，务必在 IO 线程调用。 */
    public static boolean hasRoot() {
        String uid = execSuCapture("id -u 2>/dev/null");
        return uid != null && uid.trim().startsWith("0");
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
