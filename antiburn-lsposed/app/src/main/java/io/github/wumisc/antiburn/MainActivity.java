package io.github.wumisc.antiburn;

import android.app.Activity;
import android.os.Bundle;
import android.util.Base64;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 配置界面：读写 /data/local/tmp/antiburn.conf（root 写入，SystemUI 读取）
 */
public class MainActivity extends Activity {

    private static final String CONF = "/data/local/tmp/antiburn.conf";

    private CompoundButton swStatus;
    private CompoundButton swNav;
    private EditText etHide;
    private EditText etShow;
    private EditText etWhitelist;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swStatus = findViewById(R.id.sw_status);
        swNav = findViewById(R.id.sw_nav);
        etHide = findViewById(R.id.et_hide);
        etShow = findViewById(R.id.et_show);
        etWhitelist = findViewById(R.id.et_whitelist);
        tvStatus = findViewById(R.id.tv_status);

        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> saveConfig());

        TextView tvAboutTitle = findViewById(R.id.tv_about_title);
        tvAboutTitle.setText("防 OLED 烧屏 · LSPosed 模块 v" + BuildConfig.VERSION_NAME);

        loadConfig();
    }

    /** 读取现有配置填充界面 */
    private void loadConfig() {
        tvStatus.setText("正在读取配置...");
        new Thread(() -> {
            String raw = execSu("cat " + CONF);
            runOnUiThread(() -> {
                if (raw.contains("No such file") || raw.contains("EXC") || raw.isEmpty()) {
                    tvStatus.setText("未找到配置文件，将按默认值保存（状态栏隐藏30s/显示10s）");
                    swStatus.setChecked(true);
                    swNav.setChecked(false);
                    etHide.setText("30");
                    etShow.setText("10");
                    etWhitelist.setText("");
                    return;
                }
                boolean es = true, en = false;
                int h = 30, s = 10;
                String wl = "";
                for (String line : raw.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf('=');
                    if (i < 0) continue;
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    switch (k) {
                        case "enable_status": es = v.equals("1"); break;
                        case "enable_navigation": en = v.equals("1"); break;
                        case "hide_duration": h = parseInt(v, 30); break;
                        case "show_duration": s = parseInt(v, 10); break;
                        case "whitelist": wl = v; break;
                        default: break;
                    }
                }
                swStatus.setChecked(es);
                swNav.setChecked(en);
                etHide.setText(String.valueOf(h));
                etShow.setText(String.valueOf(s));
                etWhitelist.setText(wl);
                tvStatus.setText("已读取现有配置");
            });
        }).start();
    }

    /** 保存配置：base64 经 su 写入，避免转义问题 */
    private void saveConfig() {
        int hide = parseInt(etHide.getText().toString(), 30);
        int show = parseInt(etShow.getText().toString(), 10);
        if (hide < 1) hide = 1;
        if (show < 1) show = 1;
        final int fHide = hide;
        final int fShow = show;

        String conf = "# 防止烧屏模块配置（App 保存生成）\n"
                + "enable_status=" + (swStatus.isChecked() ? 1 : 0) + "\n"
                + "enable_navigation=" + (swNav.isChecked() ? 1 : 0) + "\n"
                + "hide_duration=" + hide + "\n"
                + "show_duration=" + show + "\n"
                + "whitelist=" + etWhitelist.getText().toString().trim() + "\n";

        String b64 = Base64.encodeToString(conf.getBytes(), Base64.NO_WRAP);
        String cmd = "echo '" + b64 + "' | base64 -d > " + CONF
                + " && chmod 644 " + CONF;

        tvStatus.setText("正在保存...");
        new Thread(() -> {
            String out = execSu(cmd);
            runOnUiThread(() -> {
                if (out.startsWith("EXC") || out.contains("ERR:")) {
                    tvStatus.setText("保存失败：" + out);
                    Toast.makeText(this, "保存失败，请确认 root 授权", Toast.LENGTH_LONG).show();
                } else {
                    tvStatus.setText("已保存：状态栏" + (swStatus.isChecked() ? "开" : "关")
                            + " 导航栏" + (swNav.isChecked() ? "开" : "关")
                            + " 隐藏" + fHide + "s/显示" + fShow + "s");
                    Toast.makeText(this, "保存成功，正在重启系统界面...", Toast.LENGTH_SHORT).show();
                    // 延迟 1 秒重启 SystemUI，让提示先显示，配置立即生效
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        execSu("am crash com.android.systemui");
                    }).start();
                }
            });
        }).start();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 执行 su 命令并返回输出 */
    private static String execSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    err.append(line).append('\n');
                }
            }
            p.waitFor();
            String e = err.toString().trim();
            return out.toString().trim() + (e.isEmpty() ? "" : "\nERR:" + e);
        } catch (Exception e) {
            return "EXC:" + e;
        }
    }
}
