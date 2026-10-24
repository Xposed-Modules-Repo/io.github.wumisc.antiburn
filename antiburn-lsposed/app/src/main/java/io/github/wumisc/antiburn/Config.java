package io.github.wumisc.antiburn;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Set;

/**
 * 配置读取：/data/local/tmp/antiburn.conf
 * 格式：
 *   enable_status=1          # 隐藏状态栏 1=开 0=关
 *   enable_navigation=0      # 隐藏导航栏 1=开 0=关
 *   hide_duration=30         # 隐藏持续秒数
 *   show_duration=10         # 显示持续秒数
 *   whitelist=com.miui.home,com.tencent.mm   # 前台在这些包时不隐藏(逗号分隔)
 * 修改保存后最多一个周期内生效（周期循环里自动重读）
 */
public final class Config {

    public static final String CONF = "/data/local/tmp/antiburn.conf";

    public static boolean enableStatus = true;
    public static boolean enableNav = false;
    public static int hideSec = 30;
    public static int showSec = 10;
    public static Set<String> whitelist = new HashSet<>();

    private static long lastRead = 0;

    private Config() {
    }

    /** 重读配置，3 秒内去重 */
    public static synchronized void reload() {
        long now = System.currentTimeMillis();
        if (now - lastRead < 3000) {
            return;
        }
        lastRead = now;

        boolean es = true;
        boolean en = false;
        int h = 30;
        int s = 10;
        Set<String> wl = new HashSet<>();

        try (BufferedReader br = new BufferedReader(new FileReader(CONF))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int i = line.indexOf('=');
                if (i < 0) {
                    continue;
                }
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                switch (k) {
                    case "enable_status":
                        es = v.equals("1");
                        break;
                    case "enable_navigation":
                        en = v.equals("1");
                        break;
                    case "hide_duration":
                        h = parseInt(v, 30);
                        break;
                    case "show_duration":
                        s = parseInt(v, 10);
                        break;
                    case "whitelist":
                        for (String p : v.split(",")) {
                            p = p.trim();
                            if (!p.isEmpty()) {
                                wl.add(p);
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
            enableStatus = es;
            enableNav = en;
            hideSec = h;
            showSec = s;
            whitelist = wl;
            Log.i(XposedInit.TAG, "config reloaded: status=" + es + " nav=" + en
                    + " hide=" + h + "s show=" + s + "s whitelist=" + wl);
        } catch (Throwable t) {
            // 文件不存在或不可读：保持默认值即可，不视为错误
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
