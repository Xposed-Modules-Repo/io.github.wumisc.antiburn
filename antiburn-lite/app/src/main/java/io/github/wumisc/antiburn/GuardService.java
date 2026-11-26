package io.github.wumisc.antiburn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v3.0.24：常驻运行通知（前台服务）。
 *
 * <p>用户诉求很明确：只要一条通知、不实时刷新（费电）、主要用来保后台，
 * 顺带在「注入/打开目标 App」时换个文案好看一点。所以这里刻意做了三件事：</p>
 *
 * <ul>
 *   <li><b>唯一通知</b>：固定 ID {@link #NOTIF_ID}，任何更新都是改同一条，
 *       不存在「一个 App 一条 / 一个引擎一条」的通知堆。</li>
 *   <li><b>低频刷新</b>：定时 3 小时一次；目标 App 切换时也只按
 *       {@link #MIN_EVENT_GAP_MS}（30 分钟）节流更新，短视频 App 反复进出不会刷屏耗电。</li>
 *   <li><b>前台服务</b>：{@code specialUse} 类型，START_STICKY，
 *       通知在后台存活，模块/无障碍服务被杀后更容易被系统拉回来。</li>
 * </ul>
 *
 * <p>文案只描述「最近保护了谁」，不写「正在保护」——目标 App 退出后
 * {@link ForegroundWatch} 的最后记录还在，写「正在」会骗人。</p>
 */
public final class GuardService extends Service {

    public static final String CHANNEL_ID = "antiburn_guard";
    public static final int NOTIF_ID = 1001;
    public static final String ACTION_STOP = "io.github.wumisc.antiburn.GUARD_STOP";

    /** 定时刷新：3 小时。纯保后台用途，不需要更密。 */
    private static final long REFRESH_MS = 3L * 3600_000L;
    /** 事件触发最小间隔：30 分钟。 */
    private static final long MIN_EVENT_GAP_MS = 30L * 60_000L;
    /** 超过这个时长就不再声称「最近保护」，退回待命文案。 */
    private static final long FRESH_MS = 24L * 3600_000L;
    /**
     * v3.2.14：日志自动补录间隔。
     *
     * <p>用户要的是「打开文件就是最新的」，不想每次点导出按钮等 toast。
     * 这条循环只把镜像里还没进公开日志的行补进去：没有新行直接返回（常态零开销），
     * 有新行时本进程直写一次，不 fork su。与通知刷新（3 小时）完全无关。</p>
     */
    private static final long LOG_SYNC_MS = 180_000L;

    private static volatile GuardService instance;
    private static volatile long lastPostAt;

    /**
     * v3.2.25：用户主动「停止」与「被系统杀掉」要区分 —— 前者不再自愈，
     * 否则用户点了停止、通知照样自己回来，等于把我们当成流氓软件。
     */
    private static volatile boolean stoppedByUser;

    /** 日志补录专用单线程：只做一次磁盘追加，不常驻忙等。 */
    private static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "antiburn-log-sync");
                t.setDaemon(true);
                return t;
            });

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                post();
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    /** v3.2.14：静默日志补录。不碰通知、不弹提示，只让公开日志自己保持最新。 */
    private final Runnable logSync = new Runnable() {
        @Override
        public void run() {
            // 读文件 + 写文件一律离开主线程：主线程只负责排下一次。
            try {
                IO.execute(() -> {
                    try {
                        ConfStore.autoSyncLog();
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, LOG_SYNC_MS);
        }
    };

    /** 幂等启动：App 打开、开机、无障碍连上都走这里。 */
    public static void ensure(Context ctx) {
        if (ctx == null) {
            return;
        }
        // v3.2.22：服务还活着就别再 startForegroundService。
        // 真机日志里 noteInject（宿主进程调我们的 Provider）每次都走这条路，
        // 崩溃重启期间会形成「起了没挂前台→FDNS 崩→再起」的循环。
        if (instance != null) {
            return;
        }
        try {
            Intent i = new Intent(ctx, GuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {
            // 通知权限被关 / 系统限制后台时 startForegroundService 可能抛异常，
            // 这里绝不能把调用方（无障碍回调、开机广播）带崩。
        }
    }

    public static void stop(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.stopService(new Intent(ctx, GuardService.class));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 目标 App 打开 / 注入时的轻量刷新。
     *
     * <p>从无障碍回调里调用，必须立刻返回：只做一次时间比较，
     * 真正构建通知由主线程 Handler 完成。</p>
     */
    public static void refreshIfDue(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPostAt < MIN_EVENT_GAP_MS) {
            return;
        }
        GuardService s = instance;
        if (s == null) {
            ensure(ctx);
            return;
        }
        s.handler.post(() -> {
            try {
                s.post();
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stoppedByUser = true;
            // 只置内存标志不够：闹钟是系统侧的，到点会把服务又拉起来 → 自己复活。
            HealReceiver.markUserStop(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        stoppedByUser = false;
        // 正常起来（用户打开 App / 点通知按钮 / 系统 START_STICKY）就是用户又要它跑了
        HealReceiver.clearUserStop(this);
        createChannel(this);
        // v3.2.22：**先用最小通知挂前台，再慢慢拼完整版**。
        //
        // 3.2.21 的真机日志里连续 6 次
        // FATAL RemoteServiceException$ForegroundServiceDidNotStartInTimeException，
        // 崩在 GuardService.ensure ← ConfigProvider.noteInject ← 宿主进程 call 我们。
        // 原因：onStartCommand 里同步走完 build() —— statusText 读 SharedPreferences、
        // IslandNotify.focusAllowed 还要跨进程 call 小米的 Provider，
        // 一旦这两步被阻塞，startForeground 就超过 5 秒没到，系统直接判 FDNS 崩进程。
        // 进程一崩，同进程的无障碍服务跟着死 —— 这也是掩码心跳与 NDUMP 一起消失的原因。
        try {
            startForeground(NOTIF_ID, quickBuild(this));
            lastPostAt = SystemClock.elapsedRealtime();
            // 服务回来了：撤掉「已停止」那条兜底通知，否则用户会同时看到两条互相打脸的通知
            HealReceiver.cancelBlocked(this);
        } catch (Throwable t) {
            // 兜底：换单色矢量图再挂一次（自适应图标做 smallIcon 会 Invalid notification）
            try {
                startForeground(NOTIF_ID, plainBuild(this));
            } catch (Throwable t2) {
                XposedInit.diag("FGS fail=" + t.getClass().getSimpleName()
                        + "/" + t2.getClass().getSimpleName());
            }
        }
        // 完整版（带「最近保护」与岛参数）离开主线程再拼，拼完直接 notify 同一条 ID
        try {
            IO.execute(() -> {
                try {
                    postFull();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, REFRESH_MS);
        // v3.2.14：日志自动补录与通知刷新各自独立排期，互不影响节流。
        handler.removeCallbacks(logSync);
        handler.postDelayed(logSync, LOG_SYNC_MS);
        // v3.2.25：每次起来都重新排一次兜底巡检（15 分钟）；被杀后 45 秒的那一刀
        // 由 onDestroy / onTaskRemoved 排。用户主动停止时不再武装。
        if (!stoppedByUser) {
            HealReceiver.arm(this);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(logSync);
        // v3.2.25：不是用户点的停止，就补一刀 45 秒后的自动拉起。
        // 注意：进程被 LMK 直接 kill 时 onDestroy 不会执行，这条只是「正常回收」路径，
        // 被强杀的那条由 15 分钟兜底巡检接住（见 HealReceiver 注释）。
        if (!stoppedByUser) {
            HealReceiver.armFast(this);
            XposedInit.diag("HEAL schedule destroy");
        }
        super.onDestroy();
    }

    /** 从最近任务划掉本应用：等同被杀，排一次快速自愈。 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!stoppedByUser) {
            HealReceiver.armFast(this);
            XposedInit.diag("HEAL schedule taskRemoved");
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 定时与事件共用同一条通知：只改内容，不新建。 */
    private void post() {
        lastPostAt = SystemClock.elapsedRealtime();
        try {
            IO.execute(() -> {
                try {
                    postFull();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 完整版构建 + 更新同一条通知。只允许在 IO 线程调用（内部有 prefs 读与跨进程 call）。 */
    private void postFull() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        nm.notify(NOTIF_ID, build(this));
    }

    /** 看门狗用的存活探测：只看进程内实例，不碰磁盘、不跨进程。 */
    static boolean alive() {
        return instance != null;
    }

    static void createChannel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            // v3.2.19：重要性抬到 HIGH —— 与 HyperIsland 自己的静默渠道一致。
            // 焦点通知在 LOW/DEFAULT 上会被 SystemUI 直接过滤掉（3.2.16~3.2.18 上不去岛就是这里）；
            // 声音、震动、角标全关 + setOnlyAlertOnce，抬重要性也不会响、不会重复弹。
            NotificationChannel prev = nm.getNotificationChannel(CHANNEL_ID);
            if (prev != null && prev.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                // 渠道重要性不能原地改，只能删掉重建（一次性，之后不再动）
                nm.deleteNotificationChannel(CHANNEL_ID);
            }
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "防烧屏运行状态", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("常驻一条，仅用于保后台与显示最近保护的应用，不会实时刷新");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    private static Notification build(Context ctx) {
        String text = statusText(ctx);
        final String title = "防烧屏 · 运行中";
        Notification.Builder b = baseBuilder(ctx, smallIconRes(ctx), text);
        // 焦点通知的 extras 必须写在 build() 之后 —— kit 与 HyperIsland 都是
        // 「build 完再往 notification.extras 里塞」，Builder 阶段加的会被覆盖。
        Notification n = b.build();
        if (Config.enableIslandLink) {
            // v3.2.21：焦点参数**始终下发**，与「安全出行」5.6.16 的结论一致。
            // 之前按 canShowFocus 白名单门控是错的：HyperIsland 的「破解焦点白名单」
            // 是在 SystemUI 侧放行，我们查的还是原始 Provider（它照样回 false），
            // 于是参数被我们自己掐掉 —— 白名单破解了也等于没开。
            if (!IslandNotify.apply(ctx, n, title, text)) {
                XposedInit.diag("ISLAND extras=fail");
            }
            // 只留诊断，不参与决策：能看出这台机器白名单到底放没放行。
            // v3.2.22：这条会在 IO 线程跑（build 只允许在 IO 线程调用），
            // 跨进程 call 再慢也不会拖住 startForeground 的 5 秒窗口。
            XposedInit.diag("ISLAND focusAllowed=" + IslandNotify.focusAllowed(ctx));
        }
        return n;
    }

    /**
     * 通知小图标。
     *
     * <v3.2.22>
     * 3.2.20 直接 {@code setSmallIcon(applicationInfo.icon)} 是个雷：我们这个 icon 是
     * {@code mipmap-anydpi-v26/ic_launcher.xml} 的**自适应图标**，Android 明确不接受
     * 自适应图标做 smallIcon（Invalid notification / no valid small icon），
     * 抛出来又被 {@code catch(Throwable)} 吞掉 —— 表现就是 startForeground 没成功、
     * 5 秒后 FDNS 崩进程。
     *
     * <v3.2.39>
     * 兜底不再用 {@code ic_launcher_fg} 那张满幅彩色插画 PNG：smallIcon 必须是白色剪影，
     * 系统按 alpha 通道着色，满幅不透明的彩色插画渲出来就是一块空白/白块，真机通知左侧
     * 图标因此消失。自适应图标 / 解析失败一律退 {@code ic_tile} 单色矢量（plainBuild 已
     * 验证任何 ROM 都能画）。左侧彩色 App 图标由系统焦点通知按包名自己画，不走 smallIcon。
     */
    private static int smallIconRes(Context ctx) {
        int id = 0;
        try {
            id = ctx.getApplicationInfo().icon;
        } catch (Throwable ignored) {
            id = 0;
        }
        if (id != 0) {
            try {
                android.graphics.drawable.Drawable d = ctx.getDrawable(id);
                if (!(d instanceof android.graphics.drawable.AdaptiveIconDrawable)) {
                    return id;
                }
            } catch (Throwable ignored) {
                // 解析失败就当不能用
            }
        }
        return R.drawable.ic_tile;
    }

    /**
     * 通知左侧彩色图标（资源引用），由 {@link IslandNotify#pics(Context)} 塞进
     * {@code miui.focus.pics}，交给焦点卡左侧那一格去画。
     *
     * <p>v3.3.2：不再作为 {@code setLargeIcon} 使用 —— 那样焦点卡左侧和普通通知行行尾
     * 会各画一次，一张图变两个（12:16 真机截图）。</p>
     */
    static android.graphics.drawable.Icon cardIcon(Context ctx) {
        try {
            return android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.notif_large);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 最小可用的常驻通知：不读 prefs、不跨进程、不加岛参数，用来抢 5 秒窗口。 */
    private static Notification quickBuild(Context ctx) {
        return baseBuilder(ctx, smallIconRes(ctx), "守护中").build();
    }

    /** 连图标都出问题时的最后一条：单色矢量 + 最简文案，保证 startForeground 必成。 */
    private static Notification plainBuild(Context ctx) {
        return baseBuilder(ctx, R.drawable.ic_tile, "守护中").build();
    }

    /** quickBuild / plainBuild / build 共用的骨架，只放不依赖任何 IO 的字段。 */
    private static Notification.Builder baseBuilder(Context ctx, int icon, String text) {
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0,
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(icon)
                // v3.3.2：**不设 largeIcon**。
                // 3.3.1 加了 setLargeIcon，结果焦点卡左侧有图、右侧又飘出一个同款缩略图
                // （12:16 截图，黄圈）。原因：HyperOS 焦点卡左侧那格吃 largeIcon，
                // 而普通通知行又把 largeIcon 画成行尾的「大图」——一张图被画了两遍。
                // 现在改成「图走 miui.focus.pics，largeIcon 不设」，见 IslandNotify.pics()。
                .setContentTitle("防烧屏 · 运行中")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pi)
                // v3.2.25 保命：常驻通知上挂一键恢复，系统不放行后台启动时也不至于没救
                .addAction(R.drawable.ic_tile, "立即恢复", HealReceiver.manualPending(ctx))
                // v3.2.25：ACTION_STOP 分支以前没有任何入口，等于「停止就永不复活」是死代码；
                // 补上这条 action，用户才有真正的退出口（点了就不再自愈、不排闹钟）
                .addAction(R.drawable.ic_tile, "停止守护", HealReceiver.stopPending(ctx));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b;
    }

    private static String statusText(Context ctx) {
        // v3.2.25：刚被系统杀掉又自己爬起来的，先在通知上说清楚，别让用户以为一直没停
        if (HealReceiver.healedRecently(ctx, HealReceiver.RECENT_WINDOW_MS)) {
            return "已自愈恢复 · " + hhmm(HealReceiver.lastHealAt(ctx));
        }
        String pkg = ForegroundWatch.recentTarget(ctx);
        if (pkg == null) {
            return "待命 · 像素微移守护中";
        }
        long at = ForegroundWatch.recentTargetAt(ctx);
        String label = labelOf(pkg);
        return "最近保护 " + label + " · " + hhmm(at);
    }

    private static String labelOf(String pkg) {
        switch (VideoApps.of(pkg)) {
            case DOUYIN: return "抖音";
            case DRAMA: return "红果";
            case KUAISHOU: return "快手";
            case BILIBILI: return "B站";
            default: return "其它";
        }
    }

    private static String hhmm(long ts) {
        if (ts <= 0L) {
            return "";
        }
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
    }
}
