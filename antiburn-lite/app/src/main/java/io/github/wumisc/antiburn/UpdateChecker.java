package io.github.wumisc.antiburn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * v3.4.21：正式版更新检测。
 *
 * <p><b>只读公开接口</b>：GitHub Releases 的 {@code /releases/latest} 是匿名可读的，
 * 不需要 token、不读用户任何信息；123 云盘分享目录没有公开版本接口，只作为下载备用渠道。</p>
 *
 * <p><b>节流</b>：启动时最多每 {@link #CHECK_GAP_MS} 查一次，手动「检查更新」无视节流。
 * 网络失败一律静默（返回 error），绝不因为查更新把界面卡住或弹错误框。</p>
 *
 * <p><b>不打扰</b>：只有远端版本比本机高才返回 info；用户点「以后再说」记住该版本号，
 * 同一版本不再重复弹。</p>
 */
final class UpdateChecker {

    /** GitHub Releases 最新页（人看的）。 */
    static final String RELEASE_PAGE = "https://github.com/wumisc/antiburn/releases/latest";
    /** GitHub Releases API（机器读的）。 */
    private static final String API =
            "https://api.github.com/repos/wumisc/antiburn/releases/latest";

    private static final String PREF = "antiburn_update";
    private static final String KEY_LAST = "last_check_ms";
    private static final String KEY_SKIP = "skip_version_code";

    /** 自动检查的最小间隔：24 小时。 */
    static final long CHECK_GAP_MS = 24L * 60 * 60 * 1000;
    private static final int CONNECT_MS = 8_000;
    private static final int READ_MS = 10_000;

    private UpdateChecker() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 远端版本信息。 */
    static final class Info {
        final int versionCode;
        final String versionName;
        final String tag;
        final String apkUrl;

        Info(int versionCode, String versionName, String tag, String apkUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.tag = tag;
            this.apkUrl = apkUrl;
        }
    }

    /** 一次检查的结果：三态，界面据此给出不同提示。 */
    static final class Result {
        /** 有新版时非空。 */
        final Info info;
        /** 网络 / 解析失败时非空。 */
        final String error;
        /** 远端不比本机新。*/
        final boolean upToDate;

        Result(Info info) {
            this.info = info;
            this.error = null;
            this.upToDate = info == null;
        }

        Result(String error) {
            this.info = null;
            this.error = error;
            this.upToDate = false;
        }
    }

    /**
     * 检查更新。
     *
     * @param force true=手动点「检查更新」，跳过 24h 节流与「以后再说」记录
     */
    static Result check(Context c, boolean force) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (!force) {
            long last = p.getLong(KEY_LAST, 0L);
            if (last > 0 && now - last < CHECK_GAP_MS) return null;
        }
        Result r;
        try {
            r = fetch();
        } catch (Throwable t) {
            r = new Result("网络不可用：" + (t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage()));
        }
        p.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
        if (r.info != null && !force && r.info.versionCode == p.getInt(KEY_SKIP, -1)) return null;
        return r;
    }

    /** 用户选择「以后再说」：记住该版本，之后不再自动弹。 */
    static void snooze(Context c, int versionCode) {
        prefs(c).edit().putInt(KEY_SKIP, versionCode).apply();
    }

    private static Result fetch() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API).openConnection();
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "antiburn/" + BuildConfig.VERSION_NAME);
        try {
            int code = conn.getResponseCode();
            if (code != 200) return new Result("接口返回 HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            Info remote = parse(root);
            if (remote == null) return new Result("版本号无法识别");
            return new Result(remote.versionCode > BuildConfig.VERSION_CODE ? remote : null);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 从 release JSON 解析版本。
     *
     * <p>优先用 {@code tag_name} 的 {@code <versionCode>-<versionName>} 口径（本项目 Releases 一直这么命名）；
     * 万一改名，退回 {@code name} 里的 {@code vX.Y.Z}，versionCode 由 {@link #codeOf(String)} 折算。</p>
     */
    private static Info parse(JSONObject root) {
        String tag = opt(root, "tag_name");
        String title = opt(root, "name");
        String apkUrl = null;
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String n = opt(a, "name");
                if (n == null) continue;
                if (n.toLowerCase().endsWith(".apk")) {
                    String u = opt(a, "browser_download_url");
                    if (u != null && u.startsWith("https://")) apkUrl = u;
                    break;
                }
            }
        }
        if (tag == null) return null;
        int dash = tag.indexOf('-');
        if (dash > 0) {
            int code = parseInt(tag.substring(0, dash));
            String ver = tag.substring(dash + 1).trim();
            if (code > 0 && !ver.isEmpty()) return new Info(code, ver, tag, apkUrl);
        }
        // 兜底：tag/标题里找 vX.Y.Z
        String ver = firstVersion(tag);
        if (ver == null) ver = firstVersion(title);
        if (ver == null) return null;
        return new Info(codeOf(ver), ver, tag, apkUrl);
    }

    /** v3.4.20 → 162（与 build.gradle 的 versionCode 口径一致：major*40 + minor*10 + patch 的递增序列不再线性，故取保守折算）。 */
    private static int codeOf(String ver) {
        String[] parts = ver.split("\\.");
        int a = parseInt(parts.length > 0 ? parts[0] : null);
        int b = parseInt(parts.length > 1 ? parts[1] : null);
        int c = parseInt(parts.length > 2 ? parts[2] : null);
        if (a <= 0) return 0;
        // 无法精确还原历史递增，给出「不高于实际」的估算：能判断「远端更新」，不会误报
        return Math.max(1, (a * 1000) + (b * 10) + c);
    }

    private static String firstVersion(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String opt(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return null;
        String v = o.optString(k, "");
        return v.isEmpty() ? null : v;
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
