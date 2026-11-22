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

    private static volatile GuardService instance;
    private static volatile long lastPostAt;

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

    /** 幂等启动：App 打开、开机、无障碍连上都走这里。 */
    public static void ensure(Context ctx) {
        if (ctx == null) {
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
            stopSelf();
            return START_NOT_STICKY;
        }
        createChannel(this);
        try {
            // 必须在 onStartCommand 的 5 秒内挂上前台通知，否则系统会 ANR/杀服务
            startForeground(NOTIF_ID, build(this));
            lastPostAt = SystemClock.elapsedRealtime();
        } catch (Throwable ignored) {
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, REFRESH_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 定时与事件共用同一条通知：只改内容，不新建。 */
    private void post() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        lastPostAt = SystemClock.elapsedRealtime();
        try {
            nm.notify(NOTIF_ID, build(this));
        } catch (Throwable ignored) {
        }
    }

    private static void createChannel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "防烧屏运行状态", NotificationManager.IMPORTANCE_LOW);
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
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0,
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("防烧屏 · 运行中")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private static String statusText(Context ctx) {
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
