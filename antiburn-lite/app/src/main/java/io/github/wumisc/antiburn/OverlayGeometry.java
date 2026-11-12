package io.github.wumisc.antiburn;

/**
 * 遮盖区域计算：纯函数，不依赖任何 Android 类，可直接在 JVM 里断言。
 *
 * <p>把几何计算从 {@link BurnInMaskService} 里抽出来的理由很实际：这类「差 1dp 就露一条白光」
 * 的问题在真机上极难复查，而它本身只是一组整数加减。抽成纯函数后，横屏 / 手势条 / 三键 /
 * 刘海 / 异常高度全部能在单测里穷举，不必等三台真机。</p>
 *
 * <p>坐标系与 {@link android.view.WindowManager} 一致：原点在屏幕左上角，y 向下为正，
 * 单位是像素（已经乘过 density）。</p>
 */
public final class OverlayGeometry {

    private OverlayGeometry() {
    }

    /** 导航栏贴边位置 */
    public static final int NAV_BOTTOM = 0;
    public static final int NAV_RIGHT = 1;
    public static final int NAV_LEFT = 2;

    /** 小于此边长的区域视为无效，不画（避免 1px 线被当成「已生效」） */
    public static final int MIN_SIDE = 4;

    /** 一块整数矩形。用自定义类而不是 android.graphics.Rect，是为了能在纯 JVM 单测里构造。 */
    public static final class Rect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Rect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }

        public boolean isEmpty() {
            return width() < MIN_SIDE || height() < MIN_SIDE;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Rect)) {
                return false;
            }
            Rect r = (Rect) o;
            return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
        }

        @Override
        public int hashCode() {
            int h = left;
            h = 31 * h + top;
            h = 31 * h + right;
            h = 31 * h + bottom;
            return h;
        }

        @Override
        public String toString() {
            return "[" + left + "," + top + " " + right + "x" + bottom + "]";
        }
    }

    /**
     * 状态栏遮盖区：屏幕顶部整宽。
     *
     * @param screenW         屏幕宽
     * @param screenH         屏幕高（用于异常高度兜底）
     * @param statusBarHeight 状态栏高度（像素）。取不到时传 0，返回 null 表示「这条不用遮」。
     */
    public static Rect statusBar(int screenW, int screenH, int statusBarHeight) {
        if (screenW <= 0 || screenH <= 0 || statusBarHeight <= 0) {
            return null;
        }
        // 状态栏不可能高过屏幕的 1/4：超过说明取到的是别的尺寸
        // （有 ROM 的 status_bar_height 在分屏 / 折叠态会返回整窗高度），
        // 照画会把用户主要内容涂黑。
        int h = Math.min(statusBarHeight, Math.max(MIN_SIDE, screenH / 4));
        Rect r = new Rect(0, 0, screenW, h);
        return r.isEmpty() ? null : r;
    }

    /**
     * 导航栏遮盖区。
     *
     * @param screenW    屏幕宽
     * @param screenH    屏幕高
     * @param navBarSize 导航栏厚度（底部模式是高度，侧边模式是宽度）
     * @param side       贴边位置：{@link #NAV_BOTTOM} / {@link #NAV_RIGHT} / {@link #NAV_LEFT}
     */
    public static Rect navigationBar(int screenW, int screenH, int navBarSize, int side) {
        if (screenW <= 0 || screenH <= 0 || navBarSize <= 0) {
            return null;
        }
        Rect r;
        if (side == NAV_RIGHT) {
            // 侧边导航栏宽度同样设上限：手势条 6dp、三键 48dp，超过屏幕 1/3 的一定是错值
            int w = Math.min(navBarSize, Math.max(MIN_SIDE, screenW / 3));
            r = new Rect(screenW - w, 0, screenW, screenH);
        } else if (side == NAV_LEFT) {
            int w = Math.min(navBarSize, Math.max(MIN_SIDE, screenW / 3));
            r = new Rect(0, 0, w, screenH);
        } else {
            int h = Math.min(navBarSize, Math.max(MIN_SIDE, screenH / 3));
            r = new Rect(0, screenH - h, screenW, screenH);
        }
        return r.isEmpty() ? null : r;
    }

    /**
     * 把系统报告的窗口 bounds 转成遮盖区，并做合理性校验。
     *
     * <p>用于「读得到系统栏真实位置」的机型：刘海、挖孔、横屏侧边三键这些情况下，
     * 资源里的 {@code status_bar_height} / {@code navigation_bar_height} 经常与实际不符
     * （HyperOS 上横屏 nav 高度会返回竖屏值），照资源画就会露边或盖住内容区。
     * 但系统上报也可能离谱（有的 ROM 把整屏都报成系统栏），因此校验范围后再采用。</p>
     *
     * @param screenW 屏幕宽
     * @param screenH 屏幕高
     * @param l       系统上报 left
     * @param t       系统上报 top
     * @param r       系统上报 right
     * @param b       系统上报 bottom
     * @return 合法的遮盖区，或 null（不可用时调用方退回资源估算）
     */
    public static Rect fromSystemBounds(int screenW, int screenH, int l, int t, int r, int b) {
        if (screenW <= 0 || screenH <= 0) {
            return null;
        }
        // 夹到屏幕内，防止负坐标或越界把 canvas 画到不存在的地方
        int cl = Math.max(0, Math.min(l, screenW));
        int ct = Math.max(0, Math.min(t, screenH));
        int cr = Math.max(0, Math.min(r, screenW));
        int cb = Math.max(0, Math.min(b, screenH));
        if (cr <= cl || cb <= ct) {
            return null;
        }
        int w = cr - cl;
        int h = cb - ct;
        // 系统栏的截面形态只有两种：整宽 + 薄（顶部状态栏 / 底部导航栏），
        // 或整高 + 薄（横屏侧边三键）。任何「宽高都很大」的上报都不是系统栏
        // ——最常见的是把整个应用窗口当成系统栏报上来，照画会把用户主要内容涂黑。
        boolean fullWidthThin = w >= screenW * 9 / 10 && h <= screenH / 4;
        boolean fullHeightThin = h >= screenH * 9 / 10 && w <= screenW / 4;
        if (!fullWidthThin && !fullHeightThin) {
            return null;
        }
        Rect rect = new Rect(cl, ct, cr, cb);
        return rect.isEmpty() ? null : rect;
    }

    /**
     * 判断某个系统栏窗口位于屏幕哪一侧（底部 / 右侧 / 左侧），用于横屏三键 vs 手势条。
     *
     * @return {@link #NAV_BOTTOM} 等；无法判断返回 -1
     */
    public static int navSideOf(int screenW, int screenH, int l, int t, int r, int b) {
        if (screenW <= 0 || screenH <= 0) {
            return -1;
        }
        boolean atBottom = b >= screenH - 2 && t > screenH / 3;
        boolean atRight = r >= screenW - 2 && l > screenW / 3;
        boolean atLeft = l <= 2 && r < screenW / 3;
        if (atBottom) {
            return NAV_BOTTOM;
        }
        if (atRight) {
            return NAV_RIGHT;
        }
        if (atLeft) {
            return NAV_LEFT;
        }
        return -1;
    }
}
