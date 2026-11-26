package io.github.wumisc.antiburn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowPendingIntent;

import java.lang.reflect.Method;

/**
 * JVM 沙盒（Robolectric）：把 v3.2.25 两个「必须真跑才知道对不对」的点放进假 Android 运行时。
 *
 * <p><b>为什么不能只靠源码断言</b>：源码里写了 try/catch 不等于真不崩，写了
 * {@code setAndAllowWhileIdle} 不等于闹钟真的排上了。这里直接调真实方法、看系统影子对象的落账。</p>
 *
 * <p><b>覆盖</b>：桌面别名解析与真实组件切换（隐身开关那条链）、开关同步落盘、自愈闹钟的
 * 类型与延迟、被拉起的服务意图、系统拒绝后台启动后的兜底通知、PendingIntent 显式且带 manual 标记。</p>
 *
 * <p><b>不覆盖</b>：LSPosed 注入、HyperOS 的 LMK 回收、Doze 真实拉长、POST_NOTIFICATIONS 被拒的
 * 通知可见性、桌面 launcher 的真实刷新。这些只能在真机上看。</p>
 *
 * <p>用 {@code Application} 而不是 {@code AbApplication} 起沙盒：AbApplication.onCreate 会拉
 * ConfStore / 清日志线程 / su 杀进程，测试里全是不相干的重活。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, application = Application.class)
public class SandboxSafetyTest {

    private static Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    // ------------------------------------------------------------------ 隐身

    /** 别名必须是清单里真实存在的那一个（分支崩溃的根因就是拼出了不存在的组件）。 */
    @Test
    public void launcherAliasIsDeclaredInManifest() throws Exception {
        Context c = ctx();
        ComponentName alias = new ComponentName(c.getPackageName(), c.getPackageName() + ".LauncherAlias");
        // 清单里查得到 → 说明这名字是真组件，不是拍脑袋拼的
        assertNotNull("清单里应有 " + alias.flattenToString(),
                c.getPackageManager().getActivityInfo(alias, PackageManager.MATCH_DISABLED_COMPONENTS));
    }

    /** 开关真能把别名组件切到 DISABLED 再切回来，而且不抛异常。 */
    @Test
    public void applyLauncherVisibilityReallyTogglesComponent() {
        Context c = ctx();
        ComponentName alias = new ComponentName(c.getPackageName(), c.getPackageName() + ".LauncherAlias");
        PackageManager pm = c.getPackageManager();

        assertTrue("隐藏应返回 true", AppVisibilityStore.applyLauncherVisibility(c, true));
        assertEquals("隐藏后组件应是 DISABLED", PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                pm.getComponentEnabledSetting(alias));

        assertTrue("恢复应返回 true", AppVisibilityStore.applyLauncherVisibility(c, false));
        assertEquals("恢复后组件应是 ENABLED", PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                pm.getComponentEnabledSetting(alias));
    }

    /** 开→关两次，状态必须跟着走；偏好用 commit，读回立刻一致（不再出现「打开后自己关」）。 */
    @Test
    public void togglePersistsAndReadsBack() {
        Context c = ctx();
        assertTrue(AppVisibilityStore.setHideLauncherIcon(c, true));
        assertTrue(AppVisibilityStore.isHideLauncherIcon(c));

        assertTrue(AppVisibilityStore.setHideLauncherIcon(c, false));
        assertFalse(AppVisibilityStore.isHideLauncherIcon(c));
    }

    /** 组件状态与偏好必须同时正确，不允许一个成功一个失败。 */
    @Test
    public void toggleKeepsPrefsAndComponentInSync() {
        Context c = ctx();
        ComponentName alias = new ComponentName(c.getPackageName(), c.getPackageName() + ".LauncherAlias");
        assertTrue(AppVisibilityStore.setHideLauncherIcon(c, true));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                c.getPackageManager().getComponentEnabledSetting(alias));
        assertTrue(AppVisibilityStore.isHideLauncherIcon(c));

        assertTrue(AppVisibilityStore.setHideLauncherIcon(c, false));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                c.getPackageManager().getComponentEnabledSetting(alias));
        assertFalse(AppVisibilityStore.isHideLauncherIcon(c));
    }

    // ------------------------------------------------------------------ 自愈

    /** 兜底巡检：RTC_WAKEUP + 约 15 分钟，且不是精确闹钟。 */
    @Test
    public void healArmsFifteenMinuteWakeup() {
        Context c = ctx();
        long before = android.os.SystemClock.elapsedRealtime();
        HealReceiver.arm(c);

        ShadowAlarmManager.ScheduledAlarm a = nextAlarm(c);
        assertNotNull("应排上一条闹钟", a);
        assertEquals("必须用 ELAPSED_REALTIME_WAKEUP（RTC 会被改时间/校时带偏）",
                AlarmManager.ELAPSED_REALTIME_WAKEUP, a.type);
        long delta = a.triggerAtTime - before;
        assertTrue("延迟应在 14~16 分钟之间，实际 " + delta, delta >= 14 * 60_000L && delta <= 16 * 60_000L);
    }

    /** 被杀后的快速补刀：45 秒。 */
    @Test
    public void healArmsFastPath() {
        Context c = ctx();
        long before = android.os.SystemClock.elapsedRealtime();
        HealReceiver.armFast(c);

        ShadowAlarmManager.ScheduledAlarm a = nextAlarm(c);
        assertNotNull(a);
        long delta = a.triggerAtTime - before;
        assertTrue("延迟应在 44~50 秒之间，实际 " + delta, delta >= 44_000L && delta <= 50_000L);
    }

    /**
     * 收到 HEAL 后若服务不在，必须走到「拉起服务」那条分支并记账。
     *
     * <p>这里不看 startService 的意图（Robolectric 的 ShadowApplication 依赖
     * android.hardware.fingerprint.FingerprintManager，compileSdk 37 的 mockable jar 里没有，
     * 直接引用会编译失败）。改看它的可观测副作用：只有真的进了启动分支才会
     * {@code markHealed}、才写 last_heal_mode；没进分支这两个值永远是空。</p>
     */
    @Test
    public void healReceiverTakesStartBranchAndMarks() {
        Context c = ctx();
        assertEquals("", HealReceiver.lastHealMode(c));

        new HealReceiver().onReceive(c, new Intent(c, HealReceiver.class).setAction(HealReceiver.ACTION_HEAL));

        assertEquals("应记为自动自愈", "auto", HealReceiver.lastHealMode(c));
        assertTrue("应留下时间戳", HealReceiver.lastHealAt(c) > 0);
        assertTrue("应排上了一次兜底巡检", nextAlarm(c) != null);
    }

    /** 系统拒绝后台启动时的兜底：必须留下一条可点通知，而不是静默失败。 */
    @Test
    public void blockedStartLeavesClickableNotification() throws Exception {
        Context c = ctx();
        Method m = HealReceiver.class.getDeclaredMethod("notifyBlocked", Context.class);
        m.setAccessible(true);
        m.invoke(null, c);

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        ShadowNotificationManager snm = shadowOf(nm);
        assertTrue("应有一条兜底通知", snm.getAllNotifications().size() > 0);
        Notification n = snm.getAllNotifications().get(0);
        assertNotNull("通知必须带点击意图，否则用户没法恢复", n.contentIntent);
        ShadowPendingIntent spi = shadowOf(n.contentIntent);
        assertEquals("点击意图应打回自愈动作", HealReceiver.ACTION_HEAL, spi.getSavedIntent().getAction());
    }

    /** 两个 PendingIntent 都必须指向自家接收器，按钮那条带 manual 标记（日志能区分自动/手动）。 */
    @Test
    public void healPendingIntentsAreExplicit() {
        Context c = ctx();

        PendingIntent auto = HealReceiver.pending(c);
        Intent autoIntent = shadowOf(auto).getSavedIntent();
        assertEquals(HealReceiver.ACTION_HEAL, autoIntent.getAction());
        assertEquals(HealReceiver.class.getName(), autoIntent.getComponent().getClassName());

        PendingIntent manual = HealReceiver.manualPending(c);
        Intent manualIntent = shadowOf(manual).getSavedIntent();
        assertEquals(HealReceiver.ACTION_HEAL, manualIntent.getAction());
        assertTrue("手动那条必须带 manual=true", manualIntent.getBooleanExtra("manual", false));
        assertFalse("自动那条不该带 manual", autoIntent.getBooleanExtra("manual", false));
    }

    /** 自愈记账：刚自愈过时 healedRecently 为真，且能读出模式。 */
    @Test
    public void healedRecentlyReflectsMark() {
        Context c = ctx();
        assertFalse(HealReceiver.healedRecently(c, HealReceiver.RECENT_WINDOW_MS));

        new HealReceiver().onReceive(c, new Intent(c, HealReceiver.class).setAction(HealReceiver.ACTION_HEAL));

        assertTrue("刚拉起过服务，应算「最近自愈」", HealReceiver.healedRecently(c, HealReceiver.RECENT_WINDOW_MS));
        assertEquals("auto", HealReceiver.lastHealMode(c));
        assertTrue(HealReceiver.lastHealAt(c) > 0);
    }

    /** 用户点「停止」：排好的闹钟必须被注销，否则 15 分钟后服务自己复活。 */
    @Test
    public void userStopCancelsArmedAlarm() {
        Context c = ctx();
        HealReceiver.arm(c);
        assertNotNull("先确认闹钟真的排上了", nextAlarm(c));

        HealReceiver.markUserStop(c);

        assertNull("停止后不应还留着闹钟", nextAlarm(c));
        assertTrue("停止要落盘记住", HealReceiver.userStopped(c));
    }

    /** 即使闹钟照旧响了（ROM 重放 / 进程重启后标志丢失的极端情况），也不能自动复活。 */
    @Test
    public void userStopBlocksAutoHeal() {
        Context c = ctx();
        HealReceiver.markUserStop(c);

        new HealReceiver().onReceive(c, new Intent(c, HealReceiver.class).setAction(HealReceiver.ACTION_HEAL));

        assertEquals("不该记为自愈", "", HealReceiver.lastHealMode(c));
        assertFalse("不该当成本人刚恢复过", HealReceiver.healedRecently(c, HealReceiver.RECENT_WINDOW_MS));
        assertNull("不该再排巡检", nextAlarm(c));
    }

    /** 用户重新打开 App（服务正常启动）要清掉「别回来」，否则以后再也不自愈。 */
    @Test
    public void normalStartClearsStopFlag() {
        Context c = ctx();
        HealReceiver.markUserStop(c);
        assertTrue(HealReceiver.userStopped(c));

        HealReceiver.clearUserStop(c);
        assertFalse("正常启动后必须恢复自愈能力", HealReceiver.userStopped(c));

        HealReceiver.arm(c);
        assertNotNull("清掉后应能重新排上巡检", nextAlarm(c));
    }

    // ------------------------------------------------------- 用户停止后的门控

    /** 点过「停止守护」之后，自动巡检必须彻底不干活（不排闹钟、不记账）。 */
    @Test
    public void autoHealIsIgnoredAfterUserStop() {
        Context c = ctx();
        HealReceiver.markUserStop(c);
        assertTrue(HealReceiver.userStopped(c));
        assertNull("停止时应把已排的闹钟撤掉", nextAlarm(c));

        new HealReceiver().onReceive(c, new Intent(c, HealReceiver.class).setAction(HealReceiver.ACTION_HEAL));

        assertNull("自动巡检不能再排闹钟", nextAlarm(c));
        assertEquals("不能记账成自愈", "", HealReceiver.lastHealMode(c));
        assertFalse("也不该判定为刚自愈", HealReceiver.healedRecently(c, HealReceiver.RECENT_WINDOW_MS));
    }

    /** 手动那条（覆盖安装 / 点通知）不受「已停止」限制，否则用户装完新版反而起不来。 */
    @Test
    public void manualHealStillWorksAfterUserStop() {
        Context c = ctx();
        HealReceiver.markUserStop(c);

        new HealReceiver().onReceive(c, new Intent(c, HealReceiver.class)
                .setAction(HealReceiver.ACTION_HEAL).putExtra("manual", true));

        assertEquals("manual", HealReceiver.lastHealMode(c));
        assertNotNull("手动恢复后应重新排上兜底巡检", nextAlarm(c));
        // 「已停止」这个盘上标记由 GuardService.onStartCommand 清（沙盒不跑服务生命周期，
        // 所以这里不应该断言它已经变 false —— 真机上服务一起来就清）。
    }

    /** 服务回来后要能把「已停止」那条兜底通知撤掉。 */
    @Test
    public void blockedNoticeIsCancelledWhenServiceComesBack() throws Exception {
        Context c = ctx();
        Method nb = HealReceiver.class.getDeclaredMethod("notifyBlocked", Context.class);
        nb.setAccessible(true);
        nb.invoke(null, c);

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        assertTrue("先确认兜底通知在", shadowOf(nm).getAllNotifications().size() > 0);

        HealReceiver.cancelBlocked(c);
        assertEquals("撤掉后不应残留", 0, shadowOf(nm).getAllNotifications().size());
    }

    /** 停止按钮的 PendingIntent 必须指向 ACTION_STOP，且只在自家服务上生效。 */
    @Test
    public void stopPendingIntentTargetsActionStop() {
        Context c = ctx();
        PendingIntent stop = HealReceiver.stopPending(c);
        Intent saved = shadowOf(stop).getSavedIntent();
        assertEquals(GuardService.ACTION_STOP, saved.getAction());
        assertEquals(GuardService.class.getName(), saved.getComponent().getClassName());
    }

    // ------------------------------------------------------------------ 工具

    private static ShadowAlarmManager.ScheduledAlarm nextAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return shadowOf(am).getNextScheduledAlarm();
    }

    // ---------------------------------------------------------- 首次安装 / 热重载记账

    @Test
    public void firstInstallPromptOnlyBeforeFirstRun() {
        HookState s = new HookState();
        s.state = HookState.STALE;
        s.everRan = false;
        assertTrue("从没跑过：必须提示重启", s.firstInstallStale());
        s.everRan = true;
        assertFalse("跑过之后：更新一律不再提示", s.firstInstallStale());
        s.state = HookState.FRESH;
        s.everRan = false;
        assertFalse("不是 STALE 就不提示", s.firstInstallStale());
    }

    @Test
    public void hotReloadTimestampFlipsVerdict() {
        long apk = 1_700_000_000_000L;
        long proc = apk - 3_600_000L;
        assertEquals(HookState.STALE, HookState.decide(apk, proc, 0L));
        assertEquals("热重载成功后不再算旧版", HookState.FRESH,
                HookState.decide(apk, proc, apk + 1000L));
    }

    @Test
    public void generationMarkersRoundTrip() {
        android.content.Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences(ConfigProvider.HOOK_PREFS, android.content.Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        assertEquals("没有标记就是 0", 0L, HookState.reloadAt(ctx));
        assertEquals(0L, HookState.seenAt(ctx));
        sp.edit().putLong(ConfigProvider.KEY_RELOAD_AT, 123L)
                .putLong(ConfigProvider.KEY_SEEN_AT, 456L).commit();
        assertEquals(123L, HookState.reloadAt(ctx));
        assertEquals(456L, HookState.seenAt(ctx));
    }

    @Test
    public void hotReloadingCallbackBehavesPerApi102() throws Exception {
        java.lang.reflect.Field cap = AppContextHolder.class.getDeclaredField("captured");
        cap.setAccessible(true);
        cap.set(null, null);

        LibXposedEntry entry = new LibXposedEntry();
        Object[] saved = new Object[1];
        io.github.libxposed.api.XposedModuleInterface.HotReloadingParam param =
                new io.github.libxposed.api.XposedModuleInterface.HotReloadingParam() {
                    @Override
                    public android.os.Bundle getExtras() {
                        return null;
                    }

                    @Override
                    public void setSavedInstanceState(Object outState) {
                        saved[0] = outState;
                    }
                };

        assertFalse("API102：拿不到宿主 Application 必须取消热重载（返回 false）",
                entry.onHotReloading(param));
        assertNull("取消时不得留下半截交接状态", saved[0]);

        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        AppContextHolder.capture(app);
        assertTrue("有 Application 时放行热重载", entry.onHotReloading(param));
        assertSame("跨代状态必须是宿主 Application（不属于旧模块类加载器）", app, saved[0]);
    }
}
