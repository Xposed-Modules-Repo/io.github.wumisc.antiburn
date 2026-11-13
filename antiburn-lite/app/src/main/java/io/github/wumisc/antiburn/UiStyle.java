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
 *
 * <p><b>Flyme（v2.7.4）</b>：魅族 Flyme 10/11（含 Flyme AIOS）的状态栏没有流体云 /
 * 灵动胶囊，但魅族 20/21 这类中置挖孔机型会在屏幕正中绘制「环形电池 + 挖孔黑边」，
 * 它是软件绘制的独立容器，宽度远小于一整条状态栏。因此 Flyme 需要避让中置（否则
 * 环形电池会跟着像素微移抖动），但宽度阈值要比 HyperOS 的胶囊窄得多，避免把
 * 左右两侧较宽的图标容器也误判成「中置」。魅族系统界面包名仍是
 * {@code com.android.systemui}（Flyme 另有 com.flyme.systemuitools 等辅助组件，
 * 与本模块无关），所以作用域无需新增，适配点在识别与容器定位（见 Controller）。
 */
public final class UiStyle {

    public static final String AOSP = "aosp";
    public static final String HYPEROS = "hyperos";
    public static final String MAGICOS = "magicos";
    public static final String COLOROS = "coloros";
    public static final String ORIGINOS = "originos";
    public static final String HARMONYOS = "harmonyos";
    public static final String FLYME = "flyme";

    /** 顺序与 {@code MainActivity.UI_STYLE_LABELS} 保持一致。 */
    public static final List<String> IDS = Collections.unmodifiableList(
            java.util.Arrays.asList(HYPEROS, MAGICOS, COLOROS, ORIGINOS, HARMONYOS, FLYME, AOSP));

    private static final Map<String, Profile> PROFILES;

    static {
        Map<String, Profile> m = new LinkedHashMap<>();
        // guardCenter / centerTolerancePct / centerWidthPct
        m.put(AOSP, new Profile(false, 0, 0));
        // 七家互不相同（含 Flyme）：中心容差 / 宽度上限按各家胶囊形态给保守的默认梯度。
        // 顺序大致按「中置容器视觉宽度」从宽到窄，数值仅作结构性区分，
        // 精确值需真机 dump 各 ROM 的 SystemUI 视图树后逐家校准（见类注释）。
        m.put(HYPEROS, new Profile(true, 11, 62));    // 小米 HyperOS：中置挖孔 / 焦点通知，最宽
        m.put(MAGICOS, new Profile(true, 10, 58));    // 荣耀 MagicOS：灵动胶囊
        m.put(COLOROS, new Profile(true, 8, 54));     // OPPO ColorOS：中置挖孔，较窄
        m.put(ORIGINOS, new Profile(true, 10, 60));   // vivo OriginOS：原子岛 / 原子通知
        m.put(HARMONYOS, new Profile(true, 9, 56));   // 华为 HarmonyOS：实况窗
        // 魅族 Flyme：无胶囊，只有正中的环形电池 / 挖孔黑边，容器窄，容差与宽度同步收窄
        m.put(FLYME, new Profile(true, 7, 40));
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

    /**
     * 按机型信息猜一套界面方案，只用于「用户还没选过」的首次加载。
     *
     * <p>为什么要自动识别：魅族 / 一加这类机器在没选适配方案时默认 aosp，
     * 中置挖孔与环形电池区域不会被避让，像素微移会带着这些静止元素一起抖动。
     * 自动识别只改「初始选择」，用户随手在首页点一下就会覆盖并落盘，
     * 不存在「识别错了还改不回来」的问题。</p>
     *
     * <p>纯函数、不依赖 Android，便于 JVM 单测；调用方把 Build 字段传进来。</p>
     */
    public static String detect(String manufacturer, String brand, String display, String product) {
        String s = join(manufacturer, brand, display, product);
        if (s.contains("meizu") || s.contains("flyme")) return FLYME;
        if (s.contains("xiaomi") || s.contains("redmi") || s.contains("poco")) return HYPEROS;
        if (s.contains("honor")) return MAGICOS;
        if (s.contains("oppo") || s.contains("realme") || s.contains("oneplus") || s.contains("oplus")) {
            return COLOROS;
        }
        if (s.contains("vivo") || s.contains("iqoo")) return ORIGINOS;
        if (s.contains("huawei")) return HARMONYOS;
        return AOSP;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null) {
                sb.append(p.trim().toLowerCase(Locale.ROOT)).append(' ');
            }
        }
        return sb.toString();
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
