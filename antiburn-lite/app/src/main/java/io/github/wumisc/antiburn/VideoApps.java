package io.github.wumisc.antiburn;

/**
 * 视频类应用包名识别：全项目唯一真源。
 *
 * <p>背景（v2.8.0 真机实锤）：旧实现把包名硬编码成「精确 equals」散落在三处 ——
 * {@code res/values/arrays.xml} 的 xposed_scope、{@link XposedInit#handleLoadPackage}、
 * {@link DouyinHook#init} 的 isVideoApp 判定。用户 OPPO ColorOS 设备上抖音包名是
 * {@code com.ss.android.ugc.aweme.mobile}（渠道包，带 .mobile 后缀）、B站是
 * {@code com.bilibili.app.in}，两者既不在作用域清单、也不等于任何硬编码常量，
 * 于是 LSPosed 根本不把模块注入这两个进程，{@code DouyinHook} 一行都跑不到，
 * 表现就是「抖音基本盘失效、B站标题/底部导航无效」，而日志里连一条
 * {@code INJECTED Douyin} 都看不到。</p>
 *
 * <p>和 {@link ListRules} 同一条原则：识别逻辑只写一份，分发处与 Hook 处共用，
 * 避免「作用域加了变体、分发没加」或「分发认了、Hook 却按抖音开关处理 B站」
 * 这类两处各写一份 if 必然出现的反向 bug。用前缀匹配覆盖渠道包 / 极速版 / HD /
 * 概念版 / 国际版，新增变体只改这里一处。</p>
 */
public final class VideoApps {

    public enum Kind { NONE, DOUYIN, DRAMA, KUAISHOU, BILIBILI }

    private VideoApps() {
    }

    /**
     * 把安装包的真实包名归到四个目标应用之一；非目标返回 {@link Kind#NONE}。
     * 前缀匹配而非 equals：国内渠道包常在主包名后加后缀（.mobile / .lite / .oversea.gp）。
     */
    public static Kind of(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return Kind.NONE;
        }
        // 抖音：主包 com.ss.android.ugc.aweme，渠道/极速变体带后缀（.mobile / .lite 等）
        if (pkg.startsWith("com.ss.android.ugc.aweme")) {
            return Kind.DOUYIN;
        }
        // 红果短剧：国内版 com.phoenix.read，谷歌版 com.phoenix.read.oversea.gp
        if (pkg.startsWith("com.phoenix.read")) {
            return Kind.DRAMA;
        }
        // 快手：主包 com.smile.gifmaker，极速版 com.kuaishou.nebula
        if (pkg.startsWith("com.smile.gifmaker") || pkg.startsWith("com.kuaishou.nebula")) {
            return Kind.KUAISHOU;
        }
        // 哔哩哔哩：主包 tv.danmaku.bili，HD tv.danmaku.bilibilihd，
        // 概念版/渠道版 com.bilibili.app.*（含真机实证的 com.bilibili.app.in），国际版 com.bilibili.intl
        if (pkg.startsWith("tv.danmaku.bili") || pkg.startsWith("com.bilibili.app.")
                || pkg.startsWith("com.bilibili.intl")) {
            return Kind.BILIBILI;
        }
        return Kind.NONE;
    }

    /** 是否为本模块要注入并处理的目标应用 */
    public static boolean isTarget(String pkg) {
        return of(pkg) != Kind.NONE;
    }

    /** 供诊断日志 / VideoAppProbe 的 label 使用，避免再各处硬编码字符串 */
    public static String label(Kind k) {
        switch (k) {
            case DOUYIN: return "douyin";
            case DRAMA: return "drama";
            case KUAISHOU: return "kuaishou";
            case BILIBILI: return "bilibili";
            default: return "unknown";
        }
    }
}
