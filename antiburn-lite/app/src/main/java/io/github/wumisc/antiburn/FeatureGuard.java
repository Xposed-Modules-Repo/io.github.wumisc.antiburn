package io.github.wumisc.antiburn;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能隔离（feature guard）。
 *
 * <p>背景：本模块 hook 的是 SystemUI。任何一个 hook 点抛异常，都会顺着
 * View 的回调链把 SystemUI 打崩，而 SystemUI 是 persistent 进程，被杀后
 * 立刻重启、再次注入、再次崩溃 —— 用户看到的就是「开机就黑屏 / 状态栏反复消失」。
 *
 * <p>成熟模块（PixelXpert、Iconify、以及各类商业级 LSPosed 模块）的共同做法是：
 * 把每个功能点独立包一层，捕获 {@link Throwable} 后只把这一项标记为不可用，
 * 其余功能照常工作，并输出一份 OK / FAILED / DISABLED 汇总供诊断。
 *
 * <p>本类实现同一策略，额外加「连续失败熔断」：同一项连续失败
 * {@link #MAX_FAIL} 次后永久停用，避免每 2 秒一次的周期任务在坏锚点上
 * 反复抛异常刷日志、拖慢主线程。
 *
 * <p>注意：这是进程内状态，进程重启即清零。跨重启的兜底依赖 LSPosed
 * 自带的安全模式（开机连按音量键），本类不越权去写别的应用数据目录。
 */
public final class FeatureGuard {

    /** 同一项连续失败多少次后熔断。取 3：偶发一次不误杀，真坏了也不会拖太久。 */
    private static final int MAX_FAIL = 3;

    private static final Map<String, Integer> fails = new ConcurrentHashMap<>();
    private static final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private static final Set<String> healthy = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> reasons = new ConcurrentHashMap<>();
    /** 熔断时间：超过冷却期自动给一次重试机会，避免「偶发 3 次异常后本进程永久停用」 */
    private static final Map<String, Long> disabledAt = new ConcurrentHashMap<>();
    private static final long RETRY_COOLDOWN_MS = 60_000L;

    private FeatureGuard() {
    }

    /**
     * 执行一个功能点。已熔断的直接跳过（零开销），成功记 OK，失败累计并可能熔断。
     *
     * @return true 表示本次执行成功
     */
    public static boolean guard(String id, Runnable body) {
        if (disabled.contains(id)) {
            Long at = disabledAt.get(id);
            if (at == null || System.currentTimeMillis() - at < RETRY_COOLDOWN_MS) {
                return false;
            }
            disabled.remove(id);
            fails.remove(id);
            disabledAt.remove(id);
            XposedInit.diagForce("GUARD " + id + " 熔断冷却结束，重新尝试");
        }
        try {
            body.run();
            if (fails.remove(id) != null) {
                XposedInit.diagForce("GUARD " + id + " 恢复正常（此前有失败记录）");
            }
            healthy.add(id);
            return true;
        } catch (Throwable t) {
            int n = fails.merge(id, 1, Integer::sum);
            String msg = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            reasons.put(id, msg);
            XposedInit.diagForce("GUARD " + id + " 失败(" + n + "/" + MAX_FAIL + ") " + msg);
            if (n >= MAX_FAIL) {
                disabled.add(id);
                disabledAt.put(id, System.currentTimeMillis());
                XposedInit.diagForce("GUARD " + id + " 已熔断，60 秒后自动重试");
            }
            return false;
        }
    }

    /** 该功能点是否已被熔断（供调用方跳过整段逻辑，避免反复进出 try）。 */
    public static boolean isDisabled(String id) {
        return disabled.contains(id);
    }

    /** 手动解除熔断，配置变更后调用，给「用户改了设置再试一次」留余地。 */
    public static void reset(String id) {
        disabled.remove(id);
        fails.remove(id);
        reasons.remove(id);
        disabledAt.remove(id);
        XposedInit.diag("GUARD " + id + " 已重置");
    }

    /** 全部重置，仅在配置整体重载时调用。 */
    public static void resetAll() {
        disabled.clear();
        fails.clear();
        reasons.clear();
        disabledAt.clear();
    }

    /**
     * 单行汇总，供诊断日志与 dump 使用。
     * 形如 {@code OK: sys.status,sys.nav | DISABLED: dy.top | 近期失败: sys.nav=IllegalStateException}
     */
    public static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("OK: ").append(healthy.isEmpty() ? "-" : join(healthy));
        if (!disabled.isEmpty()) {
            sb.append(" | DISABLED: ").append(join(disabled));
        }
        if (!reasons.isEmpty()) {
            sb.append(" | 近期失败: ");
            boolean first = true;
            for (Map.Entry<String, String> e : reasons.entrySet()) {
                if (!first) {
                    sb.append("; ");
                }
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    private static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
