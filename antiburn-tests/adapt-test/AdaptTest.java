import java.util.*;

public class AdaptTest {

    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();
    static final List<String> rows = new ArrayList<>();

    static void check(String id, String desc, boolean cond, String detail) {
        if (cond) { pass++; rows.add("PASS|" + id + "|" + desc + "|" + detail); }
        else { fail++; fails.add(id + " " + desc + " :: " + detail); rows.add("FAIL|" + id + "|" + desc + "|" + detail); }
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + id + "  " + desc + "  ::  " + detail);
    }

    static Set<String> classes(V root) {
        Set<String> s = new HashSet<>();
        Deque<V> st = new ArrayDeque<>(); st.push(root);
        while (!st.isEmpty()) { V v = st.pop(); s.add(v.cls); for (V c : v.children) st.push(c); }
        return s;
    }

    static Set<String> ids(V root) {
        Set<String> s = new HashSet<>();
        Deque<V> st = new ArrayDeque<>(); st.push(root);
        while (!st.isEmpty()) { V v = st.pop(); if (v.id != null) s.add(v.id); for (V c : v.children) st.push(c); }
        return s;
    }

    static V byClass(V root, String part) {
        Deque<V> st = new ArrayDeque<>(); st.push(root);
        while (!st.isEmpty()) {
            V v = st.pop();
            if (v.cls.contains(part)) return v;
            for (V c : v.children) st.push(c);
        }
        return null;
    }

    static V byId(V root, String id) { return root.findViewById(id); }

    static void reset() {
        // 每次样本切换后重建树，避免上一轮 alpha 残留
    }

    // ================= A 组：v2.0 现有实现在五家族上的实际行为 =================
    static void groupA() {
        System.out.println("\n===== A 组：v2.0 现有实现（复现计划 §1 的断言）=====");

        // A-01 HyperOS 4 基线：应当隐藏图标、保留通知区
        {
            TreeFixtures.Sample s = TreeFixtures.hyperos4();
            V2Engine e = new V2Engine();
            s.root.attachSequence(new int[]{0});
            e.onStatusBarWindowAttach(s.root, true);
            e.applyHide();
            V left = byId(s.root, "status_bar_left_side");
            V notify = byId(s.root, "notification_icon_area");
            check("A-01", "HyperOS 4 基线：图标隐藏、通知区保留",
                    left.effAlpha() == 0f && notify.effAlpha() == 1f,
                    "left=" + left.effAlpha() + " notify=" + notify.effAlpha() + " anchor=" + (e.statusView == null ? "null" : e.statusView.id));
        }

        // A-02 ColorOS 15：流体云在左区子树内，逐子透明把它一起灭掉
        {
            TreeFixtures.Sample s = TreeFixtures.coloros15();
            V2Engine e = new V2Engine();
            s.root.attachSequence(new int[]{0});
            e.onStatusBarWindowAttach(s.root, false);
            e.onViewAttach(s.root);                 // 主路径不存在 → 兜底
            for (V c : s.root.children) e.onViewAttach(c);
            e.applyHide();
            V capsule = byClass(s.root, "FluidCloudCapsuleView");
            V clock = byClass(s.root, "Clock");
            check("A-02", "ColorOS 15：流体云胶囊被误伤（计划 §1 断言 2）",
                    capsule != null && capsule.effAlpha() == 0f,
                    "capsule.effAlpha=" + (capsule == null ? "n/a" : capsule.effAlpha())
                            + " clock=" + (clock == null ? "n/a" : clock.effAlpha())
                            + " anchor=" + (e.statusView == null ? "null" : e.statusView.id));
        }

        // A-03 OriginOS 5：通知区 id 漂移 → notifyArea 为 null → 整窗透明
        {
            TreeFixtures.Sample s = TreeFixtures.originos5();
            V2Engine e = new V2Engine();
            s.root.attachSequence(new int[]{0});
            e.onStatusBarWindowAttach(s.root, false);
            e.onViewAttach(s.root);
            for (V c : s.root.children) e.onViewAttach(c);
            e.applyHide();
            V island = byClass(s.root, "AtomicIslandView");
            V clock = byClass(s.root, "VivoClockView");
            check("A-03", "OriginOS 5：原子岛与时钟一起被灭（整窗透明）",
                    island != null && island.effAlpha() == 0f && clock.effAlpha() == 0f,
                    "island=" + (island == null ? "n/a" : island.effAlpha())
                            + " clock=" + clock.effAlpha()
                            + " notifyArea=" + (e.notifyArea == null ? "null" : e.notifyArea.id)
                            + " anchor=" + (e.statusView == null ? "null" : e.statusView.id));
        }

        // A-04 MagicOS 11：容器重建后旧引用失效，新容器不再被隐藏
        {
            TreeFixtures.Sample s = TreeFixtures.magicOs11();
            V2Engine e = new V2Engine();
            int[] cnt = {0};
            s.root.attachSequence(cnt);
            e.onStatusBarWindowAttach(s.root, true);
            e.applyHide();
            V oldContents = byId(s.root, "status_bar_contents");
            V oldLeft = byId(s.root, "status_bar_left_side");
            float oldLeftAlpha = oldLeft.effAlpha();

            // 模拟信号堆叠：bar 把旧的 contents 换掉，add 一个新的
            V newContents = new V("android.widget.LinearLayout", "status_bar_contents");
            newContents.add(new V("android.widget.LinearLayout", "status_bar_left_side")
                    .add(new V("com.hihonor.systemui.statusbar.capsule.MagicCapsuleView")));
            V bar = byId(s.root, "status_bar");
            bar.children.clear();
            bar.add(newContents);
            newContents.attachSequence(cnt);     // 新子树 attach，触发兜底扫描
            for (V v : newContents.attachSequence(cnt)) e.onViewAttach(v);

            e.applyHide();                       // 下一周期
            V newLeft = byId(newContents, "status_bar_left_side");
            check("A-04", "MagicOS 11：图标容器重建后不再生效（无重绑）",
                    oldLeftAlpha == 0f && newLeft.effAlpha() == 1f,
                    "旧子树=" + oldLeftAlpha + " 新子树=" + newLeft.effAlpha()
                            + " statusView仍指向=" + (e.statusView == null ? "null" : e.statusView.path()));
        }

        // A-05 手势导航：开关打开后 handle 被透明，无 onlyInMode 判断
        {
            TreeFixtures.Sample s = TreeFixtures.originos5();
            V2Engine e = new V2Engine();
            e.enableNav = true;
            s.root.attachSequence(new int[]{0});
            e.onViewAttach(s.navRoot);
            e.applyHide();
            check("A-05", "手势模式：导航 handle 被整体透明（无模式判断）",
                    "gesture".equals(TreeFixtures.navMode) && s.navRoot.effAlpha() == 0f,
                    "navMode=" + TreeFixtures.navMode + " handle.effAlpha=" + s.navRoot.effAlpha());
        }

        // A-06 isStatusBarWindow 的注释与实现矛盾
        {
            V2Engine e = new V2Engine();
            V leaf = new V("com.android.systemui.statusbar.StatusBarIconView");
            V win = new V("com.android.systemui.statusbar.window.StatusBarWindowView");
            check("A-06", "isStatusBarWindow 对叶子节点也返回 true（注释声称只认窗口根）",
                    e.isStatusBarWindow(leaf) && e.isStatusBarWindow(win),
                    "StatusBarIconView -> " + e.isStatusBarWindow(leaf)
                            + ", StatusBarWindowView -> " + e.isStatusBarWindow(win));
        }

        // A-07 古诗模式：整窗透明，通知区一并消失
        {
            TreeFixtures.Sample s = TreeFixtures.hyperos4();
            V2Engine e = new V2Engine();
            e.enablePoem = true;
            s.root.attachSequence(new int[]{0});
            e.onStatusBarWindowAttach(s.root, true);
            e.applyHide();
            V notify = byId(s.root, "notification_icon_area");
            check("A-07", "古诗模式整窗透明，通知区同时不可见（设计取舍）",
                    notify.effAlpha() == 0f,
                    "notify.effAlpha=" + notify.effAlpha() + "（有通知也看不见）");
        }
    }

    // ================= B 组：v3 参考引擎 =================
    static void groupB() {
        System.out.println("\n===== B 组：v3 参考引擎（计划 §3/§5/§6）=====");
        List<V3Engine.Profile> ps = V3Engine.builtinProfiles();

        // B-01 ColorOS：guard 下沉，胶囊保留、时钟隐藏
        {
            TreeFixtures.Sample s = TreeFixtures.coloros15();
            V3Engine e = new V3Engine();
            e.p = pick(ps, "coloros-14-17");
            s.root.attachSequence(new int[]{0});
            V3Engine.Result r = e.apply(s.root, s.navRoot, TreeFixtures.navMode);
            V capsule = byClass(s.root, "FluidCloudCapsuleView");
            V clock = byClass(s.root, "Clock");
            check("B-01", "ColorOS 15：胶囊保留、时钟隐藏（guard 下沉）",
                    r.ok() && r.guardIntact && capsule.effAlpha() == 1f && clock.effAlpha() == 0f,
                    r + " capsule=" + capsule.effAlpha() + " clock=" + clock.effAlpha() + " hidden=" + r.hiddenSubtrees);
        }

        // B-02 OriginOS：原子岛保留
        {
            TreeFixtures.Sample s = TreeFixtures.originos5();
            V3Engine e = new V3Engine();
            e.p = pick(ps, "originos-4-7");
            s.root.attachSequence(new int[]{0});
            V3Engine.Result r = e.apply(s.root, s.navRoot, TreeFixtures.navMode);
            V island = byClass(s.root, "AtomicIslandView");
            V clock = byClass(s.root, "VivoClockView");
            check("B-02", "OriginOS 5：原子岛保留、时钟隐藏",
                    r.ok() && r.guardIntact && island.effAlpha() == 1f && clock.effAlpha() == 0f,
                    r + " island=" + island.effAlpha() + " clock=" + clock.effAlpha());
        }

        // B-03 MagicOS：容器重建后重绑成功
        {
            TreeFixtures.Sample s = TreeFixtures.magicOs11();
            V3Engine e = new V3Engine();
            e.p = pick(ps, "magicos-8-11");
            s.root.attachSequence(new int[]{0});
            e.apply(s.root, s.navRoot, TreeFixtures.navMode);

            V newContents = new V("android.widget.LinearLayout", "status_bar_contents");
            newContents.add(new V("android.widget.LinearLayout", "status_bar_left_side")
                    .add(new V("com.android.systemui.statusbar.policy.Clock"),
                         new V("com.hihonor.systemui.statusbar.capsule.MagicCapsuleView")));
            V bar = byId(s.root, "status_bar");
            bar.children.clear();
            bar.add(newContents);
            V3Engine.Result r = e.rebind(s.root, s.navRoot, TreeFixtures.navMode);
            V capsule = byClass(s.root, "MagicCapsuleView");
            check("B-03", "MagicOS 11：容器重建后重绑成功，胶囊仍受保护",
                    r.ok() && r.guardIntact && capsule.effAlpha() == 1f && e.rebinds == 1 && e.anchor == newContents,
                    r + " capsule=" + capsule.effAlpha() + " rebinds=" + e.rebinds
                            + " anchor=" + (e.anchor == null ? "null" : e.anchor.id));
        }

        // B-04 手势模式：nav 被 onlyInMode 挡掉
        {
            TreeFixtures.Sample s = TreeFixtures.originos5();
            V3Engine e = new V3Engine();
            e.p = pick(ps, "originos-4-7");
            s.root.attachSequence(new int[]{0});
            V3Engine.Result r = e.apply(s.root, s.navRoot, "gesture");
            check("B-04", "手势模式：导航 handle 不被透明（onlyInMode 生效）",
                    s.navRoot.effAlpha() == 1f && r.ok(),
                    "handle.effAlpha=" + s.navRoot.effAlpha() + " mode=gesture");
        }

        // B-05 三键模式：nav 被透明
        {
            TreeFixtures.Sample s = TreeFixtures.coloros15();
            V3Engine e = new V3Engine();
            e.p = pick(ps, "coloros-14-17");
            s.root.attachSequence(new int[]{0});
            e.apply(s.root, s.navRoot, "three_button");
            check("B-05", "三键模式：导航栏正常透明",
                    s.navRoot.effAlpha() == 0f,
                    "nav.effAlpha=" + s.navRoot.effAlpha());
        }

        // B-06 全不命中 → NO_ANCHOR，而不是静默 not found
        {
            TreeFixtures.Sample s = TreeFixtures.aosp16();
            s.root.children.clear();
            s.root.add(new V("android.widget.FrameLayout", "unknown_root"));
            V3Engine e = new V3Engine();
            e.p = pick(ps, "generic-aosp");
            V3Engine.Result r = e.apply(s.root, s.navRoot, "three_button");
            check("B-06", "锚点全不命中 → NO_ANCHOR（可解释失败）",
                    r.reason == V3Engine.Reason.NO_ANCHOR,
                    r.toString());
        }

        // B-07 不可分割容器 → GUARD_BLOCKED
        {
            V3Engine e = new V3Engine();
            V3Engine.Profile p = pick(ps, "generic-aosp");
            e.p = p;
            V root = new V("android.widget.FrameLayout", "status_bar")
                    .add(new V("com.x.Capsule"));
            V contents = new V("android.widget.FrameLayout", "status_bar_contents")
                    .add(new V("com.x.Capsule"));   // 唯一子节点就是胶囊
            root.add(contents);
            V3Engine.Result r = e.apply(root, null, "three_button");
            check("B-07", "容器唯一子节点即胶囊 → GUARD_BLOCKED，整条不隐藏",
                    r.reason == V3Engine.Reason.GUARD_BLOCKED && r.hiddenSubtrees == 0
                            && byClass(root, "Capsule").effAlpha() == 1f,
                    r.toString() + " capsule=" + byClass(root, "Capsule").effAlpha());
        }

        // B-08 watchdog：系统把 alpha 改回 → 重新施加并计数
        {
            TreeFixtures.Sample s = TreeFixtures.hyperos4();
            V3Engine e = new V3Engine();
            e.p = pick(ps, "hyperos-4");
            s.root.attachSequence(new int[]{0});
            e.apply(s.root, s.navRoot, "three_button");
            V left = byId(s.root, "status_bar_left_side");
            left.alpha = 1f;                       // 模拟 ROM 重置
            e.tick(s.root, s.navRoot, "three_button");
            check("B-08", "alpha 被系统重置 → 检测到 RESET_BY_ROM 并恢复",
                    e.resets == 1 && left.alpha == 0f,
                    "resets=" + e.resets + " left.alpha=" + left.alpha);
        }

        // B-09 连续重置 3 次 → degraded
        {
            TreeFixtures.Sample s = TreeFixtures.hyperos4();
            V3Engine e = new V3Engine();
            e.p = pick(ps, "hyperos-4");
            s.root.attachSequence(new int[]{0});
            e.apply(s.root, s.navRoot, "three_button");
            V left = byId(s.root, "status_bar_left_side");
            for (int i = 0; i < 3; i++) { left.alpha = 1f; e.tick(s.root, s.navRoot, "three_button"); }
            check("B-09", "连续重置 3 次 → 标记 degraded",
                    e.resets >= 3 && e.last != null && e.last.degraded,
                    "resets=" + e.resets + " degraded=" + (e.last != null && e.last.degraded));
        }

        // B-10 AOSP 16 实时活动胶囊不被误伤
        {
            TreeFixtures.Sample s = TreeFixtures.aosp16();
            V3Engine e = new V3Engine();
            e.p = pick(ps, "generic-aosp");
            s.root.attachSequence(new int[]{0});
            V3Engine.Result r = e.apply(s.root, s.navRoot, "three_button");
            V live = byClass(s.root, "LiveUpdateCapsuleView");
            check("B-10", "AOSP 16 实时活动胶囊不被误伤",
                    r.ok() && r.guardIntact && live.effAlpha() == 1f,
                    r + " live=" + live.effAlpha() + " hidden=" + r.hiddenSubtrees);
        }
    }

    static V3Engine.Profile pick(List<V3Engine.Profile> ps, String id) {
        for (V3Engine.Profile p : ps) if (p.id.equals(id)) return p;
        throw new IllegalArgumentException(id);
    }

    // ================= C 组：Detector 打分 =================
    static void groupC() {
        System.out.println("\n===== C 组：Detector 证据打分（计划 §3.1）=====");
        List<V3Engine.Profile> ps = V3Engine.builtinProfiles();

        String[][] samples = {
                {"hyperos4", "hyperos-4"}, {"coloros15", "coloros-14-17"},
                {"originos5", "originos-4-7"}, {"magicOs11", "magicos-8-11"},
                {"aosp16", "generic-aosp"}};
        for (String[] pair : samples) {
            TreeFixtures.Sample s = sampleByName(pair[0]);
            V3Engine.Detection d = V3Engine.detect(ps, TreeFixtures.brand, TreeFixtures.props,
                    classes(s.root), ids(s.root), TreeFixtures.systemUiVersion);
            check("C-" + pair[0], "判定 " + s.name + " → " + pair[1],
                    d.profile.id.equals(pair[1]),
                    "得到 " + d + " 证据=" + d.evidence);
        }

        // C-06 套壳机：BRAND=Xiaomi，但 oplusrom 存在
        {
            TreeFixtures.Sample s = TreeFixtures.coloros15();
            Map<String, String> p = new LinkedHashMap<>();
            p.put("ro.build.version.oplusrom", "15.0.1");
            V3Engine.Detection d = V3Engine.detect(ps, "Xiaomi", p, classes(s.root), ids(s.root), "15");
            check("C-06", "套壳机（BRAND=Xiaomi + oplusrom）以属性为准",
                    d.profile.id.equals("coloros-14-17"),
                    "得到 " + d + " 证据=" + d.evidence);
        }

        // C-07 品牌不在任何 profile（如魅族）→ generic + unverified，不硬猜
        {
            TreeFixtures.Sample s = TreeFixtures.aosp16();
            V3Engine.Detection d = V3Engine.detect(ps, "Meizu", new LinkedHashMap<>(),
                    classes(s.root), ids(s.root), "16");
            check("C-07", "品牌不在适配集（Meizu）→ generic-aosp 且标 unverified",
                    d.profile.id.equals("generic-aosp") && d.unverified,
                    "得到 " + d + " 证据=" + d.evidence);
        }

        // C-10 品牌命中但缺版本属性 → 命中家族但代际未确认，标 unverified
        {
            TreeFixtures.Sample s = TreeFixtures.hyperos4();
            V3Engine.Detection d = V3Engine.detect(ps, "Xiaomi", new LinkedHashMap<>(),
                    classes(s.root), ids(s.root), "");
            check("C-10", "品牌命中但无版本属性 → 命中家族但标 unverified（不假装已验证）",
                    d.profile.id.equals("hyperos-4") && d.unverified,
                    "得到 " + d + " 证据=" + d.evidence);
        }

        // C-08 证据链可解释
        {
            TreeFixtures.Sample s = TreeFixtures.coloros15();
            V3Engine.Detection d = V3Engine.detect(ps, TreeFixtures.brand, TreeFixtures.props,
                    classes(s.root), ids(s.root), TreeFixtures.systemUiVersion);
            boolean explain = d.evidence.stream().anyMatch(x -> x.startsWith("prop:"))
                    && d.evidence.stream().anyMatch(x -> x.startsWith("brand:"));
            check("C-08", "判定结果附带证据链（品牌 + 版本属性）",
                    explain, "证据=" + d.evidence);
        }

        // C-09 阈值边界：7 分命中、6 分落 generic
        {
            TreeFixtures.Sample s = TreeFixtures.hyperos4();
            Map<String, String> p = new LinkedHashMap<>();
            p.put("ro.mi.os.version.name", "OS4.0");
            // brand + prop = 3 + 4 = 7，但 sysuiVer 也 +2 → 9；去掉类名与 id 影响需单独构造
            V3Engine.Detection d7 = V3Engine.detect(ps, "Xiaomi", p, new HashSet<>(), new HashSet<>(), "");
            V3Engine.Detection d6 = V3Engine.detect(ps, "Xiaomi", new LinkedHashMap<>(),
                    new HashSet<>(), new HashSet<>(Arrays.asList("status_bar_contents")), "");
            check("C-09", "阈值 7：7 分命中 HyperOS，6 分落 generic",
                    d7.profile.id.equals("hyperos-4") && d7.score == 7 && d6.unverified,
                    "7分样本=" + d7 + " 6分样本=" + d6);
        }
    }

    static TreeFixtures.Sample sampleByName(String n) {
        switch (n) {
            case "hyperos4": return TreeFixtures.hyperos4();
            case "coloros15": return TreeFixtures.coloros15();
            case "originos5": return TreeFixtures.originos5();
            case "magicOs11": return TreeFixtures.magicOs11();
            default: return TreeFixtures.aosp16();
        }
    }

    public static void main(String[] args) {
        groupA();
        groupB();
        groupC();
        System.out.println("\n================ SUMMARY ================");
        System.out.println("PASS=" + pass + "  FAIL=" + fail + "  TOTAL=" + (pass + fail));
        for (String f : fails) System.out.println("  FAILED: " + f);
    }
}
