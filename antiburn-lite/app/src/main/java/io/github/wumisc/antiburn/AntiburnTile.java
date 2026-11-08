package io.github.wumisc.antiburn;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * 快捷开关磁贴：下拉快捷面板一键开/关防烧屏。
 *
 * 修复：旧版在 onClick / onStartListening 里同步 fork su 读写配置（最长 4s），
 * 磁贴主线程直接被卡住，表现为「点了没反应 / 系统提示无响应」。
 * 现在所有 su 操作放后台线程，主线程只更新磁贴 UI 与 Toast。
 */
public class AntiburnTile extends TileService {

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        ConfStore.init(this); // TileService 可能在 Application 之外被拉起
    }

    @Override
    public void onStartListening() {
        new Thread(() -> {
            boolean on = readEnabled();
            main.post(() -> applyTile(on));
        }, "antiburn-tile-read").start();
    }

    @Override
    public void onClick() {
        new Thread(() -> {
            // 原子「读-取反-写」，避免连点两次都基于同一个旧值、以及和主页面保存互相覆盖
            String next = ConfStore.flipStatus();
            final boolean ok = next != null;
            final boolean nowOn = ok ? "1".equals(next) : readEnabled();
            main.post(() -> {
                applyTile(nowOn);
                Toast.makeText(AntiburnTile.this,
                        ok
                                ? (nowOn ? "防止烧屏已开启，下个周期生效" : "防止烧屏已关闭，下个周期生效")
                                : "写入失败，请打开 App 检查 root 授权",
                        Toast.LENGTH_SHORT).show();
            });
        }, "antiburn-tile-write").start();
    }

    private void applyTile(boolean on) {
        Tile t = getQsTile();
        if (t == null) {
            return;
        }
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("防止烧屏");
        t.setSubtitle(on ? "已开启" : "已关闭");
        t.updateTile();
    }

    /** 经 ConfStore 读取当前 enable_status；读不到时默认视为开启。 */
    private boolean readEnabled() {
        String conf = ConfStore.read();
        if (conf == null) {
            return true;
        }
        for (String line : conf.split("\n")) {
            String t = line.trim();
            if (t.startsWith("enable_status=")) {
                return t.substring(t.indexOf('=') + 1).trim().equals("1");
            }
        }
        return true;
    }

}
