package io.github.wumisc.antiburn;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * LSPosed 作用域自检与补全。
 *
 * <p><b>为什么需要它</b>：真机日志里 {@code inject=douyin:0 drama:0 kuaishou:0
 * bilibili:0 hookdiag:0} 连续多版成立，模块自己的 {@code SCOPE targets=…} 只是
 * arrays.xml 里声明过的包名，<b>不等于</b> LSPosed 的实际作用域。LSPosed 是否把模块
 * 注入某个 App，只取决于管理器里给这个模块勾了哪些作用域。</p>
 *
 * <p>作用域没勾，DouyinHook 一行都跑不到，位置微移自然也没机会执行 ——
 * 用户看到的就是「不管怎么改都没反应」。这里用 root 直接读 LSPosed 的
 * {@code modules_config.db}，把「模块是否已登记」「作用域是否包含四个目标包」
 * 查清楚并写进可导出的日志；缺失时自动补齐（写前备份，写完复查）。</p>
 *
 * <p><b>安全边界</b>：只 INSERT 本模块自己的 scope 行，不删不改其它模块；
 * 写前把 db 复制到日志目录；补完立刻复查，复查不通过就把备份还原回去。
 * 任何一步失败都只写日志、不重试。</p>
 */
final class LspScope {

    static final String MODULE = "io.github.wumisc.antiburn";

    /** 各版本 LSPosed 的配置库位置，按存在性取第一个。 */
    private static final String[] DBS = {
            "/data/adb/lspd/config/modules_config.db",
            "/data/adb/modules/lsposed/config/modules_config.db",
            "/data/adb/modules/zygisk_lsposed/config/modules_config.db",
    };

    /** 需要注入的目标包（与 arrays.xml 的 xposed_scope 保持一致）。 */
    static final String[] TARGETS = {
            "com.ss.android.ugc.aweme",
            "com.phoenix.read",
            "com.smile.gifmaker",
            "tv.danmaku.bili",
    };

    private LspScope() {
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

    private static boolean hasSqlite() {
        String out = ConfStore.execSuCapture("command -v sqlite3 2>/dev/null || echo none");
        return out != null && !out.trim().equals("none") && !out.trim().isEmpty();
    }

    private static String q(String db, String sql) {
        String out = ConfStore.execSuCapture("sqlite3 -noheader \"" + db + "\" \"" + sql + "\" 2>/dev/null");
        return out == null ? "" : out.trim();
    }

    /**
     * 查当前状态并补齐缺失的作用域。
     *
     * @return 一行可直接落盘的诊断结论；不会抛异常
     */
    static String ensure() {
        try {
            String db = findDb();
            if (db == null) {
                return "LSPDB none（没找到 LSPosed 配置库；可能是 KernelSU 版路径不同或是非 LSPosed 框架）";
            }
            if (!hasSqlite()) {
                return "LSPDB " + db + " sqlite3=missing（设备没有 sqlite3，无法读取作用域）";
            }
            String midStr = q(db, "select mid from modules where module_pkg_name='" + MODULE + "' limit 1;");
            if (midStr.isEmpty()) {
                return "LSPDB " + db + " module=unregistered（LSPosed 还没识别到本模块，先在管理器里启用模块并重启）";
            }
            int mid;
            try {
                mid = Integer.parseInt(midStr.trim());
            } catch (Throwable t) {
                return "LSPDB " + db + " mid=\"" + midStr.trim() + "\"（解析失败）";
            }

            Set<String> have = new LinkedHashSet<>();
            String rows = q(db, "select app_pkg_name from scope where mid=" + mid + ";");
            if (!rows.isEmpty()) {
                for (String l : rows.split("\\n")) {
                    String t = l.trim();
                    if (!t.isEmpty()) {
                        have.add(t);
                    }
                }
            }

            StringBuilder missing = new StringBuilder();
            for (String p : TARGETS) {
                // 用前缀判断：作用域里可能写的是变体包
                boolean hit = false;
                for (String h : have) {
                    if (h.equals(p) || h.startsWith(p + ".")) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    if (missing.length() > 0) {
                        missing.append(',');
                    }
                    missing.append(p);
                }
            }
            if (missing.length() == 0) {
                return "LSPDB " + db + " mid=" + mid + " scope=complete";
            }

            // 写前备份：坏了还能拿回来
            String bak = ConfStore.LOG_DIR + "/modules_config.db.bak";
            ConfStore.execSu("cp -f \"" + db + "\" \"" + bak + "\" 2>/dev/null; true");

            StringBuilder added = new StringBuilder();
            StringBuilder failed = new StringBuilder();
            for (String p : missing.toString().split(",")) {
                String sql = "insert into scope(mid, app_pkg_name, user_id) "
                        + "select " + mid + ",'" + p + "',0 where not exists("
                        + "select 1 from scope where mid=" + mid + " and app_pkg_name='" + p + "');";
                ConfStore.execSu("sqlite3 \"" + db + "\" \"" + sql + "\" 2>/dev/null; true");
                String check = q(db, "select count(*) from scope where mid=" + mid
                        + " and app_pkg_name='" + p + "';");
                if ("1".equals(check.trim())) {
                    if (added.length() > 0) {
                        added.append(',');
                    }
                    added.append(p);
                } else {
                    if (failed.length() > 0) {
                        failed.append(',');
                    }
                    failed.append(p);
                }
            }

            // 复查失败的还原，避免留下半残配置
            if (failed.length() > 0) {
                ConfStore.execSu("cp -f \"" + bak + "\" \"" + db + "\" 2>/dev/null; true");
                return "LSPDB " + db + " mid=" + mid + " added=" + added
                        + " FAILED=" + failed + "（已还原备份）";
            }
            return "LSPDB " + db + " mid=" + mid + " added=" + added
                    + " next=重启目标 App 或手机后生效 backup=" + bak;
        } catch (Throwable t) {
            return "LSPDB error " + t.getClass().getSimpleName();
        }
    }
}
