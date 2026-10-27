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
        boolean ok = writeEnabled(!on);
        refresh();
        Toast.makeText(this,
                ok ? (on ? "防止烧屏已关闭，下个周期生效" : "防止烧屏已开启，下个周期生效")
                   : "写入失败，请检查 root 授权",
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

    /** 改写 enable_status 为 on。返回是否写入成功（su 执行 + 读回校验都通过）。
     *  概率性不生效的根因多是 su 偶发失败/未授权/超时，这里校验并明确反馈。 */
    private boolean writeEnabled(boolean on) {
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
            boolean ok = execSu("echo '" + b64 + "' | base64 -d > " + Config.CONF
                    + " && chmod 644 " + Config.CONF);
            if (!ok) {
                return false;
            }
            // 读回校验：确认文件确实已改成目标值，避免 su 成功但写错/被覆盖
            return readEnabled() == on;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean execSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy(); // su 卡死（如授权弹窗未响应）→ 超时杀掉，避免阻塞磁贴
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
