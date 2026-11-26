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

    /**
     * 配置重读间隔。
     *
     * <p>v3.2.3 功耗：3 秒 → 8 秒；v3.2.5 再放宽到 15 秒。这个值决定「多久读一次配置」，
     * 每次都是 <b>读盘 + 全文 split(32 行) + 逐行解析</b>，在遮盖常驻的整段时间里
     * 一直重复。用户改完开关靠 {@link #invalidateConfig()} 立刻失效缓存，
     * 所以 TTL 只影响「外部进程改配置」这条罕见路径，体感依然是即时的。</p>
     */
    private static final long CONFIG_TTL_MS = 15_000L;
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
    /**
     * v3.2.2：收到通知后顶部区让开时长。
     *
     * <p>无障碍通道是「盖黑」：顶部那块不透明黑矩形 z-order 高于 heads-up 横幅，
     * 横幅弹在状态栏下方时正好被它盖住，用户看不到消息。无障碍服务原先只监听
     * typeWindowStateChanged，根本不知道有通知弹出；这里收到通知事件就让开 10 秒，
     * 横幅收起后再恢复遮盖。开关是通用页「通知弹出时让开」（默认开）。</p>
     */
    private static final long YIELD_NOTIFY_MS = 10_000L;
    /**
     * 应用内遮盖通道（v3.0.16 重新启用）。
     *
     * <p>v2.8.6 曾在 LSPosed 引擎下硬关这条通道（黑块压画面、退出后残留）。
     * v3.0.16 起改为「仅无障碍（overlay）引擎生效」：免 root 设备没有位置微移，
     * 周期遮盖是唯一能保护抖音 / 红果 / 快手 / B站固定区域的通道。
     * LSPosed 引擎仍不铺黑，由 Hook 的隐藏/微移负责。</p>
     */

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

    /**
     * v3.2.16：无障碍探针（NDUMP）—— 目标 App 前台时导出「固定区域」的真实节点，
     * 一行一个 class / view-id / bounds / text。
     *
     * <p>为什么是这条通道：被注入进程（抖音 / 快手 / 红果）写不了公共存储，su 又常被拒，
     * 于是 RDUMP / BDUMP 这类探针连续四份日志一条都没落下来，快手的红点和右侧列只能靠猜。
     * 无障碍服务本来就有 {@code canRetrieveWindowContent}，读到的正是同一棵 UI 树，
     * 而且本进程对公共日志有写权限 —— 不用 root、不用导出、不用等补录。</p>
     */
    private static final long DUMP_MIN_GAP_MS = 10 * 60_000L;
    private static final long DUMP_AFTER_MS = 1500L;
    private static final int DUMP_MAX_LINES = 120;
    private volatile String lastDumpPkg;
    private volatile long lastDumpAt = 0L;

    /** 当前相位开始时间与相位（true = 遮盖中） */
    private long phaseStart = 0L;
    private boolean masked = false;

    /**
     * v3.2.3 功耗：灭屏后是否「已停车」。
     *
     * <p>旧版灭屏后仍按 {@code IDLE_TICK_MS}（5 秒）永久自我重排 —— 一夜 8 小时
     * 约 5760 次唤醒 + 5760 次配置读盘（每次都是文件 IO + 全文解析）。屏幕黑着
     * 本来什么都不用画，这些唤醒纯属白烧电。现在灭屏/锁屏那一帧撤完窗就直接
     * 停止排队，等 {@link #screenReceiver} 的亮屏广播再把自己叫醒。</p>
     */
    private boolean idleParked = false;

    /**
     * v3.2.3 功耗：屏幕状态广播。
     *
     * <p>停车之后必须有人能把 tick 重新叫起来，否则亮屏后遮盖永久不恢复。
     * 只用三个系统广播：亮屏 / 灭屏 / 解锁，都是 framework 常驻发送，不需要
     * 额外的部分唤醒锁，也不会让进程常驻活跃。</p>
     */
    private final android.content.BroadcastReceiver screenReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, android.content.Intent it) {
                    if (it == null) {
                        return;
                    }
                    String a = it.getAction();
                    if (android.content.Intent.ACTION_SCREEN_OFF.equals(a)) {
                        idleParked = false;
                        handler.removeCallbacks(tick);
                        handler.post(tick);          // 走一帧：撤窗后由 step 停车
                    } else if (android.content.Intent.ACTION_SCREEN_ON.equals(a)
                            || android.content.Intent.ACTION_USER_PRESENT.equals(a)) {
                        cfgAt = 0L;                  // 顺手重读配置，避免亮屏后还用灭屏前的旧值
                        lockedAt = 0L;               // 解锁/亮屏后立刻重查，别信缓存
                        idleParked = false;
                        handler.removeCallbacks(tick);
                        handler.post(tick);
                    }
                }
            };

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
    /** v3.2：去抖用的比较签名（把前台包名归一化，避免系统插件进程把写盘频率拉满）。 */
    private static volatile String lastMaskCmp = "";

    /**
     * v2.8.1：su 兜底必须离开主线程。
     *
     * <p>ConfStore.execSu 内部 waitFor 最长 4 秒，root 授权弹窗还可能更久。而
     * maskDiagForce("connected") 正好在 onServiceConnected 主线程 —— 直写日志一旦
     * 失败（分区存储 / 目录被回收），一次 su 就把主线程卡住，系统随即判 ANR，
     * 表现为「无障碍服务崩溃、开关被自动关掉」。改为后台单线程串行执行，
     * 同一时刻只允许一个 su 任务，既不阻塞也不堆积。</p>
     */
    /** v3.2.24：探针写盘专用单线程。与 suIo 分开，避免重日志把掩码诊断挤后面。 */
    private static final java.util.concurrent.ExecutorService probeIo =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "antiburn-probe-io");
                t.setDaemon(true);
                return t;
            });

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
        // v3.2：**前台包名不参与「是否写盘」的判定**。
        //
        // 真机日志里 MASK 行每 2~3 秒一条，原因不是心跳太密，而是签名里的 fg 在抖：
        // com.smile.gifmaker → com.miui.securitycenter → miui.systemui.plugin →
        // com.tencent.wetype 之间来回跳。系统插件窗口一冒头就被当成「前台变了」，
        // 于是每次都老老实实写一行 —— 这是纯粹的白耗电，排障价值几乎为零。
        //
        // 现在比较时把 fg 段归一化成 fg=*：只有 eng / master / areas / masking
        // 这些**真正影响遮盖行为**的维度变化才落盘；fg 仍会出现在 30 秒心跳里，
        // 需要看的时候一条不少。
        String cmp = s.replaceAll("fg=\\S+", "fg=*");
        if (!force) {
            if (cmp.equals(lastMaskCmp) && now - lastMaskDiagAt < 30000L) {
                return; // 状态没变：30 秒一条心跳，避免每帧写盘
            }
        }
        lastMaskCmp = cmp;
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
    /** v3.2.2：通知让开的截止时刻（uptimeMillis）；0 = 无。 */
    private long yieldUntil = 0L;


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
        // v3.0.16：默认开。免 root 的 overlay 引擎没有位置微移，应用内遮盖是唯一防护；
        // LSPosed 引擎由 step() 里的 c.maskAllowed 拦掉，不会被铺黑。
        boolean appAreaOn = true;
        /** v3.2.2：通知弹出时顶部区让开（默认取 {@link Config#enableA11yYieldNotify}）。 */
        boolean yieldNotify = Config.enableA11yYieldNotify;
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
            // v3.2.3 功耗：亮/灭屏广播 —— 停车之后唯一的唤醒源。重复注册会抛
            // IllegalArgumentException，所以先试着注销一次再注册。
            try {
                android.content.IntentFilter f = new android.content.IntentFilter();
                f.addAction(android.content.Intent.ACTION_SCREEN_ON);
                f.addAction(android.content.Intent.ACTION_SCREEN_OFF);
                f.addAction(android.content.Intent.ACTION_USER_PRESENT);
                try {
                    unregisterReceiver(screenReceiver);
                } catch (Throwable ignored) {
                }
                registerReceiver(screenReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
            } catch (Throwable t) {
                Log.w(TAG, "screen receiver failed", t);
            }
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
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                // v3.2.2：无障碍是「盖黑」——顶部那块黑矩形会连悬浮通知横幅一起盖住。
                // 原先只监听窗口切换，收到通知事件就临时撤掉顶部区 10 秒，横幅消失后再盖上。
                if (cfg.yieldNotify) {
                    yieldUntil = SystemClock.uptimeMillis() + YIELD_NOTIFY_MS;
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                }
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
                // v3.0.24：把前台包名交给常驻通知的实时判断（非目标包只更新前台，不覆盖目标）
                ForegroundWatch.onForeground(this, pkg);
                maybeDumpNodes(pkg);
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

    // ---------- v3.2.16：无障碍探针（NDUMP） ----------

    /**
     * 目标 App 切到前台时排一次节点导出。只在「诊断日志」开关打开、且前台是本模块
     * 认识的视频 App 时跑；同包 10 分钟内只采一次，切页面不会反复扫树。
     */
    private void maybeDumpNodes(String pkg) {
        try {
            // v3.2.17（审计修正）：VideoApps.of() 对任何包都返回 Kind（未知是 Kind.NONE）,
            // 永远不为 null —— 旧断言 `== null` 恒假，等于没过滤。
            if (pkg == null || VideoApps.of(pkg) == VideoApps.Kind.NONE) {
                return;
            }
            long now = System.currentTimeMillis();
            String last = lastDumpPkg;
            if (pkg.equals(last) && now - lastDumpAt < DUMP_MIN_GAP_MS) {
                return;
            }
            // v3.2.19：探针不再要求「诊断日志」开关。
            // 真机日志 170 行里快手已在前台 8 次、NDUMP 仍是 0 —— 开关是关的，
            // 而用户恰恰是「为了拿到节点才开的模块」。改成只看「是不是视频 App」，
            // 同包 10 分钟一次、最多 120 行，成本比一次页面重绘还低。
            lastDumpPkg = pkg;
            lastDumpAt = now;
            // v3.2.20：先留一行「要跑了」，这样万一延迟任务没执行，日志能直接指出来
            emitProbe(now + " [a11y] NDUMP sched pkg=" + pkg
                    + " w=" + screenW + " h=" + screenH + "\n");
            // 等 1.5 秒让页面布局稳定，否则抓到的是上一页的树
            handler.postDelayed(() -> dumpNodesNow(pkg), DUMP_AFTER_MS);
        } catch (Throwable t) {
            // 旧版这里吞掉异常，探针失效时日志里一点痕迹都没有
            emitProbe(System.currentTimeMillis() + " [a11y] NDUMP sched-fail pkg=" + pkg
                    + " e=" + t.getClass().getSimpleName() + "\n");
        }
    }

    /**
     * v3.2.19：探针输出的唯一出口。
     *
     * <p>先走 `ConfStore.appendDirectText`（本进程直写 → 失败 su 追加）；两条都失败时
     * 直接写进模块私有镜像 `files/hook-injected.log` —— 这个文件一定是本进程可写的，
     * 下次导出/常驻补录会把它并回公共日志。这样「探针跑了但日志里没有」不会再发生。</p>
     */
    private void emitProbe(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final String body = text;
        // v3.2.24（审计修正）：写盘一律离开主线程。
        // appendDirectText 直写失败会走 suAppend → execSu 里 waitFor 最长 4 秒，
        // 而 emitProbe 的调用点全在无障碍服务主线程（step / onAccessibilityEvent / 延迟 dump）。
        // 一次 su 授权弹窗就足够把主线程卡到 ANR —— 与 3.2.1 那次「服务被判崩溃、开关自动关掉」同源。
        try {
            probeIo.execute(() -> {
                try {
                    if (ConfStore.appendDirectText(body)) {
                        return;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    java.io.File f = new java.io.File(getFilesDir(), ConfigProvider.HOOK_LOG_FILE);
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true)) {
                        fo.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
            // 线程池已关（服务销毁）：直接丢这一行，绝不在调用方线程上补写
        }
    }

    /**
     * 把「角落 / 顶栏 / 右侧列 / 底栏」四个固定区域里的节点落进公共日志。
     *
     * <p>行格式刻意压短：{@code r=right c=TextView id=com.smile.gifmaker:id/xxx b=1120,900,1170,950 t=1.4万}。
     * 快手左上红点和右侧赞/评/藏/转就在这几条里，拿到就能写精确规则。</p>
     */
    private void dumpNodesNow(String pkg) {
        int w = screenW, h = screenH;
        if (w <= 0 || h <= 0) {
            try {
                Rect b = wm.getCurrentWindowMetrics().getBounds();
                w = b.width();
                h = b.height();
            } catch (Throwable ignored) {
                // 兜底：资源尺寸（旧版这里直接 return，探针什么都没写就被吞了）
                try {
                    android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                    w = dm.widthPixels;
                    h = dm.heightPixels;
                } catch (Throwable ignored2) {
                    // 仍然拿不到
                }
            }
        }
        if (w <= 0 || h <= 0) {
            emitProbe(System.currentTimeMillis() + " [a11y] NDUMP fail pkg=" + pkg
                    + " no-metrics w=" + w + " h=" + h + "\n");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int scan = 0, top = 0, right = 0, bottom = 0, corner = 0;
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                emitProbe(System.currentTimeMillis() + " [a11y] NDUMP pkg="
                        + pkg + " empty（拿不到活动窗口，可能是全屏 SurfaceView / 悬浮窗覆盖）\n");
                return;
            }
            java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> q =
                    new java.util.ArrayDeque<>();
            q.add(root);
            int depthGuard = 0;
            while (!q.isEmpty() && scan < 3000 && depthGuard < 3000) {
                android.view.accessibility.AccessibilityNodeInfo n = q.poll();
                depthGuard++;
                if (n == null) {
                    continue;
                }
                scan++;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                int cx = (r.left + r.right) / 2;
                int cy = (r.top + r.bottom) / 2;
                boolean tiny = r.width() <= 0 || r.height() <= 0;
                if (!tiny && r.width() < w * 0.995f) {
                    String region = null;
                    // 左上角：红点 / 徽标所在（快手左上红点就在这块）
                    if (cx < w * 0.30f && cy < h * 0.16f) {
                        region = "corner";
                        corner++;
                    } else if (cy < h * 0.16f) {
                        region = "top";
                        top++;
                    } else if (cy > h * 0.86f) {
                        region = "bottom";
                        bottom++;
                    } else if (cx > w * 0.76f) {
                        region = "right";
                        right++;
                    }
                    if (region != null && sb.length() < 6000) {
                        sb.append(System.currentTimeMillis()).append(" [a11y] NDUMP r=").append(region)
                                .append(" c=").append(shortClass(n.getClassName()))
                                .append(" id=").append(shortId(n.getViewIdResourceName()))
                                .append(" b=").append(r.left).append(',').append(r.top).append(',')
                                .append(r.right).append(',').append(r.bottom);
                        CharSequence t = n.getText();
                        if (t != null && t.length() > 0) {
                            String s = t.toString().replace('\n', ' ').replace(' ', '_');
                            sb.append(" t=").append(s.length() > 12 ? s.substring(0, 12) : s);
                        }
                        sb.append('\n');
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    android.view.accessibility.AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) {
                        q.add(c);
                    }
                }
            }
        } catch (Throwable t) {
            sb.append(System.currentTimeMillis()).append(" [a11y] NDUMP pkg=").append(pkg)
                    .append(" failed=").append(t.getClass().getSimpleName()).append('\n');
        }
        try {
            String body = sb.toString();
            // 行数上限：一个页面一两百行就够定位，日志不能因为探针爆掉
            int nl = body.indexOf('\n');
            int cnt = 1;
            int cut = body.length();
            while (nl >= 0 && cnt < DUMP_MAX_LINES) {
                nl = body.indexOf('\n', nl + 1);
                cnt++;
            }
            if (nl >= 0) {
                cut = nl + 1;
            }
            if (cut < body.length()) {
                body = body.substring(0, cut);
            }
            String head = System.currentTimeMillis() + " [a11y] NDUMP begin pkg=" + pkg
                    + " nodes=" + scan + " corner=" + corner + " top=" + top
                    + " right=" + right + " bottom=" + bottom + " w=" + w + " h=" + h + "\n";
            String tail = System.currentTimeMillis() + " [a11y] NDUMP end pkg=" + pkg + "\n";
            emitProbe(head + body + tail);
        } catch (Throwable ignored) {
        }
    }

    private static String shortClass(CharSequence cn) {
        if (cn == null) {
            return "?";
        }
        String s = cn.toString();
        int i = s.lastIndexOf('.');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** 只留「包名之外的 id 名」，日志短一半，读起来也直观。 */
    private static String shortId(String id) {
        if (id == null || id.isEmpty()) {
            return "-";
        }
        int i = id.indexOf(":id/");
        return i >= 0 ? id.substring(i + 4) : id;
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
        idleParked = false;
        handler.removeCallbacks(tick);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
        }
        detachWindow();
        maskDiagForce("teardown");
    }

    /**
     * v3.2.3 功耗：界面保存配置后调用，立刻作废本进程内的配置缓存。
     *
     * <p>没有这个方法时，缓存只能等 {@link #CONFIG_TTL_MS} 自然过期，
     * 用户改完开关要盯着屏幕数秒才生效。现在保存即失效，缓存周期也可以放心拉长。</p>
     */
    public static void invalidateConfig() {
        BurnInMaskService s = instance;
        if (s == null) {
            return;
        }
        try {
            s.cfgAt = 0L;
            s.idleParked = false;
            s.handler.removeCallbacks(s.tick);
            s.handler.post(s.tick);
        } catch (Throwable ignored) {
        }
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
                // v3.2.3 功耗：灭屏停车后不再自我排队 —— 靠亮屏广播唤醒。
                if (!idleParked) {
                    handler.postDelayed(this, Math.max(200L, next));
                }
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
    /** v3.2.3 功耗：诊断串的「变化键」。每帧只算这一小串，状态没变就不构造完整句子。 */
    private String lastDiagKey = "";
    private long lastDiagKeyAt = 0L;

    /** v3.2.5：钥匙锁状态的 3 秒缓存 —— 灭屏/解锁广播都会重置它。 */
    private boolean lockedCached = false;
    private long lockedAt = 0L;

    private boolean isLockedCached(long now) {
        if (now - lockedAt < 3000L) {
            return lockedCached;
        }
        lockedAt = now;
        try {
            lockedCached = keyguard != null && keyguard.isKeyguardLocked();
        } catch (Throwable t) {
            lockedCached = false;
        }
        return lockedCached;
    }

    private long step() {
        idleParked = false;   // v3.2.3：走到这里说明有人叫醒了我们，先解除停车
        long now = SystemClock.uptimeMillis();
        boolean preview = previewUntil > now;
        boolean screenOn = power == null || power.isInteractive();
        // v3.2.5 功耗：KeyguardManager 是**跨进程调用**。旧版每帧查一次，
        // 1Hz 常驻等于每秒一次 binder 往返，屏亮着的时候它几乎永远是 false，
        // 却为此付出了整天的 IPC 与唤醒开销。改成 3 秒缓存 + 灭屏直接短路。
        boolean locked = screenOn && isLockedCached(now);
        Cfg c = config(now);
        resolveApp(c, foregroundPkg);
        // v3.2.20：探针的第二个触发点。窗口事件只在「包名变化」时来一次，
        // 如果进快手那一刻事件被系统合并掉、或包名没变（用户从首页退回播放页），
        // 事件路径就永远不触发。心跳这里每帧顺手问一次，同包 10 分钟一次，成本可忽略。
        if (foregroundPkg != null) {
            maybeDumpNodes(foregroundPkg);
        }

        // v2.8.2：两条通道分开判，互不覆盖。
        //  sysBar  —— 系统栏遮盖，只在 overlay 引擎下画；LSPosed 引擎的系统栏由
        //             SystemUI 真隐藏负责，这里再画一条黑边只会重影。
        //  appArea —— 应用内固定 UI 遮盖，仅无障碍（overlay）引擎生效：
        //             免 root 设备没有位置微移，周期遮盖是唯一能保护四个视频 App
        //             固定区域的通道。LSPosed 引擎不铺黑（c.maskAllowed=false），
        //             由 Hook 的分区隐藏 / 像素微移负责，避免黑块压画面。
        boolean intervene = ListRules.shouldIntervene(c.mode, c.list, foregroundPkg);
        boolean sysBar = c.maskAllowed && (c.statusBarOn || c.navOn);
        boolean appArea = c.maskAllowed && c.appAreaOn && c.appKind != VideoApps.Kind.NONE
                && (c.appRight || c.appTop || c.appBottom || c.appCorner);
        boolean mayDraw = screenOn && !locked && c.masterOn && intervene
                && (sysBar || appArea);
        // v3.2.3：让开状态只取一次时间，诊断键与正文共用同一个值（避免两处相差几毫秒）
        boolean yld = c.yieldNotify && SystemClock.uptimeMillis() < yieldUntil;
        // v2.7.9：一条日志说清「为什么没盖」——是没开无障碍、引擎没切、
        // 总闸关着、名单没命中，还是覆盖层被系统拒了。
        //
        // v3.2.3 功耗：旧版这段长串是**每帧无条件构造**的（拼接十几个字段），
        // 进 maskDiag 之后还要跑一次 replaceAll 正则归一化 fg 段。1Hz 常驻、
        // 灭屏 5 秒一帧，昼夜不停，纯属把电烧在字符串上。现在先算一个便宜的
        // 「变化键」，只有键变了或满 30 秒才构造完整诊断串。
        String diagKey = c.engineName + '|' + c.masterOn + '|' + intervene + '|' + mayDraw
                + '|' + masking + '|' + windowAttached + '|' + c.appKind
                + '|' + c.appAreaOn + '|' + appArea + '|' + yld
                + '|' + c.appRight + c.appTop + c.appBottom + c.appCorner
                + '|' + lastError.isEmpty();
        if (!diagKey.equals(lastDiagKey) || now - lastDiagKeyAt >= 30000L) {
            lastDiagKey = diagKey;
            lastDiagKeyAt = now;
            maskDiag("screen=" + screenOn + " lock=" + locked
                    + " master=" + c.masterOn + " eng=" + c.engineName
                    + " mode=" + c.mode + " fg=" + foregroundPkg
                    + " hit=" + intervene + " mayDraw=" + mayDraw
                    + " win=" + windowAttached + " masking=" + masking
                    + " sb=" + c.statusBarOn + " nav=" + c.navOn
                    + " sys=" + sysBar + " app=" + VideoApps.label(c.appKind)
                    + " appOn=" + c.appAreaOn
                    + " yield=" + yld
                    + " areas=" + (c.appRight ? "R" : "-") + (c.appTop ? "T" : "-")
                    + (c.appBottom ? "B" : "-") + (c.appCorner ? "C" : "-")
                    + " fallback=" + (appArea && !sysBar)
                    + (lastError.isEmpty() ? "" : " err=" + lastError)
                    + (lastRegions.isEmpty() ? "" : " rects=" + lastRegions));
        }
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
            // v3.2.3 功耗：这里不再 5 秒一醒，直接停车。亮屏 / 解锁广播会把 tick 叫回来。
            idleParked = true;
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
                    } else if ("enable_a11y_yield_notify".equals(k)) {
                        c.yieldNotify = "1".equals(v);
                    } else if ("enable_app_overlay".equals(k)) {
                        // v3.0.16：读取真实开关。仅 overlay 引擎下由 step() 放行，
                        // LSPosed 引擎仍不铺黑（尊重 v2.8.6 的「黑块压画面」反馈）。
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
            maskView.setRects(null, null, java.util.Collections.emptyList());
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
        long nowMs = SystemClock.uptimeMillis();
        int mask = AppRegions.mask(c.appRight, c.appTop, c.appBottom, c.appCorner);
        // v3.2.2：通知横幅期间让开顶部区（右侧列 / 底部 / 角落照旧遮）
        boolean yld = c.yieldNotify && nowMs < yieldUntil;
        if (yld) {
            mask &= ~AppRegions.T;
        }
        boolean topOn = c.maskAllowed && c.masterOn && c.statusBarOn;
        boolean navOn = c.maskAllowed && c.masterOn && c.navOn;
        // v3.2.5 功耗：**这一帧与上一帧完全一样就什么都不做**。
        // 旧版每秒都重建一个 ArrayList + 5 个 Rect，再交给 setRects 自己比对；
        // 相位内（隐藏 30 秒 / 显示 30 秒）这些值一个字都不会变，纯属每秒白算一遍。
        if (masking
                && drawW == screenW && drawH == screenH
                && drawSb == statusBarH && drawNav == navBarSize && drawSide == navSide
                && drawMask == mask && drawYld == yld
                && drawTopOn == topOn && drawNavOn == navOn
                && drawAppOn == c.appAreaOn && drawKind == c.appKind) {
            return;
        }
        // 注意：这里不做「系统栏当前是否可见」的判定。AccessibilityWindowInfo 只暴露
        // bounds / layer / isActive，没有任何可见性 API，靠 bounds 猜会把全屏视频里
        // 真实存在但透明显示的状态栏判成「不可见」而漏遮。宁可多遮一条，也不要
        // 在用户以为已生效时静默失效 —— 漏遮的代价是烧屏，多遮的代价只是看视频时
        // 顶部有黑边，且用户可以自己关掉状态栏那一项。
        // v2.8.2：系统栏两条只在 overlay 引擎下画。LSPosed 引擎的系统栏由 SystemUI
        // 真隐藏负责，这里再画一条黑边就是重影；应用内区域则与引擎无关，照常画。
        if (topOn) {
            top = OverlayGeometry.statusBar(screenW, screenH, statusBarH);
        }
        if (navOn) {
            bottom = OverlayGeometry.navigationBar(screenW, screenH, navBarSize, navSide);
        }
        java.util.List<OverlayGeometry.Rect> areas = appRects(c, mask);
        if (top == null && bottom == null && areas.isEmpty()) {
            // 一个区域都没算出来（尺寸异常 / 全都判为不可见）：不画，并记下原因
            // v2.8.6：连旧矩形一起清掉。只 setMasked(false) 时 appRects 字段还留着
            // 上一帧的五个黑块，下一次 setMasked(true) 会把它们原样复活。
            recordDrawState(mask, yld, topOn, navOn, c);
            maskView.setRects(null, null, java.util.Collections.emptyList());
            maskView.setMasked(false);
            masking = false;
            lastError = "无可遮盖区域（状态栏高 " + statusBarH + "px，导航栏 "
                    + navBarSize + "px，屏幕 " + screenW + "x" + screenH + "）";
            return;
        }
        recordDrawState(mask, yld, topOn, navOn, c);
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
    /**
     * v2.8.3：几何计算统一交给 {@link AppRegions}。
     *
     * <p>v2.8.2 这份比例是写死在本类里的，而 SystemUI 那条通道（SysOverlay）
     * 又需要同一套数字。两处各写一份的结果必然是「这个 App 有效那个无效」，
     * 所以这里不再自己算，只做类型转换。</p>
     */
    /** v3.2.5：记录「上一帧画了什么」，供 {@link #applyMask()} 做无变化短路。 */
    private int drawW = -1, drawH = -1, drawSb = -1, drawNav = -1, drawSide = -1, drawMask = -1;
    private boolean drawYld, drawTopOn, drawNavOn, drawAppOn;
    private VideoApps.Kind drawKind = VideoApps.Kind.NONE;

    private void recordDrawState(int mask, boolean yld, boolean topOn, boolean navOn, Cfg c) {
        drawW = screenW;
        drawH = screenH;
        drawSb = statusBarH;
        drawNav = navBarSize;
        drawSide = navSide;
        drawMask = mask;
        drawYld = yld;
        drawTopOn = topOn;
        drawNavOn = navOn;
        drawAppOn = c.appAreaOn;
        drawKind = c.appKind;
    }

    private java.util.List<OverlayGeometry.Rect> appRects(Cfg c, int mask) {
        java.util.List<OverlayGeometry.Rect> out = new java.util.ArrayList<>();
        if (!c.appAreaOn || c.appKind == VideoApps.Kind.NONE || screenW <= 0 || screenH <= 0) {
            return out;
        }
        for (int[] r : AppRegions.rects(screenW, screenH, Math.max(0, statusBarH), mask)) {
            addRect(out, r[0], r[1], r[2], r[3]);
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
