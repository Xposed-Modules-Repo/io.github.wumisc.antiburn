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
     *
     * <p>enableShift：v2.6.5 按需求删除「状态栏偏移」磁贴，像素微移改为内置常开。
     * 字段与 Saver 索引保留只为兼容老配置与 Bundle 恢复；Hook 侧 Config.parse 固定 true、
     * App 侧 AbConf.load/save 也固定为开，因此它仍有 Hook 消费（不在 KNOWN_NO_HOOK 里），
     * 只是不再需要界面入口 —— 用户也不该再被一个容易误触的开关困扰。</p>
     */
    private static final Set<String> KNOWN_NO_UI =
            Set.of("enableIcon", "enableDramaBottom", "enablePoem", "enableShift");

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
        String features = slice(main, "private fun FeaturesPage", "private fun TileGrid");
        check(features.contains("\"周期时长\", \"时\""),
                "T-07a 周期时长入口在功能区通用磁贴（不在首页）", "");
        check(!main.contains("ListRow(\"周期时长\""),
                "T-07c 首页不再有周期时长第二处入口", "");
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

        // ---------- T-09 总开关未开时的可见性 ----------
        // 连续三份真机日志里，用户都在「运行方式」卡片上来回切引擎，而 status 全程 false。
        // 功能没坏，是「切了也不会有反应」的原因（总闸关着）在界面上根本看不出来——
        // 与旧版检测属同一类问题：不可见的不生效状态。这里把「必须说」钉成断言。
        System.out.println("== T-09 总开关未开时的提示 ==");
        check(count(main, "MasterOffNotice\\(") == 2,
                "T-09a 总开关提示块 1 定义 + 1 调用", "实际=" + count(main, "MasterOffNotice\\("));
        int guard = main.indexOf("if (!state.enableStatus) {");
        check(guard > 0 && main.substring(guard, Math.min(guard + 140, main.length()))
                        .contains("MasterOffNotice("),
                "T-09b 提示只在总开关关闭时出现", "");
        check(main.contains("it.copy(enableStatus = true)"),
                "T-09c 提示上的按钮直接开总闸，不用滚下去找开关", "");
        int offCall = main.indexOf("MasterOffNotice(dark) {");
        int staleCall = main.indexOf("hook = hook,");
        check(offCall > 0 && staleCall > 0 && offCall < staleCall,
                "T-09d 提示排在旧版检测之前（总闸是第一顺位原因）",
                "off=" + offCall + " stale=" + staleCall);
        check(main.contains("不打开不会有任何效果") && main.contains("打开才会生效"),
                "T-09e 两条引擎的切换提示都会点名总开关", "");
        check(main.contains("if (enabled) UiPalette.accent(dark) else UiPalette.warn(dark)"),
                "T-09f 「已关闭」用警示色，不再与「已开启」同色", "");

        // ---------- T-10 磁贴参数顺序 / 状态栏独立开关 ----------
        // 真机反馈：「总开关打开后功能区单独功能失效」「改一下状态栏偏移就自己关闭」。
        // 前者是 TileItem 的尾随 lambda 被绑到 navClick（最后一个参数），
        // onToggle 成了空实现、isEntry 恒真、右上角永远是「›」；
        // 后者是状态栏没有独立开关，总闸一开必然连同导航栏一起隐藏。
        System.out.println("== T-10 磁贴参数顺序 / 状态栏独立开关 ==");
        int onClick = main.indexOf("val onToggle: (Boolean) -> Unit");
        int onNav = main.indexOf("val navClick: (() -> Unit)?");
        check(onClick > 0 && onNav > 0 && onNav < onClick,
                "T-10a onToggle 是 TileItem 最后一个参数（尾随 lambda 才能绑对）",
                "navClick@" + onNav + " onToggle@" + onClick);
        check(count(main, "navClick = \\{") == 1,
                "T-10b 只有 1 处显式命名参数的入口磁贴（周期时长），其余开关不得误用入口",
                "出现=" + count(main, "navClick = \\{"));
        check(main.contains("val enableStatusBar: Boolean = true"),
                "T-10c AbState 新字段默认 true（老配置升级行为不变）", "");
        check(main.contains("TileItem(\"状态栏\""), "T-10d 通用页有「状态栏」磁贴", "");
        check(config.contains("case \"enable_status_bar\""), "T-10e Config 解析 enable_status_bar", "");
        check(config.contains("public static volatile boolean enableStatusBar = true"),
                "T-10f Hook 侧字段默认 true（老配置缺失键行为不变）", "");
        check(controller.contains("Config.enableStatusBar"),
                "T-10g applyHide 按开关决定隐藏还是还原状态栏", "");
        check(controller.contains("statusChildrenHidden()"),
                "T-10h 关掉开关时逐个还原子容器（父 alpha 恢复不等于子恢复）", "");
        check(controller.contains("lastStatusBarSwitch != Config.enableStatusBar"),
                "T-10i 开关一变立刻重画，不等整个隐藏相位结束", "");
        check(count(main, "enable_status_bar") >= 3,
                "T-10j App 侧 load/save/diff 三处都带 enable_status_bar",
                "次数=" + count(main, "enable_status_bar"));

        // ---------- T-11 Hook 回调异常兜底（防 SystemUI 崩 → LSPosed 安全模式）----------
        // 宿主是别人的进程。回调里任何未捕获异常都会冒到 SystemUI 主线程，
        // 连续崩几次 LSPosed 就把模块丢进安全模式，用户看到的是「所有模块突然失效」。
        System.out.println("== T-11 Hook 回调异常兜底 ==");
        check(count(controller, "hookFail\\(") >= 5,
                "T-11a 4 个 attach 回调 + 1 处定义，全部包住",
                "出现=" + count(controller, "hookFail\\("));
        check(count(controller, "protected void afterHookedMethod") == 4,
                "T-11b 四个 attach 回调都在（数量没被重构弄丢）",
                "实际=" + count(controller, "protected void afterHookedMethod"));
        check(controller.contains("private static void hookFail(String where, Throwable t)"),
                "T-11c hookFail 本身不抛（内部再包一层 try）", "");
        check(controller.contains("\"HOOK FAIL \""),
                "T-11d 失败会写 diag，下次贴日志能直接看到崩在哪一步", "");

        // ---------- T-12 v2.6.5 六项界面 / 交互调整 ----------
        System.out.println("== T-12 v2.6.5 界面与交互 ==");
        check(main.contains("Text(\"防烧屏总开关\""),
                "T-12a 首页总开关改名「防烧屏总开关」", "");
        check(main.contains("总闸：关闭后所有隐藏与遮盖都不生效"),
                "T-12b 总开关描述精简为一行", "");
        check(main.contains("支持 LSPosed / 无障碍遮盖双引擎"),
                "T-12c 首页副标题改为双引擎中性文案（免 root 用户不再被劝退）", "");
        check(!main.contains("TileItem(\"状态栏偏移\""),
                "T-12d 通用页删除「状态栏偏移」磁贴", "");
        check(main.contains("enableShift = true,") && config.contains("sh = true"),
                "T-12e 像素微移内置常开（App 读与 Hook 解析都固定 true）", "");
        check(main.contains("if (showCycleDialog)") && main.contains("CycleDialog("),
                "T-12f 周期时长点磁贴弹小窗（不是跳二级页）", "");
        check(!main.contains("\"cycle\" -> CyclePage("),
                "T-12g 旧二级页路由已清理（不留进不去的死页面）", "");
        check(main.contains("private fun RoadmapCard(") && main.contains("RoadmapCard(dark)"),
                "T-12h 适配计划卡片 1 定义 + 1 调用", "");
        check(main.contains("快手") && main.contains("哔哩哔哩") && main.contains("当前版本尚未支持"),
                "T-12i 占位卡片点名快手/B站且写明规划中", "");
        check(main.contains("不提供开关"),
                "T-12j 占位卡片明确不放假开关", "");
        check(!main.contains("LSPosed + root 环境") && main.contains("免 root 无障碍两种运行方式"),
                "T-12k 关于页不再把运行方式写死成单一环境", "");
        String overlayBlock = slice(main, "if (overlay) {", "闭合 if (overlay)");
        check(overlayBlock.contains("\"可选：Shizuku 增强\""),
                "T-12l 「可选 Shizuku 增强」位于 overlay 分支内", "");
        check(count(main, "\"可选：Shizuku 增强\"") == 1,
                "T-12m Shizuku 增强文案全 App 只 1 处（LSPosed 模式不展示）", "");

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
