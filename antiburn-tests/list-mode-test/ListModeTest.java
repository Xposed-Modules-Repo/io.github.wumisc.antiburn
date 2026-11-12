import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.wumisc.antiburn.Config;
import io.github.wumisc.antiburn.ConfStore;

/**
 * v2.8 黑白名单模式回归测试（纯 JVM，直接调用真实字节码，不复制逻辑）。
 *
 * <p>编译运行（classes 来自 gradle 已编译产物，android.jar 只用于解析测试类引用）：</p>
 * <pre>
 *   SRC=/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn
 *   CLS=/tmp/v22/antiburn-lite/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
 *   AJAR=/opt/android-sdk/platforms/android-37.0/android.jar
 *   javac -cp "$CLS:$AJAR" -d /tmp/listtest ListModeTest.java
 *   java -cp "/tmp/listtest:$CLS:$AJAR" ListModeTest "$SRC"
 * </pre>
 *
 * <p>为什么不像 arbitration-test 那样重写一遍逻辑：那种写法在真实实现漂移后仍然会绿，
 * 等于测了个副本。这里直接加载编译产物，断言的是真正会跑在 SystemUI 里的字节码。</p>
 */
public class ListModeTest {

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

    /** 设定 Hook 侧运行时状态，再问「要不要干预」 */
    private static boolean intervene(String mode, Set<String> list, String pkg) {
        Config.enableStatus = true;
        Config.listMode = mode;
        Config.list = list;
        return Config.shouldIntervene(pkg);
    }

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0]
                : "/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn";
        Path dir = Path.of(src);

        // ---------- T-01 ~ T-08：判定真值表 ----------
        System.out.println("== T-01 白名单：命中即豁免 ==");
        check(!intervene("white", setOf("com.a"), "com.a"), "T-01a 命中→不干预", "false");
        check(intervene("white", setOf("com.a"), "com.b"), "T-01b 未命中→干预", "true");
        check(intervene("white", new LinkedHashSet<>(), "com.a"), "T-01c 空名单→全干预", "true");

        System.out.println("== T-02 黑名单：仅命中才干预 ==");
        check(intervene("black", setOf("com.a"), "com.a"), "T-02a 命中→干预", "true");
        check(!intervene("black", setOf("com.a"), "com.b"), "T-02b 未命中→豁免", "false");

        System.out.println("== T-03 黑名单空集必须退回全干预（防「模块看起来坏了」）==");
        check(intervene("black", new LinkedHashSet<>(), "com.any"), "T-03 空黑名单→干预", "true");

        System.out.println("== T-04 off：忽略名单 ==");
        check(intervene("off", setOf("com.a"), "com.a"), "T-04a off 命中仍干预", "true");
        check(intervene("off", setOf("com.a"), "com.b"), "T-04b off 未命中仍干预", "true");

        System.out.println("== T-05 总开关优先于一切模式 ==");
        Config.enableStatus = false;
        Config.listMode = "black";
        Config.list = setOf("com.a");
        check(!Config.shouldIntervene("com.a"), "T-05 关闭总闸→不干预", "false");
        Config.enableStatus = true;

        System.out.println("== T-06 空包名不参与命中 ==");
        check(intervene("white", setOf("com.a"), null), "T-06a white + null 包名→干预", "true");
        check(!intervene("black", setOf("com.a"), null), "T-06b black + null 包名→豁免", "false");

        // ---------- T-07：模式归一 ----------
        System.out.println("== T-07 normMode 归一 ==");
        check("white".equals(ConfStore.normMode(null)), "T-07a null→white", "white");
        check("white".equals(ConfStore.normMode("")), "T-07b 空→white", "white");
        check("white".equals(ConfStore.normMode("whatever")), "T-07c 未知值→white", "white");
        check("black".equals(ConfStore.normMode(" BLACK ")), "T-07d 大小写+空白", "black");
        check("black".equals(ConfStore.normMode("黑名单")), "T-07e 中文别名", "black");
        check("off".equals(ConfStore.normMode("关闭")), "T-07f 关闭→off", "off");

        // ---------- T-08：解析与迁移 ----------
        System.out.println("== T-08 readLists 解析 ==");
        ConfStore.Lists a = ConfStore.readLists("list_mode=white\nwhitelist=com.a,com.b\nblacklist=com.c\n");
        check("white".equals(a.mode) && a.white.size() == 2 && a.black.size() == 1,
                "T-08a 三键各自独立", a.mode + " w=" + a.white + " b=" + a.black);
        check(a.active().equals(setOf("com.a", "com.b")), "T-08b white 取白名单", a.active().toString());

        ConfStore.Lists b = ConfStore.readLists("list_mode=black\nwhitelist=com.a\nblacklist=com.c,com.d\n");
        check(b.active().equals(setOf("com.c", "com.d")), "T-08c black 取黑名单", b.active().toString());

        System.out.println("== T-09 旧配置迁移（无 list_mode）==");
        ConfStore.Lists c = ConfStore.readLists("whitelist=com.a\n");
        check("white".equals(c.mode), "T-09a 缺模式→white（行为不变）", c.mode);
        check(c.white.equals(setOf("com.a")), "T-09b 白名单内容保留", c.white.toString());

        ConfStore.Lists d = ConfStore.readLists("blacklist=com.legacy\n");
        check("white".equals(d.mode), "T-09c 只有旧别名键→仍按 white", d.mode);
        check(d.white.equals(setOf("com.legacy")), "T-09d 旧别名迁移进白名单", d.white.toString());
        check(d.black.isEmpty(), "T-09e 不留残留黑名单", d.black.toString());

        // ---------- T-10：真实解析路径（反射调用 Config.parse）----------
        // 这一组最关键：它走的是 SystemUI 里真正执行的解析代码，
        // 覆盖「模式键存在但目标名单缺键时不得沿用旧集合」这条易错规则。
        System.out.println("== T-10 真实 parse：模式与名单一次性提交 ==");
        parse("list_mode=white\nwhitelist=com.a,com.b\nblacklist=com.c\n");
        check("white".equals(Config.listMode) && Config.list.equals(setOf("com.a", "com.b")),
                "T-10a white 取白名单", Config.listMode + " " + Config.list);

        parse("list_mode=black\nwhitelist=com.a,com.b\nblacklist=com.c\n");
        check("black".equals(Config.listMode) && Config.list.equals(setOf("com.c")),
                "T-10b black 取黑名单", Config.listMode + " " + Config.list);

        // 先让 list 装进白名单内容，再切到 black 且不给 blacklist 行：
        // 旧实现（缺键保持原值）会在这里把白名单带进黑名单，必须为空。
        parse("list_mode=white\nwhitelist=com.a,com.b\nblacklist=\n");
        parse("list_mode=black\nwhitelist=com.a,com.b\n");
        check(Config.list.isEmpty(), "T-10c 切 black 时目标键缺失→空集，不带入旧内容", Config.list.toString());
        check(Config.shouldIntervene("com.a"), "T-10d 空黑名单仍全干预", "true");

        parse("list_mode=off\nwhitelist=com.a\nblacklist=com.b\n");
        check("off".equals(Config.listMode) && Config.shouldIntervene("com.b"),
                "T-10e off 忽略名单", Config.listMode + " " + Config.list);

        System.out.println("== T-11 真实 parse：旧配置升级后行为不变 ==");
        Config.listMode = "black";
        Config.list = setOf("stale");
        parse("whitelist=com.old\n");
        check("white".equals(Config.listMode), "T-11a 无 list_mode→white", Config.listMode);
        check(Config.list.equals(setOf("com.old")), "T-11b 白名单原样保留", Config.list.toString());
        check(!Config.shouldIntervene("com.old"), "T-11c 旧白名单仍然豁免", "false");

        Config.list = setOf("stale");
        parse("blacklist=com.alias\n");
        check(Config.list.equals(setOf("com.alias")) && "white".equals(Config.listMode),
                "T-11d 旧别名 blacklist 迁进白名单", Config.listMode + " " + Config.list);

        // ---------- T-12：源码级一致性（防三份列表漂移）----------
        System.out.println("== T-12 写键 / 解析键 / 默认配置 三者一致 ==");
        String main = read(dir.resolve("MainActivity.kt"));
        String config = read(dir.resolve("Config.java"));
        String store = read(dir.resolve("ConfStore.java"));

        for (String key : new String[]{"list_mode", "whitelist", "blacklist"}) {
            check(store.contains("\"" + key + "\""), "T-12a ConfStore 定义 " + key, "");
            check(main.contains("\"" + key + "\""), "T-12b MainActivity 写入 " + key, "");
            check(config.contains("case \"" + key + "\""), "T-12c Config 解析 " + key, "");
        }
        check(store.contains("KEY_MODE + \"=\"") || store.contains("sb.append(KEY_MODE)"),
                "T-12d 默认配置含 list_mode", "");
        check(!main.contains("remove(\"blacklist\")"),
                "T-12e App 不再删除 blacklist 键（它已是独立名单）", "");
        check(!config.contains("wlLegacy"),
                "T-12f Hook 侧不再把 blacklist 当别名收集", "");
        check(!main.contains("state.whitelist") && !config.contains("Config.whitelist"),
                "T-12g 旧字段 whitelist 已彻底改名", "");

        // ---------- T-13：切模式的原子写契约 ----------
        // 只写 list_mode 而不带两份名单，Hook 侧会把「目标名单缺键」当成空集，
        // 等于用户一切模式名单就被清空。这条契约靠源码结构检查来守。
        System.out.println("== T-13 切模式必须与两份名单同批落盘 ==");
        String switchBlock = slice(main, "if (old.listMode != new.listMode)", "return m");
        check(switchBlock.contains("\"list_mode\""), "T-13a 切模式写 list_mode", "");
        check(switchBlock.contains("\"whitelist\""), "T-13b 同批写 whitelist（防被当成空集）", "");
        check(switchBlock.contains("\"blacklist\""), "T-13c 同批写 blacklist（防被当成空集）", "");

        String picker = read(dir.resolve("WhitelistPickerActivity.java"));
        check(picker.contains("EXTRA_MODE"), "T-13d 选择页接收模式参数", "");
        check(picker.contains("replaceListOne"), "T-13e 选择页只改当前那份名单", "");
        check(!picker.contains("replaceWhitelist"), "T-13f 不再调用只认白名单的旧接口", "");
        check(!picker.contains("\"选择白名单应用\""), "T-13g 选择页标题不再硬编码白名单", "");

        // ---------- T-14：状态保存索引与字段数一致 ----------
        System.out.println("== T-14 AbStateSaver 索引对齐 ==");
        int fields = countFields(main);
        int saved = countSaved(main);
        check(fields == saved, "T-14a AbState 字段数(" + fields + ") == Saver 保存项数(" + saved + ")", "");
        int maxIdx = maxIndex(main);
        check(maxIdx == fields - 1, "T-14b 最大索引(" + maxIdx + ") == 字段数-1", "");

        // ---------- T-15：界面入口唯一性与开关可达性（v2.9）----------
        // 这一组守的是「同一设置不要两个入口」和「Hook 有实现、界面必须有入口」两类问题。
        // 前者在 2.7→2.8 之间真实发生过（名单卡片首页与功能页各一张）；
        // 后者同样真实发生过（enableShift 一直有 Hook 消费，磁贴却在 2.7 被一并删掉，
        // 配置永远停在默认 true，用户既关不掉也不知道它存在）。
        System.out.println("== T-15 界面唯一入口 / 开关可达 ==");
        int chipsCalls = 0;
        for (String line : main.split("\n")) {
            String t = line.trim();
            if (t.contains("ListModeChips(") && !t.startsWith("private fun ListModeChips(")) {
                chipsCalls++;
            }
        }
        check(chipsCalls == 1, "T-15a 名单卡片全 App 只出现 1 处", "调用点=" + chipsCalls);

        String features = slice(main, "private fun FeaturesPage", "private fun TileGrid");
        check(!features.contains("ListModeChips("), "T-15b 功能页不再重复渲染名单卡片", "");
        check(!features.contains("activeList"), "T-15c 功能页不再引用名单文本", "");

        check(!main.contains("TileItem(\"状态栏偏移\""),
                "T-15d 通用页已删除「状态栏偏移」磁贴（像素微移改内置常开）", "");
        String features2 = slice(main, "private fun FeaturesPage", "private fun TileGrid");
        check(features2.contains("\"周期时长\", \"时\"") && main.contains("CycleDialog("),
                "T-15e 周期时长改为通用磁贴 + 弹窗小窗", "");

        check(main.contains("tabProgress = {"), "T-15f 导航栏接收连续页进度", "");
        check(main.contains("currentPageOffsetFraction"), "T-15g 进度源含页内偏移（否则仍是整页跳）", "");

        Path javaRoot = dir.resolve("../../../../").normalize();
        String tabs = read(javaRoot.resolve("com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt"));
        String damped = read(javaRoot.resolve("com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt"));
        check(tabs.contains("snapToValue"), "T-15h 导航栏用 snap 驱动指示器（非弹簧插值）", "");
        check(damped.contains("suspend fun snapToValue"), "T-15i 动画引擎暴露连续置位入口", "");

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 直接调用 Config.parse（私有静态），走的是 SystemUI 里真正的解析路径 */
    private static void parse(String text) throws Exception {
        java.lang.reflect.Method m = Config.class.getDeclaredMethod("parse", String.class);
        m.setAccessible(true);
        m.invoke(null, text);
    }

    /** 取 from（含）到 to（不含）之间的源码片段 */
    private static String slice(String text, String from, String to) {
        int a = text.indexOf(from);
        if (a < 0) return "";
        int b = text.indexOf(to, a);
        return b < 0 ? text.substring(a) : text.substring(a, b);
    }

    private static Set<String> setOf(String... v) {
        return new LinkedHashSet<>(List.of(v));
    }

    private static String read(Path p) throws Exception {
        return Files.readString(p);
    }

    /** AbState(...) 主构造里的 val 数量 */
    private static int countFields(String main) throws Exception {
        int start = main.indexOf("data class AbState(");
        int end = main.indexOf("\n)", start);
        String body = main.substring(start, end);
        Matcher m = Pattern.compile("(?m)^\\s+val\\s+\\w+\\s*:").matcher(body);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** listSaver save 块里逗号分隔的条目数 */
    private static int countSaved(String main) throws Exception {
        int save = main.indexOf("save = { s: AbState ->");
        int listOf = main.indexOf("listOf<Any>(", save);
        int close = main.indexOf(")\n    },", listOf);
        String body = main.substring(listOf + "listOf<Any>(".length(), close);
        int n = 0;
        for (String part : body.split(",")) {
            if (!part.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static int maxIndex(String main) throws Exception {
        Matcher m = Pattern.compile("(?:Bool|Intv|Str)\\((\\d+)").matcher(main);
        int max = -1;
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max;
    }
}
