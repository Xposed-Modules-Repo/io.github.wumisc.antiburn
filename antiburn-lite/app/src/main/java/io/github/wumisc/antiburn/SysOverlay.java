package io.github.wumisc.antiburn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.Collections;
import java.util.List;

/**
 * 在 SystemUI 进程内挂一层全屏透明覆盖层，按屏幕比例盖黑 App 内固定 UI。
 *
 * <p><b>为什么需要这条通道（v2.8.3）</b>：真机日志连续多版只有
 * {@code INJECTED SystemUI}，抖音 / 快手 / 红果 / B站 进程零 {@code INJECTED}、
 * 零 {@code HOOK-ATTACH}、零 {@code CONF}；同时全篇没有一条 {@code MASK} 行，
 * 说明无障碍服务也没连上。也就是说：真隐藏依赖的「注入目标进程」走不通，
 * 免 root 兜底依赖的「无障碍服务」又没开，两条路同时断，用户看到的就是
 * 「抖音无效、快手只生效一次、B站只有右侧生效」。</p>
 *
 * <p>唯一稳定成立的事实是：<b>模块每次都成功注入 SystemUI</b>（状态栏确实在周期
 * 隐藏，{@code hid=true/false} 交替）。SystemUI 是系统进程，持有
 * {@code SYSTEM_ALERT_WINDOW}，可以直接 {@code addView} 一个
 * {@code TYPE_APPLICATION_OVERLAY} 窗口盖在 App 之上，不需要 root 之外的东西，
 * 也不需要用户去开无障碍。</p>
 *
 * <p><b>安全边界</b>：任何一步失败（{@code SecurityException} /
 * {@code BadTokenException} / OOM）都会立即撤窗并把 {@link #broken} 置位，
 * 本次进程不再重试 —— SystemUI 崩了等于状态栏没了，代价远比「少盖一层黑」大。
 * 窗口带 {@code FLAG_NOT_TOUCHABLE}，只压像素不吃触摸，点赞收藏照常可点。</p>
 */
final class SysOverlay {

    private static final String TAG = "AntiBurnOvl";

    /** 应用级覆盖层：层级在 App 之上、状态栏 / 导航栏之下。 */
    private static final int TYPE_APP_OVERLAY = 2038;
    /** 老 ROM 不认 2038 时的退路（系统进程持有 INTERNAL_SYSTEM_WINDOW）。 */
    private static final int TYPE_SYSTEM_ALERT = 2003;

    private static WindowManager wm;
    private static MaskView view;
    /** 已成功 addView，后续只需切 visibility。 */
    private static boolean attached;
    /** 加窗失败过：本进程内彻底放弃，避免每 2 秒抛一次异常。 */
    private static boolean broken;
    private static String lastSig = "";
    private static long lastLogAt = 0L;

    private SysOverlay() {
    }

    /**
     * 每轮 tick 调用一次，线程必须是主线程（Controller 的 handler 所在线程）。
     *
     * @param ctx      宿主 Context（SystemUI 的 Context）
     * @param top      当前前台包名
     * @param show     true = 需要盖黑；false = 收起（切到非目标 App、显示相位、暂停）
     * @param topInset 顶部让出高度（状态栏高度 px）
     */
    static void tick(Context ctx, String top, boolean show, int topInset) {
        if (broken || ctx == null) {
            return;
        }
        VideoApps.Kind kind = VideoApps.of(top);
        int mask = 0;
        if (show && kind != VideoApps.Kind.NONE && Config.enableSysOverlay) {
            mask = Config.appAreaMask(kind);
        }
        if (mask == 0) {
            if (attached && view != null && view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                log("hide app=" + kind.name().toLowerCase() + " reason=nomask", true);
            }
            return;
        }

        try {
            int[] wh = screenSize(ctx);
            List<int[]> rects = AppRegions.rects(wh[0], wh[1], topInset, mask);
            if (rects.isEmpty()) {
                return;
            }
            if (!attached) {
                attach(ctx);
            }
            if (!attached || view == null) {
                return;
            }
            view.setRects(rects);
            if (view.getVisibility() != View.VISIBLE) {
                view.setVisibility(View.VISIBLE);
            }
            String sig = top + "|" + mask + "|" + wh[0] + "x" + wh[1];
            if (!sig.equals(lastSig)) {
                lastSig = sig;
                log("on app=" + kind.name().toLowerCase() + " areas=" + AppRegions.label(mask)
                        + " top=" + top + " screen=" + wh[0] + "x" + wh[1]
                        + " inset=" + topInset + " rects=" + rects.size(), true);
            }
        } catch (Throwable t) {
            fail("tick", t);
        }
    }

    /** 撤窗（如用户关掉开关、切引擎）。失败也只记录，不抛。 */
    static void release() {
        try {
            if (wm != null && view != null && attached) {
                wm.removeViewImmediate(view);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "release failed", t);
        }
        attached = false;
        view = null;
        lastSig = "";
    }

    private static void attach(Context ctx) {
        try {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                throw new IllegalStateException("no WindowManager");
            }
            MaskView v = new MaskView(ctx);
            WindowManager.LayoutParams lp = buildParams(TYPE_APP_OVERLAY);
            try {
                wm.addView(v, lp);
                writeTypeDiag(TYPE_APP_OVERLAY);
            } catch (Throwable first) {
                // 某些 ROM 会拒 2038（要求 AppOps 授权），系统进程改用 2003 通常能过
                android.util.Log.w(TAG, "addView 2038 failed, retry 2003", first);
                lp = buildParams(TYPE_SYSTEM_ALERT);
                wm.addView(v, lp);
                writeTypeDiag(TYPE_SYSTEM_ALERT);
            }
            view = v;
            attached = true;
        } catch (Throwable t) {
            broken = true;
            fail("attach", t);
        }
    }

    private static WindowManager.LayoutParams buildParams(int type) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                // 不吃触摸、不抢焦点、铺满整屏（含刘海与手势区）
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = 0;
        lp.setTitle("antiburn-app-overlay");
        try {
            // 让覆盖层铺到刘海后面，否则顶部标题区会被裁掉一截
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        } catch (Throwable ignored) {
        }
        try {
            // API 30+：默认会被系统栏 inset 缩掉上下，这里显式放开
            lp.setFitInsetsTypes(0);
        } catch (Throwable ignored) {
        }
        return lp;
    }

    private static int[] screenSize(Context ctx) {
        int w = 0;
        int h = 0;
        try {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        } catch (Throwable ignored) {
        }
        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = new DisplayMetrics();
            try {
                wm.getDefaultDisplay().getRealMetrics(dm);
                w = dm.widthPixels;
                h = dm.heightPixels;
            } catch (Throwable ignored) {
            }
        }
        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        return new int[]{w, h};
    }

    private static void fail(String where, Throwable t) {
        String msg = t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage());
        log("FAILED " + where + " " + msg, true);
        android.util.Log.e(TAG, "overlay " + where, t);
    }

    private static void writeTypeDiag(int type) {
        log("window attached type=" + type, true);
    }

    /** 状态变化立刻写；同一状态 30 秒一条心跳，避免每 2 秒刷盘。 */
    private static void log(String s, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastLogAt < 30000L) {
            return;
        }
        lastLogAt = now;
        try {
            XposedInit.diagForce("OVL " + s);
        } catch (Throwable ignored) {
        }
    }

    /** 只画黑、不做任何交互的覆盖层。 */
    private static final class MaskView extends View {
        private final Paint paint = new Paint();
        private List<int[]> rects = Collections.emptyList();

        MaskView(Context c) {
            super(c);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            paint.setAntiAlias(false);
            setWillNotDraw(false);
        }

        void setRects(List<int[]> next) {
            if (AppRegions.same(rects, next)) {
                return;
            }
            rects = next;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            List<int[]> rs = rects;
            for (int i = 0; i < rs.size(); i++) {
                int[] r = rs.get(i);
                canvas.drawRect(r[0], r[1], r[2], r[3], paint);
            }
        }
    }
}
