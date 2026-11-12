import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.wumisc.antiburn.Capability;
import io.github.wumisc.antiburn.ConfStore;
import io.github.wumisc.antiburn.Config;
import io.github.wumisc.antiburn.HookState;
import io.github.wumisc.antiburn.ListRules;
import io.github.wumisc.antiburn.OverlayGeometry;

/**
 * v2.6 双引擎回归测试（纯 JVM，直接跑编译产物里的真实字节码）。
 *
 * <p>覆盖四组：</p>
 * <ol>
 *   <li><b>名单判定唯一真源</b>：ListRules 与 Config.shouldIntervene 必须给出同一个答案。
 *       两边曾经各写一份 if，那次「换个引擎名单行为就反了」的隐患就是这么来的。</li>
 *   <li><b>引擎归一</b>：normEngine 的别名与未知值兜底。缺键必须落到 lsposed，
 *       否则老用户升级后会被自动切到遮盖引擎。</li>
 *   <li><b>遮盖几何</b>：刘海、横屏侧边三键、异常高度、整屏误报。这类 off-by-one
 *       在真机上极难复查，只能在这里穷举。</li>
 *   <li><b>接线检查</b>：源码与清单里那几处「少一行就完全不工作」的关键声明。</li>
 * </ol>
 */
public class EngineTest {

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

    private static Set<String> set(String... items) {
        Set<String> s = new LinkedHashSet<>();
        for (String i : items) {
            s.add(i);
        }
        return s;
    }

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0] : "/tmp/upstream/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn";
        // 从源码目录向上找 AndroidManifest.xml，比数几层 parent 稳：包路径深度一变就错
        Path moduleDir = Path.of(src);
        while (moduleDir != null && !Files.exists(moduleDir.resolve("AndroidManifest.xml"))) {
            moduleDir = moduleDir.getParent();
        }
        if (moduleDir == null) {
            System.out.println("找不到 AndroidManifest.xml，接线检查无法进行");
            System.exit(1);
        }

        System.out.println("== 1/6 名单判定唯一真源 ==");
        listRules();

        System.out.println();
        System.out.println("== 2/6 引擎归一与让位 ==");
        engineSwitch();

        System.out.println();
        System.out.println("== 3/6 遮盖几何 ==");
        geometry();

        System.out.println();
        System.out.println("== 4/6 接线检查 ==");
        wiring(src, moduleDir);

        System.out.println();
        System.out.println("== 5/6 root 与 LSPosed 探测 ==");
        probeParsing(src);

        System.out.println();
        System.out.println("== 6/6 系统界面 Hook 版本判定 ==");
        hookVersion(src);

        System.out.println();
        System.out.println("----------------------------------------------");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---- 1. 名单 ----

    private static void listRules() {
        Set<String> empty = set();
        Set<String> one = set("com.a");

        // 白名单：命中=豁免，未命中=干预，空集=全干预
        check(!ListRules.shouldIntervene("white", one, "com.a"), "白名单命中 → 豁免", "com.a");
        check(ListRules.shouldIntervene("white", one, "com.b"), "白名单未命中 → 干预", "com.b");
        check(ListRules.shouldIntervene("white", empty, "com.b"), "白名单空集 → 全干预", "");

        // 黑名单：命中=干预，未命中=豁免，空集=退回全干预
        check(ListRules.shouldIntervene("black", one, "com.a"), "黑名单命中 → 干预", "com.a");
        check(!ListRules.shouldIntervene("black", one, "com.b"), "黑名单未命中 → 豁免", "com.b");
        check(ListRules.shouldIntervene("black", empty, "com.b"), "黑名单空集 → 退回全干预", "防误判为失效");

        // off：忽略名单
        check(ListRules.shouldIntervene("off", one, "com.a"), "off → 忽略名单照常干预", "com.a");
        check(ListRules.shouldIntervene("off", empty, "com.b"), "off + 空集 → 仍干预", "com.b");

        // 前台未知
        check(ListRules.shouldIntervene("white", one, null), "前台未知 + 白名单 → 保守干预", "null pkg");
        check(!ListRules.shouldIntervene("black", one, null), "前台未知 + 黑名单 → 不干预", "null pkg");

        // 非法模式按白名单解释
        check(!ListRules.shouldIntervene("WHATEVER", one, "com.a"), "非法模式 → 按白名单", "com.a");

        // 总开关
        check(!ListRules.shouldIntervene("white", empty, false, "com.b"), "总开关关闭 → 不干预", "masterOn=false");
        check(ListRules.shouldIntervene("white", empty, true, "com.b"), "总开关开启 → 按名单", "masterOn=true");

        // 与 Config.shouldIntervene 一致（唯一真源的证明）
        String[][ ] cases = {
                {"white", "com.a"}, {"white", "com.b"}, {"black", "com.a"}, {"black", "com.b"},
                {"off", "com.a"}, {"off", "com.b"},
        };
        boolean same = true;
        StringBuilder diff = new StringBuilder();
        for (String[] c : cases) {
            boolean a = ListRules.shouldIntervene(c[0], one, c[1]);
            boolean b = intervene(c[0], one, c[1]);
            if (a != b) {
                same = false;
                diff.append(c[0]).append('/').append(c[1]).append(":rules=").append(a).append(",config=").append(b).append(' ');
            }
        }
        check(same, "ListRules 与 Config.shouldIntervene 结论一致", diff.length() == 0 ? "6 组组合" : diff.toString());

        check(ListRules.isFallbackAll("black", empty), "空黑名单被标为「兜底全干预」", "供 UI 解释");
        check(ListRules.isFallbackAll("off", one), "off 模式被标为「兜底全干预」", "");
        check(!ListRules.isFallbackAll("white", one), "非空白名单不是兜底", "");
    }

    private static boolean intervene(String mode, Set<String> list, String pkg) {
        Config.engine = ConfStore.ENGINE_LSPOSED;
        Config.enableStatus = true;
        Config.listMode = mode;
        Config.list = list;
        return Config.shouldIntervene(pkg);
    }

    // ---- 2. 引擎 ----

    private static void engineSwitch() {
        check(ConfStore.ENGINE_LSPOSED.equals(ConfStore.normEngine(null)), "缺键 → lsposed", "向后兼容");
        check(ConfStore.ENGINE_LSPOSED.equals(ConfStore.normEngine("")), "空串 → lsposed", "");
        check(ConfStore.ENGINE_OVERLAY.equals(ConfStore.normEngine("overlay")), "overlay 原样", "");
        check(ConfStore.ENGINE_OVERLAY.equals(ConfStore.normEngine(" A11Y ")), "别名 a11y → overlay", "含空格大写");
        check(ConfStore.ENGINE_OVERLAY.equals(ConfStore.normEngine("accessibility")), "别名 accessibility", "");
        check(ConfStore.ENGINE_OVERLAY.equals(ConfStore.normEngine("无障碍")), "中文「无障碍」", "");
        check(ConfStore.ENGINE_LSPOSED.equals(ConfStore.normEngine("HOOK")), "未知值 → lsposed", "不猜成 overlay");

        // engine=overlay 时 Hook 必须让位
        Config.engine = ConfStore.ENGINE_OVERLAY;
        Config.enableStatus = true;
        Config.listMode = ConfStore.MODE_OFF;
        Config.list = set();
        check(!Config.shouldIntervene("com.any"), "overlay 引擎下 Hook 不干预", "避免双重处理");

        // 切回 lsposed 立刻恢复
        Config.engine = ConfStore.ENGINE_LSPOSED;
        check(Config.shouldIntervene("com.any"), "切回 lsposed 后 Hook 恢复", "");

        // 默认配置必须写出 engine 键，否则界面改了也无处可读
        String def = ConfStore.defaultContent();
        check(def.contains("engine=lsposed"), "默认配置含 engine=lsposed", "");
        // 这两项刻意不落盘：shizuku_assist 没有可靠触发点（App 不在前台就无从维护），
        // shizuku_hide_bar 一旦持久化，用户卸载后状态栏就再也回不来。留字段=留死代码。
        check(!def.contains("shizuku_assist"), "不再写 shizuku_assist", "无消费方的字段不留");
        check(!def.contains("shizuku_hide_bar"), "不再写 shizuku_hide_bar", "改为不落盘的手动动作");
    }

    // ---- 3. 几何 ----

    private static void geometry() {
        OverlayGeometry.Rect r;

        r = OverlayGeometry.statusBar(1080, 2400, 80);
        check(r != null && r.top == 0 && r.bottom == 80 && r.width() == 1080, "状态栏 80px 全宽贴顶", String.valueOf(r));

        // 异常高度：超过屏幕 1/4 说明取到的不是状态栏，必须夹住，否则会把主要内容涂黑
        r = OverlayGeometry.statusBar(1080, 2400, 900);
        check(r != null && r.height() == 600, "状态栏异常高度被夹到屏幕 1/4", String.valueOf(r));

        check(OverlayGeometry.statusBar(1080, 2400, 0) == null, "高度为 0 → 不遮", "null");

        r = OverlayGeometry.navigationBar(1080, 2400, 48, OverlayGeometry.NAV_BOTTOM);
        check(r != null && r.top == 2352 && r.bottom == 2400, "竖屏底部导航栏", String.valueOf(r));

        r = OverlayGeometry.navigationBar(2400, 1080, 48, OverlayGeometry.NAV_RIGHT);
        check(r != null && r.left == 2352 && r.right == 2400 && r.height() == 1080, "横屏右侧三键", String.valueOf(r));

        r = OverlayGeometry.navigationBar(2400, 1080, 48, OverlayGeometry.NAV_LEFT);
        check(r != null && r.left == 0 && r.right == 48, "横屏左侧（少数 ROM）", String.valueOf(r));

        r = OverlayGeometry.navigationBar(1080, 2400, 5000, OverlayGeometry.NAV_BOTTOM);
        check(r != null && r.height() == 800, "导航栏异常厚度被夹到屏幕 1/3", String.valueOf(r));

        // 系统上报：正常状态栏
        r = OverlayGeometry.fromSystemBounds(1080, 2400, 0, 0, 1080, 120);
        check(r != null && r.height() == 120, "系统上报状态栏被采用", String.valueOf(r));

        // 系统上报：整屏（有 ROM 会把应用窗口当系统栏报上来）
        check(OverlayGeometry.fromSystemBounds(1080, 2400, 0, 0, 1080, 2400) == null,
                "系统上报整屏 → 拒绝采用", "防止涂黑主要内容");
        check(OverlayGeometry.fromSystemBounds(1080, 2400, -50, -50, 1200, 2500) == null,
                "越界上报 → 拒绝", "夹紧后仍过大");

        // navSideOf 判位
        check(OverlayGeometry.navSideOf(1080, 2400, 0, 2330, 1080, 2400) == OverlayGeometry.NAV_BOTTOM,
                "底部条判为 NAV_BOTTOM", "");
        check(OverlayGeometry.navSideOf(2400, 1080, 2350, 0, 2400, 1080) == OverlayGeometry.NAV_RIGHT,
                "右侧条判为 NAV_RIGHT", "");
        check(OverlayGeometry.navSideOf(1080, 2400, 0, 900, 1080, 1400) == -1,
                "中间区域判不出侧边", "返回 -1");

        // 空矩形判定
        check(new OverlayGeometry.Rect(0, 0, 2, 2).isEmpty(), "过小区域判为空", "1px 线不算生效");
        check(!new OverlayGeometry.Rect(0, 0, 1080, 80).isEmpty(), "正常区域非空", "");
    }

    // ---- 4. 接线 ----

    private static void wiring(String src, Path moduleDir) throws Exception {
        String svc = read(src + "/BurnInMaskService.java");
        check(svc.contains("TYPE_ACCESSIBILITY_OVERLAY"), "用无障碍覆盖层窗口类型", "唯一能盖住系统栏的合法途径");
        check(svc.contains("FLAG_NOT_TOUCHABLE"), "窗口不可触摸", "漏了会导致整屏触摸被吞");
        check(svc.contains("FLAG_LAYOUT_IN_SCREEN"), "窗口延伸到状态栏区域", "漏了盖不到状态栏");
        check(svc.contains("setFitInsetsTypes(0)"), "不吃 inset", "Android 11+ 必需");
        check(svc.contains("LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS"), "允许覆盖刘海区", "");
        check(svc.contains("isInteractive()"), "灭屏停手", "省电 + 避免锁屏黑边");
        check(svc.contains("isKeyguardLocked()"), "锁屏停手", "");
        check(svc.contains("maskAllowed"), "遮盖引擎尊重 engine 配置", "防止与 Hook 双重处理");

        String bridge = read(src + "/ShizukuBridge.java");
        check(bridge.contains("getDeclaredMethod") && bridge.contains("setAccessible"),
                "Shizuku newProcess 走反射", "13.x 已改为 private");
        check(bridge.contains("grep -v -F"), "关闭无障碍时精确剔除自己", "不用 sed，避免正则误伤");
        check(bridge.contains("restoreStatusBar"), "保留状态栏恢复入口", "实验能力必须有回退");

        String cap = read(src + "/Capability.java");
        check(cap.contains("/data/adb/lspd"), "LSPosed 探测路径", "");
        check(cap.contains("ENABLED_ACCESSIBILITY_SERVICES"), "无障碍状态读 Secure 设置", "");
        check(cap.contains("execSuCapture(rootProbeScript()"),
                "LSPosed 痕迹由 su 读取", "/data/adb 是 0700，应用进程 stat 不到");
        check(!cap.contains("private static boolean lsposedPresent(boolean root)"),
                "不再用 File.exists 判 /data/adb", "v2.6.0 的误判源");
        check(cap.contains("ROOT_DENIED"), "root 分三态", "未授权不能报成「无 root」");
        check(cap.contains("invalidate()"), "探测结果可作废", "重新检测的前提");

        String main = read(src + "/MainActivity.kt");
        check(main.contains("EngineCard("), "首页有运行方式卡片", "");
        check(main.contains("engineCard()"), "卡片被渲染到首页", "定义不等于展示");
        check(main.contains("改用它"), "无 root 时给一键切换", "避免只报错不给出路");
        check(main.contains("ConfStore.ENGINE_OVERLAY"), "引擎常量与配置层共用", "");
        check(main.contains("A11yRequiredNotice"), "无障碍卡片下有「关闭也要开无障碍」提示", "");
        check(main.contains("如果要关闭该功能，必须开启无障碍功能，然后关闭下方总开关"),
                "关闭提示与要求逐字一致", "「然后关闭下方总开关」是操作顺序，不能省");
        check(main.contains("UiPalette.warn(dark)"), "关闭提示用橙色警示色", "不能混用代表可用的雾青");
        check(main.contains("Capability.invalidate()"), "重新检测会作废缓存", "");
        check(main.contains("HookStaleNotice("), "首页有「系统界面是旧版」提示块", "");
        check(main.contains("HookState.invalidate()"), "重新检测会作废 Hook 版本缓存", "");
        check(main.contains("if (probed.rootState == Capability.ROOT_GRANTED) HookState.probe(context) else null"),
                "root 未授权时不探 Hook 版本", "否则首屏会白等 8 秒 su 超时");

        String manifest = read(moduleDir.resolve("AndroidManifest.xml").toString());
        check(manifest.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"), "清单声明无障碍服务权限", "");
        check(manifest.contains("android.accessibilityservice.AccessibilityService"), "服务带无障碍 intent-filter", "");
        check(manifest.contains("@xml/accessibility_service_config"), "服务挂配置 xml", "");
        check(manifest.contains("rikka.shizuku.ShizukuProvider"), "注册 Shizuku binder 中继", "缺了拿不到授权");
        check(manifest.contains("moe.shizuku.privileged.api"), "queries 声明 Shizuku 包", "Android 11+ 必需");

        String xml = read(moduleDir.resolve("res/xml/accessibility_service_config.xml").toString());
        check(xml.contains("flagRetrieveInteractiveWindows"), "声明可读交互窗口", "getWindows() 生效前提");
        check(xml.contains("canRetrieveWindowContent=\"true\""), "声明可读窗口内容", "");

        // v2.6.5：快手 / 哔哩哔哩只做「适配计划占位」，不得偷偷扩 Hook 作用域，
        // 也不能放假开关。守住这两条，占位才不会变成「装了没效果」。
        String scope = read(moduleDir.resolve("res/values/arrays.xml").toString());
        check(!scope.contains("com.smile.gifmaker") && !scope.contains("tv.danmaku.bili"),
                "适配计划占位不扩 xposed_scope（快手/B站未加入）", "");
        check(main.contains("RoadmapCard(dark)") && main.contains("快手") && main.contains("哔哩哔哩"),
                "功能区有适配计划占位卡片并点名两个应用", "");
    }

    /**
     * root / LSPosed 探测的解析逻辑。
     *
     * <p>这一组是 v2.6.1 那次误判的守门测试。上一版把 {@code new File("/data/adb/lspd").exists()}
     * 当成「装了 LSPosed」的证据，而 {@code /data/adb} 是 {@code 0700 root:root}，
     * 应用进程无论有没有 root 都读不到 —— 结果是有 root 的机器一律显示「没装 LSPosed」。
     * 现在改为用 su 列目录，解析结果必须能被这里的样例钉住。</p>
     */
    private static void probeParsing(String src) throws Exception {
        String sep = "==AB==";

        check(Capability.parseUid("0\n" + sep + "\n/data/adb/lspd\n") == Capability.ROOT_GRANTED,
                "id -u 输出 0 判为已授权", "");
        check(Capability.parseUid("2000\n" + sep + "\n") == Capability.ROOT_NONE,
                "id -u 输出 2000 判为无 root", "");
        check(Capability.parseUid("") == Capability.ROOT_NONE, "空输出判为无 root", "");
        check(Capability.parseUid("uid=0(root) gid=0(root)") == Capability.ROOT_NONE,
                "id 的完整输出不误判", "只认纯数字行");
        check(Capability.parseUid("0") == Capability.ROOT_GRANTED, "无分段也能解析", "");

        check(Capability.parseLsposed(sep + "\n/data/adb/modules/zygisk_lsposed\n" + sep + "\nend\n"),
                "zygisk_lsposed 目录命中", "");
        check(Capability.parseLsposed(sep + "\n/data/adb/modules/lsposed_zygisk\n" + sep + "\nend\n"),
                "JingMatrix 的目录名命中", "模块 ID 各分支不统一");
        check(Capability.parseLsposed(sep + "\n/data/adb/lspd\n" + sep + "\nend\n"),
                "lspd 运行时目录命中", "");
        check(Capability.parseLsposed(sep + "\n/data/app/1/org.lsposed.manager-abc==\n" + sep + "\nend\n"),
                "Manager 包命中", "非寄生模式");
        check(!Capability.parseLsposed(sep + "\n/data/adb/modules/busybox\n" + sep + "\nend\n"),
                "无关模块不误判", "");
        check(!Capability.parseLsposed(sep + "\n" + sep + "\nend\n"), "空痕迹判为未安装", "");
        check(!Capability.parseLsposed(""), "无分段输出判为未安装", "");

        String script = Capability.rootProbeScript();
        check(script.contains("/data/adb/modules/*lsp*"), "通配兜住各分支模块名", "");
        check(script.contains("module.prop"), "模块 ID 被改过时读 module.prop 兜底", "");
        check(script.split("==AB==", -1).length >= 3, "输出分段，避免多行混淆", "");

        // 反面固定：探测脚本里不能出现「靠 File.exists 判 /data/adb」的写法
        String cap = read(src + "/Capability.java");
        int lspdField = cap.indexOf("/data/adb/lspd");
        check(lspdField > 0 && !cap.substring(0, lspdField).contains("new File(\"/data/adb/lspd\").exists()"),
                "源码不再直接 stat /data/adb", "");
    }

    /**
     * 系统界面 Hook 版本判定。
     *
     * <p>这组守的是「装了新版但没生效」这个坑：模块的 Hook 跑在系统界面进程里，装包
     * 不会重启它。判定方式是纯时间比较（进程启动时刻 vs APK 安装时刻），所以解析和
     * 比较都必须钉死 —— 判错成「已是最新」会让用户继续在坏状态里打转，判错成「旧版」
     * 会让他反复重启系统界面。</p>
     */
    private static void hookVersion(String src) throws Exception {
        String sep = HookState.SEP;

        long[] one = HookState.parse(sep + "\n1234 1794476500\n" + sep + "\nab-hs-done");
        check(one != null && one[0] == 1234L && one[1] == 1794476500L,
                "单行 pid + 启动时刻可解析", "");

        // 子进程由主进程 fork，启动更晚；必须取最早的那个判「这版有没有被加载」
        long[] two = HookState.parse(sep + "\n2400 1794476600\n1200 1794476500\n" + sep);
        check(two != null && two[0] == 1200L && two[1] == 1794476500L,
                "多进程取启动最早的主进程", "");

        check(HookState.parse("") == null, "空输出判为探不到", "");
        check(HookState.parse(null) == null, "null 判为探不到", "");
        check(HookState.parse(sep + "\n" + sep + "\nab-hs-done") == null,
                "只有分段标记时判为探不到", "没匹配到系统界面进程");
        check(HookState.parse("==HS==\nnotapid 1794476500\n==HS==") == null,
                "非数字行不参与解析", "");
        check(HookState.parse("==HS==\n555\n==HS==") == null,
                "缺启动时刻的行不参与解析", "");

        long apk = 1_800_000_000_000L;
        check(HookState.decide(apk, apk - 600_000L) == HookState.STALE,
                "进程比 APK 早 10 分钟 → 旧版", "");
        check(HookState.decide(apk, apk + 60_000L) == HookState.FRESH,
                "进程比 APK 晚 1 分钟 → 新版", "");
        check(HookState.decide(apk, apk + 2_000L) == HookState.FRESH,
                "容差内（装完 2 秒重启）不误报旧版", "");
        check(HookState.decide(apk, apk - 2_000L) == HookState.FRESH,
                "容差内（进程早 2 秒）不误报旧版", "");
        check(HookState.decide(0L, apk) == HookState.UNKNOWN, "读不到安装时间 → 不判定", "");
        check(HookState.decide(apk, 0L) == HookState.UNKNOWN, "读不到进程时间 → 不判定", "");

        String script = HookState.probeScript();
        check(script.contains("com\\.android\\.systemui"), "按包名找系统界面进程", "");
        check(script.contains("stat -c %Y /proc/"), "用 /proc 目录时间当启动时刻", "");
        check(script.contains("ab-hs-done"), "脚本有完成标记，便于区分「没杀到」与「没执行」", "");

        String hook = read(src + "/HookState.java");
        check(hook.contains("kill -9"), "重启用 kill -9", "TERM 可能被系统界面拦住不退出");
        // 注释里提到 "logcat" 是解释，不算违规；要拦的是真的去执行它
        check(!hook.contains("logcat -"), "判定不依赖 logcat 命令", "用户不开日志就失效");

        String main = read(src + "/MainActivity.kt");
        check(main.contains("系统界面里跑的还是旧版"), "提示文案直指原因", "");
        check(main.contains("重启系统界面"), "提示里带一键重启", "只告诉原因不给按钮等于把事推回给用户");
    }

    private static String read(String p) throws Exception {
        return Files.readString(Path.of(p));
    }
}
