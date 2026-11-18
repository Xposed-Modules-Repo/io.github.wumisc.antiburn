package io.github.wumisc.antiburn;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.TileService;

/**
 * v3.0.8 P2：开机 / 覆盖安装广播接收器。
 *
 * <p>诚实边界：只做四件同步幂等操作——持久化触发状态、初始化配置镜像、刷新磁贴、落一条诊断日志。
 * 绝不启动后台服务、不拉无障碍、不发通知、不从后台拉 Activity；国产 ROM 自启白名单仍需用户手动授权。</p>
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String A_BOOT = Intent.ACTION_BOOT_COMPLETED;
    private static final String A_REPLACED = Intent.ACTION_MY_PACKAGE_REPLACED;
    private static final long DEDUP_MS = 60_000L;
    private static volatile long lastHandledAt = 0L;   // 进程内去重

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || i.getAction() == null) {
            return;
        }
        String a = i.getAction();
        if (!A_BOOT.equals(a) && !A_REPLACED.equals(a)) {
            return;                                     // action 白名单
        }
        long now = System.currentTimeMillis();
        synchronized (BootReceiver.class) {
            if (lastHandledAt != 0L && now - lastHandledAt < DEDUP_MS) {
                return;                                 // 防重复：60s 内只处理一次
            }
            lastHandledAt = now;
        }

        // 持久化状态（独立 prefs）。markAttempted 全工程仅此一处调用；
        // trigger 区分来源：BOOT_COMPLETED → BOOT，MY_PACKAGE_REPLACED → REPLACED。
        BootRestoreStore.markAttempted(c, now, Config.engine,
                A_BOOT.equals(a) ? BootRestoreStore.TRIGGER_BOOT : BootRestoreStore.TRIGGER_REPLACED);

        // 1) 配置镜像就绪：与 AbApplication.onCreate 同语义、幂等。
        //    边界防御：配置存储异常不能让广播处理崩溃，故吞掉并继续后续可观测步骤。
        try {
            ConfStore.init(c.getApplicationContext());
            ConfStore.migrateIfNeededAsync();
        } catch (Throwable ignored) {
            // 配置镜像失败不影响「广播已到达」的可观测记录
        }
        // 2) 磁贴状态刷新（系统 API，非后台启动服务，不受 Android 8+ 后台限制）。
        try {
            TileService.requestListeningState(c, new ComponentName(c, AntiburnTile.class));
        } catch (Throwable ignored) {
            // 磁贴刷新失败不影响其余幂等步骤
        }
        // 3) 可观测：强制落一条，证明广播确实到达（用户导出日志即可核对）。
        XposedInit.diagForce("BOOT restore action=" + a + " attempt=1");
        // 说明：无需 goAsync() —— 上述均为同步幂等操作或系统 API，无长任务。
    }
}
