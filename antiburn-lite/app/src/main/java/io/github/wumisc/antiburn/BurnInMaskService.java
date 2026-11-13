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

    /** 配置重读间隔：遮盖每秒走一帧，没必要每帧都读盘 */
    private static final long CONFIG_TTL_MS = 3000L;
    /**
     * 状态机步进间隔。
     *
     * <p><b>v2.7.5 功耗修复</b>：旧版 200ms，即主线程每秒醒 5 次，2.7.4 又给每帧加了
     * {@code ensureWindow()} 与一次配置判定。真机耗电统计里本模块 AVG 4.91W，
     * 与「常驻每秒 5 帧」完全吻合 —— 遮盖周期本来就是按秒计的，
     * 相位切换误差 1 秒人眼完全看不出，没必要为此常驻 5Hz。现在降到 1 秒。</p>
     */
    private static final long TICK_MS = 1000L;
    /** 空转间隔：灭屏 / 锁屏 / 没开总开关 / 当前不是遮盖引擎时，只做低频确认。 */
    private static final long IDLE_TICK_MS = 5000L;
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

    /** 服务实例引用：预览要把周期从「等待下一帧」提前到「立刻画一帧」。 */
    private static volatile BurnInMaskService instance;

    /** 界面点「预览遮盖」时调用：强制遮盖 5 秒，用来验证这台机器到底盖不盖得住。 */
    public static void requestPreview() {
        previewUntil = SystemClock.uptimeMillis() + PREVIEW_MS;
        // v2.7.5：步进从 200ms 放宽到 1s 后，预览若只等下一帧最多要白等 1 秒，
        // 用户会以为按钮没反应。这里直接插一次 tick，体验与旧版一致。
        BurnInMaskService s = instance;
        if (s != null) {
            s.handler.removeCallbacks(s.tick);
            s.handler.post(s.tick);
        }
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
         * 「状态栏」独立开关（enable_status_bar）。
         *
         * <p>旧版遮盖引擎只认总闸，用户在功能页把状态栏关掉、只留导航栏，
         * 顶部照样被盖黑 —— 同一个开关在两套引擎下行为不一致，用户只能认为它坏了。</p>
         */
        boolean statusBarOn = true;
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
        instance = this;
        // v2.7.5：不再一连接就把全屏覆盖层挂上去。旧版无论用户选哪条引擎都先 addView，
        // LSPosed 用户等于白多一层全屏合成；现在由第一帧 step 判定「真的需要画」才建窗。
        handler.removeCallbacks(tick);
        phaseStart = SystemClock.uptimeMillis();
        handler.post(tick);
        Log.i(TAG, "service connected");
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
        if (!pkg.equals(foregroundPkg)) {
            foregroundPkg = pkg;
            // v2.7.5：空转期间是 5 秒一帧，若只等下一帧，切进目标应用后要 5 秒才盖上，
            // 用户会当成「没生效」。这里立刻插一帧，响应仍是一次事件驱动，不增加常驻唤醒。
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    @Override
    public void onInterrupt() {
        // 无障碍服务被系统中断（例如用户关掉服务）。窗口会在 onUnbind 里撤掉。
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转后状态栏高度 / 导航栏位置全变了，必须重算，否则会露边或盖住内容。
        // v2.7.5：覆盖层可能因空闲被撤掉（maskView 为空），applyMask 会直接返回，
        // 因此这里改成主动排一帧，由 step 决定要不要重建窗口并重算尺寸。
        metricsDirty = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        teardown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        teardown();
        super.onDestroy();
    }

    // ---- 窗口 ----

    /** 上次尝试添加覆盖层的时间，失败后按 3s 节流重试，避免每帧刷日志 */
    private long lastAttachTry = 0L;

    /**
     * 确保覆盖层仍在窗口上：被系统移除 / 首次 addView 失败时自动重建。
     *
     * <p>不重建的后果比「没效果」更糟：{@link #connected} 仍是 true、界面看起来一切正常，
     * 但 applyMask 因为 maskView 为空直接 return，用户等一整晚也看不到任何遮盖。</p>
     */
    private void ensureWindow() {
        if (windowAttached && maskView != null && maskView.isAttachedToWindow()) {
            return;
        }
        if (maskView != null) {
            detachWindow();
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastAttachTry < 3000L) {
            return;
        }
        lastAttachTry = now;
        attachWindow();
        if (windowAttached) {
            Log.i(TAG, "overlay window (re)attached");
        }
    }

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
            long next = TICK_MS;
            try {
                next = step();
            } catch (Throwable t) {
                // 状态机里任何异常都不许把服务打死：吞掉但记日志，下一帧继续
                Log.w(TAG, "tick error", t);
            }
            handler.postDelayed(this, Math.max(200L, next));
        }
    };

    /**
     * 走一帧状态机，返回「下一帧建议间隔」。
     *
     * <p>v2.7.5：返回值代替固定 200ms，让空转场景自动降频 —— 这也是本版功耗修复的主体。
     * 灭屏、锁屏、总开关关闭、当前引擎不是遮盖（例如 root 用户选了 LSPosed）时，
     * 遮盖层根本不需要存在，每秒醒 5 次纯属白烧电。</p>
     */
    private long step() {
        long now = SystemClock.uptimeMillis();
        boolean preview = previewUntil > now;
        boolean screenOn = power == null || power.isInteractive();
        boolean locked = keyguard != null && keyguard.isKeyguardLocked();
        Cfg c = config(now);

        // 「这一帧有没有可能真的要画」——决定是否维持覆盖层窗口。
        // 旧版无论什么引擎都先把全屏覆盖层 addView 上去（INVISIBLE 也是一层），
        // LSPosed 用户等于白搭一层全屏合成，还会让系统认为本应用在持续绘制。
        boolean mayDraw = screenOn && !locked && c.masterOn && c.maskAllowed
                && ListRules.shouldIntervene(c.mode, c.list, foregroundPkg);
        if (preview || mayDraw) {
            // v2.7.4：覆盖层可能被系统撤掉（旋转 / 低内存 / 开机早期 addView 被拒）。
            // 旧版一辈子只 addView 一次，失败后 connected=true 但永远盖不上，
            // 用户只能去系统设置里把无障碍关开一次。这里轻量校验并自动重建。
            ensureWindow();
        } else if (windowAttached) {
            detachWindow();
        }

        // 预览优先：用户点了「预览遮盖」，无条件盖住 5 秒
        if (preview) {
            masked = true;
            applyMask();
            return 500L;
        }

        if (!screenOn || locked) {
            // 灭屏 / 锁屏：撤掉遮盖，省电也避免锁屏界面出现黑边
            if (masked) {
                masked = false;
            }
            phaseStart = now;
            applyMask();
            return IDLE_TICK_MS;
        }

        if (!mayDraw) {
            if (masked) {
                masked = false;
            }
            phaseStart = now;
            applyMask();
            return IDLE_TICK_MS;
        }

        // masked = 正在遮盖（系统栏被盖住）。遮盖中要等「隐藏时长」结束才恢复，
        // 未遮盖时要等「显示时长」结束才盖 —— 旧版写反，实际变成了露 30s、盖 10s，
        // 防护时长只有设计值的 1/3，刚进应用还先裸奔一整个隐藏周期。
        long dur = (masked ? c.hideSec : c.showSec) * 1000L;
        if (now - phaseStart >= dur) {
            masked = !masked;
            phaseStart = now;
        }
        applyMask();
        // 尽量睡到相位边界，减少无意义唤醒；上限 1 秒保证响应开关变化。
        long remain = dur - (now - phaseStart);
        return Math.min(TICK_MS, Math.max(250L, remain));
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
                    } else if ("enable_status_bar".equals(k)) {
                        c.statusBarOn = "1".equals(v);
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
        if (c.masterOn && c.statusBarOn) {
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

        // v2.7.4：每次重算前先复位。
        // 横屏三键导航会把 navSide 设成 RIGHT；转回竖屏若不清零，会继续按「右侧栏」
        // 绘制，把竖屏右侧整条内容区涂黑（用户看到的是「屏幕右边突然黑了一条」）。
        statusBarH = 0;
        navBarSize = 0;
        navSide = OverlayGeometry.NAV_BOTTOM;

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
            // v2.7.5：矩形没变就不动。旧版每秒重算一次并直接覆盖字段，
            // 一旦给 setRects 补 invalidate（本版必需，否则改尺寸不重绘），
            // 就会变成每秒一次无意义全屏重绘。
            if (same(this.top, top) && same(this.bottom, bottom)) {
                return;
            }
            this.top = top;
            this.bottom = bottom;
            if (masked) {
                invalidate();
            }
        }

        private static boolean same(OverlayGeometry.Rect a, OverlayGeometry.Rect b) {
            if (a == null || b == null) {
                return a == b;
            }
            return a.left == b.left && a.top == b.top
                    && a.right == b.right && a.bottom == b.bottom;
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
