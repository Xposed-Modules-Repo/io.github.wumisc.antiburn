package io.github.wumisc.antiburn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * v3.2.30 沙盒：四大视频 App 的路由 / 区域开关 / 通用设置语义。
 *
 * <p>这里的每一条都直接调真实生产代码，不是 grep 源码。重点钉住两类容易「看着对、实际错」的东西：</p>
 * <ul>
 *   <li>包名路由：抖音 / 红果 / 快手 / B站 的渠道包与变体必须归到正确 Kind，否则
 *       {@code XposedInit} 会把 B站当抖音、把红果当抖音，用错开关；</li>
 *   <li>设置语义：名单 white/black/off、引擎 lsposed/overlay、总开关和自身豁免必须与 Hook 侧一致。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 36, application = android.app.Application.class)
public class VideoSettingsSandboxTest {

    private boolean oldStatus;
    private String oldMode;
    private String oldEngine;
    private Set<String> oldList;

    @Before
    public void saveConfig() {
        oldStatus = Config.enableStatus;
        oldMode = Config.listMode;
        oldEngine = Config.engine;
        oldList = new HashSet<>(Config.list);
    }

    @After
    public void restoreConfig() {
        Config.enableStatus = oldStatus;
        Config.listMode = oldMode;
        Config.engine = oldEngine;
        Config.list = new HashSet<>(oldList);
    }

    // ------------------------------------------------------------------ 四大视频路由

    @Test
    public void routesDouyinMainAndChannelVariants() {
        assertEquals(VideoApps.Kind.DOUYIN, VideoApps.of("com.ss.android.ugc.aweme"));
        assertEquals(VideoApps.Kind.DOUYIN, VideoApps.of("com.ss.android.ugc.aweme.mobile"));
        assertEquals(VideoApps.Kind.DOUYIN, VideoApps.of("com.ss.android.ugc.aweme.lite"));
        assertEquals("douyin", VideoApps.label(VideoApps.Kind.DOUYIN));
    }

    @Test
    public void routesHongguoDomesticAndOversea() {
        assertEquals(VideoApps.Kind.DRAMA, VideoApps.of("com.phoenix.read"));
        assertEquals(VideoApps.Kind.DRAMA, VideoApps.of("com.phoenix.read.oversea.gp"));
        assertEquals("drama", VideoApps.label(VideoApps.Kind.DRAMA));
    }

    @Test
    public void routesKuaishouMainAndNebula() {
        assertEquals(VideoApps.Kind.KUAISHOU, VideoApps.of("com.smile.gifmaker"));
        assertEquals(VideoApps.Kind.KUAISHOU, VideoApps.of("com.kuaishou.nebula"));
        assertEquals("kuaishou", VideoApps.label(VideoApps.Kind.KUAISHOU));
    }

    @Test
    public void routesBilibiliAllKnownVariants() {
        assertEquals(VideoApps.Kind.BILIBILI, VideoApps.of("tv.danmaku.bili"));
        assertEquals(VideoApps.Kind.BILIBILI, VideoApps.of("tv.danmaku.bilibilihd"));
        assertEquals(VideoApps.Kind.BILIBILI, VideoApps.of("com.bilibili.app.in"));
        assertEquals(VideoApps.Kind.BILIBILI, VideoApps.of("com.bilibili.app.blue"));
        assertEquals(VideoApps.Kind.BILIBILI, VideoApps.of("com.bilibili.intl"));
        assertEquals("bilibili", VideoApps.label(VideoApps.Kind.BILIBILI));
    }

    @Test
    public void rejectsNonTargetPackagesAndEmptyNames() {
        assertEquals(VideoApps.Kind.NONE, VideoApps.of(null));
        assertEquals(VideoApps.Kind.NONE, VideoApps.of(""));
        assertEquals(VideoApps.Kind.NONE, VideoApps.of("com.tencent.mm"));
        assertEquals(VideoApps.Kind.NONE, VideoApps.of("com.android.systemui"));
        assertFalse(VideoApps.isTarget("com.tencent.mm"));
        assertTrue(VideoApps.isTarget("tv.danmaku.bili"));
        assertEquals("unknown", VideoApps.label(VideoApps.Kind.NONE));
    }

    // ------------------------------------------------------------------ 通用设置语义

    @Test
    public void masterSwitchOffStopsEverythingEvenInBlackMode() {
        Config.enableStatus = false;
        Config.listMode = ConfStore.MODE_BLACK;
        Config.list = set("com.ss.android.ugc.aweme");
        Config.engine = ConfStore.ENGINE_LSPOSED;
        assertFalse(Config.shouldIntervene("com.ss.android.ugc.aweme"));
        assertFalse(Config.shouldIntervene("tv.danmaku.bili"));
    }

    @Test
    public void whiteModeExemptsOnlyListedPackage() {
        Config.enableStatus = true;
        Config.listMode = ConfStore.MODE_WHITE;
        Config.list = set("com.ss.android.ugc.aweme");
        Config.engine = ConfStore.ENGINE_LSPOSED;
        assertFalse(Config.shouldIntervene("com.ss.android.ugc.aweme"));
        assertTrue(Config.shouldIntervene("com.smile.gifmaker"));
        assertTrue(Config.shouldIntervene("com.phoenix.read"));
    }

    @Test
    public void blackModeTakesOnlyListedAndEmptyFallsBackToAll() {
        Config.enableStatus = true;
        Config.engine = ConfStore.ENGINE_LSPOSED;
        Config.listMode = ConfStore.MODE_BLACK;
        Config.list = set("com.kuaishou.nebula");
        assertTrue(Config.shouldIntervene("com.kuaishou.nebula"));
        assertFalse(Config.shouldIntervene("tv.danmaku.bili"));

        Config.list = new HashSet<>();
        assertTrue("黑名单空集不能变成全部豁免", Config.shouldIntervene("tv.danmaku.bili"));
    }

    @Test
    public void offModeIgnoresListAndAlwaysIntervenes() {
        Config.enableStatus = true;
        Config.engine = ConfStore.ENGINE_LSPOSED;
        Config.listMode = ConfStore.MODE_OFF;
        Config.list = set("tv.danmaku.bili");
        assertTrue(Config.shouldIntervene("tv.danmaku.bili"));
    }

    @Test
    public void ownSettingsUiIsAlwaysExempt() {
        Config.enableStatus = true;
        Config.engine = ConfStore.ENGINE_LSPOSED;
        Config.listMode = ConfStore.MODE_OFF;
        Config.list = new HashSet<>();
        assertFalse(Config.shouldIntervene("io.github.wumisc.antiburn"));
        assertFalse(Config.shouldIntervene("io.github.wumisc.antiburn:remote"));
    }

    @Test
    public void overlayEngineMakesHookYieldForEveryVideoApp() {
        Config.enableStatus = true;
        Config.listMode = ConfStore.MODE_OFF;
        Config.list = new HashSet<>();
        Config.engine = ConfStore.ENGINE_OVERLAY;
        assertFalse(Config.shouldIntervene("com.ss.android.ugc.aweme"));
        assertFalse(Config.shouldIntervene("com.phoenix.read"));
        assertFalse(Config.shouldIntervene("com.smile.gifmaker"));
        assertFalse(Config.shouldIntervene("tv.danmaku.bili"));
    }

    @Test
    public void listModeNormalizationCoversLegacyAliases() {
        assertEquals(ConfStore.MODE_WHITE, ConfStore.normMode(null));
        assertEquals(ConfStore.MODE_WHITE, ConfStore.normMode("white"));
        assertEquals(ConfStore.MODE_BLACK, ConfStore.normMode("blacklist"));
        assertEquals(ConfStore.MODE_BLACK, ConfStore.normMode(" 黑名单 "));
        assertEquals(ConfStore.MODE_OFF, ConfStore.normMode("关闭"));
        assertEquals(ConfStore.MODE_WHITE, ConfStore.normMode("unknown"));
    }

    @Test
    public void engineNormalizationCoversLegacyAliases() {
        assertEquals(ConfStore.ENGINE_LSPOSED, ConfStore.normEngine(null));
        assertEquals(ConfStore.ENGINE_LSPOSED, ConfStore.normEngine("unknown"));
        assertEquals(ConfStore.ENGINE_OVERLAY, ConfStore.normEngine("overlay"));
        assertEquals(ConfStore.ENGINE_OVERLAY, ConfStore.normEngine("a11y"));
        assertEquals(ConfStore.ENGINE_OVERLAY, ConfStore.normEngine("遮盖"));
    }

    @Test
    public void readListsMigratesOldBlacklistOnlyConfigToWhite() {
        ConfStore.Lists old = ConfStore.readLists("blacklist=com.foo.com,com.bar.com\n");
        assertEquals(ConfStore.MODE_WHITE, old.mode);
        assertEquals(set("com.foo.com", "com.bar.com"), old.white);
        assertTrue(old.black.isEmpty());
        assertEquals(old.white, old.active());
    }

    @Test
    public void readListsKeepsExplicitBlackModeAndActiveList() {
        ConfStore.Lists lists = ConfStore.readLists(
                "list_mode=black\nwhitelist=com.white\nblacklist=com.black.com\n");
        assertEquals(ConfStore.MODE_BLACK, lists.mode);
        assertEquals(set("com.white"), lists.white);
        assertEquals(set("com.black.com"), lists.black);
        assertEquals(lists.black, lists.active());
    }

    @Test
    public void whitelistParserAcceptsChineseAndAsciiSeparators() {
        assertEquals(set("com.a", "com.b", "com.c", "com.d"),
                ConfStore.parseWhitelist("com.a,com.b，com.c；com.d;"));
        assertTrue(ConfStore.parseWhitelist("   \n , ， ").isEmpty());
    }

    @Test
    public void defaultConfigDeclaresAllFourVideoAppsAndKeepsThemOff() {
        String d = ConfStore.defaultContent();
        for (String key : Arrays.asList(
                "enable_douyin_top", "enable_douyin_bottom", "enable_douyin_like",
                "enable_douyin_right_top", "enable_douyin_left_top",
                "enable_drama_top", "enable_drama_bottom", "enable_drama_like",
                "enable_drama_right_top", "enable_drama_left_top", "enable_drama_swipe",
                "enable_kuaishou", "enable_bilibili",
                "enable_ks_right", "enable_ks_top", "enable_ks_bottom", "enable_ks_corner",
                "enable_bili_right", "enable_bili_top", "enable_bili_bottom", "enable_bili_corner")) {
            assertTrue("默认配置缺键 " + key, d.contains(key + "="));
        }
        assertTrue(d.contains("enable_kuaishou=0"));
        assertTrue(d.contains("enable_bilibili=0"));
    }

    @Test
    public void listRulesFallbackStateMatchesDocs() {
        assertTrue(ListRules.isFallbackAll(ConfStore.MODE_WHITE, new HashSet<>()));
        assertTrue(ListRules.isFallbackAll(ConfStore.MODE_BLACK, new HashSet<>()));
        assertTrue(ListRules.isFallbackAll(ConfStore.MODE_OFF, set("com.foo")));
        assertFalse(ListRules.isFallbackAll(ConfStore.MODE_WHITE, set("com.foo")));
    }

    private static Set<String> set(String... items) {
        return new HashSet<>(Arrays.asList(items));
    }
}
