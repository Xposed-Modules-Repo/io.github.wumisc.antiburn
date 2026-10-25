package io.github.wumisc.antiburn;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 励志古诗提供者：
 *  - 内置一份励志古诗库（保证无网络也有内容、且内容励志）
 *  - 联网时从一言诗词接口补充候选
 *  - 缓存只保留 5 首（/data/local/tmp/antiburn_poems.txt），每 3 天换一批
 */
public final class PoemProvider {

    private static final String TAG = XposedInit.TAG;
    private static final String CACHE = "/data/local/tmp/antiburn_poems.txt";
    private static final long REFRESH_MS = 3L * 24 * 3600 * 1000; // 3 天
    private static final int COUNT = 6; // 预备 6 首

    // 内置励志古诗库（诗句 | 作者 | 出处）
    private static final String[][] BUILTIN = {
            {"长风破浪会有时，直挂云帆济沧海。", "李白", "行路难"},
            {"欲穷千里目，更上一层楼。", "王之涣", "登鹳雀楼"},
            {"会当凌绝顶，一览众山小。", "杜甫", "望岳"},
            {"黑发不知勤学早，白首方悔读书迟。", "颜真卿", "劝学"},
            {"沉舟侧畔千帆过，病树前头万木春。", "刘禹锡", "酬乐天扬州初逢席上见赠"},
            {"千磨万击还坚劲，任尔东西南北风。", "郑燮", "竹石"},
            {"天生我材必有用，千金散尽还复来。", "李白", "将进酒"},
            {"少壮不努力，老大徒伤悲。", "汉乐府", "长歌行"},
            {"路漫漫其修远兮，吾将上下而求索。", "屈原", "离骚"},
            {"老骥伏枥，志在千里；烈士暮年，壮心不已。", "曹操", "龟虽寿"},
            {"山重水复疑无路，柳暗花明又一村。", "陆游", "游山西村"},
            {"不积跬步，无以至千里；不积小流，无以成江海。", "荀子", "劝学"},
            {"盛年不重来，一日难再晨。及时当勉励，岁月不待人。", "陶渊明", "杂诗"},
            {"千淘万漉虽辛苦，吹尽狂沙始到金。", "刘禹锡", "浪淘沙"},
            {"不畏浮云遮望眼，自缘身在最高层。", "王安石", "登飞来峰"},
            {"莫愁前路无知己，天下谁人不识君。", "高适", "别董大"},
            {"三更灯火五更鸡，正是男儿读书时。", "颜真卿", "劝学"},
            {"纸上得来终觉浅，绝知此事要躬行。", "陆游", "冬夜读书示子聿"},
            {"问渠那得清如许？为有源头活水来。", "朱熹", "观书有感"},
            {"咬定青山不放松，立根原在破岩中。", "郑燮", "竹石"},
            {"业精于勤，荒于嬉；行成于思，毁于随。", "韩愈", "进学解"},
            {"骐骥一跃，不能十步；驽马十驾，功在不舍。", "荀子", "劝学"},
            {"天行健，君子以自强不息。", "周易", "乾卦"},
            {"宝剑锋从磨砺出，梅花香自苦寒来。", "佚名", "警世贤文"},
            {"读书破万卷，下笔如有神。", "杜甫", "奉赠韦左丞丈二十二韵"},
            {"有志者事竟成，破釜沉舟，百二秦关终属楚。", "蒲松龄", "自勉联"},
    };

    private static final String NET_URL = "https://v1.hitokoto.cn/?c=i&encode=json";

    private PoemProvider() {
    }

    /** 确保缓存存在且是最新一批（后台线程执行，不阻塞 SystemUI） */
    public static void ensureCacheAsync() {
        Thread t = new Thread(() -> {
            try {
                ensureCache();
            } catch (Throwable e) {
                Log.w(TAG, "poem ensure error", e);
            }
        }, "poem-provider");
        t.setDaemon(true);
        t.start();
    }

    /** 读取当前缓存 6 首（完整显示串：诗句 —— 作者《出处》），并合并用户自定义诗句；
     *  自定义优先（去重），缓存不足 6 首用内置库兜底 */
    public static List<String> loadCached() {
        List<String> out = new ArrayList<>();
        // 用户自定义（每行一首：诗句 或 诗句|作者|出处）
        String custom = Config.customPoems;
        if (custom != null) {
            for (String line : custom.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\|", -1);
                if (p.length >= 1 && !p[0].trim().isEmpty()) {
                    String s = format(p[0].trim(), p.length >= 2 ? p[1].trim() : "",
                            p.length >= 3 ? p[2].trim() : "");
                    if (!out.contains(s)) {
                        out.add(s);
                    }
                }
            }
        }
        try {
            File f = new File(CACHE);
            if (f.exists()) {
                List<String> lines = new ArrayList<>();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(f), "UTF-8"))) {
                    String l;
                    while ((l = br.readLine()) != null) {
                        l = l.trim();
                        if (!l.isEmpty() && !l.startsWith("#")) {
                            lines.add(l);
                        }
                    }
                }
                // 首行是 ts
                if (lines.size() >= 2) {
                    for (int i = 1; i < lines.size(); i++) {
                        String[] p = lines.get(i).split("\\|", -1);
                        if (p.length >= 3) {
                            String s = format(p[0], p[1], p[2]);
                            if (!out.contains(s)) {
                                out.add(s);
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "poem read error", e);
        }
        // 兜底：从内置库取足 6 首
        for (int i = 0; out.size() < COUNT && i < BUILTIN.length; i++) {
            String s = format(BUILTIN[i][0], BUILTIN[i][1], BUILTIN[i][2]);
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 判断缓存是否过期（>3 天）或不存在 */
    private static boolean needsRefresh() {
        try {
            File f = new File(CACHE);
            if (!f.exists()) {
                return true;
            }
            long ts = readTs();
            return ts <= 0 || (System.currentTimeMillis() - ts) >= REFRESH_MS;
        } catch (Throwable e) {
            return true;
        }
    }

    private static long readTs() {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(CACHE), "UTF-8"));
            String l;
            while ((l = br.readLine()) != null) {
                l = l.trim();
                if (l.startsWith("ts=")) {
                    return Long.parseLong(l.substring(3));
                }
            }
            br.close();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void ensureCache() {
        if (!needsRefresh()) {
            return;
        }
        // 候选池：内置励志库 + 网络补充（一言诗词）
        List<String[]> pool = new ArrayList<>();
        Collections.addAll(pool, BUILTIN);
        List<String[]> net = fetchNetPoems();
        if (net != null) {
            pool.addAll(net);
        }
        // 随机选 6 首写缓存
        Collections.shuffle(pool, new Random());
        StringBuilder sb = new StringBuilder("# 励志古诗缓存\n");
        sb.append("ts=").append(System.currentTimeMillis()).append('\n');
        int n = Math.min(COUNT, pool.size());
        for (int i = 0; i < n; i++) {
            String[] p = pool.get(i);
            sb.append(p[0]).append('|').append(p[1]).append('|').append(p[2]).append('\n');
        }
        try {
            File f = new File(CACHE);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
                w.write(sb.toString());
            }
            Log.i(TAG, "poem cache refreshed, " + n + " poems");
        } catch (Throwable e) {
            Log.w(TAG, "poem write error", e);
        }
    }

    /** 从一言诗词接口拉取若干首作为补充候选（失败返回 null 不影响内置库） */
    private static List<String[]> fetchNetPoems() {
        List<String[]> out = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(NET_URL).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "antiburn/1.0");
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = br.readLine()) != null) {
                        sb.append(l);
                    }
                    String json = sb.toString();
                    String content = grab(json, "hitokoto");
                    String who = grab(json, "from_who");
                    String from = grab(json, "from");
                    if (content != null && !content.isEmpty()) {
                        out.add(new String[]{
                                content,
                                (who == null || who.isEmpty()) ? "佚名" : who,
                                (from == null || from.isEmpty()) ? "古诗词" : from});
                    }
                }
                conn.disconnect();
            }
        } catch (Throwable e) {
            Log.w(TAG, "poem net error", e);
            return null;
        }
        return out.isEmpty() ? null : out;
    }

    /** 极简 JSON 取值（不带 JSON 库依赖） */
    private static String grab(String json, String key) {
        try {
            String k = "\"" + key + "\":";
            int idx = json.indexOf(k);
            if (idx < 0) {
                return null;
            }
            int s = json.indexOf('"', idx + k.length());
            if (s < 0) {
                return null;
            }
            int e = json.indexOf('"', s + 1);
            if (e < 0) {
                return null;
            }
            return json.substring(s + 1, e);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String format(String content, String author, String from) {
        if (author == null || author.isEmpty()) {
            return content;
        }
        if (from == null || from.isEmpty()) {
            return content + " ——" + author;
        }
        return content + " ——" + author + "《" + from + "》";
    }
}
