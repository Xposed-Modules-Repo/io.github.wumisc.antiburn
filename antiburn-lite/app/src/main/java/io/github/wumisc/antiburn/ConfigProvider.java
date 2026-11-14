package io.github.wumisc.antiburn;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 把配置暴露给被 LSPosed hook 的进程（SystemUI / 抖音 / 红果）。
 *
 * 为什么需要它：/data/local/tmp 的 SELinux 标签是 shell_data_file，
 * appdomain（含 platform_app 的 SystemUI、untrusted_app 的抖音）默认读不到，
 * 所以旧版 Config.reload() 直接 FileReader 永远抛异常，模块一直跑默认值
 * ——「设置保存了却完全不生效」的真正原因。
 *
 * 这里用系统标准的 ContentProvider.call() 做跨进程读取：provider 导出的只是
 * 一小段纯文本开关配置，hook 进程通过 ContentResolver 拿到后按原格式解析。
 * 优先返回应用内镜像（免 root、免 IPC 阻塞），镜像缺失时再走 su 读 /data/local/tmp。
 */
public final class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.wumisc.antiburn.config";
    public static final String METHOD_GET = "antiburn_get_conf";
    public static final String KEY_CONF = "conf";
    /** v2.8.2：被注入进程写不出公共日志时的回传通道，由 App 进程代写。 */
    public static final String METHOD_PUT_LOG = "antiburn_put_log";
    public static final String KEY_LOG = "log";
    /** Provider 是 exported 的：要求调用方带模块内约定的 token，防止任意 App 读走配置全文 */
    public static final String KEY_TOKEN = "token";
    public static final String TOKEN = "antiburn-hook-v25";

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null) {
            ConfStore.init(ctx);
        }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // 模块 APK 同时加载进 App 与 hook 进程，两边共享这个常量；
        // 第三方 App 直接 call() 不带 token 时返回 null。
        if (extras == null || !TOKEN.equals(extras.getString(KEY_TOKEN))) {
            return null;
        }
        // v2.8.2「改日志」：视频 App 进程常拿不到 su，直写 /sdcard 又被分区存储挡住，
        // INJECTED / HOOK 这类关键行会整批丢失，导出日志里只剩 SystemUI 的 SYS 心跳。
        // 这里让 App 进程代写一行，是可观测性的最后一层兜底。
        if (METHOD_PUT_LOG.equals(method)) {
            String line = extras.getString(KEY_LOG);
            if (line != null && !line.isEmpty() && line.length() < 4096) {
                appendLog(line);
            }
            return null;
        }
        if (!METHOD_GET.equals(method)) {
            return null;
        }
        // 只读应用内镜像：provider 是导出的，不能在 Binder 线程里被任意应用触发 su。
        // 旧版本升级时镜像由 AbApplication 启动后的 migrateIfNeededAsync() 异步补齐，
        // 补齐之前返回 null，Hook 侧会自动走文件/su 兜底。
        String text = ConfStore.readMirror();
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        Bundle b = new Bundle();
        b.putString(KEY_CONF, text);
        return b;
    }

    /** 只追加、不创建目录结构之外的东西；失败静默，绝不因写日志影响配置读取。 */
    private void appendLog(String line) {
        try {
            java.io.File f = new java.io.File(ConfStore.LOG_FILE);
            java.io.File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true)) {
                fo.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
