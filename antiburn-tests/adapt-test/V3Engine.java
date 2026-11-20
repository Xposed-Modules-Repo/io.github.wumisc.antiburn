import java.util.*;
import java.util.regex.Pattern;

/**
 * v3.0 参考引擎（计划 §3 / §5 / §6 的可执行原型）。
 * 不是完整实现，只实现"能被测试证伪"的那部分语义：
 * 证据打分、候选链、guard 分区、重绑、失败原因枚举、watchdog 校正。
 */
public class V3Engine {

    // ---------------- profile 模型（对应 §3.2 的 JSON） ----------------
    public static class Profile {
        public String id, family, displayName, confidence = "unverified", fallback = "noop";
        public List<String> brand = new ArrayList<>();
        public Map<String, String> props = new LinkedHashMap<>();
        public List<String> classes = new ArrayList<>();
        public List<String> ids = new ArrayList<>();
        public List<String> anchors = new ArrayList<>();
        public String hide = "alpha";
        public List<String> guardClasses = new ArrayList<>();
        public int watchdogMs = 0;
        public List<String> navAnchors = new ArrayList<>();
        public List<String> navOnlyInMode = new ArrayList<>();

        public Profile(String id, String family) { this.id = id; this.family = family; }
    }

    // ---------------- 失败原因（对应 §3.3） ----------------
    public enum Reason { OK, NO_ANCHOR, NO_CLASS, GUARD_BLOCKED, RESET_BY_ROM, EXCEPTION, MODE_MISMATCH }

    public static class Result {
        public Reason reason = Reason.OK;
        public String detail = "";
        public int hiddenSubtrees = 0;
        public boolean degraded = false;
        public V anchor;
        public final List<V> applied = new ArrayList<>();
        public boolean guardIntact = true;

        public boolean ok() { return reason == Reason.OK; }
        public String toString() { return reason + (detail.isEmpty() ? "" : "(" + detail + ")"); }
    }

    // ---------------- Detector（§3.1 证据打分） ----------------
    public static class Detection {
        public Profile profile;
        public int score;
        public boolean unverified;
        public final List<String> evidence = new ArrayList<>();
        public String toString() { return profile.id + ":" + score + (unverified ? "/unverified" : ""); }
    }

    public static final int THRESHOLD = 7;

    /**
     * 证据打分。两条规则是实跑后补上的，原设计里没有：
     * ① gate：品牌或厂商版本属性必须命中一项，否则该 profile 直接出局。
     *    原因：StatusBarWindowView / status_bar_contents 这类结构在 AOSP 上同样存在，
     *    把它们当通用证据会让任何原生机都命中"第一家厂商"。
     * ② SystemUI versionName 不计分。所有机器都有，区分度为 0，原设计给 2 分只会污染结果。
     */
    public static Detection detect(List<Profile> profiles, String brand, Map<String, String> props,
                                   Set<String> presentClasses, Set<String> presentIds, String sysUiVer) {
        Detection best = null;
        for (Profile p : profiles) {
            boolean generic = p.brand.isEmpty() && p.props.isEmpty();
            boolean gate = generic;          // generic 兜底不设门禁
            int s = 0;
            List<String> ev = new ArrayList<>();

            for (String b : p.brand) {
                if (brand != null && brand.equalsIgnoreCase(b)) { s += 3; gate = true; ev.add("brand:" + b + "+3"); break; }
            }
            for (Map.Entry<String, String> e : p.props.entrySet()) {
                String v = props.get(e.getKey());
                if (v != null && Pattern.compile(e.getValue()).matcher(v).find()) {
                    s += 4; gate = true; ev.add("prop:" + e.getKey() + "+4"); break;
                }
            }
            if (!gate) continue;             // 品牌与版本属性都对不上 → 直接出局

            for (String c : p.classes) {
                if (presentClasses.contains(c)) { s += 3; ev.add("class:" + c + "+3"); break; }
            }
            for (String id : p.ids) {
                if (presentIds.contains(id)) { s += 3; ev.add("id:" + id + "+3"); break; }
            }
            if (best == null || s > best.score) {
                best = new Detection();
                best.profile = p; best.score = s;
                best.evidence.clear(); best.evidence.addAll(ev);
            }
        }
        if (best == null) return null;

        if (best.score < THRESHOLD) {
            Profile g = null;
            for (Profile p : profiles) if ("generic-aosp".equals(p.id)) g = p;
            if (g != null) {
                if (best.profile != g) { best.profile = g; best.evidence.add("score<" + THRESHOLD + " → generic-aosp"); }
                best.unverified = true;
            }
        }
        // 命中了家族但没有任何版本属性证据 → 代际未确认，必须降级标注
        if (!best.evidence.stream().anyMatch(x -> x.startsWith("prop:"))) {
            best.unverified = true;
            best.evidence.add("无版本属性 → 代际未确认");
        }
        return best;
    }

    // ---------------- Actuator ----------------
    public Profile p;
    public Detection det;
    public final List<String> log = new ArrayList<>();
    public V anchor;
    public V notifyArea;
    public int rebinds = 0;
    public int resets = 0;
    public boolean degraded = false;
    public Result last;

    void l(String s) { log.add(s); }

    /** 候选链按序命中；全不命中 → profile 的 fallback */
    V resolveAnchor(V root) {
        for (String a : p.anchors) {
            V v = root.findViewById(a);
            if (v != null) { l("anchor hit: " + a + " -> " + v.path()); return v; }
            l("anchor miss: " + a);
        }
        return null;
    }

    /** 计划 §3.2 写的是 `*Capsule*` 这种通配符，不是正则。必须转义后再拼，否则 `.*` 会被二次转成 `..*` */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (char ch : glob.toCharArray()) {
            if (ch == '*') sb.append(".*");
            else if ("\\^$.|?+()[]{}".indexOf(ch) >= 0) sb.append('\\').append(ch);
            else sb.append(ch);
        }
        return sb.append('$').toString();
    }

    List<V> scanGuard(V root) {
        List<V> out = new ArrayList<>();
        Deque<V> st = new ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            V v = st.pop();
            for (String g : p.guardClasses) {
                if (v.cls.matches(globToRegex(g))) { out.add(v); break; }
            }
            for (V c : v.children) st.push(c);
        }
        return out;
    }

    static boolean intersectsGuard(V node, List<V> guards) {
        for (V g : guards) if (node == g || node.isAncestorOf(g)) return true;
        return false;
    }

    /** 分区隐藏：与 guard 相交的容器继续下沉，只透明"干净"的子树 */
    int descend(V container, List<V> guards, List<V> applied) {
        int n = 0;
        for (V c : container.children) {
            if (c == notifyArea) continue;
            if (intersectsGuard(c, guards)) {
                if (guards.contains(c)) { l("guard keep: " + c.cls.substring(c.cls.lastIndexOf('.') + 1)); continue; }
                n += descend(c, guards, applied);     // 容器可分割 → 下沉
            } else {
                if (c.alpha != 0f) { c.alpha = 0f; applied.add(c); n++; }
            }
        }
        return n;
    }

    /**
     * 状态栏与导航栏在 SystemUI 里是**两个独立窗口**，不在同一棵 View 树里。
     * 所以 nav 必须单独传入，不能从状态栏树里找——这是实测 B-05 失败后修正的。
     */
    public Result apply(V root, V navRoot, String navMode) {
        Result r = new Result();
        last = r;
        r.degraded = this.degraded;
        try {
            V a = resolveAnchor(root);
            if (a == null) {
                r.reason = Reason.NO_ANCHOR;
                r.detail = "候选链全部未命中: " + p.anchors;
                return r;
            }
            anchor = a; r.anchor = a;
            notifyArea = root.findViewById("notification_icon_area");

            List<V> guards = scanGuard(a);
            l("guard hits=" + guards.size());

            if ("poem".equals(p.hide)) {
                a.alpha = 0f; r.applied.add(a); r.hiddenSubtrees = 1;
                return r;
            }

            r.hiddenSubtrees = descend(a, guards, r.applied);
            if (r.hiddenSubtrees == 0) {
                r.reason = Reason.GUARD_BLOCKED;
                r.detail = "状态栏容器被 guard 完全覆盖，未做任何隐藏";
            }
            // 验证护栏：guard 节点视觉必须仍可见
            for (V g : guards) if (g.effAlpha() == 0f) r.guardIntact = false;

            // 导航栏（独立窗口）
            if (p.navOnlyInMode.isEmpty() || p.navOnlyInMode.contains(navMode)) {
                V nav = null;
                if (navRoot != null) {
                    for (String na2 : p.navAnchors) { nav = navRoot.findViewById(na2); if (nav != null) break; }
                    if (nav == null) nav = navRoot;
                }
                if (nav != null && nav.alpha != 0f) { nav.alpha = 0f; r.applied.add(nav); }
            } else {
                l("nav skipped: mode=" + navMode + " not in " + p.navOnlyInMode);
            }
            return r;
        } catch (Throwable t) {
            r.reason = Reason.EXCEPTION;
            r.detail = t.toString();
            return r;
        }
    }

    V findNavByClass(V root) {
        Deque<V> st = new ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            V v = st.pop();
            if (v.cls.contains("NavigationBar")) return v;
            for (V c : v.children) st.push(c);
        }
        return null;
    }

    /** 重绑：ROM 重建容器后，重新解析锚点（对应 §5.3） */
    public Result rebind(V newRoot, V navRoot, String navMode) {
        rebinds++;
        anchor = null;
        l("rebind #" + rebinds);
        return apply(newRoot, navRoot, navMode);
    }

    /** watchdog：系统把 alpha 改回时重新施加（对应 §3.3 第 3 条） */
    public void tick(V root, V navRoot, String navMode) {
        if (last == null || last.applied.isEmpty()) return;
        boolean reset = false;
        for (V v : last.applied) {
            if (v.alpha != 0f) { reset = true; break; }
        }
        if (!reset) return;
        resets++;
        l("RESET_BY_ROM detected, reapply #" + resets);
        if (resets >= 3) { degraded = true; l("mark profile degraded"); }
        apply(root, navRoot, navMode);
        last.degraded = degraded;
    }

    // ---------------- 内置 profile 集 ----------------
    public static List<Profile> builtinProfiles() {
        List<Profile> ps = new ArrayList<>();

        Profile h = new Profile("hyperos-4", "hyperos");
        h.displayName = "HyperOS 2–4（小米/Redmi/POCO）";
        h.brand = Arrays.asList("Xiaomi", "Redmi", "POCO");
        h.props.put("ro.mi.os.version.name", "^OS[2-9]");
        h.classes = Arrays.asList("com.android.systemui.statusbar.window.StatusBarWindowView");
        h.ids = Arrays.asList("status_bar_contents");
        h.anchors = Arrays.asList("status_bar_contents", "status_bar_icons", "notification_icon_area", "status_bar");
        h.guardClasses = Arrays.asList("*Capsule*", "*FocusNotification*", "*SuperIsland*");
        h.navAnchors = Arrays.asList("navigation_bar");
        h.navOnlyInMode = Arrays.asList("three_button");
        h.confidence = "verified";
        ps.add(h);

        Profile c = new Profile("coloros-14-17", "coloros");
        c.displayName = "ColorOS 14–17（OPPO/一加/realme）";
        c.brand = Arrays.asList("OPPO", "OnePlus", "realme", "oplus");
        c.props.put("ro.build.version.oplusrom", "^1[4-7]\\.");
        c.ids = Arrays.asList("status_bar_contents", "status_bar");
        c.anchors = Arrays.asList("status_bar_contents", "notification_icon_area", "status_bar");
        c.guardClasses = Arrays.asList("*Capsule*", "*FluidCloud*", "*LiveAlert*", "*Ongoing*");
        c.navAnchors = Arrays.asList("navigation_bar");
        c.navOnlyInMode = Arrays.asList("three_button");
        c.confidence = "community";
        ps.add(c);

        Profile o = new Profile("originos-4-7", "originos");
        o.displayName = "OriginOS 4–7（vivo/iQOO）";
        o.brand = Arrays.asList("vivo", "iQOO");
        o.props.put("ro.vivo.os.version", "^[4-9]\\.");
        o.ids = Arrays.asList("status_bar_contents", "status_bar");
        o.anchors = Arrays.asList("status_bar_contents", "status_bar");
        o.guardClasses = Arrays.asList("*AtomicIsland*", "*AtomicNotification*", "*Capsule*");
        o.navAnchors = Arrays.asList("navigation_bar");
        o.navOnlyInMode = Arrays.asList("three_button");
        o.confidence = "community";
        ps.add(o);

        Profile m = new Profile("magicos-8-11", "magicos");
        m.displayName = "MagicOS 8–11（荣耀）";
        m.brand = Arrays.asList("HONOR");
        m.props.put("ro.build.version.magic", "^([89]|1[01])\\.");
        m.classes = Arrays.asList("com.android.systemui.statusbar.window.StatusBarWindowView");
        m.ids = Arrays.asList("status_bar_contents", "status_bar");
        m.anchors = Arrays.asList("status_bar_contents", "status_bar");
        m.guardClasses = Arrays.asList("*MagicCapsule*", "*Capsule*");
        m.navAnchors = Arrays.asList("navigation_bar");
        m.navOnlyInMode = Arrays.asList("three_button");
        m.confidence = "community";
        ps.add(m);

        Profile a = new Profile("generic-aosp", "aosp");
        a.displayName = "原生 / 类原生（Pixel、LineageOS、crDroid）";
        a.ids = Arrays.asList("status_bar_contents", "status_bar");
        a.anchors = Arrays.asList("status_bar_contents", "status_bar_icons", "notification_icon_area", "status_bar");
        a.guardClasses = Arrays.asList("*LiveUpdate*", "*Capsule*");
        a.navAnchors = Arrays.asList("navigation_bar");
        a.navOnlyInMode = Arrays.asList("three_button");
        a.confidence = "unverified";
        ps.add(a);
        return ps;
    }
}
