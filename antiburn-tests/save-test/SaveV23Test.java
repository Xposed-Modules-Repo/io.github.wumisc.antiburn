import android.content.Context;
import io.github.wumisc.antiburn.ConfStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v2.3 保存链路验证：真实 ConfStore.java + Context 桩件 + 假 su。
 *
 * 相比 v2.2 多验证三件事：
 *  1. 每次写入都刷新 rev（版本号单调递增），Hook 侧才能拒绝旧副本；
 *  2. 每次写入都留审计行（谁写的 / 版本号 / 关键值），「开关自己变回去」时能定位到人；
 *  3. 磁贴在「配置读不到」时点一下必须是开启，而不是把 0 写进去。
 */
public class SaveV23Test {

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
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
        Files.createDirectories(d);
    }

    static String audit() throws Exception {
        Path p = Paths.get(dir, "save-audit.log");
        return Files.exists(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : "";
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

    static String valueOf(String key) {
        String c = ConfStore.readMirror();
        if (c == null) return null;
        for (String line : c.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + "=")) return t.substring(key.length() + 1).trim();
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        dir = System.getProperty("conf.test.dir", "/tmp/lt/files23");
        System.out.println("== 保存链路 v2.3 ==");
        System.out.println("  私有目录: " + dir);
        ConfStore.init(new Context());

        // ---------- R-01 每次写入都刷新 rev，且严格递增 ----------
        fresh();
        ConfStore.writeEx("enable_status=1\n");
        long r1 = ConfStore.revOf(ConfStore.readMirror());
        Thread.sleep(5);
        ConfStore.writeEx("enable_status=1\nenable_navigation=1\n");
        long r2 = ConfStore.revOf(ConfStore.readMirror());
        check("R-01", "两次保存产生递增的 rev", r1 > 0 && r2 > r1, "rev1=" + r1 + " rev2=" + r2);

        // ---------- R-02 rev 只有一行，且不影响其它键 ----------
        ConfStore.writeEx("enable_status=1\nenable_ball=1\n");
        ConfStore.updateEx(mapOf("enable_status", "0"));
        check("R-02a", "rev 只保留一行", countKey("rev") == 1, "count=" + countKey("rev"));
        check("R-02b", "改一个键不丢其它键", "1".equals(valueOf("enable_ball")), "ball=" + valueOf("enable_ball"));

        // ---------- R-03 老配置（无 rev）按最旧处理，新配置能覆盖 ----------
        long oldRev = ConfStore.revOf("enable_status=1\n");
        check("R-03", "无 rev 的旧配置视为 0", oldRev == 0L, "rev=" + oldRev);

        // ---------- R-04 审计日志记录写入者与版本号 ----------
        String a = audit();
        boolean auditOk = a.contains("WRITE ok rev=") && a.contains("status=") && a.contains("by=");
        check("R-04", "审计日志含 rev / status / 调用方", auditOk,
                auditOk ? a.trim().split("\n")[a.trim().split("\n").length - 1] : "audit empty");

        // ---------- R-05 读不到配置时，磁贴点击必须写 1（开启） ----------
        fresh();
        String next = ConfStore.flipStatus();
        check("R-05a", "无配置时磁贴返回 1", "1".equals(next), "next=" + next);
        check("R-05b", "无配置时磁盘上确实是 1", "1".equals(valueOf("enable_status")),
                "status=" + valueOf("enable_status"));

        // ---------- R-06 有配置时磁贴取反 ----------
        String back = ConfStore.flipStatus();
        check("R-06", "已有开启配置时磁贴写成 0", "0".equals(back), "next=" + back);

        // ---------- R-07 白名单写入后仍只有一行 whitelist ----------
        java.util.Set<String> wl = new java.util.HashSet<>();
        wl.add("com.ss.android.ugc.aweme");
        wl.add("com.phoenix.read");
        ConfStore.replaceWhitelist(wl);
        String wlText = valueOf("whitelist");
        boolean wlOk = wlText != null
                && wlText.contains("com.ss.android.ugc.aweme")
                && wlText.contains("com.phoenix.read")
                && !wlText.contains(",,");
        check("R-07", "白名单唯一且包含两个包", countKey("whitelist") == 1 && wlOk, "wl=" + wlText);

        // ---------- R-08 ui_style 这类新键能原样落盘 ----------
        ConfStore.updateEx(mapOf("ui_style", "hyperos"));
        check("R-08", "ui_style 写入后能读回", "hyperos".equals(valueOf("ui_style")),
                "ui_style=" + valueOf("ui_style"));

        System.out.println();
        System.out.println("  结果: " + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }

    static Map<String, String> mapOf(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
