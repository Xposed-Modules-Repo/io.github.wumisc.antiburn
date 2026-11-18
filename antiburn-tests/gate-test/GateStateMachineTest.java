import io.github.wumisc.antiburn.ThhLiveGate;

/**
 * v3.0.8 P4 / T-50 行为靶场（纯 JVM，零 Android 依赖）。
 *
 * <p>直接实例化真机要用的同一份 {@link ThhLiveGate} 字节码语义，用显式 nowMs 驱动，
 * 逐步断言 stable/hitStreak/missStreak 中间态。固定采样步长 600ms
 * （> SAMPLE_WINDOW_MS=500，< GAP_RESET_MS=4000）。</p>
 *
 * <p>变异实证：M1 把 EXIT_FRAMES 改成 1 → T-50-B3 FAIL；
 * M2 拔掉真源 feed 接线 → 由 FeatureAuditTest T-50-W1 FAIL（本靶场只读状态机，不改接线审计）。</p>
 */
public class GateStateMachineTest {

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

    public static void main(String[] args) {
        System.out.println("== T-50 ThhLiveGate 门控状态机靶场 ==");
        ThhLiveGate g = new ThhLiveGate();

        // t=0 首次命中：进入需 2 次，第一次不生效
        boolean o0 = g.feed(true, 0L);
        check(!o0 && !g.stable() && g.hitStreak() == 1 && g.missStreak() == 0,
                "T-50-B1 首次命中不进（stable=false, hit=1）",
                "stable=" + g.stable() + " hit=" + g.hitStreak());

        // t=600 第二次命中：连续 2 次 → 隐藏
        boolean o1 = g.feed(true, 600L);
        check(o1 && g.stable() && g.hitStreak() == 2 && g.missStreak() == 0,
                "T-50-B2 连续 2 次命中进入隐藏（stable=true, hit=2）",
                "stable=" + g.stable() + " hit=" + g.hitStreak());

        // t=1200 单帧不命中：★核心★ miss=1<3 仍隐藏，验证「单帧抖动不放行」
        boolean o2 = g.feed(false, 1200L);
        check(o2 && g.stable() && g.missStreak() == 1 && g.hitStreak() == 0,
                "T-50-B3 单帧抖动仍隐藏（stable=true, miss=1）",
                "stable=" + g.stable() + " miss=" + g.missStreak());

        // t=1800 抖动后恢复命中：保持隐藏，miss 归零、hit 重新累积
        boolean o3 = g.feed(true, 1800L);
        check(o3 && g.stable() && g.hitStreak() == 1 && g.missStreak() == 0,
                "T-50-B4 抖动后恢复命中保持隐藏（hit=1, miss=0）",
                "hit=" + g.hitStreak() + " miss=" + g.missStreak());

        // t=2400 再次单帧不命中：仍不释放
        boolean o4 = g.feed(false, 2400L);
        check(o4 && g.stable() && g.missStreak() == 1,
                "T-50-B5 再次单帧仍不释放（stable=true, miss=1）",
                "miss=" + g.missStreak());

        // t=3000 第二次连续不命中：仍未到 EXIT_FRAMES
        boolean o5 = g.feed(false, 3000L);
        check(o5 && g.stable() && g.missStreak() == 2,
                "T-50-B5b 连续 2 帧不满足仍隐藏（miss=2）",
                "miss=" + g.missStreak());

        // t=3600 第三次连续不命中：连续 3 帧 → 恢复，不挂死
        boolean o6 = g.feed(false, 3600L);
        check(!o6 && !g.stable() && g.missStreak() == 3,
                "T-50-B6 连续 3 帧不满足恢复（stable=false, miss=3）",
                "stable=" + g.stable() + " miss=" + g.missStreak());

        // t=4200 恢复后可再次进入：hit 从 0 重新累积到 1
        boolean o7 = g.feed(true, 4200L);
        check(!o7 && g.hitStreak() == 1 && g.missStreak() == 0,
                "T-50-B7 恢复后可再次进入，未挂死（hit=1）",
                "hit=" + g.hitStreak());

        // T-50-B8 窗口风暴：复用同一实例，前置 = B7 之后（hasSample=true、
        // lastSampleAt=4200、missStreak 已被 B7 的 feed(true) 归零）。
        // 首帧 t=4300 满足 4300-4200=100 < SAMPLE_WINDOW_MS(500)，三次全落「窗口内重复调用」
        // 直接 return stable，missStreak 必须仍为 0（窗口内根本不计数）——
        // 证明 onGlobalLayout 连发无法「凑次数」。
        g.feed(false, 4300L);
        g.feed(false, 4400L);
        g.feed(false, 4500L);
        check(g.missStreak() == 0 && !g.stable(),
                "T-50-B8 窗口内连发 3 次不计数（miss=0）",
                "miss=" + g.missStreak());

        // T-50-B9 硬复位：先让 stable=true，再 reset() 验证全清
        g.reset();
        ThhLiveGate h = new ThhLiveGate();
        h.feed(true, 0L);
        h.feed(true, 600L);
        check(h.stable() && h.output(), "T-50-B9a reset 前 stable=true", "");
        h.reset();
        check(!h.output() && !h.stable() && h.hitStreak() == 0 && h.missStreak() == 0
                && h.lastSampleAt() == 0L,
                "T-50-B9 硬复位全清（output=false, hit=0, miss=0）",
                "stable=" + h.stable() + " hit=" + h.hitStreak() + " miss=" + h.missStreak());

        // 常量契约：防止有人靠改常量绕过靶场（M1 变异 EXIT_FRAMES=3→1 会在 B3 先 FAIL）
        check(ThhLiveGate.ENTER_FRAMES == 2 && ThhLiveGate.EXIT_FRAMES == 3
                && ThhLiveGate.SAMPLE_WINDOW_MS == 500L && ThhLiveGate.GAP_RESET_MS == 4000L,
                "T-50-C 门控常量锁定（2/3/500/4000）",
                "E=" + ThhLiveGate.ENTER_FRAMES + " X=" + ThhLiveGate.EXIT_FRAMES
                        + " W=" + ThhLiveGate.SAMPLE_WINDOW_MS + " G=" + ThhLiveGate.GAP_RESET_MS);

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
