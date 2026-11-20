import java.util.*;

/**
 * 五家族 SystemUI 结构样本。
 * 结构依据：AOSP 公开布局 + 各厂商公开设计文档 + 计划 §4 的待验证假设。
 * 这里的目的不是"100% 还原某台机器"，而是复现计划 §1 断言所依赖的结构特征。
 */
public class TreeFixtures {

    /** 当前样本存在的资源 id（模拟 Resources.getIdentifier 的返回） */
    static Set<String> resIds = new HashSet<>();
    /** 当前样本的系统属性 */
    static Map<String, String> props = new LinkedHashMap<>();
    static String brand = "", manufacturer = "", systemUiVersion = "";
    static String navMode = "three_button";   // three_button / gesture

    static int resIdOrZero(String name) {
        return resIds.contains(name) ? name.hashCode() & 0x7fffffff : 0;
    }

    public static class Sample {
        public String name, family;
        public V root, navRoot;
        public boolean hasStatusBarWindowClass;   // 标准类名是否存在（决定走主路径还是兜底）
    }

    // ---------------- 1. HyperOS 4（基线，实测通过） ----------------
    public static Sample hyperos4() {
        Sample s = new Sample();
        s.name = "HyperOS 4 / 三键"; s.family = "hyperos";
        s.hasStatusBarWindowClass = true;
        resIds = new HashSet<>(Arrays.asList(
                "status_bar_window", "status_bar", "status_bar_contents",
                "status_bar_left_side", "notification_icon_area", "status_bar_right_side"));
        props.clear();
        props.put("ro.mi.os.version.name", "OS4.0");
        props.put("ro.build.version.incremental", "V816.0.1.0");
        brand = "Xiaomi"; manufacturer = "Xiaomi"; systemUiVersion = "17"; navMode = "three_button";

        V win = new V("com.android.systemui.statusbar.window.StatusBarWindowView", "status_bar_window");
        V bar = new V("com.android.systemui.statusbar.phone.PhoneStatusBarView", "status_bar");
        V contents = new V("android.widget.LinearLayout", "status_bar_contents");
        V left = new V("android.widget.FrameLayout", "status_bar_left_side").add(new V("com.android.systemui.statusbar.policy.Clock"));
        V notify = new V("com.android.systemui.statusbar.notification.icon.NotificationIconAreaView", "notification_icon_area");
        V right = new V("android.widget.LinearLayout", "status_bar_right_side")
                .add(new V("com.android.systemui.statusbar.StatusBarIconView"),
                     new V("com.android.systemui.statusbar.StatusBarIconView"));
        contents.add(left, notify, right);
        bar.add(contents);
        win.add(bar, new V("com.android.systemui.statusbar.focus.FocusNotificationView"));
        s.root = win;
        s.navRoot = new V("com.android.systemui.navigationbar.NavigationBarView", "navigation_bar")
                .add(new V("android.widget.ImageView"), new V("android.widget.ImageView"), new V("android.widget.ImageView"));
        return s;
    }

    // ---------------- 2. ColorOS 15（胶囊在左区子树内） ----------------
    public static Sample coloros15() {
        Sample s = new Sample();
        s.name = "ColorOS 15 / 三键"; s.family = "coloros";
        s.hasStatusBarWindowClass = false;   // 类名重排：无标准 StatusBarWindowView，只能靠兜底扫描
        resIds = new HashSet<>(Arrays.asList(
                "status_bar", "status_bar_contents", "status_bar_left_side",
                "notification_icon_area", "status_bar_right_side"));
        props.clear();
        props.put("ro.build.version.oplusrom", "15.0.1");
        brand = "OPPO"; manufacturer = "OPPO"; systemUiVersion = "15"; navMode = "three_button";

        V win = new V("com.oplus.systemui.statusbar.phone.OplusStatusBarWindowView");
        V bar = new V("com.oplus.systemui.statusbar.phone.OplusStatusBarView", "status_bar");
        V contents = new V("android.widget.FrameLayout", "status_bar_contents");
        V left = new V("android.widget.LinearLayout", "status_bar_left_side")
                .add(new V("com.android.systemui.statusbar.policy.Clock"),
                     new V("com.oplus.systemui.statusbar.fluid.FluidCloudCapsuleView"));  // 流体云
        V notifyWrap = new V("android.widget.FrameLayout", "notification_icon_area")
                .add(new V("com.android.systemui.statusbar.notification.icon.NotificationIconAreaView"));
        V right = new V("android.widget.LinearLayout", "status_bar_right_side")
                .add(new V("com.android.systemui.statusbar.StatusBarIconView"));
        contents.add(left, notifyWrap, right);
        bar.add(contents);
        win.add(bar);
        s.root = win;
        s.navRoot = new V("com.oplus.systemui.navigationbar.OplusNavigationBarView");
        return s;
    }

    // ---------------- 3. OriginOS 5（通知区 id 漂移 + 原子岛） ----------------
    public static Sample originos5() {
        Sample s = new Sample();
        s.name = "OriginOS 5 / 手势"; s.family = "originos";
        s.hasStatusBarWindowClass = false;
        resIds = new HashSet<>(Arrays.asList(
                "status_bar", "status_bar_contents", "status_bar_left_side", "status_bar_center"));
        props.clear();
        props.put("ro.vivo.os.version", "5.0");
        brand = "vivo"; manufacturer = "vivo"; systemUiVersion = "15"; navMode = "gesture";

        V win = new V("com.vivo.systemui.statusbar.VivoStatusBarWindow");
        V bar = new V("com.vivo.systemui.statusbar.VivoStatusBar", "status_bar");
        V contents = new V("android.widget.LinearLayout", "status_bar_contents");
        V left = new V("android.widget.FrameLayout", "status_bar_left_side")
                .add(new V("com.vivo.systemui.statusbar.clock.VivoClockView"));
        V center = new V("android.widget.FrameLayout", "status_bar_center")
                .add(new V("com.vivo.systemui.atomic.AtomicIslandView"));   // 原子岛
        // 注意：本样本没有 notification_icon_area，且通知类名不在第一层
        contents.add(left, center);
        bar.add(contents);
        win.add(bar);
        s.root = win;
        s.navRoot = new V("com.android.systemui.navigationbar.NavigationBarView", "navigation_bar");
        return s;
    }

    // ---------------- 4. MagicOS 11（信号堆叠 → 容器重建） ----------------
    public static Sample magicOs11() {
        Sample s = new Sample();
        s.name = "MagicOS 11 / 三键"; s.family = "magicos";
        s.hasStatusBarWindowClass = true;
        resIds = new HashSet<>(Arrays.asList(
                "status_bar_window", "status_bar", "status_bar_contents",
                "status_bar_left_side", "notification_icon_area"));
        props.clear();
        props.put("ro.build.version.magic", "11.0");
        brand = "HONOR"; manufacturer = "HONOR"; systemUiVersion = "15"; navMode = "three_button";

        s.root = magicTree();
        s.navRoot = new V("com.android.systemui.navigationbar.NavigationBarView", "navigation_bar");
        return s;
    }

    /** 可重建的状态栏树（模拟信号堆叠触发的 remove/add） */
    public static V magicTree() {
        V win = new V("com.android.systemui.statusbar.window.StatusBarWindowView", "status_bar_window");
        V bar = new V("com.android.systemui.statusbar.phone.PhoneStatusBarView", "status_bar");
        V contents = new V("android.widget.LinearLayout", "status_bar_contents");
        V left = new V("android.widget.LinearLayout", "status_bar_left_side")
                .add(new V("com.hihonor.systemui.statusbar.capsule.MagicCapsuleView"));  // 灵动胶囊
        V notify = new V("android.widget.LinearLayout", "notification_icon_area")
                .add(new V("com.android.systemui.statusbar.notification.icon.NotificationIconAreaView"));
        contents.add(left, notify);
        bar.add(contents);
        win.add(bar);
        return win;
    }

    // ---------------- 5. AOSP 16（基准实现） ----------------
    public static Sample aosp16() {
        Sample s = new Sample();
        s.name = "AOSP 16 / 三键"; s.family = "aosp";
        s.hasStatusBarWindowClass = true;
        resIds = new HashSet<>(Arrays.asList(
                "status_bar_window", "status_bar", "status_bar_contents",
                "status_bar_left_side", "notification_icon_area", "status_bar_right_side"));
        props.clear();
        props.put("ro.build.version.sdk", "36");
        brand = "google"; manufacturer = "Google"; systemUiVersion = "16"; navMode = "three_button";

        V win = new V("com.android.systemui.statusbar.window.StatusBarWindowView", "status_bar_window");
        V bar = new V("com.android.systemui.statusbar.phone.PhoneStatusBarView", "status_bar");
        V contents = new V("android.widget.LinearLayout", "status_bar_contents");
        V left = new V("android.widget.FrameLayout", "status_bar_left_side")
                .add(new V("com.android.systemui.statusbar.policy.Clock"));
        V notify = new V("android.widget.FrameLayout", "notification_icon_area")
                .add(new V("com.android.systemui.statusbar.notification.icon.NotificationIconContainer"),
                     new V("com.android.systemui.statusbar.live.LiveUpdateCapsuleView"));  // A16 实时活动
        V right = new V("android.widget.FrameLayout", "status_bar_right_side");
        contents.add(left, notify, right);
        bar.add(contents);
        win.add(bar);
        s.root = win;
        s.navRoot = new V("com.android.systemui.navigationbar.NavigationBarView", "navigation_bar");
        return s;
    }

    public static List<Sample> all() {
        return Arrays.asList(hyperos4(), coloros15(), originos5(), magicOs11(), aosp16());
    }
}
