import java.util.*;

/**
 * v2.6 配置通道仲裁回归测试（无需 Android 环境，纯逻辑）。
 *
 * 复刻 Config.reload() 的三通道按 rev 仲裁逻辑，用真机日志里的真实数字验证：
 *   provider 旧文本 rev=1794395546289（昨晚 19:12，remoteText 残留）
 *   cache    快照   rev=1794395546289（cache=ok，同样陈旧）
 *   direct   新副本 rev=1794416312234（00:58，App 刚写入并 su 同步）
 *   appliedRev     rev=1794395546289（SystemUI 已生效版本）
 *
 * v2.5 旧逻辑：direct 只在 best==null 时用 → 选中旧 provider，返回 rev=1794395546289，
 *              与 appliedRev 相等且 best==lastText → 直接 return，新配置永远不生效。
 * v2.6 新逻辑：三通道一起比 rev 取最大 → 选中 direct，rev=1794416312234 > appliedRev，
 *              CONF apply 生效。
 */
public class ArbitrationTest {

    static long revOf(String content) {
        if (content == null) return 0L;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("rev=")) {
                try { return Long.parseLong(t.substring(4).trim()); }
                catch (Throwable ignored) { return 0L; }
            }
        }
        return 0L;
    }

    /** v2.5：direct 仅兜底。 */
    static String[] oldArbitration(String provider, String cache, String direct) {
        String best = null, src = null;
        long rev = Long.MIN_VALUE;
        if (provider != null && !provider.trim().isEmpty()) { best = provider; src = "provider"; rev = revOf(provider); }
        if (cache != null && !cache.trim().isEmpty()) {
            long r = revOf(cache);
            if (best == null || r > rev) { best = cache; src = "cache"; rev = r; }
        }
        if (best == null) {
            if (direct != null && !direct.trim().isEmpty()) { best = direct; src = "conf"; rev = revOf(direct); }
        }
        return new String[]{src, String.valueOf(rev), String.valueOf(best)};
    }

    /** v2.6：direct 参与仲裁。 */
    static String[] newArbitration(String provider, String cache, String direct) {
        String best = null, src = null;
        long rev = Long.MIN_VALUE;
        if (provider != null && !provider.trim().isEmpty()) { best = provider; src = "provider"; rev = revOf(provider); }
        if (cache != null && !cache.trim().isEmpty()) {
            long r = revOf(cache);
            if (best == null || r > rev) { best = cache; src = "cache"; rev = r; }
        }
        if (direct != null && !direct.trim().isEmpty()) {
            long r = revOf(direct);
            if (best == null || r > rev) { best = direct; src = "conf"; rev = r; }
        }
        return new String[]{src, String.valueOf(rev), String.valueOf(best)};
    }

    static int passed = 0, failed = 0;
    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }

    public static void main(String[] args) {
        final long OLD = 1794395546289L;
        final long NEW = 1794416312234L;
        String p = "rev=" + OLD + "\nenable_status=0\n";          // remoteText 残留（旧）
        String c = "rev=" + OLD + "\nenable_status=0\n";          // 快照（同样旧）
        String d = "rev=" + NEW + "\nenable_drama_top=1\n";       // direct（新，含用户刚改的键）

        // T-01：旧逻辑确实会卡在旧 rev（证明这个 bug 真实存在）
        String[] o = oldArbitration(p, c, d);
        check("T-01 v2.5 旧逻辑卡在旧 provider rev=" + OLD,
                "provider".equals(o[0]) && Long.parseLong(o[1]) == OLD);

        // T-02：新逻辑选中 direct，且 rev 是新的
        String[] n = newArbitration(p, c, d);
        check("T-02 v2.6 选中 direct src=conf", "conf".equals(n[0]));
        check("T-03 v2.6 rev 推进到 " + NEW, Long.parseLong(n[1]) == NEW);
        check("T-04 选中的文本包含新键 enable_drama_top=1", n[2].contains("enable_drama_top=1"));

        // T-05：rev > appliedRev → 会真正 apply（不再被 return 短路）
        check("T-05 newVersion=true（rev > appliedRev）", Long.parseLong(n[1]) > OLD);

        // T-06：滞后的旧 direct 不能把配置回滚（rev < appliedRev 被挡）
        String staleDirect = "rev=" + (OLD - 100000L) + "\nenable_status=1\n";
        String[] s = newArbitration(p, c, staleDirect);
        check("T-06 滞后 direct 不覆盖 provider", Long.parseLong(s[1]) == OLD);

        // T-07：provider 为新、direct 为旧时，仍应选 provider（正常态不被破坏）
        String[] normal = newArbitration(d, c, p);
        check("T-07 provider 领先时保持 provider", "provider".equals(normal[0]) && Long.parseLong(normal[1]) == NEW);

        // T-08：全通道为空时不出错
        String[] empty = newArbitration(null, null, null);
        check("T-08 全通道为空不抛异常", empty[2].equals("null") && Long.parseLong(empty[1]) == Long.MIN_VALUE);

        // T-09：direct 文本没有 rev 行时不能因 revOf=0 误胜（provider 领先）
        String noRev = "enable_status=1\n";
        String[] nr = newArbitration(p, c, noRev);
        check("T-09 无 rev 的 direct 不误判为更新", Long.parseLong(nr[1]) == OLD);

        System.out.println("\n结果: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
