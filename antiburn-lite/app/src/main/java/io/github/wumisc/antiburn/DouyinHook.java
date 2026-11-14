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
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

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
 * v2.6.8：快手 / 哔哩哔哩复用本类同一套几何识别与周期隐藏，用视频区域开关门控；
 * 红果「左滑查看详情」与抖音直播间三区判定严格隔离，不串到新 App。
 *
 * v2.6.9 真机反馈修复（只作用于 isVideoApp，抖音 / 红果行为一道不改）：
 *  - 隐藏前做内容安全校验，避免几何打分把 B站 顶部横幅 / 推荐网格误判成固定栏，
 *    整棵子树 INVISIBLE 造成大片内容周期性消失；
 *  - 底部中央独立圆形「+」发布按钮按形状 + 位置单独收集，归入「底部操作栏」开关；
 *  - App 内区域隐藏补齐白名单 / 总开关 / 引擎豁免，直播特征页整页跳过。
 *
 * v2.7.0：真机反馈「只有加号生效，其它全部失效」—— v2.6.9 的排除式护栏
 * 误杀全部固定栏（右侧互动列子节点天然超 24、B站底栏含弹幕输入框、顶部横幅大容器）。
 * safeToHide 重写为形状限定式：右侧=窄竖条、顶/底=矮横条、角落/加号=小控件，
 * 保留视频画面排除；抖音 / 红果行为不变。快手 / B站 配置键完全独立
 * （enable_ks_* / enable_bili_*），两应用互不影响。
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
    private static final List<View> swipeHintList = new ArrayList<>(); // 红果「左滑查看详情」竖排引导条（按文案识别）
    /** v2.6.9：快手 / B站 底部中央独立「+」发布按钮（不属于五类几何区域，按形状 + 位置单独收集） */
    private static final List<View> centerPlusList = new ArrayList<>();
    /** v2.6.9：当前页命中直播文本特征。命中则视频 App 整页跳过 App 内区域隐藏，避免误伤弹幕 / 礼物栏。 */
    private static volatile boolean videoLivePage = false;
    /** v2.6.9：宿主进程自身包名，供 shouldIntervene 判定白名单豁免。 */
    private static volatile String selfPkg = "";
    private static volatile View topBar;       // 顶部导航
    private static volatile View rightTop;     // 右上角操作按钮
    private static volatile View leftTop;      // 左上角菜单按钮
    private static volatile View currentDecor; // 当前根视图（用于周期强制重扫）
    private static boolean started = false;
    private static boolean hidden = false;
    private static boolean isDrama = false; // 红果短剧（com.phoenix.read 系）进程：用红果独立开关
    /** v2.6.8：快手 / B站 进程。复用同一套已验证的几何识别骨架，用视频区域开关 + 各自总开关。 */
    private static volatile boolean isVideoApp = false;
    private static volatile boolean isKuaishou = false;
    /** v2.8.0：显式记录 B站进程（含 com.bilibili.app.* 变体），不靠「非快手即 B站」的隐含推断。 */
    private static volatile boolean isBilibili = false;
    /** v2.8.0：视频进程状态诊断的上一条签名与时间戳，做状态变化 + 心跳节流，避免刷屏。 */
    private static volatile String lastHookSig = "";
    private static volatile long lastHookDiagAt = 0L;
    /**
     * v2.7：当前是否直播间的唯一判定结果，由 rescan 在收集完节点后一次性算出并提交。
     * 旧版 anyEnabled 用 isLiveStrong()、hideEnabled 用 isLive()、rescan 另有一套 local live，
     * 三套判定互相打架：只开「直播间隐藏」时进不了隐藏窗口，或普通视频页被连带否决。
     * 现在三处统一只读这一个字段。
     */
    private static volatile boolean liveDetected = false;
    /**
     * v2.7.2：抖音 / 红果 直播间整体暂停。liveDetected（抖音结构判定）或 videoLivePage
     * （直播文本特征，覆盖红果）任一命中即为 true —— 本进程所有 App 内隐藏立刻让位，
     * 同时把带时间戳的信号写给 SystemUI，让状态栏 / 导航栏同步恢复。
     * 只作临时暂停，不改用户任何开关；退出直播后原有开关与周期相位自然继续。
     */
    private static volatile boolean livePaused = false;
    /** v2.7.3：最近一次 rescan 是否命中「直播专属节点 + 底部无首页 Tab」，供 tick 即时解除用。 */
    private static volatile boolean liveStrongHit = false;
    /** v2.7.3：连续命中次数。进入暂停需 2 次（防普通页误判），解除只要 1 次不命中。 */
    private static int liveStreak = 0;
    /**
     * v2.7.3：本轮是否已经排好下一次周期调度。
     * 历史缺陷（真机复现「进直播间再退出后隐藏全部失效」）：tick() 中途抛异常时，
     * 末尾的 postDelayed 根本执行不到，catch 吞掉异常后周期循环就永久停摆。
     * 现在由 finally 依据此标记兜底重排。
     */
    private static volatile boolean cycleScheduled = false;
    private static long lastLiveProbe = 0L;

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
            // v2.8.0：与 XposedInit 共用 VideoApps 唯一真源。旧版这里用精确 equals 认
            // tv.danmaku.bili，B站渠道包 com.bilibili.app.in 即使被注入也会掉进「抖音」
            // 分支用错开关；isVideoApp 也无法覆盖变体。
            VideoApps.Kind kind = VideoApps.of(lpparam.packageName);
            isDrama = kind == VideoApps.Kind.DRAMA;
            isKuaishou = kind == VideoApps.Kind.KUAISHOU;
            isBilibili = kind == VideoApps.Kind.BILIBILI;
            isVideoApp = isKuaishou || isBilibili;
            String pn = lpparam.packageName == null ? "" : lpparam.packageName;
            selfPkg = pn;
            // 注入即无条件落盘：带真实包名与识别出的类型，一条就能确认「进程有没有被注入」。
            XposedInit.diagForce("HOOK-ATTACH pkg=" + pn + " kind=" + VideoApps.label(kind));
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

    /**
     * 当前是否直播间：只读 rescan 提交的唯一结果。
     * v2.7 起不再各自即时判定，避免 isLive / isLiveStrong / rescan-local 三套结论冲突。
     */
    private static boolean isLive() {
        // v2.6.8：直播判定只在抖音成立，快手 / B站 未标定，绝不能串进直播三区隐藏。
        return !isDrama && !isVideoApp && liveDetected;
    }

    private static int dumpNode(View v, StringBuilder sb, int depth, int count) {
        if (v == null || depth > 18 || count > 1000) {
            return count;
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
        count++; // v2.5：旧版递归传 count+1 实际等于 depth，1000 节点上限从未生效
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (v.getVisibility() == View.GONE) {
                return count; // GONE 容器子树不绘制，跳过省节点数
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                count = dumpNode(g.getChildAt(i), sb, depth + 1, count);
            }
        }
        return count;
    }

    private static void writeTreeFile(String text) {
        // 抖音 / 红果是普通 app，没有任何 /sdcard 写权限（Android 10+ 分区存储），
        // 旧版这里静默失败，dump 文件从来没生成过。改走统一的文件通道：
        // 可写目录直写，否则经 root 落到 /data/local/tmp，至少能取到。
        boolean ok = XposedInit.writeSharedFile("antiburn_tree.txt", text);
        if (!ok) {
            Log.w(TAG, "write tree file failed (no writable path)");
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
            // v2.6.6：红果「左滑查看详情」竖排引导条。只在红果进程按文案收集，
            // 抖音进程不跑这段（抖音里也有「查看详情」字样，收了会误伤）。
            if (isDrama) {
                List<View> swipes = new ArrayList<>();
                collectSwipeHintNode(root, swipes, 0);
                if (!swipes.equals(swipeHintList)) {
                    // 旧列表里已透明化的视图先恢复，避免换页后残留隐藏
                    for (View prev : swipeHintList) {
                        if (!swipes.contains(prev)) {
                            showDeep(prev);
                        }
                    }
                    swipeHintList.clear();
                    swipeHintList.addAll(swipes);
                    XposedInit.diag("SWIPE x" + swipeHintList.size());
                    for (View s : swipeHintList) {
                        XposedInit.diag("  swipe " + desc(s));
                    }
                }
            }
            // v2.6.9：快手 / B站 专属收集。只在视频 App 进程跑，不串抖音 / 红果。
            // 直播文本特征只用于「保守跳过隐藏」，不触发任何隐藏动作；命中偏多只会少藏，不会误伤。
            // v2.7.2：直播文本特征改为所有进程统一算，并做 1.5 秒节流。
            // 旧版只在快手 / B站 里算，红果完全拿不到直播信号 —— 用户明确要求
            // 「抖音以及红果，进入直播间就关闭所有隐藏功能」，红果只能靠文案特征识别。
            long nowProbe = System.currentTimeMillis();
            if (nowProbe - lastLiveProbe >= 1500L) {
                lastLiveProbe = nowProbe;
                videoLivePage = isLiveTextPage(root, 0);
            }
            if (isVideoApp) {
                List<View> plus = new ArrayList<>();
                collectCenterPlusNode(root, plus, 0);
                if (!plus.equals(centerPlusList)) {
                    for (View prev : centerPlusList) {
                        if (!plus.contains(prev)) {
                            showDeep(prev);
                        }
                    }
                    centerPlusList.clear();
                    centerPlusList.addAll(plus);
                    XposedInit.diag("PLUS x" + centerPlusList.size() + " live=" + videoLivePage);
                }
            }
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
                    .append(" | lefttop=").append(leftTop != null ? desc(leftTop) : "NONE")
                    .append(" | swipe=x").append(swipeHintList.size())
                    .append(" | app=")
                    .append(isVideoApp ? (isKuaishou ? "kuaishou" : "bilibili")
                            : (isDrama ? "drama" : "douyin"))
                    .append(" | plus=x").append(centerPlusList.size())
                    .append(" | live=").append(videoLivePage)
                    .append(" | pause=").append(livePaused);
            XposedInit.diag(sb.toString());
            // 直播间专用三区收集。
            // v2.5 修复死锁：旧版先 clear 三个 live list 再调 isLive()，而 isLive()
            // 的 liveViews 分支恰恰要求这三个 list 非空 —— 于是除 rightTop 命中
            // RoundCornerFrameLayout 外，直播间永远判不出来，「直播间隐藏」形同虚设。
            // 现在先收集到临时表，用本轮收集结果判定，通过才提交。
            java.util.List<View> tTop = new java.util.ArrayList<>();
            java.util.List<View> tBottom = new java.util.ArrayList<>();
            java.util.List<View> tRight = new java.util.ArrayList<>();
            collectLiveTopNode(root, tTop, 0);
            collectLiveBottomNode(root, tBottom, 0);
            collectLiveRightNode(root, tRight, 0);
            boolean hasTab = false;
            if (bottomList != null) {
                for (View b : bottomList) {
                    if ("MainBottomTabContainer".equals(b.getClass().getSimpleName())) {
                        hasTab = true;
                        break;
                    }
                }
            }
            boolean liveNode = !tTop.isEmpty() || !tBottom.isEmpty() || !tRight.isEmpty();
            boolean strongCorner = rightTop != null
                    && "RoundCornerFrameLayout".equals(rightTop.getClass().getSimpleName());
            // v2.7：判定放宽为「强特征命中」或「收集到直播专属节点且底部无首页 Tab」。
            // 旧版要求 bottomList 非空，但直播底部含 EditText / Compose 输入条会被
            // containsDynamicUi 排除，bottomList 常为空，导致直播间永远判不出来。
            // v2.6.8：快手 / B站 复用普通五区判定，不参与抖音直播间专属三区，
            // 否则未标定的 App 会被误判成直播间、误隐藏弹幕输入框等交互控件。
            boolean live = !isDrama && !isVideoApp && (strongCorner || (liveNode && !hasTab));
            liveDetected = live;   // 唯一判定源，anyEnabled / hideEnabled 都读它
            // v2.7.3：整体暂停的判据必须比 liveDetected 更严。liveDetected 允许 strongCorner
            // 单独成立，而 RoundCornerFrameLayout 在普通视频页也会出现 —— 用它决定「整进程暂停」
            // 会把抖音永久钉在暂停态，退出直播间后所有隐藏都不再工作（真机反馈即此）。
            // 暂停只认：直播文案特征，或「直播专属节点 + 底部没有首页 Tab」。
            liveStrongHit = !isVideoApp && liveNode && !hasTab;
            boolean pausedNow = !isVideoApp && (videoLivePage || liveStrongHit);
            liveStreak = pausedNow ? Math.min(liveStreak + 1, 3) : 0;
            // 进（需连续 2 次，约 2 秒）/ 出（1 次不命中即恢复）刻意不对称：
            // 宁可晚 2 秒进暂停，也不让用户退出来以后还被锁住。
            boolean paused = pausedNow && (livePaused || liveStreak >= 2);
            if (paused != livePaused) {
                livePaused = paused;
                if (paused) {
                    // 进入直播：立刻把此前已隐藏的区域全部恢复，不等下一个显示相位
                    showAll();
                    hidden = false;
                }
                XposedInit.diag("LIVE PAUSE=" + paused
                        + " app=" + (isDrama ? "drama" : "douyin"));
            }
            // 心跳发布：退出写 0 立即撤销；直播期间 5 秒一次，15 秒无心跳即失效。
            LivePause.publish(selfPkg, paused);
            if (live) {
                liveTopList.clear();
                liveTopList.addAll(tTop);
                liveBottomList.clear();
                liveBottomList.addAll(tBottom);
                liveRightList.clear();
                liveRightList.addAll(tRight);
                XposedInit.diag("LIVE top=x" + tTop.size() + " bottom=x"
                        + tBottom.size() + " right=x" + tRight.size()
                        + " strongCorner=" + strongCorner + " hasTab=" + hasTab);
            } else {
                liveTopList.clear();
                liveBottomList.clear();
                liveRightList.clear();
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
        // v2.7.2：快手右侧只有赞 / 评论 / 分享，分享位置还会随版本折叠，子项数下限对
        // 视频 App 降到 2；抖音 / 红果 仍要求 3，行为不变。
        int minChild = isVideoApp ? 2 : 3;
        if (g.getChildCount() < minChild) {
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
        // v2.7.2：快手 / B站 用自绘或 Compose 图标，iconCount 恒为 0。
        // 未贴到 0.75 宽时旧版直接排除，现在补通用图标信号兜底。
        if (icon == 0 && loc[0] < w * 0.75f && !(isVideoApp && hasIconSignal(g, 0))) {
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
        // v2.7.2：贴右缘兜底路径同样硬要求 ImageView，快手整条右侧列收不到，
        // 一个开关都命中不了。对视频 App 接受通用图标信号，抖音 / 红果 不变。
        if (iconCount(g) == 0 && !(isVideoApp && hasIconSignal(g, 0))) {
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
        // v2.7.2：快手 / B站 用自绘或 Compose 画图标，子树里没有 ImageView，
        // 旧版在这一步把右上搜索整块排除，真机表现就是「搜索正常但角落按钮不动」。
        // 仅对视频 App 追加通用图标信号，抖音 / 红果 判定保持逐字节不变。
        if (!containsImageView(g, 0) && !(isVideoApp && hasIconSignal(g, 0))) {
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
        // v2.7.2：同 scoreRightTop —— 快手左上三横线是自绘按钮，没有 ImageView 子节点。
        if (!containsImageView(g, 0) && !(isVideoApp && hasIconSignal(g, 0))) {
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

    /**
     * v2.7.2：通用图标信号。旧版角落按钮只认 ImageView，但快手 / B站 的返回、搜索、
     * 三横线都是自绘 View 或 Compose 节点，整棵子树没有 ImageView。
     * 这里补三条与绘制方式无关的信号，任一成立即视为图标按钮：
     * ① 无障碍描述非空（自绘按钮几乎都会设置）；
     * ② 短符号文本（图标字体 / 单字按钮，长度 ≤ 4）；
     * ③ 小尺寸可点击控件（≤ 200px，自绘图标按钮的典型形态）。
     * 只在 isVideoApp 下作为 containsImageView 的替代，抖音 / 红果不受影响。
     */
    private static boolean hasIconSignal(View v, int depth) {
        if (v == null || depth > 8) {
            return false;
        }
        if (v instanceof ImageView || v instanceof android.widget.ImageButton) {
            return true;
        }
        try {
            CharSequence cd = v.getContentDescription();
            if (cd != null && cd.length() > 0 && cd.length() <= 24) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof TextView) {
            try {
                CharSequence t = ((TextView) v).getText();
                if (t != null && t.length() > 0 && t.length() <= 4) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            if (v.isClickable() && v.getWidth() > 0 && v.getHeight() > 0
                    && v.getWidth() <= 200 && v.getHeight() <= 200) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasIconSignal(g.getChildAt(i), depth + 1)) {
                    return true;
                }
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
        // v2.5：findByClassName 命中的可能是普通 View，旧版无条件强转 ViewGroup
        // 会抛 ClassCastException 并被 rescan 外层吞掉，导致整轮扫描/隐藏失效。
        int children = (v instanceof ViewGroup) ? ((ViewGroup) v).getChildCount() : 0;
        StringBuilder sb = new StringBuilder();
        sb.append(v.getClass().getSimpleName())
                .append(" id=").append(v.getId())
                .append(" children=").append(children);
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
        scheduleCycle(3000);
    }

    /** 唯一周期调度入口：先登记「已排下一次」，异常兜底据此避免重复排队。 */
    private static void scheduleCycle(long delayMs) {
        cycleScheduled = true;
        handler.postDelayed(cycleRunnable, delayMs);
    }

    private static final Runnable cycleRunnable = new Runnable() {
        @Override
        public void run() {
            cycleScheduled = false;
            try {
                tick();
            } catch (Throwable t) {
                Log.e(TAG, "tick error", t);
                // 真机取证用：这条一旦出现，说明本轮 tick 中断过，后续走 finally 兜底重排
                XposedInit.diag("TICK ERROR " + t);
            } finally {
                // v2.7.3 核心修复：任何异常都不得让周期循环停摆。
                // tick() 正常路径已经在末尾排好下一次（cycleScheduled=true），此处不重复排；
                // 只有异常中断导致未排时才兜底，保证隐藏功能不会「一次性死掉」。
                if (!cycleScheduled && handler != null) {
                    scheduleCycle(3000);
                }
            }
        }
    };

    /**
     * v2.8.0：视频进程状态快照，无条件落盘（diagForce），供用户导出日志一次定位
     * 「没注入 / 引擎让位 / 名单豁免 / 开关没开 / 几何没扫到 / 没进隐藏相位」中的哪一环。
     * 与 SystemUI 的 SYS 行、遮盖服务的 MASK 行同一套「状态签名 + 30s 心跳」节流。
     */
    private static void hookDiag() {
        long now = SystemClock.elapsedRealtime();
        boolean overlay = ConfStore.ENGINE_OVERLAY.equals(Config.engine);
        boolean itv = Config.shouldIntervene(selfPkg);
        boolean master = isVideoApp
                ? (isKuaishou ? Config.enableKuaishou : Config.enableBilibili)
                : Config.enableStatus;
        // 各 App 自己的分区开关位串：抖音6 / 红果5 / 快手·B站4
        String sw;
        if (isVideoApp) {
            boolean r = isKuaishou ? Config.enableKsRight : Config.enableBiliRight;
            boolean t = isKuaishou ? Config.enableKsTop : Config.enableBiliTop;
            boolean b = isKuaishou ? Config.enableKsBottom : Config.enableBiliBottom;
            boolean c = isKuaishou ? Config.enableKsCorner : Config.enableBiliCorner;
            sw = bit(master) + bit(r) + bit(t) + bit(b) + bit(c);
        } else if (isDrama) {
            sw = bit(Config.enableStatus) + bit(Config.enableDramaTop) + bit(Config.enableDramaLike)
                    + bit(Config.enableDramaRt) + bit(Config.enableDramaLt)
                    + bit(Config.enableDramaSwipe);
        } else {
            sw = bit(Config.enableStatus) + bit(Config.enableDouyinTop) + bit(Config.enableDouyinBottom)
                    + bit(Config.enableDouyinLike) + bit(Config.enableDouyinRt)
                    + bit(Config.enableDouyinLt) + bit(Config.enableLive);
        }
        // 几何识别命中数：区域开关开了却扫不到容器，就是「失效」最常见的下一环
        String hit = "R=x" + rightList.size() + " T=" + (topBar != null ? 1 : 0)
                + " B=x" + bottomList.size() + " rt=" + (rightTop != null ? 1 : 0)
                + " lt=" + (leftTop != null ? 1 : 0) + " plus=x" + centerPlusList.size();
        String sig = " app=" + VideoApps.label(VideoApps.of(selfPkg)) + " pkg=" + selfPkg
                + " ver=" + BuildConfig.VERSION_NAME
                + " eng=" + Config.engine + " ov=" + overlay
                + " lm=" + Config.listMode + " itv=" + itv
                + " status=" + Config.enableStatus + " sw=" + sw
                + " " + hit
                + " hid=" + hidden + " cyc=" + Config.hideSec + "/" + Config.showSec
                + " live=" + livePaused + " vlive=" + videoLivePage;
        if (sig.equals(lastHookSig) && now - lastHookDiagAt < 30_000L) {
            return;
        }
        lastHookSig = sig;
        lastHookDiagAt = now;
        XposedInit.diagForce("HOOK" + sig);
    }

    private static String bit(boolean v) {
        return v ? "1" : "0";
    }

    private static void tick() {
        // 宿主 Context 供 ConfigProvider 跨进程读配置使用
        if (currentDecor != null) {
            Config.attach(currentDecor.getContext());
        }
        Config.reload();
        // v2.8.0：应用内分区隐藏此前是排障盲区 —— 所有状态只走受 enableLog 控制的
        // diag()（默认关），视频进程又常拿不到 su，导出的日志里连一条都没有。
        // 这里无条件落盘一条带「引擎/名单/开关/各区域命中数/隐藏相位」的快照，
        // 签名变化或 30s 心跳才写，既照亮盲区又不刷屏。
        hookDiag();

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
        // v2.6.6：引导条视图随页面重建失效，清掉避免持有旧 View
        Iterator<View> isw = swipeHintList.iterator();
        while (isw.hasNext()) {
            if (!isw.next().isAttachedToWindow()) {
                isw.remove();
            }
        }
        Iterator<View> icp = centerPlusList.iterator();
        while (icp.hasNext()) {
            if (!icp.next().isAttachedToWindow()) {
                icp.remove();
            }
        }

        // v2.7.3：退出直播的即时解除。rescan 有 2 秒节流，退出瞬间若没有新的布局事件，
        // livePaused 会多撑一个周期，表现为「出来了还没恢复」。这里用最近一次判定结果立刻清掉。
        if (livePaused && !liveStrongHit && !videoLivePage) {
            livePaused = false;
        }
        // v2.7.2：直播间期间整体让位。放在任何开关判定之前，保证「只要进了直播间就全不隐藏」，
        // 不会被某个区域开关的 OR 条件重新拉起。
        if (livePaused) {
            if (hidden) {
                showAll();
                hidden = false;
            }
            scheduleCycle(1000);
            return;
        }

        // 红果进程必须用红果开关判断；快手 / B站 用「总开关 && 任一区域开关 && 识别到容器」，
        // 且遮盖引擎下整体让位（它盖不了 App 内控件，Hook 动手只会露出不一致的边）。
        boolean anyEnabled;
        if (isVideoApp) {
            boolean master = isKuaishou ? Config.enableKuaishou : Config.enableBilibili;
            boolean overlay = ConfStore.ENGINE_OVERLAY.equals(Config.engine);
            // v2.6.9：App 内区域隐藏与 SystemUI 侧共用名单 / 总开关 / 引擎判定真源。
            // 白名单命中或直播特征页一律整页跳过（弹幕输入框 / 礼物栏不误伤）。
            boolean allowed = Config.shouldIntervene(selfPkg) && !videoLivePage;
            boolean mR = isKuaishou ? Config.enableKsRight : Config.enableBiliRight;
            boolean mT = isKuaishou ? Config.enableKsTop : Config.enableBiliTop;
            boolean mB = isKuaishou ? Config.enableKsBottom : Config.enableBiliBottom;
            boolean mC = isKuaishou ? Config.enableKsCorner : Config.enableBiliCorner;
            anyEnabled = !overlay && allowed && master && (
                    (mR && !rightList.isEmpty())
                    || (mT && topBar != null)
                    || (mB && (!bottomList.isEmpty() || !centerPlusList.isEmpty()))
                    || (mC && (rightTop != null || leftTop != null)));
        } else if (isDrama) {
            // v2.7：红果底部是「完整观看/选集」功能入口，hideEnabled 里强制常驻不隐藏，
            // enableDramaBottom 也没有可点开的 UI（恒 false）。旧版却把它算进 anyEnabled，
            // 于是 qualifies 了一个根本不会隐藏的区域，白白跑完整周期。这里移除。
            anyEnabled = (Config.enableDramaLike && !rightList.isEmpty())
                    || (Config.enableDramaTop && topBar != null)
                    || (Config.enableDramaRt && rightTop != null)
                    || (Config.enableDramaLt && leftTop != null)
                    || (Config.enableDramaSwipe && !swipeHintList.isEmpty());
        } else {
            anyEnabled = (Config.enableDouyinLike && !rightList.isEmpty())
                    || (Config.enableDouyinTop && topBar != null)
                    || (Config.enableDouyinBottom && !bottomList.isEmpty())
                    || (Config.enableDouyinRt && rightTop != null)
                    || (Config.enableDouyinLt && leftTop != null)
                    // 只开「直播间隐藏」时也必须能进入隐藏窗口，否则该开关完全不生效。
                    // v2.7：与 hideEnabled 共用 rescan 提交的唯一判定，不再另用 isLiveStrong。
                    || (Config.enableLive && liveDetected);
        }

        // v2.7：总开关（防烧屏保护）是总闸。SystemUI 侧 Controller 早已遵守，
        // 但抖音/红果进程此前漏了这一条 —— 总开关关闭后，视频页各区域仍会照常周期隐藏。
        // 与 Controller.tick() 的暂停条件保持一致。
        if (!Config.enableStatus || !isScreenOn() || !anyEnabled) {
            if (hidden) {
                showAll();
                hidden = false;
            }
            scheduleCycle(3000);
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
        scheduleCycle(Math.min(2000, Math.max(100, remain)));
    }

    private static void hideEnabled() {
        // 悬浮球"强制显示"模式：不隐藏任何控件（用于查看各区域控件是否被正确隐藏）
        if (forceShow) {
            showAll();
            return;
        }
        // v2.7.2：直播间整体暂停。布局监听在直播页仍会触发 hideEnabled，
        // 这里必须再挡一次，否则刚恢复的区域会被下一次布局变化重新压掉。
        if (livePaused) {
            showAll();
            return;
        }
        // v2.7：直播三区只受「直播间隐藏」开关控制，独立判断，不再短路掉普通区域。
        // 旧版一旦 isLive() 命中，enableLive=false 就 showAll() 全盘恢复并 return，
        // 会把用户已开启的顶部/右侧等普通区域开关一起否决；判定又依赖 rightTop 的
        // RoundCornerFrameLayout（普通视频页也可能命中），误判后整个区域隐藏失效。
        // 现在两套开关彻底解耦：直播三区按 enableLive 隐藏/恢复，普通区域照常按各自开关。
        final boolean liveHide = liveDetected && !isDrama && !isVideoApp && Config.enableLive;
        for (View v : liveTopList) {
            if (liveHide) transparentDeep(v, "livetop"); else showDeep(v);
        }
        for (View v : liveBottomList) {
            if (liveHide) transparentDeep(v, "livebottom"); else showDeep(v);
        }
        for (View v : liveRightList) {
            if (liveHide) transparentDeep(v, "liveright"); else showDeep(v);
        }
        // 红果短剧用红果独立开关，抖音用抖音开关，快手 / B站 用视频区域开关 + 各自总开关。
        // 复用同一套几何容器：rightList=右侧互动列，topBar=顶部工具栏，
        // bottomList=底部操作栏，rightTop/leftTop=角落按钮。不另写未标定的识别规则。
        boolean dLike, dTop, dBot, dRt, dLt;
        if (isVideoApp) {
            boolean on = (isKuaishou ? Config.enableKuaishou : Config.enableBilibili)
                    && !ConfStore.ENGINE_OVERLAY.equals(Config.engine)
                    && !videoLivePage && Config.shouldIntervene(selfPkg);
            dLike = on && (isKuaishou ? Config.enableKsRight : Config.enableBiliRight);
            dTop = on && (isKuaishou ? Config.enableKsTop : Config.enableBiliTop);
            dBot = on && (isKuaishou ? Config.enableKsBottom : Config.enableBiliBottom);
            dRt = on && (isKuaishou ? Config.enableKsCorner : Config.enableBiliCorner);
            dLt = dRt;
        } else {
            dLike = isDrama ? Config.enableDramaLike : Config.enableDouyinLike;
            dTop = isDrama ? Config.enableDramaTop : Config.enableDouyinTop;
            dBot = isDrama ? Config.enableDramaBottom : Config.enableDouyinBottom;
            dRt = isDrama ? Config.enableDramaRt : Config.enableDouyinRt;
            dLt = isDrama ? Config.enableDramaLt : Config.enableDouyinLt;
        }
        boolean dSwipe = isDrama && Config.enableDramaSwipe;
        // 开关关的区域立即恢复（不等到显示窗口），避免"明明关了导航栏还是消失"
        if (dLike) {
            for (View v : rightList) {
                hideSafe(v, "right");
            }
        } else {
            for (View v : rightList) {
                showDeep(v);
            }
        }
        if (dTop) {
            if (topBar != null) {
                hideSafe(topBar, "top");
            }
        } else if (topBar != null) {
            showDeep(topBar);
        }
        // 底部栏：红果短剧剧集页底部是"完整观看/选集"等可点击功能入口，必须常驻可点，
        // 不能当固定导航条透明掉（否则入口消失点不进去）；故红果始终不隐藏底部，只做恢复。
        // 抖音首页/视频页底部导航仍按开关隐藏。
        // 红果底部是剧集/选集入口，必须常驻；抖音与快手 / B站 的底栏按各自开关隐藏。
        boolean hideBottom = !isDrama && dBot;
        if (hideBottom) {
            for (View v : bottomList) {
                hideSafe(v, "bottom");
            }
        } else {
            for (View v : bottomList) {
                showDeep(v);
            }
        }
        // 直播间右上角（RoundCornerFrameLayout 强特征）原本无条件隐藏；解耦后由
        // 「直播间隐藏」或「右上角」任一开关控制，两个开关互不否决。
        if (dRt || liveHide) {
            if (rightTop != null) {
                hideSafe(rightTop, "righttop");
            }
        } else if (rightTop != null) {
            showDeep(rightTop);
        }
        if (dLt) {
            if (leftTop != null) {
                hideSafe(leftTop, "lefttop");
            }
        } else if (leftTop != null) {
            showDeep(leftTop);
        }
        // v2.6.9：快手 / B站 底部中央「+」，归入「底部操作栏」开关；关掉立即恢复。
        for (View v : centerPlusList) {
            if (isVideoApp && hideBottom) {
                hideSafe(v, "plus");
            } else {
                showDeep(v);
            }
        }
        // v2.6.6：红果「左滑查看详情」竖排引导条。开关关掉时立即恢复，
        // 不等显示相位结束 —— 与上面其它区域保持一致。
        for (View v : swipeHintList) {
            if (dSwipe) {
                transparentDeep(v, "swipe");
            } else {
                showDeep(v);
            }
        }
    }

    /**
     * 深度透明（含第二重保险）：容器 setAlpha(0) + setVisibility(INVISIBLE)。
     * INVISIBLE 保留空间（不破坏布局/触摸区），强制整棵子树不绘制——
     * 即使容器 height=0、或 tab 子项越界显示，也能彻底藏掉。比 setAlpha 可靠。
     */
    private static void transparentDeep(View v, String what) {
        if (v == null) {
            return;
        }
        // v2.7.3：单个 view 失效（已 detach / 宿主校验）不得中断整轮隐藏，
        // 否则会连带跳过后续所有区域，并让周期调度走异常分支。
        try {
            if (v.getVisibility() != View.GONE && v.getAlpha() != 0f) {
                v.setAlpha(0f);
                Log.i(TAG, what + " hidden: " + desc(v));
            }
            if (v.getVisibility() != View.INVISIBLE && v.getVisibility() != View.GONE) {
                v.setVisibility(View.INVISIBLE);
            }
        } catch (Throwable t) {
            Log.e(TAG, "hide failed: " + what, t);
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
        for (View v : swipeHintList) {
            showDeep(v);
        }
        for (View v : centerPlusList) {
            showDeep(v);
        }
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
        // v2.7.3：恢复路径同样单点兜底。showAll() 会在进/退直播时被频繁调用，
        // 这里一旦抛出，整轮 tick 中断，正是「退出直播后再也不隐藏」的直接触发点。
        try {
            if (v.getVisibility() != View.VISIBLE) {
                v.setVisibility(View.VISIBLE);
            }
            if (v.getAlpha() != 1f) {
                v.setAlpha(1f);
                Log.i(TAG, "shown: " + desc(v));
            }
        } catch (Throwable t) {
            Log.e(TAG, "show failed", t);
        }
    }

    // ---------- 红果「左滑查看详情」竖排引导条（v2.6.6，按文案识别） ----------
    // 播放页右侧中部常驻一条竖排「左滑查看详情 «」半透明白字，长期静止、易烧屏。
    // 它不属于顶部/底部/右侧按钮列/角落按钮任何一类几何区域，只能按文案定位。
    // 只在红果进程（isDrama）收集；抖音里也有「查看详情」字样，收了会误伤。

    /** 归一化：竖排/逐字/带间隔的实现都折叠成连续汉字，便于比关键词 */
    private static String normText(CharSequence cs) {
        return cs == null ? "" : cs.toString().replace('\u3000', ' ').replaceAll("\\s+", "");
    }

    private static boolean swipeTextMatch(CharSequence cs) {
        String s = normText(cs);
        if (s.isEmpty()) {
            return false;
        }
        return s.contains("查看详情") || s.contains("左滑查看")
                || (s.contains("左滑") && s.contains("详情"));
    }

    /** 逐字竖排兜底：直接子 TextView 拼起来构成引导文案 */
    private static boolean swipeVerticalText(ViewGroup g) {
        StringBuilder sb = new StringBuilder();
        int tv = 0;
        for (int i = 0; i < g.getChildCount() && i < 32; i++) {
            View c = g.getChildAt(i);
            if (c instanceof TextView) {
                sb.append(normText(((TextView) c).getText()));
                sb.append(normText(c.getContentDescription()));
                tv++;
            }
        }
        return tv >= 2 && swipeTextMatch(sb.toString());
    }

    /** 位置约束：只认屏幕右侧中部的竖条，别把页面别处的「查看详情」按钮一起藏掉 */
    private static boolean swipePosOk(View v) {
        int[] l = loc(v);
        int w = screenW(v);
        int h = screenH(v);
        if (l == null || w <= 0 || h <= 0) {
            return false;
        }
        return l[0] >= w * 0.55f && l[1] >= h * 0.12f && l[1] <= h * 0.88f;
    }

    /**
     * 选隐藏目标：从命中 View 向上并进窄容器（连「«」箭头一起藏），
     * 一旦容器变大、含图标按钮或含视频画面就停，绝不吞掉整页 / 视频层。
     */
    private static View swipeTarget(View v) {
        View best = v;
        ViewParent p = v.getParent();
        for (int up = 0; up < 3 && p instanceof View; up++) {
            View c = (View) p;
            if (c.getWidth() > screenW(c) * 0.35f || c.getHeight() > screenH(c) * 0.5f) {
                break;
            }
            if (containsVideoSurface(c, 0) || containsDynamicUi(c, 0)) {
                break;
            }
            if (c instanceof ViewGroup && iconCount((ViewGroup) c) > 0) {
                break; // 别把点赞列一起吞了，它有自己的开关
            }
            best = c;
            p = c.getParent();
        }
        return best;
    }

    private static void collectSwipeHintNode(View v, List<View> out, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return;
        }
        if (v.getVisibility() == View.GONE) {
            return; // GONE 子树不绘制，跳过省节点数
        }
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if ((swipeTextMatch(tv.getText()) || swipeTextMatch(tv.getContentDescription()))
                    && swipePosOk(tv)) {
                View t = swipeTarget(tv);
                if (!out.contains(t)) {
                    out.add(t);
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (g.getWidth() > 0 && g.getWidth() < screenW(g) * 0.28f
                    && swipePosOk(g) && swipeVerticalText(g)
                    && !containsVideoSurface(g, 0)) {
                View t = swipeTarget(g);
                if (!out.contains(t)) {
                    out.add(t);
                }
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                collectSwipeHintNode(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    // ---------- v2.6.9：视频 App 隐藏前的内容安全校验 ----------

    /**
     * 抖音专属的几何打分套到结构不同的 App 上，会把顶部横幅 / 推荐卡片网格 /
     * 视频信息区误判成固定栏；一旦对整棵子树 INVISIBLE，用户看到的就是大片内容
     * 周期性消失。这里只做通用结构判定，不写任何 App 专属阈值：宁可漏藏，也绝不误伤内容。
     */
    private static boolean safeToHide(View v, String what) {
        if (v == null) {
            return false;
        }
        if (containsVideoSurface(v, 0)) {
            return false; // 视频画面不能黑屏
        }
        int w = screenW(v), h = screenH(v);
        if (w <= 0 || h <= 0 || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        // v2.7.0：形状限定式 —— 固定栏只可能是「窄竖条 / 矮横条 / 小控件」之一，
        // 首页信息流 / 推荐网格 / 视频详情区这类大块内容容器天然不满足形状约束。
        // v2.6.9 的排除式护栏（动态 UI / 子节点数 / 大容器）把真正的固定栏也拦了，
        // 造成「只有加号生效、其它全部失效」—— 详见类头 v2.7.0 注释。
        float rw = (float) v.getWidth() / w;
        float rh = (float) v.getHeight() / h;
        if ("right".equals(what)) {
            // v2.7.2：快手右侧互动列只有赞 / 评论 / 分享三个按钮，整列高度约屏高 20%，
            // 旧阈值 25% 把快手整列判成「不够高」并 showDeep 恢复，真机表现就是
            // 「B站右侧生效、快手右侧永远不隐藏」。高度下限放到 8%（约两个按钮），
            // 宽度仍限 30% —— 内容卡片、信息流容器远宽于此，不会被误伤。
            return rw < 0.30f && rh > 0.08f;
        }
        if ("top".equals(what) || "bottom".equals(what) || "plus".equals(what)) {
            return rh < 0.18f; // 顶部 / 底部横条必须矮
        }
        // v2.7.2：角落按钮容器在自绘 / Compose 页可能是一条竖排工具条，高度超过 20%，
        // 旧阈值把左上三横线、右上搜索整块恢复。上限放到 35%，仍远小于内容容器。
        return rw < 0.35f && rh < 0.35f; // 角落按钮：小控件
    }

    /** 只对快手 / B站 加安全校验；抖音 / 红果沿用已真机验证的行为，一道不改。 */
    private static void hideSafe(View v, String what) {
        if (v == null) {
            return;
        }
        if (isVideoApp && !safeToHide(v, what)) {
            showDeep(v);
            return;
        }
        transparentDeep(v, what);
    }

    // ---------- v2.6.9：直播特征页（只用于保守跳过，不触发隐藏） ----------

    private static boolean liveTextMatch(CharSequence cs) {
        String s = normText(cs);
        if (s.isEmpty() || s.length() > 16) {
            return false;
        }
        String up = s.toUpperCase();
        return s.contains("直播中") || s.contains("正在直播") || s.contains("直播间")
                || s.equals("直播") || up.contains("LIVE");
    }

    private static boolean isLiveTextPage(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return false;
        }
        if (v.getVisibility() == View.GONE) {
            return false;
        }
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (liveTextMatch(tv.getText()) || liveTextMatch(tv.getContentDescription())) {
                return true;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (isLiveTextPage(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------- v2.6.9：底部中央独立圆形「+」发布按钮 ----------

    private static boolean plusPosOk(View v) {
        int[] l = loc(v);
        int w = screenW(v), h = screenH(v);
        if (l == null || w <= 0 || h <= 0) {
            return false;
        }
        int cx = l[0] + v.getWidth() / 2;
        int cy = l[1] + v.getHeight() / 2;
        return cx >= w * 0.32f && cx <= w * 0.68f && cy >= h * 0.80f;
    }

    private static boolean plusShapeOk(View v) {
        int bw = v.getWidth(), bh = v.getHeight();
        if (bw < 48 || bh < 48 || bw > 260 || bh > 260) {
            return false;
        }
        float r = (float) bw / Math.max(1, bh);
        return r >= 0.65f && r <= 1.55f;
    }

    private static void collectCenterPlusNode(View v, List<View> out, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return;
        }
        if (v.getVisibility() == View.GONE) {
            return;
        }
        if (plusPosOk(v) && plusShapeOk(v)
                && (v.isClickable() || v instanceof ImageView || v instanceof android.widget.ImageButton)
                && !containsVideoSurface(v, 0) && !containsDynamicUi(v, 0)) {
            View t = plusTarget(v);
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectCenterPlusNode(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** 向上并进窄容器，把「+」外圈一起藏；容器变大或含视频画面 / 动态内容就停。 */
    private static View plusTarget(View v) {
        View best = v;
        ViewParent p = v.getParent();
        for (int up = 0; up < 2 && p instanceof View; up++) {
            View c = (View) p;
            if (c.getWidth() > screenW(c) * 0.3f || c.getHeight() > screenH(c) * 0.12f) {
                break;
            }
            if (containsVideoSurface(c, 0) || containsDynamicUi(c, 0)) {
                break;
            }
            best = c;
            p = c.getParent();
        }
        return best;
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
                        // v2.5：decor 不一定是 FrameLayout，旧版在 OnTouch 回调里
                        // 直接强转会崩抖音（不在 ensureBall 的 try 内）。
                        ViewGroup.LayoutParams lp = v.getLayoutParams();
                        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                            android.widget.FrameLayout.LayoutParams sp =
                                    (android.widget.FrameLayout.LayoutParams) lp;
                            start[0] = sp.leftMargin;
                            start[1] = sp.topMargin;
                        } else {
                            start[0] = 0;
                            start[1] = 0;
                        }
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
