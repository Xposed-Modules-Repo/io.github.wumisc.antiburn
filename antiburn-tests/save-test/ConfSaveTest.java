import android.content.Context;

import io.github.wumisc.antiburn.ConfStore;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 验证 v2.1 保存路径改造：从「3 次同步 su」改为「写镜像即成功 + 后台合并同步」。
 * 使用真实 ConfStore.java，Context / Base64 用桩件，su 用假脚本记录调用。
 */
public class ConfSaveTest {

    private static final String SU_LOG = "/tmp/lt/su.log";
    private static int pass;
    private static int fail;

    private static void check(String id, String desc, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("PASS " + id + "  " + desc + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("FAIL " + id + "  " + desc + "   [" + detail + "]");
        }
    }

    private static int suCount() {
        try {
            if (!Files.exists(Paths.get(SU_LOG))) {
                return 0;
            }
            return Files.readAllLines(Paths.get(SU_LOG), StandardCharsets.UTF_8).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void truncate(String path) {
        try {
            new RandomAccessFile(path, "rw").setLength(0);
        } catch (Throwable ignored) {
        }
    }

    public static void main(String[] args) throws Exception {
        String dir = System.getProperty("conf.test.dir", "/tmp/lt/files");
        File d = new File(dir);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        File[] old = d.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        System.setProperty("conf.test.dir", dir);
        truncate(SU_LOG);
        new File("/tmp/lt/su_slow").delete();

        ConfStore.init(new Context());

        // ---------- 连续保存：应全部立即成功 ----------
        int okCount = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("enable_status", i % 2 == 0 ? "1" : "0");
            m.put("hide_duration", String.valueOf(30 + i));
            if (ConfStore.updateEx(m) == ConfStore.WRITE_OK) {
                okCount++;
            }
        }
        long saveMs = (System.nanoTime() - t0) / 1_000_000L;

        check("SAVE-01", "20 次连续保存全部返回 WRITE_OK", okCount == 20, okCount + "/20");
        check("SAVE-02", "20 次保存总耗时远低于旧实现（旧实现单次最坏 12s）",
                saveMs < 3000, saveMs + "ms");

        String content = ConfStore.readMirror();
        check("SAVE-03", "最后一次写入生效且内容完整",
                content != null && content.contains("hide_duration=49")
                        && content.contains("enable_status=0"),
                content == null ? "镜像为空"
                        : "hide_duration=49? " + content.contains("hide_duration=49"));

        // ---------- 后台合并同步 ----------
        Thread.sleep(1500);
        int suAfterBatch = suCount();
        check("SAVE-04", "20 次保存合并后 su 调用 ≤ 3 次（旧实现 60 次）",
                suAfterBatch >= 1 && suAfterBatch <= 3, suAfterBatch + " 次");

        // ---------- su 卡死时保存不受影响 ----------
        new File("/tmp/lt/su_slow").createNewFile();
        long t1 = System.nanoTime();
        boolean ok = ConfStore.update("enable_poem", "1");
        long blockedMs = (System.nanoTime() - t1) / 1_000_000L;
        check("SAVE-05", "su 卡在授权弹窗时保存仍在 500ms 内返回",
                ok && blockedMs < 500, blockedMs + "ms rc=" + ok);

        String after = ConfStore.readMirror();
        check("SAVE-06", "su 卡死期间写入的配置已落盘（不因 su 失败而丢）",
                after != null && after.contains("enable_poem=1"),
                after != null && after.contains("enable_poem=1") ? "已落盘" : "丢失");

        new File("/tmp/lt/su_slow").delete();

        // ---------- 空内容与 null 防御 ----------
        boolean nullSafe;
        try {
            ConfStore.writeEx(null);
            ConfStore.update(null, null);
            nullSafe = true;
        } catch (Throwable t) {
            nullSafe = false;
        }
        check("SAVE-07", "null 参数不崩溃", nullSafe, "ok=" + nullSafe);

        System.out.println();
        System.out.println("SUMMARY pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
