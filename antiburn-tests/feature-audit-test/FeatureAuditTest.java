import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
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
            "XposedInit.java", "FeatureGuard.java", "ConfStore.java",
            // v2.8.3：SystemUI 进程内的应用内区域覆盖层，同样在 Hook 侧消费 Config
            "SysOverlay.java", "AppRegions.java",
            // v3.2.2：无障碍遮罩服务在 Hook 侧消费 Config（通知让开开关）
            "BurnInMaskService.java");

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
            Set.of("enableSysOverlay", "enableIcon", "enableDramaBottom", "enablePoem",
                    "enableShift",
                    // v3.0.16：enableAppOverlay 恢复界面入口（功能页「无障碍应用内遮盖」），移出例外表。
                    // v2.9.4：enableLive 已恢复为抖音页「直播间隐藏」开关，从例外表移除；
                    // B站 只剩右侧点赞区，顶部 / 底部 / 角落三项按用户要求删除入口。
                    "enableBiliTop", "enableBiliBottom", "enableBiliCorner");

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
            Set.of("enableIcon", "enablePoem", "enableAppOverlay",
                    // v2.9.2：B站 三项区域下线，Hook 不再读取（Config 加载时强制归零）
                    "enableBiliTop", "enableBiliBottom", "enableBiliCorner");

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

    /**
     * 逐字节指纹：T-49 用它锁「与基线逐字相等」的方法体（count(".+") 只锁行数，不够）。
     * 基线值取自 v3.0.7 开工前的 DouyinHook.java / MainActivity.kt，纯加性改动不触碰这些函数体。
     */
    private static String sha256(String s) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) {
                sb.append(String.format("%02x", x & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
        // v3.0.6：三个新开关是「全局字段、按 App 页分别挂载」（design-backend §5.4 明确要求：
        // 快手/抖音/B站各自的相关页面显示对应行，红果置灰占位）。同一字段出现在多个 App 页
        // 属设计预期，不是旧版要防的「一个字段被误绑到两个不同含义的行」。故仅对这三个字段
        // 放开重复绑定，其余任何字段仍严格遵守「一字段一行」，旧守卫不削弱。
        Set<String> sharedGlobal = Set.of("enableBottomCaption", "enableRightActions", "enableRedDot");
        List<String> dupFields = new ArrayList<>();
        for (String f : uniqBound) {
            int n = 0;
            for (String b : bound) {
                if (f.equals(b)) { n++; }
            }
            if (n > 1 && !sharedGlobal.contains(f)) {
                dupFields.add(f + "x" + n);
            }
        }
        check(dupFields.isEmpty(), "T-05a 同一配置字段不绑两行开关（v3.0.6 三全局开关按 App 页挂载除外）",
                "违规重复=" + dupFields + " · 绑定 " + bound.size() + " 处 · 标题 " + titles.size() + " 行");
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
        check(features.contains("ListRow(\"周期时间\"") && main.contains("CycleDialog("),
                "T-07a 周期时间入口移到功能区首页（仍是弹窗小窗）", "");
        check(count(main, "ListRow\\(\"周期时间\"") == 1
                        && count(main, "ListRow\\(\"周期时长\"") == 0,
                "T-07c 周期时间全 App 只有 1 处入口，旧入口已删", "新=" + count(main, "ListRow\\(\"周期时间\""));
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
        // v3.0.8：PendingProbeHint 的合规文案含「这条提示不提供开关」，属另一组件，
        // 旧的全文负向断言会误伤它。守卫意图是「说明卡不是占位」，故收紧到 RoadmapCard 函数体内。
        String roadmapCardBody = slice(main, "private fun RoadmapCard(", "private fun openLsposedManager(");
        check(main.contains("点开「快手」「B站」逐项开启对应开关")
                        && !stripComments(roadmapCardBody).contains("不提供开关"),
                "T-12j 说明卡引导到真实子页（RoadmapCard 内无「不提供开关」占位）", "");
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
        // v2.9.2：B站 顶部 / 底部 / 角落三项从应用下线（只剩右侧点赞区），
        // 不再参与「字段→界面→Hook」全链路，故从此表移除。
        String[] ksKeys = {"enable_kuaishou", "enable_bilibili",
                "enable_ks_right", "enable_ks_top", "enable_ks_bottom", "enable_ks_corner",
                "enable_bili_right"};
        String[] ksFlds = {"enableKuaishou", "enableBilibili",
                "enableKsRight", "enableKsTop", "enableKsBottom", "enableKsCorner",
                "enableBiliRight"};
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
        check(chainOk, "T-14j v2.7.0 启用中的区域键全链路一致（字段/load/save/diff/default/parse/cfg/hook/ui）",
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
        // v2.9.3：右侧分支改成 `rw >= 0.30f` 提前否决，断言随之改成「宽度上限仍 30%」。
        check(douyinCode.contains("containsVideoSurface(v, 0)") && douyinCode.contains("rw >= 0.30f")
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
        // v2.9.3：文案判定后多了一道结构闸（底部主 Tab 栏），提交点仍然只有一处。
        check(countLiteral(douyinCode, "videoLivePage = textHit && findBottomDock(root, 0) == null") == 1,
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
                        && douyinCode.contains("dTop = on && isKuaishou && Config.enableKsTop"),
                "T-16c Hook 按 App 分派各自开关（快手四区 / B站只剩右侧）", "");
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
        // v2.9.2：B站 只剩右侧（其余三项已下线，不再需要迁移）
        String[] independentKeys = {"enable_ks_right", "enable_ks_top", "enable_ks_bottom", "enable_ks_corner",
                "enable_bili_right"};
        String[] migrationLegacyKeys = {"enable_video_right", "enable_video_top", "enable_video_bottom", "enable_video_corner",
                "enable_video_right"};
        boolean migrationCalls = true;
        for (int i = 0; i < independentKeys.length; i++) {
            migrationCalls &= main.contains("migratedB(map, \"" + independentKeys[i] + "\", \"" + migrationLegacyKeys[i] + "\")");
        }
        check(migrationCalls, "T-17b 仍在用的区域键全部使用同一迁移优先级", "");
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
        // v2.9.3：整列与单按钮统一按「窄」放行，上限 50%，超过 30% 屏高时必须 <=22% 屏宽。
        check(douyinCode.contains("return rh <= 0.30f || rw <= 0.22f;")
                        && douyinCode.contains("if (rh <= 0.02f || rh > 0.50f)"),
                "T-18a 右侧互动列高度上限放宽到 50%（整列与单按钮都能过）", "");
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
        check(douyinCode.contains("boolean pausedNow = !isVideoApp && !hasTab && (hasLiveInput || videoLivePage)"),
                "T-18h 直播间判定改为双结构闸（底部输入条 + 无首页 Tab）", "");
        check(douyinCode.contains("LivePause.publish(selfPkg, paused)"),
                "T-18i 暂停状态按包名与时间戳发布给 SystemUI", "");
        int anyIdx = douyinCode.indexOf("boolean anyEnabled;");
        // v2.9.2：直播间通道断开（LIVE_ROOM_HIDE=false），判定成立即整页让位。
        // 让位判定仍必须先于 anyEnabled 的 OR 条件。
        int pauseIdx = douyinCode.indexOf("if (livePaused && !LIVE_ROOM_HIDE) {");
        check(pauseIdx > 0 && anyIdx > 0 && pauseIdx < anyIdx,
                "T-18j 让位判定先于任何开关 OR 条件（直播页整页恢复）", "");
        check(countLiteral(douyinCode, "if (livePaused && !LIVE_ROOM_HIDE) {") >= 1
                        && countLiteral(douyinCode, "if (livePaused) {") >= 1,
                "T-18k 周期入口整页让位，布局监听入口按总闸分流，两处都不漏直播",
                "tick=" + countLiteral(douyinCode, "if (livePaused && !LIVE_ROOM_HIDE) {")
                        + " hook=" + countLiteral(douyinCode, "if (livePaused) {"));

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
        check(douyinCode.contains("if (livePaused && !liveStrongHit && !videoLivePage && !liveInputSeen)"),
                "T-19i 退出直播即时解除暂停（含输入条结构闸，不依赖 2 秒节流重扫）", "");
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
        check(douyinCode.contains("hasLiveInputBar(root, 0)")
                        && douyinCode.contains("!hasTab && (hasLiveInput || videoLivePage)"),
                "T-19p 直播暂停改用「底部输入条 + 无首页 Tab」双结构闸", "");
        check(douyinCode.contains("!hasTab && (hasLiveInput || videoLivePage)")
                        && !douyinCode.contains("pausedNow = !isVideoApp && (liveStrongHit"),
                "T-19q 整体暂停既不由 strongCorner、也不由文案单独成立（普通页不会误入）", "");
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
        check(ctrlCode.contains("now - lastTopAt < 3000L"),
                "T-22j 前台包结果 3 秒内复用（v3.2.5 由 1 秒放宽，少一次 ATMS binder）", "");
        // v3.2 授权改判据：心跳 30s→60s，且「变化签名」剔除 hideDiag（hid/rot/dim/nv/zone 每轮都变，
        // 旧判据把「状态一变就记一条」退化成每秒一条 SYS，一天八万行）。
        check(ctrlCode.contains("lastDiagSig") && ctrlCode.contains("nowWl - lastWlDiag >= 60000"),
                "T-22k 诊断日志：变化即记 + 60 秒心跳；签名不再含每轮都变的视图字段", "");
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
        check(ctrlCode.contains("if (!hidden || statusWindowVisible()) {\n                    PixelShift.tick();"),
                "T-22t 系统栏整体不可见时才跳过像素位移（窗口仍可见则继续推进）", "");

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
        // v3.0.12：saveBar 判断由 feat- 扩为 feat-||home-，首页四入口二级页同获即时保存反馈（G7）。
        // 用两个独立 contains 而非整串精确匹配，避免运算符顺序/空格敏感（评审 S4）；
        // 同时仍断言 saveBar() 调用存在，保持「防功能页丢保存反馈」的原始意图不弱化。
        check(main.contains("startsWith(\"feat-\")") && main.contains("startsWith(\"home-\")")
                        && main.contains("saveBar()"),
                "T-23j 功能与首页入口二级页（v3.0.12）均带保存状态反馈", "");
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
        // v3.0.16：无障碍模式重新启用应用内遮盖（仅 overlay 引擎生效），
        // 默认必须为 1；LSPosed 引擎仍由 Config 层强制不铺黑。
        check(conf282.contains("enable_app_overlay=1"),
                "T-27a 应用内遮盖默认开（仅无障碍 overlay 引擎生效）", "");
        check(cfg282.contains("case \"enable_app_overlay\": ao = false;"),
                "T-27a2 旧配置里残留的 =1 不再生效", "");
        check(mask282.contains("resolveApp(c, foregroundPkg)") && mask282.contains("VideoApps.of(fgPkg)"),
                "T-27b 按前台包名识别目标 App，不注入目标进程也能画", "");
        check(mask282.contains("boolean sysBar =") && mask282.contains("boolean appArea =")
                        && mask282.contains("(sysBar || appArea)"),
                "T-27c 系统栏与应用内区域分开判，LSPosed 引擎下兜底仍生效", "");
        check(mask282.contains("private java.util.List<OverlayGeometry.Rect> appRects(Cfg c, int mask)")
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

        // ---------- T-28 v2.8.3 稳定版：SystemUI 代理遮盖 ----------
        System.out.println("== T-28 v2.8.3 SystemUI 代理遮盖 ==");
        String sys = read(dir.resolve("SysOverlay.java"));
        String reg = read(dir.resolve("AppRegions.java"));
        String ctrl = read(dir.resolve("Controller.java"));
        String mask283 = read(dir.resolve("BurnInMaskService.java"));
        String conf283 = read(dir.resolve("ConfStore.java"));
        check(sys.contains("TYPE_APP_OVERLAY = 2038") && sys.contains("TYPE_SYSTEM_ALERT = 2003"),
                "T-28a 覆盖层优先 2038，失败退 2003（老 ROM 兜底）", "");
        check(sys.contains("FLAG_NOT_TOUCHABLE"),
                "T-28b 覆盖层不吃触摸，点赞收藏评论照常可点", "");
        check(sys.contains("broken = true") && sys.contains("if (broken || ctx == null)"),
                "T-28c 加窗失败即熔断本进程，绝不反复抛异常拖崩 SystemUI", "");
        check(sys.contains("removeViewImmediate") && sys.contains("static void release()"),
                "T-28d 提供撤窗通道，切引擎/关开关时能收回", "");
        check(ctrl.contains("updateOverlay(top, shouldHide)")
                        && ctrl.contains("updateOverlay(top, false)")
                        && ctrl.contains("updateOverlay(null, false)"),
                "T-28e 相位切换 / 暂停 / 灭屏三处都刷新覆盖层", "");
        check(ctrl.contains("FeatureGuard.guard(\"sys.appoverlay\""),
                "T-28f 覆盖层单独熔断，异常不影响状态栏隐藏与像素偏移", "");
        check(reg.contains("public static List<int[]> rects(") && reg.contains("public static int mask(")
                        && mask283.contains("AppRegions.rects(") && sys.contains("AppRegions.rects("),
                "T-28g 区域几何只有一份，两条通道共用", "");
        check(reg.contains("0.80f") && reg.contains("0.28f") && reg.contains("0.86f"),
                "T-28h 右侧列 / 顶部 / 底部比例与 v3.2.5 标定一致（80 / 28 / 86）", "");
        String cfg283 = read(dir.resolve("Config.java"));
        check(cfg283.contains("public static int appAreaMask(VideoApps.Kind kind)")
                        && cfg283.contains("if (!r && !t && !b && !c)"),
                "T-28i 四区掩码：全关回退 R+T+B，不全猜", "");
        check(cfg283.contains("enableSysOverlay")
                        && conf283.contains("enable_sys_overlay=0")
                        && cfg283.contains("case \"enable_sys_overlay\": so = false;"),
                "T-28j enable_sys_overlay 默认关，旧配置残留值不生效", "");
        // v3.0.25：appendRaw 改为返回真实成败（崩溃回放据此决定是否删除 pending）
        check(conf283.contains("private static boolean appendRaw(")
                        && conf283.contains("base64 -d") && conf283.contains("execSu(cmd)"),
                "T-28k SELF 改走 su 追加，不再因分区存储静默失败", "");
        check(conf283.contains("public static boolean a11yEnabled(")
                        && conf283.contains("public static void logSelfCheck("),
                "T-28l 启动写 A11Y 自检，服务没开能直接从日志看出来", "");
        check(sys.contains("\"OVL \"") && sys.contains("areas=") && sys.contains("window attached type="),
                "T-28m OVL 诊断行带 app/areas/type", "");
        check(conf283.contains("public static boolean enableA11yByRoot(")
                        && conf283.contains("enabled_accessibility_services")
                        && conf283.contains("accessibility_enabled 1"),
                "T-28o 有 root 时直接启用本应用无障碍服务，不用用户手动去设置", "");
        check(conf283.contains("static String mergeA11yList(")
                        && conf283.contains("\"null\".equals(t)")
                        && conf283.contains("set.add(comp)"),
                "T-28p 启用时并入已有服务列表，不覆盖别的应用、不写进 null", "");
        check(versionAtLeast(gradle, 46, "2.8.3"),
                "T-28n 版本号升到 vc46 / 2.8.3", "");

        // ---------- T-29 v2.8.4：位置微移取代盖黑 ----------
        System.out.println("== T-29 v2.8.4 位置微移 ==");
        String dy284 = read(dir.resolve("DouyinHook.java"));
        String lsp = read(dir.resolve("LspScope.java"));
        check(dy284.contains("private static void shiftDeep(")
                        && dy284.contains("v.setTranslationX(dx)")
                        && dy284.contains("v.setTranslationY(dy)"),
                "T-29a 固定控件用 translation 微移，不再 setAlpha(0) 隐藏", "");
        check(dy284.contains("SHIFT_DX") && dy284.contains("SHIFT_DY")
                        && dy284.contains("SHIFT_STEP_MS = 8000L"),
                "T-29b 九宫格绕圈，每 8 秒换一格", "");
        check(dy284.contains("private static void resetShift("),
                "T-29c 提供归零通道", "");
        String hid284 = slice(dy284, "private static void hideEnabled()", "private static void transparentDeep");
        int liveReset = hid284.split("resetShift\\(v\\);", -1).length - 1;
        check(liveReset >= 3, "T-29d 直播三区一律 resetShift 还原（实际 " + liveReset + " 处）", "");
        check(!hid284.contains("transparentDeep("),
                "T-29e 应用路径里不再有透明隐藏调用", "");
        check(dy284.contains("// v2.8.4：偏移必须在这里归零"), "T-29f 关开关/退直播后位置必须归零", "");
        check(lsp.contains("SQLiteDatabase.openDatabase") && !lsp.contains("sqlite3 -noheader"),
                "T-29g 作用域自检改用系统 SQLiteDatabase，不再依赖设备上的 sqlite3", "");
        check(!lsp.contains("insert into scope(") && lsp.contains("为什么只读不写"),
                "T-29h 只读诊断，不往 modules_config.db 写任何行", "");
        check(versionAtLeast(gradle, 47, "2.8.4"),
                "T-29i 版本号升到 vc47 / 2.8.4", "");

        // ---------- T-30 v2.8.5：作用域自检 + B站 id 锚点 ----------
        System.out.println("== T-30 v2.8.5 作用域自检与 id 锚点 ==");
        check(lsp.contains("Base64.decode") && lsp.contains("FileOutputStream")
                        && lsp.contains("-wal") && lsp.contains("-shm"),
                "T-30a 配置库经 base64 由本进程落盘（自建文件才带 app_data_file 标签）", "");
        check(lsp.contains("android.database.sqlite.SQLiteDatabase")
                        || lsp.contains("import android.database.sqlite.SQLiteDatabase"),
                "T-30b 用系统 SQLiteDatabase 读库，无需 sqlite3 二进制", "");
        check((lsp.contains("pickPkgColumn(") || lsp.contains("pkgColumn("))
                        && lsp.contains("pragma table_info"),
                "T-30c modules 表列名按 pragma 现查，兼容各版 LSPosed", "");
        check(lsp.contains("installedTargets(") && lsp.contains("!r.scope.contains(p)"),
                "T-30d 用设备真实安装包名与作用域精确比对，不做前缀猜测", "");
        check(lsp.contains("su-denied") && lsp.contains("unregistered"),
                "T-30e 无 root / 模块未启用都有明确结论，不静默失败", "");
        check(dy284.contains("getIdentifier(name, \"id\", selfPkg)")
                        && dy284.contains("ID_CACHE"),
                "T-30f 按资源名取 id 并缓存，不写死会变的 id 数值", "");
        check(dy284.contains("\"bottom_navigation\"") && dy284.contains("\"app_bar\"")
                        && dy284.contains("\"nav_top_bar\""),
                "T-30g B站 底部导航 / 顶部栏按真实 id 名锚定", "");
        check(dy284.contains("if (isBilibili) {"),
                "T-30h id 锚点只对 B站 开（抖音/红果/快手 资源名已混淆）", "");
        check(main.contains("LspScope.diagnose(context)") && main.contains("openLsposedManager("),
                "T-30i 通用页有作用域自检入口并能跳转 LSPosed 管理器", "");
        check(!main.contains("应用内区域兜底」会用覆盖层按屏幕比例遮盖"),
                "T-30j 删掉「无障碍按比例遮盖」这类已不成立的承诺", "");
        check(versionAtLeast(gradle, 48, "2.8.5"),
                "T-30k 版本号升到 vc48 / 2.8.5", "");

        // ---------- T-31 v2.8.6 盖黑通道断开 → v3.0.16 无障碍通道重开 ----------
        // v2.8.6 曾用编译期常量 + 无条件置 false 硬断开；v3.0.16 起：
        //  - 编译期常量移除，开关回到配置（enable_app_overlay）；
        //  - 仅 overlay 引擎生效（step() 里 appArea 带 c.maskAllowed），LSPosed 引擎仍不铺黑。
        System.out.println("== T-31 v2.8.6→v3.0.16 应用内遮盖通道 ==");
        String mask286 = read(dir.resolve("BurnInMaskService.java"));
        check(!mask286.contains("APP_AREA_CHANNEL"),
                "T-31a 编译期总闸已移除（配置可正常开关）", "");
        check(!mask286.contains("if (!APP_AREA_CHANNEL)"),
                "T-31b appRects 不再被常量门拦截", "");
        check(mask286.contains("boolean appAreaOn = true;"),
                "T-31c Cfg.appAreaOn 默认开（缺键时开箱可用）", "");
        check(!mask286.contains("c.appAreaOn = false;"),
                "T-31d config() 不再无条件强关（改由引擎判定）", "");
        check(mask286.contains("boolean appArea = c.maskAllowed && c.appAreaOn"),
                "T-31e 应用内遮盖仅无障碍（overlay）引擎生效，LSPosed 不铺黑", "");
        check(mask286.contains("maskView.setRects(null, null")
                        && mask286.contains("emptyList()"),
                "T-31f 无可画区域时连旧矩形一起清，避免退出 App 后残留", "");
        check(main.contains("b(map[\"enable_app_overlay\"], true)")
                        && main.contains("enableSysOverlay = false")
                        && main.contains("lines[\"enable_app_overlay\"]"),
                "T-31g 读配置走真实开关（缺键默认开），SystemUI 覆盖层仍关", "");
        check(main.contains("val enableAppOverlay: Boolean = true")
                        && main.contains("val enableSysOverlay: Boolean = false"),
                "T-31h UI 状态默认开（仅 overlay 引擎生效）", "");
        check(main.contains("Bool(38, true)") && main.contains("Bool(39, false)"),
                "T-31i Bundle 恢复路径与应用默认一致", "");
        check(!main.contains("预览 5 秒"),
                "T-31j 移除「预览 5 秒」入口（点了就是整屏黑块）", "");
        check(lsp.contains("base64 -w0"), "T-31j 自检改用 base64 拉库，绕开 SELinux 标签", "");
        check(versionAtLeast(gradle, 49, "2.8.6"),
                "T-31k 版本号升到 vc49 / 2.8.6", "");

        // ---------- T-32 v2.8.6b：注入二义性收口 ----------
        // 「日志里什么都没有」此前有两种可能：没注入 / 进来了但中途崩了。
        // 前几版都卡在这个二义性上改来改去，必须用入口探针一锤定音。
        System.out.println("== T-32 注入探针与 LSPosed 日志回读 ==");
        String xp287 = read(dir.resolve("XposedInit.java"));
        int enterAt = xp287.indexOf("ENTER pkg=");
        int ofAt = xp287.indexOf("VideoApps.of(pkg)");
        check(enterAt > 0 && ofAt > 0 && enterAt < ofAt,
                "T-32a 入口探针打在 VideoApps.of 之前（探针自身不依赖任何业务类）", "");
        check(xp287.contains("catch (Throwable ignored)") && xp287.contains("ENTER pkg="),
                "T-32b 探针写日志失败也不能挡住主流程", "");
        check(lsp.contains("lspLogDigest(") && lsp.contains("/data/adb/lspd/log/"),
                "T-32c 自检回读 LSPosed 自身日志，作用域齐了也能看出框架侧问题", "");
        check(versionAtLeast(gradle, 50, "2.8.7"),
                "T-32d 版本号升到 vc50 / 2.8.7", "");

        // ---------- T-33 v2.8.8：自检读库真因 + 注入证据前置 ----------
        // 真机日志连续两版都是 error=open:SQLiteException。前两版往 SELinux / 属主 /
        // chcon 方向查，全错 —— 真因是 WAL 库在 OPEN_READONLY 下建不了 -shm，
        // SQLite 直接 SQLITE_CANTOPEN(14)。必须用断言把这个前提钉住。
        System.out.println("== T-33 自检采用 READWRITE + 注入证据前置 ==");
        check(!lsp.contains("SQLiteDatabase.OPEN_READONLY"),
                "T-33a 不再用 OPEN_READONLY 打开 WAL 库（会 CANTOPEN）", "");
        check(lsp.contains("SQLiteDatabase.OPEN_READWRITE"),
                "T-33b 改用 OPEN_READWRITE 打开副本，SQLite 才能建 -shm、重放 WAL", "");
        check(lsp.contains("private static SQLiteDatabase open(File copy)"),
                "T-33c 打开动作收敛到 open()，失败可回退", "");
        check(lsp.contains("\"-wal\").delete()") && lsp.contains("\"-shm\").delete()"),
                "T-33d 回退时删掉搬来的 WAL/SHM 再试一次", "");
        check(lsp.contains(":len=") && lsp.contains("t.getMessage()"),
                "T-33e 失败时报异常 message 与副本字节数，不再只给类名", "");
        check(lsp.contains("ConfStore.enterHits()") && lsp.contains("注入证据"),
                "T-33f 自检第一段就给 ENTER 命中证据", "");
        check(lsp.contains("OK:"),
                "T-33g 命中目标包时标 OK，一眼看出注入通没通", "");

        String conf288 = read(dir.resolve("ConfStore.java"));
        check(conf288.contains("enterSummary(") && conf288.contains("\"ENTER pkg=\""),
                "T-33h SELF 行输出 enter= 摘要", "");
        check(conf288.contains("+ \" app_overlay=0\""),
                "T-33i app_overlay 恒 0，不再读镜像历史值自相矛盾", "");
        check(!conf288.contains("overlayOn"),
                "T-33j 移除 overlayOn 旧逻辑（它的 true 默认值误导过排查）", "");
        check(!conf288.contains("由「应用内区域兜底」接管"),
                "T-33k hook=NONE 文案不再指向已拆除的兜底通道", "");
        check(conf288.contains("private static String readAllLogs()"),
                "T-33l 日志读取收敛成 readAllLogs()，SELF 与自检共用", "");
        check(lsp.contains("设备上已安装的目标应用")
                        && lsp.contains("installedTargets(ctx)"),
                "T-33n 读不到作用域时仍列出设备上装的目标 App，用户可照单勾选", "");
        check(versionAtLeast(gradle, 51, "2.8.8"),
                "T-33m 版本号升到 vc51 / 2.8.8", "");

        // ---------- T-34 v2.9.0：恢复周期真隐藏 + 面积保险 ----------
        // v2.8.4 把 App 内区域从「透明隐藏」改成「位置微移」，理由是「别盖别藏」。
        // 真机结论反过来：微移只是把轮廓挪到相邻像素，亮度没降，用户觉得不如真隐藏。
        // 真隐藏的代价是误判会遮黑内容区，所以必须配一道面积保险。
        System.out.println("== T-34 周期真隐藏与面积保险 ==");
        String hook290 = read(dir.resolve("DouyinHook.java"));
        int hs = hook290.indexOf("private static void hideSafe(");
        int hsEnd = hs < 0 ? -1 : hook290.indexOf("\n    }", hs);
        String hideSafeBody = (hs >= 0 && hsEnd > hs) ? hook290.substring(hs, hsEnd) : "";
        check(hideSafeBody.contains("transparentDeep(v, what)"),
                "T-34a hideSafe 回到真隐藏（transparentDeep）", "");
        check(!hideSafeBody.contains("shiftDeep(v, what)"),
                "T-34b hideSafe 不再走位置微移", "");
        check(hook290.contains("private static boolean tooBigToHide(View v)"),
                "T-34c 面积保险方法存在", "");
        check(hook290.contains("sw * 0.45f") && hook290.contains("sh * 0.45f"),
                "T-34d 面积阈值宽高双 45%（宽 100%/高 8% 的底部导航不会被误伤）", "");
        check(hideSafeBody.contains("tooBigToHide(v)"),
                "T-34e 保险接在隐藏前，命中即放弃隐藏", "");
        check(hook290.contains("private static long phaseAt"),
                "T-34f 新增相位时间戳，用于周期切换", "");
        check(hook290.contains("Config.hideSec") && hook290.contains("Config.showSec")
                        && hook290.contains("hideMs") && hook290.contains("showMs"),
                "T-34g 隐藏 / 显示时长仍读用户配置（周期时长设置有效）", "");
        check(hook290.contains("hideEnabled();\n                hidden = true;\n                phaseAt = now;")
                        || (hook290.contains("hideEnabled();") && hook290.contains("phaseAt = now;")),
                "T-34h 进入隐藏相位时记时间戳", "");
        check(hook290.contains("showAll();\n            hidden = false;\n            phaseAt = now;")
                        || (hook290.contains("showAll();") && hook290.contains("hidden = false;")),
                "T-34i 到点恢复显示并记时间戳", "");
        check(countLiteral(hook290, "shiftDeep(") <= 1,
                "T-34j shiftDeep 只剩定义，不再被调用（引用=" + countLiteral(hook290, "shiftDeep(") + "）", "");
        check(hook290.contains("hideSafe(v, \"swipe\")"),
                "T-34k 左滑引导条一并走真隐藏", "");
        check(versionAtLeast(gradle, 52, "2.9.0"),
                "T-34l 版本号升到 vc52 / 2.9.0", "");

        // ---------- T-35 v2.9.1：直播间三区真隐藏 / 名单不再否决快手 B站 / 注入可见 ----------
        // 本轮三条真机反馈：抖音普通页好了但直播间不动、快手没反应、B站没反应。
        // 前两条都能在代码里定位到确定性缺陷（短路死代码 / 名单二次过滤），
        // 第三条的根本原因是「注入证据完全看不到」，只能靠猜 —— 一并修掉。
        System.out.println("== T-35 直播间三区真隐藏 / 名单不再否决 / 注入可见 ==");
        String hook291 = read(dir.resolve("DouyinHook.java"));
        String hook291c = stripComments(hook291);
        // v2.9.2 起这条判定外面多了一层编译期总闸（LIVE_ROOM_HIDE），语义不变：
        // 总闸打开且开关关掉时才整页让位。
        check(hook291c.contains("if (livePaused && LIVE_ROOM_HIDE && !Config.enableLive) {"),
                "T-35a 直播间只在开关关掉时整页让位（不再无条件 showAll）", "");
        check(hook291c.contains("private static void hideLiveAreas()"),
                "T-35b 新增直播间三区隐藏入口", "");
        // v2.9.5：不再用统一的 "live" 标签把三区一起塞进 cand（会落到小控件分支全灭），
        // 改成三区各自按本来的区域语义过已有的 safeToHide 门禁。
        check(hook291c.contains("private static boolean hideLiveCandidate(View v, String what)")
                        && hook291c.contains("hideLiveCandidate(v, \"top\")")
                        && hook291c.contains("hideLiveCandidate(v, \"bottom\")")
                        && hook291c.contains("hideLiveCandidate(v, \"right\")"),
                "T-35c 三区分别按本来的区域标签过已有形状白名单（v2.9.5）", "");
        check(hook291c.contains("if (LIVE_ROOM_HIDE) {\n                hideLiveAreas();"),
                "T-35d 布局监听入口按总闸分流：开则三区，关则整页恢复", "");
        check(!hook291c.contains("boolean allowed = Config.shouldIntervene")
                        && !hook291c.contains("&& Config.shouldIntervene(selfPkg)"),
                "T-35e 快手 / B站 的 App 内区域不再被系统栏名单二次否决（与抖音红果一致）", "");
        String xp291 = stripComments(read(dir.resolve("XposedInit.java")));
        check(xp291.contains("if (force || nowForLog - lastProviderAt > PROVIDER_LOG_INTERVAL_MS)"),
                "T-35f 注入关键行不再看 su 退出码，一律经 Provider 回传（suOk 恒真的黑洞）", "");
        check(xp291.contains("PROVIDER_LOG_INTERVAL_MS"), "T-35g 普通行回传有节流，不刷屏", "");
        String prov291 = read(dir.resolve("ConfigProvider.java"));
        check(prov291.contains("private void noteInject(String line)")
                        && prov291.contains("INJECT_PREFS"),
                "T-35h 收到注入行时记时间戳，供首页直接展示", "");
        check(prov291.contains("public static long lastInject(Context ctx, String kind)"),
                "T-35i 首页可读最近一次注入时间", "");
        String main291 = read(dir.resolve("MainActivity.kt"));
        check(main291.contains("private fun InjectStatusCard(dark: Boolean, onEnableAll: () -> Unit)")
                        && main291.contains("ConfigProvider.lastInject(context, kind)"),
                "T-35j 首页新增注入状态卡（四个 App 一屏可见）", "");
        check(main291.contains("四个 App 全开"),
                "T-35k 一键打开四个 App 的区域开关（默认全关是最常见的「没反应」）", "");
        check(!main291.contains("每 8 秒整体挪动") && !main291.contains("（位置微移）"),
                "T-35l 用户可见文案与 v2.9.0 的真隐藏同步，不再写「位置微移」", "");
        check(versionAtLeast(gradle, 53, "2.9.1"),
                "T-35m 版本号至少 vc53 / 2.9.1（后续版本继续叠加）", "");

        // ---------- T-36 v2.9.2：直播间通道隔离 / B站只留右侧 / 悬浮球不被误藏 ----------
        // 真机反馈：v2.9.1 之后抖音普通页也不动了，悬浮球点了没反应；快手导航栏正常；
        // B站只有右侧点赞收藏评论可用。这一轮按「宁可少藏，不可误伤」重排。
        System.out.println("== T-36 直播间隔离 / B站单区 / 悬浮球自保 ==");
        String hook292 = stripComments(read(dir.resolve("DouyinHook.java")));
        check(hook292.contains("private static final boolean LIVE_ROOM_HIDE = true;"),
                "T-36a v2.9.4 直播间隐藏通道已启用（普通页由输入条结构闸隔离）", "");
        check(hook292.contains("if (livePaused && !LIVE_ROOM_HIDE) {")
                        && hook292.contains("scheduleCycle(1000);\n            return;"),
                "T-36b 判定成立即整页恢复并结束本轮（不再按普通页规则藏直播页）", "");
        check(!hook292.contains("Config.enableLive && liveDetected"),
                "T-36c 直播不再参与「是否有区域可藏」的判定（不占普通页相位）", "");
        check(hook292.contains("if (!LIVE_ROOM_HIDE || !Config.enableLive) {\n            showAll();"),
                "T-36d 断闸或关开关时 hideLiveAreas 整页恢复，一个控件都不碰", "");
        check(hook292.contains("boolean mT = isKuaishou && Config.enableKsTop;")
                        && hook292.contains("dTop = on && isKuaishou && Config.enableKsTop;"),
                "T-36e B站 顶部 / 底部 / 角落三项在 Hook 侧恒不生效（只留右侧）", "");
        String conf292 = stripComments(read(dir.resolve("Config.java")));
        check(conf292.contains("enableBiliTop = false;")
                        && conf292.contains("enableBiliBottom = false;")
                        && conf292.contains("enableBiliCorner = false;"),
                "T-36f Config 加载时把 B站 三项归零（老配置残留不会变成假开关）", "");
        String main292 = read(dir.resolve("MainActivity.kt"));
        check(main292.contains("OptionRow(\"右侧点赞区\""),
                "T-36g B站 功能页只剩「右侧点赞区」一行", "");
        check(!main292.contains("state.enableBiliTop")
                        && !main292.contains("state.enableBiliBottom")
                        && !main292.contains("state.enableBiliCorner"),
                "T-36h UI 不再引用 B站 已删三项（无假入口、无假计数）", "");
        check(main292.contains("enableBiliTop = false,")
                        && main292.contains("enableBiliCorner = false,"),
                "T-36i 读盘时同步归零，保存会把老配置写成 0", "");
        check(hook292.contains("private static boolean isOwnOverlay(View v)")
                        && hook292.contains("ball.setTag(BALL_TAG);")
                        && hook292.contains("ball.bringToFront();"),
                "T-36j 悬浮球打标记并置顶（不会被当成右侧按钮列藏掉 / 盖住）", "");
        check(hook292.contains("if (isOwnOverlay(v)) {\n            return;\n        }\n        if (isVideoApp"),
                "T-36k hideSafe 入口直接跳过模块自己的悬浮层", "");
        // hideSafe / transparentDeep 各 1 处（定义处写法不同，单独校验）
        check(countLiteral(hook292, "isOwnOverlay(v)") >= 2
                        && hook292.contains("private static boolean isOwnOverlay(View v)"),
                "T-36l hideSafe 与 transparentDeep 都跳过悬浮层（双保险，引用="
                        + countLiteral(hook292, "isOwnOverlay(v)") + "）", "");
        check(hook292.contains("+ \" ball=\" + (ballView != null ? 1 : 0)"),
                "T-36m 诊断行带 ball= / fs= / lrh=，球没出来能一眼定位", "");
        check(versionAtLeast(gradle, 54, "2.9.2"),
                "T-36n 版本号升到 vc54 / 2.9.2", "");
        check(!hook292.contains("s.equals(\"直播\")") && !hook292.contains("up.contains(\"LIVE\")"),
                "T-36o 文案判定不再认单独的「直播」tab（普通推荐页被误判的根因）", "");
        check(hook292.contains("!hasTab && (hasLiveInput || videoLivePage)")
                        && hook292.contains("hasLiveInputBar(root, 0)"),
                "T-36p 暂停必须「底部有输入条 且 没有首页 Tab」，双结构闸压过文案", "");

        // ---------- T-37 v2.9.3：快手右侧列的四个确定缺陷 ----------
        // 真机反馈「快手导航栏正常、右侧按钮区从来没生效过」。从代码上能指出四处
        // 互相独立的否决点，任何一处成立都足够让右侧一列完全不动。
        System.out.println("== T-37 快手右侧列四处否决点 ==");
        String hook293 = stripComments(read(dir.resolve("DouyinHook.java")));
        check(!hook293.contains("rh <= 0.22f"),
                "T-37a 22% 高度硬上限已删除（它把整列 25%~45% 全部否决）", "");
        check(hook293.contains("return rh <= 0.30f || rw <= 0.22f;"),
                "T-37b 整列按「窄」而不是「矮」放行（宽度才是按钮列与内容卡片的区别）", "");
        check(hook293.contains("if (rh <= 0.02f || rh > 0.50f)"),
                "T-37c 高度上限放到 50%，覆盖抖音式 6 按钮长列", "");
        check(hook293.contains("private static View findBottomDock(View v, int depth)"),
                "T-37d 新增「底部主 Tab 栏」结构判定", "");
        check(hook293.contains("videoLivePage = textHit && findBottomDock(root, 0) == null"),
                "T-37e 直播判定必须过结构闸（推荐流文案提到「直播间」不再整页停摆）", "");
        // v3.0.36 授权改判据：本轮收不到不再立刻清空，改为「3 轮宽限后仍清空」。
        // 旧行为是抖动源 —— 滚动一下列表就空、当场恢复、下一秒又藏，正是真机「一会儿藏一会儿露」。
        check(hook293.contains("RIGHT miss -> kept x")
                        && hook293.contains("MISS_GRACE_ROUNDS"),
                "T-37f 本轮收不到先给 3 轮宽限，超时才清空（抖动不再抽搐）", "");
        check(hook293.contains("lastRightReject") && hook293.contains("\" rrej=\""),
                "T-37g 诊断行带 rrej=，能区分「没收到」与「收到了被形状否决」", "");
        check(hook293.contains("private static void collectRightIcons(View v, List<View> out, int depth)"),
                "T-37h 右缘图标列兜底收集器仍在（整宽浮层结构下的唯一入口）", "");
        // 漏恢复：旧列表被 clear 掉时，正在隐藏的 View 再也没有引用，永久不显示。
        // 用户说的「看别的视频时导航栏突然消失」就是这条。
        check(hook293.contains("private static void releaseAll(List<View> olds, List<View> keep)"),
                "T-37j 新增旧视图释放器（丢弃前先恢复）", "");
        check(hook293.contains("releaseAll(rightList, rights)")
                        && hook293.contains("releaseAll(rightList, keep)"),
                "T-37k 右侧列表替换与宽限淘汰两条路径都先恢复", "");
        check(hook293.contains("releaseAll(bottomList, bottoms)"),
                "T-37l 底部列表替换同样先恢复", "");
        int deepCalls = countLiteral(hook293, "showDeep(topBar)")
                + countLiteral(hook293, "showDeep(rightTop)")
                + countLiteral(hook293, "showDeep(leftTop)");
        check(deepCalls >= 3,
                "T-37m 顶部栏 / 右上 / 左上被替换时也恢复（共 " + deepCalls + " 处）", "");
        check(versionAtLeast(gradle, 55, "2.9.3"),
                "T-37i 版本号升到 vc55 / 2.9.3", "");

        // ---------- T-38 v2.9.4：日志黑洞 / 直播间启用 / 感谢名单 ----------
        // 真机日志只有 SystemUI 心跳，四个视频 App 的 ENTER/INJECTED/HOOK 一行都没有，
        // 但抖音页面上确实有模块挂的悬浮球 —— 说明注入成功、只是日志回传全丢。
        System.out.println("== T-38 日志黑洞修复 / 直播间硬闸 / 感谢名单 ==");
        String prov294 = stripComments(read(dir.resolve("ConfigProvider.java")));
        String conf294 = stripComments(read(dir.resolve("ConfStore.java")));
        check(prov294.contains("new java.io.File(ctx.getFilesDir(), HOOK_LOG_FILE)"),
                "T-38a 注入进程日志改写 App 私有 files 目录（分区存储不再拦）", "");
        check(!prov294.contains("new java.io.File(ConfStore.LOG_FILE)"),
                "T-38b 不再直写 /sdcard/Android 下不可写的 LOG_FILE", "");
        check(conf294.contains("public static String hookLogFile()")
                        && conf294.contains("+ auditSrc + hookLogSrc"),
                "T-38c exportLogs 把私有镜像一起 cat 进用户可见日志", "");
        check(hook292.contains("private static final boolean LIVE_ROOM_HIDE = true;"),
                "T-38d 直播间隐藏通道启用", "");
        check(hook292.contains("private static boolean hasLiveInputBar(View v, int depth)")
                        && hook292.contains("说点什么"),
                "T-38e 新增「底部输入条」硬结构闸（普通视频页无输入框）", "");
        check(hook292.contains("!hasTab && (hasLiveInput || videoLivePage)"),
                "T-38f 暂停判定必须同时过「无首页 Tab」与「输入条 / 文案」", "");
        check(hook292.contains("LIVE_ROOM_HIDE && livePaused && Config.enableLive"),
                "T-38g 直播页有自己的进入隐藏窗口条件（否则三区永不执行）", "");
        check(hook292.contains("private static boolean hideLiveCandidate(View v, String what)")
                        && hook292.contains("containsDynamicUi(v, 0)")
                        && hook292.contains("containsVideoSurface(v, 0)")
                        && hook292.contains("tooBigToHide(v)")
                        && hook292.contains("!safeToHide(v, what)"),
                "T-38h 直播间候选四道排除：输入 / 弹幕 / 画面 / 形状白名单一律不碰", "");
        // v2.9.5：兜底保留（删掉会让三区几何常空的机型又「完全没反应」），
        // 但每个候选按区域语义过门禁，且绝不碰底部输入条 / 弹幕区。
        check(hook292.contains("liveTopList.isEmpty() && liveBottomList.isEmpty() && liveRightList.isEmpty()")
                        && hook292.contains("hideLiveCandidate(topBar, \"top\")")
                        && hook292.contains("hideLiveCandidate(v, \"right\")"),
                "T-38i 三区全空才兜底，且候选按区域语义过门禁（v2.9.5）", "");
        check(countLiteral(hook292, "hideLiveCandidate(v, \"bottom\")") == 1,
                "T-38i2 底部候选只来自直播三区，兜底不碰输入条 / 弹幕区", "");
        check(hook292.contains("\" lin=\"") && hook292.contains("\" lhid=\""),
                "T-38j 诊断行带 lin / l3 / lhid，能定位直播卡在哪一层", "");
        check(main292.contains("OptionRow(\"直播间隐藏\""),
                "T-38k 抖音功能页恢复「直播间隐藏」开关", "");
        check(main292.contains("酷安@bzm123123") && main292.contains("帮忙测试 ColorOS 15 环境"),
                "T-38l 感谢名单新增 @bzm123123（ColorOS 15 测试）", "");
        check(versionAtLeast(gradle, 56, "2.9.4"),
                "T-38m 版本号升到 vc56 / 2.9.4", "");

        // ---------- T-39 v2.9.5：「隐藏就挂」收紧 / 日志黑洞真因 ----------
        // 真机：抖音直播间球「显」正常、切「藏」就挂；导出日志里 App 内 HOOK 行仍全空。
        // 评审独立复核出 writeNow() 直写成功 return 把 providerLog() 整个旁路（确定性缺陷）。
        System.out.println("== T-39 直播候选按区域收紧 / 日志回传不再被直写短路 ==");
        String hook295 = stripComments(read(dir.resolve("DouyinHook.java")));
        String xp295 = stripComments(read(dir.resolve("XposedInit.java")));
        check(!hook295.contains("hideLiveCandidate(v, \"live\")"),
                "T-39a 候选不再统一用 \"live\" 落到小控件分支（否则顶栏 / 礼物列全灭）", "");
        check(hook295.contains("!safeToHide(v, what)") && hook295.contains("hideSafe(v, what);"),
                "T-39b 直播候选复用已有 safeToHide，未新增阈值 / 开关", "");
        check(hook295.contains("liveTopList.isEmpty() && liveBottomList.isEmpty() && liveRightList.isEmpty()"),
                "T-39c 保留三区全空兜底，避免 ColorOS 上「完全没反应」", "");
        check(countLiteral(hook295, "hideLiveCandidate(v, \"bottom\")") == 1,
                "T-39d 兜底不碰 bottomList（输入条 / 弹幕区）", "");
        check(xp295.contains("boolean directOk = false;") && xp295.contains("directOk = true;"),
                "T-39e writeNow 用 directOk 标记直写结果，不再提前 return", "");
        int directIf = xp295.indexOf("if (!directOk) {");
        int providerCall = xp295.lastIndexOf("providerLog(line);");
        check(directIf >= 0 && providerCall > directIf,
                "T-39f providerLog 位于直写兜底块之后、无条件执行（不再被旁路）", "");
        check(xp295.contains("!directOk") && xp295.contains("PROVIDER_LOG_INTERVAL_MS"),
                "T-39g su 兜底只在直写失败时跑，回传仍保留 5 秒节流", "");
        check(versionAtLeast(gradle, 57, "2.9.5"),
                "T-39h 版本号升到 vc57 / 2.9.5", "");

        // ---------- T-40 v2.9.6：红果「付费抢先看」弹窗常驻隐藏 ----------
        // 用户：「红果把这个提示给隐藏了省的耽误我看短剧」。截图=红果付费页居中弹窗，
        // 悬浮球在画面上=红果进程确已注入。安全审计定性：仅本地 UI 遮挡层 INVISIBLE，
        // 不解锁播放 / 不替点按钮 / 不改付费逻辑；因此代码里严禁任何点击/广播/宿主业务调用。
        // 三轮独立复核收敛：R1 事件驱动（不开周期，否则纯浮层页 tick 永不启动=假成功）、
        // R2 最内层合格容器 + 55% 上限（宁可漏藏不误伤）、R3 强词门槛、
        // R5 强词下沉到候选子树（删整屏放行，否则同屏弱词横幅被误藏）。
        System.out.println("== T-40 红果付费弹窗常驻隐藏 / 事件驱动 / 强词下沉 / 零误伤 ==");
        String hook296 = stripComments(read(dir.resolve("DouyinHook.java")));
        String hook296raw = read(dir.resolve("DouyinHook.java"));
        check(hook296.contains("private static final boolean DRAMA_PAYWALL_HIDE = true;")
                        && hook296.contains("dramaPaywallList = new ArrayList<>()"),
                "T-40a 编译期常量默认开 + 独立 View 列表（一键回退：常量改 false）", "");
        check(hook296.contains("hideSafe(v, \"dramapaywall\")")
                        && hook296.contains("releaseAll(dramaPaywallList, targets)"),
                "T-40b 隐藏走已有 hideSafe 出口、恢复复用已有 releaseAll（对称不留永久 INVISIBLE）", "");
        check(hook296.contains("DPH_KEYS") && hook296.contains("\"付费抢先看\"")
                        && hook296.contains("\"0元抢先看\"") && hook296.contains("\"立即预约\""),
                "T-40c 按截图实有文案识别（弱词），经已有 normText 归一化", "");
        check(hook296.contains("DPH_STRONG_KEYS") && hook296.contains("\"应版权方要求\"")
                        && hook296.contains("\"后续转免费\""),
                "T-40d 强特征词门槛（避免推荐卡片/活动横幅误命中）", "");
        check(countLiteral(hook296, "updateDramaPaywallHide();") == 3,
                "T-40e 事件驱动三处调用点：onResume / onGlobalLayout / tick", "");
        check(countLiteral(hook296, "startCycle();") == 1,
                "T-40f 周期调度调用点仍只有 rescan 一处（DPH 靠事件驱动，未新增 startCycle 调用）", "");
        check(hook296.contains("if (!isDrama || !DRAMA_PAYWALL_HIDE) {")
                        && hook296.contains("releaseAll(dramaPaywallList, null)"),
                "T-40g 非红果进程首分支直返并恢复（抖音/快手/B站/SystemUI 零影响、零 DPH 日志）", "");
        check(hook296.contains("private static boolean dphStrongUnder(ViewGroup candidate, List<View> strongHits)")
                        && hook296.contains("dphStrongUnder((ViewGroup) t, strongHits)")
                        && hook296.contains("rejectedTargets"),
                "T-40h R5 强词下沉到候选子树、被拒候选单独计数（不再整屏放行）", "");
        check(!hook296.contains("dphHasStrong"),
                "T-40i 整屏放行闸 dphHasStrong 已彻底删除（同屏弱词横幅不会被误藏）", "");
        check(hook296.contains("g.getWidth() > sw * 0.55f || g.getHeight() > sh * 0.55f")
                        && !hook296.contains("g.getWidth() > sw * 0.70f"),
                "T-40j 尺寸安全上限收紧到 55%（R2 最内层合格容器，宁可漏藏不误伤）", "");
        check(hook296.contains("\"DPH n=\"") && hook296.contains("\" hits=\"")
                        && hook296.contains("\" rej=\""),
                "T-40k 诊断三态 n/hits/rej（下一份真机日志可区分没弹窗/被尺寸判掉/被强词挡掉）", "");
        check(!hook296.contains("performClick") && !hook296.contains("dispatchTouchEvent")
                        && !hook296.contains("sendBroadcast")
                        && hook296raw.contains("隐藏提示") ,
                "T-40l 绝不自动点击/发广播/调宿主业务，且注释明确「隐藏提示≠解锁播放」边界", "");
        check(hook296.contains("!v.isAttachedToWindow()")
                        && hook296raw.contains("绝对不要加 isShown"),
                "T-40m 收集只判 GONE+attach、INVISIBLE 仍算命中（避免自我恢复闪烁）", "");
        check(versionAtLeast(gradle, 58, "2.9.6"),
                "T-40n 版本号升到 vc58 / 2.9.6", "");

        // v2.9.7：台账 #4 收口——恢复路径只撤销本模块造成的 INVISIBLE+alpha0，
        // 绝不把宿主自设 GONE 点亮、绝不把宿主半透明 alpha 拉平。评审独立复核后
        // 定稿 A′：showDeep 内换 resetTranslationOnly，resetShift/applyShift 本体不动。
        System.out.println("== T-41 恢复路径只撤销本模块隐藏态 / 零新状态 / 一键回退 ==");
        String hook297 = stripComments(read(dir.resolve("DouyinHook.java")));
        String hook297raw = read(dir.resolve("DouyinHook.java"));
        // 截取 showDeep 方法体做「分支内」判定，避免与 resetShift/applyShift 里的同名判断混淆
        String sdBody = "";
        {
            int i = hook297.indexOf("private static void showDeep(View v)");
            int j = hook297.indexOf("private static void resetTranslationOnly(View v)", i);
            if (i >= 0 && j > i) {
                sdBody = hook297.substring(i, j);
            }
        }
        check(hook297.contains("private static final boolean SAFE_RESTORE = true;"),
                "T-41a 编译期常量 SAFE_RESTORE 默认开（false 即逐字节回 v2.9.6，一键回退）", "");
        check(sdBody.contains("if (!SAFE_RESTORE) {") && sdBody.contains("resetShift(v);")
                        && sdBody.contains("!= View.VISIBLE") && sdBody.contains("!= 1f"),
                "T-41b 回退分支保持 v2.9.6 原逻辑（无条件 resetShift + 两处越界恢复）", "");
        {
            int k = sdBody.indexOf("resetTranslationOnly(v);");
            String falsePart = k > 0 ? sdBody.substring(0, k) : sdBody;
            String truePart = k > 0 ? sdBody.substring(k) : "";
            check(k > 0 && falsePart.contains("!= View.VISIBLE") && falsePart.contains("!= 1f")
                            && truePart.contains("== View.INVISIBLE") && truePart.contains("== 0f")
                            && !truePart.contains("!= View.VISIBLE") && !truePart.contains("!= 1f"),
                    "T-41c 两分支隔离：回退分支保留越界语义，收紧分支只撤销 INVISIBLE+alpha0", "");
        }
        check(hook297.contains("private static void resetTranslationOnly(View v)"),
                "T-41d 新增专用方法：只归零 translation", "");
        {
            int i = hook297.indexOf("private static void resetTranslationOnly(View v)");
            int j = hook297.indexOf("\n    }", i);
            String nb = (i >= 0 && j > i) ? hook297.substring(i, j) : "";
            check(nb.contains("setTranslationX") && !nb.contains("setAlpha")
                            && !nb.contains("setVisibility"),
                    "T-41e resetTranslationOnly 绝不碰 alpha / visibility（复位不越界）", "");
        }
        check(hook297.contains("private static void resetShift(View v)")
                        && countLiteral(hook297, "v.getAlpha() != 1f") == 3,
                "T-41f resetShift/applyShift 本体逐字节未改（另 5 处调用零影响，红线守住）", "");
        check(hook297raw.contains("台账 #4") && hook297raw.contains("v2.9.7")
                        && hook297raw.contains("DPH 复用 showDeep()"),
                "T-41g 类注释与 R4 注释同步标注 v2.9.7 统一处理、DPH 一并受益", "");
        check(versionAtLeast(gradle, 59, "2.9.7"),
                "T-41h 版本号曾升到 vc59 / 2.9.7（历史下限，保持）", "");

        // ---------- T-42 3.0 稳定版：版本号 / Obtainium 递增锚点 ----------
        // 3.0 稳定版：功能代码沿用 v2.9.7 定稿，仅版本号进阶；tag 序号 = versionCode，
        // Obtainium 靠 versionCode 递增识别更新，故 vc60 / 3.0 是发布契约的一部分；
        // v3.0.1 仅做可观测性修复，锚点随版递增为 vc61 / 3.0.1；
        // v3.0.2 修三处几何识别 bug，锚点递增为 vc62 / 3.0.2；
        // v3.0.3 右上角安全修复 + 两处只读探测，锚点递增为 vc63 / 3.0.3。
        // v3.0.4~v3.0.9 依次递增；v3.0.10 相位 tick 与设置页轮询降频，锚点递增为 vc70 / 3.0.10。
        // v3.0.11 桌面图标/最近任务隐藏 + P0 误伤修复 + P5 磁贴 + P6 UI 简洁化 + P-1 heads-up 修复，锚点递增为 vc71 / 3.0.11。
        System.out.println("== T-42 3.0 稳定版版本号 / 发布契约 ==");
        check(versionAtLeast(gradle, 60, "3.0"),
                "T-42a 版本号升到 vc60 / 3.0 稳定版（Obtainium 递增识别）", "");
        {
            Matcher cm = Pattern.compile("versionCode\\s+(\\d+)").matcher(gradle);
            Matcher nm = Pattern.compile("versionName\\s+\"([^\"]+)\"").matcher(gradle);
            boolean ok = cm.find() && nm.find()
                    && Integer.parseInt(cm.group(1)) == 105
                    && "3.2.6".equals(nm.group(1));
            check(ok, "T-42b 版本锚点恰为 99 / 3.2（非区间，锁死发布锚点）", "");
        }
        check(hook297raw.contains("SAFE_RESTORE") && hook297raw.contains("DRAMA_PAYWALL_HIDE"),
                "T-42c 3.0 沿用 v2.9.7/v2.9.6 定稿功能（恢复路径 + 红果弹窗均在）", "");

        // ---------- T-43 v3.0.1 Quick Flow 可观测性修复 ----------
        // 真机日志证明视频 App 进程从未被注入，五处隐藏区域代码一行没跑；
        // 本轮只让「注没注入 / 该怎么修」可见，不碰几何算法与注入链。三条分别锁 P1/P2/P3。
        System.out.println("== T-43 v3.0.1 可观测性修复 ==");
        String selfBody = slice(confStore, "private static void appendSelfCheck()", "private static void appendRaw(");
        String readAllBody = slice(confStore, "private static String readAllLogs()", "private static String readHookMirror()");
        String mirrorBody = slice(confStore, "private static String readHookMirror()", "private static String mergeUniqueLines");
        String mergeBody = slice(confStore, "private static String mergeUniqueLines", "private static String enterHits()");
        check(selfBody.contains("readAllLogs()")
                        && readAllBody.contains("readHookMirror()")
                        && mirrorBody.contains("hookLogFile()")
                        && mergeBody.contains("LinkedHashSet"),
                "T-43a P1 接线核查：SELF 经 readAllLogs 纳入 hook 私有镜像，整行去重且不写回原始日志", "");
        // 评审返工：不再只查源码文本。对真实编译产物里的 private static mergeUniqueLines 做行为验证。
        // 该方法只依赖 LinkedHashSet、不触碰 Android API，故可在纯 JVM 用反射真调；
        // setAccessible 是唯一放宽点，被测方法本体零改动。
        {
            boolean behaviorOk = false;
            String detail = "未执行";
            try {
                Class<?> cc = Class.forName("io.github.wumisc.antiburn.ConfStore");
                java.lang.reflect.Method m = cc.getDeclaredMethod("mergeUniqueLines", String[].class);
                m.setAccessible(true);
                // base 含重复行；mirror 含两条镜像独有行 + 两条与 base 完全相同的行。
                // 期望：整行去重（不是按字段）、保序、镜像独有行确实进入结果、null/空源被跳过。
                String base = "1000000000 [sys] BASE only\n"
                        + "1000000001 [app] SHARED line\n"
                        + "1000000002 [hook] DUP line\n";
                String mirror = "1000000003 [hook] INJECTED Douyin\n"
                        + "1000000001 [app] SHARED line\n"
                        + "1000000004 [hook] HOOK app= douyin\n"
                        + "1000000002 [hook] DUP line\n";
                // 必须强转成 Object，否则 String[] 会被当作 invoke 的 varargs 数组本身，参数个数不匹配。
                String out = (String) m.invoke(null, (Object) new String[]{base, mirror, null, ""});
                List<String> got = new ArrayList<>();
                for (String ln : out.split("\\r?\\n")) {
                    if (!ln.isEmpty()) {
                        got.add(ln);
                    }
                }
                boolean dedup = got.size() == new LinkedHashSet<>(got).size();
                boolean mirrorIn = got.contains("1000000003 [hook] INJECTED Douyin")
                        && got.contains("1000000004 [hook] HOOK app= douyin");
                boolean ordered = got.size() == 5
                        && got.get(0).equals("1000000000 [sys] BASE only")
                        && got.get(2).equals("1000000002 [hook] DUP line")
                        && got.get(3).equals("1000000003 [hook] INJECTED Douyin")
                        && got.get(4).equals("1000000004 [hook] HOOK app= douyin");
                behaviorOk = dedup && mirrorIn && ordered;
                detail = "输出 " + got.size() + " 行；整行去重=" + dedup + " 镜像行进入=" + mirrorIn + " 保序=" + ordered;
            } catch (Throwable t) {
                detail = "反射调用失败：" + t.getClass().getName() + " " + t.getMessage();
            }
            check(behaviorOk, "T-43a-behavior P1 mergeUniqueLines 真实行为：整行去重/保序/镜像行进入结果", detail);
        }
        int tipAt = main.indexOf("「未检测到」=");
        String injectTip = tipAt >= 0 ? main.substring(tipAt, Math.min(main.length(), tipAt + 500)) : "";
        String diagBody = slice(lsp, "static String diagnose(Context ctx)", "private static String lspLogDigest");
        int doneAt = diagBody.indexOf("结论：作用域已勾齐");
        String doneTip = doneAt >= 0 ? diagBody.substring(doneAt, Math.min(diagBody.length(), doneAt + 500)) : "";
        check(injectTip.contains("重启手机") && injectTip.contains("dex")
                        && injectTip.contains("不是只重启系统界面")
                        && doneTip.contains("重启手机") && doneTip.contains("dex")
                        && doneTip.contains("不是只重启系统界面"),
                "T-43b P2 注入卡与作用域自检均含覆盖安装后需重启手机的指引（不是只重启系统界面）", "");
        {
            Matcher cm = Pattern.compile("versionCode\\s+(\\d+)").matcher(gradle);
            Matcher nm = Pattern.compile("versionName\\s+\"([^\"]+)\"").matcher(gradle);
            boolean ok = cm.find() && nm.find()
                    && Integer.parseInt(cm.group(1)) == 105
                    && "3.2.6".equals(nm.group(1));
            // 与 T-42b 断言同源，是发布前的双重防回退锁，不冒充新增不变量（评审建议改标签）。
            check(ok, "T-43c P3 版本锚点与 T-42b 双重锁定（99/3.2）（发布前防回退，非新增不变量）", "");
        }

        // ---------- T-44 v3.0.2 三处几何 bug 修复：P1 下限 / 越界防护 / 版本锚点 ----------
        // Bug A+B：scoreLeftTop 补 30px 尺寸下限，与 scoreLiveRight 同源，
        // 让 0x0 阴影/布局代理容器拿 -1 分不再入选；其余评分函数一行未动。
        // Bug C：v3.0.2 未承诺修好，只锁「right 门槛未放宽」；v3.0.3 仍不放宽门槛，
        // 只补两处只读诊断（RDUMP / BDUMP），任何消除动作一律下轮。
        System.out.println("== T-44 v3.0.2 三处几何识别 bug 修复（v3.0.3 延续门槛锁）==");
        String dy302raw = read(dir.resolve("DouyinHook.java"));
        {
            String lt = stripComments(slice(dy302raw, "private static int scoreLeftTop(ViewGroup g)",
                    "// ---------- 工具 ----------"));
            String lr = stripComments(slice(dy302raw, "private static int scoreLiveRight(ViewGroup g)",
                    "private static int scoreRightTop(ViewGroup g)"));
            check(lt.contains("getWidth() < 30") && lt.contains("getHeight() < 30")
                            && lr.contains("getWidth() < 30") && lr.contains("getHeight() < 30"),
                    "T-44a P1 scoreLeftTop 已补 30px 尺寸下限且与 scoreLiveRight 同源", "");
        }
        {
            String rt = stripComments(slice(dy302raw, "private static int scoreRightTop(ViewGroup g)",
                    "private static int scoreLeftTop(ViewGroup g)"));
            // 基线中 rw 门槛只有 `rw >= 0.30f`（否决）与 `rw <= 0.22f`（大列放行），
            // 不存在 `rw >= 0.22f`；一旦出现后者即门槛被放宽，必须 FAIL。
            boolean gateOk = countLiteral(dy302raw, "rw >= 0.30f") >= 1
                    && countLiteral(dy302raw, "rw <= 0.22f") >= 1
                    && countLiteral(dy302raw, "rw >= 0.22f") == 0;
            // v3.0.3 P1 已在本方法补上 30px 下限；本锁同步为「下限已补 + 门槛未放宽」，
            // 与 T-45b 的四方法零新增下限互补，任何一侧回退都会 FAIL。
            check(rt.contains("getWidth() < 30") && rt.contains("getHeight() < 30") && gateOk,
                    "T-44b 同步 v3.0.3：scoreRightTop 已补 30px 下限、right 门槛未放宽", "");
        }
        {
            Matcher cm = Pattern.compile("versionCode\\s+(\\d+)").matcher(gradle);
            Matcher nm = Pattern.compile("versionName\\s+\"([^\"]+)\"").matcher(gradle);
            boolean ok = cm.find() && nm.find()
                    && Integer.parseInt(cm.group(1)) == 105
                    && "3.2.6".equals(nm.group(1));
            check(ok, "T-44c 版本号恰为 99 / 3.2（发布锚点）", "");
        }

        // ---------- T-45 v3.0.3 右上角安全修复 + 两处只读探测越界防护 ----------
        // P1：scoreRightTop 补 30px 下限，与 scoreLeftTop / scoreLiveRight 同源。
        // P2/P3：RDUMP / BDUMP 只采集不隐藏；本轮门槛与隐藏函数一律不放宽。
        System.out.println("== T-45 v3.0.3 右上角安全修复 + 只读探测 ==");
        String dy303raw = read(dir.resolve("DouyinHook.java"));
        {
            String rt = stripComments(slice(dy303raw, "private static int scoreRightTop(ViewGroup g)",
                    "private static int scoreLeftTop(ViewGroup g)"));
            String lt = stripComments(slice(dy303raw, "private static int scoreLeftTop(ViewGroup g)",
                    "// ---------- 工具 ----------"));
            String lr = stripComments(slice(dy303raw, "private static int scoreLiveRight(ViewGroup g)",
                    "private static int scoreRightTop(ViewGroup g)"));
            check(rt.contains("getWidth() < 30") && rt.contains("getHeight() < 30")
                            && lt.contains("getWidth() < 30") && lt.contains("getHeight() < 30")
                            && lr.contains("getWidth() < 30") && lr.contains("getHeight() < 30"),
                    "T-45a P1 scoreRightTop 30px 下限已补且与 scoreLeftTop/scoreLiveRight 同源", "");
        }
        {
            String ci = stripComments(slice(dy303raw, "private static void collectRightIcons(",
                    "/** 单个「右缘小按钮」的准入"));
            String ri = stripComments(slice(dy303raw, "private static boolean rightIconOk(",
                    "/** 竖向摊开校验"));
            String st = stripComments(slice(dy303raw, "private static boolean safeToHide(",
                    "/** 隐藏单个区域的唯一出口"));
            String sr = stripComments(slice(dy303raw, "private static int scoreRight(ViewGroup g)",
                    "private static int scoreRightEdge(ViewGroup g)"));
            boolean noNewLimit = !ci.contains("getWidth() < 30") && !ri.contains("getWidth() < 30")
                    && !st.contains("getWidth() < 30") && !sr.contains("getWidth() < 30");
            boolean gateOk = countLiteral(dy303raw, "rw >= 0.30f") >= 1
                    && countLiteral(dy303raw, "rw <= 0.22f") >= 1
                    && countLiteral(dy303raw, "rw >= 0.22f") == 0;
            check(noNewLimit && gateOk,
                    "T-45b 越界防护：四个红线方法无新增 30px 下限、rw>=0.30f 未变且无 rw>=0.22f", "");
        }
        {
            Matcher cm = Pattern.compile("versionCode\\s+(\\d+)").matcher(gradle);
            Matcher nm = Pattern.compile("versionName\\s+\"([^\"]+)\"").matcher(gradle);
            boolean ok = cm.find() && nm.find()
                    && Integer.parseInt(cm.group(1)) == 105
                    && "3.2.6".equals(nm.group(1));
            check(ok, "T-45c 版本号恰为 99 / 3.2（发布锚点）", "");
        }

        // ---------- T-46 v3.0.4 快手右侧作者栏放行（用户授权绕 rw>=0.30 红线） ----------
        // 全部断言基于去注释后的源码文本 + 相对顺序，不依赖插桩后绝对行号。
        // 结束锚点统一采用全文唯一、单行方法声明字面量（D4 修订），不用多行注释首行。
        System.out.println("== T-46 v3.0.4 快手右侧作者栏放行 ==");
        String dy304raw = read(dir.resolve("DouyinHook.java"));
        // 结束锚点 hideSafe( 真实存在且紧跟 safeToHide 之后：slice 精确停在 safeToHide 方法末尾
        String st = stripComments(slice(dy304raw, "private static boolean safeToHide(",
                "private static void hideSafe("));
        {
            // ① 分支位置（视频之后、门槛之前）—— 钉死「分支不得上移绕过视频否决」
            int videoAt = st.indexOf("containsVideoSurface(v, 0)");
            int p1At = st.indexOf("isKuaishou && \"right\".equals(what)");
            int rwAt = st.indexOf("rw >= 0.30f");
            int rwVarAt = st.indexOf("float rw =");
            check(videoAt >= 0 && p1At > videoAt && p1At < rwAt && p1At < rwVarAt,
                    "T-46a P1 分支位于 containsVideoSurface 之后、rw>=0.30f 之前", "");
        }
        {
            // ② 双特征 + 门控 + 视频自查齐全 —— 钉死「头像+关注双特征同时命中」
            String p1 = slice(st, "if (isKuaishou && \"right\".equals(what)", "return true;");
            boolean t46Features = p1.contains("AvatarWithPendantView")
                    && p1.contains("FollowButtonView")
                    && p1.contains("!containsVideoSurface(v, 0)")
                    && countLiteral(p1, "hasChildByClass(") == 2;
            check(t46Features, "T-46b P1 条件齐全：isKuaishou + 头像 + 关注 + !视频", "");
        }
        {
            // ②b（S3）单维面积上限表达式锁 —— 钉死「视频不藏的结构性保证不被删/不放宽」
            String p1 = slice(st, "if (isKuaishou && \"right\".equals(what)", "return true;");
            boolean areaCap = p1.contains("v.getWidth() <= w * 0.50f")
                    && p1.contains("v.getHeight() <= h * 0.50f")
                    && countLiteral(p1, "0.50f") == 2
                    && !p1.contains("w * 0.60f") && !p1.contains("h * 0.60f");
            check(areaCap, "T-46f P1 单维面积上限 <=0.50f 双维俱在且阈值未放宽", "");
        }
        {
            // ③ 通用门槛 + tooBigToHide 零改动（含 return false / 运算符字面锁）
            String p1 = slice(st, "if (isKuaishou && \"right\".equals(what)", "return true;");
            boolean gates = countLiteral(st, "rw >= 0.30f") == 1
                    && countLiteral(st, "rh <= 0.02f || rh > 0.50f") == 1
                    && countLiteral(st, "return rh <= 0.30f || rw <= 0.22f;") == 1
                    && !p1.contains("rw >= 0.30f") && !p1.contains("rh > 0.50f")
                    && !p1.contains("return rh <=");
            String g2716 = slice(st, "if (rw >= 0.30f)", "if (rh <=");
            String g2719 = slice(st, "if (rh <= 0.02f || rh > 0.50f)", "return rh <=");
            boolean gateLogic = g2716.contains("return false;")
                    && g2719.contains("return false;")
                    && countLiteral(st, "|| rh > 0.50f") == 1
                    && countLiteral(st, "return rh <= 0.30f || rw <= 0.22f;") == 1;
            String t46hs = stripComments(slice(dy304raw, "private static void hideSafe(",
                    "private static boolean isOwnOverlay("));
            String tbg = stripComments(slice(dy304raw, "private static boolean tooBigToHide(",
                    "private static void updateShiftPhase("));
            boolean tbgLogic = tbg.contains("v.getWidth() > sw * 0.45f && v.getHeight() > sh * 0.45f")
                    && tbg.contains("return v.getWidth() > sw * 0.45f && v.getHeight() > sh * 0.45f;")
                    && tbg.contains("return false;");
            check(gates && gateLogic && tbgLogic && t46hs.contains("if (tooBigToHide(v))"),
                    "T-46c 2716/2719/2722 逐字未改（含 return false/|| 运算符），tooBigToHide 双超 && 45% 未改", "");
        }
        {
            // ④ 视频检查仍是首个实质判定（P2 会在其 if 体内加赋值，故不写「方法体第二个 if」）
            int nullAt = st.indexOf("if (v == null)");
            int firstIfAt = st.indexOf("if (", nullAt + 10);
            String firstIf = st.substring(firstIfAt, st.indexOf("\n", firstIfAt));
            check(st.indexOf("containsVideoSurface") < st.indexOf("screenW(v)")
                            && firstIf.contains("containsVideoSurface(v, 0)"),
                    "T-46d containsVideoSurface 仍是 safeToHide 首个实质判定", "");
        }
        {
            // ⑤ 版本锚点 + 辅助方法只读性
            Matcher cm = Pattern.compile("versionCode\\s+(\\d+)").matcher(gradle);
            Matcher nm = Pattern.compile("versionName\\s+\"([^\"]+)\"").matcher(gradle);
            boolean ver = cm.find() && nm.find() && Integer.parseInt(cm.group(1)) == 105
                    && "3.2.6".equals(nm.group(1));
            String helper = stripComments(slice(dy304raw, "private static boolean hasChildByClass(View v, String target)",
                    "private static int scoreRight(ViewGroup g)"));
            boolean readonly = helper.contains("MAX_DEPTH") && helper.contains("catch (Throwable")
                    && !helper.contains("setVisibility") && !helper.contains("setAlpha")
                    && !helper.contains("setTranslation");
            check(ver && readonly, "T-46e 版本 99/3.2；hasChildByClass 有深度上限+异常收敛且只读", "");
        }

        // ---------- T-47 v3.0.5 底部标题栏只读探针 TDUMP ----------
        // 本轮只探测不隐藏：三条分别锁「接入 + 独立节流」「方法体零状态修改 + 接入位置」「版本锚点」。
        System.out.println("== T-47 v3.0.5 TDUMP 底部标题栏只读探测 ==");
        String dy305raw = read(dir.resolve("DouyinHook.java"));
        {
            String rescanBody = slice(dy305raw, "private static void rescan(ViewGroup root)",
                    "// 直播间专用三区收集。");
            boolean wired = rescanBody.contains("dumpBottomTitles(root);")
                    && countLiteral(dy305raw, "TDUMP") >= 1
                    && countLiteral(dy305raw, "lastTitleDumpAt") >= 1;
            check(wired, "T-47a P1 dumpBottomTitles(root) 已接入 rescan，TDUMP 前缀与 lastTitleDumpAt 独立节流存在", "");
        }
        {
            // 锚点均取全文唯一的单行方法声明，截取精确停在 RDUMP 定义前（不用多行注释首行）。
            String probeBody = slice(dy305raw, "private static void dumpBottomTitles",
                    "private static void dumpRightIcons");
            String[] banned = {"setAlpha", "setVisibility", "hideSafe", "showDeep",
                    "performClick", "setText", "setBackground"};
            boolean readOnly = !probeBody.isEmpty();
            for (String b : banned) {
                readOnly = readOnly && countLiteral(probeBody, b) == 0;
            }
            String rescanBody = slice(dy305raw, "private static void rescan(ViewGroup root)",
                    "// 直播间专用三区收集。");
            int badgeAt = rescanBody.indexOf("dumpBadges(root);");
            int titleAt = rescanBody.indexOf("dumpBottomTitles(root);");
            boolean after = badgeAt >= 0 && titleAt > badgeAt;
            check(readOnly && after,
                    "T-47b P1 只读证明：两方法体零 View 状态修改，接入点在 dumpBadges(root) 之后",
                    "banned=0 after=" + after);
        }
        {
            Matcher cm = Pattern.compile("versionCode\\s+(\\d+)").matcher(gradle);
            Matcher nm = Pattern.compile("versionName\\s+\"([^\"]+)\"").matcher(gradle);
            boolean ok = cm.find() && nm.find()
                    && Integer.parseInt(cm.group(1)) == 105
                    && "3.2.6".equals(nm.group(1));
            check(ok, "T-47c 版本号恰为 99 / 3.2（v3.0.13 发布锚点）", "");
        }

        // ---------- T-48 v3.0.6 三处精准隐藏（红点禁藏为开发门禁，缺一即失败） ----------
        // 三条负向断言统一用 count(regex)（Pattern.find 真计数），不用 countLiteral("A|B|C") 空断言。
        System.out.println("== T-48 v3.0.6 P1 点赞栏 / P2 红点 / P3 标题栏 ==");
        String dy306 = read(dir.resolve("DouyinHook.java"));
        String cfg306 = read(dir.resolve("Config.java"));
        String act306 = read(dir.resolve("MainActivity.kt"));
        {
            // P1a：放行分支存在 + 位置 + 双门锁（开关先于 id 匹配，防挪到分支外绕过）
            String p1Body = slice(dy306, "v3.0.4 P1 插入点结束", "float rw =");
            boolean shape = p1Body.contains("matchesActionButtonId(v)")
                    && p1Body.contains("v.getWidth() <= w * 0.50f")
                    && p1Body.contains("!containsVideoSurface(v, 0)")
                    && p1Body.contains("ksRightAllow = \"actions\"");
            int swAt = p1Body.indexOf("Config.enableRightActions");
            int idAt = p1Body.indexOf("matchesActionButtonId(v)");
            boolean doubleGate = countLiteral(p1Body, "Config.enableRightActions") >= 1
                    && swAt >= 0 && idAt >= 0 && swAt < idAt;
            check(shape && doubleGate,
                    "T-48-P1a P1 分支四要素齐 + enableRightActions 双门内嵌且先于 id 匹配", "");
        }
        {
            // P1b：四 id 精确匹配 + 只读（R1 改 A：count(regex) 真挡 setter）
            String body = slice(dy306, "private static boolean matchesActionButtonId(View v)",
                    "private static int scoreRight(ViewGroup g)");
            boolean ids = body.contains("\"like_button\"") && body.contains("\"comment_button\"")
                    && body.contains("\"collect_button\"") && body.contains("\"forward_button\"");
            boolean readOnly = count(body, "(setAlpha|setVisibility|setText)") == 0
                    && body.contains("return false");
            check(ids && readOnly,
                    "T-48-P1b 四 id 精确匹配 + 只读无 setter + 异常 return false", "");
        }
        {
            // P2a① 黑名单先行（语义判据，两处 indexOf 均须 >=0，挡 -1<x 假通过）
            String col = slice(dy306, "private static void collectRedDots",
                    "private static boolean isSmallBadgeChild");
            int idxForbid = col.indexOf("isBadgeForbidden(c, parent)");
            int idxSafe = col.indexOf("isBadgeSafe(c)");
            boolean firstGate = idxForbid >= 0 && idxSafe >= 0 && idxForbid < idxSafe;
            // P2a② 四类红线字面量齐备
            String forbidBody = slice(dy306, "private static boolean isBadgeForbidden",
                    "private static boolean isBadgeSafe");
            boolean literals = forbidBody.contains("TextSwitcher")
                    && forbidBody.contains("DuxTextView") && forbidBody.contains("\"0\"")
                    && forbidBody.contains("DmtTextView") && forbidBody.contains("guq")
                    && forbidBody.contains("isShortDigits(") && forbidBody.contains("none")
                    && forbidBody.contains("milano_player_seekbar")
                    && forbidBody.contains("featured_god_comment_tip")
                    && forbidBody.contains("vip_badge");
            // P2a③ 异常安全方向（catch 块内确有 return true）
            int iCatch = forbidBody.indexOf("catch (Throwable");
            boolean failClosed = iCatch >= 0 && forbidBody.substring(iCatch).contains("return true");
            check(firstGate && literals && failClosed,
                    "T-48-P2a 红点禁藏第一道：黑名单先行 + 四类红线字面量 + catch→禁藏", "");
        }
        {
            // P2b① 白名单条目齐备
            String safeBody = slice(dy306, "private static boolean isBadgeSafe",
                    "HollowAvatarBadgeLayout 特殊处理");
            boolean entries = safeBody.contains("SelectShapeView") && safeBody.contains("red_dot")
                    && safeBody.contains("OnlineDotView") && safeBody.contains("online_dot")
                    && safeBody.contains("DuxBadgeView") && safeBody.contains("1ky")
                    && safeBody.contains("notify") && safeBody.contains("right_avatar_notify")
                    && safeBody.contains("#fff73b68");
            // P2b② 负向断言（R1 改 A）：白名单体出现任一黑名单判据/具名计数字面量即 FAIL
            boolean noForbiddenInSafe = count(safeBody,
                    "(DuxTextView|DmtTextView|TextSwitcher|isShortDigits|guq|\"0\")") == 0;
            // P2b③ online_dot 四元 AND（bg=LayerDrawable + ≤80 双维）
            boolean onlineAnd = safeBody.contains("LayerDrawable")
                    && safeBody.contains("v.getWidth() <= 80")
                    && safeBody.contains("v.getHeight() <= 80");
            check(entries && noForbiddenInSafe && onlineAnd,
                    "T-48-P2b① 白名单齐备 + ② 负向零越界(count regex) + ③ online_dot 四元 AND", "");
        }
        {
            // P2b④ S2 精确枚举 + 六 equals 逐一锁 + 二次判定三件套（underBadgeLayout / cw>60 / 黑名单二次）
            String smallBody = slice(dy306, "private static boolean isSmallBadgeChild",
                    "private static void updateRedDots");
            boolean exactOnly = countLiteral(smallBody, "matchesBadgeSemantic") == 0
                    && smallBody.contains("isExactBadgeId");
            String exactBody = slice(dy306, "private static boolean isExactBadgeId",
                    "private static void updateRedDots");
            String[] six = {"\"red_dot\".equals(id)", "\"online_dot\".equals(id)",
                    "\"badge\".equals(id)", "\"notify\".equals(id)",
                    "\"right_avatar_notify\".equals(id)", "\"1ky\".equals(id)"};
            boolean sixOk = true;
            for (String lit : six) {
                sixOk = sixOk && countLiteral(exactBody, lit) == 1;
            }
            sixOk = sixOk && countLiteral(exactBody, ".equals(id)") == 6;
            boolean secondGate = smallBody.contains("cw > 60")
                    && smallBody.contains("isBadgeForbidden(v, null)");
            String col = slice(dy306, "private static void collectRedDots",
                    "private static boolean isSmallBadgeChild");
            boolean underGate = col.contains("underBadgeLayout && isSmallBadgeChild(c)");
            check(exactOnly && sixOk && secondGate && underGate,
                    "T-48-P2b④ 精确枚举(非contains) + 六 equals 逐一 + 二次判定三件套", "");
        }
        {
            // P2c 默认拒绝 + 红果门控 + 恢复提交
            String upd = slice(dy306, "private static void updateRedDots",
                    "private static void collectCaptions");
            boolean gate = upd.contains("Config.enableRedDot") && upd.contains("!isDrama");
            String safeBody = slice(dy306, "private static boolean isBadgeSafe",
                    "HollowAvatarBadgeLayout 特殊处理");
            boolean defaultDeny = countLiteral(safeBody, "return false") >= 1;
            boolean commit = upd.contains("releaseAll") && upd.contains("redDotList.clear()");
            check(gate && defaultDeny && commit,
                    "T-48-P2c 默认拒绝 + !isDrama 门控 + releaseAll/clear 提交", "");
        }
        {
            // P2d 恢复闭环：showAll + tick redOn 分支
            String showBody = slice(dy306, "private static void showAll()",
                    "/**\n     * 丢弃旧视图前先把它恢复显示");
            boolean inShowAll = showBody.contains("for (View v : redDotList)");
            boolean redOn = countLiteral(dy306, "boolean redOn") >= 1
                    && countLiteral(dy306, "hideSmallSafe(v, \"reddot\")") >= 1
                    && countLiteral(dy306, "showDeep(v)") >= 1;
            check(inShowAll && redOn, "T-48-P2d 恢复闭环：showAll 遍历 redDotList + tick redOn 分支", "");
        }
        {
            // P3a 三 App 锚点存在性
            boolean a = dy306.contains("CaptionTextView") && dy306.contains("element_caption_label")
                    && dy306.contains("ScrollMentionTextView") && dy306.contains("FeedDescParentLayout")
                    && dy306.contains("\"desc\"")
                    && dy306.contains("StoryTitleWidget") && dy306.contains("StoryInfoModule")
                    && dy306.contains("\"title\"");
            check(a, "T-48-P3a 快手/抖音/B站 三锚点齐备", "");
        }
        {
            // P3b 排除项不变量：排除 id 在源码存在（说明锚点），但不在捕获条件内
            String[] excl = {"user_name_text_view", "general_entry_title",
                    "slide_play_photo_disclaimer_text", "StoryUpNameWidget", "creative_text"};
            boolean present = true;
            for (String e : excl) {
                present = present && dy306.contains(e);
            }
            String capBody = slice(dy306, "private static void collectCaptions",
                    "private static void updateCaptions");
            boolean capClean = true;
            for (String e : excl) {
                capClean = capClean && !capBody.contains(e);
            }
            boolean douyinParent = capBody.contains("\"FeedDescParentLayout\".equals(pcls)");
            check(present && capClean && douyinParent,
                    "T-48-P3b 排除项存在但不在 collectCaptions 捕获条件内 + 抖音 parent 锚点", "");
        }
        {
            // P3c 红果门控 + 专用隐藏出口（R1 改 A）：safeToHide 新增 caption/reddot 分支即 FAIL
            String upd = slice(dy306, "private static void updateCaptions",
                    "private static boolean matchesRightSemantic");
            boolean dramaOut = upd.contains("!isDrama");
            String hideBody = slice(dy306, "private static void hideSmallSafe",
                    "private static void showAll()");
            boolean insurance = hideBody.contains("containsVideoSurface(v, 0)")
                    && hideBody.contains("tooBigToHide(v)");
            String stBody = slice(dy306, "private static boolean safeToHide",
                    "private static void hideSafe(");
            boolean noNewBranch = count(stBody, "(\"caption\"|\"reddot\")") == 0;
            check(dramaOut && insurance && noNewBranch,
                    "T-48-P3c !isDrama 门控 + hideSmallSafe 两通用保险 + safeToHide 零新增分支", "");
        }
        {
            // SW-a Hook 侧三开关全链路
            boolean decl = countLiteral(cfg306, "public static volatile boolean enableBottomCaption = false;") == 1
                    && countLiteral(cfg306, "public static volatile boolean enableRightActions = false;") == 1
                    && countLiteral(cfg306, "public static volatile boolean enableRedDot = false;") == 1;
            boolean cases = cfg306.contains("case \"enable_bottom_caption\"")
                    && cfg306.contains("case \"enable_right_actions\"")
                    && cfg306.contains("case \"enable_red_dot\"");
            boolean apply = cfg306.contains("enableBottomCaption = bc;")
                    && cfg306.contains("enableRightActions = ra;")
                    && cfg306.contains("enableRedDot = rd;");
            check(decl && cases && apply, "T-48-SW-a Hook 侧三开关 字段+case+回写 全链路", "");
        }
        {
            // SW-b App 侧三开关全链路
            boolean state = act306.contains("val enableBottomCaption: Boolean = false")
                    && act306.contains("val enableRightActions: Boolean = false")
                    && act306.contains("val enableRedDot: Boolean = false");
            boolean saver = act306.contains("Bool(40)") && act306.contains("Bool(41)")
                    && act306.contains("Bool(42)");
            boolean conf = act306.contains("b(map[\"enable_bottom_caption\"])")
                    && act306.contains("b(map[\"enable_right_actions\"])")
                    && act306.contains("b(map[\"enable_red_dot\"])")
                    && act306.contains("lines[\"enable_bottom_caption\"]")
                    && act306.contains("lines[\"enable_right_actions\"]")
                    && act306.contains("lines[\"enable_red_dot\"]")
                    && act306.contains("putB(\"enable_bottom_caption\"")
                    && act306.contains("putB(\"enable_right_actions\"")
                    && act306.contains("putB(\"enable_red_dot\"");
            check(state && saver && conf, "T-48-SW-b App 侧 state+Saver+Bool(40/41/42)+AbConf load/save/diff 全链路", "");
        }
        {
            // SW-snap 纯加性：旧索引 0-39 与旧默认值零改
            String restoreBody = slice(act306, "restore = {", "object AbConf {");
            boolean oldIdx = restoreBody.contains("Bool(39, false)")
                    && restoreBody.contains("Bool(20, true)")
                    && restoreBody.contains("Bool(26, true)")
                    && restoreBody.contains("Bool(28)") && restoreBody.contains("Bool(37)");
            boolean notClobber = countLiteral(restoreBody, "Bool(39)") == 0; // 旧末位仍 39，非 40
            boolean newIdx = restoreBody.contains("Bool(40)") && restoreBody.contains("Bool(41)")
                    && restoreBody.contains("Bool(42)");
            String loadBody = slice(act306, "fun load(): AbState", "fun save(s: AbState)");
            boolean oldDefault = loadBody.contains("b(map[\"enable_live\"], true)")
                    && loadBody.contains("enableBiliTop = false")
                    && loadBody.contains("enableBiliBottom = false")
                    && loadBody.contains("enableBiliCorner = false");
            boolean oldKeys = countLiteral(act306, "enable_ks_right") >= 1
                    && countLiteral(act306, "enable_bili_right") >= 1;
            check(oldIdx && notClobber && newIdx && oldDefault && oldKeys,
                    "T-48-SW-snap 旧索引 0-39/旧默认值/旧键名零改，仅追加 Bool(40/41/42)", "");
        }
        {
            // SW-c UI 入口 + 红果「待数据」置灰
            boolean copies = countLiteral(act306, "copy(enableBottomCaption =") >= 1
                    && countLiteral(act306, "copy(enableRightActions =") >= 1
                    && countLiteral(act306, "copy(enableRedDot =") >= 1;
            boolean dramaDisabled = act306.contains("disabledLabel = \"待数据\"");
            check(copies && dramaDisabled, "T-48-SW-c 三开关 UI copy() 入口 + 红果待数据置灰", "");
        }
        {
            // GUARD 越界：通用门槛逐字 + 视频否决顺序 + 零改方法体锚点
            String guardSt = stripComments(slice(dy306, "private static boolean safeToHide",
                    "private static void hideSafe("));
            boolean gates = countLiteral(guardSt, "rw >= 0.30f") == 1
                    && countLiteral(guardSt, "rh <= 0.02f || rh > 0.50f") == 1
                    && countLiteral(guardSt, "return rh <= 0.30f || rw <= 0.22f;") == 1;
            boolean order = guardSt.indexOf("containsVideoSurface(v, 0)") >= 0
                    && guardSt.indexOf("AvatarWithPendantView") > guardSt.indexOf("containsVideoSurface(v, 0)")
                    && guardSt.indexOf("matchesActionButtonId(v)") > guardSt.indexOf("AvatarWithPendantView");
            String ci = stripComments(slice(dy306, "private static void collectRightIcons(",
                    "/** 单个「右缘小按钮」的准入"));
            String ri = stripComments(slice(dy306, "private static boolean rightIconOk(",
                    "/** 竖向摊开校验"));
            String tbg = stripComments(slice(dy306, "private static boolean tooBigToHide(",
                    "private static void updateShiftPhase()"));
            String tp = stripComments(slice(dy306, "private static void transparentDeep(",
                    "private static void showAll()"));
            boolean untouched = ci.contains("rightIconOk(c, w, h)") && ci.contains("stackedVertically(hits, h)")
                    && ri.contains("getHeight()")
                    && tbg.contains("v.getWidth() > sw * 0.45f && v.getHeight() > sh * 0.45f")
                    && tp.contains("v.setVisibility(View.INVISIBLE)") && tp.contains("v.setAlpha(0f)");
            boolean authorBranch = guardSt.contains("AvatarWithPendantView") && guardSt.contains("FollowButtonView");
            check(gates && order && untouched && authorBranch,
                    "T-48-GUARD 三门槛逐字 + 视频否决在作者栏/P1之前 + 零改方法体锚点", "");
        }
        {
            // UPG 升级回归（源码级）：新键默认 false，老配置缺键 → 三功能全关
            boolean cfgDefault = cfg306.contains("enableBottomCaption = false")
                    && cfg306.contains("enableRightActions = false")
                    && cfg306.contains("enableRedDot = false");
            String loadBody = slice(act306, "fun load(): AbState", "fun save(s: AbState)");
            boolean loadDefault = countLiteral(loadBody, "b(map[\"enable_bottom_caption\"])") == 1
                    && countLiteral(loadBody, "b(map[\"enable_right_actions\"])") == 1
                    && countLiteral(loadBody, "b(map[\"enable_red_dot\"])") == 1;
            check(cfgDefault && loadDefault,
                    "T-48-UPG 升级回归：三新键默认 false，老配置缺键三功能全关", "");
        }
        {
            // VER 版本锚点：七处旧断言已同步 78/3.0.18（此前 71/3.0.11、70/3.0.10、69/3.0.9 依次同步，精确相等）
            Matcher cm = Pattern.compile("versionCode\\s+(\\d+)").matcher(gradle);
            Matcher nm = Pattern.compile("versionName\\s+\"([^\"]+)\"").matcher(gradle);
            boolean ver = cm.find() && nm.find()
                    && Integer.parseInt(cm.group(1)) == 105 && "3.2.6".equals(nm.group(1));
            check(ver, "T-48-VER 版本锚点 99/3.2（T-42b~T-47c 六处已同步）", "");
        }

        // ---------- T-49 v3.0.7 定向隐藏通道 THH 测试锁（安全命门确定性守卫） ----------
        // 所有数量 / 负向断言一律 count(regex)（Pattern.find 真计数），
        // 禁止 countLiteral("A|B|C") 空断言——v3.0.6 R2 踩过。
        System.out.println("== T-49 v3.0.7 定向隐藏通道 THH 测试锁 ==");
        String dy307 = read(dir.resolve("DouyinHook.java"));
        String cfg307 = read(dir.resolve("Config.java"));
        String act307 = read(dir.resolve("MainActivity.kt"));
        {
            // A1 新增 7 个 helper：定义各恰 1 处，count(regex) 真计数
            String[] defs = {
                    "private static boolean isCountLike\\(",
                    "private static boolean isOwnedElsewhere\\(",
                    "private static boolean targetTextHit\\(",
                    "private static boolean targetTextAll\\(",
                    "private static boolean targetGeoOk\\(",
                    "private static View targetLeafUp\\(",
                    "private static void hideTargeted\\("
            };
            boolean ok = true;
            for (String d : defs) {
                ok = ok && count(dy307, d) == 1;
            }
            check(ok, "T-49-A1 新增 7 个 helper 定义齐备（各恰 1 处）", "");
        }
        {
            // A2 唯一入口 + 4 collector + dump 探针
            String[] defs = {
                    "private static void updateTargetedHide\\(",
                    "private static void collectKsLiveTargets\\(",
                    "private static void collectDyLiveTargets\\(",
                    "private static void collectKsFeedTargets\\(",
                    "private static void collectDramaTargets\\(",
                    "private static void dumpThhTargets\\("
            };
            boolean ok = true;
            for (String d : defs) {
                ok = ok && count(dy307, d) == 1;
            }
            check(ok, "T-49-A2 唯一入口 + 4 collector + dumpThhTargets 定义齐备", "");
        }
        {
            // A3 四处调用接入（rescan 传 root 1 处 + tick/onResume/onGlobalLayout 传 null 3 处）
            boolean ok = count(dy307, "updateTargetedHide\\(root\\)") == 1
                    && count(dy307, "updateTargetedHide\\(null\\)") == 3;
            check(ok, "T-49-A3 updateTargetedHide 四处调用接入（root×1 + null×3）", "");
        }
        {
            // B1 数据命门负向：纯数字/带斜杠数字拒绝逻辑在，且 4 collector 全否决、无放行分支
            String cb = stripComments(slice(dy307,
                    "private static boolean isCountLike(CharSequence cs)",
                    "private static boolean targetTextHit("));
            String patternConst = stripComments(slice(dy307, "THH_COUNT_PATTERN =", ";"));
            boolean reject = count(cb, "THH_COUNT_PATTERN\\.matcher\\(s\\)\\.matches\\(\\)") == 1
                    && count(cb, "!s\\.isEmpty\\(\\) && s\\.length\\(\\) <= 24") == 1
                    && count(patternConst, "\\[0-9\\]\\+\\(\\[\\./\\]\\[0-9\\]\\+\\)\\*") == 1;
            boolean noPass = count(dy307, "if\\s*\\(\\s*isCountLike\\s*\\(") == 0
                    && count(dy307, "!isCountLike\\(t\\)") == 4
                    && count(dy307, "isCountLike\\(") == 5;
            check(reject && noPass,
                    "T-49-B1 数字命门：纯数字/斜杠数字拒绝逻辑 + 4 collector 全否决 + 零放行分支", "");
        }
        {
            // B2 fail-closed 未削弱：isBadgeForbidden 的 catch→return true 仍在
            String fb = slice(dy307, "private static boolean isBadgeForbidden", "private static boolean isBadgeSafe");
            int iCatch = fb.indexOf("catch (Throwable");
            boolean failClosed = iCatch >= 0 && count(fb.substring(iCatch), "return\\s+true\\s*;") == 1;
            check(failClosed, "T-49-B2 isBadgeForbidden catch→return true fail-closed 未削弱", "");
        }
        {
            // C1 施加路径：容器过 safeToHide，挡下走 showDeep，统一走 hideSmallSafe 出口
            String ht = slice(dy307, "private static void hideTargeted(", "private static void applyTargetedList(");
            boolean ok = count(ht, "container\\s*&&\\s*!safeToHide\\(v,\\s*what\\)") == 1
                    && count(ht, "showDeep\\(v\\)") == 1
                    && count(ht, "hideSmallSafe\\(v,\\s*what\\)") == 1
                    && count(dy307, "hideTargeted\\(v,\\s*what,\\s*container\\)") == 1;
            check(ok, "T-49-C1 施加路径：容器过 safeToHide + showDeep 恢复 + 统一 hideSmallSafe 出口", "");
        }
        {
            // C2 视频保险（变异实证守卫）：hideSmallSafe 三保险 + targetLeafUp 视频否决真计数
            String hsBody307 = slice(dy307, "private static void hideSmallSafe(", "private static void showAll()");
            String tl = slice(dy307, "private static View targetLeafUp(", "private static boolean isOwnedElsewhere(");
            boolean ok = count(hsBody307, "containsVideoSurface\\(v,\\s*0\\)") == 1
                    && count(hsBody307, "tooBigToHide\\(v\\)") == 1
                    && count(hsBody307, "isOwnOverlay\\(v\\)") == 1
                    && count(tl, "containsVideoSurface\\(c,\\s*0\\)") == 1;
            check(ok, "T-49-C2 视频保险：hideSmallSafe 三保险 + targetLeafUp 视频否决真计数", "");
        }
        {
            // D1 纯加性：safeToHide 函数体零 target/直播/红果分支（count(regex)==0）
            String stBody307 = stripComments(slice(dy307,
                    "private static boolean safeToHide(View v, String what)", "private static void hideSafe("));
            check(count(stBody307, "(?i)(target|kslive|dylive|ksfeed|dramainfo)") == 0,
                    "T-49-D1 safeToHide 体内 target/kslive/dylive/ksfeed/dramainfo 零命中", "");
        }
        {
            // D2 评分函数与基线逐字一致：count(".+") 锁行数 + SHA-256 锁字节
            String[][] f = {
                    {"scoreRight", "private static int scoreRight(ViewGroup g)",
                            "private static void collectRightEdgeNode(", "59",
                            "4a252baa9abe5eb0da1576173d04bc273ddc7634d1c40acc79ed52439d85082a"},
                    {"scoreTop", "private static int scoreTop(ViewGroup g)",
                            // v3.0.36 重锚：collectRightIconsWide 插在 collectRightIcons 之前，
                            // 旧 to 锚点会把新函数圈进区间，故改用新函数名收口。
                            "private static void collectRightIconsWide(", "25",
                            "bf9c3bbfd1af4d1325408c4aaedc198a21801aa1b5a8d71f582c75ac3b8a7bdc"},
                    {"scoreBottom", "private static int scoreBottom(ViewGroup g)",
                            "private static void collectLiveTopNode(", "36",
                            "8e018e2df7bbbd80c77230f91c614779b07a6ead3c8762b618ea981c6daec994"}
            };
            boolean ok = true;
            for (String[] x : f) {
                String body = stripComments(slice(dy307, x[1], x[2]));
                ok = ok && count(body, ".+") == Integer.parseInt(x[3]) && sha256(body).equals(x[4]);
            }
            check(ok, "T-49-D2 scoreRight/Top/Bottom 函数体与基线逐字一致（行数+SHA-256）", "");
        }
        {
            // D3 v3.0.4 作者栏分支原样（Avatar + Follow + 单维 50% 三行）
            String ab = stripComments(slice(dy307, "v3.0.4 P1 插入点 ", "v3.0.4 P1 插入点结束"));
            boolean ok = count(ab, ".+") == 18
                    && sha256(ab).equals("75be4a72e6cecc24c46f264a0792bd10813d0e5d715f81667a4ed3320aa9a218")
                    && count(ab, "AvatarWithPendantView") == 1
                    && count(ab, "FollowButtonView") == 1
                    && count(ab, "0\\.50f") == 2;
            check(ok, "T-49-D3 v3.0.4 作者栏分支（Avatar+Follow+单维50%）逐字原样", "");
        }
        {
            // D4 livePaused 只读：赋值次数与基线 3 处一致，THH 新增区零写
            String thh = dy307.substring(dy307.indexOf("private static boolean isCountLike(CharSequence cs)"));
            boolean ok = count(dy307, "livePaused\\s*=") == 3 && count(thh, "livePaused\\s*=") == 0;
            check(ok, "T-49-D4 livePaused 赋值 3 处与基线一致 + THH 区零写", "");
        }
        {
            // D5 v2.9.4 hideLiveCandidate 与基线逐字一致（design §7.3 附加）
            String hlc = stripComments(slice(dy307,
                    "private static boolean hideLiveCandidate(View v, String what)", "private static void hideSmallSafe("));
            // v3.0.36 重锁：transparentDeep 新增 touched.add(v) 一行（保底台账）
            check(count(hlc, ".+") == 46
                            && sha256(hlc).equals("6cefc4ca57e8ce6e68f7fbdee84c23703c0566f26ac43088bcceefe3a861a970"),
                    "T-49-D5 hideLiveCandidate 函数体与基线逐字一致", "");
        }
        {
            // E1 Config 四开关：4 boolean 字段 + 4 键名 case + 4 行回写
            boolean ok = count(cfg307,
                    "public static volatile boolean enable(KsLiveHits|DyLiveHits|KsFeedHits|DramaInfoHits) = false;") == 4
                    && count(cfg307, "case \"enable_(ks_live_hits|dy_live_hits|ks_feed_hits|drama_info_hits)\"") == 4
                    && count(cfg307, "enable(KsLiveHits|DyLiveHits|KsFeedHits|DramaInfoHits) = (ksl|dyl|ksf|dri);") == 4;
            check(ok, "T-49-E1 Config 4 boolean 字段 + 4 键名 case + 4 回写", "");
        }
        {
            // E2 MainActivity 四开关全链路：Bool(43..46) + 4 键 ×3 处 + state/copy
            boolean idx = count(act307, "Bool\\(43\\)") == 1 && count(act307, "Bool\\(44\\)") == 1
                    && count(act307, "Bool\\(45\\)") == 1 && count(act307, "Bool\\(46\\)") == 1;
            boolean keys = count(act307,
                    "\"enable_(ks_live_hits|dy_live_hits|ks_feed_hits|drama_info_hits)\"") == 12;
            boolean state = count(act307,
                    "val enable(KsLiveHits|DyLiveHits|KsFeedHits|DramaInfoHits): Boolean = false") == 4;
            boolean copy = count(act307,
                    "copy\\(enable(KsLiveHits|DyLiveHits|KsFeedHits|DramaInfoHits) =") == 4;
            check(idx && keys && state && copy,
                    "T-49-E2 MainActivity Bool(43..46) + 4 键 + state/copy 全链路", "");
        }
        {
            // E3 旧 Bool(0..42) 零改：restore 旧段（到 Bool(43) 之前）逐字 + SHA-256
            String oldRestore = slice(act307, "restore = {", "enableKsLiveHits = Bool(43)");
            check(count(oldRestore, ".+") == 66
                            && sha256(oldRestore).equals("2b36244db567cb845391a2d07af5a4bc8702823eb6f7478a2add947821f12438"),
                    "T-49-E3 旧 Bool(0..42) 零改（restore 旧段逐字，v3.0.16 仅 Bool(38) 授权改 true）", "");
        }
        {
            // F 变异实证守卫：评审注入「放行数字」→ B1 FAIL；「跳过 containsVideoSurface」→ C2 FAIL。
            // 两条守卫都是 count(regex)，非 countLiteral 空断言，变异必然触发。
            String hsF = slice(dy307, "private static void hideSmallSafe(", "private static void showAll()");
            boolean guards = count(dy307, "if\\s*\\(\\s*isCountLike\\s*\\(") == 0
                    && count(hsF, "containsVideoSurface\\(v,\\s*0\\)") == 1;
            check(guards, "T-49-F 变异实证守卫：数字放行/跳过视频否决两变异均触发 FAIL", "");
        }

        // ---------- T-50-W v3.0.8 门控接线 / PII 静态守卫（文本审计，非行为） ----------
        System.out.println();
        System.out.println("== T-50-W v3.0.8 THH 门控接线与 PII 守卫 ==");
        {
            // W1 feed 接线：feed 调用恰 1 处，且返回值被 return（防「调了却丢弃」的假接线）；
            //    DY_LIVE case 内不得再出现 && livePaused 直读门控。
            String dyLiveCase = slice(dy307, "case SLOT_DY_LIVE: {", "case SLOT_KS_FEED:");
            boolean feedOnce = count(dy307, "thhLiveGate\\.feed\\(") == 1;
            boolean feedReturned = count(dyLiveCase, "return nowStable;") == 1
                    && count(dyLiveCase, "thhLiveGate\\.feed\\(") == 1;
            boolean noDirectGate = count(dyLiveCase, "&&\\s*livePaused") == 0;
            check(feedOnce && feedReturned && noDirectGate,
                    "T-50-W1 feed 恰 1 处 + 返回值被 return + 无 && livePaused 直读门控", "");
        }
        {
            // W2 livePaused 零写：全文 3 处赋值与基线一致，isCountLike 之后的 THH 新增区零写。
            String thhSlice = dy307.substring(dy307.indexOf("private static boolean isCountLike(CharSequence cs)"));
            boolean ok = count(dy307, "livePaused\\s*=") == 3 && count(thhSlice, "livePaused\\s*=") == 0;
            check(ok, "T-50-W2 livePaused 赋值 3 处 + 新增 THH 区零写（双锁）", "");
        }
        {
            // W3 D5 PII 守卫（转写设计 §5.3 五条）：
            //  ① dumpLiveUiNode 体内零 View 修改；
            //  ② readMeta( 恰 0（不得回退会拼原文的旧方法；readMetaLite 不算）；
            //  ③ txt= / desc= 拼接 0；
            //  ④ readMetaLite 存在且被 dumpLiveUiNode 调用，其体内无文本 getter / txt= / desc=；
            //  ⑤ LIVE_UI_KEYS 只被 dump 引用无 out.add，anchor 只经 safeDumpText 输出。
            // 先去注释：javadoc 里写了「不得出现 getText/txt=」等禁用词，不去注释会假 FAIL。
            String dumpNode = stripComments(slice(dy307, "private static void dumpLiveUiNode(", "private static String readMetaLite("));
            String metaLite = stripComments(slice(dy307, "private static String readMetaLite(", "private static String safeDumpText("));
            String safeText = stripComments(slice(dy307, "private static String safeDumpText(", "/** v3.0.7：计数签名"));
            boolean noViewEdit = count(dumpNode,
                    "setAlpha|setVisibility|setText|setLayoutParams|setTranslation|addView|removeView") == 0;
            boolean noOldReadMeta = count(dumpNode, "readMeta\\(") == 0;
            boolean noTextJoin = count(dumpNode, "txt=|desc=|shortText\\(|shortDesc\\(") == 0;
            boolean liteExists = count(dy307, "private static String readMetaLite\\(") == 1
                    && count(dumpNode, "readMetaLite\\(") >= 1;
            boolean liteClean = count(metaLite,
                    "getText|getContentDescription|shortText|shortDesc|txt=|desc=") == 0;
            boolean anchorSafe = count(safeText, "LIVE_UI_KEYS") >= 1
                    && count(safeText, "<omitted>") == 1
                    && count(dy307, "out\\.add") >= 1; // 既有探针的 out.add 仍在，但 dump 片段内不新增
            boolean dumpNoOutAdd = count(dumpNode, "out\\.add") == 0
                    && count(dumpNode, "anchor=") >= 1
                    && count(dumpNode, "safeDumpText\\(") >= 1;
            check(noViewEdit && noOldReadMeta && noTextJoin && liteExists && liteClean
                            && anchorSafe && dumpNoOutAdd,
                    "T-50-W3 dumpLiveUiNode 零改写/无 readMeta(/无 txt=desc=/readMetaLite 干净/anchor 白名单", "");
        }
        {
            // W4 门控常量锁定（防靶场跑的类与真机不同源）：门控常量在 ThhLiveGate 内、Hook 只实例化不改。
            Path gateSrc = dir.resolve("ThhLiveGate.java");
            boolean gateClassExists = Files.exists(gateSrc);
            String gate = gateSrc.toFile().exists() ? read(gateSrc) : "";
            boolean constants = count(gate, "ENTER_FRAMES\\s*=\\s*2") == 1
                    && count(gate, "EXIT_FRAMES\\s*=\\s*3") == 1
                    && count(gate, "SAMPLE_WINDOW_MS\\s*=\\s*500L") == 1
                    && count(gate, "GAP_RESET_MS\\s*=\\s*4000L") == 1;
            check(gateClassExists && constants && count(dy307, "new ThhLiveGate\\(\\)") == 1,
                    "T-50-W4 ThhLiveGate 常量锁定 2/3/500/4000 + Hook 恰实例化 1 次", "");
        }

        // ---------- T-51 v3.0.9 卡片精简 / 轮询合并 / 导航栏微移值回退（真守卫） ----------
        // 数量断言一律 count(regex)（Pattern.find 真计数），禁止 countLiteral("A|B") 空断言
        // —— v3.0.6 R2 教训。变异实证只在内存副本上做，不落盘、不改源码。
        System.out.println();
        System.out.println("== T-51 v3.0.9 卡片精简 / 轮询合并 / 导航栏微移值回退 ==");
        String act309 = read(dir.resolve("MainActivity.kt"));
        {
            // A1 开机自启卡：裸 token 计数 == 0，同时覆盖「定义」与「调用」两侧。
            int card = count(act309, "BootStatusCard");
            check(card == 0,
                    "T-51-A1 MainActivity.kt 无 BootStatusCard 定义与调用（count==0）",
                    "count=" + card);
        }
        {
            // A2 5s 轮询点：合并后 ≤ 2（首页注入状态卡 + 红果二级页各一处）。
            int polls = count(act309, "delay\\s*\\(\\s*5_000\\s*\\)");
            check(polls <= 2,
                    "T-51-A2 delay(5_000) 轮询点计数 ≤ 2",
                    "count=" + polls);
        }
        {
            // A3 navMicroSec 固定 45（原防烧屏节奏）且不被 rememberSaveable 托管：
            //    只锁该声明行本身——subPage/state 等别处合法使用 rememberSaveable，
            //    全文零命中会假 FAIL，必须把 rememberSaveable 与 navMicroSec 绑定判定。
            int fixed45 = count(act309, "(?m)^[ \\t]*val[ \\t]+navMicroSec[ \\t]*=[ \\t]*45[ \\t]*$");
            int boundSaveable = count(act309,
                    "navMicroSec[^\\n]*rememberSaveable|rememberSaveable[^\\n]*navMicroSec");
            check(fixed45 == 1 && boundSaveable == 0,
                    "T-51-A3 navMicroSec 固定值 45（原防烧屏节奏）且声明不绑 rememberSaveable",
                    "fixed45=" + fixed45 + " boundSaveable=" + boundSaveable);
        }
        {
            // A4 onSelfCheck 正向守卫：名单卡按钮依赖，删自启卡时不得连带删除。
            int decl = count(act309, "onSelfCheck[ \\t]*:[ \\t]*\\(\\)[ \\t]*->[ \\t]*Unit");
            int used = count(act309, "onClick[ \\t]*=[ \\t]*onSelfCheck");
            check(decl >= 1 && used >= 1,
                    "T-51-A4 onSelfCheck 参数与按钮绑定仍在（名单卡依赖，防误删）",
                    "decl=" + decl + " onClick=" + used);
        }
        {
            // A5 变异实证（内存变异，不落盘）：
            //    变异①恢复 BootStatusCard 定义 → A1 的 count>0，断言必 FAIL；
            //    变异②navMicroSec 固定 45 改成其他值（如 200）→ A3 的 fixed45==0，断言必 FAIL。
            //    本项证明 A1/A3 是真守卫而非空断言。
            String mutantCard = act309 + "\nfun BootStatusCard() {}\n";
            String mutantNav = act309.replace("val navMicroSec = 45", "val navMicroSec = 200");
            boolean cardCaught = count(mutantCard, "BootStatusCard") > 0;
            boolean navCaught = count(mutantNav,
                    "(?m)^[ \\t]*val[ \\t]+navMicroSec[ \\t]*=[ \\t]*45[ \\t]*$") == 0;
            check(cardCaught && navCaught,
                    "T-51-M 变异实证：恢复 BootStatusCard→A1 FAIL；navMicroSec 45 改成 200→A3 FAIL",
                    "cardCaught=" + cardCaught + " navCaught=" + navCaught);
        }

        // ---------- T-52 v3.0.10 功耗优化（P1 相位 tick 常量化 / P2 前端轮询 / P3 零改 / 变异实证） ----------
        // 公共约定（照抄 T-51 教训）：数量断言一律 count(regex)（Pattern.find 真计数），
        // 禁止 countLiteral("A|B") 空断言 —— v3.0.6 R2 教训；变异实证只在内存副本上做，不落盘、不污染真源。
        // A5 变异 β 用字面 String.replace，不用未转义的 replaceFirst 正则（评审 design-review 问题 1）。
        System.out.println();
        System.out.println("== T-52 v3.0.10 相位 tick 常量化 / 轮询降频 / 越界零改 ==");
        String ctrl310 = read(dir.resolve("Controller.java"));
        String act310 = read(dir.resolve("MainActivity.kt"));
        String dy310 = read(dir.resolve("DouyinHook.java"));
        String cfg310 = read(dir.resolve("Config.java"));
        String a1DeclRx = "private[ \\t]+static[ \\t]+final[ \\t]+long[ \\t]+MIN_TICK_MS[ \\t]*=[ \\t]*3000L[ \\t]*;";
        String a3OldRx = "delay\\s*\\(\\s*5_000\\s*\\)";
        String a3UseRx = "delay\\(STATUS_REFRESH_MS\\)";

        // A1 · P1 常量声明 + 唯一接线 + 旧值清零（Math.min(2000L 必须归零）
        {
            int decl = count(ctrl310, a1DeclRx);
            int used = count(ctrl310,
                    "scheduleCycle\\(Math\\.min\\(MIN_TICK_MS,[ \\t]*Math\\.max\\(100L, remain\\)\\)\\)");
            int old2000 = count(ctrl310, "Math\\.min\\(2000L");
            check(decl == 1 && used == 1 && old2000 == 0,
                    "T-52-A1 MIN_TICK_MS 声明==1 / 接线==1 / 旧 Math.min(2000L)==0",
                    "decl=" + decl + " used=" + used + " old2000=" + old2000);
        }
        // A1-d · 全局替换误伤守卫：异常兜底与直播暂停两处 scheduleCycle(2000) 必须恰好还在
        {
            int pause2000 = count(ctrl310, "scheduleCycle\\(2000\\)");
            check(pause2000 == 2,
                    "T-52-A1-d 异常兜底/直播暂停两处 scheduleCycle(2000) 未误改（count==2）",
                    "count=" + pause2000);
        }

        // A2 · 越界七段函数体 raw slice：锚点 hits==1 + 行数 + sha256 逐字锁（基线见 design-backend §4.2）
        {
            String[] a2From = {
                    "long hideMs = Math.max(1, Config.hideSec) * 1000L;",
                    "if (shouldHide) {",
                    "if (!intervene || livePause) {",
                    "if (Config.enableShift) {",
                    "private static void updateOverlay(String top, boolean show) {",
                    "private static void applyHide() {",
                    "private static void applyShow() {"
            };
            String[] a2To = {
                    "if (shouldHide) {",
                    "// v2.8.3：应用内区域覆盖层",
                    "// 统一周期相位",
                    "// 调度：",
                    "/** 上一次应用到 PixelShift",
                    "private static void applyShow() {",
                    "/**\n     * 暂停原因"
            };
            // v3.0.11 重锁：段[5] applyHide→applyShow 区间含 P-1 heads-up 修复
            // （hideStatusKeepNotify 取消整窗 alpha=0 降级 + 新增 hideKnownStaticIconsOnly + dimAlong 祖先守卫），
            // 属授权改动（T-54 已单独验收全绿）；经 git diff 逐行核对，其余 6 段逐字未改仍 MATCH 基线。
            // v3.0.14 重锁：段[3] enableShift 区间含偏移推进修复（hidden 但窗口可见时仍 tick），20→23 行。
            // v3.0.17 重锁：段[5] applyHide→applyShow 区间含状态栏几何分区透明（dimByZone/dimLeaves），157→220 行。
            // v3.0.30 重锁：段[0][1] 区间含状态栏三班倒相位计算与接线（5→13 / 16→14 行），
            //   段[5] 含 applyStatusRound/applyShowNavOnly 新增（434→447 行），段[6] applyShow 复位相位（41→46 行）。
            // v3.0.31 重锁：段[5] 含分区能力探测 probeLeaves 与降级（447→450 行）。
            // v3.0.35 重锁：段[5] 含 dimFallbackChain 薄命中补遮（450→457 行）。
            // v3.2.1 重锁：段[5] 含第 4 级整层兜底 dimWholeBarIfSafe/hasLiveHeadsUp
            //   与「无子 ViewGroup 当叶子」四处改动（473→545 行），属 Pixel 修复授权改动。
            // v3.2.6 重锁：段[2] 暂停分支去掉三班倒判据（25→23 行）；段[5] 移除
            //   applyStatusRound/probeLeaves/applyShowNavOnly（545→529 行）；段[6] applyShow
            //   去掉相位复位（46→41 行）。均为「删除三班倒」授权改动。
            int[] a2Lines = {9, 12, 23, 23, 11, 529, 41};
            String[] a2Sha = {
                    "f317657c4ab99a9805e8cc9165ceb5c51afbc181bce1043c9fa3c773e8bce124",
                    "d9bc8a9f13ec04aa47d3d9b311e84c69867fdfb503bd1b94ca1f80fd4dc3e482",
                    "3effc07f0ebca68663dbd95766e0a3d11baf1d57b18efd064becaf1311f1fd7f",
                    "a8a3c21d8ec069e7d778a2e3e18682c9b4a68f25945bdb80452ebf823f138d35",
                    "31f7e195cb32c1bf85b3631c5e2ac43dc8d795b5cc2e48e1137371646ba36020",
                    "5e3ade580facaa3693b7d9fb594dc56db8dc3477cc0ed0a2e98b7e0a2be74a7e",
                    "29a926668a181c748745072486318738514980532d0c6d971a90b77e5e8363a9"
            };
            StringBuilder drift = new StringBuilder();
            for (int i = 0; i < a2From.length; i++) {
                int hits = count(ctrl310, Pattern.quote(a2From[i]));
                String body = slice(ctrl310, a2From[i], a2To[i]);
                int lines = count(body, ".+");
                String h = sha256(body);
                if (hits != 1 || lines != a2Lines[i] || !h.equals(a2Sha[i])) {
                    drift.append("[").append(i).append(":hits=").append(hits)
                            .append(",lines=").append(lines).append(",sha=").append(h).append("]");
                }
            }
            check(drift.length() == 0,
                    "T-52-A2 越界七段函数体逐字未改（锚点唯一+行数+sha256）",
                    drift.length() == 0 ? "7/7 段 MATCH" : drift.toString());
        }

        // A3 · P2 前端契约：先写好真守卫；若此刻前端尚未落地则 SKIP 并如实标注，
        // 独立复验阶段前端改完后统一重跑，本分支不再进入。
        {
            int old5k = count(act310, a3OldRx);
            int decl = count(act310, "private[ \\t]+const[ \\t]+val[ \\t]+STATUS_REFRESH_MS[ \\t]*=[ \\t]*15_?000L?");
            int used = count(act310, a3UseRx);
            int now = count(act310, "now[ \\t]*=[ \\t]*System\\.currentTimeMillis\\(\\)");
            int injKind = count(act310, "ConfigProvider\\.lastInject\\(context, kind\\)");
            int injDrama = count(act310, "ConfigProvider\\.lastInject\\(context, \"drama\"\\)");
            int fresh = count(act310, "24L \\* 3600_000L");
            String detail = "old5k=" + old5k + " decl=" + decl + " used=" + used + " now=" + now
                    + " injKind=" + injKind + " injDrama=" + injDrama + " fresh=" + fresh;
            boolean frontendPending = decl == 0 && used == 0 && old5k > 0;
            if (frontendPending) {
                System.out.println("  SKIP  T-52-A3 前端轮询契约（MainActivity.kt 尚未改，待前端完成后独立复验重跑）  — " + detail);
            } else {
            // v3.0.11 锚点同步：P6 UI 简洁化新增顶层 recentlyInjected()（design-ui §MechanismNote/FeatureSummaryBar
            // 复用点，调用见 MainActivity L2431），再引一次 lastInject(context, kind) 与 24L*3600_000L；
            // 判定口径（24h freshness / 只读 prefs）零改，injDrama 仍恰 1。经 git diff 确认属授权改动，非改坏。
            check(old5k == 0 && decl == 1 && used == 2 && now == 2
                            && injKind == 2 && injDrama == 0 && fresh == 2,
                    "T-52-A3 前端 STATUS_REFRESH_MS=15000L / 两处引用 / 轮询保活 / 判定口径零改(P6 复用点 injKind=2/fresh=2；v3.0.23 红果页统一走 recentlyInjected)", detail);
            }
        }

        // A4 · P3 三处零改：DouyinHook【归一化重算】+ fetchInterval 片段哈希/三档字面 + navMicroSec
        //   v3.0.11：DouyinHook 本轮有 P0a/P0b 授权改动，整文件哈希必然变化。按 design-backend §9.1/§12，
        //   禁用整文件哈希，改为「剥离本轮 6 类授权改动后重算 == 基线」——既守住零越界，又不误伤授权改动。
        //   v3.0.14：新增悬浮球「贴底收起」授权改动（字段/ensureBall/onTouch/removeBall），基线已重锁。
        {
            String dyNorm = dy310;
            // 1) 词表：新注释+新数组 -> 旧数组
            dyNorm = dyNorm.replace(
                    "    // v3.0.11 P0a：全屏浏览入口是可点击功能控件，不作提示隐藏；由 isClickableControl 兜底。\n"
                            + "    private static final String[] KS_FEED_KEYS = {\"含AI生成内容\", \"内容由AI生成\"};",
                    "    private static final String[] KS_FEED_KEYS = {\"全屏观看\", \"含AI生成内容\", \"内容由AI生成\"};");
            // 2) 6 个 helper 块：从分隔注释到 targetLeafUp 的 javadoc 之前整块删除
            String helperStart = "    // ---------- v3.0.11 P0a/P0b：只读 UI 上下文探针（纯加性，不改任何既有函数/出口）----------\n";
            String helperEnd = "    /**\n     * v3.0.7：从命中叶子向上并进最内层静态父容器（<= maxUp 层）。";
            int hi = dyNorm.indexOf(helperStart);
            int hj = dyNorm.indexOf(helperEnd, hi + helperStart.length());
            if (hi >= 0 && hj > hi) {
                dyNorm = dyNorm.substring(0, hi) + dyNorm.substring(hj);
            }
            // 3) 4 个 collector 的 TextView 早退块
            dyNorm = dyNorm.replace(
                    "                // v3.0.11 P0：可点击功能入口 / 用户主页作品列表上下文跳过\n"
                            + "                if (isClickableControl(v) || isProfileListContext(v)) {\n"
                            + "                    return;\n"
                            + "                }\n", "");
            dyNorm = dyNorm.replace(
                    "                // v3.0.11 P0a：可点击功能入口跳过；P0b：用户主页作品列表上下文跳过\n"
                            + "                if (isClickableControl(v) || isProfileListContext(v)) {\n"
                            + "                    return;\n"
                            + "                }\n", "");
            dyNorm = dyNorm.replace(
                    "                // v3.0.11 P0b：用户主页/作品列表上下文跳过（红果剧集页通常返回 false）\n"
                            + "                if (isProfileListContext(v)) {\n"
                            + "                    return;\n"
                            + "                }\n", "");
            // 4) 结构锚两处 && !isProfileListContext(g) + 行尾注释 -> 还原闭合括号
            dyNorm = dyNorm.replace(
                    "\n                    && !isProfileListContext(g)) {   // v3.0.11 P0b：用户主页作品卡不隐藏",
                    ") {");
            String dyNormHash = sha256(dyNorm);
            String fetchBody = slice(cfg310, "private static long fetchInterval() {", "/** 屏幕是否亮着。");
            int fetchLines = count(fetchBody, ".+");
            String fetchHash = sha256(fetchBody);
            int t60 = count(fetchBody, "return 60000L;");
            int t2 = count(fetchBody, "return 2000L;");
            int t20 = count(fetchBody, "return 20000L;");
            int t90 = count(fetchBody, "now - lastChangeAt < 90000L");
            int fixed45 = count(act310, "(?m)^[ \\t]*val[ \\t]+navMicroSec[ \\t]*=[ \\t]*45[ \\t]*$");
            int boundSaveable = count(act310,
                    "navMicroSec[^\\n]*rememberSaveable|rememberSaveable[^\\n]*navMicroSec");
                                    check("8822c3ea9d941b677b97bc3914196d2d3fa98ba802618a3b31b8e627426d9e16".equals(dyNormHash)
                            && fetchLines == 11
                            && "e9395fb642fcb791b8af95ce676fb109cd7c16196ac7ff8d9242def575ba39a7".equals(fetchHash)
                            && t60 == 1 && t2 == 1 && t20 == 1 && t90 == 1
                            && fixed45 == 1 && boundSaveable == 0,
                    "T-52-A4 DouyinHook 归一化重算/fetchInterval/navMicroSec 三处零改（v3.0.28 扫描节流/注入时效授权新增，基线重锁）",
                    "dyNorm=" + dyNormHash.substring(0, 8) + " fetchLines=" + fetchLines
                            + " fetch=" + fetchHash.substring(0, 8)
                            + " 60000/2000/20000/90000=" + t60 + "/" + t2 + "/" + t20 + "/" + t90
                            + " fixed45=" + fixed45 + " bound=" + boundSaveable);
        }

        // A5 · 变异实证（内存副本，真守卫自证）
        //   α：把 MIN_TICK_MS = 3000L 改回 2000L → A1 声明正则必须归零（caughtA）。
        //   β：字面 replace("delay(STATUS_REFRESH_MS)", "delay(5_000)")（评审问题 1：不用未转义 replaceFirst）
        //      → 旧间隔计数>0 且新引用!=2，A3 必 FAIL（caughtB）。
        //   前端未落地时 β 以内存夹具作为「改后应有形态」，落地后直接用真 MainActivity.kt。
        {
            String mutA = ctrl310.replace("MIN_TICK_MS = 3000L", "MIN_TICK_MS = 2000L");
            boolean caughtA = count(mutA, a1DeclRx) == 0;
            boolean frontendDone = count(act310,
                    "private[ \\t]+const[ \\t]+val[ \\t]+STATUS_REFRESH_MS") > 0 || count(act310, a3OldRx) == 0;
            String betaBase = frontendDone ? act310
                    : ("private const val STATUS_REFRESH_MS = 15000L\n"
                       + "delay(STATUS_REFRESH_MS)\n"
                       + "now = System.currentTimeMillis()\n"
                       + "delay(STATUS_REFRESH_MS)\n"
                       + "now = System.currentTimeMillis()\n");
            boolean baseOk = count(betaBase, a3OldRx) == 0 && count(betaBase, a3UseRx) == 2;
            String mutB = betaBase.replace("delay(STATUS_REFRESH_MS)", "delay(5_000)");
            boolean caughtB = count(mutB, a3OldRx) > 0 || count(mutB, a3UseRx) != 2;
            check(caughtA && caughtB && baseOk,
                    "T-52-M 变异实证：α 3000→2000→A1 FAIL；β 字面 replace 回退 delay(5_000)→A3 FAIL",
                    "caughtA=" + caughtA + " caughtB=" + caughtB + " baseOk=" + baseOk
                            + " betaSrc=" + (frontendDone ? "real" : "fixture(frontend-pending)"));
        }

        // ---------- T-53 v3.0.11：P0 误伤修复 / P1 桌面图标 / P2 最近任务底座 / P5 磁贴（真守卫） ----------
        // 公共约定：数量断言一律 count(regex)（Pattern.find 真计数），禁止 \| 字面竖线空断言；
        // 变异实证只在内存副本上做，不落盘、不污染真源。
        System.out.println();
        System.out.println("== T-53 v3.0.11 P0/P1/P2/P5 真守卫 ==");
        String dy311 = read(dir.resolve("DouyinHook.java"));
        String tile311 = read(dir.resolve("AntiburnTile.java"));
        String vis311 = read(dir.resolve("AppVisibilityStore.java"));
        String act311 = read(dir.resolve("MainActivity.kt"));
        String man311 = read(dir.resolve("../../../../../AndroidManifest.xml"));
        String str311 = read(dir.resolve("../../../../../res/values/strings.xml"));
        String ic311 = read(dir.resolve("../../../../../res/drawable/ic_tile.xml"));
        String app311 = read(dir.resolve("AbApplication.java"));

        // A1 · KS_FEED 词表删"全屏观看"且保留真目标（限域到数组行）
        {
            String ksLine = slice(dy311, "String[] KS_FEED_KEYS =", ";");
            int banned = count(ksLine, "全屏观看");
            int keep = count(ksLine, "含AI生成内容");
            check(banned == 0 && keep == 1,
                    "T-53-A1 KS_FEED 词表删全屏观看且保留含AI生成内容",
                    "banned=" + banned + " keep=" + keep);
        }

        // A1b · DRAMA 词表仍含"全屏观看"（限域 slice，不数注释/L4195）
        {
            String drLine = slice(dy311, "String[] DRAMA_KEYS =", "DRAMA_NAV_KEYS");
            int dr = count(drLine, "全屏观看");
            check(dr >= 1,
                    "T-53-A1b DRAMA 词表保留全屏观看（限域旁证，DRAMA 零改）",
                    "dr=" + dr);
        }

        // A2 · 新判据 6 helper 存在且被调用
        {
            int defCtx = count(dy311, "private static boolean isProfileListContext\\(");
            int defCards = count(dy311, "private static int countContentCards\\(");
            int defDom = count(dy311, "private static boolean hasDominantVideoSurface\\(View v\\)");
            int uses = count(dy311, "isProfileListContext\\(");
            check(defCtx == 1 && defCards == 1 && defDom == 1 && uses >= 6,
                    "T-53-A2 新判据函数存在且被调用",
                    "defCtx=" + defCtx + " defCards=" + defCards + " defDom=" + defDom + " uses=" + uses);
        }

        // A2b · 4 处 targetLeafUp 并进常量零改 + leafUpLimit 已删
        {
            int tlu = count(dy311, "targetLeafUp\\(v, 2, 0\\.50f, 0\\.50f\\)");
            int leafLimit = count(dy311, "leafUpLimit");
            check(tlu == 4 && leafLimit == 0,
                    "T-53-A2b 4 处并进常量零改 + leafUpLimit 已删",
                    "targetLeafUp=" + tlu + " leafUpLimit=" + leafLimit);
        }

        // A2c · 几何取反不回归 + 有害兜底 isProfileContent 已删（复-B1）
        {
            int geoNeg = count(dy311, "!targetGeoOk\\(c, 0\\.45f, 0\\.45f\\)");
            int areaRule = count(dy311, "rw \\* rh > 0\\.50f");
            int profileContent = count(dy311, "isProfileContent");
            int anchorGuard = count(dy311, "!isProfileListContext\\(g\\)");
            check(geoNeg == 0 && areaRule == 1 && profileContent == 0 && anchorGuard == 2,
                    "T-53-A2c 几何取反不回归 + isProfileContent 已删 + 结构锚两处守卫",
                    "geoNeg=" + geoNeg + " areaRule=" + areaRule
                            + " profileContent=" + profileContent + " anchorGuard=" + anchorGuard);
        }

        // A3 · Manifest LAUNCHER 三作用域（本体 0 / alias 1 / queries 1）+ 全局 ==2 + DONT_KILL_APP
        {
            String mainBlk = slice(man311, "android:name=\".MainActivity\"", "</activity>");
            String aliasBlk = slice(man311, "<activity-alias", "</activity-alias>");
            String qBlk = slice(man311, "<queries>", "</queries>");
            int inMain = count(mainBlk, "LAUNCHER");
            int inAlias = count(aliasBlk, "LAUNCHER");
            int tgtAct = count(aliasBlk, "targetActivity=\".MainActivity\"");
            int inQ = count(qBlk, "LAUNCHER");
            int total = count(man311, "android.intent.category.LAUNCHER");
            int dk = count(vis311, "DONT_KILL_APP");
            // dk 真守卫：AppVisibilityStore 注释里另有 2 处说明性 DONT_KILL_APP（L33 javadoc / L60 行注），
            // 原始计数=3 含注释；真实调用仅 L61 一处。设计只要求"切换时带 DONT_KILL_APP"，锁实现处恰好 1 即可，
            // 故用 stripComments 后的计数守卫（若实现删掉 flag 则归 0 → FAIL；不被注释数量绑架）。
            int dkCode = count(stripComments(vis311), "DONT_KILL_APP");
            check(inMain == 0 && inAlias == 1 && tgtAct == 1 && inQ == 1 && total == 2 && dkCode == 1,
                    "T-53-A3 LAUNCHER 三作用域(0/1/1)+全局==2+DONT_KILL_APP(实现处恰好1)",
                    "main=" + inMain + " alias=" + inAlias + " target=" + tgtAct
                            + " queries=" + inQ + " total=" + total + " dk(raw/code)=" + dk + "/" + dkCode);
        }

        // A4 · 存储键存在且隔离（两条独立单字面 count，杜绝 \| 空断言）
        {
            int p1 = count(vis311, "antiburn_visibility");
            int p2 = count(vis311, "hide_launcher_icon");
            int p3 = count(vis311, "hide_recents");
            int cs = count(vis311, "ConfStore");
            int ab = count(vis311, "AbState");
            String visStrip = stripComments(vis311);
            int csS = count(visStrip, "ConfStore");
            int abS = count(visStrip, "AbState");
            check(p1 == 1 && p2 == 1 && p3 == 1 && cs == 0 && ab == 0 && csS == 0 && abS == 0,
                    "T-53-A4 存储键存在且隔离（不引用配置桥接类/状态类）",
                    "keys=" + p1 + "/" + p2 + "/" + p3 + " raw=" + cs + "/" + ab
                            + " strip=" + csS + "/" + abS);
        }

        // A5 · P2 选型正确（setExcludeFromRecents / 无 finishAndRemoveTask / onResume）
        //   前端 MainActivity.kt 由韦端宇并行落地，若此刻未含新函数则 SKIP 并如实标注。
        {
            int setEx = count(act311, "setExcludeFromRecents\\(");
            // finish 真守卫：MainActivity L700 注释含"不用 finishAndRemoveTask()"字样（raw=1 是注释，非调用）。
            // 设计（design-backend §6.1）明确禁用该 API，实现用的是 AppTask.setExcludeFromRecents。
            // 用 stripComments 后计数守卫==0：若真有人写了该调用则命中 → FAIL；不被说明性注释误伤。
            int finish = count(stripComments(act311), "finishAndRemoveTask");
            int onResume = count(act311, "override fun onResume\\(");
            boolean frontendPending = setEx == 0 && onResume == 0;
            if (frontendPending) {
                System.out.println("  SKIP  T-53-A5 P2 前端接线（MainActivity.kt 由前端并行落地，待前端完成后独立复验重跑）"
                        + "  — setEx=" + setEx + " finish=" + finish + " onResume=" + onResume);
            } else {
                check(setEx >= 1 && finish == 0 && onResume == 1,
                        "T-53-A5 P2 选型正确(setExcludeFromRecents) + 无 finishAndRemoveTask + onResume",
                        "setEx=" + setEx + " finish=" + finish + " onResume=" + onResume);
            }
        }

        // A6 · 磁贴文案 + 并发零改（applyTile / onClick 分作用域计数）
        {
            String tileBlk = slice(tile311, "private void applyTile(", "private boolean readEnabled");
            String clickBlk = slice(tile311, "public void onClick()", "private void applyTile");
            int tLabel = count(tileBlk, "防烧屏总开关");
            int tOld = count(tileBlk, "防止烧屏");
            // tSub 真守卫：applyTile 的 javadoc 注释含 2 处"已开启"字样（"磁贴显示已开启"/"仍是「已开启」"），
            // raw=3 含注释；真实 subtitle 仅 L59 一处。用 stripComments 守卫 subtitle 恰好 1 处"已开启"。
            int tSub = count(stripComments(tileBlk), "已开启");
            int cOn = count(clickBlk, "防烧屏总开关 ·");
            int cOld = count(clickBlk, "防止烧屏");
            int cFlip = count(clickBlk, "ConfStore\\.flipStatus\\(\\)");
            int cThread = count(clickBlk, "new Thread");
            int strLabel = count(str311, "<string name=\"tile_label\">防烧屏总开关</string>");
            check(tLabel == 1 && tOld == 0 && tSub == 1 && cOn == 2 && cOld == 0
                            && cFlip == 1 && cThread == 1 && strLabel == 1,
                    "T-53-A6 磁贴 label/Toast 文案 + flipStatus/后台线程零改",
                    "tile=" + tLabel + "/" + tOld + "/" + tSub + " click=" + cOn + "/" + cOld
                            + "/" + cFlip + "/" + cThread + " strings=" + strLabel);
        }

        // A7 · 越界零改哈希（9 个文件，逐字 MATCH 基线）
        {
            String[][] baseFiles = {
                    // P-1 授权改动：Controller.java 基线为修复后实测值
                    // （v3.0.14：containsHeadsUp 双判据 + hidden 下窗口可见时仍推进偏移）。
                    // v3.0.17：dimByZone 几何分区透明（HyperOS 类名不命中兜底），基线重锁
                    // v3.0.30：状态栏三班倒相位（applyStatusRound/applyShowNavOnly），基线重锁
                    // v3.0.31：分区能力探测 probeLeaves + 不可分区降级，基线重锁
                    // v3.0.35：AOSP/Pixel 薄命中补遮，基线重锁
                    {"Controller.java", "69958205805b19231755de2581045945d893f8a8812a303087ef68619f36506a"},
                    {"Config.java", "3f603999570682432335dbede46930bdf36e821d42c073766c83a7ac25229831"},
                    // v3.0.16 授权改动：enable_app_overlay 默认值 0→1（仅无障碍引擎生效）
                    {"ConfStore.java", "abe7b6034db15577b1b89c49007ebd78db12347fedaa99af9e3465c986ee73cb"},
                    {"ThhLiveGate.java", "09903e9bb86f1f9f044c04a869df71a9c6e31ea8d8e4746aa7381c0e81226c5f"},
                    // v3.0.16 授权改动：应用内遮盖通道重开（仅 overlay 引擎）
                    {"BurnInMaskService.java", "aef50ab9c6f83ccb168345226eff5c50fcc9558ee1daa64ec7d76e03d342e1e1"},
                    {"BootReceiver.java", "8383a771e4ce1e3fc9d9fa7da4b6e00f66740a901a3a56ca8041b3c10ba2aeea"},
                    {"VideoApps.java", "a2d2e5d5d3ccd65e6dc2960a2e80d7237ede53230161fdf790f6812327dddea4"},
                    {"VideoAppProbe.java", "a3367842f05930d3698ac90f87325a910ce5d76eed2d06644d64f56155b49bb9"},
                    {"LivePause.java", "a831315930feaf768dbf00dcdec317338a7ddce6707f3e134142a5b951aae984"},
                    {"LspScope.java", "ae13eb8546982734559eb6c4c0f69a6f99a19c33ee8f9d2b361a00bb1c23dd3f"},
                    {"AppRegions.java", "97e9e423f41b48c945af69af4aada5814c90efdbc5afa3674c896c02da5915cd"}
            };
            int ok = 0;
            StringBuilder bad = new StringBuilder();
            for (String[] fb : baseFiles) {
                String h = sha256(read(dir.resolve(fb[0])));
                if (fb[1].equals(h)) {
                    ok++;
                } else {
                    bad.append(fb[0]).append('=').append(h.substring(0, 8)).append(' ');
                }
            }
            String icHash = sha256(ic311);
            boolean icOk = "fae472467cde62a45c6d3a4914de0e35eadc22632aad5441a176c62e6f13c950".equals(icHash);
            check(ok == baseFiles.length && icOk,
                    "T-53-A7 越界零改哈希（11 Hook 文件 + ic_tile）",
                    "match=" + ok + "/" + baseFiles.length + " ic=" + icOk + " bad=" + bad);
        }

        // M · 变异实证（内存副本，真守卫自证）：α~η
        {
            // α：KS_FEED 词表加回"全屏观看" → A1 FAIL
            String mutA = dy311.replace("String[] KS_FEED_KEYS = {\"含AI生成内容\", \"内容由AI生成\"}",
                    "String[] KS_FEED_KEYS = {\"全屏观看\", \"含AI生成内容\", \"内容由AI生成\"}");
            boolean caughtA = count(slice(mutA, "String[] KS_FEED_KEYS =", ";"), "全屏观看") > 0;
            // β：删 alias 的 LAUNCHER → A3 FAIL
            String mutB = man311.replace("<category android:name=\"android.intent.category.LAUNCHER\" />", "<!--removed-->");
            boolean caughtB = count(slice(mutB, "<activity-alias", "</activity-alias>"), "LAUNCHER") == 0;
            // γ：setExcludeFromRecents → finishAndRemoveTask（夹具，不依赖前端是否落地）
            String gammaSrc = "t.setExcludeFromRecents(on);";
            String mutG = gammaSrc.replace("setExcludeFromRecents", "finishAndRemoveTask");
            boolean caughtG = count(mutG, "finishAndRemoveTask") == 1 && count(mutG, "setExcludeFromRecents\\(") == 0;
            // δ：countContentCards 改回 !targetGeoOk(0.45) → A2c FAIL
            String mutD = dy311.replace("hasCoverImage(c, 0) && hasChildByClass(c, \"TextView\")",
                    "!targetGeoOk(c, 0.45f, 0.45f) && hasChildByClass(c, \"TextView\")");
            boolean caughtD = count(mutD, "!targetGeoOk\\(c, 0\\.45f, 0\\.45f\\)") == 1;
            // ε：注入 ConfStore.read(); 进 AppVisibilityStore → A4 FAIL
            String mutE = vis311 + "\n    static void leak() { ConfStore.read(); }\n";
            boolean caughtE = count(mutE, "ConfStore") > 0 || count(stripComments(mutE), "ConfStore") > 0;
            // ζ：把 LAUNCHER 加回本体 activity → A3 FAIL（mainBlk 命中 + 全局==3）
            String mutZ = man311.replace("android:name=\".MainActivity\"\n            android:exported=\"true\">",
                    "android:name=\".MainActivity\"\n            android:exported=\"true\">\n"
                            + "            <intent-filter><category android:name=\"android.intent.category.LAUNCHER\" /></intent-filter>");
            boolean caughtZ = count(slice(mutZ, "android:name=\".MainActivity\"", "</activity>"), "LAUNCHER") >= 1
                    && count(mutZ, "android.intent.category.LAUNCHER") == 3;
            // η：把 isProfileContent 兜底加回结构锚 → A2c FAIL
            String mutEta = dy311 + "\n    private static boolean isProfileContent(View g) { return false; }\n";
            boolean caughtEta = count(mutEta, "isProfileContent") > 0;
            check(caughtA && caughtB && caughtG && caughtD && caughtE && caughtZ && caughtEta,
                    "T-53-M 变异实证：α/β/γ/δ/ε/ζ/η 全部 caught",
                    "α=" + caughtA + " β=" + caughtB + " γ=" + caughtG + " δ=" + caughtD
                            + " ε=" + caughtE + " ζ=" + caughtZ + " η=" + caughtEta);
        }

        // ---------- T-54 v3.0.11 P-1 heads-up 横幅可达性（真守卫，设计门禁 PASS 版） ----------
        {
            System.out.println();
            System.out.println("== T-54 P-1 heads-up 可达性（G1~G9）==");
            String p1ctrl = stripComments(read(dir.resolve("Controller.java")));
            String ps = stripComments(read(dir.resolve("PixelShift.java")));
            String ogo = stripComments(read(dir.resolve("OverlayGeometry.java")));
            String bmsRaw = read(dir.resolve("BurnInMaskService.java"));
            String cfg = read(dir.resolve("../../../../../res/xml/accessibility_service_config.xml"));

            // G1 · 白名单函数存在 / 递归祖先扫描 / 只读 / isIconLike 保留
            String body = slice(p1ctrl, "static boolean isHeadsUpContainer", "\n    }");
            String scan = slice(p1ctrl, "static boolean containsHeadsUp", "\n    }");
            check(body.contains("HeadsUp") && body.contains("HeadsUpNotificationView")
                            && body.contains("NotificationShade") && body.contains("heads_up"),
                    "T-54-G1a 白名单覆盖 AOSP/OEM/下拉面板/资源id 锚点", "");
            check(scan.contains("isHeadsUpContainer") && scan.contains("getChildCount")
                            && scan.contains("HEADS_UP_SCAN_MAX_DEPTH"),
                    "T-54-G1b containsHeadsUp 递归扫描+深度上限存在", "");
            boolean pure = !body.contains("setAlpha") && !body.contains("setTranslation")
                    && !body.contains("setVisibility")
                    && !scan.contains("setAlpha") && !scan.contains("setTranslation")
                    && !scan.contains("setVisibility");
            check(pure, "T-54-G1c 两函数只读（无 setAlpha/Translation/Visibility）", "");
            check(slice(p1ctrl, "String[] bad = {", "};").contains("HeadsUp"),
                    "T-54-G1d isIconLike 黑名单保留 HeadsUp 词（防回退）", "");

            // G2 · 整窗 alpha=0 已移除 + 残留恢复（分两处降级各自 slice，第3轮门禁定稿）
            String hide = slice(p1ctrl, "private static void hideStatusKeepNotify", "private static List<View> pathTo");
            check(!hide.contains("sv.setAlpha(0f)") && !hide.contains("sv.setVisibility"),
                    "T-54-G2a hideStatusKeepNotify 内无整窗 sv.setAlpha(0f)/setVisibility", "");
            check(countLiteral(hide, "hideKnownStaticIconsOnly(sv, 0)") >= 1
                            && countLiteral(p1ctrl, "hideKnownStaticIconsOnly(sv, 0)") == 2,
                    // v3.0.35 授权改判据：原来是「z==0 才补」，现在「薄命中( z<4 )也补」，调用点仍是 2 处
                    "T-54-G2b 非容器分支 + 三级链薄命中补遮，共 2 处调用", "");
            String headG2 = slice(hide, "View sv = statusView;",
                    "if (!(sv instanceof android.view.ViewGroup))");
            check(headG2.contains("sv.setAlpha(1f)"),
                    "T-54-G2c1 入口统一恢复残留 sv.alpha=1（降级前生效）", "");
            check(hide.contains("if (sv.getAlpha() != 1f) {")
                            && hide.contains("sv.setAlpha(1f);"),
                    "T-54-G2c2 两处降级仍保留 alpha 恢复（统一入口，无整窗透明）", "");
            String fb = slice(p1ctrl, "private static void hideKnownStaticIconsOnly", "\n    }");
            check(fb.contains("containsHeadsUp(v, 0)") && !fb.contains("sv.setAlpha"),
                    "T-54-G2d 降级递归含祖先守卫且不整窗透明", "");

            // G3 · findNotificationNode 收紧（stripComments + 字面量构造规避转义）
            String fnn = slice(p1ctrl, "private static View findNotificationNode", "private static String desc");
            check(fnn.contains("containsHeadsUp(c, 0)") && fnn.contains("NotificationIcon")
                            && !fnn.contains("n.contains(" + '"' + "Notification" + '"' + ")"),
                    "T-54-G3 findNotificationNode 排除横幅祖先且收紧为 NotificationIcon 语义", "");

            // G4 · dimAlong 祖先守卫
            String dim = slice(p1ctrl, "private static void dimAlong", "private static void setAlphaTracked");
            check(dim.contains("containsHeadsUp(c, 0)") && dim.contains("setAlphaTracked(c, 1f, false)"),
                    "T-54-G4 dimAlong 对含 heads-up 的祖先 keep 且不记账", "");

            // G5 · PixelShift 两处祖先守卫（countLiteral，期望恰 2）
            int guardHits = countLiteral(ps, "Controller.containsHeadsUp(c, 0)");
            check(guardHits == 2,
                    "T-54-G5 PixelShift applyTo+enforce 均含祖先守卫", "hits=" + guardHits);

            // G6 · 无障碍零事件 + 静态几何（反通知事件断言 + 几何上限）
            String sb = slice(ogo, "public static Rect statusBar", "\n    }");
            check(sb.contains("Math.min(statusBarHeight") && sb.contains("screenH / 4"),
                    "T-54-G6a statusBar 几何上限 h=min(statusBarHeight,..) 未改（INV-4）", "");
            // v3.2.2 授权变更：无障碍侧新增「通知事件 → 顶部区让开」。
            // 约束不放松：只能用于让开（YIELD_NOTIFY_MS），不得引入任何 heads-up grace /
            // topForHeadsUp 之类的改动路径；开关必须存在（c.yieldNotify）。
            check(!bmsRaw.contains("HEADS_UP_GRACE_MS") && !bmsRaw.contains("topForHeadsUp")
                            && !bmsRaw.contains("inHeadsUpGrace")
                            && bmsRaw.contains("TYPE_NOTIFICATION_STATE_CHANGED")
                            && bmsRaw.contains("YIELD_NOTIFY_MS")
                            && bmsRaw.contains("c.yieldNotify"),
                    "T-54-G6b 无障碍通知事件仅用于顶部让开（无 grace / 无 topForHeadsUp）", "");
            check(cfg.contains("typeNotificationStateChanged")
                            && cfg.contains("typeWindowStateChanged")
                            && !cfg.contains("typeAllMask"),
                    "T-54-G6c config 只多声明通知事件一种（仍不含 typeAllMask）", "");

            // G7 · 纯几何行为断言（反射真实 OverlayGeometry.statusBar）
            Class<?> ogeh = Class.forName("io.github.wumisc.antiburn.OverlayGeometry");
            java.lang.reflect.Method statusBarMethod = ogeh.getMethod("statusBar", int.class, int.class, int.class);
            Object r1 = statusBarMethod.invoke(null, 1080, 2400, 72);
            int bottom1 = r1.getClass().getField("bottom").getInt(r1);
            check(r1 != null && bottom1 == 72,
                    "T-54-G7a statusBar(1080,2400,72).bottom==statusBarHeight（横幅在其下方）", "bottom=" + bottom1);
            Object r2 = statusBarMethod.invoke(null, 1080, 2400, 1000);
            int bottom2 = r2.getClass().getField("bottom").getInt(r2);
            check(bottom2 <= 2400 / 4,
                    "T-54-G7b 异常高度钳制 bottom<=screenH/4", "bottom=" + bottom2);
            Object r3 = statusBarMethod.invoke(null, 1080, 2400, 0);
            check(r3 == null, "T-54-G7c statusBarHeight<=0 返回 null", "");

            // G8 · 越界哈希零改（G8a 冻结 8 文件）+ 授权/无障碍文件基线（实测值，待主理人复核锁定）
            String[][] p1Frozen = {
                    {"Config.java",      "3f603999570682432335dbede46930bdf36e821d42c073766c83a7ac25229831"},
                    // v3.0.16 授权改动：enable_app_overlay 默认 1（仅无障碍引擎生效）
                    {"ConfStore.java",   "abe7b6034db15577b1b89c49007ebd78db12347fedaa99af9e3465c986ee73cb"},
                    {"ThhLiveGate.java", "09903e9bb86f1f9f044c04a869df71a9c6e31ea8d8e4746aa7381c0e81226c5f"},
                    {"VideoApps.java",   "a2d2e5d5d3ccd65e6dc2960a2e80d7237ede53230161fdf790f6812327dddea4"},
                    {"VideoAppProbe.java","a3367842f05930d3698ac90f87325a910ce5d76eed2d06644d64f56155b49bb9"},
                    {"LivePause.java",   "a831315930feaf768dbf00dcdec317338a7ddce6707f3e134142a5b951aae984"},
                    {"LspScope.java",    "ae13eb8546982734559eb6c4c0f69a6f99a19c33ee8f9d2b361a00bb1c23dd3f"},
                    {"AppRegions.java",  "97e9e423f41b48c945af69af4aada5814c90efdbc5afa3674c896c02da5915cd"}
            };
            int okP1 = 0;
            StringBuilder badP1 = new StringBuilder();
            for (String[] fbk : p1Frozen) {
                String h = sha256(read(dir.resolve(fbk[0])));
                if (fbk[1].equals(h)) {
                    okP1++;
                } else {
                    badP1.append(fbk[0]).append('=').append(h.substring(0, 8)).append(' ');
                }
            }
            check(okP1 == p1Frozen.length,
                    "T-54-G8a 越界哈希：8 个非授权 Hook 文件逐字零改",
                    "match=" + okP1 + "/" + p1Frozen.length + " bad=" + badP1);
            // G8c：无障碍侧三文件零改（哈希=开工前基线，实施者按 sha256sum 实测，待主理人复核）
            String cfgH = sha256(read(dir.resolve("../../../../../res/xml/accessibility_service_config.xml")));
            boolean a11yZero = // v3.2 授权：MASK 日志签名对 fg 去抖（前台插件进程抖动不再每次写盘）
                    "aef50ab9c6f83ccb168345226eff5c50fcc9558ee1daa64ec7d76e03d342e1e1".equals(sha256(bmsRaw))
                    && "c56258f24f1395ad4c2b6510b165a88785294109227cdf6d04a151938ba8e23c".equals(sha256(read(dir.resolve("OverlayGeometry.java"))))
                    && "9026c696e17c9f16dff35e1146a59117c81a680342a337ce8cf02dcb6a5488bb".equals(cfgH);
            check(a11yZero, "T-54-G8c 无障碍侧 3 文件哈希==v3.2.2 基线（BMS/config 已授权变更）", "");
            // G8b：授权文件修复后基线（v3.0.14 偏移推进修复后实测，已按 sha256sum 重采；v3.0.30 三班倒重锁）
            // 注意：必须读原始文件（含注释），与 sha256sum 采集口径一致，不能用 stripComments 后的串。
            boolean authZero = "69958205805b19231755de2581045945d893f8a8812a303087ef68619f36506a"
                            .equals(sha256(read(dir.resolve("Controller.java"))))
                    && "fc9c5b0fb1f35fb2ecf084b398507f14dc0f5b15079cc82427c59ba580dc2e99"
                            .equals(sha256(read(dir.resolve("PixelShift.java"))));
            check(authZero, "T-54-G8b 授权文件哈希==修复后实测基线（待主理人复核确认）", "");

            // G9 · 变异实证（四处变异均被对应守卫命中）
            String mutA = ps.replaceFirst("Controller\\.containsHeadsUp\\(c, 0\\)", "");
            boolean caughtA = countLiteral(mutA, "Controller.containsHeadsUp(c, 0)") < 2;
            String mutB = hide.replace("hideKnownStaticIconsOnly(sv, 0);", "sv.setAlpha(0f);");
            boolean caughtB = mutB.contains("sv.setAlpha(0f)");
            String mutC = sb.replace("Math.min(statusBarHeight", "Math.max(statusBarHeight");
            boolean caughtC = !mutC.contains("Math.min(statusBarHeight");
            String mutD = dim.replace("containsHeadsUp(c, 0)", "isHeadsUpContainer(c)");
            boolean caughtD = !mutD.contains("containsHeadsUp(c, 0)");
            check(caughtA && caughtB && caughtC && caughtD,
                    "T-54-G9 变异实证：四处变异均被对应守卫命中",
                    "α=" + caughtA + " β=" + caughtB + " γ=" + caughtC + " δ=" + caughtD);
        }

        // ---------- T-55 v3.0.13 隐藏桌面图标后仍可被 LSPosed / 系统入口启动（真守卫） ----------
        {
            System.out.println();
            System.out.println("== T-55 v3.0.13 隐藏图标后外部启动可达性 ==");
            String man313 = read(dir.resolve("../../../../../AndroidManifest.xml"));
            String act313 = read(dir.resolve("MainActivity.kt"));
            String vis313 = read(dir.resolve("AppVisibilityStore.java"));
            String gradle313 = read(dir.resolve("../../../../../../../build.gradle"));

            // A1 · 根因修复：本体补 MAIN+INFO 入口。LSPosed AppHelper 先查 CATEGORY_INFO，
            //      隐藏态下 alias 被禁用也不影响该入口，getLaunchIntentForPackage 不再返回 null。
            String mainBlk313 = slice(man313, "android:name=\".MainActivity\"", "</activity>");
            int infoCat = count(mainBlk313, "android.intent.category.INFO");
            int mainAct = count(mainBlk313, "android.intent.action.MAIN");
            check(infoCat == 1 && mainAct == 1,
                    "T-55-A1 MainActivity 承载 MAIN+INFO 入口（LSPosed 首查 INFO 即命中）",
                    "info=" + infoCat + " mainAction=" + mainAct);

            // A1b · v3.0.15：INFO 入口必须带 DEFAULT 类目。
            //      getLaunchIntentForPackage 的 INFO 查询带 MATCH_DEFAULT_ONLY，缺 DEFAULT 即不命中，
            //      LSPosed 仍拿不到启动 Intent（真机「开启隐藏图标后打不开」的根因）。
            int infoDefault313 = count(mainBlk313, "android.intent.category.DEFAULT");
            check(infoDefault313 == 1,
                    "T-55-A1b INFO 入口带 DEFAULT 类目（MATCH_DEFAULT_ONLY 查询可命中）",
                    "default=" + infoDefault313);

            // A2 · 隐藏功能不退化：桌面只解析 LAUNCHER，本体必须仍为 0，否则隐藏态会冒出图标
            int launcherInMain = count(mainBlk313, "LAUNCHER");
            String aliasBlk313 = slice(man313, "<activity-alias", "</activity-alias>");
            int aliasLauncher313 = count(aliasBlk313, "android.intent.category.LAUNCHER");
            int totalLauncher313 = count(man313, "android.intent.category.LAUNCHER");
            check(launcherInMain == 0 && aliasLauncher313 == 1 && totalLauncher313 == 2,
                    "T-55-A2 隐藏不退化：桌面类目仍只由 alias 承载（本体0 / alias1 / 全局2）",
                    "main=" + launcherInMain + " alias=" + aliasLauncher313 + " total=" + totalLauncher313);

            // A3 · 可见性实现零改：仍只切 alias，仍带 DONT_KILL_APP（实现处恰好 1）
            String vis313Strip = stripComments(vis313);
            int aliasSwitch313 = count(vis313Strip, "LauncherAlias");
            int dk313 = count(vis313Strip, "DONT_KILL_APP");
            check(aliasSwitch313 == 1 && dk313 == 1,
                    "T-55-A3 AppVisibilityStore 仍只切 alias 且保留 DONT_KILL_APP",
                    "alias=" + aliasSwitch313 + " dk=" + dk313);

            // A4 · 恢复指引必须是双路径，不得再只指向（此前已失效的）LSPosed 单一路径
            int dualPath = count(act313, "从 LSPosed 模块管理器或「设置→应用管理→防烧屏」打开本应用");
            int deadPath = count(act313, "从 LSPosed 模块管理器打开本应用");
            check(dualPath == 1 && deadPath == 0,
                    "T-55-A4 恢复文案给出 LSPosed + 系统设置双路径（旧单路径已删除）",
                    "dual=" + dualPath + " dead=" + deadPath);

            // A5 · 版本锚点
            java.util.regex.Matcher cm313 = java.util.regex.Pattern
                    .compile("versionCode\\s+(\\d+)").matcher(gradle313);
            java.util.regex.Matcher nm313 = java.util.regex.Pattern
                    .compile("versionName\\s+\"([^\"]+)\"").matcher(gradle313);
            boolean ver313 = cm313.find() && nm313.find()
                    && Integer.parseInt(cm313.group(1)) == 105
                    && "3.2.6".equals(nm313.group(1));
            check(ver313, "T-55-A5 版本锚点 99 / 3.2", "");

            // A6 · 变异实证：把 INFO 类目改坏，A1 守卫必须失效（证明断言非空转）
            String mutMan = man313.replace("android.intent.category.INFO", "android.intent.category.BOGUS");
            int mutInfo = count(slice(mutMan, "android:name=\".MainActivity\"", "</activity>"),
                    "android.intent.category.INFO");
            check(mutInfo == 0, "T-55-A6 变异实证：删/改 INFO 入口即被拦截", "mut=" + mutInfo);

            // A7 · 变异实证：删掉 DEFAULT 类目，A1b 守卫必须失效
            String mutDefault = man313.replace(
                    "<category android:name=\"android.intent.category.DEFAULT\" />", "");
            int mutDefaultCount = count(
                    slice(mutDefault, "android:name=\".MainActivity\"", "</activity>"),
                    "android.intent.category.DEFAULT");
            check(mutDefaultCount == 0, "T-55-A7 变异实证：删 DEFAULT 类目即被拦截", "mut=" + mutDefaultCount);
        }

        // ---------- T-56 v3.0.16 无障碍模式四 App 适配（红果 / 抖音 / 快手 / B站）----------
        System.out.println();
        System.out.println("== T-56 无障碍应用内遮盖四 App 适配 ==");
        {
            String mask316 = read(dir.resolve("BurnInMaskService.java"));
            String regions316 = read(dir.resolve("AppRegions.java"));
            String videos316 = read(dir.resolve("VideoApps.java"));
            // A1 · 四个 App 都被识别为可遮盖目标
            check(videos316.contains("DOUYIN") && videos316.contains("DRAMA")
                            && videos316.contains("KUAISHOU") && videos316.contains("BILIBILI"),
                    "T-56-A1 VideoApps 覆盖抖音/红果/快手/B站四类目标", "");
            // A2 · 每 App 的配置前缀都在 resolveApp 里被消费
            check(mask316.contains("\"dy_\"") && mask316.contains("\"dr_\"")
                            && mask316.contains("\"ks_\"") && mask316.contains("\"bi_\""),
                    "T-56-A2 四个 App 的区域开关前缀（dy_/dr_/ks_/bi_）均已接线", "");
            // A3 · 默认三区：R+T+B；红果不画底部（选集入口可点）
            check(mask316.contains("r = true;") && mask316.contains("t = true;")
                            && mask316.contains("b = kind != VideoApps.Kind.DRAMA;"),
                    "T-56-A3 未显式配置时默认右侧列+顶部+底部，红果底部不画", "");
            // A4 · 几何单一真源：四区常量与真机标定比例
            check(regions316.contains("public static final int R = 1")
                            && regions316.contains("public static final int B = 4")
                            && regions316.contains("w * 0.80f") && regions316.contains("h * 0.86f"),
                    "T-56-A4 AppRegions 提供 R/T/B/C 四区与 v3.2.5 比例（80 / 86）", "");
            // A5 · 遮盖层不吃触摸，点赞评论照常可点
            check(mask316.contains("FLAG_NOT_TOUCHABLE"),
                    "T-56-A5 无障碍覆盖层不拦截触摸", "");
            // A6 · 退出目标 App 立即清空矩形（前台包名变 NONE → appArea=false）
            check(mask316.contains("resolveApp(c, foregroundPkg)")
                            && mask316.contains("c.appKind = VideoApps.Kind.NONE;"),
                    "T-56-A6 退出目标 App 即清空遮盖，不在设置页残留", "");
            // A7 · 前台识别来自无障碍事件，不依赖目标进程注入
            check(mask316.contains("TYPE_WINDOW_STATE_CHANGED") && mask316.contains("foregroundPkg"),
                    "T-56-A7 前台包名由无障碍事件维护，无需注入目标进程", "");
        }

        // ---------- T-57 v3.0.17 状态栏几何分区透明（HyperOS 类名不命中的兜底）----------
        System.out.println();
        System.out.println("== T-57 状态栏几何分区透明 ==");
        {
            String ctrl317 = read(dir.resolve("Controller.java"));
            check(ctrl317.contains("private static int dimByZone(android.view.ViewGroup root)"),
                    "T-57-A1 dimByZone 几何分区入口存在", "");
            check(ctrl317.contains("(int) (w * 0.34f), (int) (w * 0.62f)"),
                    "T-57-A2 左区 34% / 右区 62%，中间通知图标区保留", "");
            check(ctrl317.contains("if (n.contains(\"Notification\"))"),
                    "T-57-A3 类名含 Notification 的叶子一律保留（通知不误伤）", "");
            check(ctrl317.contains("if (isHeadsUpBanner(c) && isHeadsUpVisible(c))")
                            && ctrl317.contains("lastZoneH++;"),
                    "T-57-A4 只跳过正在显示的横幅容器本身（不牵连兄弟支）", "");
            check(ctrl317.contains("dimFallbackChain(sv, svg)"),
                    "T-57-A5 几何命中不足即走三级降级链（不放弃旧 ROM）", "");
            check(ctrl317.contains("setAlphaTracked(v, 0f, true)"),
                    "T-57-A6 只透明叶子并记账，applyShow 可完整恢复", "");
            // 变异实证：删掉 Notification 保护 → A3 失效
            String mutZone = ctrl317.replace("if (n.contains(\"Notification\")) {", "if (false) {");
            check(!mutZone.contains("if (n.contains(\"Notification\"))"),
                    "T-57-A7 变异实证：删 Notification 保护即被拦截", "");
        }

        // ---------- T-58 v3.0.18 状态栏强兜底（只跳真横幅 + 除通知外全透明）----------
        System.out.println();
        System.out.println("== T-58 状态栏强兜底 ==");
        {
            String ctrl318 = read(dir.resolve("Controller.java"));
            check(ctrl318.contains("if (isHeadsUpBanner(c) && isHeadsUpVisible(c))"),
                    "T-58-A1 只跳过真正显示中的横幅容器，不牵连兄弟支", "");
            check(ctrl318.contains("private static int dimAllExceptNotify(android.view.ViewGroup root)"),
                    "T-58-A2 除通知外全透明兜底存在", "");
            check(ctrl318.contains("private static boolean isNotifyish(View v)"),
                    "T-58-A3 类名/资源名含 notification 的分支永远保留", "");
            check(ctrl318.contains("z = dimAllExceptBanner(svg)")
                            && ctrl318.contains("lastZone += \"/f3=\""),
                    "T-58-A4 三级降级链到底（除横幅全透明必胜）", "");
            check(ctrl318.contains("\" zone=\" + lastZone"),
                    "T-58-A5 SYS 行输出 zone 诊断（宽/命中/跳横幅数）", "");
            check(ctrl318.contains("setAlphaTracked(c, 0f, true)"),
                    "T-58-A6 兜底也只透明叶子并记账", "");
            check(ctrl318.contains("n.contains(\"heads_up\") || n.contains(\"headsup\")"),
                    "T-58-A7 横幅判据只认 heads_up，不再拿 notification 当横幅", "");
            check(ctrl318.contains("z = dimAllExceptNotify(svg)")
                            && ctrl318.contains("if (z >= 2)"),
                    "T-58-A8 二级除通知全透明在三级之前，逐级短路", "");
        }

        // ---------- T-59 v3.0.19 悬浮球「显」模式不得误伤 ----------
        System.out.println();
        System.out.println("== T-59 显模式全放行 ==");
        {
            String hook319 = stripComments(read(dir.resolve("DouyinHook.java")));
            String hs319 = slice(hook319, "private static void hideSafe(View v, String what)",
                    "private static boolean isOwnOverlay(View v)");
            check(hs319.contains("if (forceShow)") && hs319.contains("showDeep(v)"),
                    "T-59-A1 hideSafe 入口 forceShow 一律转恢复", "");
            String td319 = slice(hook319, "private static void transparentDeep(View v, String what)",
                    "private static void showAll()");
            check(td319.contains("if (forceShow)") && td319.contains("showDeep(v)"),
                    "T-59-A2 transparentDeep 入口 forceShow 一律转恢复（覆盖直播/兜底路径）", "");
            check(hook319.contains("ball.setText(forceShow ? \"显\" : \"藏\")"),
                    "T-59-A3 悬浮球「显/藏」文字与 forceShow 语义绑定", "");
        }

        // ---------- T-60 v3.0.20 状态栏左右细分 + 周期时间前置 ----------
        System.out.println();
        System.out.println("== T-60 状态栏左右细分 ==");
        {
            String cfg320 = read(dir.resolve("Config.java"));
            check(cfg320.contains("public static volatile boolean statusClockOn = true")
                            && cfg320.contains("public static volatile boolean statusIconsOn = true"),
                    "T-60-A1 Config 两个开关默认 true（老配置缺键不改变行为）", "");
            check(cfg320.contains("case \"status_clock_on\"") && cfg320.contains("case \"status_icons_on\""),
                    "T-60-A2 conf 两个新键被 parse 识别", "");

            String ctrl320 = read(dir.resolve("Controller.java"));
            check(ctrl320.contains("private static boolean sideAllowed(int cx, int w)"),
                    "T-60-A3 Controller 按水平中心判定该侧是否允许隐藏", "");
            check(ctrl320.contains("if (cx >= left && cx <= right) {")
                            && ctrl320.contains("return false;"),
                    "T-60-A4 中间 34%~62% 通知区任何情况下都保留", "");
            check(ctrl320.contains("if (!Config.statusClockOn && !Config.statusIconsOn) {")
                            && ctrl320.contains("return;      // v3.0.20"),
                    "T-60-A5 两侧都关时直接不动整条栏", "");

            String act320 = read(dir.resolve("MainActivity.kt"));
            check(act320.contains("val statusClockOn: Boolean = true")
                            && act320.contains("val statusIconsOn: Boolean = true"),
                    "T-60-A6 AbState 两字段存在且默认 true", "");
            check(act320.contains("lines[\"status_clock_on\"]") && act320.contains("lines[\"status_icons_on\"]")
                            && act320.contains("putB(\"status_clock_on\"") && act320.contains("putB(\"status_icons_on\""),
                    "T-60-A7 两键写入 + diff 落盘齐全", "");
            check(act320.contains("OptionRow(\"隐藏时间\"") && act320.contains("OptionRow(\"隐藏右侧图标\""),
                    "T-60-A8 通用页出现「隐藏时间」「隐藏右侧图标」两行", "");
            String feat = slice(act320, "private fun FeaturesPage(", "private fun OptionRow(");
            String general = slice(act320, "private fun GeneralFeaturePage(", "private fun DouyinFeaturePage(");
            check(feat.contains("ListRow(\"周期时间\"") && feat.contains("CycleDialog("),
                    "T-60-A9 功能首页出现「周期时间」卡并可弹周期设置", "");
            check(!general.contains("ListRow(\"周期时长\"") && !general.contains("CycleDialog("),
                    "T-60-A10 通用页不再重复周期入口（单一真源）", "");
        }

        // ---------- T-61 v3.0.21 合集页底部固定条 ----------
        System.out.println();
        System.out.println("== T-61 底部 chrome ==");
        {
            String hook321 = stripComments(read(dir.resolve("DouyinHook.java")));
            String cb = slice(hook321, "private static List<View> collectBottom(ViewGroup root)",
                    "private static View findTabBarByClassName");
            check(cb.contains("collectBottomChrome(root, list, 0)"),
                    "T-61-A1 collectBottom 接入底部 chrome 收集（含 EditText 的条不再整棵跳过）", "");
            String chrome = slice(hook321, "private static void collectBottomChrome(View v, List<View> list, int depth)",
                    "/** 子树里是否有输入框");
            check(chrome.contains("w >= sw * 0.5f") && chrome.contains("h <= sh * 0.14f")
                            && chrome.contains("bottomInRoot >= sh * 0.86f"),
                    "T-61-A2 几何三门槛：半屏宽 / 不高于 14% / 下缘贴 86%", "");
            check(chrome.contains("!hasQuietHint(g, 2)"),
                    "T-61-A3 音量浮层（取消静音）不参与固定条隐藏", "");
            String hq = slice(hook321, "private static boolean hasQuietHint(View v, int depth)",
                    "private static void collectBottomNode");
            check(hq.contains("取消静音"),
                    "T-61-A4 静音浮层按文案排除", "");
            String he = slice(hook321, "private static boolean hasEditBox(View v, int depth)",
                    "private static boolean hasBottomChromeText");
            check(he.contains("android.widget.EditText") && he.contains("InputCompat"),
                    "T-61-A5 输入框识别覆盖原生 EditText 与自绘输入条", "");
            String ht = slice(hook321, "private static boolean hasBottomChromeText(View v, int depth)",
                    "/** 音量浮层");
            check(ht.contains("有什么想法") && ht.contains("展开说说")
                            && ht.contains("合集") && ht.contains("下一集"),
                    "T-61-A6 固定条文案覆盖评论条 / 合集条 / 下一集", "");
        }

        // ---------- T-62 v3.0.22 底部 chrome 坐标系校准 + 计数修正 ----------
        System.out.println();
        System.out.println("== T-62 校准 ==");
        {
            String hook322 = stripComments(read(dir.resolve("DouyinHook.java")));
            check(hook322.contains("int bottomInRoot = l != null ? l[1] + h - baseY : 0"),
                    "T-62-A1 贴底判定换算到根视图坐标系（沉浸式不再差一个状态栏）", "");
            check(hook322.contains("\"BOTTOMCHROME add \""),
                    "T-62-A2 命中底部 chrome 时写诊断日志（下轮可直接验证）", "");
            String act322 = read(dir.resolve("MainActivity.kt"));
            check(act322.contains("已开启 $generalOn/4 项"),
                    "T-62-A3 通用页计数口径与四项开关一致（3↔4 错位已修）", "");
        }

        // ---------- T-63 v3.0.23 自检证据源改为双层，去掉「没加载模块」误判 ----------
        System.out.println();
        System.out.println("== T-63 注入证据 ==");
        {
            String lsp323 = stripComments(read(dir.resolve("LspScope.java")));
            check(lsp323.contains("static Set<String> lspdLogHits") && lsp323.contains("/data/adb/lspd/log/modules.log"),
                    "T-63-A1 增加 LSPosed 日志命中解析（root 可读，三方进程写盘不受限）", "");
            check(!lsp323.contains("这不是定位算法的问题")
                            && lsp323.contains("证据缺失") && lsp323.contains("不能据此断定"),
                    "T-63-A2 证据为空时不下「LSPosed 没加载模块」的断言", "");
            String act323 = read(dir.resolve("MainActivity.kt"));
            check(act323.contains("LspScope.logHitsCached()") && act323.contains("LspScope.warmLogHits(context)"),
                    "T-63-A3 recentlyInjected 补日志证据（UI 线程只读缓存，su 走后台预热）", "");
            check(act323.contains("红果注入未确认") && !act323.contains("红果未注入 · 待勾选作用域"),
                    "T-63-A4 橙卡标题不再武断写「待勾选作用域」", "");
            String conf323 = stripComments(read(dir.resolve("ConfStore.java")));
            check(conf323.contains("lsp=\" + lsp") && conf323.contains("不代表没加载"),
                    "T-63-A5 SELF 行新增 lsp= 字段，hook=NONE 文案不再断定没加载", "");
        }

        // ---------- T-64 v3.0.24 实时前台判断 + 单一常驻通知 ----------
        System.out.println();
        System.out.println("== T-64 常驻通知 ==");
        {
            String man324 = read(dir.resolve("../../../../../AndroidManifest.xml"));
            check(man324.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
                            && man324.contains("android:foregroundServiceType=\"specialUse\"")
                            && man324.contains(".GuardService"),
                    "T-64-A1 前台服务按 Android 14+ 要求声明 specialUse 与权限", "");
            String guard324 = stripComments(read(dir.resolve("GuardService.java")));
            check(countLiteral(guard324, "NOTIF_ID = 1001") == 1
                            && countLiteral(guard324, "new NotificationChannel(") == 1
                            && guard324.contains("setOngoing(true)"),
                    "T-64-A2 只有一条通知：固定 ID + 单通道 + ongoing",
                    "id=" + countLiteral(guard324, "NOTIF_ID = 1001") + " ch=" + countLiteral(guard324, "new NotificationChannel(")
                            + " ongoing=" + guard324.contains("setOngoing(true)"));
            check(guard324.contains("3L * 3600_000L") && guard324.contains("30L * 60_000L"),
                    "T-64-A3 低频：定时 3 小时，事件触发 30 分钟节流（不做实时刷新）", "");
            String bms = stripComments(read(dir.resolve("BurnInMaskService.java")));
            check(bms.contains("ForegroundWatch.onForeground(this, pkg)")
                            && bms.contains("TYPE_WINDOW_STATE_CHANGED"),
                    "T-64-A4 实时判断接在无障碍窗口切换事件上", "");
            String fw = stripComments(read(dir.resolve("ForegroundWatch.java")));
            check(fw.contains("VideoApps.isTarget(pkg)") && fw.contains("ConfigProvider.lastInject"),
                    "T-64-A5 双证据：无障碍实时 + LSPosed 注入回传", "");
            String act324 = read(dir.resolve("MainActivity.kt"));
            check(act324.contains("GuardService.ensure(this)")
                            && act324.contains("POST_NOTIFICATIONS")
                            && act324.contains("ForegroundWatch.recentTarget(context)"),
                    "T-64-A6 可见时启动服务 + 申请通知权限 + 首页显示当前前台", "");
            String cp324 = stripComments(read(dir.resolve("ConfigProvider.java")));
            check(cp324.contains("GuardService.refreshIfDue(ctx)"),
                    "T-64-A7 LSPosed 注入回传时触发通知刷新（每次注入显示一下）", "");
        }

        // ---------- T-65 v3.0.25 闪退止血 + 崩溃可观测 ----------
        System.out.println();
        System.out.println("== T-65 闪退修复 ==");
        {
            String cfg325 = stripComments(read(dir.resolve("Config.java")));
            int selfGuard = cfg325.indexOf("\"io.github.wumisc.antiburn\".equals(pkg)");
            boolean selfExempt = selfGuard > 0
                    && cfg325.substring(selfGuard, Math.min(cfg325.length(), selfGuard + 120)).contains("return false");
            check(selfExempt,
                    "T-65-A1 本模块自己的界面不再干预（用户设置 App 时不再拖崩 SystemUI）", "");
            String conf325 = stripComments(read(dir.resolve("ConfStore.java")));
            check(conf325.contains("private static boolean appendRaw(")
                            && conf325.contains("public static boolean appendRawLine("),
                    "T-65-A2 日志追加返回真实成败（分区存储失败不再被当成已写）", "");
            String app325 = stripComments(read(dir.resolve("AbApplication.java")));
            check(app325.contains("if (ConfStore.appendRawLine(replay))") && app325.contains("pend.delete()"),
                    "T-65-A3 崩溃回放走 su，写成功才删 pending（下次闪退能看到堆栈）", "");
        }

        // ---------- T-66 v3.2.1 Pixel / AOSP 修复（整层兜底 / 无子容器当叶子 / 导航栏放宽） ----------
        {
            String c661 = read(dir.resolve("Controller.java"));
            // A1 · 四处递归入口都加了「无子 ViewGroup 当叶子」守卫（ComposeView 无子时不再 return 0）
            int guardC = count(c661, "\\(\\(android\\.view\\.ViewGroup\\) c\\)\\.getChildCount\\(\\) > 0");
            int guardV = count(c661, "\\(\\(android\\.view\\.ViewGroup\\) v\\)\\.getChildCount\\(\\) > 0");
            check(guardC == 4 && guardV == 1,
                    "T-66-A1 无子 ViewGroup 当叶子：四处 c 守卫 + 一处 v 守卫", "c=" + guardC + " v=" + guardV);

            // A2 · 第 4 级整层兜底只在 f3 之后调用
            int f3 = c661.indexOf("lastZone += \"/f3=\"");
            int f4 = c661.indexOf("dimWholeBarIfSafe(sv, svg)");
            check(f3 >= 0 && f4 > f3, "T-66-A2 整层兜底位于 f3 之后（f3=" + f3 + " f4=" + f4 + "）", "");
            check(count(c661, "dimWholeBarIfSafe\\(sv, svg\\)") == 1,
                    "T-66-A3 整层兜底唯一接线", "");

            // A4 · 整层兜底必须带 heads-up 守卫 + 单侧开关守卫（用户硬约束：不影响悬浮通知）
            String whole = slice(c661, "private static boolean dimWholeBarIfSafe", "private static boolean hasLiveHeadsUp");
            check(whole.contains("hasLiveHeadsUp(svg, 0)")
                            && whole.contains("!Config.statusClockOn || !Config.statusIconsOn"),
                    "T-66-A4 整层兜底：栏内 live heads-up 放弃 + 只开一侧不整层", "");
            String hud = slice(c661, "private static boolean hasLiveHeadsUp", "private static void applyShow");
            check(hud.contains("isHeadsUpBanner(c)") && hud.contains("isHeadsUpVisible(c)")
                            && hud.contains("HEADS_UP_SCAN_MAX_DEPTH"),
                    "T-66-A5 heads-up 探测只读 + 深度上限", "");

            // A6 · 导航栏放宽：资源名 nav_bar / 类名 NavBarView / rescan 按 id 找
            check(c661.contains("\"nav_bar\"") && c661.contains("NavBarView")
                            && count(c661, "findNavById\\(root\\)") == 1,
                    "T-66-A6 导航栏：nav_bar 资源名 + NavBarView 类名 + rescan 按 id 兜底", "");
        }

        // ---------- T-67 v3.2.2 无障碍盖黑不再吞悬浮通知 ----------
        {
            String bms = read(dir.resolve("BurnInMaskService.java"));
            String cfgx = read(dir.resolve("../../../../../res/xml/accessibility_service_config.xml"));
            String act = read(dir.resolve("MainActivity.kt"));
            String cf = read(dir.resolve("Config.java"));
            String cs = read(dir.resolve("ConfStore.java"));

            check(cfgx.contains("typeNotificationStateChanged") && cfgx.contains("typeWindowStateChanged"),
                    "T-67-A1 config 同时声明窗口切换与通知事件", "");
            check(bms.contains("TYPE_NOTIFICATION_STATE_CHANGED")
                            && bms.contains("yieldUntil = SystemClock.uptimeMillis() + YIELD_NOTIFY_MS"),
                    "T-67-A2 收到通知事件写入让开截止时刻", "");
            check(bms.contains("mask &= ~AppRegions.T"),
                    "T-67-A3 让开期间只撤顶部区（右侧/底部/角落照旧）", "");
            check(bms.contains("c.yieldNotify") && bms.contains("if (cfg.yieldNotify)"),
                    "T-67-A4 让开受开关门控（关掉后行为完全回到旧版）", "");
            check(cf.contains("enableA11yYieldNotify") && cs.contains("enable_a11y_yield_notify=1")
                            && act.contains("enableA11yYieldNotify") && act.contains("enable_a11y_yield_notify"),
                    "T-67-A5 开关三层齐备：Config 字段 / 默认值 / App 读写", "");
            check(act.contains("悬浮通知") && act.contains("通知弹出时让开"),
                    "T-67-A6 首页与通用页都对用户明示这件事", "");
        }

        // ---------- T-68 v3.2.3 功耗 ----------
        {
            String bms68 = read(dir.resolve("BurnInMaskService.java"));
            String dy68 = read(dir.resolve("DouyinHook.java"));
            String act68 = read(dir.resolve("MainActivity.kt"));

            check(bms68.contains("private boolean idleParked")
                            && bms68.contains("idleParked = true;")
                            && bms68.contains("if (!idleParked)"),
                    "T-68-A1 灭屏停车：置位 + 不再自我排队", "");
            check(bms68.contains("ACTION_SCREEN_ON") && bms68.contains("ACTION_SCREEN_OFF")
                            && bms68.contains("ACTION_USER_PRESENT")
                            && bms68.contains("registerReceiver(screenReceiver"),
                    "T-68-A2 亮/灭屏广播是唯一唤醒源", "");
            check(bms68.contains("CONFIG_TTL_MS = 15_000L"),
                    "T-68-A3 配置读盘 3s→8s→15s（读盘=文件IO+全文解析）", "");
            check(bms68.contains("public static void invalidateConfig()")
                            && act68.contains("BurnInMaskService.invalidateConfig()"),
                    "T-68-A4 保存即失效缓存（弥补 TTL 拉长）", "");
            check(bms68.contains("String diagKey =") && bms68.contains("if (!diagKey.equals(lastDiagKey)"),
                    "T-68-A5 诊断串惰性化：先算键，不每帧拼长句+跑正则", "");
            check(dy68.contains("DPH_SCAN_INTERVAL_MS = 1500L"),
                    "T-68-A6 DPH 全树扫描 400ms→1500ms", "");
        }

        // ---------- T-69 靶场：四 App 遮盖矩阵 + 显隐对称 ----------
        {
            java.lang.reflect.Method rectsM = Class.forName("io.github.wumisc.antiburn.AppRegions")
                    .getMethod("rects", int.class, int.class, int.class, int.class);
            java.lang.reflect.Field fR = Class.forName("io.github.wumisc.antiburn.AppRegions").getField("R");
            java.lang.reflect.Field fT = Class.forName("io.github.wumisc.antiburn.AppRegions").getField("T");
            java.lang.reflect.Field fB = Class.forName("io.github.wumisc.antiburn.AppRegions").getField("B");
            java.lang.reflect.Field fC = Class.forName("io.github.wumisc.antiburn.AppRegions").getField("C");
            int vR = fR.getInt(null), vT = fT.getInt(null), vB = fB.getInt(null), vC = fC.getInt(null);
            int all = vR | vT | vB | vC;

            java.util.List<?> rects = (java.util.List<?>) rectsM.invoke(null, 1080, 2400, 72, all);
            int[][] got = new int[rects.size()][];
            for (int i = 0; i < rects.size(); i++) {
                got[i] = (int[]) rects.get(i);
            }
            // 顺序由 AppRegions 固定：R、T、B、C右、C左
            boolean geoOk = got.length == 5
                    && got[0][0] == 864 && got[0][1] == 672 && got[0][2] == 1080 && got[0][3] == 2208
                    && got[1][1] == 72 && got[1][3] == 264
                    && got[2][1] == 2064 && got[2][3] == 2400
                    && got[3][0] == 842 && got[4][2] == 216;
            check(geoOk, "T-69-A1 1080x2400 四区矩形与 v3.2.5 比例一致（R80/28-92，T72-11%，B86-100，C20%）",
                    "rects=" + got.length);

            StringBuilder table = new StringBuilder();
            table.append("R=[").append(got[0][0]).append(',').append(got[0][1]).append('-').append(got[0][3]).append("] ")
                    .append("T=[72-").append(got[1][3]).append("] ")
                    .append("B=[").append(got[2][1]).append("-2400] ");
            // v3.2.5 闭合缺口：快手带货卡/评论区落在 84%~90% 高。
            // 旧值 B 从 90%（2160）起、R 从 84%（907）起 → 84%~86% 这一条横带两边都碰不到。
            // 现在 B 提到 86%（2064）、R 提到 80%（864），两条带子叠着把它夹住。
            boolean gapKuaishou = got[2][1] <= 2064 && got[0][0] <= 907;
            check(gapKuaishou, "T-69-A2 缺口已闭合：B 起点 86% + R 左界 80% 双向夹住带货卡（84%~90%）", table.toString());

            // 抖音短剧顶部 Tab（状态栏下 0~8% 屏高）、B站顶部标题：T 区覆盖
            boolean topCovered = got[1][1] <= 100 && got[1][3] >= 200;
            check(topCovered, "T-69-A3 顶部 72~11% 覆盖抖音短剧 Tab / B站标题", "");

            // 右侧列：抖音点赞列多在 88%~98% 宽，R 区 84%~100% 覆盖；若控件在 80~84% 则漏
            boolean rightCovered = got[0][0] <= 1080 * 84 / 100;
            check(rightCovered, "T-69-A4 右侧 84%~100% 覆盖四 App 互动列", "");

            // 显隐对称：hide 统一入口 + 台账 + showAll 全量还原
            String dy69 = read(dir.resolve("DouyinHook.java"));
            check(dy69.contains("touched.add(v)") && dy69.contains("sweepTouched")
                            && dy69.contains("private static void showAll()"),
                    "T-69-B1 每次隐藏写入都进 WeakHashMap 台账，showAll 末尾全量 sweep", "");
            int listDecl = count(dy69, "private static final List<View> [a-zA-Z]+List");
            int shown = 0;
            for (String nm : new String[]{"rightList","bottomList","swipeHintList","centerPlusList",
                    "redDotList","captionList","ksLiveTargetList","dyLiveTargetList",
                    "ksFeedTargetList","dramaTargetList","liveTopList","liveBottomList","liveRightList"}) {
                if (count(slice(dy69, "private static void showAll()", "private static void sweepTouched"), nm) > 0) {
                    shown++;
                }
            }
            check(shown >= 13, "T-69-B2 showAll 覆盖全部 13 个隐藏集合（漏一个=永久 INVISIBLE）",
                    "fields=" + listDecl + " shown=" + shown);

            // 清空前的释放：所有 clear 点要么先 releaseAll，要么紧跟 addAll（旧引用已释放）
            boolean keepGuard = countLiteral(dy69, "keep != null && keep.contains(v)") >= 1;
            boolean oldRestored = countLiteral(dy69, "for (View v : olds)") >= 1
                    && countLiteral(dy69, "private static void releaseAll") >= 1;
            check(keepGuard && oldRestored,
                    "T-69-B3 releaseAll：新列表仍在用的不恢复、被替换掉的先 showDeep 再丢引用", "");
        }


        // ---------- T-71 v3.2.5 三班倒正向语义 / 功耗三改 / 裁剪配置 ----------
        {
            String cf71 = read(dir.resolve("Config.java"));
            String cs71 = read(dir.resolve("ConfStore.java"));
            String ct71 = read(dir.resolve("Controller.java"));
            String bm71 = read(dir.resolve("BurnInMaskService.java"));
            String rg71 = read(dir.resolve("AppRegions.java"));
            String act71 = read(dir.resolve("MainActivity.kt"));
            // dir 是 java 包目录，往上找到最近的 build.gradle 所在目录（app/）
            java.nio.file.Path appDir71 = dir;
            while (appDir71 != null && !java.nio.file.Files.exists(appDir71.resolve("build.gradle"))) {
                appDir71 = appDir71.getParent();
            }
            String gd71 = appDir71 == null ? "" : read(appDir71.resolve("build.gradle"));
            String pg71 = appDir71 == null ? "" : read(appDir71.resolve("proguard-rules.pro"));

            // A1 · 三班倒已在 v3.2.6 移除，本块只留 3.2.5 的功耗 / 裁剪 / 比例
            // A4 · 功耗：配置 TTL 15s + keyguard 3s 缓存 + 灭屏短路
            String lockBlk = slice(bm71, "private boolean isLockedCached", "return locked;");
            check(bm71.contains("CONFIG_TTL_MS = 15_000L")
                            && lockBlk.contains("now - lockedAt < 3000L")
                            && bm71.contains("locked = screenOn && isLockedCached(now)"),
                    "T-71-A4 配置 15s 一次 + keyguard 3s 缓存 + 灭屏不查 ATMS", "");
            // A5 · applyMask 同帧短路
            check(bm71.contains("private void recordDrawState(int mask")
                            && countLiteral(bm71, "recordDrawState(") >= 3
                            && bm71.contains("&& drawMask == mask"),
                    "T-71-A5 上一帧完全相同则直接 return（相位内不再每秒重建矩形）", "");
            // A6 · 前台包 3s 缓存
            check(ct71.contains("now - lastTopAt < 3000L"),
                    "T-71-A6 前台包缓存 1s→3s，与 3s tick 对齐", "");

            // A7 · 裁剪配置：release 开 minify+shrink，但不改名、模块整包 keep
            check(gd71.contains("minifyEnabled true") && gd71.contains("shrinkResources true")
                            && pg71.contains("-dontobfuscate")
                            && pg71.contains("-keep class io.github.wumisc.antiburn.** { *; }"),
                    "T-71-A7 3.48MB 裁剪口径：删未用/压缩资源，但零改名、模块类全 keep", "");
            // A8 · 比例：R 80 / B 86，T 与 C 不动
            check(rg71.contains("w * 0.80f") && rg71.contains("h * 0.86f")
                            && rg71.contains("h * 0.11f") && rg71.contains("h * 0.20f"),
                    "T-71-A8 缺口闭合比例 80/86，顶部 11% 与角落 20% 保持", "");
        }

        // ---------- T-72 v3.2.6 彻底移除状态栏三班倒 ----------
        {
            String cf72 = read(dir.resolve("Config.java"));
            String cs72 = read(dir.resolve("ConfStore.java"));
            String ct72 = read(dir.resolve("Controller.java"));
            String act72 = read(dir.resolve("MainActivity.kt"));

            // A1 · 四个文件零残留（源码 + 注释 + 文案全清）
            check(!ct72.contains("statusRound") && !ct72.contains("三班倒")
                            && !cf72.contains("enableStatusRound") && !cs72.contains("enable_status_round")
                            && !act72.contains("enableStatusRound") && !act72.contains("三班倒"),
                    "T-72-A1 三班倒代码零残留（Controller/Config/ConfStore/MainActivity）", "");

            // A2 · 三班倒专属路径一并删掉，不留悬空辅助
            check(!ct72.contains("probeLeaves") && !ct72.contains("zoneReady")
                            && !ct72.contains("lastProbe") && !ct72.contains("lastMaskSig")
                            && !ct72.contains("applyShowNavOnly"),
                    "T-72-A2 分区探测 / 轮播签名 / 只恢复导航栏 全部移除", "");

            // A3 · 状态栏回到两段周期：显示相位整条恢复
            check(ct72.contains("            if (hidden) {\n                applyShow();\n                hidden = false;\n            }"),
                    "T-72-A3 显示相位调用 applyShow，状态栏整条跟周期亮", "");

            // A4 · applyHide 不再给轮播让路
            String hideBlk72 = slice(ct72, "private static void applyHide()", "if (navView != null)");
            check(!hideBlk72.contains("statusRound") && hideBlk72.contains("if (Config.enableStatusBar) {\n                hideStatusKeepNotify();"),
                    "T-72-A4 隐藏相位直接 hideStatusKeepNotify，不再有「让路」空分支", "");

            // A5 · sideAllowed 只剩「时间 / 图标」两个开关
            check(ct72.contains("private static boolean sideAllowed(int cx, int w)")
                            && !ct72.contains("return cx > right;")
                            && ct72.contains("return cx < left ? Config.statusClockOn : Config.statusIconsOn;"),
                    "T-72-A5 左右门控回到「隐藏时间 / 隐藏右侧图标」", "");

            // A6 · 调度只按全局两段周期算
            check(!ct72.contains("seg * 2 + off"),
                    "T-72-A6 调度不再叠加三段边界", "");

            // A7 · 白名单恢复条件仍覆盖子容器残留
            check(ct72.contains("if (hidden || statusChildrenHidden())"),
                    "T-72-A7 白名单/暂停仍会清掉子容器残影", "");

            // A8 · UI：开关已删，「隐藏时间」紧跟「状态栏」
            int iBar72 = act72.indexOf("OptionRow(\"状态栏\"");
            int iClock72 = act72.indexOf("OptionRow(\"隐藏时间\"");
            check(iBar72 > 0 && iClock72 > iBar72,
                    "T-72-A8 通用页「状态栏」下方不再有该开关（隐藏时间紧邻）",
                    "bar=" + iBar72 + " clock=" + iClock72);
        }

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
