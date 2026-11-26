package io.github.wumisc.antiburn;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;

import org.json.JSONObject;

/**
 * 把常驻通知同时做成「标准通知 + 小米焦点通知（超级岛）」。
 *
 * <p>v3.2.19 重写：字段结构改成与 hyperisland-kit 0.4.5 的
 * {@code HyperIslandNotification.buildJsonParam()} / {@code buildResourceBundle()} 完全一致。
 * 之前的结构（{@code param_v1}、{@code islandType/islandIcon/islandText}、
 * {@code key_island_icon}）是按旧文档反推的，真实的 {@code param_island} 只有
 * {@code islandProperty/islandPriority/islandTimeout/islandOrder/dismissIsland/
 * maxSize/needCloseAnimation/expandedTime/highlightColor} 这些键 ——
 * 键名不对，SystemUI 直接当空岛丢掉，所以一直上不去岛。</p>
 *
 * <p>真实协议要点（全部来自 kit 源码，不是猜的）：</p>
 * <ul>
 *   <li>顶层：{@code param_v2} + {@code title/content/isShowNotification}，{@code protocol=3}；</li>
 *   <li>内容模板走 {@code baseInfo}（{@code type/title/content/picFunction}），岛的文字就从这里出；</li>
 *   <li>{@code picFunction} 指向 pics Bundle 里的 key，而 key 必须是 {@code miui.focus.pic_} 前缀；</li>
 *   <li>{@code miui.focus.pics} 与 {@code miui.focus.param} 是在 {@code build()} 之后
 *       直接写进 {@code notification.extras} 的（kit 与 HyperIsland 都这么做），
 *       Builder 阶段加进去的 extras 会被覆盖，所以本类接收的是已经 build 好的 Notification；</li>
 *   <li>渠道必须是 IMPORTANCE_HIGH：HyperIsland 自己也用 HIGH（静默渠道同样 HIGH + 关声音震动）。</li>
 * </ul>
 *
 * <p>失败即降级：任何异常就地吞掉，通知仍然是普通常驻通知，岛上不显示而已。</p>
 */
public final class IslandNotify {

    /** 焦点通知渲染参数。 */
    static final String K_PARAM = "miui.focus.param";
    /** 焦点通知图片池（Bundle，key 必须 {@code miui.focus.pic_} 前缀）。 */
    static final String K_PICS = "miui.focus.pics";
    /** 我们那张图在 pics 里的 key。 */
    static final String PIC_KEY = "miui.focus.pic_antiburn";
    /** 岛的短标题（小岛上只放得下几个字）。 */
    static final String ISLAND_TEXT = "防烧屏";
    /** 业务标识：SystemUI 用它区分来源，同一 business 同 id 的通知会原地更新。 */
    static final String BUSINESS = "antiburn";

    private IslandNotify() {
    }

    /**
     * v3.2.20：先问系统「我们这个包能不能显示焦点通知」。
     *
     * <p>这正是 hyperisland-kit 的 {@code isSupported()} 第一个条件：读小米的公开 Provider
     * {@code content://miui.statusbar.notification.public} 的 {@code canShowFocus}。
     * 白名单没放行时，写多少 extras 都只会让通知变成一张「没有左侧图标」的焦点卡片 ——
     * 与其硬塞，不如退成普通通知：图标在左边、标题正文都在，跟「安全出行」那条一样。</p>
     *
     * @return true 表示系统允许本包使用焦点通知
     */
    static boolean focusAllowed(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            if (!"Xiaomi".equalsIgnoreCase(android.os.Build.MANUFACTURER)) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        try {
            Bundle arg = new Bundle();
            arg.putString("package", ctx.getPackageName());
            Bundle ret = ctx.getContentResolver().call(
                    android.net.Uri.parse("content://miui.statusbar.notification.public"),
                    "canShowFocus", null, arg);
            return ret != null && ret.getBoolean("canShowFocus", false);
        } catch (Throwable ignored) {
            // Provider 不存在 / 被拒 / 签名不符：当作不允许，走普通通知
            return false;
        }
    }

    /**
     * 给已构建好的通知挂上焦点通知 extras。
     *
     * <p>必须在 {@code builder.build()} 之后调用 —— 见类注释：Builder 的 extras 会被覆盖。</p>
     *
     * @param title   通知标题（同时用作岛标题）
     * @param content 通知正文 / 最近保护状态（同时用作岛内容）
     * @return true 表示 extras 写成功（不代表 ROM 一定渲染，只代表字段齐了）
     */
    static boolean apply(Context ctx, Notification n, String title, String content) {
        if (ctx == null || n == null) {
            return false;
        }
        try {
            // v3.3.2：左侧彩色图标改由 pics 提供（见 pics()），大岛左格用 picInfo.type=2 指过去。
            // 3.3.1 走的是 largeIcon，结果焦点卡左侧画一次、普通通知行行尾又画一次 —— 两个图标。
            n.extras.putString(K_PARAM, param(title, content));
            Bundle pics = pics(ctx);
            if (pics != null) {
                n.extras.putBundle(K_PICS, pics);
            }
            return true;
        } catch (Throwable ignored) {
            // 岛是锦上添花：任何异常都退化成普通通知，不影响常驻与点击
            return false;
        }
    }

    /**
     * 焦点通知的图片池：key 必须带 {@code miui.focus.pic_} 前缀，由 param 里的 picInfo 引用。
     *
     * <p>图标用资源引用（{@code Icon.createWithResource}），不在我们这边画位图 ——
     * 3.2.17 现场画 192px 位图的写法会构造失败、被吞掉后退成不可见图，真机「时有时无」。</p>
     */
    static Bundle pics(Context ctx) {
        try {
            android.graphics.drawable.Icon icon = GuardService.cardIcon(ctx);
            if (icon == null) {
                return null;
            }
            Bundle b = new Bundle();
            b.putParcelable(PIC_KEY, icon);
            return b;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 焦点通知 JSON —— 结构与 hyperisland-kit 的 {@code buildJsonParam()} 对齐。
     *
     */
    static String param(String title, String content) {
        String t = safe(title, "防烧屏 · 运行中");
        String c = safe(content, "像素微移守护中");
        // 岛摘要态只放得下 4 个字，正文摘要 8 个字 —— 与「安全出行」同口径
        String tag = ISLAND_TEXT.length() > 4 ? ISLAND_TEXT.substring(0, 4) : ISLAND_TEXT;
        String brief = c.length() > 8 ? c.substring(0, 8) : c;
        try {
            JSONObject base = new JSONObject()
                    .put("type", 1)
                    .put("title", t)
                    .put("content", c);

            // v3.2.21：结构照抄「安全出行」的 FocusNotifyParam.json（真机上岛的那一份），
            // 关键差异：protocol=1（不是 kit 默认的 3）、内容区走
            // bigIslandArea.imageTextInfoLeft + smallIslandArea。
            // v3.3.2：picInfo 由 type=1（用系统解析的应用图标，会时有时无）改成 type=2
            // 引用 pics 里的 key —— 图由我们自己给，不再依赖系统去解自适应图标。
            JSONObject leftPic = new JSONObject()
                    .put("type", 2)
                    .put("pic", PIC_KEY);
            JSONObject left = new JSONObject()
                    .put("type", 1)
                    .put("picInfo", leftPic)
                    .put("textInfo", new JSONObject()
                            .put("frontTitle", ISLAND_TEXT)
                            .put("title", tag)
                            .put("content", brief)
                            .put("useHighLight", false));

            JSONObject island = new JSONObject()
                    // 1 = 信息展示型（有按钮时才是 2）
                    .put("islandProperty", 1)
                    .put("bigIslandArea", new JSONObject().put("imageTextInfoLeft", left))
                    .put("smallIslandArea", new JSONObject()
                            .put("picInfo", leftPic));

            JSONObject v2 = new JSONObject()
                    .put("protocol", 1)
                    .put("business", BUSINESS)
                    // 常驻通知不抢焦点，但允许第一次弹出时亮一下岛
                    .put("enableFloat", false)
                    .put("islandFirstFloat", true)
                    .put("updatable", true)
                    // 不写 timeout：kit 里它是「通知可见时长(ms)」，
                    // 安全出行那份写 1 是因为它本来就是一次性提醒；
                    // 我们是常驻运行态，写了会让岛一闪就没。
                    .put("ticker", tag)
                    .put("baseInfo", base)
                    .put("param_island", island);

            return new JSONObject()
                    .put("param_v2", v2)
                    .put("title", t)
                    .put("content", c)
                    .put("isShowNotification", true)
                    .toString();
        } catch (Throwable t1) {
            return "{}";
        }
    }

    /** 文案里的引号 / 换行不能进 JSON 裸串，统一收敛成一行短文本。 */
    private static String safe(String s, String def) {
        if (s == null || s.length() == 0) {
            return def;
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (one.length() > 60) {
            one = one.substring(0, 60);
        }
        return one.length() == 0 ? def : one;
    }
}
