package io.github.wumisc.antiburn;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Base64;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;

/**
 * 快捷开关磁贴：下拉快捷面板一键开/关防烧屏。
 * 点击后改写 /data/local/tmp/antiburn.conf 的 enable_status，
 * 模块周期轮询会在一个周期内生效。
 */
public class AntiburnTile extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        boolean on = readEnabled();
        writeEnabled(!on);
        refresh();
        Toast.makeText(this,
                on ? "防止烧屏已关闭，下个周期生效" : "防止烧屏已开启，下个周期生效",
                Toast.LENGTH_SHORT).show();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) {
            return;
        }
        boolean on = readEnabled();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("防止烧屏");
        t.setSubtitle(on ? "已开启" : "已关闭");
        t.updateTile();
    }

    private boolean readEnabled() {
        try (BufferedReader br = new BufferedReader(new FileReader(Config.CONF))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("enable_status=")) {
                    return line.substring(line.indexOf('=') + 1).trim().equals("1");
                }
            }
        } catch (Throwable t) {
            // 默认视为开启
        }
        return true;
    }

    private void writeEnabled(boolean on) {
        try {
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            try (BufferedReader br = new BufferedReader(new FileReader(Config.CONF))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.trim().startsWith("enable_status=")) {
                        sb.append("enable_status=").append(on ? 1 : 0).append('\n');
                        replaced = true;
                    } else {
                        sb.append(line).append('\n');
                    }
                }
            }
            if (!replaced) {
                sb.append("enable_status=").append(on ? 1 : 0).append('\n');
            }
            String b64 = Base64.encodeToString(
                    sb.toString().getBytes(), Base64.NO_WRAP);
            execSu("echo '" + b64 + "' | base64 -d > " + Config.CONF
                    + " && chmod 644 " + Config.CONF);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static void execSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Throwable t) {
            // ignore
        }
    }
}
