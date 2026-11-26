package io.github.wumisc.antiburn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Handler;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * v3.2.30 沙盒：热重载「旧代码联动」逐项验证。
 *
 * <p>框架只 unhook 方法，不会替我们撤 Handler 周期、全局布局监听、悬浮球、
 * 浮层、线程，也不会清掉指向宿主 Activity / DecorView 的静态引用。这里不靠源码
 * grep，而是真的把旧代的状态塞进 {@link DouyinHook} / {@link Controller}，
 * 再调它们的 {@code shutdownForReload()}，用主线程调度队列与反射字段验收：
 * 退役后代际必须「排不进新任务、已排的被撤掉、引用与列表被清空」。</p>
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 36, application = Application.class)
public class HotReloadLinkageTest {

    private static final String[] DOUYIN_LISTS = {
            "rightList", "bottomList", "swipeHintList", "centerPlusList", "redDotList",
            "captionList", "ksLiveTargetList", "dyLiveTargetList", "ksFeedTargetList",
            "dramaTargetList", "liveTopList", "liveBottomList", "liveRightList",
            "dramaPaywallList"
    };

    private static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Method method(Class<?> c, String name, Class<?>... args) throws Exception {
        Method m = c.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    private static Handler handlerOf(Class<?> c) throws Exception {
        return (Handler) field(c, "handler").get(null);
    }

    private static Runnable runnableOf(Class<?> c) throws Exception {
        return (Runnable) field(c, "cycleRunnable").get(null);
    }

    private static void clearMainQueue() {
        Handler h = (Handler) fieldQuietly(DouyinHook.class, "handler");
        if (h != null) {
            h.removeCallbacksAndMessages(null);
        }
        Handler ch = (Handler) fieldQuietly(Controller.class, "handler");
        if (ch != null) {
            ch.removeCallbacksAndMessages(null);
        }
    }

    private static Object fieldQuietly(Class<?> c, String name) {
        try {
            return field(c, name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void resetDouyin() throws Exception {
        field(DouyinHook.class, "retired").setBoolean(null, false);
        field(DouyinHook.class, "started").setBoolean(null, false);
        field(DouyinHook.class, "cycleScheduled").setBoolean(null, false);
        clearMainQueue();
    }

    private static void resetController() throws Exception {
        field(Controller.class, "retired").setBoolean(null, false);
        field(Controller.class, "cycleScheduled").setBoolean(null, false);
        clearMainQueue();
    }

    // ------------------------------------------------------------------ 视频钩子（抖音 / 红果 / 快手 / B站 共用）

    @Test
    public void douyinShutdownClearsRegionListsAndHostViewReferences() throws Exception {
        resetDouyin();
        View dummy = new View(RuntimeEnvironment.getApplication());
        for (String name : DOUYIN_LISTS) {
            @SuppressWarnings("unchecked")
            List<View> list = (List<View>) field(DouyinHook.class, name).get(null);
            list.clear();
            list.add(dummy);
        }
        @SuppressWarnings("unchecked")
        java.util.Map<View, Integer> miss =
                (java.util.Map<View, Integer>) field(DouyinHook.class, "missRounds").get(null);
        miss.clear();
        miss.put(dummy, 7);

        for (String name : new String[]{"topBar", "rightTop", "leftTop", "currentDecor", "lastRoot"}) {
            field(DouyinHook.class, name).set(null, dummy);
        }
        field(DouyinHook.class, "hidden").setBoolean(null, true);
        field(DouyinHook.class, "forceShow").setBoolean(null, true);
        field(DouyinHook.class, "phaseAt").setLong(null, 123L);
        field(DouyinHook.class, "started").setBoolean(null, true);

        DouyinHook.shutdownForReload();

        for (String name : DOUYIN_LISTS) {
            @SuppressWarnings("unchecked")
            List<View> list = (List<View>) field(DouyinHook.class, name).get(null);
            assertTrue("退役后 " + name + " 必须清空", list.isEmpty());
        }
        assertTrue("missRounds 必须清空", miss.isEmpty());
        for (String name : new String[]{"topBar", "rightTop", "leftTop", "currentDecor", "lastRoot"}) {
            assertNull("退役后 " + name + " 不能继续攥着宿主 View", field(DouyinHook.class, name).get(null));
        }
        assertTrue((Boolean) field(DouyinHook.class, "retired").get(null));
        assertFalse((Boolean) field(DouyinHook.class, "hidden").get(null));
        assertFalse((Boolean) field(DouyinHook.class, "forceShow").get(null));
        assertEquals(0L, ((Long) field(DouyinHook.class, "phaseAt").get(null)).longValue());
        assertFalse((Boolean) field(DouyinHook.class, "started").get(null));
    }

    @Test
    public void douyinShutdownDropsQueuedCycleAndBlocksRescheduling() throws Exception {
        resetDouyin();
        Handler h = handlerOf(DouyinHook.class);
        Runnable r = runnableOf(DouyinHook.class);
        assertFalse("起点不应有旧周期", h.hasCallbacks(r));

        method(DouyinHook.class, "startCycle").invoke(null);
        assertTrue("startCycle 应真的排进主线程队列", h.hasCallbacks(r));

        DouyinHook.shutdownForReload();
        assertFalse("退役必须撤掉未执行的周期", h.hasCallbacks(r));

        method(DouyinHook.class, "startCycle").invoke(null);
        assertFalse("退役后 startCycle 不许再排任务", h.hasCallbacks(r));
    }

    @Test
    public void dequeuedDouyinCycleExitsImmediatelyAfterRetire() throws Exception {
        resetDouyin();
        DouyinHook.shutdownForReload();
        Handler h = handlerOf(DouyinHook.class);
        Runnable r = runnableOf(DouyinHook.class);
        r.run();
        assertFalse("旧 cycleRunnable 退役后不得再排下一轮", h.hasCallbacks(r));
        assertTrue((Boolean) field(DouyinHook.class, "retired").get(null));
    }

    @Test
    public void douyinShutdownIsIdempotent() throws Exception {
        resetDouyin();
        DouyinHook.shutdownForReload();
        DouyinHook.shutdownForReload();
        assertTrue((Boolean) field(DouyinHook.class, "retired").get(null));
    }

    // ------------------------------------------------------------------ SystemUI 控制器

    @Test
    public void controllerShutdownDropsQueuedCycleAndBlocksRescheduling() throws Exception {
        resetController();
        Handler h = handlerOf(Controller.class);
        Runnable r = runnableOf(Controller.class);
        assertFalse("起点不应有旧周期", h.hasCallbacks(r));

        method(Controller.class, "scheduleCycle", long.class).invoke(null, 1000L);
        assertTrue("scheduleCycle 应真的排进主线程队列", h.hasCallbacks(r));

        Controller.shutdownForReload();
        assertFalse("退役必须撤掉未执行的周期", h.hasCallbacks(r));

        method(Controller.class, "scheduleCycle", long.class).invoke(null, 1000L);
        assertFalse("退役后 scheduleCycle 不许再排任务", h.hasCallbacks(r));
    }

    @Test
    public void controllerShutdownIsIdempotentAndClearsViewRefs() throws Exception {
        resetController();
        View dummy = new View(RuntimeEnvironment.getApplication());
        field(Controller.class, "statusView").set(null, dummy);
        field(Controller.class, "navView").set(null, dummy);
        field(Controller.class, "notifyArea").set(null, dummy);
        field(Controller.class, "hidden").setBoolean(null, true);
        field(Controller.class, "islandHid").setBoolean(null, true);

        Controller.shutdownForReload();
        Controller.shutdownForReload();

        assertTrue((Boolean) field(Controller.class, "retired").get(null));
        assertNull(field(Controller.class, "statusView").get(null));
        assertNull(field(Controller.class, "navView").get(null));
        assertNull(field(Controller.class, "notifyArea").get(null));
        assertFalse((Boolean) field(Controller.class, "hidden").get(null));
        assertFalse((Boolean) field(Controller.class, "islandHid").get(null));
    }

    // ------------------------------------------------------------------ 总闸与各常驻资源

    @Test
    public void hotReloadGuardRetiresAllEnginesAndIsIdempotent() throws Exception {
        resetDouyin();
        resetController();
        HotReloadGuard.shutdown();
        HotReloadGuard.shutdown();

        assertTrue("Controller 必须退役", (Boolean) field(Controller.class, "retired").get(null));
        assertTrue("视频钩子必须退役", (Boolean) field(DouyinHook.class, "retired").get(null));
        assertTrue("配置预取必须停止", (Boolean) field(Config.class, "fetcherStop").get(null));
    }

    @Test
    public void configFetcherStopIsIdempotent() throws Exception {
        field(Config.class, "fetcherStop").setBoolean(null, false);
        Config.stopForReload();
        Config.stopForReload();
        assertTrue((Boolean) field(Config.class, "fetcherStop").get(null));
    }

    @Test
    public void livePauseIoStopIsIdempotent() {
        LivePause.stopForReload();
        LivePause.stopForReload();
    }

    @Test
    public void xposedLogWriterStopIsIdempotent() {
        XposedInit.stopForReload();
        XposedInit.stopForReload();
    }

    @Test
    public void sysOverlayReleaseIsIdempotentAndDetached() throws Exception {
        SysOverlay.release();
        SysOverlay.release();
        assertFalse((Boolean) field(SysOverlay.class, "attached").get(null));
        assertNull(field(SysOverlay.class, "view").get(null));
    }

    @Test
    public void pixelShiftShutdownIsIdempotent() {
        PixelShift.shutdown();
        PixelShift.shutdown();
    }

    @Test
    public void allFourVideoHooksShareOneRetirementSwitch() throws Exception {
        // 四个 App 在 XposedInit 里都走 DouyinHook.init；这里验证同一份退役闸
        // 能清掉四种 App 可能用到的全部列表（抖音/红果/快手/B站 的定向隐藏列表也在内）。
        resetDouyin();
        View dummy = new View(RuntimeEnvironment.getApplication());
        List<String> expected = new ArrayList<>();
        for (String name : DOUYIN_LISTS) {
            @SuppressWarnings("unchecked")
            List<View> list = (List<View>) field(DouyinHook.class, name).get(null);
            list.clear();
            list.add(dummy);
            expected.add(name);
        }
        DouyinHook.shutdownForReload();
        for (String name : expected) {
            @SuppressWarnings("unchecked")
            List<View> list = (List<View>) field(DouyinHook.class, name).get(null);
            assertTrue(name, list.isEmpty());
        }
    }
}
