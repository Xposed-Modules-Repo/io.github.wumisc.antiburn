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
    // v2.8.2 enableAppOverlay：消费方是 App 进程的无障碍遮盖服务（直接读 conf），
    // 不经过 Config / Hook 进程，因此登记为「无 Hook 消费」而非死字段。
    private static final Set<String> KNOWN_NO_HOOK =
            Set.of("enableIcon", "enablePoem", "enableAppOverlay");

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

    /** 统计匹配次数（Set 版会因匹配文本相同而去重，数量断言必须用这个）。参数是正则。 */
    /** 版本号单调检查：避免每次升版都要改一遍测试（v2.7.6 起遗留的假红）。 */
    private static boolean versionAtLeast(String gradle, int vc, String name) {
        Matcher cm = Pattern.compile("versionCode\\s+(\\d+)").matcher(gradle);
        Matcher nm = Pattern.compile("versionName\\s+\"([^\"]+)\"").matcher(gradle);
        if (!cm.find() || !nm.find() || Integer.parseInt(cm.group(1)) < vc) {
            return false;
        }
        try {
            String[] x = nm.group(1).split("\\."), y = name.split("\\.");
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                int xi = i < x.length ? Integer.parseInt(x[i].trim()) : 0;
                int yi = i < y.length ? Integer.parseInt(y[i].trim()) : 0;
                if (xi != yi) {
                    return xi > yi;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int count(String text, String regex) {
        int n = 0;
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * 字面量计数：用于含正则元字符（如 {@code init(lpparam)}、{@code catch (Throwable}）的串。
     * 直接把它交给 {@link #count} 会被当成捕获组甚至抛 PatternSyntaxException。
     */
    private static int countLiteral(String text, String literal) {
        int n = 0, from = 0;
        while ((from = text.indexOf(literal, from)) >= 0) {
            n++;
            from += literal.length();
        }
        return n;
    }

    /**
     * 去掉 Java 块注释与行注释，让「分支里到底有没有真的调用某方法」这类断言
     * 只针对真实代码。说明性注释常写「不调用 DouyinHook.init」「不修改 setTranslation*」，
     * 不去注释会把注释里的词误判成代码。
     */
    private static String stripComments(String s) {
        String noBlock = s.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("(?m)//[^\\n]*", "");
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

        // v2.7.0：迁移键（load 里以 `|| b(map["k"])` 兼容分支出现的旧共用键）通过新键持久化，
        // 豁免「写回同名键」，但其映射目标新键必须被写回，防止值真的丢失。
        List<String> legacyKeys = new ArrayList<>();
        for (String k : loadedKeys) {
            if (main.contains("|| b(map[\"" + k + "\"])")) {
                legacyKeys.add(k);
            }
        }
        List<String> neverSaved = new ArrayList<>();
        for (String k : loadedKeys) {
            if (legacyKeys.contains(k)) {
                continue; // 兼容读：值由同一行的新键写回
            }
            if (!saved.containsKey(k)) {
                neverSaved.add(k);
            }
        }
        check(neverSaved.isEmpty(), "T-02c App 读的每个 key 也会被写回（迁移键除外，值由新键持久化）",
                "只读不写=" + neverSaved);
        List<String> legacyLost = new ArrayList<>();
        for (String k : legacyKeys) {
            boolean covered = false;
            for (String nk : saved.keySet()) {
                if (main.contains("b(map[\"" + nk + "\"]) || b(map[\"" + k + "\"])")) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                legacyLost.add(k);
            }
        }
        check(legacyLost.isEmpty(), "T-02c2 每个迁移键的值由同名新键写回（不丢值）",
                "迁移无落点=" + legacyLost);

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

        // ---------- T-05 开关行完整性（v2.7.8：磁贴 -> 单行列表） ----------
        System.out.println("== T-05 功能开关行 ==");
        List<String> titles = new ArrayList<>();
        Matcher tm = Pattern.compile("OptionRow\\(\"([^\"]+)\"").matcher(main);
        while (tm.find()) {
            titles.add(tm.group(1));
        }
        // 按 OptionRow( 分块：每块只到下一行开关之前，避免跨行误配。
        // 字段来源两种：checked = state.X（多数）与 s.copy(X = on)（快手/B站共用子页）。
        // 同一块内可能同时匹配到两处（既写 checked 又写 copy），先去重再计入。
        List<String> bound = new ArrayList<>();
        List<String> badBind = new ArrayList<>();
        String[] optBlocks = main.split("OptionRow\\(");
        for (int i = 1; i < optBlocks.length; i++) {
            Set<String> inBlock = new LinkedHashSet<>();
            Matcher fm = Pattern.compile("checked = state\\.(\\w+)|s\\.copy\\((\\w+) = on\\)")
                    .matcher(optBlocks[i]);
            while (fm.find()) {
                inBlock.add(fm.group(1) != null ? fm.group(1) : fm.group(2));
            }
            for (String f : inBlock) {
                bound.add(f);
                if (!fields.contains(f)) {
                    badBind.add(f);
                }
            }
        }
        Set<String> uniqBound = new LinkedHashSet<>(bound);
        check(bound.size() == uniqBound.size(), "T-05a 同一配置字段不绑两行开关",
                "绑定 " + bound.size() + " 处，去重后 " + uniqBound.size() + " · 标题 " + titles.size() + " 行");
        check(badBind.isEmpty(), "T-05b 开关行绑定的字段都真实存在", "错绑=" + badBind);
        check(titles.size() >= 20, "T-05c 开关行数量未异常减少", "共 " + titles.size() + " 行");
        check(!stripComments(main).contains("TileItem")
                        && !stripComments(main).contains("TileGrid"),
                "T-05d 旧磁贴组件已删除（六参 + 尾随 lambda 误绑隐患连根拔掉）", "");

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
        String features = slice(main, "private fun FeaturesPage", "private fun AboutPage");
        check(features.contains("ListRow(\"周期时长\"") && main.contains("CycleDialog("),
                "T-07a 周期时长入口在「通用」二级页 + 弹窗小窗（不在首页）", "");
        check(count(main, "ListRow\\(\"周期时长\"") == 1,
                "T-07c 周期时长全 App 只有 1 处入口", "出现=" + count(main, "ListRow\\(\"周期时长\""));
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
        System.out.println("== T-10 开关行参数安全 / 状态栏独立开关 ==");
        check(main.contains("private fun OptionRow(")
                        && main.contains("onCheckedChange: (Boolean) -> Unit"),
                "T-10a 开关行用显式命名参数 onCheckedChange（无尾随 lambda 误绑空间）", "");
        check(!stripComments(main).contains("TileItem")
                        && !stripComments(main).contains("navClick")
                        && !stripComments(main).contains("TileGrid"),
                "T-10b 六参磁贴数据类 / navClick / TileGrid 已从代码层移除", "");
        check(main.contains("val enableStatusBar: Boolean = true"),
                "T-10c AbState 新字段默认 true（老配置升级行为不变）", "");
        check(main.contains("OptionRow(\"状态栏\""), "T-10d 通用二级页有「状态栏」开关行", "");
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
        check(count(controller, "protected void afterHookedMethod") == 5,
                "T-11b 五个 attach 回调都在（v2.7.4 新增 Flyme PhoneStatusBarView）",
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
        check(main.contains("快手") && main.contains("哔哩哔哩") && main.contains("已支持"),
                "T-12i 说明卡点名快手/B站且写明已支持", "");
        check(main.contains("点开「快手」「B站」逐项开启对应开关")
                        && !stripComments(main).contains("不提供开关"),
                "T-12j 说明卡引导到真实子页（不再是「不提供开关」占位）", "");
        check(!main.contains("LSPosed + root 环境") && main.contains("免 root 无障碍两种运行方式"),
                "T-12k 关于页不再把运行方式写死成单一环境", "");
        String overlayBlock = slice(main, "if (overlay) {", "闭合 if (overlay)");
        check(overlayBlock.contains("\"可选：Shizuku 增强\""),
                "T-12l 「可选 Shizuku 增强」位于 overlay 分支内", "");
        check(count(main, "\"可选：Shizuku 增强\"") == 1,
                "T-12m Shizuku 增强文案全 App 只 1 处（LSPosed 模式不展示）", "");

        // ---------- T-13 v2.6.6 红果「左滑查看详情」独立开关 ----------
        System.out.println("== T-13 红果左滑查看详情开关 ==");
        String douyin = read(dir.resolve("DouyinHook.java"));
        check(main.contains("val enableDramaSwipe: Boolean = false"),
                "T-13a AbState 新增 enableDramaSwipe（默认关闭）", "");
        check(count(main, "enable_drama_swipe") >= 3,
                "T-13b App 侧 load/save/diff 三处都带 enable_drama_swipe",
                "次数=" + count(main, "enable_drama_swipe"));
        check(config.contains("case \"enable_drama_swipe\""),
                "T-13c Hook 侧 Config.parse 认识 enable_drama_swipe", "");
        check(config.contains("enableDramaSwipe = drSwipe;"),
                "T-13d Config.parse 提交该字段", "");
        check(douyin.contains("Config.enableDramaSwipe"),
                "T-13e Hook 侧消费该开关（真隐藏，不是死开关）", "");
        check(douyin.contains("swipeTextMatch") && douyin.contains("查看详情"),
                "T-13f 按文案识别引导条（含关键词）", "");
        check(douyin.contains("swipePosOk"),
                "T-13g 识别带位置约束（右侧中部），避免误伤别处「查看详情」按钮", "");
        check(douyin.contains("containsVideoSurface"),
                "T-13h 排除视频画面容器（不黑屏）", "");
        check(douyin.contains("if (isDrama)") && douyin.contains("collectSwipeHintNode"),
                "T-13i 只在红果进程收集，不影响抖音", "");
        check(main.contains("OptionRow(\"左滑查看详情\""),
                "T-13j 红果页有该开关磁贴", "");
        check(main.contains("state.enableDramaSwipe"),
                "T-13k 磁贴绑定真实字段（用户看得见开/关）", "");
        check(douyin.contains("\" | swipe=x\""),
                "T-13l SCAN 诊断行输出命中数量，真机能确认识别是否成功", "");

        // ---------- T-14 v2.6.8 快手 / 哔哩哔哩真功能全链路 ----------
        // v2.6.7 是 Step 1 诊断骨架（零隐藏/零开关/零配置键）；本轮起正式接入真隐藏：
        // 6 个新键走完整配置链路、两条分发真的调用 DouyinHook、复用已验证的几何识别、
        // overlay 降级、红果 swipe 与抖音直播判定严格隔离。任何一条被破坏都意味着
        // 又退回到「假开关 / 装了没效果」或误伤视频画面。
        System.out.println("== T-14 快手/B站真功能全链路（v2.6.8）==");
        String xposed = read(dir.resolve("XposedInit.java"));
        String probe = read(dir.resolve("VideoAppProbe.java"));
        String confStore = read(dir.resolve("ConfStore.java"));
        String scope = read(dir.resolve("../../../../../res/values/arrays.xml"));
        String manifest = read(dir.resolve("../../../../../AndroidManifest.xml"));
        String gradle = read(dir.resolve("../../../../../../../build.gradle"));
        String xposedCode = stripComments(xposed);
        String probeCode = stripComments(probe);
        String scopeCode = stripComments(scope);
        String douyinCode = stripComments(douyin);   // douyin 由 T-13 读入

        // v2.8.0：真机日志实证用户 OPPO ColorOS 装的是抖音 .mobile / B站 com.bilibili.app.in，
        // 旧的「精确 6 项主包 + 变体不启用」会直接导致这两个进程不注入、App 内隐藏 100% 失效。
        // 断言改为验证「主包仍在 + 真机实证渠道包已启用」。
        check(scope.contains("<item>com.smile.gifmaker</item>")
                        && scope.contains("<item>tv.danmaku.bili</item>")
                        && scope.contains("<item>com.ss.android.ugc.aweme</item>")
                        && scope.contains("<item>com.phoenix.read</item>"),
                "T-14a 作用域含四个主包", "");
        check(scopeCode.contains("<item>com.ss.android.ugc.aweme.mobile</item>")
                        && scopeCode.contains("<item>com.bilibili.app.in</item>"),
                "T-14b 真机实证的两个渠道包已进入作用域（否则 LSPosed 不注入）", "");
        check(scopeCode.contains("<item>com.ss.android.ugc.aweme.lite</item>")
                        && scopeCode.contains("<item>com.kuaishou.nebula</item>")
                        && scopeCode.contains("<item>tv.danmaku.bilibilihd</item>")
                        && scopeCode.contains("<item>com.bilibili.app.blue</item>"),
                "T-14c 常见变体（极速版/HD/概念版）一并登记，供用户勾选", "");

        // 分发：抖音/红果/快手/B站 四路都进 DouyinHook（真隐藏），快手/B站 另保留诊断探针
        check(countLiteral(xposedCode, "DouyinHook.init(lpparam)") == 4,
                "T-14d 四条分发都接入 DouyinHook 真隐藏（复用已验证几何骨架）",
                "次数=" + countLiteral(xposedCode, "DouyinHook.init(lpparam)"));
        check(countLiteral(xposedCode, "VideoAppProbe.init(lpparam,") == 2,
                "T-14e 快手/B站保留 View 树诊断探针供真机精调", "");
        check(xposed.contains("INJECTED Kuaishou") && xposed.contains("INJECTED Bilibili")
                        && xposed.contains("stage=2 hide-ready"),
                "T-14f 新分发出注入日志并标明已接入隐藏", "");
        // v2.8.0：分发改用 VideoApps.Kind 单一真源，不再精确 equals 包名
        String ksBlock = slice(xposedCode, "kind == VideoApps.Kind.KUAISHOU", "kind == VideoApps.Kind.BILIBILI");
        String biliBlock = slice(xposedCode, "kind == VideoApps.Kind.BILIBILI", "\n    }\n}");
        check(ksBlock.contains("DouyinHook.init") && ksBlock.contains("VideoAppProbe.init")
                        && !ksBlock.contains("Controller.init"),
                "T-14g 快手分支真隐藏 + 诊断并存，且不动 SystemUI 逻辑", "");
        check(biliBlock.contains("DouyinHook.init") && biliBlock.contains("VideoAppProbe.init")
                        && !biliBlock.contains("Controller.init"),
                "T-14h B站分支真隐藏 + 诊断并存，且不动 SystemUI 逻辑", "");
        check(xposedCode.contains("Controller.init(lpparam)"),
                "T-14i SystemUI 分发保持不变", "");

        // 6 个新键全链路：AbState 字段 / Saver / load / save / diff / default / parse / Hook / UI
        String[] ksKeys = {"enable_kuaishou", "enable_bilibili",
                "enable_ks_right", "enable_ks_top", "enable_ks_bottom", "enable_ks_corner",
                "enable_bili_right", "enable_bili_top", "enable_bili_bottom", "enable_bili_corner"};
        String[] ksFlds = {"enableKuaishou", "enableBilibili",
                "enableKsRight", "enableKsTop", "enableKsBottom", "enableKsCorner",
                "enableBiliRight", "enableBiliTop", "enableBiliBottom", "enableBiliCorner"};
        boolean chainOk = true;
        StringBuilder miss = new StringBuilder();
        for (int i = 0; i < ksKeys.length; i++) {
            String k = ksKeys[i], f = ksFlds[i];
            if (!main.contains("val " + f + ": Boolean = false")) { chainOk = false; miss.append(" field:").append(k); }
            if (!main.contains("map[\"" + k + "\"]") && !main.contains("migratedB(map, \"" + k + "\",")) {
                chainOk = false; miss.append(" load:").append(k);
            }
            if (!main.contains("lines[\"" + k + "\"]")) { chainOk = false; miss.append(" save:").append(k); }
            if (!main.contains("putB(\"" + k + "\"")) { chainOk = false; miss.append(" diff:").append(k); }
            if (!confStore.contains("\"" + k + "=0")) { chainOk = false; miss.append(" default:").append(k); }
            if (!config.contains("case \"" + k + "\":")) { chainOk = false; miss.append(" parse:").append(k); }
            if (!config.contains("public static volatile boolean " + f)) { chainOk = false; miss.append(" cfgField:").append(k); }
            if (!douyinCode.contains("Config." + f)) { chainOk = false; miss.append(" hook:").append(k); }
            if (!main.contains("state." + f)) { chainOk = false; miss.append(" ui:").append(k); }
        }
        check(chainOk, "T-14j v2.7.0 十个键全链路一致（字段/load/save/diff/default/parse/cfg/hook/ui）",
                miss.toString());
        // Saver 新索引追加在末尾（28..37），不插队
        check(main.contains("Bool(28)") && main.contains("Bool(29)") && main.contains("Bool(30)")
                        && main.contains("Bool(34)") && main.contains("Bool(36)") && main.contains("Bool(37)"),
                "T-14k Saver 新索引 28..37 追加在末尾（老 Bundle 不串位）", "");

        // Hook 隔离：快手/B站 用 isVideoApp 分支复用几何容器，不写新识别规则、不串红果/抖音专属逻辑
        check(douyinCode.contains("isVideoApp") && douyinCode.contains("isKuaishou"),
                "T-14l DouyinHook 用目标标识分派（复用几何识别，非每 App 新写阈值）", "");
        check(douyinCode.contains("!isVideoApp"),
                "T-14m 直播判定与直播隐藏排除快手/B站（未标定 App 不串直播三区）", "");
        check(douyinCode.contains("isDrama && Config.enableDramaSwipe"),
                "T-14n 红果「左滑查看详情」仍在 isDrama 门控内", "");
        check(douyinCode.contains("ConfStore.ENGINE_OVERLAY.equals(Config.engine)"),
                "T-14o 遮盖引擎下 Hook 整体让位（overlay 用户不误隐藏）", "");
        check(douyinCode.contains("\" | app=\""),
                "T-14p SCAN 诊断带 app= 标识，真机可区分四个进程", "");

        // UI：两个真子页 + 总开关/共用区域开关 + overlay 降级
        check(main.contains("private fun VideoAppSubPage(") && countLiteral(main, "VideoAppSubPage(") == 3,
                "T-14q 功能页有快手/B站独立子页（1 定义 + 2 调用）",
                "次数=" + countLiteral(main, "VideoAppSubPage("));
        check(main.contains("\"feat-general\"") && main.contains("\"feat-douyin\"")
                        && main.contains("\"feat-drama\"") && main.contains("\"feat-kuaishou\"")
                        && main.contains("\"feat-bilibili\""),
                "T-14r 五个功能分类各有一个二级菜单路由（通用/抖音/红果/快手/B站）", "");
        check(main.contains("val overlay = state.isOverlay") && main.contains("enabled = !overlay"),
                "T-14s overlay 引擎下整页磁贴置灰（不放假开关）", "");
        check(main.contains("快手、哔哩哔哩已支持") && main.contains("点开「快手」「B站」逐项开启"),
                "T-14t 说明卡改为「已支持 + 引导子页」，不再是占位", "");

        // 探针仍只读（隐藏由 DouyinHook 负责），沿用 Step 1 的零隐藏边界
        check(!probeCode.contains("setAlpha(") && !probeCode.contains("setVisibility(")
                        && !probeCode.contains("transparentDeep") && !probeCode.contains("setTranslation"),
                "T-14u 探针只读，不修改任何 View 属性（零隐藏）", "");
        check(probeCode.contains("if (!Config.enableLog)") && probeCode.contains("Config.reload()"),
                "T-14v 探针受诊断日志开关控制，关闭时零开销", "");
        check(countLiteral(probeCode, "catch (Throwable") >= 5,
                "T-14w 全链路异常兜底，异常不冒到宿主主线程",
                "catch 数=" + countLiteral(probeCode, "catch (Throwable"));

        check(versionAtLeast(gradle, 38, "2.7.5"),
                "T-14x 版本号不低于 vc38 / 2.7.5（单调升，升版无需改测试）", "");
        check(manifest.contains("xposedscope") && manifest.contains("快手、哔哩哔哩各区域"),
                "T-14y 模块描述如实写明已支持快手/B站", "");

        // ---------- T-15 v2.6.9 真机反馈三项修复 ----------
        System.out.println("== T-15 v2.6.9 真机反馈修复 ==");
        check(douyinCode.contains("private static boolean safeToHide(View v, String what)"),
                "T-15a 隐藏前有内容安全校验（v2.7.0 升级为按区域形状限定）", "");
        check(douyinCode.contains("containsVideoSurface(v, 0)") && douyinCode.contains("rw < 0.30f")
                        && douyinCode.contains("rh < 0.18f") && !douyinCode.contains("countDescendants"),
                "T-15b 安全校验 = 视频画面排除 + 形状限定（v2.6.9 误杀源 countDescendants 已删）", "");
        check(countLiteral(douyinCode, "hideSafe(") >= 6,
                "T-15c 视频 App 五区 + 加号都走安全隐藏",
                "次数=" + countLiteral(douyinCode, "hideSafe("));
        check(douyinCode.contains("isVideoApp && !safeToHide(v, what)"),
                "T-15d 安全校验只对视频 App 生效（抖音 / 红果行为不变）", "");
        check(douyinCode.contains("collectCenterPlusNode") && douyinCode.contains("centerPlusList"),
                "T-15e 底部中央「+」有专用收集器", "");
        check(douyinCode.contains("PLUS x") && douyinCode.contains(" | plus=x"),
                "T-15f 加号命中数进诊断，真机可验证", "");
        check(douyinCode.contains("Config.shouldIntervene(selfPkg)"),
                "T-15g App 内隐藏补齐白名单 / 总开关 / 引擎豁免", "");
        check(douyinCode.contains("!videoLivePage") && douyinCode.contains("isLiveTextPage"),
                "T-15h 直播特征页整页跳过 App 内区域隐藏", "");
        check(douyinCode.contains("hideSafe(v, \"plus\")") && douyinCode.contains("isVideoApp && hideBottom"),
                "T-15i 加号归入底部开关，关掉立即恢复", "");
        check(countLiteral(douyinCode, "videoLivePage = isLiveTextPage(root, 0)") == 1,
                "T-15j 直播判定只在 rescan 提交一次", "");
        check(versionAtLeast(gradle, 38, "2.7.5"),
                "T-15k 版本号不低于 vc38 / 2.7.5（单调升，升版无需改测试）", "");

        // ---------- T-16 v2.7.0 正式版 ----------
        System.out.println("== T-16 v2.7.0 正式版 ==");
        check(!stripComments(main).contains("enableVideo"),
                "T-16a 共用开关字段已删除（快手 / B站配置完全独立）", "");
        check(main.contains("migratedB(map, \"enable_ks_right\", \"enable_video_right\")")
                        && config.contains("case \"enable_video_right\""),
                "T-16b 旧共用键仅作新键缺失时的迁移兜底（老用户升级行为不变）", "");
        check(douyinCode.contains("Config.enableKsRight : Config.enableBiliRight")
                        && douyinCode.contains("Config.enableKsBottom : Config.enableBiliBottom"),
                "T-16c Hook 按 App 分派各自开关（互不影响）", "");
        check(main.contains("各一套独立开关，互不影响"),
                "T-16d UI 如实写明两应用开关独立", "");
        check(main.contains("为人民服务") && main.contains("为天地立心")
                        && main.contains("为生民立命") && main.contains("为往圣继绝学")
                        && main.contains("为万世开太平"),
                "T-16e 张载四句 + 为人民服务已展示在三个页面标题旁", "");
        check(main.contains("护眼防烧屏") && main.contains("第一目标"),
                "T-16f 护眼防烧屏核心价值观入首页与关于页", "");
        check(!main.contains("lines[\"enable_video") && !main.contains("putB(\"enable_video"),
                "T-16g 落盘只写新键，不再写旧共用键", "");
        check(countLiteral(douyinCode, "hideSafe(") >= 6,
                "T-16h 六处隐藏调用仍全部走安全校验",
                "次数=" + countLiteral(douyinCode, "hideSafe("));
        // v2.7.2：角落阈值从 0.20 放宽到 0.35 —— 自绘页的角落按钮是一条竖排工具条，
        // 高度会超过 20%。仍远小于内容容器，误伤面不变。
        check(douyinCode.contains("rw < 0.35f && rh < 0.35f"),
                "T-16i 角落按钮按小控件形状限定（大块内容容器不误伤）", "");

        // ---------- T-17 v2.7.1 新旧配置迁移回弹修复 ----------
        System.out.println("== T-17 v2.7.1 新旧配置迁移回弹修复 ==");
        check(main.contains("private fun migratedB(map: Map<String, String>, key: String, legacy: String)")
                        && main.contains("if (map.containsKey(key)) b(map[key]) else b(map[legacy])"),
                "T-17a App 迁移规则为新键存在即优先，不再使用新值 OR 旧值", "");
        String[] independentKeys = {"enable_ks_right", "enable_ks_top", "enable_ks_bottom", "enable_ks_corner",
                "enable_bili_right", "enable_bili_top", "enable_bili_bottom", "enable_bili_corner"};
        String[] migrationLegacyKeys = {"enable_video_right", "enable_video_top", "enable_video_bottom", "enable_video_corner",
                "enable_video_right", "enable_video_top", "enable_video_bottom", "enable_video_corner"};
        boolean migrationCalls = true;
        for (int i = 0; i < independentKeys.length; i++) {
            migrationCalls &= main.contains("migratedB(map, \"" + independentKeys[i] + "\", \"" + migrationLegacyKeys[i] + "\")");
        }
        check(migrationCalls, "T-17b 快手/B站八个区域键全部使用同一迁移优先级", "");
        check(!stripComments(main).contains("|| b(map[\"enable_video_"),
                "T-17c 根除旧值 1 让新开关无法关闭的 OR 读取", "");
        check(config.contains("Set<String> presentKeys = new HashSet<>()")
                        && config.contains("presentKeys.add(k)"),
                "T-17d Hook 解析记录本次快照实际包含的键", "");
        boolean hookPriority = true;
        for (String key : independentKeys) {
            hookPriority &= config.contains("!presentKeys.contains(\"" + key + "\")");
        }
        check(hookPriority, "T-17e Hook 八个独立键存在时均忽略旧共用键", "");
        check(versionAtLeast(gradle, 38, "2.7.5"),
                "T-17f 版本号不低于 vc38 / 2.7.5（单调升，升版无需改测试）", "");

        // ---------- T-18 v2.7.2 快手右侧列 / 角落按钮 / 直播全暂停 ----------
        System.out.println("== T-18 v2.7.2 快手右侧列 / 角落按钮 / 直播全暂停 ==");
        String ctrlCode = stripComments(controller);

        // 右侧互动列：高度下限从 0.25 放到 0.08（快手只有三个按钮），宽度仍限 0.30
        check(douyinCode.contains("rw < 0.30f && rh > 0.08f"),
                "T-18a 右侧互动列高度下限适配三按钮布局（快手不再整列被恢复）", "");
        check(!douyinCode.contains("rh > 0.25f"),
                "T-18b 旧 25% 高度阈值已移除（真机快手右侧失效的直接原因）", "");
        // 收集环节的同一根因：scoreRight / scoreRightEdge 也硬依赖 ImageView
        check(douyinCode.contains("int minChild = isVideoApp ? 2 : 3"),
                "T-18b1 右侧列子项数下限对视频 App 降到 2（分享按钮会折叠）", "");
        check(douyinCode.contains("if (iconCount(g) == 0 && !(isVideoApp && hasIconSignal(g, 0)))"),
                "T-18b3 贴右缘兜底收集不再被 ImageView 硬门槛排除", "");

        // 角落按钮：通用图标信号替代 ImageView 硬依赖，且只在视频 App 生效
        check(douyinCode.contains("private static boolean hasIconSignal(View v, int depth)"),
                "T-18c 新增与绘制方式无关的图标信号判定", "");
        check(countLiteral(douyinCode, "isVideoApp && hasIconSignal(g, 0)") == 4,
                "T-18d 右上搜索、左上三横线、右侧列、贴右缘兜底四处都接受图标信号",
                "次数=" + countLiteral(douyinCode, "isVideoApp && hasIconSignal(g, 0)"));
        check(douyinCode.contains("v.getContentDescription()")
                        && douyinCode.contains("v.getWidth() <= 200 && v.getHeight() <= 200"),
                "T-18e 图标信号覆盖无障碍描述与小尺寸可点击控件", "");
        check(!douyinCode.contains("if (!containsImageView(g, 0)) {\n            return -1;"),
                "T-18f 角落按钮不再被 ImageView 硬门槛整块排除", "");

        // 直播暂停：字段 + 提交 + 两处短路 + 信号发布
        check(douyinCode.contains("private static volatile boolean livePaused = false"),
                "T-18g 新增直播间暂停状态", "");
        check(douyinCode.contains("boolean pausedNow = !isVideoApp && (videoLivePage || liveStrongHit)"),
                "T-18h 抖音按结构判定、红果按文案特征，均触发整体暂停", "");
        check(douyinCode.contains("LivePause.publish(selfPkg, paused)"),
                "T-18i 暂停状态按包名与时间戳发布给 SystemUI", "");
        int anyIdx = douyinCode.indexOf("boolean anyEnabled;");
        int pauseIdx = douyinCode.indexOf("if (livePaused) {");
        check(pauseIdx > 0 && anyIdx > 0 && pauseIdx < anyIdx,
                "T-18j 暂停判定先于任何开关 OR 条件（不会被区域开关重新拉起）", "");
        check(countLiteral(douyinCode, "if (livePaused) {") >= 2,
                "T-18k 周期入口与布局监听入口都拦截（恢复后不被布局变化压回）",
                "次数=" + countLiteral(douyinCode, "if (livePaused) {"));

        // 文本特征对所有进程统一计算（红果此前拿不到直播信号）
        check(douyinCode.contains("nowProbe - lastLiveProbe >= 1500L"),
                "T-18l 直播文案特征统一计算并 1.5 秒节流（含红果，省 IO）", "");
        check(douyinCode.contains(".append(\" | pause=\")"),
                "T-18m SCAN 诊断输出 pause 字段（真机可直接核对）", "");

        // SystemUI 侧
        check(ctrlCode.contains("LivePause.readCached(top)"),
                "T-18n SystemUI 只读内存缓存，包名不匹配即视为无信号", "");
        // v2.7.5：灭屏已在更早的快路径里 return，这里只剩名单豁免与直播暂停两种条件。
        check(ctrlCode.contains("if (!intervene || livePause)"),
                "T-18o 直播暂停与名单豁免同级（状态栏导航栏同步恢复）", "");
        check(ctrlCode.contains("+ \" lp=\" + livePause"),
                "T-18p 系统栏心跳日志输出 lp 字段", "");
        check(ctrlCode.contains("return \"直播中暂停 top=\" + top"),
                "T-18q 像素偏移停止原因可区分直播暂停", "");

        // LivePause 自身约束：临时状态，不写用户配置
        String liveCode = stripComments(read(dir.resolve("LivePause.java")));
        check(liveCode.contains("TTL_MS = 15000L"),
                "T-18r 暂停信号 15 秒过期（宿主异常退出不会永久停摆）", "");
        check(!liveCode.contains("AbConf") && !liveCode.contains("Config.enable"),
                "T-18s 暂停状态不写入用户配置（退出直播后原开关自然继续）", "");
        check(versionAtLeast(gradle, 38, "2.7.5"),
                "T-18t 版本号不低于 vc38 / 2.7.5（单调升，升版无需改测试）", "");

        // ---------- T-19 v2.7.3 周期循环永不因异常停摆 ----------
        // 真机现象：抖音进直播间正常暂停，退出后 App 内隐藏全部失效且不再恢复。
        // 根因是 cycleRunnable 只在 tick() 末尾 postDelayed，tick 中途抛异常时
        // catch 吞掉后直接返回，下一次调度永远不会发生 —— 整个周期循环死亡。
        System.out.println("== T-19 v2.7.3 周期循环存活 / 主线程零 IO ==");
        check(countLiteral(douyinCode, "private static volatile boolean cycleScheduled") == 1,
                "T-19a 抖音进程登记本轮是否已排调度", "");
        check(countLiteral(ctrlCode, "private static volatile boolean cycleScheduled") == 1,
                "T-19b SystemUI 进程同样登记", "");
        check(countLiteral(douyinCode, "scheduleCycle(") >= 5,
                "T-19c 抖音所有调度统一走 scheduleCycle（含兜底）",
                "次数=" + countLiteral(douyinCode, "scheduleCycle("));
        check(countLiteral(ctrlCode, "scheduleCycle(") >= 4,
                "T-19d SystemUI 所有调度统一走 scheduleCycle",
                "次数=" + countLiteral(ctrlCode, "scheduleCycle("));
        check(countLiteral(douyinCode, "handler.postDelayed(cycleRunnable") == 1,
                "T-19e 抖音不再有绕过登记的直接调度",
                "次数=" + countLiteral(douyinCode, "handler.postDelayed(cycleRunnable"));
        check(countLiteral(ctrlCode, "handler.postDelayed(cycleRunnable") == 1,
                "T-19f SystemUI 不再有绕过登记的直接调度",
                "次数=" + countLiteral(ctrlCode, "handler.postDelayed(cycleRunnable"));
        check(countLiteral(douyinCode, "if (!cycleScheduled && handler != null)") == 1,
                "T-19g 抖音 tick 异常后由 finally 兜底重排（本轮根因修复）", "");
        check(countLiteral(ctrlCode, "if (!cycleScheduled && handler != null)") == 1,
                "T-19h SystemUI tick 异常后同样兜底重排", "");
        check(douyinCode.contains("if (livePaused && !liveStrongHit && !videoLivePage)"),
                "T-19i 退出直播即时解除暂停（不依赖 2 秒节流重扫）", "");
        check(douyinCode.contains("Log.e(TAG, \"hide failed: \" + what, t)"),
                "T-19j 单个区域隐藏失败不中断整轮", "");
        check(douyinCode.contains("Log.e(TAG, \"show failed\", t)"),
                "T-19k 单个区域恢复失败不中断整轮", "");
        check(!liveCode.contains("writeSharedFile") && !liveCode.contains("execSu"),
                "T-19l 暂停信号不再走 su 兜底（宿主进程未授权，主线程会卡死）", "");
        check(liveCode.contains("newSingleThreadExecutor") && liveCode.contains("setDaemon(true)"),
                "T-19m 信号读写放到后台守护线程", "");
        check(liveCode.contains("static boolean readCached(String topPkg)"),
                "T-19n SystemUI 只读内存缓存，主线程零 IO", "");
        check(liveCode.contains("writeBroken"),
                "T-19o 共享路径写不进去就停止重试，不每 5 秒空转", "");
        check(countLiteral(douyinCode, "liveStrongHit") >= 4,
                "T-19p 直播暂停改用「直播专属节点 + 无首页 Tab」精确判据",
                "次数=" + countLiteral(douyinCode, "liveStrongHit"));
        check(douyinCode.contains("boolean pausedNow = !isVideoApp && (videoLivePage || liveStrongHit)"),
                "T-19q 整体暂停不再由 strongCorner 单独成立（普通页误判会锁死进程）", "");
        check(douyinCode.contains("liveStreak >= 2"),
                "T-19r 进入需连续命中、解除只需一次不命中（退出即时恢复）", "");


        // ---------- T-20 v2.7.4 魅族 Flyme 适配 / 感谢名单 / 视图自愈 ----------
        // Flyme 10/11 仍是 AOSP 结构，但状态栏容器、时钟 id（mz_clock）、
        // 窗口重建时机都与小米 / 荣耀不同。这一组守的是「适配真的接上」，
        // 以及本轮修掉的三个真 bug（通知区嵌套、恢复时误点亮、窗口重建后失效）。
        System.out.println("== T-20 v2.7.4 Flyme 适配与视图自愈 ==");
        String ui = read(dir.resolve("UiStyle.java"));
        check(ui.contains("public static final String FLYME = \"flyme\""),
                "T-20a UiStyle 新增 flyme id", "");
        check(ui.contains("FLYME, AOSP"), "T-20b IDS 含 flyme 且顺序与 UI 标签一致", "");
        check(ui.contains("m.put(FLYME, new Profile(true,"),
                "T-20c Flyme 避让中置（环形电池 / 挖孔）", "");
        check(ui.contains("public static String detect(String manufacturer"),
                "T-20d 首次加载按机型识别（魅族 → flyme）", "");
        check(main.contains("\"flyme\" to \"魅族 Flyme\""),
                "T-20e 首页适配按钮有 Flyme 入口", "");
        check(main.contains("UiStyle.detect(") && main.contains("Build.MANUFACTURER"),
                "T-20f App 侧把 Build 字段交给 detect", "");
        check(ctrlCode.contains("FLYME_ROM") && ctrlCode.contains("detectFlyme()"),
                "T-20g Hook 侧按厂商判定 Flyme", "");
        check(ctrlCode.contains("\"status_bar\", \"mz_status_bar\", \"status_bar_contents\""),
                "T-20h Flyme 优先整条 status_bar（时钟 mz_clock 不在 contents 里）", "");
        check(ctrlCode.contains("PhoneStatusBarView"),
                "T-20i Flyme 状态栏本体有独立 attach 钩子", "");
        check(ctrlCode.contains("isAttachedToWindow()") && ctrlCode.contains("re-acquire"),
                "T-20j 窗口被重建后自动重抓（不再永久失效）", "");
        check(ctrlCode.contains("WindowManagerGlobal") && ctrlCode.contains("mViews"),
                "T-20k 注入较晚时枚举窗口根兜底", "");
        check(ctrlCode.contains("pathTo(") && ctrlCode.contains("dimAlong("),
                "T-20l 通知区按路径逐层保留（嵌套通知不再被一起隐藏）", "");
        check(ctrlCode.contains("private static final List<View> dimmed"),
                "T-20m 只记录我们改过的子视图", "");
        check(ctrlCode.contains("for (int i = 0; i < dimmed.size(); i++)"),
                "T-20n 恢复时只点亮自己改过的视图（不覆盖 ROM 的 alpha）", "");
        check(main.contains("Thanks(\"酷安\"") && main.contains("Thanks(\"心之所及\""),
                "T-20o 感谢名单新增酷安 / 心之所及", "");
        check(manifest.contains("魅族 Flyme"),
                "T-20p 模块描述如实写明已适配 Flyme", "");

        // ---------- T-21 v2.7.4 全面体检修掉的五个真 bug ----------
        // 这一组守的是「用户真实会遇到、但静态看代码很容易漏」的五处：
        // 加载窗口点击冲掉配置、遮盖周期长短写反、名单归一化误报 + 写坏 conf、
        // 覆盖层丢失后不重建、横屏侧栏不复位。
        System.out.println("== T-21 v2.7.4 全面体检修复 ==");
        String mask = read(dir.resolve("BurnInMaskService.java"));
        check(main.contains("val pendingOps = remember") && main.contains("pendingOps.add(update)"),
                "T-21a 加载窗口内的点击按增量记录，不再整份回写默认值", "");
        check(main.contains("for (op in pendingOps.toList()) merged = op(merged)"),
                "T-21b 加载完成后在磁盘状态上重放增量", "");
        check(main.contains("fun canonList(text: String)") && main.contains("parseWhitelist"),
                "T-21c 名单文本与 Hook 侧同一套归一规则", "");
        check(main.contains("m[\"whitelist\"] = canonList(new.whiteList)"),
                "T-21d 只写归一后的名单（结尾逗号 / 换行不进 conf）", "");
        check(main.contains("state = disk.copy(whiteList = target.whiteList, blackList = target.blackList)"),
                "T-21e 保存后不把输入框里的名单文本吞掉", "");
        check(mask.contains("(masked ? c.hideSec : c.showSec)"),
                "T-21f 遮盖周期方向修正（盖 hideSec、露 showSec）", "");
        check(mask.contains("boolean statusBarOn") && mask.contains("c.masterOn && c.statusBarOn"),
                "T-21g 遮盖引擎认「状态栏」独立开关（与 Hook 语义一致）", "");
        check(mask.contains("private void ensureWindow()") && mask.contains("maskView.isAttachedToWindow()"),
                "T-21h 覆盖层被移除 / 首次失败后自动重建", "");
        check(mask.contains("navSide = OverlayGeometry.NAV_BOTTOM;") && mask.contains("statusBarH = 0;"),
                "T-21i 旋转重算前复位旧尺寸与导航栏方向", "");

        // ---------- T-22 v2.7.5 功耗专项 ----------
        // 真机耗电统计：本模块 AVG 4.91W、MAX 40℃，位列前茅，而它平时只改几个 alpha。
        // 根因全是「常驻轮询」：SystemUI 每 1.5 秒 call 一次本模块 provider（持续唤醒
        // 后台进程）、无障碍服务每 200ms 走一帧、灭屏后仍查前台包并写日志。
        // 这一组守的就是「这些轮询必须真的降下来」，不允许以后被改回去。
        System.out.println("== T-22 v2.7.5 功耗专项 ==");
        String cfgCode = read(dir.resolve("Config.java"));
        String maskCode = read(dir.resolve("BurnInMaskService.java"));
        String liveSrc = read(dir.resolve("LivePause.java"));
        check(cfgCode.contains("private static long fetchInterval()"),
                "T-22a 预取线程改为自适应间隔（不再固定 1.5 秒）", "");
        check(cfgCode.contains("return 60000L;") && cfgCode.contains("return 20000L;"),
                "T-22b 灭屏 60 秒 / 稳定 20 秒两档", "");
        check(cfgCode.contains("public static void setScreenOn(boolean on)"),
                "T-22c 屏幕状态由 Controller 同步给预取线程", "");
        check(cfgCode.contains("fetchLock.wait(wait)"),
                "T-22d 用 wait/notify 而非 sleep，亮屏能立刻唤醒", "");
        check(cfgCode.contains("now - lastRead < 1200"),
                "T-22e reload 节流 700 → 1200ms", "");
        check(cfgCode.contains("now - lastSuTry > 300000L"),
                "T-22f su 兜底 30 秒 → 5 分钟（fork 代价高）", "");
        check(ctrlCode.contains("Config.setScreenOn(screenOn)"),
                "T-22g tick 里同步屏幕状态", "");
        check(ctrlCode.contains("if (!screenOn) {") && ctrlCode.contains("scheduleCycle(5000)")
                        && ctrlCode.indexOf("if (!screenOn) {") < ctrlCode.indexOf("currentTopPackage();"),
                "T-22h 灭屏快路径：先于前台包查询返回，5 秒一轮", "");
        check(ctrlCode.contains("private static Object atms()") && ctrlCode.contains("atmsService"),
                "T-22i ActivityTaskManager 的 Class/Service 缓存复用", "");
        check(ctrlCode.contains("now - lastTopAt < 1000L"),
                "T-22j 前台包结果 1 秒内复用", "");
        check(ctrlCode.contains("lastDiagSig") && ctrlCode.contains("nowWl - lastWlDiag >= 30000"),
                "T-22k 诊断日志：变化即记 + 30 秒心跳", "");
        check(ctrlCode.contains("powerManager") && ctrlCode.contains("isInteractiveMethod"),
                "T-22l PowerManager 的 Method 缓存（不再每轮 getMethod）", "");
        check(maskCode.contains("private static final long TICK_MS = 1000L"),
                "T-22m 遮盖步进 200ms → 1000ms", "");
        check(maskCode.contains("IDLE_TICK_MS = 5000L") && maskCode.contains("private long step()"),
                "T-22n 空转 5 秒，step 返回下一帧间隔", "");
        check(maskCode.contains("boolean mayDraw")
                        && maskCode.contains("} else if (windowAttached && now - lastMayDrawAt >= DETACH_HYSTERESIS_MS) {"),
                "T-22o 不需要画时滞回 3 秒再撤窗（不保留全屏覆盖层，也不秒级增删）", "");
        check(maskCode.contains("private static boolean same(OverlayGeometry.Rect a"),
                "T-22p 遮盖矩形没变就不重绘", "");
        check(maskCode.contains("s.handler.removeCallbacks(s.tick)"),
                "T-22q 预览立刻插一帧（不白等 1 秒）", "");
        check(maskCode.contains("handler.removeCallbacks(tick);\n            handler.post(tick);"),
                "T-22r 切前台立刻响应一次，其余时间空转", "");
        check(liveSrc.contains("REFRESH_MS = 3000L"),
                "T-22s 直播信号回源 1 秒 → 3 秒", "");
        check(cfgCode.contains("lastDirectTry > 15000L"),
                "T-22u 文件通道读不到时退避 15 秒（不再每轮 stat）", "");
        check(ctrlCode.contains("if (!hidden) {\n                    PixelShift.tick();"),
                "T-22t 系统栏已透明时跳过像素位移", "");

        // ---------- T-23 v2.7.8 功能页列表化 / 功能建议二级页 ----------
        System.out.println("== T-23 列表化与功能建议 ==");
        String mainCode = stripComments(main);
        check(mainCode.contains("private fun FeaturesPage(")
                        && mainCode.contains("onOpen: (String) -> Unit"),
                "T-23a 功能页改为路由式列表（点行进二级菜单）", "");
        check(count(main, "onOpen\\(\"feat-") == 5,
                "T-23b 功能页 5 个分组入口", "出现=" + count(main, "onOpen\\(\"feat-"));
        check(count(main, "\"feat-[\\w-]+\" -> \\w+Page\\(") == 5,
                "T-23c 五个分组都有落地页（不会点出白屏）",
                "落地=" + count(main, "\"feat-[\\w-]+\" -> \\w+Page\\("));
        check(main.contains("ListRow(\"功能建议\"")
                        && main.contains("\"suggest\" -> \"功能建议\"")
                        && main.contains("\"suggest\" -> SuggestPage("),
                "T-23d 关于页新增「功能建议」入口 / 标题 / 落地页", "");
        check(main.contains("PROJECT_ISSUES_URL") && main.contains("/issues/new"),
                "T-23e GitHub 提交按钮指向新建 Issue 页", "");
        check(main.contains("appendQueryParameter(\"title\", \"[功能建议] \")")
                        && main.contains("模块版本：") && main.contains("onOpenUrl(url)"),
                "T-23f 按钮预填标题标签与正文模板（含模块版本）", "");
        check(main.contains("前往 GitHub 提交建议") && main.contains("Button("),
                "T-23g 建议页有可见的提交按钮", "");
        check(count(main, "OverlayNotice\\(dark, ") == 3,
                "T-23h 抖音/红果/快手B站三处都有遮盖引擎能力提示",
                "出现=" + count(main, "OverlayNotice\\(dark, "));
        check(count(main, "enabled = !overlay") >= 15,
                "T-23i App 内控件开关在遮盖引擎下统一置灰（不再放假开关）",
                "置灰=" + count(main, "enabled = !overlay"));
        check(main.contains("if (page.startsWith(\"feat-\")) saveBar()"),
                "T-23j 功能二级页带保存状态反馈", "");
        check(main.contains("BackHandler(enabled = subPage != null)"),
                "T-23k 系统返回键先回二级页（含新增页）", "");
        check(!mainCode.contains("featurePage"),
                "T-23l 旧横向 tab 状态已删除（不再与 Pager 抢滑动手势）", "");

        // ---------- T-24 v2.7.9 ColorOS 15 视图识别 / 遮盖落盘（真机日志实锤） ----------
        System.out.println("== T-24 ColorOS 15 视图识别修复 ==");
        String ctrl279 = stripComments(controller);
        check(ctrl279.contains("findStatusBarContainer(View v)")
                        && ctrl279.contains("v.getRootView()"),
                "T-24a 状态栏容器从整棵窗口树按根查找（不再只查图标自己）", "");
        check(!ctrl279.contains("bar = v;"),
                "T-24b 删除「退化用 attach 的 View 本身」——抓单个图标的根因", "");
        check(ctrl279.contains("isIconLike(View v)")
                        && ctrl279.contains("\"Bindable\"") && ctrl279.contains("\"Icon\""),
                "T-24c 图标 / 绑定项类名显式排除（SingleBindableStatusBarIconView）", "");
        check(ctrl279.contains("isPlausibleStatusBar(View v)")
                        && ctrl279.contains("getWidth()") && ctrl279.contains("widthPixels"),
                "T-24d statusView 合格性用几何判据（不靠类名误杀合法容器）", "");
        check(ctrl279.contains("BAD STATUS VIEW"),
                "T-24e 抓错对象可自愈丢弃重抓（旧引用是图标时不再空转一整晚）", "");
        check(ctrl279.contains("findBarByGeometry(View root)"),
                "T-24f 厂商把 id 全改名时按几何兜底找横贯全屏的状态栏", "");
        check(ctrl279.contains("svok="),
                "T-24g 诊断行带 statusView 合格标记（sv= 之外再给一个判定）", "");
        check(!ctrl279.contains("\"system_icons\"")
                        && !ctrl279.contains("\"status_bar_start_side\""),
                "T-24h 候选 id 不含局部区域（避免只抓半条栏继续烧时钟）", "");
        check(ctrl279.contains("findStatusBarContainer(root)"),
                "T-24i 窗口重扫不再靠类名猜，改用容器查找", "");

        String mask279 = stripComments(mask);
        check(mask279.contains("maskDiag(") && mask279.contains("ConfStore.LOG_FILE"),
                "T-24j 遮盖引擎状态写入用户可导出的 antiburn.log（消除排障盲区）", "");
        check(mask279.contains("\"connected\"") && mask279.contains("window FAILED")
                        && mask279.contains("\"teardown\""),
                "T-24k 服务连接 / 覆盖层被系统拒 / 销毁三节点都落盘", "");
        check(mask279.contains("mayDraw=") && mask279.contains("hit=")
                        && mask279.contains("fg=") && mask279.contains("eng="),
                "T-24l 一条日志说清遮盖为何没生效（名单/引擎/前台/总闸逐项）", "");
        check(mask279.contains("lastMaskSig") && mask279.contains("30000L"),
                "T-24m 状态签名 + 30s 心跳节流，不每帧写盘", "");
        check(versionAtLeast(gradle, 42, "2.7.9"),
                "T-24n 版本号 vc42 / 2.7.9", "");

        // ---------- T-25 v2.8.0 变体包名适配 + 视频进程诊断落盘 ----------
        System.out.println("== T-25 v2.8.0 变体适配 / 应用内链路可观测 ==");
        String vapps = read(dir.resolve("VideoApps.java"));
        String vappsC = stripComments(vapps);
        String xposedC = stripComments(xposed);
        String dyC = stripComments(douyin);
        check(vappsC.contains("enum Kind") && vappsC.contains("NONE, DOUYIN, DRAMA, KUAISHOU, BILIBILI"),
                "T-25a VideoApps 单一真源定义四类目标应用", "");
        check(vappsC.contains("startsWith(\"com.ss.android.ugc.aweme\")")
                        && vappsC.contains("startsWith(\"com.bilibili.app.\")")
                        && vappsC.contains("startsWith(\"com.phoenix.read\")")
                        && vappsC.contains("startsWith(\"com.smile.gifmaker\")"),
                "T-25b 前缀匹配覆盖渠道包/极速版/HD/概念版（.mobile、com.bilibili.app.in）", "");
        check(vappsC.contains("static String label(Kind k)") && vappsC.contains("return \"douyin\"")
                        && vappsC.contains("return \"bilibili\""),
                "T-25c label() 供诊断/探针复用，避免各处再硬编码字符串", "");
        // 分发处与 Hook 处共用同一真源，不再各自精确 equals
        check(xposedC.contains("VideoApps.Kind kind = VideoApps.of(pkg)"),
                "T-25d XposedInit 分发改用 VideoApps.of 单一真源", "");
        check(countLiteral(xposedC, "kind == VideoApps.Kind.") == 4,
                "T-25e 四类应用各一路分发", "次数=" + countLiteral(xposedC, "kind == VideoApps.Kind."));
        check(!xposedC.contains("\"com.ss.android.ugc.aweme\".equals(pkg)")
                        && !xposedC.contains("\"tv.danmaku.bili\".equals(pkg)"),
                "T-25f 分发处已无精确 equals 包名（变体不再被漏掉）", "");
        check(dyC.contains("VideoApps.Kind kind = VideoApps.of(lpparam.packageName)")
                        && dyC.contains("isBilibili = kind == VideoApps.Kind.BILIBILI")
                        && dyC.contains("isVideoApp = isKuaishou || isBilibili"),
                "T-25g DouyinHook 用同一真源判定，B站变体不再掉进抖音分支", "");
        // 视频进程诊断无条件落盘（消除前几轮排障盲区）
        check(xposedC.contains("write(s, true)") && xposedC.contains("write(s, false)")
                        && xposedC.contains("if (force || now - lastSuWrite > SU_WRITE_INTERVAL_MS)"),
                "T-25h diagForce 跳过 su 10s 节流，注入日志不再被吞", "");
        check(dyC.contains("XposedInit.diagForce(\"HOOK-ATTACH pkg=\"")
                        && dyC.contains("XposedInit.diagForce(\"HOOK\" + sig)"),
                "T-25i 视频进程注入 + 周期状态都无条件落盘", "");
        check(dyC.contains("hookDiag();") && dyC.contains("now - lastHookDiagAt < 30_000L"),
                "T-25j HOOK 状态签名 + 30s 心跳节流，不刷屏", "");
        check(dyC.contains("Config.shouldIntervene(selfPkg)") && dyC.contains("eng=\" + Config.engine")
                        && dyC.contains("\" hid=\"") && dyC.contains("rightList.size()"),
                "T-25k 一条 HOOK 日志说清 引擎/名单/开关/几何命中数/隐藏相位", "");
        check(xposedC.contains("\" pkg=\" + pkg"),
                "T-25l 注入日志带真实包名，可直接核对渠道包是否命中", "");
        check(versionAtLeast(gradle, 43, "2.8.0"),
                "T-25m 版本号 vc43 / 2.8.0", "");
        String livePauseC = stripComments(read(dir.resolve("LivePause.java")));
        check(livePauseC.contains("VideoApps.Kind k = VideoApps.of(pkg)")
                        && livePauseC.contains("k == VideoApps.Kind.DOUYIN || k == VideoApps.Kind.DRAMA"),
                "T-25n LivePause 直播暂停也复用单一真源，且只放行抖音/红果（不误扩快手/B站）", "");

        // ---------- T-26 v2.8.1 无障碍服务崩溃修复（真机日志断流实锤） ----------
        System.out.println("== T-26 无障碍服务稳定性 / 崩溃可观测 ==");
        String svc281 = stripComments(read(dir.resolve("BurnInMaskService.java")));
        String app281 = stripComments(read(dir.resolve("AbApplication.java")));

        check(svc281.contains("suIo.execute(") && svc281.contains("suPending.compareAndSet")
                        && svc281.contains("if (!ok && "),
                "T-26a 日志直写失败时 su 兜底改后台单线程，主线程零阻塞", "");
        check(svc281.contains("ConfStore.ensureMirror()") && !svc281.contains("String text = ConfStore.read();"),
                "T-26b config() 读盘改 ensureMirror，不再在主线程 tick 触发 su", "");
        int cbGuard = 0;
        for (String w : new String[]{"onServiceConnected", "onAccessibilityEvent",
                "onConfigurationChanged", "onUnbind", "onDestroy", "step", "tick.postDelayed"}) {
            if (svc281.contains("reportCallbackCrash(\"" + w + "\"")) {
                cbGuard++;
            }
        }
        check(cbGuard == 7 && svc281.contains("private static void reportCallbackCrash(String where, Throwable t)"),
                "T-26c 六个生命周期回调 + step/postDelayed 全部异常兜底并落盘", "guard=" + cbGuard);
        check(svc281.contains("DETACH_HYSTERESIS_MS = 3000L") && svc281.contains("lastMayDrawAt = now")
                        && svc281.contains("lastMayDrawAt = 0L"),
                "T-26d 窗口增删滞回 3 秒，消除 ColorOS 高频 add/removeViewImmediate", "");
        check(svc281.contains("\" eng=\" + c.engineName") && svc281.contains("c.engineName = v"),
                "T-26e 诊断打印真实引擎值（不再把 lsposed 显示成 other）", "");
        check(app281.contains("Thread.setDefaultUncaughtExceptionHandler")
                        && app281.contains("FATAL thread=") && app281.contains("Caused by: "),
                "T-26f 全局 UEH 把崩溃与 Caused by 完整堆栈写进可导出日志", "");
        check(app281.contains("pending_crash.log") && app281.contains("CRASH-REPLAY"),
                "T-26g 崩溃另存应用内部目录，下次启动回放到主日志（目录不可写也不丢）", "");
        check(app281.contains("prev.uncaughtException(thread, ex)"),
                "T-26h 崩溃交回系统默认处理，不吞异常导致僵死 ANR", "");
        check(versionAtLeast(gradle, 44, "2.8.1"),
                "T-26i 版本号 vc44 / 2.8.1", "");


        // ---------- T-27 v2.8.2 稳定版：应用内区域兜底 + 日志自检 ----------
        System.out.println("== T-27 v2.8.2 应用内区域兜底 / 日志自检 ==");
        String mask282 = read(dir.resolve("BurnInMaskService.java"));
        String conf282 = read(dir.resolve("ConfStore.java"));
        String xp282 = read(dir.resolve("XposedInit.java"));
        String cfg282 = read(dir.resolve("Config.java"));
        String prov282 = read(dir.resolve("ConfigProvider.java"));
        check(conf282.contains("enable_app_overlay=1") && main.contains("enable_app_overlay")
                        && mask282.contains("enable_app_overlay"),
                "T-27a 应用内区域兜底默认开，配置/UI/遮盖服务三处键一致", "");
        check(mask282.contains("resolveApp(c, foregroundPkg)") && mask282.contains("VideoApps.of(fgPkg)"),
                "T-27b 按前台包名识别目标 App，不注入目标进程也能画", "");
        check(mask282.contains("boolean sysBar =") && mask282.contains("boolean appArea =")
                        && mask282.contains("(sysBar || appArea)"),
                "T-27c 系统栏与应用内区域分开判，LSPosed 引擎下兜底仍生效", "");
        check(mask282.contains("private java.util.List<OverlayGeometry.Rect> appRects(Cfg c)")
                        && mask282.contains("canvas.drawRect(r.left"),
                "T-27d 应用内矩形按右侧列/顶部/底部/角落计算并绘制", "");
        check(mask282.contains("FLAG_NOT_TOUCHABLE"),
                "T-27e 覆盖层不吃触摸，点赞评论照常可点", "");
        check(conf282.contains("appendSelfCheck()") && conf282.contains("SELF ver="),
                "T-27f 导出日志追加 SELF 注入自检汇总", "");
        check(conf282.contains("hook=NONE") && conf282.contains("INJECTED Douyin"),
                "T-27g SELF 能明确给出「LSPosed 未注入」结论", "");
        check(xp282.contains("SCOPE ver=") && xp282.contains("providerLog(line)"),
                "T-27h 注入时写 SCOPE 提示，su 失败时经 Provider 回传日志", "");
        check(prov282.contains("METHOD_PUT_LOG") && prov282.contains("appendLog(line)"),
                "T-27i ConfigProvider 提供带 token 校验的日志代写通道", "");
        check(cfg282.contains("dsw=") && cfg282.contains("ks=") && cfg282.contains("bi="),
                "T-27j CONF apply 补齐各 App 分区开关位串", "");
        check(mask282.contains("\" app=\"") && mask282.contains("areas=")
                        && mask282.contains("fallback="),
                "T-27l MASK 诊断行输出前台 App、区域位与兜底状态", "");
        check(versionAtLeast(gradle, 45, "2.8.2"),
                "T-27k 版本号升到 vc45 / 2.8.2", "");

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
