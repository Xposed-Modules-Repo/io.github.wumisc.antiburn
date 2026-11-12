package io.github.wumisc.antiburn;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 界面适配方案（ui_style）的唯一真源。
 *
 * <p>此前 {@code MainActivity.UI_STYLE_LABELS}、{@code AbConf.UI_STYLE_IDS}、
 * {@code AbConf.normUiStyle}、{@code Config.normStyle} 各自维护一份 id 列表与归一逻辑，
 * 四份实现容易漂移。现在全部收敛到这里：App 侧与 Hook 侧共用同一套 id、同一套归一化、
 * 同一套适配参数，避免「App 写了 hyperos、模块读成 aosp」这类跨进程不一致。
 *
 * <p><b>参数说明（重要）</b>：下面的中心容差 / 宽度阈值是「保守启发式默认值」，
 * 依据是当前 {@code PixelShift} 里已有的中置胶囊判定思路（水平中心落在屏幕中线附近、
 * 宽度明显小于整条状态栏）。各厂商真实胶囊宽度、状态栏结构需真机 dump 各 ROM 的
 * SystemUI 视图树后逐家校准；本轮不编造未经实测的精确像素值，参数差异仅体现
 * 「有无中置胶囊」与「保护区宽窄」的结构性区别，属于可安全回退的保守分组。
 */
public final class UiStyle {

    public static final String AOSP = "aosp";
    public static final String HYPEROS = "hyperos";
    public static final String MAGICOS = "magicos";
    public static final String COLOROS = "coloros";
    public static final String ORIGINOS = "originos";
    public static final String HARMONYOS = "harmonyos";

    /** 顺序与 {@code MainActivity.UI_STYLE_LABELS} 保持一致。 */
    public static final List<String> IDS = Collections.unmodifiableList(
            java.util.Arrays.asList(HYPEROS, MAGICOS, COLOROS, ORIGINOS, HARMONYOS, AOSP));

    private static final Map<String, Profile> PROFILES;

    static {
        Map<String, Profile> m = new LinkedHashMap<>();
        // guardCenter / centerTolerancePct / centerWidthPct
        m.put(AOSP, new Profile(false, 0, 0));
        // 六家互不相同：中心容差 / 宽度上限按各家胶囊形态给保守的默认梯度。
        // 顺序大致按「中置容器视觉宽度」从宽到窄，数值仅作结构性区分，
        // 精确值需真机 dump 各 ROM 的 SystemUI 视图树后逐家校准（见类注释）。
        m.put(HYPEROS, new Profile(true, 11, 62));    // 小米 HyperOS：中置挖孔 / 焦点通知，最宽
        m.put(MAGICOS, new Profile(true, 10, 58));    // 荣耀 MagicOS：灵动胶囊
        m.put(COLOROS, new Profile(true, 8, 54));     // OPPO ColorOS：中置挖孔，较窄
        m.put(ORIGINOS, new Profile(true, 10, 60));   // vivo OriginOS：原子岛 / 原子通知
        m.put(HARMONYOS, new Profile(true, 9, 56));   // 华为 HarmonyOS：实况窗
        PROFILES = Collections.unmodifiableMap(m);
    }

    private UiStyle() {
    }

    /** 未知 / 空 / 大小写不一致 / 带空白，一律归一到合法 id；脏值回退 aosp。 */
    public static String norm(String v) {
        String s = (v == null) ? "" : v.trim().toLowerCase(Locale.ROOT);
        return PROFILES.containsKey(s) ? s : AOSP;
    }

    public static boolean isKnown(String v) {
        return v != null && PROFILES.containsKey(v.trim().toLowerCase(Locale.ROOT));
    }

    /** 是否避让中置胶囊。原生 AOSP 状态栏中间只有时钟，平移无副作用，不避让。 */
    public static boolean guardCenter(String style) {
        return profile(style).guardCenter;
    }

    /** 中置判定：容器水平中心相对屏幕中线允许的偏差百分比（0 = 不判定）。 */
    public static int centerTolerancePct(String style) {
        return profile(style).centerTolerancePct;
    }

    /** 中置判定：容器宽度必须小于整条栏宽的百分比（0 = 不判定）。 */
    public static int centerWidthPct(String style) {
        return profile(style).centerWidthPct;
    }

    /** 诊断摘要，便于真机日志核对当前生效参数。 */
    public static String describe(String style) {
        String id = norm(style);
        Profile p = profile(id);
        return id + " guard=" + p.guardCenter + " tol=" + p.centerTolerancePct
                + "% width=" + p.centerWidthPct + "%";
    }

    private static Profile profile(String style) {
        Profile p = PROFILES.get(norm(style));
        return p == null ? PROFILES.get(AOSP) : p;
    }

    private static final class Profile {
        final boolean guardCenter;
        final int centerTolerancePct;
        final int centerWidthPct;

        Profile(boolean guardCenter, int centerTolerancePct, int centerWidthPct) {
            this.guardCenter = guardCenter;
            this.centerTolerancePct = centerTolerancePct;
            this.centerWidthPct = centerWidthPct;
        }
    }
}
