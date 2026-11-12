import java.util.LinkedHashSet;
import java.util.Set;

import io.github.wumisc.antiburn.ConfStore;

/**
 * v2.9 配置层行为测试：名单解析 / 模式归一 / 旧配置迁移 / 多行值编解码。
 *
 * <p>这些是纯函数，直接调用真实实现（不复制逻辑）。编译时用 android.jar 解析
 * Context 等引用，运行时不会触发任何 Android API。</p>
 *
 * <pre>
 *   CLS=/tmp/v22/antiburn-lite/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
 *   AJAR=/opt/android-sdk/platforms/android-37.0/android.jar
 *   javac -cp "$CLS:$AJAR" -d /tmp/conftest ConfStoreTest.java
 *   java -cp "/tmp/conftest:$CLS:$AJAR" ConfStoreTest
 * </pre>
 */
public class ConfStoreTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(boolean cond, String name, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "  — " + detail));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  — " + detail);
        }
    }

    private static Set<String> setOf(String... v) {
        Set<String> s = new LinkedHashSet<>();
        for (String x : v) {
            s.add(x);
        }
        return s;
    }

    public static void main(String[] args) {
        // ---------- T-01 模式归一 ----------
        System.out.println("== T-01 模式归一 ==");
        check("white".equals(ConfStore.normMode(null)), "T-01a null → white", "");
        check("white".equals(ConfStore.normMode("")), "T-01b 空串 → white", "");
        check("black".equals(ConfStore.normMode("BLACK")), "T-01c 大小写不敏感", "");
        check("black".equals(ConfStore.normMode(" 黑名单 ")), "T-01d 中文「黑名单」→ black", "");
        check("black".equals(ConfStore.normMode("blacklist")), "T-01e 旧值 blacklist → black", "");
        check("off".equals(ConfStore.normMode("关闭")), "T-01f 中文「关闭」→ off", "");
        check("off".equals(ConfStore.normMode("off_list")), "T-01g 旧值 off_list → off", "");
        check("white".equals(ConfStore.normMode("随便什么")), "T-01h 未知值 → white（安全侧）", "");

        // ---------- T-02 名单拆分 ----------
        System.out.println("== T-02 名单文本拆分 ==");
        check(ConfStore.parseWhitelist("com.a,com.b").equals(setOf("com.a", "com.b")),
                "T-02a 英文逗号", ConfStore.parseWhitelist("com.a,com.b").toString());
        check(ConfStore.parseWhitelist(" com.a , com.b ").equals(setOf("com.a", "com.b")),
                "T-02b 两侧空格被去掉", "");
        check(ConfStore.parseWhitelist("com.a,,com.b,").equals(setOf("com.a", "com.b")),
                "T-02c 空项被丢弃", "");
        check(ConfStore.parseWhitelist("com.a,com.a").equals(setOf("com.a")),
                "T-02d 重复项去重", "");
        check(ConfStore.parseWhitelist(null).isEmpty() && ConfStore.parseWhitelist("").isEmpty(),
                "T-02e null/空串 → 空集", "");
        // 用户手动粘贴包名时中文逗号很常见；不支持的话整串会被当成一个包名，名单静默失效
        check(ConfStore.parseWhitelist("com.a，com.b").equals(setOf("com.a", "com.b")),
                "T-02f 中文逗号也能分", ConfStore.parseWhitelist("com.a，com.b").toString());

        // ---------- T-03 readLists 兼容规则 ----------
        System.out.println("== T-03 三键解析与旧配置迁移 ==");
        ConfStore.Lists l1 = ConfStore.readLists("whitelist=com.a,com.b\nblacklist=com.x");
        check("white".equals(l1.mode), "T-03a 无 list_mode → white", "");
        check(l1.white.equals(setOf("com.a", "com.b")), "T-03b white 独立解析", l1.white.toString());
        check(l1.black.equals(setOf("com.x")), "T-03c black 独立解析", l1.black.toString());

        ConfStore.Lists l2 = ConfStore.readLists("blacklist=com.legacy");
        check("white".equals(l2.mode), "T-03d 只有旧 blacklist → 仍按 white", "");
        check(l2.white.equals(setOf("com.legacy")),
                "T-03e 旧 blacklist 迁移成白名单（v2.7 及更早的真实语义）", l2.white.toString());
        check(l2.black.isEmpty(), "T-03f 迁移后黑名单为空", l2.black.toString());

        ConfStore.Lists l3 = ConfStore.readLists("list_mode=black\nwhitelist=com.w\nblacklist=com.b");
        check("black".equals(l3.mode), "T-03g list_mode=black 生效", "");
        check(l3.active().equals(setOf("com.b")), "T-03h active() 取黑名单", l3.active().toString());

        ConfStore.Lists l4 = ConfStore.readLists("list_mode=black\nwhitelist=com.w");
        check(l4.black.isEmpty() && l4.active().isEmpty(),
                "T-03i 切到黑名单但没写 blacklist → 黑名单为空（不吃旧白名单）",
                "black=" + l4.black);

        ConfStore.Lists l5 = ConfStore.readLists("list_mode=off\nwhitelist=com.w\nblacklist=com.b");
        check("off".equals(l5.mode), "T-03j list_mode=off 生效", "");

        ConfStore.Lists l6 = ConfStore.readLists("# 注释行\n\nlist_mode=black\n   \nno_equal_sign\nblacklist=com.b");
        check("black".equals(l6.mode) && l6.black.equals(setOf("com.b")),
                "T-03k 注释 / 空行 / 无等号行被忽略", "black=" + l6.black);

        // ---------- T-04 多行值编解码 ----------
        System.out.println("== T-04 多行值编解码 ==");
        String[] samples = {
                "床前明月光\n疑是地上霜",
                "含\\反斜杠\\n字面",
                "一行",
                "",
                "中文 · emoji 🌙 · 逗号,与;分号",
        };
        for (String s : samples) {
            String enc = ConfStore.encodeValue(s);
            String dec = ConfStore.decodeValue(enc);
            check(s.equals(dec), "T-04 往返一致：" + (s.length() > 12 ? s.substring(0, 12) + "…" : s),
                    s.equals(dec) ? "" : "enc=" + enc + " dec=" + dec);
            check(!enc.contains("\n") && !enc.contains("\r"),
                    "T-04 编码后是单行（不会破坏 conf 分行）", "enc=" + enc);
        }
        check("a\\\\b".equals(ConfStore.encodeValue("a\\b")), "T-04a 反斜杠被转义",
                ConfStore.encodeValue("a\\b"));

        // ---------- T-05 默认配置可被自身解析 ----------
        System.out.println("== T-05 默认配置自洽 ==");
        String def = ConfStore.defaultContent();
        ConfStore.Lists d = ConfStore.readLists(def);
        check(def.contains("list_mode="), "T-05a 默认配置含 list_mode", "");
        check("white".equals(d.mode), "T-05b 默认模式为 white", d.mode);
        check(d.white.isEmpty() && d.black.isEmpty(), "T-05c 默认两份名单都为空", "");
        check(def.contains("whitelist=") && def.contains("blacklist="),
                "T-05d 默认配置同时写出两份名单键", "");

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
