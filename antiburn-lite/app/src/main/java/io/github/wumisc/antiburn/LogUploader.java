package io.github.wumisc.antiburn;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v3.2.38：诊断日志上传（Cloudflare Pages Function + D1）。
 *
 * <p><b>隐私与同意</b>：上传必须先取得用户同意。同意状态独立存一份 SharedPreferences
 * （与 Hook 配置、隐身偏好隔离），首次同意后记住；设置里可撤回。未同意时任何路径都不会发请求。</p>
 *
 * <p><b>随附环境</b>：只采排查所需的机型 / 系统 / 模块版本信息，不含账号、昵称等可识别内容。
 * 日志正文取私有镜像（本进程可读），超长截断到上限，避免单条请求撑爆 Binder / 免费额度。</p>
 *
 * <p>网络在调用方协程的 IO 线程执行；本类不碰 UI、不弹框，结果以字符串交回界面提示。</p>
 */
final class LogUploader {

    private static final String PREF = "antiburn_upload";
    private static final String KEY_CONSENT = "consent_given";
    /** 单次上传日志字符上限（约 120KB 文本）。 */
    private static final int LOG_CAP = 120_000;
    private static final int CONNECT_MS = 8_000;
    private static final int READ_MS = 10_000;

    /** 上传端点（Cloudflare Pages Function，落 D1 antiburn-logs）。 */
    static final String ENDPOINT = "https://antiburn-log.pages.dev/api/log";
    /** 未配置占位守卫：端点留空或等于占位值时拒绝发送。 */
    private static final String ENDPOINT_PLACEHOLDER = "";

    private LogUploader() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 用户是否已同意上传；默认 false（未同意绝不上传）。 */
    static boolean hasConsent(Context c) {
        return prefs(c).getBoolean(KEY_CONSENT, false);
    }

    /** 记录 / 撤回同意。 */
    static void setConsent(Context c, boolean consent) {
        prefs(c).edit().putBoolean(KEY_CONSENT, consent).apply();
    }

    /** 采集排查所需环境信息（不含账号 / 昵称）。 */
    private static Map<String, String> collectEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("model", safe(Build.MODEL));
        env.put("brand", safe(Build.BRAND));
        env.put("manufacturer", safe(Build.MANUFACTURER));
        env.put("device", safe(Build.DEVICE));
        env.put("product", safe(Build.PRODUCT));
        env.put("release", safe(Build.VERSION.RELEASE));
        env.put("sdk", String.valueOf(Build.VERSION.SDK_INT));
        env.put("module", BuildConfig.VERSION_NAME);
        env.put("moduleCode", String.valueOf(BuildConfig.VERSION_CODE));
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0) {
            env.put("abi", abis[0]);
        }
        return env;
    }

    /** 汇总日志正文：v3.4.0 起交给 {@link ConfStore#uploadLogText()} 合并三份来源；截断到上限。 */
    private static String collectLog(Context c) {
        String text;
        try {
            text = ConfStore.uploadLogText();
        } catch (Throwable t) {
            text = "";
        }
        if (text == null || text.isEmpty()) {
            // 极端兜底：合并失败时至少把公共日志带上去（原来是「只有配置快照」）
            text = collectLogFallback();
        }
        if (text.length() > LOG_CAP) {
            text = text.substring(text.length() - LOG_CAP);
        }
        return text;
    }

    private static String collectLogFallback() {
        StringBuilder sb = new StringBuilder();
        try {
            String mirror = ConfStore.readMirror();
            if (mirror != null && !mirror.isEmpty()) {
                sb.append("[config]\n").append(mirror).append('\n');
            }
        } catch (Throwable ignored) {
        }
        try {
            File pub = new File(ConfStore.LOG_FILE);
            if (pub.canRead()) {
                FileInputStream in = new FileInputStream(pub);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                in.close();
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /**
     * 阻塞执行上传。调用方必须在 IO 线程。
     *
     * @return 给界面提示的结果文案；失败不抛异常，返回失败原因。
     */
    static String uploadBlocking(Context c) {
        if (!hasConsent(c)) {
            return "未同意上传，已取消";
        }
        if (ENDPOINT.isBlank() || ENDPOINT.equals(ENDPOINT_PLACEHOLDER)) {
            return "上传地址尚未配置（等待云端部署）";
        }
        HttpURLConnection cn = null;
        try {
            JSONObject payload = new JSONObject();
            JSONObject env = new JSONObject(collectEnv());
            payload.put("env", env);
            payload.put("ts", System.currentTimeMillis());
            payload.put("log", collectLog(c));
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

            cn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            cn.setRequestMethod("POST");
            cn.setConnectTimeout(CONNECT_MS);
            cn.setReadTimeout(READ_MS);
            cn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            cn.setRequestProperty("Content-Length", String.valueOf(body.length));
            cn.setDoOutput(true);
            try (OutputStream os = cn.getOutputStream()) {
                os.write(body);
            }
            int code = cn.getResponseCode();
            if (code >= 200 && code < 300) {
                return "日志已上传 · " + collectEnv().get("module");
            }
            return "上传失败：服务端返回 " + code;
        } catch (Throwable t) {
            return "上传失败：" + t.getClass().getSimpleName();
        } finally {
            if (cn != null) {
                cn.disconnect();
            }
        }
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }
}
