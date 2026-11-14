package io.github.wumisc.antiburn;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LSPosed 作用域自检（只读，不改配置）。
 *
 * <p><b>为什么需要它</b>：真机日志里 {@code inject=douyin:0 drama:0 kuaishou:0
 * bilibili:0 hookdiag:0} 连续多版成立，而 {@code INJECTED SystemUI} 一直都在。
 * 模块自己的 {@code SCOPE targets=…} 只是 arrays.xml 里声明过的包名，<b>不等于</b>
 * LSPosed 的实际作用域；LSPosed 只往「管理器里给本模块勾选过」的包里注入。
 * 作用域没勾，{@link DouyinHook} 一行都跑不到，位置微移自然无从谈起 ——
 * 用户看到的就是「不管怎么改都没反应」。</p>
 *
 * <p><b>v2.8.5 的关键修正</b>：v2.8.4 用 {@code sqlite3} 命令行读配置库，而绝大多数
 * Android 设备（含真机 OPPO）根本没有 sqlite3 二进制，于是自检永远停在
 * {@code sqlite3=missing}，作用域内容一次都没读出来过。这里改成 --root 复制配置库
 * 到应用私有目录，再用系统自带的 {@code SQLiteDatabase} 打开查询，不依赖任何外部命令。</p>
 *
 * <p><b>为什么只读不写</b>：直接往 {@code modules_config.db} 里 INSERT 看似省事，
 * 但 LSPosed 的服务端在启动时把作用域读进内存、并与 WAL 共用同一份文件，外部写入
 * 既要处理属主 / SELinux / WAL 一致性，又可能让管理器下次启动时判定配置损坏。
 * 勾选作用域在管理器里只需几步，风险却全在用户设备上，不值得代劳。这里只负责
 * <b>把真相读出来并说清楚缺哪几个</b>。</p>
 */
final class LspScope {

    static final String MODULE = "io.github.wumisc.antiburn";

    /** 各版本 LSPosed 的配置库位置，按存在性取第一个。 */
    private static final String[] DBS = {
            "/data/adb/lspd/config/modules_config.db",
            "/data/adb/modules/lsposed/config/modules_config.db",
            "/data/adb/modules/zygisk_lsposed/config/modules_config.db",
    };

    private LspScope() {
    }

    /** 一次自检的结论；字段全部为 null / 空时看 {@link #error}。 */
    static final class Report {
        String db;
        String error;
        boolean copied;
        boolean opened;
        int mid = -1;
        final Set<String> scope = new LinkedHashSet<>();
        final Set<String> installed = new LinkedHashSet<>();
        final Set<String> missing = new LinkedHashSet<>();

        boolean ok() {
            return opened && mid > 0 && (error == null || error.isEmpty());
        }
    }

    private static String findDb() {
        for (String p : DBS) {
            String out = ConfStore.execSuCapture("test -f \"" + p + "\" && echo yes || echo no");
            if (out != null && out.trim().equals("yes")) {
                return p;
            }
        }
        return null;
    }

    /**
     * 把配置库（含 WAL / SHM）复制到应用私有目录。
     *
     * <p><b>v2.8.6 的关键修正</b>：v2.8.5 用 {@code cp} + {@code chown} + {@code restorecon}
     * 把文件搬进私有目录，真机上 {@code SQLiteDatabase.openDatabase} 抛
     * {@code SQLiteException}（日志里 {@code error=open:SQLiteException}）——
     * 属主改了、SELinux context 却没落到应用自己的标签上，进程依旧读不了。</p>
     *
     * <p>现在改成：root 端只做 {@code base64 -w0} 输出字节，<b>文件由应用进程自己创建</b>。
     * 自己创建的文件天然带 {@code app_data_file} 标签和本 uid，不再需要任何 chown/restorecon。
     * WAL 一并搬过来，SQLite 打开时会自动重放，避免漏掉「刚勾选、还没 checkpoint」的作用域。</p>
     */
    private static File stage(Context ctx, String db) {
        File dir = new File(ctx.getFilesDir(), "lspdb");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File copy = new File(dir, "modules_config.db");
        if (!pull(db, copy)) {
            return null;
        }
        // WAL / SHM 是可选件：没有就跳过，有就必须同名放好，否则打开时读到旧快照
        pull(db + "-wal", new File(dir, "modules_config.db-wal"));
        pull(db + "-shm", new File(dir, "modules_config.db-shm"));
        return copy.exists() && copy.length() > 0 ? copy : null;
    }

    /**
     * 用 root 把 src 以 base64 读出来，再由本进程写进 dst。
     *
     * <p>不用 {@code cp} 是为了绕开 SELinux：私有目录里的文件必须由应用自己创建，
     * 否则即便 uid 对了，标签不对照样 {@code EACCES}。</p>
     */
    private static boolean pull(String src, File dst) {
        String b64 = ConfStore.execSuCapture(
                "base64 -w0 '" + src + "' 2>/dev/null", 20_000L);
        if (b64 == null) {
            return false;
        }
        b64 = b64.trim();
        if (b64.isEmpty()) {
            return false;
        }
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            if (data.length == 0) {
                return false;
            }
            try (FileOutputStream fos = new FileOutputStream(dst, false)) {
                fos.write(data);
                fos.flush();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 打开副本数据库。
     *
     * <p><b>v2.8.8 的根因修正</b>：v2.8.5 / v2.8.6 都用 {@code OPEN_READONLY} 打开，
     * 而 LSPosed 的配置库是 <b>WAL 模式</b>。WAL 库在只读句柄下必须先建立
     * {@code <db>-shm} 共享内存索引，只读打开建不了这个文件，SQLite 直接返回
     * {@code SQLITE_CANTOPEN(14)}，Java 侧抛基类 {@code SQLiteException} ——
     * 日志里那句 {@code error=open:SQLiteException} 与 SELinux、属主、chcon 全都无关，
     * 前两版一直往权限方向查，方向错了。</p>
     *
     * <p>副本位于应用私有目录，改为 {@code OPEN_READWRITE} 打开：SQLite 能正常建 shm、
     * 重放 WAL，读到的也是「刚勾完还没 checkpoint」的最新作用域；原库在
     * {@code /data/adb/lspd/} 下，改的是副本，管理器那份不受任何影响。</p>
     *
     * <p>若 READWRITE 仍失败，多半是搬过来的 WAL/SHM 是被写了一半的快照，
     * 删掉让 SQLite 用主文件 + 新建 shm 再来一次。</p>
     */
    private static SQLiteDatabase open(File copy) {
        try {
            return SQLiteDatabase.openDatabase(copy.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
        } catch (Throwable first) {
            new File(copy.getPath() + "-wal").delete();
            new File(copy.getPath() + "-shm").delete();
            return SQLiteDatabase.openDatabase(copy.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
        }
    }

    /** {@code modules} 表里存包名的列；不同 LSPosed 版本列名有过变化，按 pragma 现查。 */
    private static String pkgColumn(SQLiteDatabase d, String table) {
        List<String> cols = new ArrayList<>();
        try (Cursor c = d.rawQuery("pragma table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                String n = c.getString(c.getColumnIndexOrThrow("name"));
                if (n != null) {
                    cols.add(n);
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        if (cols.contains("module_pkg_name")) {
            return "module_pkg_name";
        }
        for (String c : cols) {
            if (c.contains("pkg")) {
                return c;
            }
        }
        return null;
    }

    private static boolean hasTable(SQLiteDatabase d, String table) {
        try (Cursor c = d.rawQuery(
                "select name from sqlite_master where type='table' and name=?", new String[]{table})) {
            return c.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 设备上真实安装的目标应用包名（渠道包 / 海外版都算），用于精确比对作用域。 */
    private static Set<String> installedTargets(Context ctx) {
        Set<String> out = new LinkedHashSet<>();
        try {
            List<PackageInfo> list = ctx.getPackageManager().getInstalledPackages(0);
            if (list != null) {
                for (PackageInfo pi : list) {
                    if (pi != null && VideoApps.isTarget(pi.packageName)) {
                        out.add(pi.packageName);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 部分 ROM 即便声明了 QUERY_ALL_PACKAGES 也只返回可见包，走下面的 su 兜底
        }
        if (out.isEmpty()) {
            String pm = ConfStore.execSuCapture("pm list packages 2>/dev/null");
            if (pm != null) {
                for (String line : pm.split("\\n")) {
                    String p = line.trim();
                    if (p.startsWith("package:")) {
                        p = p.substring(8).trim();
                    }
                    if (VideoApps.isTarget(p)) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    /** 读作用域并算出缺口；任何一步失败都写进 error，不抛异常。 */
    static Report probe(Context ctx) {
        Report r = new Report();
        try {
            r.db = findDb();
            if (r.db == null) {
                r.error = "no-db";
                return r;
            }
            if (ctx == null) {
                r.error = "no-context";
                return r;
            }
            File copy = stage(ctx, r.db);
            if (copy == null) {
                r.error = "su-denied";
                return r;
            }
            r.copied = true;
            SQLiteDatabase d = null;
            try {
                d = open(copy);
                r.opened = true;
                if (!hasTable(d, "modules")) {
                    r.error = "schema";
                    return r;
                }
                String col = pkgColumn(d, "modules");
                if (col == null) {
                    r.error = "schema";
                    return r;
                }
                try (Cursor c = d.rawQuery(
                        "select mid from modules where " + col + "=? limit 1", new String[]{MODULE})) {
                    if (c.moveToFirst()) {
                        r.mid = c.getInt(0);
                    }
                }
                if (r.mid <= 0) {
                    r.error = "unregistered";
                    return r;
                }
                if (hasTable(d, "scope")) {
                    try (Cursor c = d.rawQuery(
                            "select app_pkg_name from scope where mid=?",
                            new String[]{String.valueOf(r.mid)})) {
                        while (c.moveToNext()) {
                            String s = c.getString(0);
                            if (s != null && !s.trim().isEmpty()) {
                                r.scope.add(s.trim());
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                // v2.8.8：只记异常类名会把 SQLITE_CANTOPEN(14)、文件损坏、schema 不符
                // 全糊成一句 SQLiteException，够不着根因。带上 message 与副本字节数。
                String msg = t.getMessage();
                if (msg == null) {
                    msg = "";
                }
                msg = msg.replace('\n', ' ').trim();
                if (msg.length() > 90) {
                    msg = msg.substring(0, 90);
                }
                r.error = "open:" + t.getClass().getSimpleName()
                        + ":" + msg + ":len=" + copy.length();
            } finally {
                if (d != null) {
                    try {
                        d.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            r.installed.addAll(installedTargets(ctx));
            for (String p : r.installed) {
                if (!r.scope.contains(p)) {
                    r.missing.add(p);
                }
            }
            return r;
        } catch (Throwable t) {
            r.error = "error:" + t.getClass().getSimpleName();
            return r;
        }
    }

    private static String zh(String pkg) {
        switch (VideoApps.of(pkg)) {
            case DOUYIN: return "抖音";
            case DRAMA: return "红果";
            case KUAISHOU: return "快手";
            case BILIBILI: return "B站";
            default: return "其它";
        }
    }

    private static String join(Set<String> s) {
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(x);
        }
        return sb.toString();
    }

    /** 单行日志版本：字段短、可 grep，便于用户把一行贴回来定位。 */
    static String ensure(Context ctx) {
        Report r = probe(ctx);
        StringBuilder sb = new StringBuilder("LSPDB db=").append(r.db == null ? "none" : r.db);
        if (r.error != null) {
            sb.append(" error=").append(r.error);
            switch (r.error) {
                case "no-db":
                    sb.append("（没找到 LSPosed 配置库：可能是 KernelSU 版路径不同，或框架不是 LSPosed）");
                    break;
                case "su-denied":
                    sb.append("（root 未授权，读不到作用域；请给本应用 root 权限后重开）");
                    break;
                case "unregistered":
                    sb.append("（LSPosed 里还没启用本模块：先启用模块并重启手机）");
                    break;
                case "schema":
                    sb.append("（配置库结构不认识，可能是很新/很旧的 LSPosed 版本）");
                    break;
                default:
                    break;
            }
            return sb.toString();
        }
        sb.append(" mid=").append(r.mid)
                .append(" scope=").append(r.scope.size()).append('[').append(join(r.scope)).append(']')
                .append(" installed=").append(r.installed.size()).append('[').append(join(r.installed)).append(']');
        if (r.missing.isEmpty()) {
            sb.append(" missing=0");
        } else {
            sb.append(" missing=").append(r.missing.size()).append('[').append(join(r.missing)).append(']')
                    .append(" next=在 LSPosed 里给本模块勾选缺失应用，保存后强行停止该应用再打开");
        }
        return sb.toString();
    }

    /** 多行可读版本：给设置页「作用域自检」弹窗用。 */
    static String diagnose(Context ctx) {
        Report r = probe(ctx);
        StringBuilder sb = new StringBuilder();
        // v2.8.8：先给注入证据。ENTER 是 handleLoadPackage 的第一行、走 diagForce，
        // 它没命中的包，后面作用域勾得再对也白搭 —— 顺序不能反着讲。
        String hits = ConfStore.enterHits();
        sb.append("注入证据（ENTER 命中的进程）：").append(hits).append('\n');
        if (!hits.contains("OK:")) {
            sb.append("  → 四个视频 App 一个都没命中，模块代码从未进过这些进程。\n")
                    .append("    这不是定位算法的问题，是 LSPosed 没加载模块。\n");
        }
        sb.append('\n');
        sb.append("配置库：").append(r.db == null ? "未找到" : r.db).append('\n');
        if (r.error != null) {
            switch (r.error) {
                case "no-db":
                    sb.append("\n没找到 LSPosed 配置库。\n常见原因：框架不是 LSPosed（如 TaiChi / 旧版 EdXposed），或配置路径与已知版本不同。\n本模块只支持 LSPosed。");
                    break;
                case "su-denied":
                    sb.append("\n读不到配置库：root 未授权，或 su 请求被拒绝。\n请在本应用弹出的 root 授权框点「允许」，然后重开本页。");
                    break;
                case "unregistered":
                    sb.append("\nLSPosed 里没有启用本模块。\n处理：打开 LSPosed → 模块 → 勾选「抗烧屏」→ 重启手机。");
                    break;
                case "schema":
                    sb.append("\n配置库结构无法识别，无法判断作用域。\n请手动确认：LSPosed → 模块 → 抗烧屏 → 作用域 里是否勾了抖音 / 红果 / 快手 / B站。");
                    break;
                default:
                    sb.append("\n读取失败：").append(r.error);
                    break;
            }
            // 即便读不到作用域，也把设备上装的目标 App 列出来 ——
            // 用户至少能照着名单去 LSPosed 里逐个勾，不必猜自己装的是哪个渠道包。
            Set<String> installed = installedTargets(ctx);
            if (!installed.isEmpty()) {
                sb.append("\n\n设备上已安装的目标应用（照此在 LSPosed 作用域里勾选）：\n");
                for (String p : installed) {
                    sb.append("  · ").append(zh(p)).append(' ').append(p).append('\n');
                }
            }
            return sb.toString();
        }
        sb.append("模块登记：已启用（mid=").append(r.mid).append("）\n");
        sb.append("已勾选作用域：").append(r.scope.size()).append(" 个");
        if (!r.scope.isEmpty()) {
            sb.append('\n');
            for (String p : r.scope) {
                sb.append("  ✓ ").append(p).append('\n');
            }
        } else {
            sb.append("（空，只有 SystemUI 生效时通常说明从没勾过）\n");
        }
        sb.append("\n设备上找到的目标应用：").append(r.installed.size()).append(" 个\n");
        for (String p : r.installed) {
            sb.append(r.scope.contains(p) ? "  ✓ " : "  ✗ ").append(zh(p)).append(' ').append(p).append('\n');
        }
        if (r.installed.isEmpty()) {
            sb.append("  （没找到抖音/红果/快手/B站，确认是否装在本机）\n");
        }
        sb.append('\n');
        if (r.missing.isEmpty()) {
            sb.append("结论：作用域已勾齐。\n若仍无效果，请强行停止对应 App 再打开，让新进程重新加载模块。");
        } else {
            sb.append("结论：有 ").append(r.missing.size()).append(" 个应用没勾选，模块不会被注入，这些 App 内一定没效果。\n\n")
                    .append("处理：LSPosed → 模块 → 抗烧屏 → 作用域 → 勾选上面打 ✗ 的应用 → 保存 → 强行停止该 App 再打开。");
        }
        String digest = lspLogDigest();
        if (!digest.isEmpty()) {
            sb.append("\n\n—— LSPosed 自己的日志（最近几条与本模块相关）——\n").append(digest);
        }
        return sb.toString();
    }

    /**
     * 从 LSPosed 自己的日志里筛出与本模块相关的最近几行。
     *
     * <p>作用域勾齐了却仍没有 {@code INJECTED}，问题就不在模块里，而在 LSPosed 的加载环节
     * （框架版本与新系统不兼容、Zygisk 未生效、模块 dex 损坏等）。这些判断只有 LSPosed
     * 自己的日志写得清楚，所以直接把它读回来，不再让用户去猜。</p>
     */
    private static String lspLogDigest() {
        try {
            String out = ConfStore.execSuCapture(
                    "for f in /data/adb/lspd/log/modules.log /data/adb/lspd/log/verbose.log; do "
                            + "[ -f \"$f\" ] && echo \"==$f\" && tail -600 \"$f\"; done 2>/dev/null",
                    12_000L);
            if (out == null || out.trim().isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int kept = 0;
            String[] lines = out.split("\n");
            for (int i = lines.length - 1; i >= 0 && kept < 10; i--) {
                String l = lines[i].trim();
                if (l.isEmpty()) {
                    continue;
                }
                if (!(l.contains("antiburn") || l.contains("wumisc.antiburn")
                        || l.contains("Zygisk") || l.contains("zygisk")
                        || l.contains("scope") || l.contains("Scope"))) {
                    continue;
                }
                sb.insert(0, l + "\n");
                kept++;
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
