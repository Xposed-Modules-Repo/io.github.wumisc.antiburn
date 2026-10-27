package io.github.wumisc.antiburn;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 抖音（com.ss.android.ugc.aweme）防烧屏：区域化定时隐藏，把控制权交给用户。
 *
 * 可隐藏区域（各自独立开关）：
 *  - 右侧按钮组（点赞/评论/收藏/分享等，可能多个容器）→ Config.enableDouyinLike
 *  - 顶部导航栏（直播/团购/关注/商城/同城/推荐）     → Config.enableDouyinTop
 *  - 底部导航栏（首页/朋友/消息/我）                → Config.enableDouyinBottom
 *  - 右上角操作按钮（搜索/分享/更多）              → Config.enableDouyinRt
 *  - 左上角菜单按钮（三横线）                    → Config.enableDouyinLt
 *
 * 主动扫描：界面布局完成后从根视图递归识别各区域容器，按开关周期透明化。
 *
 * 关键实测（真机日志）：
 *  - 抖音底部导航容器高度为 0（size=1280x0），且不同页面复用同一 id、容器位置高度反复变化，
 *    无法靠"选中单个容器"隐藏。方案：收集屏幕最底部（距根底部一定间隔内）的所有横向横条，
 *    对每个横条整棵子树递归透明，无论 tab 嵌套多深都能盖住。
 *  - 抖音进程 displayMetrics 与真实渲染坐标系不一致，贴底/贴边判定不能依赖 displayMetrics，
 *    统一用根视图（decorView）实际尺寸。
 */
public final class DouyinHook {

    private static final String TAG = XposedInit.TAG + "DY";

    private static final List<View> rightList = new ArrayList<>();  // 右侧竖排按钮组（可能多个）
    private static final List<View> bottomList = new ArrayList<>(); // 底部贴底横条（可能多个，递归隐藏）
    private static volatile View topBar;       // 顶部导航
    private static volatile View rightTop;     // 右上角操作按钮
    private static volatile View leftTop;      // 左上角菜单按钮
    private static volatile View currentDecor; // 当前根视图（用于周期强制重扫）
    private static boolean started = false;
    private static boolean hidden = false;
    private static boolean isDrama = false; // 红果短剧（com.phoenix.read 系）进程：用红果独立开关

    private static long lastScan = 0L;

    /** 全树递归扫描深度上限：防御极端深树（Compose 复杂页等）导致的栈溢出 */
    private static final int MAX_DEPTH = 32;

    // ---------- 布局监听（单例 + 随页面迁移，防止旧 Activity 泄漏）----------
    private static volatile View registeredDecor;                 // 当前已注册布局监听的根视图
    private static ViewTreeObserver.OnGlobalLayoutListener glListener; // 全局唯一监听

    // ---------- 悬浮球：点击切换隐藏控件的显示/消失 ----------
    private static volatile boolean forceShow = false; // true=强制显示所有隐藏控件
    private static volatile View ballView;             // 悬浮球（挂在当前页面 decorView 上）
    private static volatile int ballLeft = -1;         // 拖动后的悬浮球位置（-1=未自定义，用默认右缘/上部1/3）
    private static volatile int ballTop = -1;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private DouyinHook() {
    }

    static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            isDrama = lpparam.packageName != null && lpparam.packageName.startsWith("com.phoenix.read");
            // 用 onResume（而非 onCreate）绑定当前页：新建/返回切换都能把布局监听迁移到当前前台页，
            // 单例监听 + 主动移除旧监听，避免每个 Activity 累积 addOnGlobalLayoutListener 造成 Activity 泄漏。
            XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Activity a = (Activity) param.thisObject;
                                View decor = a.getWindow().getDecorView();
                                // 让 currentDecor 始终指向当前前台页面（小球与重扫都用它）
                                currentDecor = decor;
                                // 悬浮球开关（抖音/红果）：开则创建，关则移除
                                Config.reload();
                                if (Config.enableBall) {
                                    ensureBall();
                                } else {
                                    removeBall();
                                }
                                // 布局监听迁移到当前页：切换 Activity 时移除旧监听，释放旧页面引用
                                rebindGlobalListener(decor);
                                // 进入页面立即扫一次，压住当前页固定 UI
                                if (decor instanceof ViewGroup) {
                                    rescan((ViewGroup) decor);
                                }
                                // 若处于隐藏窗口，进入即压住新容器，避免短暂露出
                                if (hidden) {
                                    hideEnabled();
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "hook onResume failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hook Activity.onResume failed", t);
        }
    }

    /**
     * 把全局唯一布局监听绑定到当前前台页 decor：
     *  - 监听始终只有一个（不再随 Activity 累积，杜绝旧页面 Activity 泄漏）；
     *  - 切换页面时移除旧监听、绑定新监听，旧 decor/Activity 无引用后即可被回收。
     */
    private static void rebindGlobalListener(View decor) {
        if (decor == null) {
            return;
        }
        if (decor == registeredDecor && glListener != null) {
            return; // 已绑定当前页，无需重复
        }
        // 移除旧监听，释放旧页面
        if (registeredDecor != null && glListener != null) {
            try {
                registeredDecor.getViewTreeObserver().removeOnGlobalLayoutListener(glListener);
            } catch (Throwable ignored) {
            }
        }
        glListener = () -> {
            try {
                View cur = currentDecor;
                long now = System.currentTimeMillis();
                if (now - lastScan < 800) {
                    return; // 节流：布局变化虽事件驱动，仍避免高频全树遍历
                }
                lastScan = now;
                if (cur instanceof ViewGroup) {
                    rescan((ViewGroup) cur);
                }
                // 悬浮球保活：抖音页面内部会动态增删 decor 子视图（翻页/刷新），
                // 球被移除后随布局变化立即重建，避免悬浮球自动消失
                if (Config.enableBall) {
                    ensureBall();
                } else if (ballView != null) {
                    removeBall();
                }
                // 布局变化（翻页/界面重建）后，若处于隐藏窗口，
                // 立即压住新容器，避免短暂露出（事件驱动即时，不耗周期）
                if (hidden) {
                    hideEnabled();
                }
            } catch (Throwable ex) {
                Log.e(TAG, "scan error", ex);
            }
        };
        try {
            decor.getViewTreeObserver().addOnGlobalLayoutListener(glListener);
        } catch (Throwable ignored) {
        }
        registeredDecor = decor;
    }

    // ---------- 直播间诊断 ----------

    private static long lastTreeDump = 0;

    // 直播间专用三区容器（结构不同于普通视频页，独立收集与隐藏）
    private static final List<View> liveTopList = new ArrayList<>();
    private static final List<View> liveBottomList = new ArrayList<>();
    private static final List<View> liveRightList = new ArrayList<>();

    /**
     * 直播页与普通视频页结构完全不同。检测到直播特征时，
     * 把当前完整 View 树 dump 到文件，供精确适配（避免盲藏误伤弹幕/操作区）。
     */
    private static void maybeDumpLiveTree(View root) {
        try {
            // 直播树 dump 合并到"写诊断日志"开关：开启才写，关闭不写（省 IO）
            if (!Config.enableLog) {
                return;
            }
            boolean live = isLive();
            if (!live) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastTreeDump < 30000) {
                return; // 30s 节流，一次直播停留只 dump 一份
            }
            lastTreeDump = now;
            StringBuilder sb = new StringBuilder();
            sb.append("LIVE TREE ts=").append(now).append('\n');
            dumpNode(root, sb, 0, 0);
            String text = sb.toString();
            writeTreeFile(text);
            XposedInit.diag("LIVE TREE dumped " + text.length() + " chars");
        } catch (Throwable t) {
            Log.e(TAG, "dump live tree error", t);
        }
    }

    /** 检测当前是否直播间。仅抖音进程存在直播；红果短剧无直播页，恒返回 false，
     *  否则兜底收集的贴底横条会让红果/抖音普通视频页被误判为直播，
     *  导致关闭"直播间隐藏"后整个进程都不再隐藏。 */
    private static boolean isLive() {
        if (isDrama) {
            return false;
        }
        if (rightTop != null && "RoundCornerFrameLayout".equals(rightTop.getClass().getSimpleName())) {
            return true;
        }
        // 抖音直播页：底部是输入条/固定 UI 容器而非首页导航。只有明确没有
        // MainBottomTabContainer 且已收集到贴底横条才视为直播（普通视频页同此特征）。
        if (bottomList != null && !bottomList.isEmpty()) {
            for (View b : bottomList) {
                if ("MainBottomTabContainer".equals(b.getClass().getSimpleName())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static void dumpNode(View v, StringBuilder sb, int depth, int count) {
        if (v == null || depth > 18 || count > 1000) {
            return;
        }
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        int id = 0;
        try {
            id = v.getId();
        } catch (Throwable ignored) {
        }
        sb.append(v.getClass().getSimpleName()).append(" id=").append(id)
                .append(" pos=(").append(v.getLeft()).append(',').append(v.getTop()).append(')')
                .append(" size=").append(v.getWidth()).append('x').append(v.getHeight())
                .append(" vis=").append(v.getVisibility()).append(" alpha=").append(v.getAlpha())
                .append(" ch=").append(v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : 0)
                .append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (v.getVisibility() == View.GONE) {
                return; // GONE 容器子树不绘制，跳过省节点数（避免占位容器挤占 1000 上限）
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpNode(g.getChildAt(i), sb, depth + 1, count + 1);
            }
        }
    }

    private static void writeTreeFile(String text) {
        String[] paths = {"/sdcard/Download/antiburn_tree.txt", "/sdcard/antiburn_tree.txt"};
        for (String p : paths) {
            try {
                File f = new File(p);
                FileOutputStream fo = new FileOutputStream(f, false);
                fo.write(text.getBytes());
                fo.close();
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- 扫描 ----------

    /** 全量重新扫描当前界面，更新各区域容器引用 */
    private static void rescan(ViewGroup root) {
        try {
            List<View> rights = collectRight(root, 4);
            // 补充兜底：贴右缘竖列（不限 childCount，防止个别视频把点赞列分组成小容器而漏藏）
            for (View e : collectRightEdge(root, 4)) {
                if (!rights.contains(e)) {
                    rights.add(e);
                }
            }
            View t = scan(root, DouyinHook::scoreTop);
            List<View> bottoms = collectBottom(root);
            View rt = scan(root, DouyinHook::scoreRightTop);
            View lt = scan(root, DouyinHook::scoreLeftTop);
            if (!rights.isEmpty()) {
                boolean changed = rights.size() != rightList.size();
                if (!changed) {
                    for (int i = 0; i < rights.size(); i++) {
                        if (rightList.get(i) != rights.get(i)) {
                            changed = true;
                            break;
                        }
                    }
                }
                if (changed) {
                    rightList.clear();
                    rightList.addAll(rights);
                    Log.i(TAG, "RIGHT x" + rightList.size() + " captured");
                    for (View v : rightList) {
                        Log.i(TAG, "  - " + desc(v));
                    }
                }
            }
            if (t != null && t != topBar) {
                topBar = t;
                Log.i(TAG, "TOP CAPTURED: " + desc(t));
            }
            if (!bottoms.isEmpty()) {
                boolean changed = bottoms.size() != bottomList.size();
                if (!changed) {
                    for (int i = 0; i < bottoms.size(); i++) {
                        if (bottomList.get(i) != bottoms.get(i)) {
                            changed = true;
                            break;
                        }
                    }
                }
                if (changed) {
                    bottomList.clear();
                    bottomList.addAll(bottoms);
                    Log.i(TAG, "BOTTOM x" + bottomList.size() + " captured");
                    for (View v : bottomList) {
                        Log.i(TAG, "  - " + desc(v));
                    }
                }
            }
            if (rt != null && rt != rightTop) {
                rightTop = rt;
                Log.i(TAG, "RIGHTTOP CAPTURED: " + desc(rt));
            }
            if (lt != null && lt != leftTop) {
                leftTop = lt;
                Log.i(TAG, "LEFTTOP CAPTURED: " + desc(lt));
            }
            boolean any = !rightList.isEmpty() || topBar != null
                    || !bottomList.isEmpty() || rightTop != null || leftTop != null;
            if (any && !started) {
                startCycle();
            }
            // 确定性文件诊断：每次扫描把各区域识别结果写文件，供真机排查
            StringBuilder sb = new StringBuilder();
            sb.append("SCAN top=").append(topBar != null ? desc(topBar) : "NONE")
                    .append(" | bottom=x").append(bottomList.size());
            for (View v : bottomList) {
                sb.append(" [").append(desc(v)).append(']');
            }
            sb.append(" | right=x").append(rightList.size());
            for (View v : rightList) {
                sb.append(" [").append(desc(v)).append(']');
            }
            sb.append(" | righttop=").append(rightTop != null ? desc(rightTop) : "NONE")
                    .append(" | lefttop=").append(leftTop != null ? desc(leftTop) : "NONE");
            XposedInit.diag(sb.toString());
            // 直播间专用三区收集：顶栏/底部大区/右下角按钮组（独立于普通页五区）
            liveTopList.clear();
            liveBottomList.clear();
            liveRightList.clear();
            if (isLive()) {
                collectLiveTopNode(root, liveTopList, 0);
                collectLiveBottomNode(root, liveBottomList, 0);
                collectLiveRightNode(root, liveRightList, 0);
                if (!liveTopList.isEmpty() || !liveBottomList.isEmpty() || !liveRightList.isEmpty()) {
                    XposedInit.diag("LIVE top=x" + liveTopList.size() + " bottom=x"
                            + liveBottomList.size() + " right=x" + liveRightList.size());
                }
            }
            maybeDumpLiveTree(root);
        } catch (Throwable ex) {
            Log.e(TAG, "rescan error", ex);
            XposedInit.diag("RESCAN ERROR " + ex);
        }
    }

    private interface Scorer {
        int score(ViewGroup g);
    }

    /** 选得分最优的一个 */
    private static View scan(ViewGroup root, Scorer sc) {
        View[] holder = new View[1];
        int[] best = new int[]{-1};
        scanNode(root, holder, best, sc, 0);
        return holder[0];
    }

    private static void scanNode(View v, View[] holder, int[] best, Scorer sc, int depth) {
        if (depth > MAX_DEPTH) {
            return; // 深度超限：放弃该子树（防御栈溢出）
        }
        if (!(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        int s = sc.score(g);
        if (s > best[0]) {
            best[0] = s;
            holder[0] = g;
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            scanNode(g.getChildAt(i), holder, best, sc, depth + 1);
        }
    }

    /** 收集右侧竖排按钮组（多个），按得分取前 limit 个 */
    private static List<View> collectRight(ViewGroup root, int limit) {
        List<View> views = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        collectRightNode(root, views, scores, 0);
        // 插入排序（降序）
        for (int i = 1; i < views.size(); i++) {
            View v = views.get(i);
            int sc = scores.get(i);
            int j = i - 1;
            while (j >= 0 && scores.get(j) < sc) {
                views.set(j + 1, views.get(j));
                scores.set(j + 1, scores.get(j));
                j--;
            }
            views.set(j + 1, v);
            scores.set(j + 1, sc);
        }
        if (views.size() > limit) {
            return new ArrayList<>(views.subList(0, limit));
        }
        return views;
    }

    private static void collectRightNode(View v, List<View> views, List<Integer> scores, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (!(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        int s = scoreRight(g);
        if (s > 0) {
            views.add(g);
            scores.add(s);
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            collectRightNode(g.getChildAt(i), views, scores, depth + 1);
        }
    }

    /** 收集屏幕底部的固定 UI 横条（底部导航及其周边），递归隐藏用 */
    private static List<View> collectBottom(ViewGroup root) {
        List<View> list = new ArrayList<>();
        // 精确容器：抖音 MainBottomTabContainer；红果等按 BottomTab/TabBar/TabLayout 类名特征找
        View precise = findByClassName(root, "MainBottomTabContainer", 0);
        if (precise == null) {
            precise = findTabBarByClassName(root, 0);
        }
        // 兜底：收集所有"贴底 + 高度适中 + 较宽"横条。
        // 精确容器 height=0 占位时兜底抓不到，手动补入；两者合并覆盖
        // 首页导航与视频页作者条/输入条等全部底部固定 UI。
        collectBottomNode(root, list, 0);
        if (precise != null && !list.contains(precise)) {
            list.add(0, precise);
        }
        return list;
    }

    /** 按类名特征（含 BottomTab/TabBar/TabLayout/BottomNav）深度查找第一个匹配的容器 */
    private static View findTabBarByClassName(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return null;
        }
        String n = v.getClass().getSimpleName();
        if (n.contains("BottomTab") || n.contains("TabBar")
                || n.contains("TabLayout") || n.contains("BottomNav")) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findTabBarByClassName(g.getChildAt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 按类名（SimpleName）深度查找第一个匹配的容器 */
    private static View findByClassName(View v, String name, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return null;
        }
        if (name.equals(v.getClass().getSimpleName())) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByClassName(g.getChildAt(i), name, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void collectBottomNode(View v, List<View> list, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (!(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        // 含动态内容（Compose 评论区/输入框等）的容器整棵跳过：
        // 避免把滚动评论区当"固定底部 UI"透明掉导致间歇空白；底部导航等纯静态栏仍会收集
        // 再排除含视频画面的容器：视频页若底部有播放画面，透明会导致视频不显示
        if (containsDynamicUi(g, 0) || containsVideoSurface(g, 0)) {
            return;
        }
        if (scoreBottom(g) > 0) {
            list.add(g);
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            collectBottomNode(g.getChildAt(i), list, depth + 1);
        }
    }

    /** 容器是否含动态内容：Compose 渲染（评论区等）或输入框。是则不应按固定 UI 隐藏。 */
    private static boolean containsDynamicUi(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return false;
        }
        if (v instanceof android.widget.EditText) {
            return true;
        }
        String n = v.getClass().getName();
        if (n.contains("Compose") || n.contains("EditText")
                || n.contains("InputMethod") || n.contains("InputCompat")) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsDynamicUi(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 容器是否承载视频画面（TextureView / SurfaceView / VideoView 及其变体）。
     * 直播间/视频页若把视频画面容器误判为固定 UI 而透明，会导致"视频画面不显示"，
     * 故任何区域透明前必须排除含视频画面的容器，宁可漏藏 UI 也不黑屏。
     */
    private static boolean containsVideoSurface(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return false;
        }
        if (v instanceof android.view.TextureView
                || v instanceof android.view.SurfaceView
                || v instanceof android.widget.VideoView) {
            return true;
        }
        String n = v.getClass().getName();
        if (n.contains("TextureView") || n.contains("SurfaceView") || n.contains("VideoView")
                || n.contains("MediaTexture") || n.contains("GLTextureView")) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsVideoSurface(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 右侧竖排按钮组：靠右 + 竖排 + ≥3 子项 + 图标多 */
    private static int scoreRight(ViewGroup g) {
        if (g.getChildCount() < 3) {
            return -1;
        }
        if (g.getHeight() <= g.getWidth() * 1.2f) {
            return -1;
        }
        int[] loc = loc(g);
        if (loc == null) {
            return -1;
        }
        int w = screenW(g);
        int h = screenH(g);
        if (w <= 0 || h <= 0) {
            return -1;
        }
        // 屏内检查：容器起点必须在屏内（防收集到左右滑动列表里屏外的点赞列）
        if (loc[0] < 0 || loc[0] >= w || loc[1] < -h / 5 || loc[1] >= h) {
            return -1;
        }
        if (loc[0] < w * 0.55f) {
            return -1;
        }
        int icon = iconCount(g);
        if (icon == 0 && loc[0] < w * 0.75f) {
            return -1;
        }
        int rightness = (int) (loc[0] * 1000f / w);
        return icon * 100 + rightness + g.getChildCount();
    }

    /** 贴右缘竖列兜底收集（不限 childCount）：点赞列被分组成小容器时也能抓到 */
    private static List<View> collectRightEdge(ViewGroup root, int limit) {
        List<View> views = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        collectRightEdgeNode(root, views, scores, 0);
        // 降序插入排序
        for (int i = 1; i < views.size(); i++) {
            View v = views.get(i);
            int sc = scores.get(i);
            int j = i - 1;
            while (j >= 0 && scores.get(j) < sc) {
                views.set(j + 1, views.get(j));
                scores.set(j + 1, scores.get(j));
                j--;
            }
            views.set(j + 1, v);
            scores.set(j + 1, sc);
        }
        if (views.size() > limit) {
            return new ArrayList<>(views.subList(0, limit));
        }
        return views;
    }

    private static void collectRightEdgeNode(View v, List<View> views, List<Integer> scores, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (!(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        int s = scoreRightEdge(g);
        if (s > 0) {
            views.add(g);
            scores.add(s);
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            collectRightEdgeNode(g.getChildAt(i), views, scores, depth + 1);
        }
    }

    /** 贴右缘竖列：靠右(>0.78宽) + 竖排 + 含图标 + 小尺寸，不限子项数 */
    private static int scoreRightEdge(ViewGroup g) {
        if (g.getHeight() <= g.getWidth() * 1.2f) {
            return -1;
        }
        int[] loc = loc(g);
        if (loc == null) {
            return -1;
        }
        int w = screenW(g);
        int h = screenH(g);
        if (w <= 0 || h <= 0) {
            return -1;
        }
        // 屏内检查：防收集到左右滑动列表/屏外占位的容器
        if (loc[0] < 0 || loc[0] >= w || loc[1] < -h / 5 || loc[1] >= h) {
            return -1;
        }
        if (loc[0] < w * 0.78f) {
            return -1;
        }
        if (iconCount(g) == 0) {
            return -1;
        }
        if (g.getHeight() > h * 0.4f) {
            return -1; // 排除过大块（如整屏列表）
        }
        int rightness = (int) (loc[0] * 1000f / w);
        return iconCount(g) * 100 + rightness;
    }

    /** 顶部导航栏：屏上部 + 横向 + ≥3 子项 + 较宽 */
    private static int scoreTop(ViewGroup g) {
        if (g.getChildCount() < 3) {
            return -1;
        }
        if (g.getWidth() < g.getHeight() * 1.5f) {
            return -1;
        }
        int[] loc = loc(g);
        if (loc == null) {
            return -1;
        }
        int h = screenH(g);
        if (h <= 0 || loc[1] < 0 || loc[1] > h * 0.2f) {
            return -1;
        }
        int w = screenW(g);
        if (w <= 0 || g.getWidth() < w * 0.25f) {
            return -1;
        }
        return g.getWidth() + g.getChildCount() * 10;
    }

    /**
     * 底部贴底横条兜底判定：距屏幕底部间隔小（≤10% 屏高）+ 横向 + 高度适中 + 较宽。
     * 高度适中用于排除 0 高度占位容器与过大的信息卡（避免误伤大片内容）。
     */
    private static int scoreBottom(ViewGroup g) {
        if (g.getWidth() < g.getHeight() * 1.5f) {
            return -1; // 非横向
        }
        int[] loc = loc(g);
        if (loc == null) {
            return -1;
        }
        View root = g.getRootView();
        if (root == null) {
            return -1;
        }
        int rootBottom = root.getBottom();
        if (rootBottom <= 0) {
            rootBottom = root.getHeight();
        }
        if (rootBottom <= 0) {
            return -1;
        }
        int bottom = loc[1] + g.getHeight();
        int gap = rootBottom - bottom;
        if (gap < 0 || gap > rootBottom * 0.1f) {
            return -1; // 距底太远（>10% 屏高），不是贴底横条
        }
        // 高度适中：排除 0 高度占位与过大的信息卡（如视频下方 500+ 高的大块）
        if (g.getHeight() < 20 || g.getHeight() > rootBottom * 0.12f) {
            return -1;
        }
        int rootWidth = root.getWidth();
        if (rootWidth <= 0 || g.getWidth() < rootWidth * 0.4f) {
            return -1; // 不够宽
        }
        return rootBottom - gap; // 越贴底得分越大
    }

    /** 直播间：顶部固定区（标题/主播信息条等，可多个）——屏上部横向块。
     *  起点允许部分在屏外（直播顶栏常从 -152px 起），必须在屏上部 1/3 内结束。
     *  含视频画面的容器排除，防误透明直播小窗/视频画面。 */
    private static void collectLiveTopNode(View v, List<View> list, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (!(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        if (scoreLiveTop(g) > 0 && !containsVideoSurface(g, 0)) {
            list.add(g);
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            collectLiveTopNode(g.getChildAt(i), list, depth + 1);
        }
    }

    private static int scoreLiveTop(ViewGroup g) {
        int[] loc = loc(g);
        if (loc == null) {
            return -1;
        }
        int w = screenW(g);
        int h = screenH(g);
        if (w <= 0 || h <= 0) {
            return -1;
        }
        if (loc[1] < -h / 5 || loc[1] >= h * 0.22f) {
            return -1; // 顶部范围
        }
        if (loc[1] + g.getHeight() > h * 0.35f) {
            return -1; // 必须在屏上部 1/3 内结束
        }
        if (g.getWidth() < w * 0.6f) {
            return -1; // 不够宽
        }
        if (g.getHeight() < 20 || g.getHeight() > h * 0.2f) {
            return -1; // 高度适中
        }
        return (int) (w - Math.max(loc[1], 0)) + g.getChildCount() * 10;
    }

    /** 直播间：底部固定大区（输入条/分享栏等，可多个）——贴底大横块（比普通页允许更高） */
    private static void collectLiveBottomNode(View v, List<View> list, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (!(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        // 同样排除动态内容（Compose 评论区/输入框），防直播评论间歇空白；
        // 再排除含视频画面的容器（半屏/小窗直播时视频若贴底会被误透明，黑屏）
        if (containsDynamicUi(g, 0) || containsVideoSurface(g, 0)) {
            return;
        }
        if (scoreLiveBottom(g) > 0) {
            list.add(g);
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            collectLiveBottomNode(g.getChildAt(i), list, depth + 1);
        }
    }

    private static int scoreLiveBottom(ViewGroup g) {
        int[] loc = loc(g);
        if (loc == null) {
            return -1;
        }
        View root = g.getRootView();
        if (root == null) {
            return -1;
        }
        int rootBottom = root.getBottom();
        if (rootBottom <= 0) {
            rootBottom = root.getHeight();
        }
        if (rootBottom <= 0) {
            return -1;
        }
        int bottom = loc[1] + g.getHeight();
        int gap = rootBottom - bottom;
        if (gap < 0 || gap > rootBottom * 0.1f) {
            return -1; // 必须贴底
        }
        int w = screenW(g);
        int h = screenH(g);
        if (w <= 0 || h <= 0) {
            return -1;
        }
        if (g.getWidth() < w * 0.6f) {
            return -1; // 不够宽
        }
        if (g.getHeight() < h * 0.08f || g.getHeight() > h * 0.4f) {
            return -1; // 高度适中（可大于普通页 12%：直播底栏含输入条整体）
        }
        return rootBottom - gap + g.getChildCount() * 100;
    }

    /** 直播间：右下角大按钮组（礼物/分享等，可多个）——右下角 + 中尺寸 + 有子内容 */
    private static void collectLiveRightNode(View v, List<View> list, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (!(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        if (scoreLiveRight(g) > 0 && !containsVideoSurface(g, 0)) {
            list.add(g);
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            collectLiveRightNode(g.getChildAt(i), list, depth + 1);
        }
    }

    private static int scoreLiveRight(ViewGroup g) {
        if (g.getChildCount() == 0) {
            return -1;
        }
        int[] loc = loc(g);
        if (loc == null) {
            return -1;
        }
        int w = screenW(g);
        int h = screenH(g);
        if (w <= 0 || h <= 0) {
            return -1;
        }
        if (loc[0] < w * 0.7f || loc[1] < h * 0.8f) {
            return -1; // 右下角
        }
        if (g.getWidth() > w * 0.25f || g.getHeight() > h * 0.15f) {
            return -1; // 尺寸过大排除全屏容器
        }
        if (g.getWidth() < 30 || g.getHeight() < 30) {
            return -1; // 排除 0 尺寸占位
        }
        return (int) (loc[0] + loc[1]) + g.getChildCount() * 10;
    }

    /** 右上角操作按钮（搜索/分享/更多等单个或小组件）：屏内右上角 + 小尺寸 + 图标 */
    private static int scoreRightTop(ViewGroup g) {
        int[] loc = loc(g);
        if (loc == null) {
            return -1;
        }
        int w = screenW(g);
        int h = screenH(g);
        if (w <= 0 || h <= 0) {
            return -1;
        }
        // 必须完整落在屏幕内（抖音存在屏幕外的下一屏预览等组件，须排除）
        if (loc[0] < 0 || loc[1] < 0
                || loc[0] + g.getWidth() > w || loc[1] + g.getHeight() > h) {
            return -1;
        }
        if (loc[0] < w * 0.6f || loc[1] > h * 0.22f) {
            return -1; // 不在右上角
        }
        if (g.getWidth() > w * 0.2f) {
            return -1; // 尺寸过大
        }
        if (!containsImageView(g, 0)) {
            return -1; // 必须含图标
        }
        int rightness = (int) (loc[0] * 1000f / w);
        int topness = (int) ((1f - loc[1] / h) * 100);
        return rightness * 10 + topness + (containsDirectImage(g) ? 100 : 0) + g.getChildCount();
    }

    /** 左上角菜单按钮（三横线）：屏内左上角 + 小尺寸 + 图标 */
    private static int scoreLeftTop(ViewGroup g) {
        int[] loc = loc(g);
        if (loc == null) {
            return -1;
        }
        int w = screenW(g);
        int h = screenH(g);
        if (w <= 0 || h <= 0) {
            return -1;
        }
        if (loc[0] < 0 || loc[1] < 0
                || loc[0] + g.getWidth() > w || loc[1] + g.getHeight() > h) {
            return -1; // 必须在屏内
        }
        if (loc[0] > w * 0.15f || loc[1] > h * 0.15f) {
            return -1; // 不在左上角
        }
        if (g.getWidth() > w * 0.2f) {
            return -1;
        }
        if (!containsImageView(g, 0)) {
            return -1;
        }
        int leftness = (int) ((1f - loc[0] / w) * 100);
        int topness = (int) ((1f - loc[1] / h) * 100);
        return leftness * 10 + topness + (containsDirectImage(g) ? 100 : 0) + g.getChildCount();
    }

    // ---------- 工具 ----------

    private static int[] loc(View v) {
        int[] loc = new int[2];
        try {
            v.getLocationOnScreen(loc);
            return loc;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 屏幕宽：优先取根视图实际宽度（与 getLocationOnScreen 同一坐标系）。
     * 抖音进程 displayMetrics 与真实渲染尺寸可能不一致，必须用 decorView 实际尺寸。
     */
    private static int screenW(View v) {
        View root = v.getRootView();
        if (root != null && root.getWidth() > 0) {
            return root.getWidth();
        }
        Context ctx = v.getContext();
        if (ctx == null) {
            return 0;
        }
        return ctx.getResources().getDisplayMetrics().widthPixels;
    }

    /** 屏幕高：同 screenW，优先根视图实际高度 */
    private static int screenH(View v) {
        View root = v.getRootView();
        if (root != null && root.getHeight() > 0) {
            return root.getHeight();
        }
        Context ctx = v.getContext();
        if (ctx == null) {
            return 0;
        }
        return ctx.getResources().getDisplayMetrics().heightPixels;
    }

    private static int iconCount(ViewGroup g) {
        int icon = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (containsImageView(g.getChildAt(i), 1)) {
                icon++;
            }
        }
        return icon;
    }

    private static boolean containsDirectImage(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            if (g.getChildAt(i) instanceof ImageView) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsImageView(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return false;
        }
        if (v instanceof ImageView) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsImageView(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String desc(View v) {
        if (v == null) {
            return "null";
        }
        ViewGroup g = (ViewGroup) v;
        StringBuilder sb = new StringBuilder();
        sb.append(v.getClass().getSimpleName())
                .append(" id=").append(v.getId())
                .append(" children=").append(g.getChildCount());
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            sb.append(" pos=(").append(loc[0]).append(',').append(loc[1]).append(')')
                    .append(" size=").append(v.getWidth()).append('x').append(v.getHeight());
        } catch (Throwable t) {
            // ignore
        }
        return sb.toString();
    }

    // ---------- 周期 ----------

    private static void startCycle() {
        if (started) {
            return;
        }
        started = true;
        handler.removeCallbacks(cycleRunnable);
        handler.postDelayed(cycleRunnable, 3000);
    }

    private static final Runnable cycleRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                Log.e(TAG, "tick error", t);
            }
        }
    };

    private static void tick() {
        Config.reload();

        // 兜底重扫：主要靠布局监听（事件驱动）即时抓新容器；这里每 2s 兜底一次，
        // 防布局监听覆盖不到的翻页场景漏藏。节流避免高频全树遍历（省电）。
        if (currentDecor instanceof ViewGroup && currentDecor.isAttachedToWindow()
                && System.currentTimeMillis() - lastScan >= 2000) {
            lastScan = System.currentTimeMillis();
            rescan((ViewGroup) currentDecor);
        }
        // 悬浮球周期兜底保活（布局变化即时保活之上的最后防线）
        if (Config.enableBall) {
            ensureBall();
        } else if (ballView != null) {
            removeBall();
        }

        // 清理失效视图（页面切换/重建），布局监听会重扫
        Iterator<View> it = rightList.iterator();
        while (it.hasNext()) {
            View v = it.next();
            if (!v.isAttachedToWindow()) {
                it.remove();
            }
        }
        Iterator<View> ib = bottomList.iterator();
        while (ib.hasNext()) {
            View v = ib.next();
            if (!v.isAttachedToWindow()) {
                ib.remove();
            }
        }
        if (topBar != null && !topBar.isAttachedToWindow()) {
            topBar = null;
        }
        if (rightTop != null && !rightTop.isAttachedToWindow()) {
            rightTop = null;
        }
        if (leftTop != null && !leftTop.isAttachedToWindow()) {
            leftTop = null;
        }

        // 红果进程必须用红果开关判断，否则红果开关开了也不进隐藏窗口
        boolean anyEnabled;
        if (isDrama) {
            anyEnabled = (Config.enableDramaLike && !rightList.isEmpty())
                    || (Config.enableDramaTop && topBar != null)
                    || (Config.enableDramaBottom && !bottomList.isEmpty())
                    || (Config.enableDramaRt && rightTop != null)
                    || (Config.enableDramaLt && leftTop != null);
        } else {
            anyEnabled = (Config.enableDouyinLike && !rightList.isEmpty())
                    || (Config.enableDouyinTop && topBar != null)
                    || (Config.enableDouyinBottom && !bottomList.isEmpty())
                    || (Config.enableDouyinRt && rightTop != null)
                    || (Config.enableDouyinLt && leftTop != null);
        }

        if (!isScreenOn() || !anyEnabled) {
            if (hidden) {
                showAll();
                hidden = false;
            }
            handler.postDelayed(cycleRunnable, 3000);
            return;
        }

        // 统一周期相位：与 SystemUI 状态栏/导航栏（Controller）共用「系统单调时钟对周期取模」判断此刻
        // 该隐藏还是显示，并在同一相位边界切换——抖音/红果与状态栏/导航栏完全同步，
        // 不再"这个隐藏完了那个才显示"，用户观感统一。
        long now = SystemClock.elapsedRealtime();
        long hideMs = Math.max(1, Config.hideSec) * 1000L;
        long showMs = Math.max(1, Config.showSec) * 1000L;
        long pos = now % (hideMs + showMs);
        boolean shouldHide = pos < hideMs;

        if (shouldHide) {
            if (!hidden) {
                hideEnabled();
                hidden = true;
            } else {
                // 隐藏窗口内：轻量重压一遍。已隐藏容器仅做属性检查（几乎无开销），
                // 覆盖翻页新建容器；翻页即时性主要由布局监听 + onAttached 兜底保证。
                hideEnabled();
            }
        } else {
            if (hidden) {
                showAll();
                hidden = false;
            }
        }

        // 调度：优先切到下一相位边界（保持四者同步），但最迟 2s 一次（兜底重扫/保活）
        long remain = shouldHide ? (hideMs - pos) : (hideMs + showMs - pos);
        handler.postDelayed(cycleRunnable, Math.min(2000, Math.max(100, remain)));
    }

    private static void hideEnabled() {
        // 悬浮球"强制显示"模式：不隐藏任何控件（用于查看各区域控件是否被正确隐藏）
        if (forceShow) {
            showAll();
            return;
        }
        // 直播间专用：页面结构与普通视频页完全不同，走直播三区 + 右上角
        if (isLive()) {
            if (!Config.enableLive) {
                showAll();
                return;
            }
            for (View v : liveTopList) {
                transparentDeep(v, "livetop");
            }
            for (View v : liveBottomList) {
                transparentDeep(v, "livebottom");
            }
            for (View v : liveRightList) {
                transparentDeep(v, "liveright");
            }
            if (rightTop != null) {
                transparentDeep(rightTop, "righttop");
            }
            return;
        }
        // 红果短剧用红果独立开关，抖音用抖音开关
        boolean dLike = isDrama ? Config.enableDramaLike : Config.enableDouyinLike;
        boolean dTop = isDrama ? Config.enableDramaTop : Config.enableDouyinTop;
        boolean dBot = isDrama ? Config.enableDramaBottom : Config.enableDouyinBottom;
        boolean dRt = isDrama ? Config.enableDramaRt : Config.enableDouyinRt;
        boolean dLt = isDrama ? Config.enableDramaLt : Config.enableDouyinLt;
        // 开关关的区域立即恢复（不等到显示窗口），避免"明明关了导航栏还是消失"
        if (dLike) {
            for (View v : rightList) {
                transparentDeep(v, "right");
            }
        } else {
            for (View v : rightList) {
                showDeep(v);
            }
        }
        if (dTop) {
            if (topBar != null) {
                transparentDeep(topBar, "top");
            }
        } else if (topBar != null) {
            showDeep(topBar);
        }
        // 底部栏：红果短剧剧集页底部是"完整观看/选集"等可点击功能入口，必须常驻可点，
        // 不能当固定导航条透明掉（否则入口消失点不进去）；故红果始终不隐藏底部，只做恢复。
        // 抖音首页/视频页底部导航仍按开关隐藏。
        boolean hideBottom = isDrama ? false : dBot;
        if (hideBottom) {
            for (View v : bottomList) {
                transparentDeep(v, "bottom");
            }
        } else {
            for (View v : bottomList) {
                showDeep(v);
            }
        }
        if (dRt) {
            if (rightTop != null) {
                transparentDeep(rightTop, "righttop");
            }
        } else if (rightTop != null) {
            showDeep(rightTop);
        }
        if (dLt) {
            if (leftTop != null) {
                transparentDeep(leftTop, "lefttop");
            }
        } else if (leftTop != null) {
            showDeep(leftTop);
        }
    }

    /**
     * 深度透明（含第二重保险）：容器 setAlpha(0) + setVisibility(INVISIBLE)。
     * INVISIBLE 保留空间（不破坏布局/触摸区），强制整棵子树不绘制——
     * 即使容器 height=0、或 tab 子项越界显示，也能彻底藏掉。比 setAlpha 可靠。
     */
    private static void transparentDeep(View v, String what) {
        if (v.getVisibility() != View.GONE && v.getAlpha() != 0f) {
            v.setAlpha(0f);
            Log.i(TAG, what + " hidden: " + desc(v));
        }
        if (v.getVisibility() != View.INVISIBLE && v.getVisibility() != View.GONE) {
            v.setVisibility(View.INVISIBLE);
        }
    }

    private static void showAll() {
        for (View v : rightList) {
            showDeep(v);
        }
        showDeep(topBar);
        for (View v : bottomList) {
            showDeep(v);
        }
        showDeep(rightTop);
        showDeep(leftTop);
        for (View v : liveTopList) {
            showDeep(v);
        }
        for (View v : liveBottomList) {
            showDeep(v);
        }
        for (View v : liveRightList) {
            showDeep(v);
        }
    }

    /** 深度恢复：容器恢复 VISIBLE + alpha=1 */
    private static void showDeep(View v) {
        if (v == null) {
            return;
        }
        if (v.getVisibility() != View.VISIBLE) {
            v.setVisibility(View.VISIBLE);
        }
        if (v.getAlpha() != 1f) {
            v.setAlpha(1f);
            Log.i(TAG, "shown: " + desc(v));
        }
    }

    // ---------- 悬浮球：点击切换隐藏控件的显示/消失 ----------

    private static void ensureBall() {
        try {
            View decor = currentDecor;
            if (decor == null || !(decor instanceof ViewGroup)) {
                return;
            }
            if (ballView != null) {
                // 小球仍在当前页面窗口上：不动
                if (ballView.getParent() == decor) {
                    return;
                }
                // 页面 Activity 更换导致旧窗口销毁：摘除旧引用重建
                try {
                    if (ballView.getParent() instanceof ViewGroup) {
                        ((ViewGroup) ballView.getParent()).removeView(ballView);
                    }
                } catch (Throwable ignored) {
                }
                ballView = null;
            }
            Context ctx = decor.getContext();
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int size = (int) (44 * dm.density + 0.5f);

            TextView ball = new TextView(ctx);
            ball.setGravity(Gravity.CENTER);
            ball.setTextSize(13);
            applyBallFace(ball);
            // 点击切换显/藏；拖动可自由移动（长按/移动距离超过 touchSlop 视为拖动，
            // 短按视为点击）——避免小窗/评论等场景被悬浮球挡住无法操作
            final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            final float[] down = new float[2];
            final int[] start = new int[2];
            final boolean[] dragged = {false};
            ball.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        down[0] = event.getRawX();
                        down[1] = event.getRawY();
                        android.widget.FrameLayout.LayoutParams sp =
                                (android.widget.FrameLayout.LayoutParams) v.getLayoutParams();
                        start[0] = sp.leftMargin;
                        start[1] = sp.topMargin;
                        dragged[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragged[0] && (Math.abs(event.getRawX() - down[0]) > slop
                                || Math.abs(event.getRawY() - down[1]) > slop)) {
                            dragged[0] = true;
                        }
                        if (dragged[0]) {
                            ViewGroup.LayoutParams p = v.getLayoutParams();
                            if (p instanceof android.widget.FrameLayout.LayoutParams) {
                                android.widget.FrameLayout.LayoutParams flp =
                                        (android.widget.FrameLayout.LayoutParams) p;
                                int nl = start[0] + (int) (event.getRawX() - down[0]);
                                int nt = start[1] + (int) (event.getRawY() - down[1]);
                                nl = Math.max(0, Math.min(nl, dm.widthPixels - size));
                                nt = Math.max(0, Math.min(nt, dm.heightPixels - size));
                                flp.leftMargin = nl;
                                flp.topMargin = nt;
                                v.setLayoutParams(flp);
                                ballLeft = nl;
                                ballTop = nt;
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragged[0]) {
                            toggleForceShow(); // 短按 = 点击切换显/藏
                        }
                        return true;
                    default:
                        return true;
                }
            });

            // 直接挂到抖音页面自己的窗口树上：无需悬浮窗权限，必然可见、必然置顶
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(size, size);
            lp.gravity = Gravity.TOP | Gravity.START;
            // 位置：优先记住用户拖到的地方；未拖动过用默认（右缘 8dp、上部 1/3）
            lp.leftMargin = ballLeft >= 0 ? ballLeft
                    : (dm.widthPixels - size - (int) (8 * dm.density + 0.5f));
            lp.topMargin = ballTop >= 0 ? ballTop : dm.heightPixels / 3;
            ((ViewGroup) decor).addView(ball, lp);
            ballView = ball;
            Log.i(TAG, "floating ball on decor forceShow=" + forceShow);
        } catch (Throwable t) {
            Log.e(TAG, "ball create failed", t);
            ballView = null;
        }
    }

    private static void removeBall() {
        if (ballView == null) {
            return;
        }
        try {
            if (ballView.getParent() instanceof ViewGroup) {
                ((ViewGroup) ballView.getParent()).removeView(ballView);
            }
        } catch (Throwable ignored) {
        }
        ballView = null;
    }

    /** 点击悬浮球：切换 隐藏生效 ↔ 强制显示全部隐藏控件 */
    private static void toggleForceShow() {
        forceShow = !forceShow;
        if (forceShow) {
            showAll();   // 立即显示所有被隐藏控件
        } else {
            hideEnabled();
        }
        if (ballView instanceof TextView) {
            applyBallFace((TextView) ballView);
        }
        Log.i(TAG, "forceShow=" + forceShow);
    }

    /** 悬浮球外观：藏=灰底"藏"，显=绿底"显" */
    private static void applyBallFace(TextView ball) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(forceShow ? 0xFF2E8B57 : 0xAA333333);
        gd.setStroke(2, 0xAAFFFFFF);
        ball.setBackground(gd);
        ball.setTextColor(0xFFFFFFFF);
        ball.setText(forceShow ? "显" : "藏");
    }

    private static boolean isScreenOn() {
        try {
            View ref = !rightList.isEmpty() ? rightList.get(0)
                    : (!bottomList.isEmpty() ? bottomList.get(0)
                    : (topBar != null ? topBar
                    : (rightTop != null ? rightTop : leftTop)));
            if (ref == null) {
                return true;
            }
            Object pm = ref.getContext().getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return true;
            }
            return (Boolean) pm.getClass().getMethod("isInteractive").invoke(pm);
        } catch (Throwable t) {
            return true;
        }
    }
}
