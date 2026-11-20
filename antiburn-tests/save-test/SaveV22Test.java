import android.content.Context;
import io.github.wumisc.antiburn.ConfStore;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * v2.2 保存链路验证：真实 ConfStore.java + Context 桩件 + 假 su。
 *
 * 核心要证明两件事：
 *  1. 「点一下 → 写盘 → 回读」这条链真的把值留在磁盘上（不是只在内存里）；
 *  2. 写不进去时 verify() 必须报错，不能像旧版那样照样提示「已保存」。
 */
public class SaveV22Test {

    static int pass = 0, fail = 0;
    static String dir;

    static void check(String id, String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + id + " " + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        } else {
            fail++;
            System.out.println("  FAIL " + id + " " + name + "  [" + detail + "]");
        }
    }

    static void fresh() throws Exception {
        Path d = Paths.get(dir);
        if (Files.exists(d)) {
            Files.walk(d).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
        Files.createDirectories(d);
    }

    /** 模拟 AbConf.load() 里对某个布尔键的解析 */
    static boolean readBool(String key) {
        String c = ConfStore.readMirror();
        if (c == null) return false;
        for (String line : c.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + "=")) {
                return "1".equals(t.substring(t.indexOf('=') + 1).trim());
            }
        }
        return false;
    }

    static int countKey(String key) {
        String c = ConfStore.readMirror();
        if (c == null) return -1;
        int n = 0;
        for (String line : c.split("\n")) {
            if (line.trim().startsWith(key + "=")) n++;
        }
        return n;
    }

    public static void main(String[] args) throws Exception {
        dir = System.getProperty("conf.test.dir", "/tmp/lt/files22");
        System.out.println("== 保存链路 v2.2 ==");
        System.out.println("  私有目录: " + dir);
        // 真实 App 里由 AbApplication.onCreate() 完成；测试必须显式初始化，
        // 否则 mirrorFile() 拿不到 filesDir，一切写入都会失败。
        ConfStore.init(new Context());

        // ---------- S-01 点一次总开关：关 -> 开 ----------
        fresh();
        ConfStore.writeEx("enable_status=0\nhide_duration=30\n");
        Map<String, String> d1 = new LinkedHashMap<>();
        d1.put("enable_status", "1");
        int rc = ConfStore.updateEx(d1);
        Set<String> bad = ConfStore.verify(d1);
        check("S-01", "点一下总开关即落盘，回读校验通过",
                rc != ConfStore.WRITE_FAILED && bad.isEmpty() && readBool("enable_status"),
                "rc=" + rc + " 校验失败键=" + bad + " 磁盘值=" + readBool("enable_status"));

        // ---------- S-02 模拟重开 App：重新从磁盘解析 ----------
        boolean persisted = readBool("enable_status");
        check("S-02", "重开 App 后读到的是新值（不是默认值）", persisted,
                "磁盘 enable_status=" + persisted + "（期望 true）");

        // ---------- S-03 连续快速切换 10 次 ----------
        fresh();
        ConfStore.writeEx("enable_status=0\n");
        boolean last = false;
        for (int i = 0; i < 10; i++) {
            last = !last;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("enable_status", last ? "1" : "0");
            ConfStore.updateEx(m);
            Set<String> b = ConfStore.verify(m);
            if (!b.isEmpty()) {
                check("S-03", "连续 10 次切换不丢", false, "第 " + (i + 1) + " 次校验失败 " + b);
                break;
            }
        }
        check("S-03", "连续 10 次切换后磁盘与最后一次一致、且只有一行",
                readBool("enable_status") == last && countKey("enable_status") == 1,
                "磁盘=" + readBool("enable_status") + " 期望=" + last
                        + " 行数=" + countKey("enable_status"));

        // ---------- S-04 写不进去时必须报失败 ----------
        String oldDir = System.getProperty("conf.test.dir");
        System.setProperty("conf.test.dir", "/proc/antiburn-not-writable");
        // 重新 init 让 mirrorFile() 指向不可写目录
        ConfStore.init(new Context());
        Map<String, String> d4 = new LinkedHashMap<>();
        d4.put("enable_status", "1");
        int rc4 = ConfStore.updateEx(d4);
        Set<String> bad4 = ConfStore.verify(d4);
        check("S-04", "写入失败时返回 WRITE_FAILED 且校验不通过（不会谎报成功）",
                rc4 == ConfStore.WRITE_FAILED && !bad4.isEmpty(),
                "rc=" + rc4 + " 校验失败键=" + bad4);
        System.setProperty("conf.test.dir", oldDir);
        ConfStore.init(new Context());

        // ---------- S-05 磁盘被外部改回时能发现 ----------
        fresh();
        ConfStore.writeEx("enable_status=1\n");
        Map<String, String> d5 = new LinkedHashMap<>();
        d5.put("enable_status", "1");
        Set<String> okBefore = ConfStore.verify(d5);
        // 模拟被别的进程覆盖成 0
        File f = new File(dir, "antiburn.conf");
        try (FileOutputStream fo = new FileOutputStream(f, false)) {
            fo.write("enable_status=0\n".getBytes(StandardCharsets.UTF_8));
        }
        Set<String> badAfter = ConfStore.verify(d5);
        check("S-05", "磁盘值被覆盖后 verify 立刻发现不一致",
                okBefore.isEmpty() && badAfter.contains("enable_status"),
                "写前=" + okBefore + " 被覆盖后=" + badAfter);

        // ---------- S-06 缺键不能当成一致 ----------
        fresh();
        ConfStore.writeEx("hide_duration=30\n");
        Map<String, String> d6 = new LinkedHashMap<>();
        d6.put("enable_status", "1");
        Set<String> bad6 = ConfStore.verify(d6);
        check("S-06", "目标键根本不在磁盘上时 verify 判失败",
                bad6.contains("enable_status"), "校验失败键=" + bad6);

        // ---------- S-07 日志目录准备命令的引号 ----------
        Path suLog = Paths.get("/tmp/lt/su.log");
        String before = Files.exists(suLog) ? new String(Files.readAllBytes(suLog), StandardCharsets.UTF_8) : "";
        ConfStore.prepareSharedDirs();
        String after = Files.exists(suLog) ? new String(Files.readAllBytes(suLog), StandardCharsets.UTF_8) : "";
        String delta = after.substring(before.length());
        boolean quoted = delta.contains("\"/storage/emulated/0/Android/Prevent burn-in\"");
        check("S-07", "含空格的日志目录整体加引号（否则 shell 会拆成两个参数）",
                quoted && delta.contains("mkdir -p"),
                quoted ? "引号正确" : "命令里未加引号: " + delta.replace('\n', ' '));

        System.out.println("\n== 汇总: PASS " + pass + " / FAIL " + fail + " ==");
        if (fail > 0) System.exit(1);
    }
}
