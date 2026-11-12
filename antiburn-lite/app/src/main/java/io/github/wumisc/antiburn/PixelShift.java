package io.github.wumisc.antiburn;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 像素偏移（pixel shift）防烧屏引擎。
 *
 * <p>为什么需要它：本模块原有实现是「每隔 N 秒把状态栏图标整个隐藏再显示」。
 * 对 OLED 来说，图标 ON/OFF 各占一半时间，理论上只能把局部老化摊薄一半；
 * 而且图标突然消失会让用户以为系统出故障（这是旧版被反复投诉的体验问题）。
 *
 * <p>AOSP 与主流第三方 ROM 采用的方案是像素偏移：每隔一段时间把状态栏 / 导航栏
 * 的 item 平移 1~2px，让固定元素轮流压在不同的物理像素上。人眼几乎无法察觉，
 * 但轮廓边缘被彻底磨平。参考实现见 frameworks/base 的 BurnInProtectionController：
 * 横向在 [-maxX, +maxX] 往返，纵向同法但步频不同，避免两轴同时归零。
 *
 * <p>作用对象是「子容器」而不是窗口根：状态栏窗口根的 translationY 由
 * StatusBarWindowController 与下拉手势控制，直接改会与下拉动画抢同一个属性，
 * 表现为拖拽时位置抖动。AOSP 同样只偏移 item（shiftStatusBarItems）。
 *
 * <p>本类不查找锚点、不猜类名（锚点由 {@link Controller} 负责），
 * 因此换 ROM 时失效面极小：只要能拿到状态栏窗口根，本类就能工作。
 *
 * <p>抗 ROM 重置：部分 ROM 的进出场动画会 setTranslationX/Y 覆盖位移。
 * 每次 {@link #tick} 都校验实际值是否仍等于期望值，被改回则重新施加。
 */
public final class PixelShift {

    /** 每步移动间隔。AOSP 原生为 60s；步长仅 1~2px，快一点覆盖更均匀。 */
    private static final long STEP_MS = 8000L;

    /** 横向最大偏移（dp）。2dp 是 AOSP 常见值；再大边缘可能被父容器裁剪露黑边。 */
    private static final int MAX_X_DP = 2;
    /** 纵向最大偏移（dp）。状态栏高度有限，纵向只给 1dp。 */
    private static final int MAX_Y_DP = 1;

    /**
     * 这两个引用与 {@code Controller.statusView / navView} 指向同一批 View，
     * 生命周期完全一致（Controller 本就是 static volatile 强引用）。
     * 因此这里不构成额外泄漏面，属于 lint 的 StaticFieldLeak 模式化误报。
     */
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static View statusRoot;
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static View navRoot;
    /** 上次偏移过的子容器，目标变化时用于归零，避免残留位移。 */
    private static final List<View> touched = new ArrayList<>();

    private static long lastStepAt;
    private static int curX;
    private static int curY;
    private static int dirX = 1;
    private static int dirY = 1;

    private static int maxX = 2;
    private static int maxY = 1;
    private static float lastDensity;

    private static long applied;

    /**
     * 是否避让「中置胶囊」区域（流体云 / 灵动胶囊 / 原子岛 / 实况窗）。
     *
     * <p>原生 AOSP 状态栏中间只有时钟，平移无副作用；而国产 UI 的中置胶囊是
     * 悬浮的独立窗口/容器，把它整体平移 1~2px 会造成「胶囊在抖」的观感，
     * 部分 ROM 还会因为胶囊跟随手指/动画而反复覆盖位移，白耗性能。
     * 因此按用户选择的 ui_style 决定是否跳过中置容器。</p>
     */
    private static volatile boolean guardCenter;
    /** 中置判定的容差 / 宽度阈值由 UiStyle 按厂商给出，不再硬编码 8% / 55%。 */
    private static volatile int centerTolerancePct;
    private static volatile int centerWidthPct;
    private static volatile String currentStyle = UiStyle.AOSP;

    private PixelShift() {
    }

    /**
     * 绑定目标窗口根。status 通常是 StatusBarWindowView，nav 是导航栏视图
     * （手势导航下可能为 null，此时只偏移状态栏）。
     * 目标变化时把旧目标的位移归零，避免换窗口后残留偏移。
     */
    public static void bind(View status, View nav) {
        if (statusRoot != status) {
            clearTouched();
            statusRoot = status;
        }
        if (navRoot != nav) {
            if (navRoot != null) {
                navRoot.setTranslationX(0f);
                navRoot.setTranslationY(0f);
            }
            navRoot = nav;
        }
        if (status != null) {
            float d = status.getResources().getDisplayMetrics().density;
            if (d <= 0f) {
                d = 1f;
            }
            if (d != lastDensity) {
                lastDensity = d;
                maxX = Math.max(1, Math.round(MAX_X_DP * d));
                maxY = Math.max(1, Math.round(MAX_Y_DP * d));
                curX = 0;
                curY = 0;
                dirX = 1;
                dirY = 1;
            }
        }
    }

    /**
     * 由 Controller 按 ui_style 设置。v2.7：六个 id 各自带一套「是否避让中置胶囊 +
     * 中心容差 + 宽度阈值」，不再是「非 aosp 就一律同一个值」的二值判断。
     * 参数来自 UiStyle（App 侧与 Hook 侧同一份真源），未知值自动归一到 aosp。
     */
    public static void setStyle(String style) {
        String id = UiStyle.norm(style);
        currentStyle = id;
        guardCenter = UiStyle.guardCenter(id);
        centerTolerancePct = UiStyle.centerTolerancePct(id);
        centerWidthPct = UiStyle.centerWidthPct(id);
    }

    public static boolean isGuardCenter() {
        return guardCenter;
    }

    /** 供诊断日志核对当前生效的厂商参数。 */
    public static String styleState() {
        return UiStyle.describe(currentStyle);
    }

    /** 是否已绑定可用目标。 */
    public static boolean isActive() {
        return statusRoot != null || navRoot != null;
    }

    /**
     * 由 Controller 的周期任务调用（间隔 ≤ 2s）。
     * 只有到达 STEP_MS 才推进相位；其余调用只做「抗重置校验」，开销是几次 getter 比较。
     */
    public static void tick() {
        if (statusRoot == null && navRoot == null) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastStepAt == 0L) {
            lastStepAt = now;
        }
        if (now - lastStepAt >= STEP_MS) {
            lastStepAt = now;
            advance();
        }
        enforce(statusRoot, curY);
        enforce(navRoot, 0);
    }

    /**
     * 推进一个相位步。两轴用不同步频，合成轨迹是一条缓慢漂移的折线，
     * 覆盖的像素组合远多于两轴同步往返。
     */
    private static void advance() {
        curX += dirX;
        if (curX >= maxX || curX <= -maxX) {
            dirX = -dirX;
        }
        if ((applied & 1L) == 0L) {
            curY += dirY;
            if (curY >= maxY || curY <= -maxY) {
                dirY = -dirY;
            }
        }
        applied++;
        applyTo(statusRoot, curX, curY);
        // 导航栏只做横向：手势条下沿贴屏幕边缘，上下移动容易被裁切
        applyTo(navRoot, curX, 0);
        if (applied % 30 == 0) {
            XposedInit.diag("SHIFT applied=" + applied + " offset=(" + curX + "," + curY + ")"
                    + " max=(" + maxX + "," + maxY + ")");
        }
    }

    /** 对窗口根的直接子容器施加位移；根不是 ViewGroup 时退化为偏移自身。 */
    private static void applyTo(View root, int x, int y) {
        if (root == null) {
            return;
        }
        if (!(root instanceof ViewGroup)) {
            apply(root, x, y);
            touch(root);
            return;
        }
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() == View.GONE || guarded(root, c)) {
                continue;
            }
            apply(c, x, y);
            touch(c);
        }
    }

    /** 校验并修正：ROM 动画把 translation 改回 0 时重新施加。 */
    private static void enforce(View root, int y) {
        if (root == null) {
            return;
        }
        if (!(root instanceof ViewGroup)) {
            if (root.getTranslationX() != curX || root.getTranslationY() != y) {
                apply(root, curX, y);
            }
            return;
        }
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() == View.GONE) {
                continue;
            }
            if (c.getTranslationX() != curX || c.getTranslationY() != y) {
                if (guarded(root, c)) {
                    continue; // 胶囊容器恢复原位，不参与位移
                }
                apply(c, curX, y);
            }
        }
    }

    /**
     * 中置胶囊判定：水平中心落在屏幕中线附近、宽度明显小于整条栏的容器。
     * 取不到宽度（布局尚未完成）时不避让，宁可多移一点也不要漏移。
     */
    private static boolean guarded(View root, View c) {
        if (!guardCenter || centerTolerancePct <= 0 || centerWidthPct <= 0) {
            return false;
        }
        int rw = root.getWidth();
        if (rw <= 0) {
            return false;
        }
        int left = c.getLeft();
        int right = c.getRight();
        int w = right - left;
        if (w <= 0) {
            return false;
        }
        int cx = left + w / 2;
        return Math.abs(cx - rw / 2) < rw * centerTolerancePct / 100
                && w < rw * centerWidthPct / 100;
    }

    private static void apply(View v, int x, int y) {
        try {
            v.setTranslationX(x);
            v.setTranslationY(y);
        } catch (Throwable t) {
            XposedInit.diag("SHIFT apply failed: " + t);
        }
    }

    private static void touch(View v) {
        if (touched.size() < 32 && !touched.contains(v)) {
            touched.add(v);
        }
    }

    /** 把上次偏移过的容器归零（目标被替换时调用）。 */
    private static void clearTouched() {
        for (View v : touched) {
            try {
                v.setTranslationX(0f);
                v.setTranslationY(0f);
            } catch (Throwable ignored) {
            }
        }
        touched.clear();
    }

    /** 完全停用：归零所有位移并解绑。 */
    public static void shutdown() {
        clearTouched();
        if (statusRoot != null) {
            try {
                statusRoot.setTranslationX(0f);
                statusRoot.setTranslationY(0f);
            } catch (Throwable ignored) {
            }
        }
        if (navRoot != null) {
            try {
                navRoot.setTranslationX(0f);
                navRoot.setTranslationY(0f);
            } catch (Throwable ignored) {
            }
        }
        statusRoot = null;
        navRoot = null;
        curX = 0;
        curY = 0;
        dirX = 1;
        dirY = 1;
        lastStepAt = 0L;
    }

    /** 诊断摘要，供 dump / 状态页展示。 */
    public static String state() {
        return "shift active=" + isActive()
                + " bound=" + (statusRoot != null) + "/" + (navRoot != null)
                + " offset=(" + curX + "," + curY + ")"
                + " max=(" + maxX + "," + maxY + ")"
                + " steps=" + applied;
    }
}
