import java.util.*;

/**
 * v2.0 现有逻辑的可执行镜像。
 * 每个方法都标注了 Controller.java 的对应行号；算法逐行照搬，未做"改良"，
 * 目的是让测试结果能直接指向真实代码，而不是指向一个更好的实现。
 */
public class V2Engine {

    public V statusView, navView, notifyArea;
    public boolean enableStatus = true;   // Config.java:32 默认 true
    public boolean enableNav = false;     // Config.java:33 默认 false
    public boolean enablePoem = false;    // Config.java:45 默认 false

    public final List<String> log = new ArrayList<>();

    void l(String s) { log.add(s); }

    // ---------- Controller.java:58-81 主路径 ----------
    public void onStatusBarWindowAttach(V win, boolean classExists) {
        if (!classExists) { l("StatusBarWindowView not found"); return; }
        V bar = findStatusBarView(win);
        if (bar != null) {
            statusView = bar;
            findNotifyArea(bar);
            l("status bar view found: " + bar.path());
        } else {
            statusView = win;                       // :73 整窗兜底
            l("status_bar_container not found, fallback to window");
        }
    }

    // ---------- Controller.java:133-158 兜底扫描 ----------
    public void onViewAttach(V v) {
        if (navView == null && isNavView(v)) {       // :142
            navView = v;
            l("nav view caught (View scan): " + v.path());
            return;
        }
        if (statusView == null && isStatusBarWindow(v)) {   // :149
            V bar = findStatusBarView(v);
            if (bar != null) {
                statusView = bar;
                l("status bar view caught (View scan): " + v.path() + " -> " + bar.path());
            }
        }
    }

    // ---------- Controller.java:180-212 ----------
    public V findStatusBarView(V root) {
        String[] iconIds = {"status_bar_contents", "status_bar_icons", "notification_icon_area"};
        for (String id : iconIds) {
            if (root.getIdentifier(id) != 0) {
                V v = root.findViewById(id);
                if (v != null) return v;
            }
        }
        if (root.getIdentifier("status_bar") != 0) {
            V v = root.findViewById("status_bar");
            if (v != null) return v;
        }
        return null;
    }

    // ---------- Controller.java:247-263 ----------
    public boolean isNavView(V v) {
        if (v.cls.contains("NavigationBar")) return true;
        if (v.getIdentifier("navigation_bar") != 0 && v.id != null && v.id.equals("navigation_bar")) return true;
        return false;
    }

    // ---------- Controller.java:266-273（注意：注释说"只把窗口根作为候选"，实现却是任意 StatusBar） ----------
    public boolean isStatusBarWindow(V v) {
        return v.cls.contains("StatusBar") && !v.cls.contains("NavigationBar");
    }

    // ---------- Controller.java:276-305 只扫直接子 ----------
    public void findNotifyArea(V root) {
        notifyArea = null;
        for (V c : root.children) {
            if (c.cls.contains("Notification") || c.cls.contains("notification")) {
                notifyArea = c;
                l("notify area: " + c.path());
                return;
            }
        }
        if (root.getIdentifier("notification_icon_area") != 0) {
            V v = root.findViewById("notification_icon_area");
            if (v != null) { notifyArea = v; l("notify area by id: " + v.path()); }
        }
    }

    // ---------- Controller.java:613-638 ----------
    public void applyHide() {
        if (statusView != null) {
            if (enableStatus) hideStatusKeepNotify();
            else if (statusView.alpha != 1f) restoreStatusChildren();
        }
        if (navView != null) {
            if (enableNav) {
                if (navView.visibility != 8 && navView.alpha != 0f) {
                    navView.alpha = 0f;
                    l("nav transparent");
                }
            } else if (navView.alpha != 1f) {
                navView.alpha = 1f;
                l("nav shown (switch off)");
            }
        }
    }

    // ---------- Controller.java:641-677 ----------
    void hideStatusKeepNotify() {
        if (enablePoem) {                            // :644 古诗模式整条透明
            if (statusView.visibility != 8 && statusView.alpha != 0f) {
                statusView.alpha = 0f;
                l("status transparent (poem replaces)");
            }
            return;
        }
        if (notifyArea == null) {                    // :651 未识别通知区 → 整窗透明
            if (statusView.visibility != 8 && statusView.alpha != 0f) {
                statusView.alpha = 0f;
                l("status transparent (no notify area)");
            }
            return;
        }
        if (statusView.alpha != 1f) statusView.alpha = 1f;
        for (V c : statusView.children) {            // :664 逐直接子透明
            if (c == notifyArea) {
                if (c.alpha != 1f) { c.alpha = 1f; l("notify area kept visible"); }
            } else {
                if (c.visibility != 8 && c.alpha != 0f) c.alpha = 0f;
            }
        }
    }

    // ---------- Controller.java:688-705 只恢复直接子 ----------
    void restoreStatusChildren() {
        if (statusView == null) return;
        if (statusView.alpha != 1f) statusView.alpha = 1f;
        for (V c : statusView.children) {
            if (c.alpha != 1f) c.alpha = 1f;
        }
    }
}
