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
    /**
     * v2.7.9：把遮盖引擎状态写进用户能导出的 antiburn.log。
     *
     * <p>旧版遮盖服务的所有结论只走 {@link Log#i}（logcat），而用户导出的
     * antiburn.log 是 Xposed 侧写的，里面一条遮盖记录都没有 —— 「切到
     * 无障碍遮盖也没反应」根本无从判断是「服务没连上」「覆盖层被系统拒绝」
     * 还是「黑名单模式下当前 app 本来就不该遮盖」。这里低频补上，
     * 直写共享目录，失败再借 su 兜底，全程 try/catch，绝不影响遮盖主逻辑。</p>
     */
    private static volatile long lastMaskDiagAt = 0L;
    private static volatile String lastMaskSig = "";

    /**
     * v2.8.1：su 兜底必须离开主线程。
     *
     * <p>ConfStore.execSu 内部 waitFor 最长 4 秒，root 授权弹窗还可能更久。而
     * maskDiagForce("connected") 正好在 onServiceConnected 主线程 —— 直写日志一旦
     * 失败（分区存储 / 目录被回收），一次 su 就把主线程卡住，系统随即判 ANR，
     * 表现为「无障碍服务崩溃、开关被自动关掉」。改为后台单线程串行执行，
     * 同一时刻只允许一个 su 任务，既不阻塞也不堆积。</p>
     */
    private static final java.util.concurrent.ExecutorService suIo =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "antiburn-mask-log");
                t.setDaemon(true);
                return t;
            });
    private static final java.util.concurrent.atomic.AtomicBoolean suPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void maskDiag(String s) {
        maskDiag(s, false);
    }

    private static void maskDiagForce(String s) {
        maskDiag(s, true);
    }

    private static void maskDiag(String s, boolean force) {
        long now = System.currentTimeMillis();
        if (!force) {
            if (s.equals(lastMaskSig) && now - lastMaskDiagAt < 30000L) {
                return; // 状态没变：30 秒一条心跳，避免每帧写盘
            }
        }
        lastMaskSig = s;
        lastMaskDiagAt = now;
        String line = now + " [mask/" + android.os.Process.myPid() + "] MASK " + s + "\n";
        boolean ok = false;
        try {
            java.io.File f = new java.io.File(ConfStore.LOG_FILE);
            java.io.File parent = f.getParentFile();
            if (parent != null && (parent.exists() || parent.mkdirs())) {
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true)) {
                    fo.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                ok = true;
            }
        } catch (Throwable ignored) {
        }
        if (!ok && suPending.compareAndSet(false, true)) {
            // v2.8.1：分区存储 / 目录权限不足时借 root 补一条，但只在后台跑。
            try {
                final String b64 = android.util.Base64.encodeToString(
                        line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        android.util.Base64.NO_WRAP);
                suIo.execute(() -> {
                    try {
                        ConfStore.execSu("echo " + b64 + " | base64 -d >> \""
                                + ConfStore.LOG_FILE + "\" 2>/dev/null; true");
                    } catch (Throwable ignored) {
                    } finally {
                        suPending.set(false);
                    }
                });
            } catch (Throwable t) {
                suPending.set(false);
            }
        }
        Log.i(TAG, "MASK " + s);
    }

    /**
     * v2.8.1：无障碍服务的回调异常绝不能冒到系统框架。
     *
     * <p>AccessibilityService 的 onServiceConnected / onAccessibilityEvent 等回调由
     * framework 跨进程调用，任何未捕获异常都会被系统记为「无障碍服务崩溃」，
     * 随即断开绑定、把开关自动关掉 —— 这正是用户看到的「无障碍崩溃」。
     * 这里兜住异常并写进可导出的 antiburn.log（带堆栈），下次导日志即可定位。</p>
     */
    private static void reportCallbackCrash(String where, Throwable t) {
        try {
            String stack = android.util.Log.getStackTraceString(t).replace('\n', '|');
            Log.e(TAG, "callback crash @" + where, t);
            // 非 force：状态签名节流，避免 step 每帧抛异常时刷屏写盘
            maskDiag("CRASH " + where + " " + t.getClass().getName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()) + " | " + stack);
        } catch (Throwable ignored) {
        }
    }

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
        /** 配置里的引擎原文，落盘诊断用（v2.8.1：别再把 lsposed 显示成 other） */
        String engineName = "?";
        String mode = ConfStore.MODE_WHITE;
        Set<String> list = new HashSet<>();
        int hideSec = 30;
        int showSec = 10;
        /**
         * v2.8.2 稳定版：应用内固定区域兜底遮盖总开关（enable_app_overlay，默认开）。
         *
         * <p>真机日志实证：模块只被 LSPosed 注入 SystemUI，抖音 / 快手 / B站 进程
         * 连一条 INJECTED / HOOK-ATTACH 都没有，DouyinHook 整个跑不到，App 内分区
         * 隐藏 100% 失效。这条通道不注入目标进程、不需要 root，由无障碍覆盖层在
         * App 自身进程按屏幕比例画黑固定 UI 区域，与引擎选择无关：
         *  - eng=overlay：状态栏 / 导航栏照旧由遮盖层盖黑；
         *  - eng=lsposed：系统栏交给 SystemUI 真隐藏（这里不重复画），
         *    App 内区域由这里兜底，两条通道互补而不是互相覆盖。</p>
         */
        boolean appAreaOn = true;
        /** 当前前台应用归属；NONE 表示不是四个目标视频 App，不画应用内区域。 */
        VideoApps.Kind appKind = VideoApps.Kind.NONE;
        /** 用户是否显式配置过该 App 的区域开关；没配过就按默认三区全画，保证开箱可用。 */
        boolean appExplicit = false;
        boolean appRight = true;
        boolean appTop = true;
        boolean appBottom = true;
        boolean appCorner = false;
        /** 配置文本里各 App 的区域开关原值，供 resolveApp() 按前台包名挑选。 */
        final java.util.Map<String, Boolean> appSw = new java.util.HashMap<>();
    }

    /**
     * v2.8.2：按当前前台包名挑出这一帧要画的应用内区域。
     *
     * <p>不进 config() 的 TTL 缓存：前台切换会立刻插一帧 tick，但配置文本有 2~3 秒
     * 缓存，若把 appKind 一起缓存，切进抖音后要等缓存过期才画，用户会当成「没生效」。
     * 这里每帧现算，只有几个布尔判断和一次前缀匹配，开销可忽略。</p>
     *
     * <p>兜底语义：该 App 四个区域开关全没开过（或全为 0）时，按默认三区全画。
     * 真机日志里 B站四个 enable_bili_* 从来都是 0、快手也多为 0 —— 若严格按开关画，
     * 用户装完依旧什么都看不到。稳定版先保证「装上就有防护」，需要精调再到
     * 功能页按 App 逐区关闭，或直接关掉「应用内区域兜底」。</p>
     */
    private static void resolveApp(Cfg c, String fgPkg) {
        VideoApps.Kind kind = VideoApps.of(fgPkg);
        if (!c.appAreaOn || kind == VideoApps.Kind.NONE) {
            c.appKind = VideoApps.Kind.NONE;
            c.appRight = c.appTop = c.appBottom = c.appCorner = false;
            return;
        }
        c.appKind = kind;
        String p;
        switch (kind) {
            case DOUYIN:
                p = "dy_";
                break;
            case DRAMA:
                p = "dr_";
                break;
            case KUAISHOU:
                p = "ks_";
                break;
            case BILIBILI:
                p = "bi_";
                break;
            default:
                c.appRight = c.appTop = c.appBottom = c.appCorner = false;
                return;
        }
        boolean r = on(c, p + "right");
        boolean t = on(c, p + "top");
        boolean b = on(c, p + "bottom");
        boolean corner = on(c, p + "corner");
        c.appExplicit = r || t || b || corner;
        if (!c.appExplicit) {
            // 没显式配过（或全关）：右侧列 + 顶部默认画；corner 不默认画（容易盖住返回键）
            r = true;
            t = true;
            // 红果底部是「完整观看 / 选集」等可点入口，盖住会点不进去，默认不画
            b = kind != VideoApps.Kind.DRAMA;
            corner = false;
        }
        c.appRight = r;
        c.appTop = t;
        c.appBottom = b;
        c.appCorner = corner;
    }

    private static boolean on(Cfg c, String k) {
        Boolean v = c.appSw.get(k);
        return v != null && v;
    }

    /** 同一目标位可能由两个配置键合并（如 dy_corner = 右上角 || 左上角），用 OR 聚合。 */
    private static void putSw(Cfg c, String k, String v, String srcKey, String dst) {
        if (!srcKey.equals(k)) {
            return;
        }
        boolean val = "1".equals(v);
        Boolean prev = c.appSw.get(dst);
        c.appSw.put(dst, (prev != null && prev) || val);
    }

    @Override
    protected void onServiceConnected() {
        try {
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
            maskDiagForce("connected");
        } catch (Throwable t) {
            reportCallbackCrash("onServiceConnected", t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
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
        } catch (Throwable t) {
            reportCallbackCrash("onAccessibilityEvent", t);
        }
    }

    @Override
    public void onInterrupt() {
        // 无障碍服务被系统中断（例如用户关掉服务）。窗口会在 onUnbind 里撤掉。
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        try {
            super.onConfigurationChanged(newConfig);
            // 旋转后状态栏高度 / 导航栏位置全变了，必须重算，否则会露边或盖住内容。
            // v2.7.5：覆盖层可能因空闲被撤掉（maskView 为空），applyMask 会直接返回，
            // 因此这里改成主动排一帧，由 step 决定要不要重建窗口并重算尺寸。
            metricsDirty = true;
            handler.removeCallbacks(tick);
            handler.post(tick);
        } catch (Throwable t) {
            reportCallbackCrash("onConfigurationChanged", t);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            teardown();
            return super.onUnbind(intent);
        } catch (Throwable t) {
            reportCallbackCrash("onUnbind", t);
            return false;
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (instance == this) {
                instance = null;
            }
            teardown();
            super.onDestroy();
        } catch (Throwable t) {
            reportCallbackCrash("onDestroy", t);
        }
    }

    // ---- 窗口 ----

    /** 上次尝试添加覆盖层的时间，失败后按 3s 节流重试，避免每帧刷日志 */
    private long lastAttachTry = 0L;

    /**
     * v2.8.1 窗口滞回：mayDraw 转 false 后延迟撤窗。
     *
     * <p>真机日志里前台在名单内/外一秒一切换，mayDraw 跟着翻转，旧逻辑每帧
     * addView / removeViewImmediate 一个全屏 TYPE_ACCESSIBILITY_OVERLAY ——
     * ColorOS 上这种高频窗口增删是 framework 判崩的高发点。窗口留着时
     * applyMask 会把 MaskView 置 INVISIBLE（不参与合成），多挂 3 秒不影响功耗。</p>
     */
    private static final long DETACH_HYSTERESIS_MS = 3000L;
    private long lastMayDrawAt = 0L;

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
            maskDiagForce("window attached");
        } catch (Throwable t) {
            windowAttached = false;
            lastError = "覆盖层被系统拒绝：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
            maskDiagForce("window FAILED " + lastError);
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
        lastMayDrawAt = 0L;
        handler.removeCallbacks(tick);
        detachWindow();
        maskDiagForce("teardown");
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
                reportCallbackCrash("step", t);
            }
            try {
                handler.postDelayed(this, Math.max(200L, next));
            } catch (Throwable t) {
                reportCallbackCrash("tick.postDelayed", t);
            }
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
        resolveApp(c, foregroundPkg);

        // v2.8.2：两条通道分开判，互不覆盖。
        //  sysBar  —— 系统栏遮盖，只在 overlay 引擎下画；LSPosed 引擎的系统栏由
        //             SystemUI 真隐藏负责，这里再画一条黑边只会重影。
        //  appArea —— 应用内固定 UI 兜底，不看引擎、不要求目标进程被注入，
        //             只要无障碍服务连着就能画。真机日志显示视频 App 进程零注入，
        //             这一条是那些设备上唯一能生效的通道。
        boolean intervene = ListRules.shouldIntervene(c.mode, c.list, foregroundPkg);
        boolean sysBar = c.maskAllowed && (c.statusBarOn || c.navOn);
        boolean appArea = c.appAreaOn && c.appKind != VideoApps.Kind.NONE
                && (c.appRight || c.appTop || c.appBottom || c.appCorner);
        boolean mayDraw = screenOn && !locked && c.masterOn && intervene
                && (sysBar || appArea);
        // v2.7.9：一条日志说清「为什么没盖」——是没开无障碍、引擎没切、
        // 总闸关着、名单没命中，还是覆盖层被系统拒了。
        maskDiag("screen=" + screenOn + " lock=" + locked
                + " master=" + c.masterOn + " eng=" + c.engineName
                + " mode=" + c.mode + " fg=" + foregroundPkg
                + " hit=" + intervene + " mayDraw=" + mayDraw
                + " win=" + windowAttached + " masking=" + masking
                + " sb=" + c.statusBarOn + " nav=" + c.navOn
                + " sys=" + sysBar + " app=" + VideoApps.label(c.appKind)
                + " appOn=" + c.appAreaOn
                + " areas=" + (c.appRight ? "R" : "-") + (c.appTop ? "T" : "-")
                + (c.appBottom ? "B" : "-") + (c.appCorner ? "C" : "-")
                + " fallback=" + (appArea && !sysBar)
                + (lastError.isEmpty() ? "" : " err=" + lastError)
                + (lastRegions.isEmpty() ? "" : " rects=" + lastRegions));
        if (preview || mayDraw) {
            // v2.7.4：覆盖层可能被系统撤掉（旋转 / 低内存 / 开机早期 addView 被拒）。
            // 旧版一辈子只 addView 一次，失败后 connected=true 但永远盖不上，
            // 用户只能去系统设置里把无障碍关开一次。这里轻量校验并自动重建。
            lastMayDrawAt = now;
            ensureWindow();
        } else if (windowAttached && now - lastMayDrawAt >= DETACH_HYSTERESIS_MS) {
            // v2.8.1：短时不需要画不立刻撤窗，避免前台切换引发秒级 add/remove。
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
            // v2.8.1：改用 ensureMirror()。ConfStore.read() 在镜像缺失时会
            // execSuCapture（最长 4s），而 config() 跑在主线程 tick —— 一旦镜像
            // 因清数据 / 首次安装读不到，每 3 秒一次 su 会直接把无障碍服务拖成 ANR。
            // ensureMirror 只读应用内镜像 + 默认值，全程本地文件 IO，绝不触发 su。
            String text = ConfStore.ensureMirror();
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
                        c.engineName = v;
                        c.maskAllowed = ConfStore.ENGINE_OVERLAY.equals(ConfStore.normEngine(v));
                    } else if ("enable_navigation".equals(k)) {
                        c.navOn = "1".equals(v);
                    } else if ("enable_status_bar".equals(k)) {
                        c.statusBarOn = "1".equals(v);
                    } else if ("hide_duration".equals(k)) {
                        c.hideSec = clampDur(v, c.hideSec, 5);
                    } else if ("show_duration".equals(k)) {
                        c.showSec = clampDur(v, c.showSec, 1);
                    } else if ("enable_app_overlay".equals(k)) {
                        c.appAreaOn = "1".equals(v);
                    } else {
                        // v2.8.2：把各 App 的区域开关收进一张表，resolveApp() 按
                        // 前台包名挑用。缺省键不进表 —— 缺键和显式 0 都算「没配过」，
                        // 由兜底规则决定默认画哪几块。
                        putSw(c, k, v, "enable_douyin_like", "dy_right");
                        putSw(c, k, v, "enable_douyin_top", "dy_top");
                        putSw(c, k, v, "enable_douyin_bottom", "dy_bottom");
                        putSw(c, k, v, "enable_douyin_right_top", "dy_corner");
                        putSw(c, k, v, "enable_douyin_left_top", "dy_corner");
                        putSw(c, k, v, "enable_drama_like", "dr_right");
                        putSw(c, k, v, "enable_drama_top", "dr_top");
                        putSw(c, k, v, "enable_drama_bottom", "dr_bottom");
                        putSw(c, k, v, "enable_drama_right_top", "dr_corner");
                        putSw(c, k, v, "enable_drama_left_top", "dr_corner");
                        putSw(c, k, v, "enable_ks_right", "ks_right");
                        putSw(c, k, v, "enable_ks_top", "ks_top");
                        putSw(c, k, v, "enable_ks_bottom", "ks_bottom");
                        putSw(c, k, v, "enable_ks_corner", "ks_corner");
                        putSw(c, k, v, "enable_bili_right", "bi_right");
                        putSw(c, k, v, "enable_bili_top", "bi_top");
                        putSw(c, k, v, "enable_bili_bottom", "bi_bottom");
                        putSw(c, k, v, "enable_bili_corner", "bi_corner");
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
        // v2.8.2：系统栏两条只在 overlay 引擎下画。LSPosed 引擎的系统栏由 SystemUI
        // 真隐藏负责，这里再画一条黑边就是重影；应用内区域则与引擎无关，照常画。
        if (c.maskAllowed && c.masterOn && c.statusBarOn) {
            top = OverlayGeometry.statusBar(screenW, screenH, statusBarH);
        }
        if (c.maskAllowed && c.navOn) {
            bottom = OverlayGeometry.navigationBar(screenW, screenH, navBarSize, navSide);
        }
        java.util.List<OverlayGeometry.Rect> areas = appRects(c);
        if (top == null && bottom == null && areas.isEmpty()) {
            // 一个区域都没算出来（尺寸异常 / 全都判为不可见）：不画，并记下原因
            maskView.setMasked(false);
            masking = false;
            lastError = "无可遮盖区域（状态栏高 " + statusBarH + "px，导航栏 "
                    + navBarSize + "px，屏幕 " + screenW + "x" + screenH + "）";
            return;
        }
        maskView.setRects(top, bottom, areas);
        maskView.setMasked(true);
        masking = true;
        lastError = "";
        lastRegions = (top == null ? "状态栏：关闭" : "状态栏：" + top)
                + "；" + (bottom == null ? "导航栏：关闭" : "导航栏：" + bottom)
                + "；应用内：" + (areas.isEmpty() ? "关闭" : "x" + areas.size()
                + "(" + VideoApps.label(c.appKind) + " "
                + (c.appRight ? "R" : "-") + (c.appTop ? "T" : "-")
                + (c.appBottom ? "B" : "-") + (c.appCorner ? "C" : "-") + ")");
    }

    /**
     * v2.8.2：按屏幕比例算出应用内固定 UI 区域。
     *
     * <p>比例来自真机竖屏截图（1080×2400 级）：右侧互动列从约 28% 高排到 92% 高、
     * 贴右缘约 16% 宽；顶部标题/搜索栏在状态栏下方约 11% 高；底部导航/弹幕输入栏
     * 在 90% 高以下。横屏沿用同一组比例（可能略宽，但宁可多遮一条也不漏遮 ——
     * 漏遮的代价是烧屏，多遮只是边缘多一条黑边）。</p>
     *
     * <p>这些矩形画在 TYPE_ACCESSIBILITY_OVERLAY 上，带 FLAG_NOT_TOUCHABLE，
     * 只挡住像素发光、不吃触摸，点赞/评论照样能点。</p>
     */
    private java.util.List<OverlayGeometry.Rect> appRects(Cfg c) {
        java.util.List<OverlayGeometry.Rect> out = new java.util.ArrayList<>();
        if (!c.appAreaOn || c.appKind == VideoApps.Kind.NONE || screenW <= 0 || screenH <= 0) {
            return out;
        }
        int w = screenW;
        int h = screenH;
        int sb = Math.max(0, statusBarH);
        if (c.appRight) {
            addRect(out, (int) (w * 0.84f), (int) (h * 0.28f), w, (int) (h * 0.92f));
        }
        if (c.appTop) {
            addRect(out, 0, sb, w, Math.max(sb + 8, (int) (h * 0.11f)));
        }
        if (c.appBottom) {
            addRect(out, 0, (int) (h * 0.90f), w, h);
        }
        if (c.appCorner) {
            addRect(out, (int) (w * 0.78f), sb, w, (int) (h * 0.20f));
            addRect(out, 0, sb, (int) (w * 0.20f), (int) (h * 0.20f));
        }
        return out;
    }

    private static void addRect(java.util.List<OverlayGeometry.Rect> out,
                                int l, int t, int r, int b) {
        OverlayGeometry.Rect rect = new OverlayGeometry.Rect(l, t, r, b);
        if (!rect.isEmpty()) {
            out.add(rect);
        }
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
        /** v2.8.2：应用内固定 UI 区域（右侧列 / 顶部 / 底部 / 角落），与系统栏两块一起画。 */
        private java.util.List<OverlayGeometry.Rect> appRects = java.util.Collections.emptyList();
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

        void setRects(OverlayGeometry.Rect top, OverlayGeometry.Rect bottom,
                      java.util.List<OverlayGeometry.Rect> areas) {
            // v2.7.5：矩形没变就不动。旧版每秒重算一次并直接覆盖字段，
            // 一旦给 setRects 补 invalidate（本版必需，否则改尺寸不重绘），
            // 就会变成每秒一次无意义全屏重绘。v2.8.2 把应用内矩形纳入同一个比较。
            java.util.List<OverlayGeometry.Rect> next =
                    areas == null ? java.util.Collections.<OverlayGeometry.Rect>emptyList() : areas;
            if (same(this.top, top) && same(this.bottom, bottom)
                    && this.appRects.equals(next)) {
                return;
            }
            this.top = top;
            this.bottom = bottom;
            this.appRects = next;
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
            for (OverlayGeometry.Rect r : appRects) {
                canvas.drawRect(r.left, r.top, r.right, r.bottom, paint);
            }
        }
    }
}
