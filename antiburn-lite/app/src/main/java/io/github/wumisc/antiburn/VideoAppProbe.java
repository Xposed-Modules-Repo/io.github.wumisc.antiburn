package io.github.wumisc.antiburn;

import android.app.Activity;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 快手 / 哔哩哔哩适配计划 —— Step 1：View 树诊断探针（只读，零隐藏）。
 *
 * <p>本类的唯一职责是把目标 App 短视频页的完整 View 树导出到文件，供后续
 * 标定「哪些容器对应右侧互动列 / 底部操作栏 / 顶部工具栏 / 作者信息区」。
 * 它是适配计划的第一步，<b>刻意不做任何隐藏</b>：</p>
 *
 * <ul>
 *   <li>不调用 setAlpha / setVisibility / setX / setY / setTranslation*，不改动任何 View 属性；</li>
 *   <li>不引入任何配置键、不读开关（除共用的「诊断日志」开关）、不加界面入口；</li>
 *   <li>不复用 DouyinHook 的扫描 / 打分 / 隐藏逻辑，抖音与红果的行为完全不受影响；</li>
 *   <li>仅在「诊断日志」开启时工作，30 秒节流，单次遍历有深度与节点数双上限；</li>
 *   <li>所有 Hook 回调包 catch (Throwable)：异常绝不能冒到宿主主线程，
 *       否则可能触发 LSPosed 安全模式（v2.6.4 的教训）。</li>
 * </ul>
 *
 * <p>为什么必须先做这一步：两张截图只能说明「屏幕上有哪些常驻控件」，
 * 无法说明这些控件在 View 树里的类名、id、层级与几何关系，也无法判断
 * 目标 App 是否用 Compose / 自绘 View。凭截图直接写识别规则必然误伤或
 * 命中为 0（v2.6.6 红果「左滑查看详情」的 swipe=x0 就是先例）。
 * 真机导出 {@code antiburn_probe_*.log} 后，Step 2 才能据此定规则。</p>
 */
public final class VideoAppProbe {

    private static final String TAG = XposedInit.TAG + "PROBE";

    /** 遍历深度上限：防御 Compose 等深层嵌套导致栈溢出 */
    private static final int MAX_DEPTH = 24;
    /** 单次 dump 节点上限：防御超大页面把字符串撑爆 */
    private static final int MAX_NODES = 800;
    /** 单个文本值截断长度，避免把整段视频文案写进日志 */
    private static final int MAX_TEXT = 48;
    /** dump 节流：一次页面停留最多导出一份 */
    private static final long DUMP_INTERVAL_MS = 30_000L;

    private static volatile long lastDump = 0L;

    private VideoAppProbe() {
    }

    /**
     * 在目标 App 进程内挂载探针。
     *
     * @param lpparam 目标进程信息
     * @param label   输出文件后缀，如 {@code kuaishou} / {@code bilibili}
     */
    static void init(XC_LoadPackage.LoadPackageParam lpparam, String label) {
        final String pkg = lpparam.packageName;
        XposedInit.diagForce("PROBE ATTACH " + label + " pkg=" + pkg
                + " process=" + lpparam.processName + " (stage1: dump only, no hide)");
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                onResume(label, (Activity) param.thisObject);
                            } catch (Throwable t) {
                                // 绝不把异常抛回宿主主线程
                                Log.e(TAG, "probe onResume failed", t);
                                XposedInit.diagForce("PROBE FAIL " + label + " onResume: "
                                        + Log.getStackTraceString(t));
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hook Activity.onResume failed", t);
            XposedInit.diagForce("PROBE FAIL " + label + " attach: "
                    + Log.getStackTraceString(t));
        }
    }

    /** 节流 + 诊断开关双门控：诊断日志没开时探针零开销。 */
    private static void onResume(String label, Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Config.reload();
        } catch (Throwable ignored) {
            // 配置未就绪时按「不导出」处理，不影响宿主
        }
        if (!Config.enableLog) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDump < DUMP_INTERVAL_MS) {
            return;
        }
        lastDump = now;
        View decor = activity.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        // onResume 时 View 树往往还没完成 layout，此时 getWidth/getLocationOnScreen 全是 0，
        // 导出来对标定毫无价值。post 到下一帧（布局完成后）再遍历。
        final View root = decor;
        try {
            root.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        dump(label, root);
                    } catch (Throwable t) {
                        Log.e(TAG, "probe dump failed", t);
                        XposedInit.diagForce("PROBE FAIL " + label + " dump: "
                                + Log.getStackTraceString(t));
                    }
                }
            });
        } catch (Throwable t) {
            // post 失败时退回同步 dump：坐标可能不全，但至少不空
            try {
                dump(label, root);
            } catch (Throwable t2) {
                Log.e(TAG, "probe sync dump failed", t2);
            }
        }
    }

    /**
     * 导出整棵 View 树到独立文件，不复用抖音的 antiburn_tree.txt。
     * 文件名用 .log 后缀：App 的「导出日志」只收 antiburn*.log，
     * 这样用户不用 root 文件管理器也能把 dump 取出来回传。
     */
    private static void dump(String label, View root) {
        StringBuilder sb = new StringBuilder(16 * 1024);
        sb.append("PROBE TREE label=").append(label)
                .append(" ts=").append(System.currentTimeMillis())
                .append(" ver=2.6.7 stage=1 mode=dump-only\n");
        int[] rootLoc = new int[2];
        try {
            root.getLocationOnScreen(rootLoc);
        } catch (Throwable ignored) {
        }
        sb.append("root=").append(root.getClass().getName())
                .append(" screen=(").append(rootLoc[0]).append(',').append(rootLoc[1]).append(')')
                .append(" size=").append(root.getWidth()).append('x').append(root.getHeight())
                .append('\n');
        int[] counter = {0};
        walk(root, rootLoc[0], rootLoc[1], 0, sb, counter);
        sb.append("TOTAL nodes=").append(counter[0])
                .append(" truncated=").append(counter[0] >= MAX_NODES).append('\n');
        String text = sb.toString();
        boolean ok = XposedInit.writeSharedFile("antiburn_probe_" + label + ".log", text);
        XposedInit.diag("PROBE " + label + " dumped nodes=" + counter[0]
                + " chars=" + text.length() + " written=" + ok);
        if (!ok) {
            Log.w(TAG, "probe file write failed (no writable path): " + label);
        }
    }

    /**
     * 递归遍历，只读属性：类名、id 名/十六进制、屏幕绝对坐标、尺寸、
     * 可见性、alpha、clickable、文本、contentDescription、子节点数。
     */
    private static void walk(View v, int parentScreenX, int parentScreenY,
                             int depth, StringBuilder sb, int[] counter) {
        if (v == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return;
        }
        counter[0]++;
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        int id = View.NO_ID;
        try {
            id = v.getId();
        } catch (Throwable ignored) {
        }
        String idName = idName(v, id);

        int[] loc = new int[2];
        try {
            v.getLocationOnScreen(loc);
        } catch (Throwable ignored) {
            loc[0] = parentScreenX + v.getLeft();
            loc[1] = parentScreenY + v.getTop();
        }
        String txt = "", desc = "";
        try {
            if (v instanceof TextView) {
                txt = clamp(String.valueOf(((TextView) v).getText()));
            }
        } catch (Throwable ignored) {
        }
        try {
            CharSequence cd = v.getContentDescription();
            if (cd != null) {
                desc = clamp(cd.toString());
            }
        } catch (Throwable ignored) {
        }

        sb.append(v.getClass().getName())
                .append(" id=").append(idName)
                .append(" hex=").append(Integer.toHexString(id))
                .append(" scr=(").append(loc[0]).append(',').append(loc[1]).append(')')
                .append(" size=").append(v.getWidth()).append('x').append(v.getHeight())
                .append(" vis=").append(v.getVisibility())
                .append(" alpha=").append(v.getAlpha())
                .append(" click=").append(v.isClickable())
                .append(" ch=").append(v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : 0);
        if (!TextUtils.isEmpty(txt)) {
            sb.append(" text=").append(txt);
        }
        if (!TextUtils.isEmpty(desc)) {
            sb.append(" desc=").append(desc);
        }
        sb.append('\n');

        if (!(v instanceof ViewGroup)) {
            return;
        }
        // GONE 容器子树不绘制，跳过，省节点预算
        if (v.getVisibility() == View.GONE) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount() && counter[0] < MAX_NODES; i++) {
            try {
                walk(g.getChildAt(i), loc[0], loc[1], depth + 1, sb, counter);
            } catch (Throwable t) {
                // 单个子节点异常不影响整棵树
                sb.append("  <child-error ").append(t.getClass().getSimpleName()).append(">\n");
            }
        }
    }

    /** 尽量把数字 id 转成可读资源名，便于 Step 2 写规则；失败退回十六进制。 */
    private static String idName(View v, int id) {
        if (id == View.NO_ID || id <= 0) {
            return "-";
        }
        try {
            Resources r = v.getResources();
            if (r != null) {
                return r.getResourceEntryName(id);
            }
        } catch (Throwable ignored) {
            // 动态 id / 无资源名：走下面的兜底
        }
        return "0x" + Integer.toHexString(id);
    }

    /** 单行化 + 截断，避免换行破坏 dump 的行结构。 */
    private static String clamp(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > MAX_TEXT ? one.substring(0, MAX_TEXT) + "…" : one;
    }
}
