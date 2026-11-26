package io.github.wumisc.antiburn;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import io.github.wumisc.antiburn.compat.XC_MethodHook;
import io.github.wumisc.antiburn.compat.XposedBridge;
import io.github.wumisc.antiburn.compat.XposedHelpers;

/**
 * v3.2.7：超级岛 / 焦点通知联动（SystemUI 侧）。
 *
 * <p>为什么从这里判：HyperIsland 与小米原生的「超级岛 / 焦点通知」都走同一套
 * extras 约定 —— 生成通知时写 {@code miui.focus.param}（渲染 JSON）、
 * {@code miui.focus.pics}（图片 Bundle），按钮在 {@code miui.focus.actions}。
 * 不看包名、不读 HyperIsland 的私有配置，只看「这条通知是不是焦点通知」，
 * 所以对 HyperIsland 版本升级、对原生岛、对其它岛的实现都成立。</p>
 *
 * <p>Hook 点选 {@code com.android.systemui.statusbar.NotificationListener#onNotificationPosted}：
 * 这是 AOSP / HyperOS / ColorOS / OneUI 都在用的系统通知入口，签名稳定；
 * 用 {@code hookAllMethods} 匹配所有重载，避免某个 ROM 多一个参数导致整条链装不上。</p>
 *
 * <p>只在 LSPosed 引擎下有意义：无障碍引擎跑在模块自己的进程里，既看不到
 * SystemUI 的通知入口，也没有能力「只透明化图标、留下岛窗口」。那种模式继续走
 * {@code BurnInMaskService} 的通知让开（撤顶部黑块 10 秒）。</p>
 */
public final class IslandWatch {

    private IslandWatch() {
    }

    /** 焦点通知的标准 key：渲染参数 / 图片。 */
    static final String K_PARAM = "miui.focus.param";
    static final String K_PICS = "miui.focus.pics";
    /** 兜底前缀：不同 ROM 版本会加 miui.focus.xxx（actions / pic_aod / rvNight…）。 */
    private static final String K_PREFIX = "miui.focus.";

    private static volatile boolean hooked = false;

    /** 由 {@link Controller#init} 在 SystemUI 进程里调用一次。 */
    static void init(ClassLoader cl) {
        if (hooked) {
            return;
        }
        hooked = true;
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.NotificationListener", cl);
            if (cls == null) {
                XposedInit.diag("ISLAND hook skip: NotificationListener not found");
                return;
            }
            XposedBridge.hookAllMethods(cls, "onNotificationPosted", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        StatusBarNotification sbn = sbnOf(param == null ? null : param.args);
                        if (sbn == null) {
                            return;
                        }
                        if (isFocus(sbn.getNotification())) {
                            Controller.onIslandDetected(sbn.getPackageName());
                        }
                    } catch (Throwable ignored) {
                        // 宿主是 SystemUI：这里抛一次就可能被 LSPosed 送进安全模式
                    }
                }
            });
            XposedInit.diag("ISLAND hook installed");
        } catch (Throwable t) {
            try {
                XposedInit.diag("ISLAND hook fail " + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 参数表里第一个 StatusBarNotification（不同版本位置可能不同）。 */
    private static StatusBarNotification sbnOf(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof StatusBarNotification) {
                return (StatusBarNotification) a;
            }
        }
        return null;
    }

    /**
     * 是否是焦点通知 / 超级岛。只看 extras，不解析内容 ——
     * HyperIsland 在生成岛通知时必写 {@code miui.focus.param}，原生岛同理。
     */
    static boolean isFocus(Notification n) {
        if (n == null) {
            return false;
        }
        Bundle e;
        try {
            e = n.extras;
        } catch (Throwable t) {
            return false;
        }
        if (e == null) {
            return false;
        }
        if (e.containsKey(K_PARAM) || e.containsKey(K_PICS)) {
            return true;
        }
        try {
            for (String k : e.keySet()) {
                if (k != null && k.startsWith(K_PREFIX)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // keySet 在极端情况下会抛 ClassCastException，忽略即可
        }
        return false;
    }
}
