package io.github.wumisc.antiburn;

/**
 * v3.0.8 P1：THH 抖音直播间独立迟滞门控。
 * 纯 Java、无 Android 依赖 —— 靶场可用显式 nowMs 直接驱动并断言每个中间态。
 * 只消费外部传入的「原始直播信号」，绝不写 livePaused。
 */
public final class ThhLiveGate {
    /** 进入：连续 ENTER_FRAMES 次原始命中才置 stable=true（对齐 liveStreak>=2 语义）。 */
    public static final int ENTER_FRAMES = 2;
    /** 退出：连续 EXIT_FRAMES 次原始不命中才置 stable=false —— 单帧抖动不放行的核心。 */
    public static final int EXIT_FRAMES = 3;
    /** 采样窗口：相邻有效采样至少间隔这么多毫秒；窗口内重复调用不重复计数（抗 onGlobalLayout 风暴）。 */
    public static final long SAMPLE_WINDOW_MS = 500L;
    /** 超窗作废：距上次采样超过该值则清空连续计数，避免跨场景「凑次数」误进/误出。 */
    public static final long GAP_RESET_MS = 4000L;

    private boolean stable;
    private boolean hasSample;
    private int hitStreak;
    private int missStreak;
    private long lastSampleAt;

    public boolean output()      { return stable; }
    public boolean stable()      { return stable; }   // 靶场断言别名
    public int hitStreak()       { return hitStreak; }
    public int missStreak()      { return missStreak; }
    public long lastSampleAt()   { return lastSampleAt; }

    /**
     * 喂入一次采样。
     * @param rawSatisfied 原始「仍在直播间」信号是否满足（由 Hook 层只读计算）
     * @param nowMs 单调时钟毫秒（真机 = SystemClock.elapsedRealtime()；靶场 = 显式常量）
     * @return 去抖后的稳定输出
     */
    public boolean feed(boolean rawSatisfied, long nowMs) {
        if (hasSample) {
            long dt = nowMs - lastSampleAt;
            if (dt >= 0L && dt < SAMPLE_WINDOW_MS) {
                return stable;                       // 窗口内重复调用：忽略，不计数
            }
            if (dt > GAP_RESET_MS) {
                hitStreak = 0;                       // 跨场景：连续计数作废
                missStreak = 0;
            }
        }
        hasSample = true;
        lastSampleAt = nowMs;

        if (rawSatisfied) {
            missStreak = 0;
            if (hitStreak < ENTER_FRAMES) {
                hitStreak++;
            }
            if (hitStreak >= ENTER_FRAMES) {
                stable = true;
            }
        } else {
            hitStreak = 0;
            if (missStreak < EXIT_FRAMES) {
                missStreak++;
            }
            if (missStreak >= EXIT_FRAMES) {
                stable = false;                      // 连续 N 帧不满足才恢复 → 绝不挂死
            }
        }
        return stable;
    }

    /** 硬关（关开关/灭屏/切非抖音/forceShow/overlay）时立即复位，重新进房需重新去抖。 */
    public void reset() {
        stable = false;
        hasSample = false;
        hitStreak = 0;
        missStreak = 0;
        lastSampleAt = 0L;
    }
}
