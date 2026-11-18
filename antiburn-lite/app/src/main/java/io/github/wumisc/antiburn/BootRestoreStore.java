package io.github.wumisc.antiburn;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * v3.0.8 P2：开机恢复状态持久化。
 * 独立 SharedPreferences，不占 AbState 的 Bool 索引（T-49-E3 旧 Bool 0..42 零改不受影响）。
 *
 * <p>硬约束：{@link #markAttempted} 全工程唯一调用点是 BootReceiver.onReceive；
 * Application.onCreate / AbApplication.onCreate 不得调用、不得回填 last_boot_at，
 * 否则普通冷启动会把「尚未收到广播」态永久做成假。</p>
 */
public final class BootRestoreStore {
    public static final String TRIGGER_BOOT = "BOOT";
    public static final String TRIGGER_REPLACED = "REPLACED";

    /** 引擎快照值域：只陈述触发时的运行引擎，不升级为「功能已恢复」。 */
    public static final String ENGINE_LSPOSED = "lsposed";
    public static final String ENGINE_ACCESSIBILITY = "accessibility";

    /** actionKey 值域：给出可操作深链，或无可操作项。 */
    public static final String ACTION_OPEN_APP_DETAILS = "OPEN_APP_DETAILS";
    public static final String ACTION_NONE = "NONE";

    private static final String PREFS = "antiburn_boot_restore"; // 独立 prefs，不占 AbState Bool 索引
    private static final String K_LAST_BOOT_AT = "last_boot_at";
    private static final String K_ATTEMPTED = "attempted";
    private static final String K_ENGINE_SNAPSHOT = "engine_snapshot";
    private static final String K_TRIGGER = "trigger";

    private static final String HINT_TITLE = "开机自动恢复";
    private static final String HINT_BODY_PREFIX = "模块已声明开机 / 覆盖安装恢复，并已记录一次触发（时间：";
    private static final String HINT_BODY_SUFFIX = "）。能否真正免手动启动取决于系统：国产 ROM 通常需要在"
            + "「设置 → 应用 → AntiBurn → 自启动 / 后台管理」中允许；LSPosed 用户请确认模块已勾选且"
            + "目标应用作用域已选；无障碍引擎需系统设置里保持开启。点此打开应用详情。";

    private BootRestoreStore() { }

    /**
     * 全工程唯一调用点：BootReceiver.onReceive()。
     * BOOT_COMPLETED → {@link #TRIGGER_BOOT}，MY_PACKAGE_REPLACED → {@link #TRIGGER_REPLACED}。
     * apply() 异步落盘即可，Receiver 内无长任务。
     */
    public static void markAttempted(Context c, long nowMs, String engine, String trigger) {
        String safeEngine = ENGINE_LSPOSED.equals(engine) ? ENGINE_LSPOSED : ENGINE_ACCESSIBILITY;
        String safeTrigger = TRIGGER_REPLACED.equals(trigger) ? TRIGGER_REPLACED : TRIGGER_BOOT;
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(K_LAST_BOOT_AT, nowMs)
                .putBoolean(K_ATTEMPTED, true)
                .putString(K_ENGINE_SNAPSHOT, safeEngine)
                .putString(K_TRIGGER, safeTrigger)
                .apply();
    }

    /**
     * 读侧：逐字段读取并组装 {@link BootRestoreUiState}；缺键时 last_boot_at=0、attempted=false。
     * 只报「记录到什么」，不承诺「系统允许了什么」——needUserAction 恒 true。
     */
    public static BootRestoreUiState readState(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastBootAtMs = p.getLong(K_LAST_BOOT_AT, 0L);
        boolean attempted = p.getBoolean(K_ATTEMPTED, false);
        String engineRaw = p.getString(K_ENGINE_SNAPSHOT, ENGINE_LSPOSED);
        String engine = ENGINE_LSPOSED.equals(engineRaw) ? ENGINE_LSPOSED : ENGINE_ACCESSIBILITY;
        // trigger 仅内部区分来源，不进前端 data class（保持七字段契约逐字不变）
        String trigger = p.getString(K_TRIGGER, TRIGGER_BOOT);
        boolean needUserAction = true;
        String actionKey = ACTION_OPEN_APP_DETAILS; // 任何状态都给一步可操作深链
        String hintBody = buildHintBody(lastBootAtMs);
        return new BootRestoreUiState(attempted, lastBootAtMs, engine, needUserAction,
                HINT_TITLE, hintBody, actionKey, trigger);
    }

    private static String buildHintBody(long lastBootAtMs) {
        return HINT_BODY_PREFIX + lastBootAtMs + HINT_BODY_SUFFIX;
    }

    /**
     * 给前端的只读视图：七字段逐字对齐 design-backend §3.4 / design-frontend §2.2 C1–C7。
     * trigger 为内部记录字段，不参与 UI 展示契约。
     */
    public static final class BootRestoreUiState {
        public final boolean restoreAttempted;
        public final long lastBootAtMs;
        public final String engine;
        public final boolean needUserAction;
        public final String hintTitle;
        public final String hintBody;
        public final String actionKey;
        /** 内部区分 BOOT / REPLACED，仅供诊断，不进前端 data class。 */
        public final String triggerInternal;

        public BootRestoreUiState(boolean restoreAttempted, long lastBootAtMs, String engine,
                                  boolean needUserAction, String hintTitle, String hintBody,
                                  String actionKey, String triggerInternal) {
            this.restoreAttempted = restoreAttempted;
            this.lastBootAtMs = lastBootAtMs;
            this.engine = engine;
            this.needUserAction = needUserAction;
            this.hintTitle = hintTitle;
            this.hintBody = hintBody;
            this.actionKey = actionKey;
            this.triggerInternal = triggerInternal;
        }
    }
}
