package io.github.wumisc.antiburn;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * 常驻服务的「保命」看门狗（v3.2.25）。
 *
 * <p><b>要解决的问题</b>：HyperOS / 各家 ROM 会把常驻的前台服务连同进程一起回收，
 * 通知跟着消失，用户完全看不出来「防烧屏已经停了」。系统虽然对 START_STICKY 有重启，
 * 但在省电策略下并不保证，尤其是我们这种 specialUse 服务。</p>
 *
 * <p><b>做法</b>：三层，全部在本进程能力范围内，不越权、不常驻轮询：</p>
 * <ol>
 *   <li>服务被杀后 45 秒补一次闹钟（{@link #armFast}）——覆盖正常回收路径；</li>
 *   <li>每 15 分钟一次兜底唤醒（{@link #arm}）——覆盖「没来得及排闹钟就被杀」的路径；
 *       用 {@code setAndAllowWhileIdle} 的**不精确**闹钟，不申请精确闹钟权限，
 *       也不拿 wake lock，一次只读一个内存状态，功耗可以忽略；</li>
 *   <li>通知上挂「立即恢复」按钮——用户手动一键拉起，不依赖系统放行。</li>
 * </ol>
 *
 * <p>拉起失败（Android 12+ 后台启动前台服务限制）不崩、不重试风暴：只记一行
 * {@code HEAL block=...} 到诊断日志，等下一次闹钟。</p>
 */
public final class HealReceiver extends BroadcastReceiver {

    /** 闹钟与通知按钮共用同一个动作。 */
    public static final String ACTION_HEAL = "io.github.wumisc.antiburn.HEAL";

    /** 服务被杀后尽快补一刀。 */
    private static final long FAST_MS = 45_000L;
    /** 兜底巡检间隔：15 分钟（在 Doze 的 allowWhileIdle 配额内，别更密）。 */
    private static final long CHECK_MS = 15L * 60_000L;
    /** 自愈记录：只看最近 30 分钟，用来在通知里说「刚自愈过」。 */
    public static final long RECENT_WINDOW_MS = 30L * 60_000L;

    private static final String PREFS = "antiburn_heal";
    private static final String KEY_AT = "last_heal_at";
    private static final String KEY_MODE = "last_heal_mode";
    /** v3.2.25：用户点过「停止」——跨进程重启也要记住，否则闹钟一到就把服务又拉起来。 */
    private static final String KEY_STOPPED = "user_stopped";
    private static final int REQ = 1002;
    /** 起不来时的兜底通知 ID：刻意避开常驻通知的 1001，免得互相顶掉。 */
    private static final int BLOCK_ID = 1003;
    /** 兜底通知节流：每 15 分钟一次的巡检不能变成每 15 分钟弹一次「已停止」。 */
    private static final long BLOCK_NOTIFY_GAP_MS = 6L * 60L * 60_000L;
    private static final String KEY_BLOCK_AT = "last_block_notify_at";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ctx == null) {
            return;
        }
        String action = intent == null ? null : intent.getAction();
        // 覆盖安装后旧进程一定不在了：这次按「手动」记账，日志里一眼能区分
        boolean manual = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || (ACTION_HEAL.equals(action) && intent.getBooleanExtra("manual", false));
        // v3.2.25：用户点过「停止」就绝不自愈。这条不能只靠「不排闹钟」——
        // 停止那一刻闹钟可能早就排好了，到点还会把服务拉起来（自己复活，等于流氓软件）。
        // 覆盖安装这条是例外：那是用户主动装的，按手动处理。
        if (!manual && userStopped(ctx)) {
            XposedInit.diag("HEAL skip=user_stopped");
            return;
        }
        boolean started = false;
        boolean already = GuardService.alive();
        if (!already) {
            try {
                Intent svc = new Intent(ctx, GuardService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svc);
                } else {
                    ctx.startService(svc);
                }
                started = true;
            } catch (Throwable t) {
                // 12+ 后台启动限制 / ROM 拦截：记账后等下一次，不循环重试
                XposedInit.diag("HEAL block=" + t.getClass().getSimpleName()
                        + " manual=" + manual);
                // 系统不给后台启动时，至少让用户看得见：弹一条可点的通知，
                // 点通知属于「用户交互」，再拉起服务就不再受限。
                notifyBlocked(ctx);
            }
        }
        if (started || manual) {
            markHealed(ctx, manual ? "manual" : "auto");
            cancelBlocked(ctx);
            XposedInit.diag("HEAL " + (manual ? "manual" : "auto")
                    + " alive=" + already + " started=" + started);
        }
        arm(ctx);
    }

    /** 服务起不来时的可见兜底：一条可点通知，点一下等同用户手动恢复。 */
    private static void notifyBlocked(Context ctx) {
        try {
            long now = System.currentTimeMillis();
            if (now - prefs(ctx).getLong(KEY_BLOCK_AT, 0L) < BLOCK_NOTIFY_GAP_MS) {
                return; // 6 小时内已经提醒过，静默等下一次（自愈本身照旧尝试）
            }
            prefs(ctx).edit().putLong(KEY_BLOCK_AT, now).commit();
        } catch (Throwable ignored) {
        }
        try {
            GuardService.createChannel(ctx);
            Notification.Builder b = new Notification.Builder(ctx, GuardService.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("防烧屏守护已停止")
                    .setContentText("后台被系统回收，点此恢复")
                    .setContentIntent(manualPending(ctx))
                    .setAutoCancel(true)
                    // 同一 ID 反复更新也不响不震动：巡检每 15 分钟一次，绝不能变成骚扰
                    .setOnlyAlertOnce(true);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(BLOCK_ID, b.build());
            }
        } catch (Throwable t) {
            XposedInit.diag("HEAL notify-fail=" + t.getClass().getSimpleName());
        }
    }

    /** 兜底巡检排期（15 分钟）。服务每次启动成功都会调一次，天然续命。 */
    static void arm(Context ctx) {
        schedule(ctx, CHECK_MS);
    }

    /** 服务刚死时补一刀（45 秒）。 */
    static void armFast(Context ctx) {
        schedule(ctx, FAST_MS);
    }

    private static void schedule(Context ctx, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            PendingIntent pi = pending(ctx);
            // 不精确闹钟：不碰 SCHEDULE_EXACT_ALARM，不触发系统「精确闹钟」授权，
            // 在 Doze 里也能被 allowWhileIdle 唤醒（有配额，15 分钟刚好安全）。
            // v3.2.25 兼容修：必须用 ELAPSED_REALTIME。RTC 是墙钟，
            // 开机后 NTP 把时间从 1970 校到真实时间、用户改时区/手动改时间，
            // 都会让「15 分钟后」在系统看来已经过期 → 闹钟立刻响，
            // 表现为「什么都没干，通知突然连着弹」。
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (Throwable t) {
            XposedInit.diag("HEAL arm-fail=" + t.getClass().getSimpleName());
        }
    }

    static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, HealReceiver.class).setAction(ACTION_HEAL);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getBroadcast(ctx, REQ, i, flags);
    }

    /** 通知按钮用：带 manual 标记，便于日志区分「系统自愈」与「用户点的」。 */
    /**
     * 通知上的「停止守护」：给用户一个明确的退出口。
     *
     * <p>之前 ACTION_STOP 分支存在、但全工程没有任何地方构造这条 PendingIntent，
     * 等于「用户主动停止就永不复活」是一段永远走不到的死代码 —— 用户除了强行停止
     * 根本没有别的办法让守护安静。这里把入口补齐。</p>
     */
    static PendingIntent stopPending(Context ctx) {
        Intent i = new Intent(ctx, GuardService.class).setAction(GuardService.ACTION_STOP);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(ctx, REQ + 2, i, flags);
    }

    static PendingIntent manualPending(Context ctx) {
        Intent i = new Intent(ctx, HealReceiver.class)
                .setAction(ACTION_HEAL)
                .putExtra("manual", true);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getBroadcast(ctx, REQ + 1, i, flags);
    }

    /**
     * 用户点「停止」：立刻注销已排的闹钟，并把「别回来」记到磁盘。
     *
     * <p>只置内存标志是不够的——闹钟是系统侧的，进程重启后标志清零、
     * 闹钟照旧触发，服务就自己复活了。</p>
     */
    static void markUserStop(Context ctx) {
        try {
            prefs(ctx).edit().putBoolean(KEY_STOPPED, true).commit();
        } catch (Throwable t) {
            XposedInit.diag("HEAL stop-pref-fail=" + t.getClass().getSimpleName());
        }
        disarm(ctx);
    }

    /** 服务被正常拉起（用户打开 App / 通知按钮 / 手动恢复）时清掉「别回来」。 */
    static void clearUserStop(Context ctx) {
        try {
            prefs(ctx).edit().putBoolean(KEY_STOPPED, false).commit();
        } catch (Throwable ignored) {
        }
    }

    static boolean userStopped(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_STOPPED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 注销两档闹钟（巡检 + 手动按钮同源的那条也不留）。 */
    static void disarm(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            am.cancel(pending(ctx));
            am.cancel(manualPending(ctx));
        } catch (Throwable t) {
            XposedInit.diag("HEAL disarm-fail=" + t.getClass().getSimpleName());
        }
    }

    /** 服务已经起来了：把「已停止」那条兜底通知撤掉，别让两条通知自相矛盾。 */
    static void cancelBlocked(Context ctx) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(BLOCK_ID);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void markHealed(Context ctx, String mode) {
        try {
            prefs(ctx).edit()
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .putString(KEY_MODE, mode)
                    .apply();
        } catch (Throwable ignored) {
            // 记不住不影响自愈本身
        }
    }

    static long lastHealAt(Context ctx) {
        try {
            return prefs(ctx).getLong(KEY_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    static String lastHealMode(Context ctx) {
        try {
            return prefs(ctx).getString(KEY_MODE, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 最近一次自愈是否还「新鲜」——通知里据此显示「刚自愈恢复」。 */
    static boolean healedRecently(Context ctx, long windowMs) {
        long at = lastHealAt(ctx);
        return at > 0 && System.currentTimeMillis() - at <= windowMs;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
