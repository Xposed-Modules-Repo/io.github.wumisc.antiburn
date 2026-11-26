package io.github.wumisc.antiburn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯逻辑靶场：这些类不碰 Android，直接在 JVM 里跑，期望值全部来自各自的注释契约。
 *
 * <p>为什么单独一套：FeatureAuditTest 那 700 多条守的是「源码里有没有这句话」，
 * 但几何 / 名单 / 门控这类东西的真 bug 是「算错了」，源码在、结果不对 ——
 * 只有真的把函数调一遍才发现。典型就是 statusBar 明明注释写「超过 1/4 是错值」，
 * 实现却把错值夹到 1/4 继续涂黑。</p>
 */
public class PureLogicTest {

    // ---------------------------------------------------------------- 几何

    @Test
    public void statusBarRejectsImpossibleHeightInsteadOfPaintingQuarterScreen() {
        // 正常值
        OverlayGeometry.Rect ok = OverlayGeometry.statusBar(1080, 2400, 120);
        assertNotNull(ok);
        assertEquals(0, ok.left);
        assertEquals(1080, ok.right);
        assertEquals(120, ok.height());

        // 取不到高度：不画
        assertNull(OverlayGeometry.statusBar(1080, 2400, 0));
        // 分屏 / 折叠态把整窗当状态栏报：必须是 null，不能夹成 600px 继续涂黑
        assertNull(OverlayGeometry.statusBar(1080, 2400, 2400));
        assertNull(OverlayGeometry.statusBar(1080, 2400, 601));
        // 恰好 1/4 仍算可疑但边界值允许
        assertNotNull(OverlayGeometry.statusBar(1080, 2400, 600));
        // 异常屏幕值
        assertNull(OverlayGeometry.statusBar(0, 2400, 120));
    }

    @Test
    public void navigationBarGeometryForThreeSides() {
        OverlayGeometry.Rect bottom = OverlayGeometry.navigationBar(1080, 2400, 144, OverlayGeometry.NAV_BOTTOM);
        assertNotNull(bottom);
        assertEquals(2400 - 144, bottom.top);
        assertEquals(2400, bottom.bottom);

        OverlayGeometry.Rect right = OverlayGeometry.navigationBar(2400, 1080, 96, OverlayGeometry.NAV_RIGHT);
        assertNotNull(right);
        assertEquals(2400 - 96, right.left);
        assertEquals(1080, right.height());

        OverlayGeometry.Rect left = OverlayGeometry.navigationBar(2400, 1080, 96, OverlayGeometry.NAV_LEFT);
        assertNotNull(left);
        assertEquals(0, left.left);
        assertEquals(96, left.width());

        // 离谱厚度：判为不可用，不再夹成 1/3 屏涂黑
        assertNull(OverlayGeometry.navigationBar(1080, 2400, 2400, OverlayGeometry.NAV_BOTTOM));
        assertNull(OverlayGeometry.navigationBar(2400, 1080, 2400, OverlayGeometry.NAV_RIGHT));
        // 0 / 负值
        assertNull(OverlayGeometry.navigationBar(1080, 2400, 0, OverlayGeometry.NAV_BOTTOM));
    }

    @Test
    public void systemBoundsValidation() {
        // 顶部状态栏：整宽 + 薄 → 接受
        OverlayGeometry.Rect s = OverlayGeometry.fromSystemBounds(1080, 2400, 0, 0, 1080, 120);
        assertNotNull(s);
        // 横屏右侧三键：整高 + 薄 → 接受
        assertNotNull(OverlayGeometry.fromSystemBounds(2400, 1080, 2304, 0, 2400, 1080));
        // 整个应用窗口被误报成系统栏 → 拒绝
        assertNull(OverlayGeometry.fromSystemBounds(1080, 2400, 0, 120, 1080, 2280));
        // 越界坐标被夹进屏幕，仍合法
        OverlayGeometry.Rect clamped = OverlayGeometry.fromSystemBounds(1080, 2400, -50, -50, 1200, 120);
        assertNotNull(clamped);
        assertEquals(0, clamped.left);
        assertEquals(1080, clamped.right);
        // 退化矩形
        assertNull(OverlayGeometry.fromSystemBounds(1080, 2400, 500, 500, 500, 500));
        // 屏幕值非法
        assertNull(OverlayGeometry.fromSystemBounds(0, 0, 0, 0, 10, 10));
    }

    @Test
    public void navSideDetection() {
        assertEquals(OverlayGeometry.NAV_BOTTOM, OverlayGeometry.navSideOf(1080, 2400, 0, 2256, 1080, 2400));
        assertEquals(OverlayGeometry.NAV_RIGHT, OverlayGeometry.navSideOf(2400, 1080, 2304, 0, 2400, 1080));
        assertEquals(OverlayGeometry.NAV_LEFT, OverlayGeometry.navSideOf(2400, 1080, 0, 0, 96, 1080));
        assertEquals(-1, OverlayGeometry.navSideOf(2400, 1080, 1200, 500, 1300, 600));
        assertEquals(-1, OverlayGeometry.navSideOf(0, 0, 0, 0, 0, 0));
    }

    // ---------------------------------------------------------------- 应用内区域

    @Test
    public void appRegionRectanglesStayInsideScreen() {
        List<int[]> all = AppRegions.rects(1080, 2400, 120, AppRegions.mask(true, true, true, true));
        assertEquals("R + T + B + C(两块) = 5 块", 5, all.size());
        for (int[] r : all) {
            assertTrue(r[0] >= 0 && r[1] >= 0);
            assertTrue("right 不能超过屏宽：" + Arrays.toString(r), r[2] <= 1080);
            assertTrue("bottom 不能超过屏高：" + Arrays.toString(r), r[3] <= 2400);
            assertTrue("不能是空矩形：" + Arrays.toString(r), r[2] > r[0] && r[3] > r[1]);
        }
    }

    @Test
    public void appRegionTopRespectsInsetAndClamps() {
        int[] top = AppRegions.rects(1080, 2400, 120, AppRegions.T).get(0);
        assertEquals("顶部区必须从状态栏下沿开始", 120, top[1]);
        // 异常 inset（超过 1/4 屏）被夹住，不会把整块顶部吃掉
        int[] weird = AppRegions.rects(1080, 2400, 2400, AppRegions.T).get(0);
        assertTrue(weird[1] <= 600);
    }

    @Test
    public void appRegionMaskFailsSafely() {
        assertTrue(AppRegions.rects(0, 2400, 0, AppRegions.R).isEmpty());
        assertTrue(AppRegions.rects(1080, 0, 0, AppRegions.R).isEmpty());
        assertTrue(AppRegions.rects(1080, 2400, 0, 0).isEmpty());
        assertEquals("RTBC", AppRegions.label(AppRegions.mask(true, true, true, true)));
        assertEquals("----", AppRegions.label(0));
    }

    @Test
    public void appRegionSameComparesPixels() {
        // 带 T 才对 inset 敏感：R/B 两块与顶部让出高度无关，只换 topInset 必须仍然等价
        List<int[]> a = AppRegions.rects(1080, 2400, 120, AppRegions.R | AppRegions.T | AppRegions.B);
        List<int[]> b = AppRegions.rects(1080, 2400, 120, AppRegions.R | AppRegions.T | AppRegions.B);
        assertTrue(AppRegions.same(a, b));
        assertFalse(AppRegions.same(a, AppRegions.rects(1080, 2400, 121, AppRegions.R | AppRegions.T | AppRegions.B)));
        assertTrue("R/B 与顶部让出高度无关", AppRegions.same(
                AppRegions.rects(1080, 2400, 120, AppRegions.R | AppRegions.B),
                AppRegions.rects(1080, 2400, 240, AppRegions.R | AppRegions.B)));
        assertFalse(AppRegions.same(a, null));
        assertTrue(AppRegions.same(null, null));
    }

    // ---------------------------------------------------------------- 名单

    @Test
    public void whitelistSemantics() {
        Set<String> wl = new HashSet<>(Arrays.asList("com.ss.android.ugc.aweme"));
        assertFalse("白名单命中 = 豁免", ListRules.shouldIntervene("white", wl, "com.ss.android.ugc.aweme"));
        assertTrue("白名单未命中 = 干预", ListRules.shouldIntervene("white", wl, "com.other.app"));
        assertTrue("白名单空 = 全部干预", ListRules.shouldIntervene("white", new HashSet<>(), "com.other.app"));
        assertTrue("不知道前台是谁时按干预（保护优先）", ListRules.shouldIntervene("white", wl, null));
    }

    @Test
    public void blacklistSemantics() {
        Set<String> bl = new HashSet<>(Arrays.asList("com.tencent.qqlive"));
        assertTrue("黑名单命中 = 干预", ListRules.shouldIntervene("black", bl, "com.tencent.qqlive"));
        assertFalse("黑名单未命中 = 豁免", ListRules.shouldIntervene("black", bl, "com.other.app"));
        assertTrue("黑名单空 = 退回全部干预（不能看起来像坏了）",
                ListRules.shouldIntervene("black", new HashSet<>(), "com.other.app"));
        assertTrue("黑名单 null 视为空集", ListRules.shouldIntervene("black", null, "com.other.app"));
    }

    @Test
    public void offModeAndMasterSwitch() {
        assertTrue("off = 忽略名单", ListRules.shouldIntervene("off", new HashSet<>(), "com.any"));
        assertTrue("非法模式归一到 white", ListRules.shouldIntervene("???", new HashSet<>(), "com.any"));
        assertFalse("总开关关闭一律不干预",
                ListRules.shouldIntervene("off", new HashSet<>(), false, "com.any"));
        assertTrue(ListRules.isFallbackAll("white", new HashSet<>()));
        assertFalse(ListRules.isFallbackAll("white", new HashSet<>(Arrays.asList("a"))));
    }

    // ---------------------------------------------------------------- 直播门控

    @Test
    public void liveGateNeedsTwoHitsAndThreeMisses() {
        ThhLiveGate g = new ThhLiveGate();
        assertFalse(g.feed(true, 1000L));
        assertTrue("第二次命中才置稳", g.feed(true, 1600L));
        assertTrue("单帧丢失不退出", g.feed(false, 2200L));
        assertTrue("第二帧丢失仍放行（EXIT_FRAMES=3）", g.feed(false, 2800L));
        assertFalse("连续三帧丢失才退出", g.feed(false, 3400L));
    }

    @Test
    public void liveGateIgnoresStormInsideSampleWindow() {
        ThhLiveGate g = new ThhLiveGate();
        g.feed(true, 1000L);
        assertFalse("500ms 内的重复调用不计数", g.feed(true, 1100L));
        assertFalse(g.feed(true, 1300L));
        assertTrue("越过窗口后的第二次命中才生效", g.feed(true, 1600L));
    }

    @Test
    public void liveGateResetsOnLongGap() {
        ThhLiveGate g = new ThhLiveGate();
        g.feed(true, 1000L);
        // 隔 5 秒再来命中：旧计数作废，需要重新凑两次
        assertFalse(g.feed(true, 6000L));
        assertTrue(g.feed(true, 6600L));
    }

    @Test
    public void liveGateResetClearsEverything() {
        ThhLiveGate g = new ThhLiveGate();
        g.feed(true, 1000L);
        g.feed(true, 1600L);
        assertTrue(g.stable());
        g.reset();
        assertFalse(g.stable());
        assertFalse("复位后一次命中不能直接稳住", g.feed(true, 2000L));
    }

    // ---------------------------------------------------------------- 直播暂停信号

    @Test
    public void livePauseSignalRoundTrip() {
        String text = LivePause.encode("com.ss.android.ugc.aweme", true, 1000L);
        assertTrue(LivePause.isActive(text, "com.ss.android.ugc.aweme", 1000L));
        assertTrue("15 秒内仍有效", LivePause.isActive(text, "com.ss.android.ugc.aweme", 15000L));
        assertFalse("超过 TTL 失效", LivePause.isActive(text, "com.ss.android.ugc.aweme", 16001L));
        assertFalse("前台换了就不算", LivePause.isActive(text, "com.other.app", 1000L));
        assertFalse("未来时间戳不可信", LivePause.isActive(text, "com.ss.android.ugc.aweme", 999L));

        // active=false 写 0：立刻撤销
        String off = LivePause.encode("com.ss.android.ugc.aweme", false, 2000L);
        assertFalse(LivePause.isActive(off, "com.ss.android.ugc.aweme", 2000L));

        // 不支持的应用 / 脏数据
        assertFalse(LivePause.isActive(text, "com.tencent.qqlive", 1000L));
        assertFalse(LivePause.isActive("", "com.ss.android.ugc.aweme", 1000L));
        assertFalse(LivePause.isActive("garbage", "com.ss.android.ugc.aweme", 1000L));
        assertFalse(LivePause.isActive(null, "com.ss.android.ugc.aweme", 1000L));
    }

    // ---------------------------------------------------------- API 102 契约 / 自换代码

    @Test
    public void modulePropFollowsApi102Contract() throws Exception {
        java.io.File prop = new java.io.File("src/main/resources/META-INF/xposed/module.prop");
        assertTrue("module.prop 必须存在", prop.isFile());
        java.util.Properties p = new java.util.Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(prop)) {
            p.load(in);
        }
        assertEquals("minApiVersion 必须 >= 102（我们用了 API102 的 autoHotReload/onHotReloading）",
                "102", p.getProperty("minApiVersion", "").trim());
        assertEquals("102", p.getProperty("targetApiVersion", "").trim());
        assertEquals("覆盖安装自动热重载开关必须打开", "true",
                p.getProperty("autoHotReload", "").trim());
        assertTrue("staticScope 必须显式声明", p.getProperty("staticScope", "").trim().length() > 0);

        java.io.File init = new java.io.File("src/main/resources/META-INF/xposed/java_init.list");
        assertTrue("java_init.list 必须存在", init.isFile());
        java.util.List<String> entries = new java.util.ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(init.toPath())) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                entries.add(t);
            }
        }
        assertEquals("API102 规则：热重载只支持恰好一个 Java 入口类", 1, entries.size());
        Class.forName(entries.get(0));
    }

    @Test
    public void selfReloadDecisionCoversAllBranches() {
        long t1 = 1000L;
        assertTrue("从未记录基线、APK 已更新 → 要换",
                SelfReload.needsReload(0L, "", t1, "/a.apk", 0L));
        assertFalse("同一版、同一路径 → 不换",
                SelfReload.needsReload(t1, "/a.apk", t1, "/a.apk", 0L));
        assertTrue("路径变了（重装到别的目录）→ 要换",
                SelfReload.needsReload(t1, "/old.apk", t1, "/new.apk", 0L));
        assertFalse("框架已热重载过这一版 → 模块不再重复装",
                SelfReload.needsReload(0L, "", t1, "/a.apk", t1));
        assertTrue("框架重载的是上一版，新版本仍要自己换",
                SelfReload.needsReload(t1, "/a.apk", t1 + 1, "/a.apk", t1));
        assertFalse("安装时间为 0 / 路径为空 → 不动",
                SelfReload.needsReload(0L, "", 0L, "/a.apk", 0L));
        assertFalse("路径为空 → 不动",
                SelfReload.needsReload(0L, "", t1, "", 0L));
    }
}
