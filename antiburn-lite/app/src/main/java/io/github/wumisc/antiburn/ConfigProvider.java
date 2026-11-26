package io.github.wumisc.antiburn;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.File;

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

    /**
     * 热重载记账通道：hook 进程热重载成功后 call 一次，App 侧把时间写进私有 prefs，
     * 界面据此判断「系统界面里跑的已经是新代码」，不再提示重启。
     */
    public static final String METHOD_PUT_RELOAD = "antiburn_put_reload";
    public static final String HOOK_PREFS = "antiburn_hook";
    public static final String KEY_RELOAD_AT = "hot_reload_at";
    /** 模块曾经在系统界面进程里成功装上钩子的时间；只有它为空才算「首次安装」。 */
    public static final String METHOD_PUT_SEEN = "antiburn_put_seen";
    public static final String KEY_SEEN_AT = "hook_seen_at";
    /** 自换代码成功时间（存的是「换到的那一版 APK 的 lastUpdateTime」）；用于和框架热重载互斥。 */
    public static final String METHOD_PUT_SELF = "antiburn_put_self";
    public static final String KEY_SELF_AT = "self_reload_at";
    /** 一次读回三个代际时间，供 SystemUI 决定「这一版到底该由谁来装」。 */
    public static final String METHOD_GET_GEN = "antiburn_get_gen";

    /**
     * v3.2.37：自换代码的 APK 交接通道。SystemUI 等 hook 进程受 SELinux 限制，
     * 直接读模块 /data/app/…/base.apk 会失败（DexClassLoader 抛 ClassNotFoundException）。
     * 由本进程（能读自己的 APK）打开只读 FD 经 Binder 传给 hook 进程，绕过路径权限。
     */
    public static final String METHOD_GET_APK = "antiburn_get_apk";
    public static final String KEY_APK_FD = "apk_fd";
    /** Provider 是 exported 的：要求调用方带模块内约定的 token，防止任意 App 读走配置全文 */
    public static final String KEY_TOKEN = "token";
    public static final String TOKEN = "antiburn-hook-v25";
    /** 注入痕迹存储：key = douyin / drama / kuaishou / bilibili，value = 最近一次见到注入的时间戳 */
    public static final String INJECT_PREFS = "antiburn_inject";

    /**
     * v2.9.4：注入进程日志的「App 内镜像」文件名。
     * 被 hook 的进程经本 Provider 回传的行先落到这里，导出时再由 su 合并进用户可见日志。
     */
    public static final String HOOK_LOG_FILE = "hook-injected.log";
    /** 镜像文件上限：超过就重新写，避免长期使用无限膨胀 */
    private static final long HOOK_LOG_MAX_BYTES = 512 * 1024L;

    /** 最近一次见到该 App 的注入痕迹；0 = 从未见过（v2.9.1） */
    public static long lastInject(Context ctx, String kind) {
        if (ctx == null || kind == null) {
            return 0L;
        }
        try {
            return ctx.getSharedPreferences(INJECT_PREFS, Context.MODE_PRIVATE)
                    .getLong(kind, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * v3.2.17：调用方白名单。
     *
     * <p>用 {@link Binder#getCallingUid()} 取真实调用方，再按包名白名单校验，
     * 而不是信任 extras 里的 token —— token 在 dex 里，谁都能读。</p>
     */
    private boolean callerAllowed() {
        try {
            int uid = Binder.getCallingUid();
            if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == 0 || uid == 2000) {
                return true;
            }
            Context ctx = getContext();
            if (ctx == null) {
                return false;
            }
            String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) {
                return false;
            }
            for (String p : pkgs) {
                if (p == null) {
                    continue;
                }
                if (p.equals("com.android.systemui") || p.startsWith("io.github.wumisc.antiburn")) {
                    return true;
                }
                if (VideoApps.of(p) != VideoApps.Kind.NONE) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

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
        // v3.2.17（审计修正）：token 是写在 APK 里的常量，任何 App 反编译都能拿到，
        // 单靠它挡不住「读配置 / 往公共日志里灌垃圾」。这里再按调用方 uid 校验一次：
        // 只放行本模块、SystemUI、root/shell 与四个视频 App，其余一律等同于没带 token。
        if (!callerAllowed()) {
            return null;
        }
        // v3.2.37：把本进程自己的 APK 以只读 FD 交给 hook 进程做自换代码。
        // FD 经 Binder 传递不受 /data/app 路径 SELinux 限制，接收方拷到本地缓存再载 dex。
        if (METHOD_GET_APK.equals(method)) {
            Bundle r = new Bundle();
            try {
                Context c = getContext();
                String src = c.getPackageManager().getPackageInfo(c.getPackageName(), 0)
                        .applicationInfo.sourceDir;
                ParcelFileDescriptor fd = ParcelFileDescriptor.open(
                        new File(src), ParcelFileDescriptor.MODE_READ_ONLY);
                r.putParcelable(KEY_APK_FD, fd);
            } catch (Throwable t) {
                Log.w("antiburn", "get_apk fail " + t);
            }
            return r;
        }
        // v2.8.2「改日志」：视频 App 进程常拿不到 su，直写 /sdcard 又被分区存储挡住，
        // INJECTED / HOOK 这类关键行会整批丢失，导出日志里只剩 SystemUI 的 SYS 心跳。
        // 这里让 App 进程代写一行，是可观测性的最后一层兜底。
        if (METHOD_PUT_LOG.equals(method)) {
            String line = extras.getString(KEY_LOG);
            if (line != null && !line.isEmpty() && line.length() < 4096) {
                appendLog(line);
                noteInject(line);
            }
            return null;
        }
        if (METHOD_PUT_SELF.equals(method)) {
            try {
                getContext().getSharedPreferences(HOOK_PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_SELF_AT, extras.getLong(KEY_CONF, 0L)).commit();
                Bundle r = new Bundle();
                r.putBoolean("ok", true);
                return r;
            } catch (Throwable t) {
                Log.w("Antiburn", "note self reload failed", t);
                return null;
            }
        }
        if (METHOD_GET_GEN.equals(method)) {
            try {
                android.content.SharedPreferences sp =
                        getContext().getSharedPreferences(HOOK_PREFS, Context.MODE_PRIVATE);
                Bundle r = new Bundle();
                r.putLong(KEY_RELOAD_AT, sp.getLong(KEY_RELOAD_AT, 0L));
                r.putLong(KEY_SELF_AT, sp.getLong(KEY_SELF_AT, 0L));
                r.putLong(KEY_SEEN_AT, sp.getLong(KEY_SEEN_AT, 0L));
                return r;
            } catch (Throwable t) {
                return null;
            }
        }
        if (METHOD_PUT_SEEN.equals(method)) {
            try {
                getContext().getSharedPreferences(HOOK_PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_SEEN_AT, System.currentTimeMillis()).commit();
                Bundle r = new Bundle();
                r.putBoolean("ok", true);
                return r;
            } catch (Throwable t) {
                Log.w("Antiburn", "note hook seen failed", t);
                return null;
            }
        }
        if (METHOD_PUT_RELOAD.equals(method)) {
            long at = System.currentTimeMillis();
            try {
                getContext().getSharedPreferences(HOOK_PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_RELOAD_AT, at).commit();
                Bundle r = new Bundle();
                r.putLong(KEY_RELOAD_AT, at);
                return r;
            } catch (Throwable t) {
                Log.w("Antiburn", "note hot reload failed", t);
                return null;
            }
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

    /**
     * v2.9.1：把「哪个视频 App 真的被注入了」记进 SharedPreferences，供首页直接展示。
     *
     * <p>此前这个结论只能靠翻日志里的 INJECTED 行，而注入进程写日志的通道本身
     * 就在漏（su 恒成功、Provider 从不触发），于是用户和开发者都在猜。
     * 现在注入进程每写一条 ENTER / INJECTED / HOOK-ATTACH，App 进程顺手记一次时间戳，
     * 首页一屏就能看出「抖音已注入、快手没注入」——没注入就是 LSPosed 作用域没勾，
     * 不用再猜是识别规则坏了还是开关没开。</p>
     */
    private void noteInject(String line) {
        try {
            String kind = null;
            int i = line.indexOf("INJECTED ");
            if (i >= 0) {
                String rest = line.substring(i + 9);
                if (rest.startsWith("Douyin")) {
                    kind = "douyin";
                } else if (rest.startsWith("Drama")) {
                    kind = "drama";
                } else if (rest.startsWith("Kuaishou")) {
                    kind = "kuaishou";
                } else if (rest.startsWith("Bilibili")) {
                    kind = "bilibili";
                }
            }
            if (kind == null) {
                // ENTER / HOOK-ATTACH 都带 pkg=，用同一个真源归类，避免两处各写一份判断
                int p = line.indexOf("pkg=");
                if (p > 0) {
                    String pkg = line.substring(p + 4).trim();
                    int sp = pkg.indexOf(' ');
                    if (sp > 0) {
                        pkg = pkg.substring(0, sp);
                    }
                    switch (VideoApps.of(pkg)) {
                        case DOUYIN: kind = "douyin"; break;
                        case DRAMA: kind = "drama"; break;
                        case KUAISHOU: kind = "kuaishou"; break;
                        case BILIBILI: kind = "bilibili"; break;
                        default: kind = null; break;
                    }
                }
            }
            if (kind == null) {
                return;
            }
            Context ctx = getContext();
            if (ctx == null) {
                return;
            }
            ctx.getSharedPreferences(INJECT_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(kind, System.currentTimeMillis())
                    .apply();
            // v3.0.24：注入也算一次「目标 App 出现」，让常驻通知在节流窗口内换个文案。
            // 这里跑在 Binder 线程，refreshIfDue 只做一次时间比较 + post 到主线程。
            GuardService.refreshIfDue(ctx);
        } catch (Throwable ignored) {
            // 记录注入痕迹失败不得影响日志落盘
        }
    }

    /** 只追加、不创建目录结构之外的东西；失败静默，绝不因写日志影响配置读取。 */
    /**
     * v2.9.4「日志黑洞」真因修复。
     *
     * <p>旧实现直接写 {@link ConfStore#LOG_FILE}
     * （{@code /storage/emulated/0/Android/Prevent burn-in/antiburn.log}）。可这里跑在
     * <b>App 进程</b>，Android 11+ 分区存储下普通应用用 Java File API 根本写不进
     * {@code /sdcard/Android/} 下的自定义目录；异常又被 {@code catch} 静默吞掉。于是
     * 被注入的抖音 / 快手 / 红果 / B站 进程明明成功 {@code call()} 回来了，
     * {@code ENTER / INJECTED / HOOK-ATTACH / HOOK} 却一行都进不了导出日志，只剩
     * SystemUI 的心跳 —— 前几版一直在「没勾作用域 / 没注入 / 规则没扫到」之间猜，
     * 证据从来没落盘。（{@code noteInject} 写 SharedPreferences 是成功的，所以首页
     * 「注入状态」能显示「已注入」，这恰好反证 Provider 调用本身没问题。）</p>
     *
     * <p>现在写 App 私有 {@code files/} 目录（无需任何权限、不受分区存储限制），
     * 由 {@link ConfStore#exportLogs()} 用 su 把这个文件一起 cat 进用户可见日志。</p>
     */
    private void appendLog(String line) {
        // v3.2.16：**先写用户可见的 antiburn.log**。
        // 真机复盘：用户每次都是从文件管理器直接捞这个文件，而它以前只收模块自己的行
        // （SYS / MASK / CONF），视频 App 的 ENTER / INJECTED / RDUMP / BDUMP 全躺在
        // 私有镜像里，要等「点导出」或常驻服务 180 秒补录才并进来 —— 于是每份日志都
        // 像「没注入」。这里就地追加，用户捞到的那份就是全的。
        if (ConfStore.appendHookLine(line)) {
            return;
        }
        // 落不进公共日志（权限被系统收回 / 文件被删）时退回私有镜像，导出照样能合并
        try {
            Context ctx = getContext();
            if (ctx == null) {
                return;
            }
            java.io.File f = new java.io.File(ctx.getFilesDir(), HOOK_LOG_FILE);
            if (f.length() > HOOK_LOG_MAX_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
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
