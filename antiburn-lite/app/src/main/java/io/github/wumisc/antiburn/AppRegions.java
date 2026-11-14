package io.github.wumisc.antiburn;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用内固定区域的几何计算 —— 单一真源。
 *
 * <p>同一套比例必须同时被两条通道使用：</p>
 * <ul>
 *   <li>{@code BurnInMaskService}：无障碍覆盖层（免 root，需用户开启服务）；</li>
 *   <li>{@code SysOverlay}：SystemUI 进程内的覆盖层（LSPosed 引擎，不需无障碍）。</li>
 * </ul>
 *
 * <p>v2.8.3 抽出来的原因：v2.8.2 只在无障碍那条通道里算了区域，而真机日志显示
 * 无障碍服务根本没连上（全篇零 {@code MASK} 行），于是「应用内区域兜底」一次
 * 都没跑过。两处各写一份比例，改一处漏一处必然导致「这个 App 有效那个无效」，
 * 因此这里只保留一份纯函数，两边都调它。</p>
 *
 * <p>比例按 1080×2400 级别的竖屏标定：</p>
 * <ul>
 *   <li>右侧列 {@code R}：x 84%→100%，y 28%→92%（点赞 / 评论 / 收藏 / 分享竖排）；</li>
 *   <li>顶部 {@code T}：状态栏下沿 → 11% 高度（标题、搜索框、顶部 Tab）；</li>
 *   <li>底部 {@code B}：y 90%→100%（底部导航栏、发布按钮）；</li>
 *   <li>角落 {@code C}：右上 + 左上各一块（悬浮活动入口）。</li>
 * </ul>
 */
public final class AppRegions {

    /** 位掩码：与配置里的四区开关一一对应 */
    public static final int R = 1;
    public static final int T = 2;
    public static final int B = 4;
    public static final int C = 8;

    private AppRegions() {
    }

    /** 把四个开关压成位掩码，日志里直接打成 R-T-B-C 便于核对。 */
    public static int mask(boolean right, boolean top, boolean bottom, boolean corner) {
        return (right ? R : 0) | (top ? T : 0) | (bottom ? B : 0) | (corner ? C : 0);
    }

    /** 位掩码转人类可读串：R\-\-B 形式，与诊断日志一致。 */
    public static String label(int mask) {
        StringBuilder sb = new StringBuilder(4);
        sb.append((mask & R) != 0 ? 'R' : '-');
        sb.append((mask & T) != 0 ? 'T' : '-');
        sb.append((mask & B) != 0 ? 'B' : '-');
        sb.append((mask & C) != 0 ? 'C' : '-');
        return sb.toString();
    }

    /**
     * 计算要盖黑的像素矩形，每项 {@code {left, top, right, bottom}}。
     *
     * @param w        屏幕宽（px）
     * @param h        屏幕高（px）
     * @param topInset 顶部让出的高度（px），通常是状态栏高度；传 0 表示从屏幕最顶开始
     * @param mask     {@link #mask} 的结果
     * @return 非空矩形列表；参数非法或 mask 为 0 时返回空列表
     */
    public static List<int[]> rects(int w, int h, int topInset, int mask) {
        List<int[]> out = new ArrayList<>();
        if (w <= 0 || h <= 0 || mask == 0) {
            return out;
        }
        int inset = Math.max(0, Math.min(topInset, h / 4));
        if ((mask & R) != 0) {
            add(out, (int) (w * 0.84f), (int) (h * 0.28f), w, (int) (h * 0.92f));
        }
        if ((mask & T) != 0) {
            add(out, 0, inset, w, Math.max(inset + 8, (int) (h * 0.11f)));
        }
        if ((mask & B) != 0) {
            add(out, 0, (int) (h * 0.90f), w, h);
        }
        if ((mask & C) != 0) {
            add(out, (int) (w * 0.78f), inset, w, (int) (h * 0.20f));
            add(out, 0, inset, (int) (w * 0.20f), (int) (h * 0.20f));
        }
        return out;
    }

    private static void add(List<int[]> out, int l, int t, int r, int b) {
        if (r > l && b > t) {
            out.add(new int[]{l, t, r, b});
        }
    }

    /** 两份矩形列表在像素层面是否等价，用于跳过无意义的重绘。 */
    public static boolean same(List<int[]> a, List<int[]> b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            int[] x = a.get(i);
            int[] y = b.get(i);
            if (x[0] != y[0] || x[1] != y[1] || x[2] != y[2] || x[3] != y[3]) {
                return false;
            }
        }
        return true;
    }
}
