package io.github.wumisc.antiburn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 配置界面：读写 /data/local/tmp/antiburn.conf（root 写入，SystemUI 读取）
 */
public class MainActivity extends Activity {

    private static final String CONF = "/data/local/tmp/antiburn.conf";

    private CompoundButton swStatus;
    private CompoundButton swNav;
    private CompoundButton swDouyinTop;
    private CompoundButton swDouyinBottom;
    private CompoundButton swDouyinLike;
    private CompoundButton swDouyinRt;
    private CompoundButton swDouyinLt;
    private CompoundButton swDramaTop;
    private CompoundButton swDramaBottom;
    private CompoundButton swDramaLike;
    private CompoundButton swDramaRt;
    private CompoundButton swDramaLt;
    private CompoundButton swLive;
    private CompoundButton swBall;
    private CompoundButton swIcon;
    private CompoundButton swLog;
    private CompoundButton swPoem;
    private EditText etCustom;
    private EditText etHide;
    private EditText etShow;
    private EditText etWhitelist;
    private TextView tvStatus;
    private TextView tvCycleSummary;
    private TextView chipStatus;
    private TextView chipNav;
    private TextView chipDyin;
    private TextView chipDrama;
    private TextView chipPoem;
    private TextView chipBall;
    private View groupDyinHeader;
    private View groupDyinBody;
    private TextView tvGroupArrow;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swStatus = findViewById(R.id.sw_status);
        swNav = findViewById(R.id.sw_nav);
        swDouyinTop = findViewById(R.id.sw_douyin_top);
        swDouyinBottom = findViewById(R.id.sw_douyin_bottom);
        swDouyinLike = findViewById(R.id.sw_douyin_like);
        swDouyinRt = findViewById(R.id.sw_douyin_rt);
        swDouyinLt = findViewById(R.id.sw_douyin_lt);
        swDramaTop = findViewById(R.id.sw_drama_top);
        swDramaBottom = findViewById(R.id.sw_drama_bottom);
        swDramaLike = findViewById(R.id.sw_drama_like);
        swDramaRt = findViewById(R.id.sw_drama_rt);
        swDramaLt = findViewById(R.id.sw_drama_lt);
        swLive = findViewById(R.id.sw_live);
        swBall = findViewById(R.id.sw_ball);
        swIcon = findViewById(R.id.sw_icon);
        swLog = findViewById(R.id.sw_log);
        swPoem = findViewById(R.id.sw_poem);
        etCustom = findViewById(R.id.et_custom_poems);
        etHide = findViewById(R.id.et_hide);
        etShow = findViewById(R.id.et_show);
        etWhitelist = findViewById(R.id.et_whitelist);
        tvStatus = findViewById(R.id.tv_status);
        tvCycleSummary = findViewById(R.id.tv_cycle_summary);
        chipStatus = findViewById(R.id.chip_status);
        chipNav = findViewById(R.id.chip_nav);
        chipDyin = findViewById(R.id.chip_dyin);
        chipDrama = findViewById(R.id.chip_drama);
        chipPoem = findViewById(R.id.chip_poem);
        chipBall = findViewById(R.id.chip_ball);
        groupDyinHeader = findViewById(R.id.group_dyin_header);
        groupDyinBody = findViewById(R.id.group_dyin_body);
        tvGroupArrow = findViewById(R.id.tv_group_arrow);

        // 抖音/红果大类：点击标题展开/收起设置区
        groupDyinHeader.setOnClickListener(v -> {
            boolean show = groupDyinBody.getVisibility() != View.GONE;
            groupDyinBody.setVisibility(show ? View.GONE : View.VISIBLE);
            tvGroupArrow.setText(show ? "展开 ▸" : "收起 ▾");
        });

        // 初次进入先按当前控件状态刷新一次总览（加载完配置后还会再刷新）
        updateSummary();

        // 底部导航栏：首页 / 软件区 / 关于 三页切换
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        FrameLayout pageHome = findViewById(R.id.page_home);
        FrameLayout pageSoftware = findViewById(R.id.page_software);
        FrameLayout pageAbout = findViewById(R.id.page_about);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            pageHome.setVisibility(id == R.id.nav_home ? View.VISIBLE : View.GONE);
            pageSoftware.setVisibility(id == R.id.nav_software ? View.VISIBLE : View.GONE);
            pageAbout.setVisibility(id == R.id.nav_about ? View.VISIBLE : View.GONE);
            return true;
        });

        // 桌面图标开关：切换即生效并持久化
        swIcon.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (loading) {
                return;
            }
            setIconEnabled(isChecked);
            saveConfig();
        });

        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> saveConfig());

        Button btnRestart = findViewById(R.id.btn_restart);
        btnRestart.setOnClickListener(v -> restartSystemUI());

        Button btnDonate = findViewById(R.id.btn_donate);
        btnDonate.setOnClickListener(v -> showDonate());

        TextView tvAboutTitle = findViewById(R.id.tv_about_title);
        tvAboutTitle.setText("防 OLED 烧屏 · LSPosed 模块 v" + BuildConfig.VERSION_NAME);

        loadConfig();
    }

    /** 动态启用/禁用桌面图标组件（IconActivity） */
    /** 刷新「运行状态」总览卡：周期 + 各功能开关芯片 */
    private void updateSummary() {
        int h = Math.max(1, parseInt(etHide.getText().toString().trim(), 30));
        int s = Math.max(1, parseInt(etShow.getText().toString().trim(), 10));
        tvCycleSummary.setText("隐藏 " + h + "s · 显示 " + s + "s");
        setChip(chipStatus, swStatus.isChecked());
        setChip(chipNav, swNav.isChecked());
        boolean dyin = swDouyinTop.isChecked() || swDouyinBottom.isChecked()
                || swDouyinLike.isChecked() || swDouyinRt.isChecked()
                || swDouyinLt.isChecked() || swLive.isChecked();
        setChip(chipDyin, dyin);
        boolean drama = swDramaTop.isChecked() || swDramaBottom.isChecked()
                || swDramaLike.isChecked() || swDramaRt.isChecked()
                || swDramaLt.isChecked();
        setChip(chipDrama, drama);
        setChip(chipPoem, swPoem.isChecked());
        setChip(chipBall, swBall.isChecked());
    }

    /** 芯片：开=绿底白字，关=灰底灰字；首次调用记录功能名，之后按名+状态刷新 */
    private void setChip(TextView chip, boolean on) {
        if (chip.getTag() == null) {
            chip.setTag(chip.getText().toString());
        }
        String name = chip.getTag().toString();
        GradientDrawable gd = (GradientDrawable) chip.getBackground();
        if (on) {
            gd.setColor(0xFF10B981);
            chip.setTextColor(0xFFFFFFFF);
            chip.setText(name + " 开");
        } else {
            gd.setColor(0xFFEEEEF0);
            chip.setTextColor(0xFF999999);
            chip.setText(name + " 关");
        }
    }

    private void setIconEnabled(boolean on) {
        try {
            ComponentName cn = new ComponentName(this, IconActivity.class);
            int state = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            getPackageManager().setComponentEnabledSetting(
                    cn, state, PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** 支持捐赠：显示微信收款码 */
    private void showDonate() {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_donate_wechat);
        iv.setAdjustViewBounds(true);
        new AlertDialog.Builder(this)
                .setTitle("支持捐赠")
                .setMessage("如果觉得好用，请作者喝杯奶茶 🧋 微信扫一扫即可。\n\n作者：@酷安锦无名")
                .setView(iv)
                .setPositiveButton("好", null)
                .show();
    }

    /** 读取现有配置填充界面 */
    private void loadConfig() {
        tvStatus.setText("正在读取配置...");
        new Thread(() -> {
            String raw = execSu("cat " + CONF);
            runOnUiThread(() -> {
                loading = true;
                try {
                    if (raw.contains("No such file") || raw.contains("EXC") || raw.isEmpty()) {
                        tvStatus.setText("未找到配置文件，将按默认值保存（状态栏隐藏30s/显示10s）");
                        swStatus.setChecked(true);
                        swNav.setChecked(false);
                        swDouyinTop.setChecked(false);
                        swDouyinBottom.setChecked(false);
                        swDouyinLike.setChecked(false);
                        swDouyinRt.setChecked(false);
                        swDouyinLt.setChecked(false);
                        swDramaTop.setChecked(false);
                        swDramaBottom.setChecked(false);
                        swDramaLike.setChecked(false);
                        swDramaRt.setChecked(false);
                        swDramaLt.setChecked(false);
                        swLive.setChecked(true);
                        swBall.setChecked(false);
                        swIcon.setChecked(false);
                        swLog.setChecked(false);
                        swPoem.setChecked(false);
                        etCustom.setText("");
                        etHide.setText("30");
                        etShow.setText("10");
                        etWhitelist.setText("");
                        return;
                    }
                    boolean es = true, en = false, dtop = false, dbot = false, dlike = false, drt = false, dlt = false, ic = false, lg = false;
                    boolean drTop = false, drBot = false, drLike = false, drRt = false, drLt = false;
                    boolean lv = true;
                    boolean pm = false;
                    boolean bl = false;
                    String custom = "";
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
                            case "enable_douyin_top": dtop = v.equals("1"); break;
                            case "enable_douyin_bottom": dbot = v.equals("1"); break;
                            case "enable_douyin_like": dlike = v.equals("1"); break;
                            case "enable_douyin_right_top": drt = v.equals("1"); break;
                            case "enable_douyin_left_top": dlt = v.equals("1"); break;
                            case "enable_icon": ic = v.equals("1"); break;
                            case "enable_log": lg = v.equals("1"); break;
                            case "enable_drama_top": drTop = v.equals("1"); break;
                            case "enable_drama_bottom": drBot = v.equals("1"); break;
                            case "enable_drama_like": drLike = v.equals("1"); break;
                            case "enable_drama_right_top": drRt = v.equals("1"); break;
                            case "enable_drama_left_top": drLt = v.equals("1"); break;
                            case "enable_live": lv = v.equals("1"); break;
                            case "enable_poem": pm = v.equals("1"); break;
                            case "enable_ball": bl = v.equals("1"); break;
                            case "custom_poems": custom = v; break;
                            case "hide_duration": h = parseInt(v, 30); break;
                            case "show_duration": s = parseInt(v, 10); break;
                            case "whitelist": wl = v; break;
                            default: break;
                        }
                    }
                    swStatus.setChecked(es);
                    swNav.setChecked(en);
                    swDouyinTop.setChecked(dtop);
                    swDouyinBottom.setChecked(dbot);
                    swDouyinLike.setChecked(dlike);
                    swDouyinRt.setChecked(drt);
                    swDouyinLt.setChecked(dlt);
                    swDramaTop.setChecked(drTop);
                    swDramaBottom.setChecked(drBot);
                    swDramaLike.setChecked(drLike);
                    swDramaRt.setChecked(drRt);
                    swDramaLt.setChecked(drLt);
                    swLive.setChecked(lv);
                    swBall.setChecked(bl);
                    swIcon.setChecked(ic);
                    swLog.setChecked(lg);
                    swPoem.setChecked(pm);
                    etCustom.setText(custom);
                    etHide.setText(String.valueOf(h));
                    etShow.setText(String.valueOf(s));
                    etWhitelist.setText(wl);
                    tvStatus.setText("已读取现有配置");
                    updateSummary();
                } finally {
                    loading = false;
                }
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
                + "enable_douyin_top=" + (swDouyinTop.isChecked() ? 1 : 0) + "\n"
                + "enable_douyin_bottom=" + (swDouyinBottom.isChecked() ? 1 : 0) + "\n"
                + "enable_douyin_like=" + (swDouyinLike.isChecked() ? 1 : 0) + "\n"
                + "enable_douyin_right_top=" + (swDouyinRt.isChecked() ? 1 : 0) + "\n"
                + "enable_douyin_left_top=" + (swDouyinLt.isChecked() ? 1 : 0) + "\n"
                + "enable_drama_top=" + (swDramaTop.isChecked() ? 1 : 0) + "\n"
                + "enable_drama_bottom=" + (swDramaBottom.isChecked() ? 1 : 0) + "\n"
                + "enable_drama_like=" + (swDramaLike.isChecked() ? 1 : 0) + "\n"
                + "enable_drama_right_top=" + (swDramaRt.isChecked() ? 1 : 0) + "\n"
                + "enable_drama_left_top=" + (swDramaLt.isChecked() ? 1 : 0) + "\n"
                + "enable_live=" + (swLive.isChecked() ? 1 : 0) + "\n"
                + "enable_ball=" + (swBall.isChecked() ? 1 : 0) + "\n"
                + "enable_poem=" + (swPoem.isChecked() ? 1 : 0) + "\n"
                + "custom_poems=" + etCustom.getText().toString().trim() + "\n"
                + "enable_icon=" + (swIcon.isChecked() ? 1 : 0) + "\n"
                + "enable_log=" + (swLog.isChecked() ? 1 : 0) + "\n"
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
                            + " 抖音顶/底/赞/右/左" + (swDouyinTop.isChecked() ? "开" : "关")
                            + "/" + (swDouyinBottom.isChecked() ? "开" : "关")
                            + "/" + (swDouyinLike.isChecked() ? "开" : "关")
                            + "/" + (swDouyinRt.isChecked() ? "开" : "关")
                            + "/" + (swDouyinLt.isChecked() ? "开" : "关")
                            + " 隐藏" + fHide + "s/显示" + fShow + "s");
                    Toast.makeText(this, "配置已保存，下个周期自动生效", Toast.LENGTH_SHORT).show();
                    updateSummary();
                }
            });
        }).start();
    }

    /** 单独重启系统界面：应用当前已保存的配置（未保存则应用上次保存值） */
    private void restartSystemUI() {
        tvStatus.setText("正在重启系统界面...");
        Toast.makeText(this, "正在重启系统界面，稍等", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
            }
            execSu("am crash com.android.systemui");
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
