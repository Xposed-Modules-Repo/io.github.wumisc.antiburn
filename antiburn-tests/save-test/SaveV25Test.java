import android.content.Context;
import io.github.wumisc.antiburn.ConfStore;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.5 专项断言：验证本轮「补窟窿」的几处修复确实生效。 */
public class SaveV25Test {

    private static int pass = 0, fail = 0;
    private static File dir;

    private static void check(String id, String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS " + id + " " + name + "  [" + detail + "]"); }
        else    { fail++; System.out.println("  FAIL " + id + " " + name + "  [" + detail + "]"); }
    }

    private static String mirror() throws Exception {
        File f = new File(dir, "antiburn.conf");
        return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
    }

    private static void writeMirrorRaw(String s) throws Exception {
        Files.write(new File(dir, "antiburn.conf").toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    private static String val(String conf, String key) {
        if (conf == null) return null;
        for (String l : conf.split("\n")) {
            String t = l.trim();
            if (t.startsWith(key + "=")) return t.substring(key.length() + 1).trim();
        }
        return null;
    }

    private static long countKey(String conf, String key) {
        if (conf == null) return 0;
        long n = 0;
        for (String l : conf.split("\n")) if (l.trim().startsWith(key + "=")) n++;
        return n;
    }

    private static List<String> suLog() throws Exception {
        File f = new File("/tmp/lt/su.log");
        return f.exists() ? Files.readAllLines(f.toPath(), StandardCharsets.UTF_8) : new ArrayList<>();
    }

    private static void clearSuLog() throws Exception {
        Files.write(new File("/tmp/lt/su.log").toPath(), new byte[0]);
    }

    private static void resetStatics() throws Exception {
        Field a = ConfStore.class.getDeclaredField("mirrorIsDefault");
        a.setAccessible(true); a.setBoolean(null, false);
        Field b = ConfStore.class.getDeclaredField("lastStampRev");
        b.setAccessible(true); b.setLong(null, 0L);
        File m = new File(dir, "antiburn.conf");
        if (m.exists()) m.delete();
    }

    public static void main(String[] args) throws Exception {
        dir = new File(System.getProperty("conf.test.dir", "/tmp/lt/files25"));
        dir.mkdirs();
        ConfStore.init(new Context());
        System.out.println("== v2.5 专项断言 ==");
        System.out.println("  私有目录: " + dir.getAbsolutePath());

        // T-01 时间回拨：镜像里预置一个远大于当前时间的 rev，新写入仍必须严格递增
        long huge = System.currentTimeMillis() + 10_000_000_000L;
        writeMirrorRaw("rev=" + huge + "\nenable_status=1\nenable_poem=1\nwhitelist=a,b\n");
        ConfStore.update("enable_status", "0");
        String c1 = mirror();
        long r1 = ConfStore.revOf(c1);
        check("T-01", "时间回拨后 rev 仍单调递增", r1 > huge,
                "old=" + huge + " new=" + r1);

        // T-02 单调 rev 不破坏其它键
        check("T-02", "高 rev 配置下改一个键不丢其它键",
                "1".equals(val(c1, "enable_poem")) && "a,b".equals(val(c1, "whitelist"))
                        && countKey(c1, "rev") == 1,
                "poem=" + val(c1, "enable_poem") + " wl=" + val(c1, "whitelist"));

        // T-03 WRITE 审计行带本次变更明细
        File audit = new File(dir, "save-audit.log");
        String at = audit.exists() ? new String(Files.readAllBytes(audit.toPath()), StandardCharsets.UTF_8) : "";
        check("T-03", "WRITE 审计行含 ch= 变更明细", at.contains("ch=enable_status=0"),
                at.trim().isEmpty() ? "(空)" : at.trim().split("\n")[at.trim().split("\n").length - 1]);

        // T-04 ensureMirror 不触发 su（主线程写入路径不能 fork su）
        clearSuLog();
        ConfStore.ensureMirror();
        boolean anySu = false;
        for (String l : suLog()) if (l.contains("cat ")) { anySu = true; break; }
        check("T-04", "镜像存在时 ensureMirror 不触发 su", !anySu, "su 调用 " + suLog().size() + " 条");

        // T-05 迁移竞态：用户保存后，后台 su 迁移不得覆盖镜像
        resetStatics();
        ConfStore.init(new Context());   // 建默认镜像，mirrorIsDefault=true
        String def = mirror();
        check("T-05a", "init 同步建立默认镜像", def != null && def.contains("enable_status=1")
                && ConfStore.revOf(def) == 0L, "rev=" + ConfStore.revOf(def));
        Map<String, String> m = new LinkedHashMap<>();
        m.put("enable_status", "1");
        m.put("whitelist", "com.foo,com.bar");
        m.put("hide_duration", "77");
        ConfStore.updateEx(m);
        String saved = mirror();
        clearSuLog();
        ConfStore.migrateIfNeededAsync();
        Thread.sleep(400);
        String after = mirror();
        check("T-05b", "迁移线程不覆盖用户刚保存的配置",
                "com.foo,com.bar".equals(val(after, "whitelist"))
                        && "77".equals(val(after, "hide_duration"))
                        && ConfStore.revOf(after) > 0L,
                "wl=" + val(after, "whitelist") + " hide=" + val(after, "hide_duration"));

        // T-06 exportLogs 改为去重排序 + 覆盖写，不再 >> 追加
        clearSuLog();
        ConfStore.exportLogs();
        String cmd = String.join("\n", suLog());
        check("T-06", "导出日志不再重复追加、按时间戳排序",
                cmd.contains("sort -n") && cmd.contains("mv -f")
                        && !cmd.contains(">> \"" + ConfStore.LOG_FILE + "\""),
                "含 sort=" + cmd.contains("sort -n") + " 含 mv=" + cmd.contains("mv -f")
                        + " 含 >>=" + cmd.contains(">> \"" + ConfStore.LOG_FILE + "\""));

        // T-07 非幂等命令超时不重跑（restartSystemUi 不能把 pkill 执行两次）
        clearSuLog();
        ConfStore.restartSystemUi();
        int pkill = 0;
        for (String l : suLog()) if (l.contains("pkill -f")) pkill++;
        check("T-07", "重启 SystemUI 每条 pkill 只执行一次", pkill <= 2,
                "pkill 命令数=" + pkill + "（两个候选命令各一次）");

        System.out.println("\n  结果: " + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
