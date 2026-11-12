package io.github.wumisc.antiburn;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 免 root 遮盖引擎：用无障碍覆盖层周期性盖住状态栏 / 导航栏，让那块 OLED 像素停发光。
 *
 * <h3>为什么是这个窗口类型</h3>
 * <p>{@link WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY} 的 z-order 高于状态栏与锁屏，
 * 这是普通应用能做到「盖住系统栏」的唯一合法途径；悬浮窗（TYPE_APPLICATION_OVERLAY）会被
 * 状态栏压住，盖不住。</p>
 *
 * <h3>和 Hook 引擎的能力差异（必须让用户知道）</h3>
 * <ul>
 *   <li>Hook 是「真隐藏」：把 View 的可见性改掉，那块区域由应用自己绘制内容。</li>
 *   <li>遮盖是「盖黑」：状态栏仍在，只是被一层不透明黑色挡住。视觉上等价于隐藏，
 *       但那块像素仍然在被合成（纯黑不发光，OLED 上耗电与烧屏风险同样归零）。</li>
 *   <li>做不到像素微移（{@link PixelShift}）：那需要改 SystemUI 内部布局。</li>
 * </ul>
 *
 * <h3>三条实现约束</h3>
 * <ol>
 *   <li>窗口必须 {@code FLAG_NOT_TOUCHABLE}：否则整屏触摸全被吞掉，用户会以为手机死机。</li>
 *   <li>窗口必须 {@code FLAG_LAYOUT_IN_SCREEN}：否则系统把内容限制在状态栏以下，盖不到状态栏。</li>
 *   <li>灭屏 / 锁屏时立刻撤掉：省电，也避免锁屏界面出现黑边。</li>
 * </ol>
 */
public class BurnInMaskService extends AccessibilityService {

    private static final String TAG = "AntiburnMask";

    /** 配置重读间隔：遮盖每 200ms 走一帧，但没必要每帧都读盘 */
    private static final long CONFIG_TTL_MS = 2000L;
    /** 状态机步进间隔。200ms 足够让周期切换误差落在人眼不可感的范围内，功耗可忽略 */
    private static final long TICK_MS = 200L;
    /** 预览遮盖时长：给用户验证「到底盖没盖住」用 */
    public static final long PREVIEW_MS = 5000L;

    // ---- 供同进程 UI 读取的运行状态（不给跨进程用，所以是普通静态字段） ----
    /** 服务是否已连接 */
    public static volatile boolean connected = false;
    /** 覆盖层窗口是否添加成功。false 且 connected=true 说明这台机器拒绝了无障碍覆盖层 */
    public static volatile boolean windowAttached = false;
    /** 最近一次失败原因，空串表示没有失败 */
    public static volatile String lastError = "";
    /** 当前是否正在遮盖（UI 自检用） */
    public static volatile boolean masking = false;
    /** 最近一次算出的遮盖区域描述，UI 自检直接显示，省得用户去翻日志 */
    public static volatile String lastRegions = "";

    private WindowManager wm;
    private PowerManager power;
    private KeyguardManager keyguard;
    private MaskView maskView;
    private WindowManager.LayoutParams lp;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 前台包名，来自 TYPE_WINDOW_STATE_CHANGED；拿不到时保持 null */
    private volatile String foregroundPkg;

    /** 当前相位开始时间与相位（true = 遮盖中） */
    private long phaseStart = 0L;
    private boolean masked = false;

    /**
     * 预览强制遮盖的截止时刻（uptimeMillis），0 表示没有预览。
     *
     * <p>做成静态字段而不是走广播：界面与服务在同一个进程里，广播要先跨进程排队、
     * 还要处理 RECEIVER_NOT_EXPORTED 的可见性规则，为了一次「盖 5 秒给人看」
     * 引入这些约束不值得。静态字段直达，服务没连上时界面直接读
     * {@link #connected} 就知道该不该让按钮可点。</p>
     */
    private static volatile long previewUntil = 0L;

    /** 界面点「预览遮盖」时调用：强制遮盖 5 秒，用来验证这台机器到底盖不盖得住。 */
    public static void requestPreview() {
        previewUntil = SystemClock.uptimeMillis() + PREVIEW_MS;
    }

    /** 配置缓存 */
    private long cfgAt = 0L;
    private Cfg cfg = new Cfg();

    /** 屏幕度量缓存，旋转 / 尺寸变化时置脏 */
    private boolean metricsDirty = true;
    private int screenW;
    private int screenH;
    private int statusBarH;
    private int navBarSize;
    private int navSide = OverlayGeometry.NAV_BOTTOM;


    /** 一份配置快照，避免每帧反复读盘 */
    private static final class Cfg {
        boolean masterOn = true;
        boolean navOn = false;
        /**
         * 配置里的运行引擎是不是 overlay。
         *
         * <p>默认 false（不遮）：配置读不到、或用户还停在 lsposed 引擎时，遮盖必须停手。
         * 两套引擎同时动手的结果不是「效果更好」，而是状态栏被 Hook 隐藏一次、
         * 再被这里盖一条黑边，比任何单套都难看。</p>
         */
        boolean maskAllowed = false;
        String mode = ConfStore.MODE_WHITE;
        Set<String> list = new HashSet<>();
        int hideSec = 30;
        int showSec = 10;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        lastError = "";
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        power = (PowerManager) getSystemService(POWER_SERVICE);
        keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        ConfStore.init(getApplicationContext());
        metricsDirty = true;
        attachWindow();
        handler.removeCallbacks(tick);
        phaseStart = SystemClock.uptimeMillis();
        handler.post(tick);
        Log.i(TAG, "service connected, windowAttached=" + windowAttached);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence p = event.getPackageName();
        if (p == null || p.length() == 0) {
            return;
        }
        String pkg = p.toString();
        // 无障碍设置页 / 权限弹窗也会发这个事件，它们不是「用户真正在用的应用」，
        // 用来判名单会把结果带偏。过滤掉系统级包名后缀。
        if (pkg.endsWith("permissioncontroller") || pkg.equals("com.android.settings")) {
            return;
        }
        foregroundPkg = pkg;
    }

    @Override
    public void onInterrupt() {
        // 无障碍服务被系统中断（例如用户关掉服务）。窗口会在 onUnbind 里撤掉。
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转后状态栏高度 / 导航栏位置全变了，必须重算，否则会露边或盖住内容
        metricsDirty = true;
        applyMask();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        teardown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    // ---- 窗口 ----

    private void attachWindow() {
        if (wm == null) {
            lastError = "拿不到 WindowManager";
            return;
        }
        if (maskView != null) {
            return;
        }
        maskView = new MaskView(this);
        lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 不吃任何 inset：否则系统会把窗口裁到状态栏以下，状态栏那条永远盖不住
            lp.setFitInsetsTypes(0);
        }
        try {
            wm.addView(maskView, lp);
            windowAttached = true;
            lastError = "";
        } catch (Throwable t) {
            windowAttached = false;
            lastError = "覆盖层被系统拒绝：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
            Log.w(TAG, "addView failed", t);
            maskView = null;
        }
    }

    private void detachWindow() {
        if (maskView != null && wm != null) {
            try {
                wm.removeViewImmediate(maskView);
            } catch (Throwable ignored) {
                // 已经不在窗口上，忽略
            }
        }
        maskView = null;
        windowAttached = false;
        masking = false;
    }

    private void teardown() {
        connected = false;
        handler.removeCallbacks(tick);
        detachWindow();
    }

    // ---- 状态机 ----

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (Throwable t) {
                // 状态机里任何异常都不许把服务打死：吞掉但记日志，下一帧继续
                Log.w(TAG, "tick error", t);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void step() {
        long now = SystemClock.uptimeMillis();

        // 预览优先：用户点了「预览遮盖」，无条件盖住 5 秒
        if (previewUntil > now) {
            masked = true;
            applyMask();
            return;
        }

        boolean screenOn = power == null || power.isInteractive();
        boolean locked = keyguard != null && keyguard.isKeyguardLocked();
        if (!screenOn || locked) {
            // 灭屏 / 锁屏：撤掉遮盖，省电也避免锁屏界面出现黑边
            if (masked) {
                masked = false;
            }
            phaseStart = now;
            applyMask();
            return;
        }

        Cfg c = config(now);
        boolean want = c.maskAllowed
                && c.masterOn
                && ListRules.shouldIntervene(c.mode, c.list, foregroundPkg);
        if (!want) {
            if (masked) {
                masked = false;
            }
            phaseStart = now;
            applyMask();
            return;
        }

        long dur = (masked ? c.showSec : c.hideSec) * 1000L;
        if (now - phaseStart >= dur) {
            masked = !masked;
            phaseStart = now;
        }
        applyMask();
    }

    private Cfg config(long now) {
        if (now - cfgAt < CONFIG_TTL_MS) {
            return cfg;
        }
        cfgAt = now;
        Cfg c = new Cfg();
        try {
            String text = ConfStore.read();
            if (text != null) {
                ConfStore.Lists lists = ConfStore.readLists(text);
                c.mode = lists.mode;
                c.list = new HashSet<>(lists.active());
                for (String line : text.split("\n")) {
                    String t = line.trim();
                    int i = t.indexOf('=');
                    if (i <= 0) {
                        continue;
                    }
                    String k = t.substring(0, i).trim();
                    String v = t.substring(i + 1).trim();
                    if ("enable_status".equals(k)) {
                        c.masterOn = "1".equals(v);
                    } else if ("engine".equals(k)) {
                        c.maskAllowed = ConfStore.ENGINE_OVERLAY.equals(ConfStore.normEngine(v));
                    } else if ("enable_navigation".equals(k)) {
                        c.navOn = "1".equals(v);
                    } else if ("hide_duration".equals(k)) {
                        c.hideSec = clampDur(v, c.hideSec, 5);
                    } else if ("show_duration".equals(k)) {
                        c.showSec = clampDur(v, c.showSec, 1);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "read config failed", t);
        }
        cfg = c;
        return c;
    }

    private static int clampDur(String v, int def, int min) {
        try {
            int n = Integer.parseInt(v.trim());
            return Math.max(min, Math.min(3600, n));
        } catch (Throwable t) {
            return def;
        }
    }

    // ---- 绘制 ----

    private void applyMask() {
        if (maskView == null) {
            masking = false;
            return;
        }
        if (!masked) {
            maskView.setMasked(false);
            masking = false;
            return;
        }
        OverlayGeometry.Rect top = null;
        OverlayGeometry.Rect bottom = null;
        // 预览广播可能先于下一次 tick 到达，这里再取一次（有 2s 缓存，不会真的反复读盘）
        Cfg c = config(SystemClock.uptimeMillis());
        if (metricsDirty) {
            refreshMetrics();
        }
        // 注意：这里不做「系统栏当前是否可见」的判定。AccessibilityWindowInfo 只暴露
        // bounds / layer / isActive，没有任何可见性 API，靠 bounds 猜会把全屏视频里
        // 真实存在但透明显示的状态栏判成「不可见」而漏遮。宁可多遮一条，也不要
        // 在用户以为已生效时静默失效 —— 漏遮的代价是烧屏，多遮的代价只是看视频时
        // 顶部有黑边，且用户可以自己关掉状态栏那一项。
        if (c.masterOn) {
            top = OverlayGeometry.statusBar(screenW, screenH, statusBarH);
        }
        if (c.navOn) {
            bottom = OverlayGeometry.navigationBar(screenW, screenH, navBarSize, navSide);
        }
        if (top == null && bottom == null) {
            // 两条都没算出来（尺寸异常 / 全都判为不可见）：不画，并记下原因
            maskView.setMasked(false);
            masking = false;
            lastError = "无可遮盖区域（状态栏高 " + statusBarH + "px，导航栏 " + navBarSize + "px）";
            return;
        }
        maskView.setRects(top, bottom);
        maskView.setMasked(true);
        masking = true;
        lastError = "";
        lastRegions = (top == null ? "状态栏：关闭" : "状态栏：" + top)
                + "；" + (bottom == null ? "导航栏：关闭" : "导航栏：" + bottom);
    }

    private void refreshMetrics() {
        metricsDirty = false;
        try {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            screenW = b.width();
            screenH = b.height();
        } catch (Throwable t) {
            // 兜底：老 API 或异常时用资源尺寸
            screenW = getResources().getDisplayMetrics().widthPixels;
            screenH = getResources().getDisplayMetrics().heightPixels;
        }

        boolean landscape = screenW > screenH;

        // 1) 先试着读系统栏真实 bounds（最准，能正确处理刘海、横屏侧边三键）
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) {
                        continue;
                    }
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    boolean isTop = r.top <= 2 && r.bottom <= screenH / 3;
                    if (isTop) {
                        OverlayGeometry.Rect g = OverlayGeometry.fromSystemBounds(
                                screenW, screenH, r.left, r.top, r.right, r.bottom);
                        if (g != null) {
                            statusBarH = g.height();
                        }
                        continue;
                    }
                    int side = OverlayGeometry.navSideOf(screenW, screenH, r.left, r.top, r.right, r.bottom);
                    if (side >= 0) {
                        OverlayGeometry.Rect g = OverlayGeometry.fromSystemBounds(
                                screenW, screenH, r.left, r.top, r.right, r.bottom);
                        if (g != null) {
                            navSide = side;
                            navBarSize = (side == OverlayGeometry.NAV_BOTTOM) ? g.height() : g.width();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "read system bars failed", t);
        }

        // 2) 读不到就按资源估算（部分 ROM 不给 TYPE_SYSTEM 窗口）
        if (statusBarH <= 0) {
            statusBarH = dimen("status_bar_height");
        }
        if (navBarSize <= 0) {
            navBarSize = landscape
                    ? dimen("navigation_bar_height_landscape")
                    : dimen("navigation_bar_height");
            if (navBarSize <= 0) {
                navBarSize = dimen("navigation_bar_height");
            }
        }
        if (navSide == OverlayGeometry.NAV_BOTTOM && landscape) {
            // 横屏三键导航栏多在右侧；手势条仍在底部。用系统设置区分。
            if (!isGestureNav()) {
                navSide = OverlayGeometry.NAV_RIGHT;
            }
        }
        Log.i(TAG, "metrics " + screenW + "x" + screenH + " status=" + statusBarH
                + " nav=" + navBarSize + " side=" + navSide);
    }

    private int dimen(String name) {
        try {
            int id = getResources().getIdentifier(name, "dimen", "android");
            return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 手势导航返回 true。
     *
     * <p>{@code navigation_mode} 是 2 表示手势、0/1 表示按键。读 Secure 设置不需要权限。
     * 读不到时按手势处理（Android 13+ 出厂默认手势），因为手势条的遮盖区域更小，
     * 猜错只是少盖一点，不会盖到内容区。</p>
     */
    private boolean isGestureNav() {
        try {
            return Settings.Secure.getInt(getContentResolver(), "navigation_mode", 2) == 2;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 遮盖层本体：一个全屏透明 View，只在需要时画两块黑矩形。 */
    private static final class MaskView extends View {
        private final Paint paint = new Paint();
        private OverlayGeometry.Rect top;
        private OverlayGeometry.Rect bottom;
        private boolean masked;

        MaskView(Context context) {
            super(context);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            // 关闭抗锯齿：边缘要落在整像素上，否则会有一条半透明灰边（那圈灰边照样烧屏）
            paint.setAntiAlias(false);
            setWillNotDraw(false);
            setVisibility(INVISIBLE);
        }

        void setRects(OverlayGeometry.Rect top, OverlayGeometry.Rect bottom) {
            this.top = top;
            this.bottom = bottom;
        }

        void setMasked(boolean on) {
            if (masked != on) {
                masked = on;
                invalidate();
            }
            // INVISIBLE 而不是 setAlpha(0)：不可见时 View 完全不参与绘制，
            // 合成器直接跳过这一层，比透明层省一次全屏合成。
            setVisibility(on ? VISIBLE : INVISIBLE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!masked) {
                return;
            }
            if (top != null) {
                canvas.drawRect(top.left, top.top, top.right, top.bottom, paint);
            }
            if (bottom != null) {
                canvas.drawRect(bottom.left, bottom.top, bottom.right, bottom.bottom, paint);
            }
        }
    }
}
