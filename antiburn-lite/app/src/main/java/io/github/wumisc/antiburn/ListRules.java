package io.github.wumisc.antiburn;

import java.util.Set;

/**
 * 名单判定：全项目唯一真源。
 *
 * <p>v2.6 引入无障碍遮盖引擎后，同一套「白名单 / 黑名单 / 关闭」语义要被两处消费：
 * Hook 侧（SystemUI 进程内，{@link Config#shouldIntervene}）和遮盖侧
 * （{@link BurnInMaskService}，跑在 App 自己进程里）。如果两边各写一份 if，
 * 迟早出现「Hook 引擎里黑名单是豁免、遮盖引擎里黑名单是干预」这种反向 bug——
 * 用户切个引擎就发现名单行为变了，却没有任何地方能看出差异来源。</p>
 *
 * <p>三条规则（与 {@link ConfStore#MODE_* } 一一对应）：</p>
 * <ul>
 *   <li>white：命中=豁免，未命中=干预；空名单 = 全部干预</li>
 *   <li>black：命中=干预，未命中=豁免；空名单 = 退回全部干预</li>
 *   <li>off  ：忽略名单，全部干预</li>
 * </ul>
 *
 * <p>黑名单空集不按字面「全部豁免」，因为用户切到黑名单却没勾任何应用时，
 * 模块会表现为完全没反应，极易被判定成「功能坏了」。</p>
 */
public final class ListRules {

    private ListRules() {
    }

    /**
     * 只按名单模式判定，不管总开关。
     *
     * @param mode 名单模式（white / black / off），非法值按 white 处理
     * @param list 当前模式对应的那份名单，null 视为空集
     * @param pkg  前台包名，null 表示不知道前台是谁
     */
    public static boolean shouldIntervene(String mode, Set<String> list, String pkg) {
        String m = ConfStore.normMode(mode);
        if (ConfStore.MODE_OFF.equals(m)) {
            return true;
        }
        boolean hit = pkg != null && list != null && list.contains(pkg);
        if (ConfStore.MODE_BLACK.equals(m)) {
            return hit || list == null || list.isEmpty();
        }
        return !hit;
    }

    /**
     * 带总开关的完整判定。
     *
     * @param masterOn 总开关（conf 里的 enable_status），关闭时一律不干预
     */
    public static boolean shouldIntervene(String mode, Set<String> list, boolean masterOn, String pkg) {
        return masterOn && shouldIntervene(mode, list, pkg);
    }

    /** 名单为空时是否处于「兜底全干预」状态，供 UI 解释当前行为。 */
    public static boolean isFallbackAll(String mode, Set<String> list) {
        if (ConfStore.MODE_OFF.equals(ConfStore.normMode(mode))) {
            return true;
        }
        return list == null || list.isEmpty();
    }
}
