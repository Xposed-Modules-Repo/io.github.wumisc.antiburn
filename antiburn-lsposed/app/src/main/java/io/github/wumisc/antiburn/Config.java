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
    public static boolean enableDouyinTop = false;
    public static boolean enableDouyinBottom = false;
    public static boolean enableDouyinLike = false;
    public static boolean enableDouyinRt = false;
    public static boolean enableDouyinLt = false;
    // 红果短剧独立开关（默认关，勾选红果作用域后按需开启）
    public static boolean enableDramaTop = false;
    public static boolean enableDramaBottom = false;
    public static boolean enableDramaLike = false;
    public static boolean enableDramaRt = false;
    public static boolean enableDramaLt = false;
    // 直播间隐藏（默认开）：若直播黑屏怀疑模块误伤，可关闭此开关让直播页完全不被碰
    public static boolean enableLive = true;
    // 状态栏隐藏时滚动显示励志古诗（默认关；联网+缓存6首，每3天换一批）
    public static boolean enablePoem = false;
    // 抖音/红果悬浮球（默认关）：点击切换隐藏控件的显示/消失，用于查看各区域效果
    public static boolean enableBall = false;
    // 用户自定义诗句（每行一首；支持 诗句 或 诗句|作者|出处），参与古诗随机滚动
    public static String customPoems = "";
    public static boolean enableIcon = false;
    public static boolean enableLog = false; // 默认关闭省电省存储；排查问题时再打开
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
        boolean dtop = false;
        boolean dbot = false;
        boolean dlike = false;
        boolean drt = false;
        boolean dlt = false;
        boolean ic = false;
        boolean lg = false;
        boolean drTop = false, drBot = false, drLike = false, drRt = false, drLt = false;
        boolean live = true;
        boolean poem = false;
        boolean ball = false;
        String custom = "";
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
                    case "enable_douyin_top":
                        dtop = v.equals("1");
                        break;
                    case "enable_douyin_bottom":
                        dbot = v.equals("1");
                        break;
                    case "enable_douyin_like":
                        dlike = v.equals("1");
                        break;
                    case "enable_douyin_right_top":
                        drt = v.equals("1");
                        break;
                    case "enable_douyin_left_top":
                        dlt = v.equals("1");
                        break;
                    case "enable_icon":
                        ic = v.equals("1");
                        break;
                    case "enable_log":
                        lg = v.equals("1");
                        break;
                    case "enable_drama_top":
                        drTop = v.equals("1");
                        break;
                    case "enable_drama_bottom":
                        drBot = v.equals("1");
                        break;
                    case "enable_drama_like":
                        drLike = v.equals("1");
                        break;
                    case "enable_drama_right_top":
                        drRt = v.equals("1");
                        break;
                    case "enable_drama_left_top":
                        drLt = v.equals("1");
                        break;
                    case "enable_live":
                        live = v.equals("1");
                        break;
                    case "enable_poem":
                        poem = v.equals("1");
                        break;
                    case "enable_ball":
                        ball = v.equals("1");
                        break;
                    case "custom_poems":
                        custom = v;
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
            enableDouyinTop = dtop;
            enableDouyinBottom = dbot;
            enableDouyinLike = dlike;
            enableDouyinRt = drt;
            enableDouyinLt = dlt;
            enableIcon = ic;
            enableLog = lg;
            enableDramaTop = drTop;
            enableDramaBottom = drBot;
            enableDramaLike = drLike;
            enableDramaRt = drRt;
            enableDramaLt = drLt;
            enableLive = live;
            enablePoem = poem;
            enableBall = ball;
            customPoems = custom;
            hideSec = h;
            showSec = s;
            whitelist = wl;
            Log.i(XposedInit.TAG, "config reloaded: status=" + es + " nav=" + en
                    + " dyTop=" + dtop + " dyBottom=" + dbot + " dyLike=" + dlike
                    + " dyRt=" + drt + " dyLt=" + dlt + " icon=" + ic
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
