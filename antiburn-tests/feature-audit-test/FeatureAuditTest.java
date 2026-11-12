import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.9 全功能矩阵审计（纯 JVM，源码级，不依赖 Android 运行时）。
 *
 * <p>守的是四类「功能接不上」的问题，这四类在本项目历史上都真实发生过：</p>
 * <ul>
 *   <li>字段加了没落盘（配置丢）</li>
 *   <li>App 写的键 Hook 不认（开关点了没用）</li>
 *   <li>Hook 有实现但界面没入口（用户关不掉，且不知道它存在 —— 2.7 的 enableShift）</li>
 *   <li>界面有开关但 Hook 不消费（死开关 —— 2.7 之前的 enableIcon）</li>
 * </ul>
 *
 * <p>编译运行：</p>
 * <pre>
 *   SRC=/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn
 *   javac -d /tmp/audittest FeatureAuditTest.java
 *   java -cp /tmp/audittest FeatureAuditTest "$SRC"
 * </pre>
 */
public class FeatureAuditTest {

    private static int pass = 0;
    private static int fail = 0;

    /** Hook 侧会消费 Config 的文件 */
    private static final List<String> HOOK_FILES = List.of(
            "Controller.java", "DouyinHook.java", "AntiburnTile.java",
            "XposedInit.java", "FeatureGuard.java", "ConfStore.java");

    /**
     * 已知「有 Hook 实现但无界面入口」的字段。
     *
     * <p>enableIcon：2.7 删掉错绑总闸的「状态栏图标」磁贴后彻底无入口，且 Hook 侧也无消费，
     * 本质是死字段。保留键只为兼容老 conf，不在界面暴露。</p>
     *
     * <p>enableDramaBottom：红果底部是「完整观看 / 选集」功能入口，必须常驻不隐藏，
     * 磁贴显式设为 enabled=false。字段保留是因为 Hook 侧 hideEnabled 仍按它分支。</p>
     *
     * <p>enablePoem：界面入口在 v2.6 已按要求删除，2.5.2 把 Hook 侧实现也移除了，
     * 该字段永久不会再有入口，因此同时登记在本表与 KNOWN_NO_HOOK。</p>
     */
    private static final Set<String> KNOWN_NO_UI =
            Set.of("enableIcon", "enableDramaBottom", "enablePoem");

    /**
     * 已知「有界面/存储但无 Hook 消费」的字段。
     *
     * <p>enableIcon：2.7 起即为死字段。</p>
     *
     * <p>enablePoem：2.5.2 起 Hook 侧的滚动古诗 Overlay 已整体移除（连同 PoemProvider），
     * 界面在 v2.6 就没有入口了。字段与解析键保留只为向下兼容老 conf，
     * App 侧固定写 0，不会再把「关不掉的行为」带进新版本。</p>
     */
    private static final Set<String> KNOWN_NO_HOOK = Set.of("enableIcon", "enablePoem");

    private static void check(boolean cond, String name, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "  — " + detail));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  — " + detail);
        }
    }

    private static String read(Path p) throws Exception {
        return Files.readString(p);
    }

    private static String slice(String text, String from, String to) {
        int a = text.indexOf(from);
        if (a < 0) {
            return "";
        }
        int b = text.indexOf(to, a + from.length());
        return b < 0 ? text.substring(a) : text.substring(a, b);
    }

    /** 统计匹配次数（Set 版会因匹配文本相同而去重，数量断言必须用这个） */
    private static int count(String text, String regex) {
        int n = 0;
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static Set<String> findAll(String text, String regex, int group) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            out.add(m.group(group));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0]
                : "/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn";
        Path dir = Path.of(src);

        String main = read(dir.resolve("MainActivity.kt"));
        String config = read(dir.resolve("Config.java"));

        // ---------- 领域模型与持久化键 ----------
        Set<String> fields = new LinkedHashSet<>();
        String abState = slice(main, "data class AbState(", "\n) {");
        for (String line : abState.split("\n")) {
            Matcher m = Pattern.compile("^\\s+val (\\w+): [\\w<>]+ = ").matcher(line);
            if (m.find()) {
                fields.add(m.group(1));
            }
        }

        Map<String, String> saved = new LinkedHashMap<>(); // confKey -> state field
        Matcher sm = Pattern.compile("lines\\[\"(\\w+)\"\\] = [^\\n]*?s\\.(\\w+)").matcher(main);
        while (sm.find()) {
            saved.put(sm.group(1), sm.group(2));
        }

        Set<String> loadedKeys = findAll(main, "map\\[\"(\\w+)\"\\]", 1);
        Set<String> parseKeys = findAll(config, "case \"(\\w+)\":", 1);

        System.out.println("== 概览 ==");
        System.out.println("  AbState 字段 " + fields.size()
                + " · conf 写入键 " + saved.size()
                + " · App 读取键 " + loadedKeys.size()
                + " · Hook 解析键 " + parseKeys.size());

        // ---------- T-01 领域字段 ↔ 落盘双向一致 ----------
        System.out.println("== T-01 字段与落盘双向一致 ==");
        Set<String> savedFields = new TreeSet<>(saved.values());
        List<String> noSave = new ArrayList<>();
        for (String f : fields) {
            if (!savedFields.contains(f)) {
                noSave.add(f);
            }
        }
        check(noSave.isEmpty(), "T-01a 每个字段都会写入 conf", "缺写入=" + noSave);

        List<String> ghostSave = new ArrayList<>();
        for (String f : savedFields) {
            if (!fields.contains(f)) {
                ghostSave.add(f);
            }
        }
        check(ghostSave.isEmpty(), "T-01b 写入的字段都存在于 AbState", "幽灵字段=" + ghostSave);

        // ---------- T-02 App 读写的键 Hook 必须认识 ----------
        System.out.println("== T-02 App 键 ↔ Hook 解析键 ==");
        List<String> unknown = new ArrayList<>();
        for (String k : loadedKeys) {
            if (!parseKeys.contains(k)) {
                unknown.add(k);
            }
        }
        check(unknown.isEmpty(), "T-02a App 读取的每个 key 都有 parse 分支", "未识别=" + unknown);

        List<String> unsavedKey = new ArrayList<>();
        for (String k : saved.keySet()) {
            if (!parseKeys.contains(k)) {
                unsavedKey.add(k);
            }
        }
        check(unsavedKey.isEmpty(), "T-02b App 写入的每个 key 都有 parse 分支", "未识别=" + unsavedKey);

        List<String> neverSaved = new ArrayList<>();
        for (String k : loadedKeys) {
            if (!saved.containsKey(k)) {
                neverSaved.add(k);
            }
        }
        check(neverSaved.isEmpty(), "T-02c App 读的每个 key 也会被写回（不会读出空值）",
                "只读不写=" + neverSaved);

        // ---------- T-03 每个开关在 Hook 侧有消费者 ----------
        System.out.println("== T-03 开关 → Hook 消费 ==");
        StringBuilder hookText = new StringBuilder();
        for (String f : HOOK_FILES) {
            Path p = dir.resolve(f);
            if (Files.exists(p)) {
                hookText.append(read(p)).append('\n');
            }
        }
        List<String> noConsumer = new ArrayList<>();
        for (String f : fields) {
            if (!f.startsWith("enable")) {
                continue;
            }
            boolean used = Pattern.compile("Config\\." + f + "\\b").matcher(hookText).find();
            if (!used && !KNOWN_NO_HOOK.contains(f)) {
                noConsumer.add(f);
            }
        }
        check(noConsumer.isEmpty(), "T-03a 每个 enable* 都有 Hook 消费", "无消费=" + noConsumer);

        // 例外表本身要守住：项若已被修好（不再符合豁免条件），失败提示更新表，
        // 避免例外表变成永久豁免。
        List<String> staleHookException = new ArrayList<>();
        for (String f : KNOWN_NO_HOOK) {
            if (Pattern.compile("Config\\." + f + "\\b").matcher(hookText).find()) {
                staleHookException.add(f);
            }
        }
        check(staleHookException.isEmpty(), "T-03b 死字段例外表仍然成立（修好后须从表里删）",
                "已修复仍在表中=" + staleHookException);

        // ---------- T-04 每个开关在界面有入口 ----------
        System.out.println("== T-04 开关 → 界面入口 ==");
        List<String> noEntry = new ArrayList<>();
        for (String f : fields) {
            if (!f.startsWith("enable")) {
                continue;
            }
            boolean hasEntry = main.contains("state." + f) || main.contains("enabled = state." + f);
            if (!hasEntry && !KNOWN_NO_UI.contains(f)) {
                noEntry.add(f);
            }
        }
        check(noEntry.isEmpty(), "T-04a 每个 enable* 都能在界面上找到（用户关得掉）", "无入口=" + noEntry);

        List<String> staleUiException = new ArrayList<>();
        for (String f : KNOWN_NO_UI) {
            if (main.contains("state." + f)) {
                staleUiException.add(f);
            }
        }
        check(staleUiException.isEmpty(), "T-04b 无入口例外表仍然成立", "已加回入口=" + staleUiException);

        // ---------- T-05 磁贴完整性 ----------
        System.out.println("== T-05 功能磁贴 ==");
        List<String> titles = new ArrayList<>();
        List<String> badBind = new ArrayList<>();
        Matcher tm = Pattern.compile(
                "TileItem\\(\"([^\"]+)\", \"([^\"]+)\", \"([^\"]+)\", ([^\\n]+)").matcher(main);
        while (tm.find()) {
            titles.add(tm.group(1));
            Matcher fm = Pattern.compile("state\\.(\\w+)").matcher(tm.group(4));
            while (fm.find()) {
                if (!fields.contains(fm.group(1))) {
                    badBind.add(tm.group(1) + "→" + fm.group(1));
                }
            }
        }
        // 同名磁贴允许跨 App 出现（抖音「右上角」与红果「右上角」是两块不同开关），
        // 真正要防的是同一个配置字段被摆成两块磁贴。
        List<String> bound = new ArrayList<>();
        Matcher bm = Pattern.compile(
                "TileItem\\(\"[^\"]+\", \"[^\"]+\", \"[^\"]+\", [^\\n]*?state\\.(\\w+)").matcher(main);
        while (bm.find()) {
            bound.add(bm.group(1));
        }
        Set<String> uniqBound = new LinkedHashSet<>(bound);
        check(bound.size() == uniqBound.size(), "T-05a 同一配置字段不绑两块磁贴",
                "绑定 " + bound.size() + " 处，去重后 " + uniqBound.size() + " · 标题 " + titles.size() + " 块");
        check(badBind.isEmpty(), "T-05b 磁贴绑定的字段都真实存在", "错绑=" + badBind);
        check(titles.size() >= 10, "T-05c 磁贴数量未异常减少", "共 " + titles.size() + " 块");

        // ---------- T-06 页面结构与二级路由 ----------
        System.out.println("== T-06 页面与二级路由 ==");
        int tabCount = count(main, "NavItem\\(\"");
        check(tabCount == 3, "T-06a 底部导航 3 个 tab", "实际=" + tabCount);

        Matcher pc = Pattern.compile("pageCount = \\{ (\\d+) \\}").matcher(main);
        boolean pcFound = pc.find();
        check(pcFound && pc.group(1).equals(String.valueOf(tabCount)),
                "T-06b Pager 页数 == tab 数",
                pcFound ? ("tab=" + tabCount + " pager=" + pc.group(1)) : "未找到 pageCount");

        Set<String> routes = new LinkedHashSet<>();
        routes.addAll(findAll(main, "onOpen\\(\"(\\w+)\"\\)", 1));
        routes.addAll(findAll(main, "openSub\\(\"(\\w+)\"\\)", 1));
        Set<String> branches = findAll(main, "\"(\\w+)\" -> \\w+Page\\(", 1);
        List<String> brokenRoute = new ArrayList<>();
        for (String r : routes) {
            if (!branches.contains(r)) {
                brokenRoute.add(r);
            }
        }
        check(!routes.isEmpty(), "T-06c 存在二级入口", "路由=" + routes);
        check(brokenRoute.isEmpty(), "T-06d 每个二级入口都有落地页（不会点出白屏）", "断链=" + brokenRoute);

        Set<String> titledRoutes = findAll(main, "\"(\\w+)\" -> \"[^\"]+\"", 1);
        List<String> noTitle = new ArrayList<>();
        for (String r : routes) {
            if (!titledRoutes.contains(r)) {
                noTitle.add(r);
            }
        }
        check(noTitle.isEmpty(), "T-06e 每个二级页都有标题", "缺标题=" + noTitle);

        // ---------- T-07 首页关键入口仍在 ----------
        System.out.println("== T-07 首页入口 ==");
        check(main.contains("title = \"周期时长\""), "T-07a 首页保留「周期时长」唯一入口", "");
        check(count(main, "ListModeChips\\(") == 2,
                "T-07b 名单卡片全局只有 1 处调用（1 定义 + 1 调用）", "");

        // ---------- T-08 混装检测：App 已升级、SystemUI 仍跑旧 Hook ----------
        // 这是真实发生过的一次排障：App 是 2.9.1、SystemUI 进程还是装包前启动的，
        // 跑着 2.7 的 dex，list_mode 等新键全被忽略，日志却看不出任何异常。
        // 版本号必须出现在诊断行里，否则下次还只能靠「日志格式像哪一版」去猜。
        System.out.println("== T-08 版本标识（识别 App 新 / Hook 旧） ==");
        String controller = read(dir.resolve("Controller.java"));
        check(config.contains("case \"app_ver\":"), "T-08a Config 解析 app_ver 键", "");
        check(config.contains("BuildConfig.VERSION_NAME"), "T-08b Hook 侧诊断输出自身版本", "");
        check(controller.contains("\" ver=\" + BuildConfig.VERSION_NAME"),
                "T-08c SYS 行带版本号", "");
        check(controller.contains("pauseReason("), "T-08d SHIFT 停止按原因分类打印", "");
        check(!controller.contains("SHIFT 总开关关闭"),
                "T-08e 不再把灭屏 / 名单命中 / 总开关都写成「总开关关闭」", "");
        check(main.contains("withVer[\"app_ver\"]"), "T-08f App 增量写入带 app_ver", "");
        check(count(main, "lines\\[\"app_ver\"\\]") == 1, "T-08g App 全量写入带 app_ver", "");

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
