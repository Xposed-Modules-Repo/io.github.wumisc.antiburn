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
 *
 * v2.9.6：红果付费页「付费抢先看」弹窗常驻隐藏（编译期常量 DRAMA_PAYWALL_HIDE）。
 * 原因是弹窗挡住短剧画面，用户要「一直别显示」而非防烧屏周期显隐，故不进
 * anyEnabled / hideEnabled() / showAll() 周期链路；由 onResume / onGlobalLayout
 * 事件驱动（tick() 仅在它本来就在跑时补一路）单独自扫自恢复自隐藏，绝不启动周期调度；
 * 只把遮挡层 alpha=0 + INVISIBLE，绝不点按、不解锁播放（隐藏提示 ≠ 解锁播放）。
 * 仅 isDrama 生效，且受总开关与亮屏约束；常量改 false 即完全停用并恢复。
 *
 * v2.9.7：修复全局恢复路径越界强改宿主状态（台账 #4）。showDeep() 旧版把宿主
 * 自设 GONE 点亮成 VISIBLE、把宿主半透明 alpha 拉平成 1f，且首行无条件调的
 * resetShift() 又重复一遍；本模块隐藏态恒为 INVISIBLE+alpha0，故 v2.9.7 用
 * 编译期常量 SAFE_RESTORE 收紧为「只撤销这两种写入」+ 专用 resetTranslationOnly()。
 * 相对 v2.9.6 严格收窄（只会少点亮），DPH / 五区 / 直播三区 / 开关关 / 悬浮球
 * 全部复用同一 showDeep()，零回归；SAFE_RESTORE=false 即一键回退。
 *
 * v2.9.6 R5：强词门槛从「整屏」下沉到「单个候选容器子树内」。旧版只要全屏任意一处
 * 命中强词就给全屏所有 ≥2 命中的候选开绿灯，同屏仅含两个弱词的活动横幅会被误藏；
 * 现在每个候选必须自己子树内含 ≥1 个强词节点才允许隐藏。诊断补 hits / rej，
 * 让真机日志能区分「没弹窗」与「被 55% 上限 / 强词门槛挡掉」。
 */
public final class DouyinHook {

    private static final String TAG = XposedInit.TAG + "DY";

    private static final List<View> rightList = new ArrayList<>();  // 右侧竖排按钮组（可能多个）
    private static final List<View> bottomList = new ArrayList<>(); // 底部贴底横条（可能多个，递归隐藏）
    private static final List<View> swipeHintList = new ArrayList<>(); // 红果「左滑查看详情」竖排引导条（按文案识别）
    /** v2.6.9：快手 / B站 底部中央独立「+」发布按钮（不属于五类几何区域，按形状 + 位置单独收集） */
    private static final List<View> centerPlusList = new ArrayList<>();
    /** v3.0.6 P2：红点强迫症模式候选列表。黑名单先行 + 白名单精确放行，默认拒绝。 */
    private static final List<View> redDotList = new ArrayList<>();
    /** v3.0.6 P3：下方标题栏候选列表。三 App 精确三元锚点，只收叶子 TextView。 */
    private static final List<View> captionList = new ArrayList<>();
    // ---------- v3.0.7：定向隐藏通道 THH（Targeted Hide Channel，纯加性）----------
    // 与 livePaused / anyEnabled / hideEnabled / showAll 完全并行；只读直播状态当门控，
    // 不写 livePaused、不进相位调度、不进 showAll。四列表各自门控、互不串台。
    private static final List<View> ksLiveTargetList = new ArrayList<>();   // P1 快手直播间
    private static final List<View> dyLiveTargetList = new ArrayList<>();   // P2 抖音直播间
    private static final List<View> ksFeedTargetList = new ArrayList<>();   // P3 快手普通刷视频
    private static final List<View> dramaTargetList = new ArrayList<>();    // P4 红果信息区 + 底部导航
    // THH slot 常量（禁止魔法值）
    private static final int SLOT_KS_LIVE = 0;
    private static final int SLOT_DY_LIVE = 1;
    private static final int SLOT_KS_FEED = 2;
    private static final int SLOT_DRAMA = 3;
    // P5 只读探针：THH dump / 诊断的 30s 节流状态
    private static long lastThhDumpAt = 0L;
    private static long lastThhDiagAt = 0L;
    private static String lastThhDiagSig = "";
    // ---------- v3.0.8 P1：抖音直播间 THH 独立迟滞门控（纯加性，只读 livePaused 组）----------
    private static final ThhLiveGate thhLiveGate = new ThhLiveGate();
    private static volatile boolean lastThhDyStableLogged = false; // 仅用于 ENTER/EXIT 强制诊断的边沿检测
    private static long lastLiveUiDumpAt = 0L;                     // P3 新增只读 dump 的 30s 节流
    /** 纯数字互动计数（719 / 0/20 / 238/2425 / 3/62）一票否决，命中即永不入 THH 列表。 */
    private static final java.util.regex.Pattern THH_COUNT_PATTERN =
            java.util.regex.Pattern.compile("[0-9]+([./][0-9]+)*");
    // 文案锚点关键词全部来自 PRD 四图；泛词（第/季/全/集）刻意不收，避免误伤（评审 T2）
    private static final String[] KS_LIVE_KEYS = {"热门职位榜", "热门榜", "职位榜", "招普工",
            "查简历", "抽大奖", "联系主播", "更多招聘", "查看并复制", "收藏职位",
            "在线咨询", "沟通咨询方"};
    private static final String[] KS_RECRUIT_KEYS = {"月薪", "经验不限", "学历不限", "性别不限", "18-55"};
    private static final String[] DY_LIVE_KEYS = {"人气榜", "本场点赞", "点赞榜", "直播广场", "关注主播"};
    private static final String[] DY_FOLLOW_KEYS = {"关注", "已关注"};
    // v3.0.11 P0a：全屏浏览入口是可点击功能控件，不作提示隐藏；由 isClickableControl 兜底。
    private static final String[] KS_FEED_KEYS = {"含AI生成内容", "内容由AI生成"};
    private static final String[] DRAMA_KEYS = {"弹幕", "静音", "全屏观看", "系列剧", "已上线",
            "人在追", "权力斗争", "打脸反派", "作者声明", "内容由AI生成", "观看完整漫剧"};
    /** 红果底部导航三锚 AND（宪法 §3 唯一例外，已在 changes/ 留批准记录）。 */
    private static final String[] DRAMA_NAV_KEYS = {"首页", "剧场", "我的"};
    /** v2.6.9：当前页命中直播文本特征。命中则视频 App 整页跳过 App 内区域隐藏，避免误伤弹幕 / 礼物栏。 */
    private static volatile boolean videoLivePage = false;
    /**
     * v3.0.26：当前页面是个人主页 / 作品列表页（整页放行）。
     *
     * <p>真机反馈：在他人主页往下翻作品时连续出现白屏，三块白正对应三个作品。
     * 主页不是全屏播放页，作品封面是普通 ImageView，被几何规则当成「固定控件」
     * 透明化后会露出页面白底 —— 模块的防烧屏收益在这个页面接近于零，直接放行最稳。</p>
     */
    private static volatile boolean profilePage = false;
    /** v3.0.26：最近一次 rescan 的根视图，hideEnabled 复用它做主页判断，避免每 tick 全树扫。 */
    private static volatile View lastRoot;
    /** v2.6.9：宿主进程自身包名，供 shouldIntervene 判定白名单豁免。 */
    private static volatile String selfPkg = "";
    private static volatile View topBar;       // 顶部导航
    private static volatile View rightTop;     // 右上角操作按钮
    private static volatile View leftTop;      // 左上角菜单按钮
    private static volatile View currentDecor; // 当前根视图（用于周期强制重扫）
    private static boolean started = false;
    private static boolean hidden = false;
    /** 当前相位（隐藏 / 显示）的起始时刻，用于 v2.9.0 恢复的周期真隐藏。 */
    private static long phaseAt = 0L;

    // ---------- v2.8.4：App 内固定区域改为「位置微移」而非透明隐藏 ----------
    /**
     * 偏移步长（px）。固定 UI 长期压在同一批像素上才会烧屏，把它整体挪 2~3px
     * 就足以让轮廓落在相邻像素上，画面本身完整可见 —— 这是真机上唯一
     * 「既看得见又能防烧屏」的形态，也是用户明确要求的形态。
     *
     * <p>九宫格绕圈（不含中心），每 8 秒一步，一个循环 64 秒。任何时刻都不会
     * 回到原点停留，除非进入直播间或强制显示 —— 那时必须还原，因为直播间的
     * 礼物栏 / 弹幕输入框位置是动态的，偏移会造成错位。</p>
     */
    private static final int[] SHIFT_DX = {-3, -2, 0, 2, 3, 2, 0, -2};
    private static final int[] SHIFT_DY = {0, 2, 3, 2, 0, -2, -3, -2};
    private static final long SHIFT_STEP_MS = 8000L;

    /**
     * v2.9.2：直播间隐藏总闸（编译期常量，默认断开）。
     *
     * <p>v2.9.1 把「进直播间就整页让位」改成了「只藏直播三区」，真机后果是：
     * 直播间判定一旦在普通页误命中，{@code tick()} 与 {@code hideEnabled()}
     * 都会整轮走进直播分支，普通页的顶部/底部/右侧全部不再隐藏 ——
     * 抖音导航栏、点赞收藏评论集体失效，悬浮球点按也看不出任何变化
     * （它切换的正是同一套区域）。</p>
     *
     * <p>v2.9.2 / 2.9.3 曾把这条通道用编译期常量断开，让抖音普通页先稳下来
     * （代价是直播间一个控件都不藏）。v2.9.4 用户明确要求「单独修直播间」，
     * 现在重新启用，但换了一套几乎不会误判的判定：
     * <b>底部必须有「说点什么」输入框，且没有首页 MainBottomTabContainer</b>。
     * 普通视频页底部是 @作者 / 标题 / 相关搜索，两者不可能同时成立，
     * 所以普通页的五区隐藏一行都不用改、也不会被直播判定带崩。</p>
     */
    private static final boolean LIVE_ROOM_HIDE = true;

    /**
     * v2.9.6：红果「付费抢先看」弹窗常驻隐藏总闸（编译期常量）。
     * 改成 false 即整条通道停用：下一次 DPH 调用（onResume / 布局事件 / tick）
     * 会把已隐藏容器全部恢复并清空列表，不需要动其它任何一行。
     * 默认 true：用户要的是弹窗一直别挡着看短剧。
     */
    private static final boolean DRAMA_PAYWALL_HIDE = true;

    /**
     * v2.9.7（台账 #4）：恢复路径「只撤销本模块造成的隐藏态」总闸（编译期常量）。
     *
     * <p>旧版 {@code showDeep()} 恢复时越界强改宿主：把任何「非 VISIBLE」（含宿主自设
     * {@code GONE}）拉成 VISIBLE、把任何「非 1f 的 alpha」（含宿主半透明）拉成 1f，
     * 且首行无条件调的 {@code resetShift()} 里又重复一遍。本模块隐藏
     * {@link #transparentDeep} 从不设 {@code GONE}，藏态恒为 {@code INVISIBLE+alpha0}，
     * 因此这些越界写入只会把宿主本不显示的层点亮（真机「主播离开了」提示乱跳一类）。</p>
     *
     * <p>true：只把 {@code INVISIBLE→VISIBLE}、{@code alpha0→1f}，translation 归零另用
     * 不碰 alpha/visibility 的 {@link #resetTranslationOnly}；相对 v2.9.6 严格收窄
     * （只会少点亮，绝不多点亮）。false：逐字节回到 v2.9.6 恢复语义，一键回退。</p>
     *
     * <p>DPH 复用 {@code showDeep()}，故红果付费弹窗「红果挺好的」一并受益、零回归；
     * {@code resetShift()} / {@code applyShift()} 本体另有 5 处调用，一律不动。</p>
     */
    private static final boolean SAFE_RESTORE = true;

    /** v2.9.6：上一轮被常驻隐藏的弹窗容器。独立于五区 / 直播三区，showAll() 不遍历它。 */
    private static final List<View> dramaPaywallList = new ArrayList<>();
    /** v2.9.6：DPH 诊断只记上一次状态，状态变化才落盘一条，避免每轮刷屏。 */
    private static volatile int dphLastN = -1;
    /** v2.9.6 R5：上一次 DPH 诊断的命中节点数 / 被拒候选数，与 dphLastN 组成状态签名。 */
    private static volatile int dphLastHits = -1;
    private static volatile int dphLastRej = -1;
    /** v2.9.6：DPH 扫描节流间隔。tick 与 onResume/onGlobalLayout 双路径共用，避免重复全树遍历。 */
    private static final long DPH_SCAN_INTERVAL_MS = 400L;
    /** v2.9.6：最近一次真正执行扫描的时刻（elapsedRealtime），0 = 尚未扫过。 */
    private static volatile long lastDphScanAt = 0L;

    /** 悬浮球自带标记：任何区域收集 / 隐藏都必须跳过它，否则小球会被当成右侧按钮列藏掉。 */
    private static final String BALL_TAG = "antiburn.floating.ball";
    private static volatile int shiftDx = 0;
    private static volatile int shiftDy = 0;
    private static volatile long shiftStepAt = 0L;
    private static boolean isDrama = false; // 红果短剧（com.phoenix.read 系）进程：用红果独立开关
    /** v2.6.8：快手 / B站 进程。复用同一套已验证的几何识别骨架，用视频区域开关 + 各自总开关。 */
    private static volatile boolean isVideoApp = false;
    private static volatile boolean isKuaishou = false;
    /** v2.8.0：显式记录 B站进程（含 com.bilibili.app.* 变体），不靠「非快手即 B站」的隐含推断。 */
    private static volatile boolean isBilibili = false;
    /** v2.8.0：视频进程状态诊断的上一条签名与时间戳，做状态变化 + 心跳节流，避免刷屏。 */
    private static volatile String lastHookSig = "";
    /** v2.9.3：最近一次被形状校验否决的右侧几何（空 = 没有被否决过）。 */
    private static volatile String lastRightReject = "-";
    /** v3.0.4：最近一次 right 候选的最终判定，纯诊断副作用，不参与任何隐藏决策。 */
    private static volatile String ksRightAllow = "-";
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
    /** v2.9.4：最近一次 rescan 是否看到直播间底部输入条（硬结构闸结果）。 */
    private static volatile boolean liveInputSeen = false;
    /** v2.9.4：上一轮直播间真正隐藏掉的容器数（0 = 判定对了但一个都没藏）。 */
    private static volatile int liveHiddenCount = 0;
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
    /** v3.0.14：拖到屏幕最下方松手后「收起」，球隐藏；下次打开/进入新页面时自动恢复。 */
    private static volatile boolean ballDocked = false;

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
                                // v2.9.6 R1：付费弹窗走事件驱动，不开周期。周期循环的唯一
                                // 入口在 rescan() 的 any（五区几何命中）里，纯浮层付费页
                                // 可能永远不启动 tick()，故 onResume 必须自己驱动一次 DPH。
                                if (isDrama) {
                                    updateDramaPaywallHide();
                                }
                                // v3.0.7：事件驱动补一路定向隐藏施加（不收集，只按门控施加已有列表）
                                updateTargetedHide(null);
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
                // v2.9.6 R1：付费弹窗事件驱动第二路。刻意放在下面 800ms 节流之前：
                // tick() 可能从未启动（周期循环需五区几何命中），纯浮层付费页
                // 只能靠布局事件 + onResume 驱动；本方法自带 400ms 节流且恢复分支
                // 在节流之前，高频 layout 既不会重复全树扫描，也不会延迟恢复。
                // 绝不启动周期调度、不碰 started，也不改任何既有周期逻辑。
                if (isDrama) {
                    updateDramaPaywallHide();
                }
                // v3.0.7：布局事件补一路定向隐藏施加（不收集，只按门控施加已有列表）
                updateTargetedHide(null);
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
    /**
     * v2.8.5：资源 id 名 → id 值的缓存。
     *
     * <p>用 {@code getIdentifier("bottom_navigation", "id", 包名)} 而不是写死 0x7f090681：
     * 资源 id 数值每次发版都会变（B站 8.91.1 的 bottom_navigation 是 0x7f090681，
     * 下个版本多半不是），但<b>名字不会变</b>。名字查询一次即可，缓存住避免每轮扫描都反射。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> ID_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int idOfName(View root, String name) {
        Integer hit = ID_CACHE.get(name);
        if (hit != null) {
            return hit;
        }
        int id = 0;
        try {
            id = root.getResources().getIdentifier(name, "id", selfPkg);
        } catch (Throwable ignored) {
            // 资源系统异常按「没有这个 id」处理，走几何兜底
        }
        if (id != 0) {
            ID_CACHE.put(name, id);
        }
        return id;
    }

    /** 按 id 名在 root 子树里取控件；找不到 / 资源名不存在都返回 null。 */
    private static View byId(View root, String name) {
        if (root == null) {
            return null;
        }
        int id = idOfName(root, name);
        if (id == 0) {
            return null;
        }
        try {
            return root.findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void rescan(ViewGroup root) {
        try {
            // v3.0.26：主页判定随布局变化重算一次（hideEnabled 每 tick 只读结果）
            lastRoot = root;
            profilePage = !isVideoApp && isProfilePage(root);
            List<View> rights = collectRight(root, 4);
            // 补充兜底：贴右缘竖列（不限 childCount，防止个别视频把点赞列分组成小容器而漏藏）
            for (View e : collectRightEdge(root, 4)) {
                if (!rights.contains(e)) {
                    rights.add(e);
                }
            }
            // v2.9.3：快手 / B站 的右侧互动列常被包在一个「铺满屏幕宽度」的浮层容器里，
            // 按钮靠右对齐（gravity=end / Compose Arrangement.End）。这种结构下容器
            // loc[0]≈0、宽≈全屏，scoreRight 的「起点必须在屏幕 55% 右侧」与
            // scoreRightEdge 的「78%」两条规则全都命不中，rightList 恒为空 ——
            // 真机表现正是「快手导航栏能藏、右侧点赞评论一直不动」，B站同理。
            // 这里补一条「右缘图标列」兜底：同一父容器里竖向排列、贴右边的两枚以上
            // 小控件逐个收集。只对快手 / B站 生效，抖音 / 红果 一行不改。
            if (isVideoApp) {
                List<View> icons = new ArrayList<>();
                collectRightIcons(root, icons, 0);
                for (View v : icons) {
                    if (!rights.contains(v)) {
                        rights.add(v);
                    }
                }
            }
            View t = scan(root, DouyinHook::scoreTop);
            List<View> bottoms = collectBottom(root);
            // v2.8.5：B站资源 id 锚点（反编译 tv.danmaku.bili 8.91.1 实证）。
            // 主界面布局 res/6R5.xml 的实际结构：
            //   CoordinatorLayout(id=coordinatorLayout)
            //     ├ FrameLayout(id=main_top_layout) —— 顶部整块，含 ImageView(id=main_top_bg)
            //     ├ com.bilibili.lib.homepage.widget.TabHost(id=bottom_navigation)
            //     │     layout_gravity=bottom / height=wrap —— 就是底部 5 个 tab
            //     └ tv.danmaku.bili.ui.main2.widget.HomeAppBarLayout(id=app_bar)
            //           └ androidx.appcompat.widget.Toolbar(id=nav_top_bar) —— 顶部搜索栏
            // TabHost 是自绘 ViewGroup（子节点不是 ImageView，也不一定贴满宽度），
            // HomeAppBarLayout 被 AppBarLayout 的 behavior 托管、位置随滚动变化，
            // 两者都过不了 scoreTop / collectBottom 的几何门槛 —— 真机表现正是
            // 「B站只有右侧按钮列生效，顶部标题和底部导航不动」。
            // 抖音 / 红果的资源名被整体混淆（id 只剩 a / 0 / 00 这类），快手主界面 id
            // 也被抹掉，只有 B站 保留了语义化 id，所以这条锚点只对 B站 生效。
            if (isBilibili) {
                View nav = byId(root, "bottom_navigation");
                if (nav != null && nav.isShown() && nav.getWidth() > 0 && nav.getHeight() > 0) {
                    bottoms.clear();
                    bottoms.add(nav);
                }
                if (t == null) {
                    View bar = byId(root, "app_bar");
                    if (bar == null) {
                        bar = byId(root, "nav_top_bar");
                    }
                    if (bar != null && bar.isShown() && bar.getHeight() > 0) {
                        t = bar;
                    }
                }
            }
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
                // v2.9.3：文案命中还要过一道结构闸 —— 页面底部没有主 Tab 栏才算直播页。
                // 快手 / B站 的推荐流文案里出现「直播间 / 人看过」被扫到，旧版直接把
                // 整页判成直播，而它们的区域开关是 `on = … && !videoLivePage`，
                // 于是右侧 / 顶部 / 底部 / 角落一起失效，真机就是「一直没修好过」。
                boolean textHit = isLiveTextPage(root, 0);
                videoLivePage = textHit && findBottomDock(root, 0) == null;
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
                    // v2.9.3：换页时旧列表里的 View 必须先恢复再丢弃。
                    // 旧版直接 clear + addAll：上一页被藏掉的容器再没有任何引用，
                    // showAll() 也遍历不到它 —— 用户看到的是「翻回去那一块再也不出现」，
                    // 真机反馈的「看别的视频时导航栏突然消失」就是这条。
                    releaseAll(rightList, rights);
                    rightList.clear();
                    rightList.addAll(rights);
                    Log.i(TAG, "RIGHT x" + rightList.size() + " captured");
                    for (View v : rightList) {
                        Log.i(TAG, "  - " + desc(v));
                    }
                }
            } else if (!rightList.isEmpty()) {
                // v2.9.3：本轮一个右侧容器都没收到，说明当前页没有右侧列（换页 / 全屏
                // 直播间 / 详情页）。旧版不处理，rightList 会留着上一页的 View，而
                // tick 里的清理只看 isAttachedToWindow —— ViewPager 的邻页仍然 attached，
                // 于是隐藏动作全部打在「看不见的邻页」上，真正的列表从头到尾没进过这里。
                // 真机表现就是「日志里 R=x2、开关也开着，当前页右侧一动不动」。
                releaseAll(rightList, null);
                rightList.clear();
                Log.i(TAG, "RIGHT cleared (none found this round)");
            }
            if (t != null && t != topBar) {
                // v2.9.3：同上，被替换掉的旧顶部栏先恢复再丢引用
                showDeep(topBar);
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
                    releaseAll(bottomList, bottoms);
                    bottomList.clear();
                    bottomList.addAll(bottoms);
                    Log.i(TAG, "BOTTOM x" + bottomList.size() + " captured");
                    for (View v : bottomList) {
                        Log.i(TAG, "  - " + desc(v));
                    }
                }
            }
            if (rt != null && rt != rightTop) {
                showDeep(rightTop);
                rightTop = rt;
                Log.i(TAG, "RIGHTTOP CAPTURED: " + desc(rt));
            }
            if (lt != null && lt != leftTop) {
                showDeep(leftTop);
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
            // v3.0.3：只读可观测性探测（右侧列内部按钮 / 红点角标）。
            // 两个探测都只读取 View 属性并落日志，不改任何 View 状态、不碰区域收集列表。
            dumpRightIcons(root);
            dumpBadges(root);
            dumpBottomTitles(root);
            dumpLiveUi(root);        // v3.0.8 P3-b：只读，不参与任何收集/隐藏
            // v3.0.6 P2/P3：只读探针之后追加收集（探针自身保持只读，不改一行）
            updateRedDots(root);      // :703-705 三探针之后，直播间三区收集之前
            updateCaptions(root);
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
            // v2.9.2：整体暂停再加一道结构闸 —— 必须「底部没有首页 Tab」。
            // 抖音普通页一定带 MainBottomTabContainer；直播间是独立全屏 Activity，没有它。
            // 旧条件只看文案，一个「直播」tab 就能让普通页进入暂停态，代价是整个
            // 区域隐藏全停。现在结构优先、文案只作辅助。
            // v2.9.4：直播间判定换成「双结构闸」，彻底摆脱文案误判。
            // 必须同时满足：① 不是快手 / B站（它们不参与抖音直播三区）；
            // ② 底部没有首页 MainBottomTabContainer（普通页一定有，直播间一定没有）；
            // ③ 底部存在「说点什么」输入条，或文案判定命中（输入条识别不到时兜底）。
            // 普通视频页的 ② 必不成立 —— 无论推荐流文案里出现多少次「直播间」，
            // 都不可能进入直播路径。这正是 v2.9.1「抖音又完了」的根因。
            boolean hasLiveInput = hasLiveInputBar(root, 0);
            liveInputSeen = hasLiveInput;
            boolean pausedNow = !isVideoApp && !hasTab && (hasLiveInput || videoLivePage);
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
            // v3.0.7 插入点 T：直播三区列表已提交，此处收集 + 施加定向隐藏（读到最新门控信号）
            updateTargetedHide(root);
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
        // v3.0.21：合集页 / 搜索结果单视频页的底部固定条（评论输入条 + 合集条）。
        // 这些容器含 EditText / Compose，被 containsDynamicUi 整棵跳过，
        // 真机表现就是「底部一整条在隐藏相位纹丝不动」。
        collectBottomChrome(root, list, 0);
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

    /**
     * v3.0.21：底部固定 chrome 收集（合集页 / 搜索结果单视频页）。
     *
     * <p>{@link #collectBottomNode} 遇到 EditText / Compose 会把整棵跳过（防误藏滚动评论区），
     * 但合集页底部那条「有什么想法，展开说说」输入条、以及「合集 · xxx」固定条本身
     * 就是长期静止的，正是要防烧屏的对象。这里按几何单独收：横条（≥ 半屏宽）、
     * 矮（≤ 14% 屏高）、贴底（下缘 ≥ 86% 屏高），且要么含输入框、要么含固定条文案。</p>
     *
     * <p>「取消静音」是抖音自己的音量浮层（几秒后自动消失），不能当固定条藏掉 ——
     * 藏它只会让浮层闪一下，反而更像 bug。</p>
     */
    private static void collectBottomChrome(View v, List<View> list, int depth) {
        if (v == null || depth > MAX_DEPTH || v.getVisibility() == View.GONE
                || !(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        if (containsVideoSurface(g, 0)) {
            return;
        }
        int[] l = loc(g);
        int sh = screenH(g);
        int sw = screenW(g);
        int w = g.getWidth();
        int h = g.getHeight();
        // getLocationOnScreen 是屏幕坐标，screenH 是根视图高度；沉浸式下两者差一个状态栏。
        // 先减掉根视图自身的屏幕 y，换算到同一坐标系再比 86%。
        int[] rl = loc(g.getRootView());
        int baseY = rl != null ? rl[1] : 0;
        int bottomInRoot = l != null ? l[1] + h - baseY : 0;
        if (l != null && sh > 0 && sw > 0 && h > 0
                && w >= sw * 0.5f && h <= sh * 0.14f && bottomInRoot >= sh * 0.86f
                && !hasQuietHint(g, 2)
                && (hasEditBox(g, 2) || hasBottomChromeText(g, 2))
                && !list.contains(g)) {
            list.add(g);
            Log.i(TAG, "BOTTOMCHROME add " + geo(g)
                    + " hasEdit=" + hasEditBox(g, 2));
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            collectBottomChrome(g.getChildAt(i), list, depth + 1);
        }
    }

    /** 子树里是否有输入框（原生 EditText 或类名带 EditText/InputCompat 的自绘输入条） */
    private static boolean hasEditBox(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return false;
        }
        if (v instanceof android.widget.EditText) {
            return true;
        }
        String n = v.getClass().getName();
        if (n.contains("EditText") || n.contains("InputCompat")) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasEditBox(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 底部固定条文案：评论输入条 / 合集条 / 下一集 */
    private static boolean hasBottomChromeText(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return false;
        }
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            String t = normText(tv.getText()) + " " + normText(tv.getHint());
            if (t.contains("有什么想法") || t.contains("展开说说") || t.contains("说点什么")
                    || t.contains("合集") || t.contains("下一集")) {
                return true;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasBottomChromeText(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 音量浮层（取消静音 / 静音提示）不参与固定条隐藏 */
    private static boolean hasQuietHint(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return false;
        }
        if (v instanceof TextView) {
            String t = normText(((TextView) v).getText());
            if (t.contains("取消静音") || t.contains("开启静音") || t.equals("静音")) {
                return true;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasQuietHint(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * v3.0.4：只读探测 v 的子树是否含类名片段匹配 target 的 View。
     * 复用 {@link #containsVideoSurface} 的递归式与 MAX_DEPTH 深度上限；
     * 从直接子节点开始（不含 v 自身），避免容器自身类名巧合命中。
     * 只读：不 setVisibility / setAlpha / setTranslation，异常一律收敛为「未命中」。
     */
    private static boolean hasChildByClass(View v, String target) {
        return hasChildByClass(v, target, 0);
    }

    private static boolean hasChildByClass(View v, String target, int depth) {
        if (v == null || target == null || target.length() == 0 || depth > MAX_DEPTH) {
            return false;
        }
        try {
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c == null) {
                        continue;
                    }
                    if (c.getClass().getName().contains(target)
                            || hasChildByClass(c, target, depth + 1)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            // 宿主布局树的非常规实现不得中断扫描/隐藏主流程；安全失败 = 不命中 = 不放行
            return false;
        }
        return false;
    }

    /**
     * v3.0.6：快手右侧点赞互动栏四按钮的 resource-id 精确匹配。
     * 只读：复用既有 resName()（内部已 try/catch 回退数值 id），
     * 这里再包一层异常收敛——宿主混淆 / 动态 id 取不到语义名时一律「不匹配」。
     * 不用语义模糊匹配、不看 desc 里的「点赞X」数字，避免把计数节点当成按钮。
     */
    private static boolean matchesActionButtonId(View v) {
        if (v == null) {
            return false;
        }
        try {
            String n = resName(v);
            return "like_button".equals(n)
                    || "comment_button".equals(n)
                    || "collect_button".equals(n)
                    || "forward_button".equals(n);
        } catch (Throwable t) {
            // 安全失败 = 不匹配 = 不放行，继续走 2776 原门槛
            return false;
        }
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

    // ---------- v2.9.3：右缘图标列兜底（快手 / B站） ----------

    /**
     * 收集「右缘竖向图标列」里的单个按钮。
     *
     * <p>快手 / B站 的点赞、评论、收藏往往是「整宽浮层 + 靠右对齐」的布局：
     * 父容器宽≈全屏、起点 x≈0，几何评分一律判它「不是右侧列」；而真正要藏的
     * 是里面那几枚 40~60dp 的图标。这里反过来找「同一父容器下、贴右缘、
     * 竖向排开的两枚以上小控件」，把它们逐个交出去隐藏。</p>
     *
     * <p>仅对快手 / B站 调用（见 rescan），抖音 / 红果 的识别路径不受影响。</p>
     */
    private static void collectRightIcons(View v, List<View> out, int depth) {
        if (depth > MAX_DEPTH || !(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        int w = screenW(g);
        int h = screenH(g);
        if (w > 0 && h > 0 && g.getChildCount() >= 2) {
            List<View> hits = new ArrayList<>();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (rightIconOk(c, w, h)) {
                    hits.add(c);
                }
            }
            if (hits.size() >= 2 && stackedVertically(hits, h)) {
                for (View c : hits) {
                    if (!out.contains(c)) {
                        out.add(c);
                    }
                }
                return; // 命中即止：不再往这棵子树里继续拆，避免把图标内部的贴图当按钮
            }
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            collectRightIcons(g.getChildAt(i), out, depth + 1);
        }
    }

    /** 单个「右缘小按钮」的准入：尺寸小、贴右缘、在屏内、不是播放器、不是我们自己的悬浮球。 */
    private static boolean rightIconOk(View c, int w, int h) {
        try {
            if (c == null || c.getVisibility() != View.VISIBLE) {
                return false;
            }
            int cw = c.getWidth();
            int ch = c.getHeight();
            if (cw <= 0 || ch <= 0) {
                return false;
            }
            if (cw > w * 0.30f || ch > h * 0.22f) {
                return false;
            }
            int[] l = loc(c);
            if (l == null) {
                return false;
            }
            if (l[0] < w * 0.72f || l[0] >= w) {
                return false;
            }
            if (l[1] < -h / 10 || l[1] >= h) {
                return false;
            }
            if (isOwnOverlay(c) || containsVideoSurface(c, 0)) {
                return false;
            }
            return c.isClickable() || hasIconSignal(c, 0) || c instanceof TextView;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 竖向摊开校验：至少两枚按钮的纵向跨度占屏高 8%，防止把「同一行的几个图标」当按钮列。 */
    private static boolean stackedVertically(List<View> views, int h) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (View v : views) {
            int[] l = loc(v);
            if (l == null) {
                return false;
            }
            minY = Math.min(minY, l[1]);
            maxY = Math.max(maxY, l[1] + v.getHeight());
        }
        return maxY - minY >= h * 0.08f;
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
        if (g.getWidth() < 30 || g.getHeight() < 30) {
            return -1; // 排除 0 尺寸代理容器，与 scoreLeftTop/scoreLiveRight 同源
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
        if (g.getWidth() < 30 || g.getHeight() < 30) {
            return -1; // 排除 0 尺寸代理容器，与 scoreLiveRight 同源
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
        // v2.9.1：只作诊断，不再参与 App 内隐藏的放行判定（见 hideEnabled 顶部注释）
        boolean listHit = Config.shouldIntervene(selfPkg);
        boolean master = isVideoApp
                ? (isKuaishou ? Config.enableKuaishou : Config.enableBilibili)
                : Config.enableStatus;
        // 各 App 自己的分区开关位串：抖音6 / 红果5 / 快手·B站4
        String sw;
        if (isVideoApp) {
            boolean r = isKuaishou ? Config.enableKsRight : Config.enableBiliRight;
            // v2.9.2：B站只有右侧一项，其余位恒 0，日志里不再出现「开了却没效果」的假开关
            boolean t = isKuaishou && Config.enableKsTop;
            boolean b = isKuaishou && Config.enableKsBottom;
            boolean c = isKuaishou && Config.enableKsCorner;
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
        // v2.9.3：右侧区域的实际几何（宽%x高%@x%）。用来一眼区分
        // 「压根没收到容器」和「收到了却被形状校验否决」——快手右侧一直修不好，
        // 卡的就是这两种情况分不开。
        StringBuilder rg = new StringBuilder();
        for (int i = 0; i < rightList.size() && i < 3; i++) {
            if (rg.length() > 0) {
                rg.append(',');
            }
            rg.append(geo(rightList.get(i)));
        }
        String sig = " app=" + VideoApps.label(VideoApps.of(selfPkg)) + " pkg=" + selfPkg
                + " ver=" + BuildConfig.VERSION_NAME
                + " eng=" + Config.engine + " ov=" + overlay
                + " lm=" + Config.listMode + " listHit=" + listHit
                + " status=" + Config.enableStatus + " sw=" + sw
                + " " + hit
                + " hid=" + hidden + " cyc=" + Config.hideSec + "/" + Config.showSec
                + " live=" + livePaused + " vlive=" + videoLivePage
                // v2.9.2：悬浮球状态与直播间通道开关一起落盘。球没出来、或球在但
                // 点按无效果时，这两项能直接区分「没创建」「被藏掉」「直播通道还在跑」。
                + " ball=" + (ballView != null ? 1 : 0) + " fs=" + (forceShow ? 1 : 0)
                + " lrh=" + (LIVE_ROOM_HIDE ? 1 : 0)
                // v2.9.4：直播间四件套。lin= 底部输入条是否识别到；l3= 三区几何收集数；
                // lhid= 上一轮真正藏掉的容器数。三项一起看就能定位「没判成直播 /
                // 判成了但没收到容器 / 收到了但被排除」到底卡在哪一层。
                + " lin=" + (liveInputSeen ? 1 : 0)
                + " l3=x" + liveTopList.size() + "/x" + liveBottomList.size()
                + "/x" + liveRightList.size() + " lhid=" + liveHiddenCount
                + " rgeo=" + rg + " rrej=" + lastRightReject
                + " ksAllow=" + ksRightAllow; // v3.0.4 P2 新增（唯一的 sig 改动）
        if (sig.equals(lastHookSig) && now - lastHookDiagAt < 30_000L) {
            return;
        }
        lastHookSig = sig;
        lastHookDiagAt = now;
        XposedInit.diagForce("HOOK" + sig);
    }

    /** 区域几何摘要：宽%x高%@左边界% —— 判断「没收到」还是「收到了被否决」的分水岭。 */
    private static String geo(View v) {
        try {
            if (v == null) {
                return "none";
            }
            int sw = screenW(v);
            int sh = screenH(v);
            if (sw <= 0 || sh <= 0) {
                return "none";
            }
            int[] l = loc(v);
            int x = l == null ? -1 : Math.round(l[0] * 100f / sw);
            return Math.round(v.getWidth() * 100f / sw) + "x"
                    + Math.round(v.getHeight() * 100f / sh) + "@" + x;
        } catch (Throwable t) {
            return "err";
        }
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

        // v2.9.6：红果付费弹窗常驻隐藏（tick 补路，非主驱动 —— 见 R1：
        // 周期循环需五区几何命中，纯浮层付费页可能永远没有 tick，主驱动是
        // onResume / onGlobalLayout 的事件调用）。放在 hookDiag() 之后、rescan() 与
        // 1750/1759/1832 三处 early-return 之前：不看 anyEnabled、不看相位，
        // 每轮自己扫描 + 自己恢复 + 自己重新隐藏，宿主重新 addView 也能再藏；
        // 总闸关 / 灭屏时自己恢复并 return。不挂进 hideEnabled() / showAll()，
        // 所以周期恢复与别处调用的 showAll() 都不会把弹窗点亮。
        updateDramaPaywallHide();

        // v3.0.7 插入点 U：tick 补一路定向隐藏施加（不收集，only 按门控施加/恢复）
        updateTargetedHide(null);

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
        // livePaused 会多撑一个周期，表现为「出来了还没恢复」。
        // v2.9.4：必须把 liveInputSeen 一起算进去 —— 直播页靠「底部输入条」进入暂停，
        // 若这里只看 liveStrongHit / videoLivePage，会在输入条识别到、三区几何没收集到时
        // 立刻解除，直播间刚进就退出、反复抖动。
        if (livePaused && !liveStrongHit && !videoLivePage && !liveInputSeen) {
            livePaused = false;
        }
        // v2.9.4：LIVE_ROOM_HIDE 已启用，这条分支留作「一键回退」的安全网：
        // 万一真机上直播间又出问题，把常量改回 false 就立刻回到 v2.9.3 的行为，
        // 普通区域一个都不藏，不需要动下面任何一行。
        if (livePaused && !LIVE_ROOM_HIDE) {
            if (hidden) {
                showAll();
                hidden = false;
            }
            scheduleCycle(1000);
            return;
        }
        // 直播间隐藏打开时：只有「直播间隐藏」开关关掉才整体让位；开着就只藏三区。
        if (livePaused && LIVE_ROOM_HIDE && !Config.enableLive) {
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
            // v2.6.9：App 内区域隐藏与 SystemUI 侧共用「总开关 / 引擎」真源。
            // 直播特征页整页跳过（弹幕输入框 / 礼物栏不误伤）。
            //
            // v2.9.1：这里原本还有 Config.shouldIntervene(selfPkg)，与抖音 / 红果
            // 两个分支不一致 —— 同一份开关，抖音不看名单、快手 / B站 看名单。
            // 真机上是白名单模式且名单里只有微信：抖音照常隐藏，快手 / B站 被这行
            // 直接否决，表现就是「快手没反应、B站还是老样子」，而日志里看不出任何
            // 异常（because 根本不进隐藏分支）。App 内区域是用户按 App 单独打开的，
            // 不该再被系统栏的名单二次过滤，统一为只看本页是否直播。
            // v3.0.26：快手 / B站 直播间也要隐藏固定控件。旧版 `!videoLivePage` 把两者
            // 的四个区域开关整体否决，用户真机反馈「直播顶部头像/在线人数、右侧礼物列、
            // 底部输入条一个都没藏」。抖音 / 红果仍保留「进直播还原」的历史约定，
            // 只对 isVideoApp（快手 / B站）放开；视频画面保护仍由 safeToHide 的
            // containsVideoSurface / tooBigToHide 兜底。
            boolean allowed = !videoLivePage || isVideoApp;
            boolean mR = isKuaishou ? Config.enableKsRight : Config.enableBiliRight;
            // v2.9.2：B站只保留「右侧点赞/收藏/评论」一个区域。真机反复验证的结论是
            // 顶部 / 底部 / 角落三项在 B站 8.9x 上识别不到稳定容器，开关打开也没有效果，
            // 用户要求直接删掉，别留着当假开关。快手不受影响，仍是四区。
            boolean mT = isKuaishou && Config.enableKsTop;
            boolean mB = isKuaishou && Config.enableKsBottom;
            boolean mC = isKuaishou && Config.enableKsCorner;
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
                    || (Config.enableDouyinLt && leftTop != null);
            // v2.9.2：删掉 `Config.enableLive && liveDetected && 三区非空` 这一项。
            // 它让直播页在没有普通区域可藏时也进入隐藏窗口，每秒空转一轮，
            // 还顺带占掉普通页的相位；直播间现在整体让位（见 LIVE_ROOM_HIDE），
            // 不需要也不应该再参与「是否进入隐藏窗口」的判定。
        }

        // v2.9.4：直播页要有自己的「进入隐藏窗口」条件。
        // v2.9.2 曾把 `Config.enableLive && liveDetected` 从 anyEnabled 里删掉，
        // 于是重新启用三区隐藏后，直播页永远进不了隐藏相位，hideLiveAreas() 一次都不会跑，
        // 表现还是「直播间没反应」。这里只在 livePaused（已过硬结构闸）时覆盖，
        // 普通视频页的 anyEnabled 完全不变。
        if (LIVE_ROOM_HIDE && livePaused && Config.enableLive) {
            anyEnabled = !liveTopList.isEmpty() || !liveBottomList.isEmpty()
                    || !liveRightList.isEmpty()
                    || !rightList.isEmpty() || topBar != null || !bottomList.isEmpty()
                    || rightTop != null || leftTop != null;
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

        // v2.9.0：恢复 v2.7 之前的「周期性真隐藏」。
        //
        // 中间几版改成常驻位置微移（控件全程可见、每 8 秒挪 2~3px），真机结论是
        // 「不如最开始的真隐藏，效果立竿见影」—— 微移只让轮廓落在相邻像素上，
        // 亮度一点没降；真隐藏是像素直接不发光，防烧屏强度差一个量级。
        //
        // 代价是识别错了会藏掉内容区（用户说的「容易产生 bug」正是这条），
        // 所以在 hideSafe() 里补了一道面积保险：超过屏幕 45%×45% 的一律不藏。
        long now = SystemClock.elapsedRealtime();
        long hideMs = Math.max(1, Config.hideSec) * 1000L;
        long showMs = Math.max(1, Config.showSec) * 1000L;
        if (!hidden) {
            // phaseAt==0 表示刚进页面，先立刻藏一轮，别让用户等一个 showSec
            if (phaseAt == 0L || now - phaseAt >= showMs) {
                hideEnabled();
                hidden = true;
                phaseAt = now;
            }
        } else if (now - phaseAt >= hideMs) {
            showAll();
            hidden = false;
            phaseAt = now;
        }

        // 每 1 秒重扫一次：相位切换靠时间戳判定，tick 间隔必须小于最短相位，
        // 否则 showSec 调小时会出现「该切了却迟迟不切」的迟滞。
        scheduleCycle(1000);
    }

    private static void hideEnabled() {
        // v2.8.4：先推进偏移相位，再按各区域开关应用。
        updateShiftPhase();
        // 悬浮球"强制显示"模式：不隐藏任何控件（用于查看各区域控件是否被正确隐藏）
        if (forceShow) {
            showAll();
            return;
        }
        // v3.0.26：个人主页 / 作品列表页整页放行，否则作品封面会被当固定控件透明化 → 白屏。
        // 只在抖音 / 红果生效，快手 / B站 的 isVideoApp 分支不参与。
        if (profilePage && !isVideoApp) {
            showAll();
            return;
        }
        // v2.9.2：直播间通道断开时，直播页只做恢复，绝不隐藏任何普通区域。
        // 布局监听在直播页仍会调用本方法，这里必须挡住，否则「判定是直播、却按普通页藏」
        // 会把弹幕输入框与礼物栏一起抹掉。
        if (livePaused) {
            if (LIVE_ROOM_HIDE) {
                hideLiveAreas();
            } else {
                showAll();
            }
            return;
        }
        // v2.7：直播三区只受「直播间隐藏」开关控制，独立判断，不再短路掉普通区域。
        // 旧版一旦 isLive() 命中，enableLive=false 就 showAll() 全盘恢复并 return，
        // 会把用户已开启的顶部/右侧等普通区域开关一起否决；判定又依赖 rightTop 的
        // RoundCornerFrameLayout（普通视频页也可能命中），误判后整个区域隐藏失效。
        // 现在两套开关彻底解耦：直播三区按 enableLive 隐藏/恢复，普通区域照常按各自开关。
        // v2.8.4：直播间一律还原位置。
        // 礼物栏 / 弹幕输入框 / 连麦按钮的位置随直播内容动态变化，做位移会让
        // 点击区域与画面错开，用户明确要求「进直播间还原」。
        for (View v : liveTopList) {
            resetShift(v);
        }
        for (View v : liveBottomList) {
            resetShift(v);
        }
        for (View v : liveRightList) {
            resetShift(v);
        }
        // 红果短剧用红果独立开关，抖音用抖音开关，快手 / B站 用视频区域开关 + 各自总开关。
        // 复用同一套几何容器：rightList=右侧互动列，topBar=顶部工具栏，
        // bottomList=底部操作栏，rightTop/leftTop=角落按钮。不另写未标定的识别规则。
        boolean dLike, dTop, dBot, dRt, dLt;
        if (isVideoApp) {
            boolean on = (isKuaishou ? Config.enableKuaishou : Config.enableBilibili)
                    && !ConfStore.ENGINE_OVERLAY.equals(Config.engine)
                    && (!videoLivePage || isVideoApp);
            dLike = on && (isKuaishou ? Config.enableKsRight : Config.enableBiliRight);
            // v2.9.2：B站只剩右侧一项，其余三项恒 false（开关已从 UI 删除）
            dTop = on && isKuaishou && Config.enableKsTop;
            dBot = on && isKuaishou && Config.enableKsBottom;
            dRt = on && isKuaishou && Config.enableKsCorner;
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
                // v3.0.6：快手点赞栏新开关关闭时主动恢复，不参与放行判定（放行门在 safeToHide 分支内）
                if (isKuaishou && !Config.enableRightActions && matchesActionButtonId(v)) {
                    showDeep(v);
                    continue;
                }
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
        // v2.8.4：v2.7 曾让「直播间隐藏」也来否决这里；现在直播三区一律还原位置，
        // 右上角只由「右上角」开关决定，语义单一不打架。
        if (dRt) {
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
                // v2.9.0：引导条是静态竖排小字，真隐藏比微移彻底，一并走 hideSafe
                hideSafe(v, "swipe");
            } else {
                showDeep(v);
            }
        }
        // v3.0.6 P2：红点隐藏；开关关 / 直播暂停 / overlay 一律恢复
        boolean redOn = Config.enableRedDot
                && (isKuaishou || (!isVideoApp && !isDrama))
                && Config.enableStatus && !videoLivePage
                && !ConfStore.ENGINE_OVERLAY.equals(Config.engine);
        for (View v : redDotList) {
            if (redOn) {
                hideSmallSafe(v, "reddot");
            } else {
                showDeep(v);
            }
        }
        // v3.0.6 P3：下方标题栏；开关关 / 直播暂停 / overlay / 红果一律恢复
        boolean captionOn = Config.enableBottomCaption && Config.enableStatus
                && !isDrama && !videoLivePage
                && !ConfStore.ENGINE_OVERLAY.equals(Config.engine);
        for (View v : captionList) {
            if (captionOn) {
                hideSmallSafe(v, "caption");
            } else {
                showDeep(v);
            }
        }
    }

    /**
     * 直播间专用隐藏（v2.9.1）。
     *
     * <p>只动「直播专属三区」——顶部主播信息条、右侧礼物/分享列、底部非输入条；
     * 普通页的五大区域一律恢复。原因：直播间的 topBar / bottomList 往往就是
     * 输入框、礼物栏、退出按钮的父容器，按普通页规则藏会把交互点一起抹掉；
     * 而三区规则在收集阶段已经排除了含 EditText 与视频画面的容器。</p>
     *
     * <p>双层保险：collect 阶段的排除 + {@link #hideSafe} 里的 45% 面积上限，
     * 任何一层命中都不会把直播画面藏黑。</p>
     *
     * <p>v2.9.5：v2.9.4 的「三区为空就退回普通页五区几何」是「一点隐藏就挂」的
     * 直接来源 —— 那五个容器是普通视频页评分器收集的，在直播间里常是弹幕层 /
     * 评论浮层 / 含画面的父容器；抖音候选原先在 hideSafe() 里因 isVideoApp=false
     * 完全不过 {@link #safeToHide}，只剩 tooBigToHide 的「宽高双超 45%」，
     * 于是宽 100%×高 30% 的大容器被整块 INVISIBLE。
     * 本版**保留兜底**（删掉的话，三区几何常空的机型又会「完全没反应」），
     * 但每个候选都按它本来的区域语义复用已有的 safeToHide 门禁，
     * 不新增任何阈值 / 开关：顶栏只认矮横条、右侧只认窄竖列、底部输入条区一律不碰。</p>
     */
    private static void hideLiveAreas() {
        // v2.9.5 一键回退：把 hideLiveCandidate() 首行改成 `return false;` 即回到
        // 「直播间零隐藏」安全态（v2.9.3 行为），本方法开头的 showAll() 仍逐轮恢复。
        // 通道断开 / 开关关掉：整页恢复，一个控件都不碰。
        if (!LIVE_ROOM_HIDE || !Config.enableLive) {
            showAll();
            return;
        }
        // 每轮先统一恢复再按候选重新藏：同一帧主线程内完成，视觉不闪，
        // 且不会留下「上一轮藏过、这一轮已不在候选里」的永久隐藏 View。
        showAll();

        int n = 0;
        // ① 直播专属三区：按各自区域语义过已有的 safeToHide，不新增阈值。
        for (View v : liveTopList) {
            if (hideLiveCandidate(v, "top")) {
                n++;
            }
        }
        for (View v : liveBottomList) {
            if (hideLiveCandidate(v, "bottom")) {
                n++;
            }
        }
        for (View v : liveRightList) {
            if (hideLiveCandidate(v, "right")) {
                n++;
            }
        }
        // ② 三区几何全空才兜底（ColorOS 直播间常见）。只借用顶栏 / 右侧列 / 角落按钮，
        //    绝不碰 bottomList —— 直播间底部就是「说点什么」输入条与弹幕区，
        //    用户明确要求输入框和弹幕不受影响，整块隐藏风险最高。
        //    这些容器同样在 showAll() 的遍历范围内，下一轮开头必被恢复。
        if (liveTopList.isEmpty() && liveBottomList.isEmpty() && liveRightList.isEmpty()) {
            if (topBar != null && hideLiveCandidate(topBar, "top")) {
                n++;
            }
            for (View v : rightList) {
                if (hideLiveCandidate(v, "right")) {
                    n++;
                }
            }
            if (rightTop != null && hideLiveCandidate(rightTop, "righttop")) {
                n++;
            }
            if (leftTop != null && hideLiveCandidate(leftTop, "lefttop")) {
                n++;
            }
        }
        liveHiddenCount = n;
    }

    /**
     * v2.9.4：直播间候选容器隐藏前的三道排除。
     * 用户反复强调「弹幕和打字都不受影响」「输入框不碰」，这里把它们写死：
     * ① containsDynamicUi —— 含 EditText / Compose 输入条 / 弹幕容器一律跳过；
     * ② containsVideoSurface —— 播放画面容器跳过（宁可漏藏也不黑屏）；
     * ③ tooBigToHide —— 宽、高都超屏 45% 的内容容器跳过。
     * 通过后才交给 hideSafe（里面还有悬浮球跳过与面积保险双保险）。
     *
     * <p>v2.9.5：补第 ④ 道 —— 复用已有的 {@link #safeToHide} 形状白名单。
     * what 传候选**本来的区域标签**（top / bottom / right / righttop / lefttop），
     * 直接复用为普通页设计好的门槛：顶栏与底栏只认矮横条、右侧只认窄竖列、
     * 角落只认小控件。不新增任何阈值 / 开关。抖音直播候选原先在 hideSafe()
     * 里因 isVideoApp=false 完全绕过这道校验，只剩 tooBigToHide 的「宽高双超 45%」，
     * 于是宽 100%×高 30% 的弹幕层 / 评论浮层会被整块 INVISIBLE（真机「隐藏就挂」）。
     * 现在不满足对应区域门槛就恢复、不藏：内容区 / 画面 / 输入条
     * 绝不会被整块设成 INVISIBLE。隐藏集合是 v2.9.4 的严格子集 —— 只会少藏，不会多藏。</p>
     */
    private static boolean hideLiveCandidate(View v, String what) {
        if (v == null || isOwnOverlay(v)) {
            return false;
        }
        // v2.9.5：只加门禁、不新增阈值；被挡下的候选仍走 showDeep 恢复，与 hideLiveAreas()
        // 开头的 showAll() 对齐，保证隐藏/恢复对称、不留永久 INVISIBLE 的 View。
        if (containsDynamicUi(v, 0) || containsVideoSurface(v, 0)
                || tooBigToHide(v) || !safeToHide(v, what)) {
            showDeep(v);
            return false;
        }
        hideSafe(v, what);
        return true;
    }

    /**
     * 深度透明（含第二重保险）：容器 setAlpha(0) + setVisibility(INVISIBLE)。
     * INVISIBLE 保留空间（不破坏布局/触摸区），强制整棵子树不绘制——
     * 即使容器 height=0、或 tab 子项越界显示，也能彻底藏掉。比 setAlpha 可靠。
     */
    private static void transparentDeep(View v, String what) {
        if (forceShow) {
            showDeep(v);
            return;      // v3.0.19：显式「显」模式下任何路径都不得隐藏
        }
        if (v == null) {
            return;
        }
        // v2.9.2：悬浮球连带其子树永不透明化（防误收进区域后整块消失）
        if (isOwnOverlay(v)) {
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

    /**
     * v3.0.6 P2/P3 共用的专用隐藏出口：不新增到 safeToHide 的 what 分支，保持通用门槛零改。
     * 只复用三道通用保险：悬浮球保护 + 视频否决 + 面积保险，最后走 transparentDeep（只 INVISIBLE）。
     */
    private static void hideSmallSafe(View v, String what) {
        if (v == null) {
            return;
        }
        if (isOwnOverlay(v)) {
            return;
        }
        if (containsVideoSurface(v, 0) || tooBigToHide(v)) {
            return;
        }
        transparentDeep(v, what);
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
        // v3.0.6 P2/P3：新列表全部接入恢复路径，避免关开关/进直播后留永久 INVISIBLE
        for (View v : redDotList) {
            showDeep(v);
        }
        for (View v : captionList) {
            showDeep(v);
        }
    }

    /**
     * 丢弃旧视图前先把它恢复显示（v2.9.3）。
     *
     * <p>右侧列 / 底部列 / 顶部栏 / 角落按钮都是「本轮收集结果覆盖上一轮」。旧版只做
     * {@code clear() + addAll()}，被替换掉的 View 从此没有任何引用 —— {@link #showAll()}
     * 遍历不到它，{@code hideEnabled()} 里「开关关掉就恢复」的分支也遍历不到它。
     * 一旦它当时正处在隐藏相位，就再也不会恢复。</p>
     *
     * <p>真机表现：翻到下一个视频再滑回来，底部导航 / 右侧按钮再也不出现，只能杀进程。
     * 用户说的「看别的视频时导航栏突然消失」正是这条。{@code keep} 里已有的（同一个
     * 对象在新列表里继续用）不恢复，否则会把刚藏好的又点亮。</p>
     */
    private static void releaseAll(List<View> olds, List<View> keep) {
        if (olds == null || olds.isEmpty()) {
            return;
        }
        for (View v : olds) {
            if (v == null) {
                continue;
            }
            if (keep != null && keep.contains(v)) {
                continue;
            }
            showDeep(v);
        }
    }

    /** 深度恢复：容器恢复 VISIBLE + alpha=1，并把 v2.8.4 的偏移归零 */
    private static void showDeep(View v) {
        if (v == null) {
            return;
        }
        // v2.9.7（台账 #4）一键回退分支：SAFE_RESTORE=false 时逐字节保持 v2.9.6 逻辑。
        if (!SAFE_RESTORE) {
            // v2.8.4：偏移必须在这里归零，否则「关掉开关 / 退出直播」后控件
            // 还停在挪过的位置，用户会以为布局错乱。
            resetShift(v);
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
            return;
        }
        // v2.9.7：只归零 translation，绝不碰 alpha / visibility。
        // resetShift() 本体含 alpha!=1f→1f、INVISIBLE→VISIBLE，且另被 1952/1955/
        // 1958/2679/2684 五处调用，不能改它本体，只能在此换用专用方法。
        resetTranslationOnly(v);
        // v2.7.3：恢复路径同样单点兜底。showAll() 会在进/退直播时被频繁调用，
        // 这里一旦抛出，整轮 tick 中断，正是「退出直播后再也不隐藏」的直接触发点。
        try {
            // 本模块隐藏态恒为 INVISIBLE（从不设 GONE）：只撤销这一个写入，
            // 宿主自设的 GONE 保持 GONE，绝不被点亮。
            if (v.getVisibility() == View.INVISIBLE) {
                v.setVisibility(View.VISIBLE);
            }
            // 本模块隐藏态恒为 alpha0：只撤销这一个写入，
            // 宿主自设的半透明 alpha（如 0.5f）保持原值，绝不被拉平成 1f。
            if (v.getAlpha() == 0f) {
                v.setAlpha(1f);
                Log.i(TAG, "shown: " + desc(v));
            }
        } catch (Throwable t) {
            Log.e(TAG, "show failed", t);
        }
    }

    /**
     * v2.9.7：只把偏移归零，不碰 alpha / visibility。供 {@link #showDeep} 在
     * SAFE_RESTORE 分支精确恢复用。风格照抄 {@link #resetShift}，但去掉了它
     * 尾部那两行会强改宿主状态的 alpha / visibility 写入。
     */
    private static void resetTranslationOnly(View v) {
        if (v == null) {
            return;
        }
        try {
            if (v.getTranslationX() != 0f) {
                v.setTranslationX(0f);
            }
            if (v.getTranslationY() != 0f) {
                v.setTranslationY(0f);
            }
        } catch (Throwable t) {
            Log.e(TAG, "reset translation failed", t);
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

    // ---------- v2.9.6：红果「付费抢先看」弹窗常驻隐藏（按文案识别） ----------
    // 付费页居中弹窗挡住短剧画面，用户要「一直别显示」，不是防烧屏周期显隐。
    // v2.9.6 R1：改事件驱动 —— onResume / onGlobalLayout 直接调
    // updateDramaPaywallHide()，tick() 只在它本来就在跑时补一路；绝不启动周期调度。
    // 不塞进 anyEnabled / hideEnabled() / showAll()，因此不随相位闪烁。
    // 只把遮挡层 alpha=0 + INVISIBLE，绝不触发点击 / 触摸派发 / 广播 / 宿主业务方法
    // —— 隐藏提示 ≠ 解锁播放，付费逻辑一律不动。
    // R4（v2.9.7 已统一处理）：showDeep() 旧版会把 GONE 强改 VISIBLE、alpha 强改 1f。
    // 这是全项目共用恢复路径的已知问题，单独在 DPH 修会造成两套恢复语义，故
    // v2.9.6 保持复用 releaseAll() 不动。v2.9.7 已在 showDeep() 内用 SAFE_RESTORE
    // 统一收紧为「只撤销 INVISIBLE + alpha0」，DPH 复用 showDeep() 因此一并受益，
    // 无需各自存原 visibility/alpha；releaseAll / transparentDeep 依旧不动。
    // v2.9.6 R5：强词门槛下沉到「单个候选容器子树内」。旧版整屏一处强词即放行全屏
    // 所有 ≥2 命中候选，仅含两个弱词的横幅会被误藏；现在每个候选必须自己子树内
    // 含 ≥1 个强词节点才进 targets。宁可漏藏，绝不误伤。

    /** v2.9.6：截图里实有的全部文案；经已有 normText 折叠空白后匹配（含弱词，仅用于收集）。 */
    private static final String[] DPH_KEYS = {
            "付费抢先看", "0元抢先看", "立即预约", "应版权方要求", "后续转免费"
    };
    /** v2.9.6 R3：强特征词。弱词「0元抢先看」「立即预约」在推荐卡片 / 活动横幅上
     * 并排两个按钮即可凑够 ≥2 命中被整块 INVISIBLE，故至少一个强词才允许隐藏。 */
    private static final String[] DPH_STRONG_KEYS = {
            "付费抢先看", "应版权方要求", "后续转免费"
    };

    private static boolean dphTextHit(CharSequence cs) {
        String s = normText(cs);
        if (s.isEmpty()) {
            return false;
        }
        for (String k : DPH_KEYS) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** v2.9.6 R3：是否带强特征词（付费页专属短语）。 */
    private static boolean dphStrongHit(CharSequence cs) {
        String s = normText(cs);
        if (s.isEmpty()) {
            return false;
        }
        for (String k : DPH_STRONG_KEYS) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * v2.9.6：全树收集命中的 TextView（getText / getContentDescription 都认）。
     *
     * <p>⚠️ R3 陷阱：只判 GONE + isAttachedToWindow()，<b>绝对不要加 isShown 这类
     * 可见性判定过滤</b>。弹窗被本模块 {@link #transparentDeep} 设成 INVISIBLE 后，
     * 该可见性判定会返回 false，下一轮就收集不到 → 触发 releaseAll 恢复 →
     * 再隐藏 → <b>自我闪烁</b>。INVISIBLE 是模块自己设的状态，必须仍算命中。</p>
     */
    private static void collectDphHits(View v, List<View> out, int depth) {
        if (v == null || depth > MAX_DEPTH || v.getVisibility() == View.GONE
                || !v.isAttachedToWindow()) {
            return;
        }
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (dphTextHit(tv.getText()) || dphTextHit(tv.getContentDescription())) {
                out.add(v);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectDphHits(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** v2.9.6：候选 ViewGroup 子树内的命中文本节点数（复用命中表，不重复全树遍历）。 */
    private static int dphHitUnder(ViewGroup candidate, List<View> hits) {
        int n = 0;
        for (View h : hits) {
            ViewParent p = h.getParent();
            for (int up = 0; up <= MAX_DEPTH && p instanceof View; up++) {
                if (p == candidate) {
                    n++;
                    break;
                }
                p = p.getParent();
            }
        }
        return n;
    }

    /**
     * v2.9.6 R5：候选容器<b>自己的子树内</b>是否至少含 1 个强特征词命中节点。
     * 复用 {@link #dphHitUnder} 的向上回溯写法，不新增几何 / 阈值判断。
     * 这是防误伤的核心：同屏别处有付费弹窗提供强词，也不能给本容器开绿灯，
     * 只含「立即预约 / 0元抢先看」两个弱词的活动横幅 / 推荐卡片必须被拒绝。
     */
    private static boolean dphStrongUnder(ViewGroup candidate, List<View> strongHits) {
        return dphHitUnder(candidate, strongHits) > 0;
    }

    /**
     * v2.9.6 R2：自命中 TextView 向上回溯 ≤4 层，<b>首次</b>遇到「内部含 ≥2 个命中
     * 文本节点」的合格 ViewGroup 就立即返回 —— 取<b>最内层</b>合格容器，宁可残留
     * 外层暗色遮罩 / Dialog window 背景板（可接受的「漏藏」），也绝不误伤。
     * 不再向上刷新、不再放大误伤面：containsVideoSurface 只递归候选子树，
     * 播放器作为兄弟层检不到；红果 isVideoApp=false 会跳过 safeToHide，
     * hideSafe 只剩 tooBigToHide 的「宽>45% 且 高>45%」单维放行，
     * 69%×40% 的容器会被整块 INVISIBLE —— 所以安全上限收紧到宽高各 ≤55% 屏。
     * 三条安全上限（非识别阈值）：≤55% 屏、不是自身悬浮层、不含视频画面；
     * 尺寸超限或撞上视频层 / 悬浮球即停，防止回溯到 decor 根整页 INVISIBLE。
     */
    private static View dphTarget(View hit, List<View> hits) {
        ViewParent p = hit.getParent();
        for (int up = 0; up < 4 && p instanceof View; up++) {
            View c = (View) p;
            if (isOwnOverlay(c)) {
                break;
            }
            if (c instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) c;
                int sw = screenW(g);
                int sh = screenH(g);
                if (sw <= 0 || sh <= 0 || g.getWidth() <= 0 || g.getHeight() <= 0
                        || g.getWidth() > sw * 0.55f || g.getHeight() > sh * 0.55f) {
                    break; // 祖先只会更大，回溯到此为止（防 decor 根整页 INVISIBLE）
                }
                if (containsVideoSurface(g, 0)) {
                    break; // 已到宿主视频层边界，再往上必然整页，停
                }
                if (dphHitUnder(g, hits) >= 2) {
                    return g; // 最内层合格容器，立即返回，绝不继续向上放大
                }
            }
            p = c.getParent();
        }
        return null;
    }

    /**
     * v2.9.6 R5：状态变化才落盘一条低频诊断；不动 hookDiag() 的 sig 拼接。
     * hits / rej 让 n=0 时可区分三态：hits=0 没弹窗；hits>0 rej=0 被 55% 上限 /
     * 无 ≥2 命中簇判掉；hits>0 rej>0 形成了候选但被强词门槛挡掉（弱词横幅）。
     * 签名由 (n, hits, rej) 三元组组成，任一变化才写，仍旧低频不刷屏。
     */
    private static void dphDiag(int n, int hits, int rejected) {
        if (n == dphLastN && hits == dphLastHits && rejected == dphLastRej) {
            return;
        }
        dphLastN = n;
        dphLastHits = hits;
        dphLastRej = rejected;
        boolean hit = n > 0;
        XposedInit.diagForce("DPH n=" + n + " hit=" + hit
                + " hits=" + hits + " rej=" + rejected);
    }

    /**
     * v2.9.6：红果付费弹窗常驻隐藏的唯一入口。R1 改事件驱动：onResume /
     * onGlobalLayout 为主驱动，tick() 仅在它本来就在跑时补一路，绝不启动周期调度。
     * 只看 isDrama / DRAMA_PAYWALL_HIDE / 总开关 / 亮屏，不看 anyEnabled 与相位。
     * 所有恢复 return 分支都复用已有 releaseAll() 并清空 dramaPaywallList，
     * 绝不留永久 INVISIBLE 的 View；隐藏走已有 hideSafe(v, "dramapaywall")。
     * v2.9.6 R5：强词判定下沉到单个候选容器子树内，删除整屏放行旁路。
     */
    private static void updateDramaPaywallHide() {
        // 抖音 / 快手 / B站 / SystemUI：连诊断都不写，行为与日志链一行不变
        if (!isDrama || !DRAMA_PAYWALL_HIDE) {
            releaseAll(dramaPaywallList, null);
            dramaPaywallList.clear();
            return;
        }
        // 总闸关 / 灭屏 / 无根视图：恢复并清空，保持「总开关关掉就别动宿主 UI」的语义
        if (!Config.enableStatus || !isScreenOn() || !(currentDecor instanceof ViewGroup)) {
            releaseAll(dramaPaywallList, null);
            dramaPaywallList.clear();
            dphDiag(0, 0, 0);
            return;
        }
        // v2.9.6 R1：节流必须放在上面全部恢复 return 分支之后、正常扫描之前。
        // tick 与 onResume / onGlobalLayout 双路径共用同一节流，幂等不重复全树扫描；
        // 恢复路径不受节流延迟（否则会留下残留 View）。被节流时直接 return，
        // 上一轮已隐藏的容器保持隐藏，不做任何恢复动作。
        long nowDph = SystemClock.elapsedRealtime();
        if (lastDphScanAt != 0L && nowDph - lastDphScanAt < DPH_SCAN_INTERVAL_MS) {
            return;
        }
        lastDphScanAt = nowDph;
        List<View> hits = new ArrayList<>();
        collectDphHits((ViewGroup) currentDecor, hits, 0);
        // v2.9.6 R5：把带强词的命中节点单独列出，只供「候选容器子树内」判定。
        // 不再做整屏放行判断 —— 旧版一处强词给全屏所有 ≥2 命中候选开绿灯，
        // 正是本轮要修的确定性误伤路径。R3 的 DPH_STRONG_KEYS / GONE +
        // isAttachedToWindow() 收集过滤保持不变。
        List<View> strongHits = new ArrayList<>();
        for (View h : hits) {
            if (h instanceof TextView) {
                TextView tv = (TextView) h;
                if (dphStrongHit(tv.getText()) || dphStrongHit(tv.getContentDescription())) {
                    strongHits.add(h);
                }
            }
        }
        List<View> targets = new ArrayList<>();
        // v2.9.6 R5：被强词门槛挡掉的去重候选容器，供诊断 rej 计数。
        List<View> rejectedTargets = new ArrayList<>();
        for (View h : hits) {
            View t = dphTarget(h, hits);   // R2：首次 ≥2 命中的最内层合格容器，立即返回
            if (t == null || targets.contains(t) || rejectedTargets.contains(t)) {
                continue;
            }
            // 只有该容器自己的子树内含 ≥1 个强词节点才允许隐藏；
            // 只含弱词的活动横幅 / 推荐卡片一律进 rejectedTargets，不进 targets。
            // dphTarget() 只会返回 ViewGroup 或 null，这里显式判型匹配形参。
            if (t instanceof ViewGroup && dphStrongUnder((ViewGroup) t, strongHits)) {
                targets.add(t);
            } else {
                rejectedTargets.add(t);
            }
        }
        int rejected = rejectedTargets.size();
        // 每轮先恢复「上一轮藏过、本轮不再命中」的 View（复用 v2.9.3 releaseAll），
        // 再整体替换；宿主重新 addView 时本轮会重新命中并重新隐藏。
        // targets 为空（无命中 / 全被门槛挡掉）时这里仍会完整恢复并清空。
        releaseAll(dramaPaywallList, targets);
        dramaPaywallList.clear();
        dramaPaywallList.addAll(targets);
        for (View v : dramaPaywallList) {
            hideSafe(v, "dramapaywall");
        }
        dphDiag(dramaPaywallList.size(), hits.size(), rejected);
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
            if ("right".equals(what)) {
                ksRightAllow = "video";
            }
            return false; // 视频画面不能黑屏
        }
        int w = screenW(v), h = screenH(v);
        if (w <= 0 || h <= 0 || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        // ↓↓↓ v3.0.4 P1 插入点 ↓↓↓
        // v3.0.4：快手右侧作者栏（头像 + 关注 + 作者信息，叠加在视频上的 UI）。
        // 用户显式授权放行；本分支不得上移到 2689 视频检查之前（视频否决权最高）。
        // 命中后跳过右列的形状门槛，但 hideSafe 的 tooBigToHide 与 2689 仍照常执行。
        // S3 加固：单维面积上限。tooBigToHide 是「宽高双超45%」的 AND 条件，
        // 单维满屏容器（如 rw=100%/rh=44%）会漏过它；若视频组件再漏检则会黑屏。
        // 故任一维 >50% 者一律不放行，回落 2716/2719/2722 原门槛（超 50% 必被拒）。
        // 用 2692 已定义且已校验 >0 的 w/h 内联比较，不前移 2700 的 rw/rh。
        if (isKuaishou && "right".equals(what)
                && v.getWidth() <= w * 0.50f          // S3 单维面积上限：宽<=50%
                && v.getHeight() <= h * 0.50f         // S3 单维面积上限：高<=50%
                && hasChildByClass(v, "AvatarWithPendantView")
                && hasChildByClass(v, "FollowButtonView")
                && !containsVideoSurface(v, 0)) {
            ksRightAllow = "author"; // P2：纯日志标记，不改隐藏行为
            return true;
        }
        // ↑↑↑ v3.0.4 P1 插入点结束 ↑↑↑
        // ↓↓↓ v3.0.6 P1 快手点赞互动栏精准放行 ↓↓↓
        // 四按钮 rw≈0.371 必被 2776 的 0.30 门槛否决；此处按 resource-id 精确放行。
        // 位置约束：必须在 2728 视频否决之后、2760/2776 几何门槛之前。
        // S3 同款单维上限照抄 2747-2748，防止单维满屏容器漏过 tooBigToHide。
        if (isKuaishou && "right".equals(what)
                && Config.enableRightActions        // 【双门·内嵌】新开关作为放行判据的第一项，任何调用点都无法绕过
                && matchesActionButtonId(v)
                && v.getWidth() <= w * 0.50f
                && v.getHeight() <= h * 0.50f
                && !containsVideoSurface(v, 0)) {   // 第二道视频保险
            ksRightAllow = "actions";               // 纯日志标记，与 author/geo/big/ok 区分
            return true;
        }
        // ↑↑↑ v3.0.6 P1 插入点结束 ↑↑↑
        // v2.7.0：形状限定式 —— 固定栏只可能是「窄竖条 / 矮横条 / 小控件」之一，
        // 首页信息流 / 推荐网格 / 视频详情区这类大块内容容器天然不满足形状约束。
        // v2.6.9 的排除式护栏（动态 UI / 子节点数 / 大容器）把真正的固定栏也拦了，
        // 造成「只有加号生效、其它全部失效」—— 详见类头 v2.7.0 注释。
        float rw = (float) v.getWidth() / w;
        float rh = (float) v.getHeight() / h;
        if ("right".equals(what)) {
            // 右侧要接受三种形态，同时一条都不能放过内容卡片：
            //   整列容器   宽 ~15%   高 25%~45%   （快手 3 个按钮 / B站 5 个按钮）
            //   分组容器   宽 ~15%   高 10%~20%   （点赞+评论挤在一个 LinearLayout）
            //   单个按钮   宽 ~15%   高 4%~8%     （右缘图标列兜底逐个收集）
            //
            // v2.9.2 及以前：`rh > 0.08f` 无上限，整列能过，但单按钮过不了；
            // v2.9.3 中途改成 `rh <= 0.22f` 放行单按钮，却把**整列**全否决了 ——
            // 快手 / B站的点赞列本身就占屏高 25%~45%，于是收到容器也一个都藏不了。
            // 真机表现就是「开关开着、日志里 R=x3、右侧纹丝不动」。
            //
            // 现在按「窄」而不是「矮」来区分：按钮列在竖向永远只占屏宽 15% 上下，
            // 内容卡片（作者卡、商品卡、推荐卡）极少窄于 22% 屏宽；因此超过 30%
            // 屏高时额外要求宽度 <= 22%，两条同时满足才放行。
            if (rw >= 0.30f) {
                return false;
            }
            if (rh <= 0.02f || rh > 0.50f) {
                return false;
            }
            return rh <= 0.30f || rw <= 0.22f;
        }
        if ("top".equals(what) || "bottom".equals(what) || "plus".equals(what)) {
            return rh < 0.18f; // 顶部 / 底部横条必须矮
        }
        // v2.7.2：角落按钮容器在自绘 / Compose 页可能是一条竖排工具条，高度超过 20%，
        // 旧阈值把左上三横线、右上搜索整块恢复。上限放到 35%，仍远小于内容容器。
        return rw < 0.35f && rh < 0.35f; // 角落按钮：小控件
    }

    /**
     * 隐藏单个区域的唯一出口。
     *
     * <p>v2.9.0 起恢复真隐藏（{@code alpha=0} + {@code INVISIBLE}）。防烧屏强度最高，
     * 但识别错一次就会把内容区一起藏掉、屏幕发黑 —— 用户说的「容易产生 bug」正是这条。
     * 所以执行前叠两道保险：</p>
     * <ol>
     *   <li>{@link #safeToHide} —— 只对快手 / B站 生效的区域白名单校验；</li>
     *   <li>{@link #tooBigToHide} —— 对全部 App 生效的面积校验，超过屏幕 45%×45% 的
     *       一律当内容容器，宁可少藏一块也不遮黑。</li>
     * </ol>
     */
    private static void hideSafe(View v, String what) {
        if (v == null) {
            return;
        }
        if ("right".equals(what)) {
            ksRightAllow = "-";
        }
        // v3.0.19：悬浮球「显」模式是用户明确要求「全部正常显示」的开关。
        // 旧版只在 hideEnabled 入口挡了一次，布局监听/rescan 仍会从这里把右侧按钮
        // 再藏一遍（真机表现：绿球写着「显」，旁边点赞评论却不见了）。
        // 在这里统一拦截：任何隐藏请求在 forceShow 下都转为恢复。
        if (forceShow) {
            showDeep(v);
            return;
        }
        // v2.9.2：模块自己的悬浮球永不参与隐藏。它就挂在 decor 上、位置正好落在
        // 「右侧按钮列」的几何区间里，不挡住这一条就会被当成按钮列藏掉。
        if (isOwnOverlay(v)) {
            return;
        }
        if (isVideoApp && !safeToHide(v, what)) {
            // v2.9.3：把「收到了但被形状校验否决」单独记下来。日志里 R=x3 却不动时，
            // 只有这一项能区分「容器没收到」和「收到了被阈值挡住」—— 快手右侧列
            // 卡了六个版本，就是因为这两种情况在日志里长得一模一样。
            if ("right".equals(what)) {
                lastRightReject = geo(v);
                if ("-".equals(ksRightAllow)) {
                    ksRightAllow = "geo";   // 走到这里说明是 2716/2719/2722 否决
                }
            }
            resetShift(v);
            return;
        }
        if (tooBigToHide(v)) {
            if ("right".equals(what)) {
                ksRightAllow = "big";
            }
            // 误判的典型形态：把播放器 / 列表容器当成「底部导航」藏掉，整屏变黑。
            resetShift(v);
            return;
        }
        if ("right".equals(what) && "-".equals(ksRightAllow)) {
            ksRightAllow = "ok";
        }
        transparentDeep(v, what);
    }

    /**
     * 是否模块自己的悬浮层（悬浮球）。区域收集与隐藏都必须跳过，
     * 否则「防烧屏」会把工具本身藏掉，用户看到的就是悬浮球失效。
     */
    private static boolean isOwnOverlay(View v) {
        try {
            return v != null && BALL_TAG.equals(v.getTag());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 面积保险：宽、高都超过屏幕 45% 的 View 不是「控件」，而是内容容器或播放器。
     *
     * <p>真隐藏最容易踩的坑就在这儿 —— 几何评分只看位置和长宽比，竖屏内容区
     * 在「上下各 15%」的判定里也可能拿高分。宽高双超限才拦，避免误伤
     * 底部整条导航（宽 100%、高 8%）。</p>
     */
    private static boolean tooBigToHide(View v) {
        try {
            int sw = screenW(v);
            int sh = screenH(v);
            if (sw <= 0 || sh <= 0) {
                return false;
            }
            return v.getWidth() > sw * 0.45f && v.getHeight() > sh * 0.45f;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- v2.8.4：位置微移 ----------

    /**
     * 推进偏移相位：每 {@link #SHIFT_STEP_MS} 换一格，九宫格绕圈。
     *
     * <p>只在主线程 tick 里调用，节流靠时间戳，不额外开线程。</p>
     */
    private static void updateShiftPhase() {
        long now = SystemClock.elapsedRealtime();
        if (shiftStepAt != 0L && now - shiftStepAt < SHIFT_STEP_MS) {
            return;
        }
        shiftStepAt = now;
        int step = (int) ((now / SHIFT_STEP_MS) % SHIFT_DX.length);
        shiftDx = SHIFT_DX[step];
        shiftDy = SHIFT_DY[step];
    }

    /**
     * 把固定控件整体平移当前偏移量。
     *
     * <p>用 {@code setTranslationX/Y} 而不是改 LayoutParams：前者只影响绘制与
     * 触摸命中区域的变换，不触发父容器重新测量布局，在抖音这种复杂 RecyclerView
     * 里不会引起列表抖动或回收错位。</p>
     */
    private static void shiftDeep(View v, String what) {
        if (v == null) {
            return;
        }
        try {
            int dx = shiftDx;
            int dy = shiftDy;
            if (v.getTranslationX() != dx) {
                v.setTranslationX(dx);
            }
            if (v.getTranslationY() != dy) {
                v.setTranslationY(dy);
            }
            // 曾经被透明隐藏过的控件（旧版本留下的状态），这里顺手恢复可见
            if (v.getAlpha() != 1f) {
                v.setAlpha(1f);
            }
            if (v.getVisibility() == View.INVISIBLE) {
                v.setVisibility(View.VISIBLE);
            }
        } catch (Throwable t) {
            Log.e(TAG, "shift failed: " + what, t);
        }
    }

    /** 偏移归零：退出偏置状态、恢复原始位置（进直播间 / 强制显示 / 开关关闭时）。 */
    private static void resetShift(View v) {
        if (v == null) {
            return;
        }
        try {
            if (v.getTranslationX() != 0f) {
                v.setTranslationX(0f);
            }
            if (v.getTranslationY() != 0f) {
                v.setTranslationY(0f);
            }
            if (v.getAlpha() != 1f) {
                v.setAlpha(1f);
            }
            if (v.getVisibility() == View.INVISIBLE) {
                v.setVisibility(View.VISIBLE);
            }
        } catch (Throwable t) {
            Log.e(TAG, "reset failed", t);
        }
    }

    // ---------- v2.6.9：直播特征页（只用于保守跳过，不触发隐藏） ----------

    private static boolean liveTextMatch(CharSequence cs) {
        String s = normText(cs);
        if (s.isEmpty() || s.length() > 16) {
            return false;
        }
        // v2.9.2：删掉 `s.equals("直播")` 与 `up.contains("LIVE")`。
        // 抖音首页顶部导航就有一个「直播」tab，这两个条件让**普通推荐页永远被判成直播页**，
        // livePaused 一置真，整页隐藏就整轮让位 —— 用户看到的正是「抖音又完了、
        // 导航栏点赞收藏全不藏」。只认「直播中 / 正在直播 / 直播间 / 在线人数」这类
        // 直播间内部才会出现的短语，且长度 ≤16 已排除长文案/评论误命中。
        return s.contains("直播中") || s.contains("正在直播") || s.contains("直播间")
                || s.contains("在线人数") || s.contains("人看过") || s.contains("主播暂时离开");
    }

    /**
     * 当前页面是否带「底部主 Tab 栏」（首页 / 精选 / 发布 / 消息 / 我 那一条）。
     *
     * <p>v2.9.3：这是把「直播页」和「普通视频页」分开的**结构证据**。文案判定
     * （{@link #isLiveTextPage}）一定会误伤 —— 快手 / B站 的推荐流里本来就会滚到
     * 「xxx 直播间」「1.2 万人看过」这种标题，扫到一次整页就被判成直播。
     * 而快手 / B站 的区域开关是 `on = 总开关 && !videoLivePage`，一旦误判，
     * 右侧、顶部、底部、角落**全部**失效，日志里却只有一行 vlive=true，
     * 看起来像「开关没生效」。—— 主 Tab 栏在普通页必然存在、在直播间必然不存在，
     * 用它当闸门比加关键词可靠得多。</p>
     */
    /**
     * v2.9.4：直播间「硬结构闸」——底部必须存在输入条。
     *
     * <p>真机截图给出的互斥特征：普通视频页底部是 @作者 / 视频标题 / 相关搜索，
     * 没有任何输入框；直播间底部固定一条「说点什么…」输入条（外加表情 / 礼物 /
     * 分享按钮）。这是 View 树里直接可读的结构，不依赖文案关键词，也不会像
     * 首页那个「直播」tab 一样在推荐流里误命中。</p>
     *
     * <p>三种写法都覆盖：原生 EditText、hint 文案（未输入时 getText() 为空）、
     * Compose / 自定义输入条（类名不含 EditText 但 TextView 文案能命中）。
     * 只认屏幕下方 28% 区域内的，顶部搜索框不会被算进来。</p>
     */
    private static boolean hasLiveInputBar(View v, int depth) {
        if (v == null || depth > MAX_DEPTH || v.getVisibility() == View.GONE) {
            return false;
        }
        int[] l = loc(v);
        int h = screenH(v);
        boolean inBottom = l != null && h > 0 && l[1] > h * 0.72f;
        if (inBottom && v instanceof android.widget.EditText) {
            return true;
        }
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            String t = normText(tv.getText()) + " " + normText(tv.getHint());
            if (t.contains("说点什么") || t.contains("说点啥") || t.contains("发个弹幕")
                    || t.contains("聊点什么") || t.contains("和大家说点")
                    || t.contains("评论一下")) {
                return true;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasLiveInputBar(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static View findBottomDock(View v, int depth) {
        if (v == null || depth > MAX_DEPTH || v.getVisibility() == View.GONE) {
            return null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int w = screenW(g);
            int h = screenH(g);
            if (w > 0 && h > 0 && g.getChildCount() >= 3
                    && g.getWidth() >= w * 0.85f
                    && g.getHeight() > 0 && g.getHeight() <= h * 0.15f) {
                int[] l = loc(g);
                if (l != null && l[1] + g.getHeight() >= h * 0.88f) {
                    return g;
                }
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findBottomDock(g.getChildAt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
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

    /** v3.0.26：子树里是否有任一文案命中（深度 12 与 MAX_DEPTH 取小，避免深树过大开销）。 */
    private static boolean hasAnyText(View v, int depth, String... keys) {
        if (v == null || depth > 12 || depth > MAX_DEPTH || v.getVisibility() == View.GONE) {
            return false;
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            CharSequence d = v.getContentDescription();
            String ts = t == null ? "" : t.toString();
            String ds = d == null ? "" : d.toString();
            for (String k : keys) {
                if (ts.contains(k) || ds.contains(k)) {
                    return true;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasAnyText(g.getChildAt(i), depth + 1, keys)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * v3.0.26：个人主页 / 作品列表页判定。
     *
     * <p>三类证据任一成立即可，前提是整页没有视频画面（视频页的播放器一旦命中就否决，
     * 不给「主页豁免」误伤视频页的机会）：</p>
     * <ol>
     *   <li>「搜索TA的作品」搜索框 —— 他人主页独有；</li>
     *   <li>「作品」+ 「粉丝 / 获赞 / 私密」 —— 主页统计区；</li>
     *   <li>「编辑资料」 —— 自己主页。</li>
     * </ol>
     */
    private static boolean isProfilePage(View root) {
        if (root == null) {
            return false;
        }
        if (containsVideoSurface(root, 0)) {
            return false;
        }
        if (hasAnyText(root, 0, "搜索TA", "搜索他的作品", "编辑资料")) {
            return true;
        }
        return hasAnyText(root, 0, "作品") && hasAnyText(root, 0, "粉丝", "获赞", "私密");
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
            // v3.0.14：已「拖到最下方收起」的球，同一页面内保持隐藏；
            // 页面更换（新 Activity / 新窗口 = 下次打开）时才恢复。
            if (ballDocked) {
                if (ballView != null && ballView.getParent() == decor) {
                    return;   // 同一页面：保持收起
                }
                ballDocked = false;   // 页面已更换 / 球对象已脱离：视为下次打开，恢复
            }
            Context ctx = decor.getContext();
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int size = (int) (44 * dm.density + 0.5f);
            int defLeft = ballLeft >= 0 ? ballLeft
                    : (dm.widthPixels - size - (int) (8 * dm.density + 0.5f));
            int defTop = ballTop >= 0 ? ballTop : dm.heightPixels / 3;
            if (ballView != null) {
                // 小球仍在当前页面窗口上：只要保证它还在最上层就够了。
                // v2.9.2：抖音页面内部会往 decor 里插新子视图（播放器、浮层），
                // 一旦插在球之上，球就被盖住 —— 看起来「悬浮球失效」，其实是被压到下面。
                if (ballView.getParent() == decor) {
                    // 恢复被收起的球：重新可见 + 回到默认/上次位置
                    if (ballView.getVisibility() != View.VISIBLE) {
                        ballView.setVisibility(View.VISIBLE);
                        ViewGroup.LayoutParams p = ballView.getLayoutParams();
                        if (p instanceof android.widget.FrameLayout.LayoutParams) {
                            android.widget.FrameLayout.LayoutParams flp =
                                    (android.widget.FrameLayout.LayoutParams) p;
                            flp.leftMargin = defLeft;
                            flp.topMargin = defTop;
                            ballView.setLayoutParams(flp);
                        }
                    }
                    ViewGroup dg = (ViewGroup) decor;
                    if (dg.indexOfChild(ballView) != dg.getChildCount() - 1) {
                        ballView.bringToFront();
                    }
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

            TextView ball = new TextView(ctx);
            ball.setGravity(Gravity.CENTER);
            ball.setTextSize(13);
            // v2.9.2：打上标记。区域收集与隐藏都按「位置 + 长宽比」识别容器，
            // 挂在右缘、上部 1/3 的 44dp 小球正好符合「右侧按钮列」的几何特征，
            // 一旦被收进 rightList 就会被透明化 —— 表现就是球还在、点不动也看不见。
            ball.setTag(BALL_TAG);
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
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            // v3.0.14：拖到屏幕最下方（贴底）松手 → 收起隐藏；
                            // 下次打开/进入新页面时自动恢复（见 ensureBall 的 ballDocked 分支）。
                            ViewGroup.LayoutParams p2 = v.getLayoutParams();
                            if (p2 instanceof android.widget.FrameLayout.LayoutParams) {
                                int top = ((android.widget.FrameLayout.LayoutParams) p2).topMargin;
                                if (top + size >= dm.heightPixels - (int) (6 * dm.density)) {
                                    ballDocked = true;
                                    ballLeft = -1;
                                    ballTop = -1;
                                    v.setVisibility(View.GONE);
                                    XposedInit.diag("BALL docked 已收起：下次打开恢复");
                                }
                            }
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
            lp.leftMargin = defLeft;
            lp.topMargin = defTop;
            ((ViewGroup) decor).addView(ball, lp);
            // v2.9.2：显式提到最上层。addView 默认追加到末尾，但抖音的播放器/浮层
            // 若在随后插入，球会被盖住；这里先摆正一次，后续由 tick 的保活继续纠正。
            ball.bringToFront();
            ballView = ball;
            Log.i(TAG, "floating ball on decor forceShow=" + forceShow);
        } catch (Throwable t) {
            Log.e(TAG, "ball create failed", t);
            ballView = null;
        }
    }

    private static void removeBall() {
        ballDocked = false;   // 关闭开关 / 换页后重建 → 不再保持收起
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

    // ---------- v3.0.3：只读可观测性探测（RDUMP / BDUMP）----------
    // 两个探测只读取 View 属性并落日志：不改任何 View 状态、不读写任何区域收集列表。
    // 各自带 30s 节流 + 12 条上限，异常一律在方法内收敛，绝不影响 rescan 主流程。
    /** v3.0.3：只读诊断节流间隔（ms），RDUMP 与 BDUMP 共用。 */
    private static final long READONLY_DUMP_INTERVAL_MS = 30000L;
    /** v3.0.3：单次只读诊断最多记录的候选数。 */
    private static final int READONLY_DUMP_MAX = 12;
    /** v3.0.3：右侧列只读诊断最近一次执行时刻。 */
    private static volatile long lastRightDumpAt = 0L;
    /** v3.0.3：红点/角标只读诊断最近一次执行时刻。 */
    private static volatile long lastBadgeDumpAt = 0L;
    /** v3.0.5：底部标题栏只读诊断最近一次执行时刻（与 RDUMP / BDUMP 各自独立）。 */
    private static volatile long lastTitleDumpAt = 0L;

    /**
     * v3.0.5：屏幕下半部标题 / 描述行的只读诊断。
     * 只读取 View 属性并落日志，不改任何 View 状态、不碰任何区域收集列表；
     * 抖音 / 快手 / 红果 / B站 四端通用，不加 App 门控。异常在方法内收敛。
     */
    private static void dumpBottomTitles(ViewGroup root) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTitleDumpAt < READONLY_DUMP_INTERVAL_MS) {
            return;
        }
        lastTitleDumpAt = now;
        try {
            int w = screenW(root);
            int h = screenH(root);
            if (w <= 0 || h <= 0) {
                return;
            }
            List<View> found = new ArrayList<>();
            collectBottomTitleCands(root, w, h, 0, found);
            XposedInit.diagForce("TDUMP n=" + found.size() + " w=" + w + " h=" + h);
            int limit = Math.min(found.size(), READONLY_DUMP_MAX);
            for (int i = 0; i < limit; i++) {
                XposedInit.diagForce("TDUMP " + readMeta(found.get(i))
                        + " bg=" + bgDesc(found.get(i)));
            }
        } catch (Throwable ignored) {
            // 只读诊断失败不影响主流程
        }
    }

    /** 递归找屏幕下半部、够宽且非巨型的标题行候选，宁可多采不漏（12 条 + 30s 节流兜底）。 */
    private static void collectBottomTitleCands(View v, int w, int h, int depth, List<View> out) {
        if (v == null || depth > MAX_DEPTH || out.size() >= READONLY_DUMP_MAX) {
            return;
        }
        int[] l = loc(v);
        int cw = v.getWidth();
        int ch = v.getHeight();
        if (l != null && cw > w * 0.30f && ch > 0 && l[1] > h * 0.55f
                && ch <= h * 0.5f
                && (v instanceof TextView || containsImage(v, 0) || v.isClickable())
                && (!shortText(v).isEmpty() || !shortDesc(v).isEmpty())) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && out.size() < READONLY_DUMP_MAX; i++) {
                collectBottomTitleCands(g.getChildAt(i), w, h, depth + 1, out);
            }
        }
    }

    /**
     * P2：快手右侧互动列内部结构的只读诊断。
     * 只采集不改动：对右侧区域的大容器递归下沉，记录疑似点赞/评论/收藏按钮的元信息。
     */
    private static void dumpRightIcons(ViewGroup root) {
        if (!isKuaishou || !Config.enableKsRight) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRightDumpAt < READONLY_DUMP_INTERVAL_MS) {
            return;
        }
        lastRightDumpAt = now;
        try {
            int w = screenW(root);
            int h = screenH(root);
            if (w <= 0 || h <= 0) {
                return;
            }
            List<View> found = new ArrayList<>();
            collectRightDumpCands(root, w, h, 0, found);
            XposedInit.diagForce("RDUMP n=" + found.size() + " w=" + w + " h=" + h);
            int limit = Math.min(found.size(), READONLY_DUMP_MAX);
            for (int i = 0; i < limit; i++) {
                XposedInit.diagForce("RDUMP " + readMeta(found.get(i)));
            }
        } catch (Throwable ignored) {
            // 只读诊断失败不影响主流程
        }
    }

    /** 递归找右侧区域内尺寸像按钮的候选：下沉到图标 / 文案 / 可点击节点。 */
    private static void collectRightDumpCands(View v, int w, int h, int depth, List<View> out) {
        if (v == null || depth > MAX_DEPTH || out.size() >= READONLY_DUMP_MAX) {
            return;
        }
        int[] l = loc(v);
        int cw = v.getWidth();
        int ch = v.getHeight();
        if (l != null && cw > 0 && ch > 0
                && l[0] + cw > w * 0.55f
                && cw <= w * 0.35f && ch <= h * 0.30f
                && (v instanceof ImageView || v instanceof TextView || v.isClickable()
                    || matchesRightSemantic(resName(v)) || matchesRightSemantic(shortDesc(v)))) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && out.size() < READONLY_DUMP_MAX; i++) {
                collectRightDumpCands(g.getChildAt(i), w, h, depth + 1, out);
            }
        }
    }

    /** P3：红点 / 数字角标的只读探测。快手为主，其余进程顺带开（全程只读）。 */
    private static void dumpBadges(ViewGroup root) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBadgeDumpAt < READONLY_DUMP_INTERVAL_MS) {
            return;
        }
        lastBadgeDumpAt = now;
        try {
            int w = screenW(root);
            int h = screenH(root);
            if (w <= 0 || h <= 0) {
                return;
            }
            List<View> found = new ArrayList<>();
            collectBadgeCands(root, 0, found);
            XposedInit.diagForce("BDUMP n=" + found.size() + " w=" + w + " h=" + h);
            int limit = Math.min(found.size(), READONLY_DUMP_MAX);
            for (int i = 0; i < limit; i++) {
                XposedInit.diagForce("BDUMP " + readBadgeMeta(found.get(i)));
            }
        } catch (Throwable ignored) {
            // 只读诊断失败不影响主流程
        }
    }

    /** 递归找小尺寸 + 红系背景 / 短数字文本 / 语义命名的红点角标候选。 */
    private static void collectBadgeCands(View v, int depth, List<View> out) {
        if (v == null || depth > MAX_DEPTH || out.size() >= READONLY_DUMP_MAX) {
            return;
        }
        int[] l = loc(v);
        int cw = v.getWidth();
        int ch = v.getHeight();
        if (l != null && cw > 0 && ch > 0 && l[0] >= 0 && l[1] >= 0) {
            String txt = shortText(v);
            boolean semantic = matchesBadgeSemantic(v.getClass().getSimpleName())
                    || matchesBadgeSemantic(resName(v)) || matchesBadgeSemantic(shortDesc(v));
            boolean tiny = cw <= 60 && ch <= 60 && cw >= ch * 0.5f && ch >= cw * 0.5f;
            if (semantic || (tiny && (isShortDigits(txt) || isRedBackground(v)))) {
                out.add(v);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && out.size() < READONLY_DUMP_MAX; i++) {
                collectBadgeCands(g.getChildAt(i), depth + 1, out);
            }
        }
    }

    /**
     * v3.0.6 P2 第一道判定：红点候选的黑名单一票否决。
     * 【最高安全红线】本方法返回 true = 永不进入隐藏列表 = 保住用户互动数据。
     * 判据全部来自真机日志 L187-464 的多轮采样，不猜规则。
     * 异常一律返回 true（安全失败 = 禁藏），绝不放行未知节点。
     */
    private static boolean isBadgeForbidden(View v, View parent) {
        if (v == null) {
            return true;
        }
        try {
            String cls = v.getClass().getSimpleName();
            String id = resName(v);
            String pcls = parent == null ? "" : parent.getClass().getSimpleName();
            String bg = bgDesc(v);

            // ① parent=TextSwitcher → 纯数字计数容器（DuxTextView id=0，txt 单调递增）
            if ("TextSwitcher".equals(pcls)) {
                return true;
            }
            // ② 显式排除：已实证的计数 / 互动数字角标
            if ("DuxTextView".equals(cls) && "0".equals(id)) {
                return true;
            }
            if ("DmtTextView".equals(cls) && "guq".equals(id)) {
                return true;
            }
            // ③ bg=none 且纯数字 → 一律按计数禁藏（覆盖未具名的新计数节点）
            if ((bg == null || bg.isEmpty() || "none".equals(bg))
                    && isShortDigits(shortText(v))) {
                return true;
            }
            // ④ 进度条 / 身份标识：名字即红线，绝不当红点
            if ("milano_player_seekbar".equals(id)
                    || "featured_god_comment_tip".equals(id)
                    || "vip_badge".equals(id)) {
                return true;
            }
            return false;
        } catch (Throwable t) {
            // 宿主布局树异常时唯一安全选择：禁藏
            return true;
        }
    }

    /**
     * v3.0.6 P2 第二道判定：仅在 isBadgeForbidden()==false 之后调用。
     * 类名 + resource-id + 背景 三元 AND；未命中一律 false（默认拒绝）。
     */
    private static boolean isBadgeSafe(View v) {
        if (v == null) {
            return false;
        }
        try {
            String cls = v.getClass().getSimpleName();
            String id = resName(v);
            String bg = bgDesc(v);
            boolean red = bg != null && ("#fff73b68".equals(bg) || bg.contains("ShapeDrawable"));

            // 快手：SelectShapeView#red_dot 26×26 bg=#fff73b68
            if ("SelectShapeView".equals(cls) && "red_dot".equals(id) && red) {
                return true;
            }
            // 快手：OnlineDotView#online_dot 72×72 bg=LayerDrawable（四元 AND，上限 80 留分版余量）
            if ("OnlineDotView".equals(cls) && "online_dot".equals(id)
                    && "LayerDrawable".equals(bg)
                    && v.getWidth() <= 80 && v.getHeight() <= 80) {
                return true;
            }
            // 抖音：DuxBadgeView#1ky bg=ShapeDrawable txt 恒定
            if ("DuxBadgeView".equals(cls) && "1ky".equals(id) && red) {
                return true;
            }
            // 快手：notify / right_avatar_notify，bg 必为红底
            if (v instanceof TextView
                    && ("notify".equals(id) || "right_avatar_notify".equals(id))
                    && bg != null && "#fff73b68".equals(bg)) {
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;   // 白名单异常 = 不放行（与黑名单的安全方向一致）
        }
    }

    // ↓↓↓ HollowAvatarBadgeLayout 特殊处理：只收 badge 小子节点，容器整块永不入列 ↓↓↓
    /** v3.0.6 P2：红点收集。黑名单先行 → 白名单放行 → 默认拒绝；命中即止。 */
    private static void collectRedDots(View v, List<View> out, int depth) {
        if (v == null || depth > MAX_DEPTH || !(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null) {
                continue;
            }
            View parent = c.getParent() instanceof View ? (View) c.getParent() : null;

            // 【第一道】黑名单：一票否决，连白名单都不看
            if (isBadgeForbidden(c, parent)) {
                continue;
            }
            // 【第二道】白名单
            boolean underBadgeLayout = parent != null
                    && "HollowAvatarBadgeLayout".equals(parent.getClass().getSimpleName());
            boolean hit = isBadgeSafe(c)
                    || (underBadgeLayout && isSmallBadgeChild(c));
            if (hit) {
                if (!out.contains(c)) {
                    out.add(c);
                }
                continue;   // 命中即止：不再下沉，避免把 badge 内部贴图误当红点
            }
            // 未命中也继续下沉：真机结构里红点可能嵌在任意层容器
            collectRedDots(c, out, depth + 1);
        }
    }

    /**
     * HollowAvatarBadgeLayout 的 badge 子节点准入：小尺寸 + 黑名单二次判定 + 精确枚举，
     * 绝不收 130×130 整块。
     * 【S2·精确枚举】废弃语义子串模糊匹配的方案（"red"/"dot" 会误命中 red_packet 等控件）。
     * 只认下方六项 equals 精确值，或 bg 恰为红底；再叠加外层 underBadgeLayout + 尺寸 + 黑名单二次判定。
     */
    private static boolean isSmallBadgeChild(View v) {
        try {
            int cw = v.getWidth(), ch = v.getHeight();
            if (cw <= 0 || ch <= 0 || cw > 60 || ch > 60) {
                return false;
            }
            if (isBadgeForbidden(v, null)) {     // 二次判定：对子节点再跑一次黑名单
                return false;
            }
            String id = resName(v);
            String bg = bgDesc(v);
            return isExactBadgeId(id) || "#fff73b68".equals(bg);
        } catch (Throwable t) {
            return false;                        // 白名单异常 = 不放行
        }
    }

    /** S2：badge 子节点 resource-id 的精确枚举（equals，非 contains）。 */
    private static boolean isExactBadgeId(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        return "red_dot".equals(id) || "online_dot".equals(id)
                || "badge".equals(id) || "notify".equals(id)
                || "right_avatar_notify".equals(id) || "1ky".equals(id);
    }

    /** rescan 插入点 A/B 调用：收集 + 恢复旧节点 + 提交（照抄 :621 / :656 的 releaseAll 写法）。 */
    private static void updateRedDots(ViewGroup root) {
        boolean allowed = Config.enableRedDot
                && (isKuaishou || (!isVideoApp && !isDrama));  // 门控：仅快手/抖音；红果不写规则
        List<View> next = new ArrayList<>();
        if (allowed && Config.enableStatus && !videoLivePage) {
            collectRedDots(root, next, 0);
        }
        if (!next.equals(redDotList)) {
            releaseAll(redDotList, next);
            redDotList.clear();
            redDotList.addAll(next);
            XposedInit.diag("RED x" + redDotList.size());
        }
    }

    /**
     * v3.0.6 P3：下方标题栏收集。三 App 各自精确三元锚点；红果门控不进本方法。
     * 只收叶子 TextView，不收父容器，因此作者行/声明/视频画面天然不受影响。
     * 排除项（其类名/id/parent 三条件都不符，天然不命中）：
     *   快手 user_name_text_view / general_entry_title / slide_play_photo_disclaimer_text；
     *   抖音 DmtTextView#title（@作者）；B站 StoryUpNameWidget#name / creative_text。
     */
    private static void collectCaptions(View v, List<View> out, int depth) {
        if (v == null || depth > MAX_DEPTH || !(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || !(c instanceof TextView)) {
                continue;
            }
            View parent = c.getParent() instanceof View ? (View) c.getParent() : null;
            String pcls = parent == null ? "" : parent.getClass().getSimpleName();
            String cls = c.getClass().getSimpleName();
            String id = resName(c);
            boolean hit =
                    (isKuaishou && "CaptionTextView".equals(cls)
                            && "element_caption_label".equals(id))
                    || (!isVideoApp && !isDrama && "ScrollMentionTextView".equals(cls)
                            && "desc".equals(id) && "FeedDescParentLayout".equals(pcls))
                    || (isBilibili && "StoryTitleWidget".equals(cls)
                            && "title".equals(id) && "StoryInfoModule".equals(pcls));
            if (hit) {
                if (!out.contains(c)) {
                    out.add(c);
                }
                continue;   // 命中即止，不收其内部 span/子 View
            }
            collectCaptions(c, out, depth + 1);
        }
    }

    /** rescan 插入点 B 调用。 */
    private static void updateCaptions(ViewGroup root) {
        boolean allowed = Config.enableBottomCaption && Config.enableStatus
                && !isDrama && !videoLivePage;              // 红果不进
        List<View> next = new ArrayList<>();
        if (allowed) {
            collectCaptions(root, next, 0);
        }
        if (!next.equals(captionList)) {
            releaseAll(captionList, next);
            captionList.clear();
            captionList.addAll(next);
            XposedInit.diag("CAPTION x" + captionList.size());
        }
    }

    /** 右侧按钮常见语义匹配（只读文本判断）。 */
    private static boolean matchesRightSemantic(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String x = s.toLowerCase(java.util.Locale.ROOT);
        return x.contains("like") || x.contains("comment") || x.contains("collect")
                || x.contains("favor") || x.contains("digg") || x.contains("share")
                || x.contains("赞") || x.contains("评") || x.contains("藏");
    }

    /** 红点 / 角标语义匹配（只读文本判断）。 */
    private static boolean matchesBadgeSemantic(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String x = s.toLowerCase(java.util.Locale.ROOT);
        return x.contains("badge") || x.contains("dot") || x.contains("red")
                || x.contains("unread") || x.contains("角标") || x.contains("红点");
    }

    // ==================== v3.0.7：定向隐藏通道 THH（Targeted Hide Channel）====================
    // 与 livePaused / anyEnabled / hideEnabled / showAll 完全并行：只读直播状态当门控，
    // 不写 livePaused、不进相位调度、不进 showAll。四列表各自门控、互不串台。
    // 施加出口复用 hideSmallSafe（不新增隐藏出口），容器类额外过 safeToHide 形状白名单（S1）。

    /**
     * v3.0.7 数据命门：纯数字互动计数（719 / 0/20 / 238/2425 / 3/62 / 405万前的数字）一票否决。
     * 正则 ^[0-9]+([./][0-9]+)*$，normText 折叠空白后匹配；命中即永不入任何 THH 列表。
     * 只认纯数字与分数/小数形式，中文数字后缀（如 405万）不在其列 —— 那类文案走强文案父卡，
     * 数字自身仍不单独入列（由文案锚点决定，非 isCountLike 放行）。
     */
    private static boolean isCountLike(CharSequence cs) {
        String s = normText(cs);
        return !s.isEmpty() && s.length() <= 24 && THH_COUNT_PATTERN.matcher(s).matches();
    }

    /**
     * v3.0.7 只读 helper：文案锚点命中（getText + contentDescription 都认，normText 折叠后 contains）。
     */
    private static boolean targetTextHit(View v, String[] keys) {
        if (!(v instanceof TextView) || keys == null) {
            return false;
        }
        String s = normText(((TextView) v).getText()) + normText(v.getContentDescription());
        if (s.isEmpty()) {
            return false;
        }
        for (String k : keys) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * v3.0.7 只读 helper：容器子树内累计命中 >= min 个「不同」锚点（多锚 AND，防单锚误判）。
     * recursion 限深 6 层、文本上限 2048，避免大容器拼串拖慢扫描。
     */
    private static boolean targetTextAll(ViewGroup g, String[] keys, int min) {
        if (g == null || keys == null || min <= 0) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        appendTargetText(g, sb, 0);
        int hit = 0;
        for (String k : keys) {
            if (sb.indexOf(k) >= 0) {
                hit++;
                if (hit >= min) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void appendTargetText(View v, StringBuilder sb, int depth) {
        if (v == null || depth > 6 || sb.length() > 2048) {
            return;
        }
        if (v instanceof TextView) {
            sb.append(normText(((TextView) v).getText()))
                    .append(normText(v.getContentDescription()));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                appendTargetText(g.getChildAt(i), sb, depth + 1);
            }
        }
    }

    /** v3.0.7 单维几何上限：候选宽、高都不得超过屏幕对应维度的 maxW / maxH（沿用 S3 收窄）。 */
    private static boolean targetGeoOk(View v, float maxW, float maxH) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        int w = screenW(v);
        int h = screenH(v);
        if (w <= 0 || h <= 0) {
            return false;
        }
        return v.getWidth() <= w * maxW && v.getHeight() <= h * maxH;
    }

    // ---------- v3.0.11 P0a/P0b：只读 UI 上下文探针（纯加性，不改任何既有函数/出口）----------

    /** v3.0.11 P0a：只读判定 v 自身/直接父是否可点击；不触发 performClick，不写任何 View 属性。 */
    private static boolean isClickableControl(View v) {
        if (v == null) {
            return false;
        }
        if (v.isClickable() || v.isFocusable() || v.hasOnClickListeners()) {
            return true;
        }
        ViewParent p = v.getParent();
        return p instanceof View
                && (((View) p).isClickable() || ((View) p).hasOnClickListeners());
    }

    /**
     * v3.0.11 P0b：只读识别"用户主页/作品列表"上下文。
     * 两个结构条件同时成立才判为用户主页（与 2/3/单列布局、卡片绝对尺寸无关）：
     *   A 多卡同构：某祖先容器的直接子节点中 >=2 个"内容卡"（封面图锚 + 文本锚）；
     *   B 无面积主导视频：该祖先容器子树内不存在占屏面积主导的视频画面。
     * fail-closed、纯只读：证据不足即 false（保持原隐藏行为）。
     */
    private static boolean isProfileListContext(View v) {
        if (v == null) {
            return false;
        }
        View node = v;
        for (int up = 0; up < MAX_DEPTH && node != null; up++) {
            ViewParent p = node.getParent();
            if (!(p instanceof View)) {
                break;
            }
            View parent = (View) p;
            if (parent instanceof ViewGroup
                    && countContentCards((ViewGroup) parent) >= 2
                    && !hasDominantVideoSurface(parent)) {
                return true;   // 多卡同构 + 无主导视频 = 用户主页/作品列表
            }
            node = parent;
        }
        return false;
    }

    /** A 实现：内容卡数量（早停于 2）。直接子节点优先；不足时再下探一层
     *  —— 主页作品网格常见「行容器 → 多个作品卡」，只看直接子节点会漏判，
     *  导致作品封面被当成固定 UI 透明掉（v3.0.14 误伤修复）。
     *  不做任何卡片绝对尺寸判断。 */
    private static int countContentCards(ViewGroup g) {
        int n = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() == View.GONE
                    || c.getWidth() <= 0 || c.getHeight() <= 0) {
                continue;
            }
            if (hasCoverImage(c, 0) && hasChildByClass(c, "TextView")) {
                n++;
                if (n >= 2) {
                    return 2;
                }
            }
        }
        // v3.0.14：二层识别 —— 行容器里的作品卡
        for (int i = 0; i < g.getChildCount(); i++) {
            View row = g.getChildAt(i);
            if (!(row instanceof ViewGroup) || row.getVisibility() == View.GONE
                    || row.getWidth() <= 0 || row.getHeight() <= 0) {
                continue;
            }
            ViewGroup rg = (ViewGroup) row;
            for (int j = 0; j < rg.getChildCount(); j++) {
                View card = rg.getChildAt(j);
                if (card == null || card.getVisibility() == View.GONE
                        || card.getWidth() <= 0 || card.getHeight() <= 0) {
                    continue;
                }
                if (hasCoverImage(card, 0) && hasChildByClass(card, "TextView")) {
                    n++;
                    if (n >= 2) {
                        return 2;
                    }
                }
            }
        }
        return n;
    }

    /** 子树是否含"封面图"视图；instanceof 覆盖 ImageView 子类，弥补按类名匹配的盲区。 */
    private static boolean hasCoverImage(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return false;
        }
        if (v instanceof android.widget.ImageView
                || v instanceof android.view.TextureView
                || v instanceof android.view.SurfaceView) {
            return true;
        }
        String n = v.getClass().getName();
        if (n.contains("ImageView") || n.contains("Drawee")
                || n.contains("Image") || n.contains("Photo")) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasCoverImage(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 子树内是否存在"占屏面积主导"的视频画面（直播/信息流页特征）。 */
    private static boolean hasDominantVideoSurface(View v) {
        return hasDominantVideoSurface(v, 0);
    }

    private static boolean hasDominantVideoSurface(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return false;
        }
        if (isVideoView(v)) {
            int w = screenW(v), h = screenH(v);
            if (w > 0 && h > 0 && v.getWidth() > 0 && v.getHeight() > 0) {
                float rw = (float) v.getWidth() / w;
                float rh = (float) v.getHeight() / h;
                // 面积主导 OR 双维均超 45%（宪法 §3 面积保险同口径）
                if (rw * rh > 0.50f || (rw > 0.45f && rh > 0.45f)) {
                    return true;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasDominantVideoSurface(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 视频画面判据复刻 containsVideoSurface(L1028-1036) 的 instanceof + 类名两路，保持单一真源风格。 */
    private static boolean isVideoView(View v) {
        if (v instanceof android.view.TextureView
                || v instanceof android.view.SurfaceView
                || v instanceof android.widget.VideoView) {
            return true;
        }
        String n = v.getClass().getName();
        return n.contains("TextureView") || n.contains("SurfaceView") || n.contains("VideoView")
                || n.contains("MediaTexture") || n.contains("GLTextureView");
    }

    /**
     * v3.0.7：从命中叶子向上并进最内层静态父容器（<= maxUp 层）。
     * 每层重跑 isOwnedElsewhere + 单维 50% + 视频否决 + 动态内容否决，任一命中立即停；
     * 只并进「未被任何既有通道认领」的静态小卡，杜绝双重隐藏与越权接管。
     */
    private static View targetLeafUp(View v, int maxUp, float maxW, float maxH) {
        View best = v;
        ViewParent p = v == null ? null : v.getParent();
        for (int up = 0; up < maxUp && p instanceof View; up++) {
            View c = (View) p;
            if (!targetGeoOk(c, maxW, maxH) || isOwnedElsewhere(c)
                    || containsVideoSurface(c, 0) || containsDynamicUi(c, 0)) {
                break;
            }
            best = c;
            p = c.getParent();
        }
        return best;
    }

    /**
     * v3.0.7 权威跳过清单（评审 T1：统一为全部 13 项）：10 个既有列表 + 3 个顶部/角落 View。
     * 所有 THH collector 首行强制调用；命中即跳过，THH 只拿「未被任何既有通道认领」的静态元素。
     * 这是「不破坏 v2.9.4 / v3.0.6 / v3.0.4」的结构性保证，不靠测试碰运气。
     */
    private static boolean isOwnedElsewhere(View v) {
        if (v == null) {
            return false;
        }
        if (rightList.contains(v) || bottomList.contains(v) || swipeHintList.contains(v)
                || centerPlusList.contains(v) || redDotList.contains(v) || captionList.contains(v)
                || liveTopList.contains(v) || liveBottomList.contains(v) || liveRightList.contains(v)
                || dramaPaywallList.contains(v)) {
            return true;
        }
        return v == topBar || v == rightTop || v == leftTop;
    }

    /**
     * v3.0.7 施加出口（评审 S1 安全底线）：
     * 叶子 TextView 只走 hideSmallSafe 三道通用保险；
     * 【容器类目标】（主播卡 / 招聘卡 / 信息卡 / 底部导航）在 hideSmallSafe 之外
     * 【再过一道 safeToHide(v, what) 形状白名单】——挡下的候选 showDeep 恢复、绝不 INVISIBLE。
     * 不新增隐藏出口、不改 safeToHide / hideSmallSafe 本体、不新增 what 分支。
     */
    private static void hideTargeted(View v, String what, boolean container) {
        if (v == null) {
            return;
        }
        if (container && !safeToHide(v, what)) {
            showDeep(v);
            return;
        }
        hideSmallSafe(v, what);
    }

    /** v3.0.7 单列表施加：门控开则隐藏（容器过 safeToHide），关则整表 showDeep 恢复。 */
    private static void applyTargetedList(int slot, List<View> list) {
        boolean on = targetedGate(slot);
        for (View v : list) {
            if (!on) {
                showDeep(v);
                continue;
            }
            boolean container = !(v instanceof TextView);
            String what = "target";
            // S2：红果底部导航（三锚 AND 命中的容器）走矮横条门槛 bottom，且已过 isOwnedElsewhere / tooBigToHide
            if (container && slot == SLOT_DRAMA && v instanceof ViewGroup
                    && targetTextAll((ViewGroup) v, DRAMA_NAV_KEYS, 3)) {
                what = "bottom";
            }
            hideTargeted(v, what, container);
        }
    }

    /** v3.0.7 列表提交：先恢复本轮不再命中的 View，再整体替换（复用 v2.9.3 releaseAll）。 */
    private static void commitTargeted(List<View> old, List<View> next) {
        releaseAll(old, next);
        old.clear();
        old.addAll(next);
    }

    /**
     * v3.0.7 门控解析：只读 livePaused / videoLivePage，绝不写、不翻转。
     * P1 快手直播间读 videoLivePage（快手 pausedNow 恒 false，见 §1.2 关键发现）；
     * P2 抖音直播间读 livePaused（硬结构闸结果）；P3/P4 靠进程 + 页面信号隔离。
     */
    private static boolean targetedGate(int slot) {
        if (!Config.enableStatus || !isScreenOn() || forceShow
                || ConfStore.ENGINE_OVERLAY.equals(Config.engine)) {
            // v3.0.8 纯加性：硬关条件 → DY_LIVE 迟滞立即复位；整表恢复沿用既有 !on 路径
            if (slot == SLOT_DY_LIVE) {
                thhLiveGate.reset();
                lastThhDyStableLogged = false;
            }
            return false;
        }
        switch (slot) {
            case SLOT_KS_LIVE:
                return Config.enableKsLiveHits && isKuaishou && videoLivePage;
            case SLOT_DY_LIVE: {
                // v3.0.8 改造点 G：不再直读 livePaused 当门控
                if (!Config.enableDyLiveHits || isVideoApp || isDrama) {
                    thhLiveGate.reset();
                    lastThhDyStableLogged = false;
                    return false;
                }
                boolean before = thhLiveGate.output();
                // raw = tick:1942「不会清 livePaused」的逻辑析取：
                //   !( !liveStrongHit && !videoLivePage && !liveInputSeen )
                // 即只要任一直播结构证据仍在，就认定「仍在直播间」；只有连续 N 次全部落空才放行。
                // 全部为只读信号，本行及其上下文不得出现任何 livePaused 写操作。
                boolean raw = livePaused || liveStrongHit || videoLivePage || liveInputSeen;
                boolean nowStable = thhLiveGate.feed(raw, SystemClock.elapsedRealtime());
                if (nowStable != before) {
                    thhDyLiveTransitionDiag(nowStable, raw);   // 边沿触发的强制诊断（P3-a 可靠性）
                    lastThhDyStableLogged = nowStable;
                }
                return nowStable;
            }
            case SLOT_KS_FEED:
                return Config.enableKsFeedHits && isKuaishou && !videoLivePage;
            case SLOT_DRAMA:
                return Config.enableDramaInfoHits && isDrama;
            default:
                return false;
        }
    }

    /**
     * v3.0.7 唯一入口：root != null 先收集四列表再施加（rescan 插入点 T）；
     * root == null 只按门控施加/恢复已有列表（tick / onResume / onGlobalLayout）。
     */
    private static void updateTargetedHide(ViewGroup root) {
        try {
            if (root != null) {
                List<View> ksl = new ArrayList<>();
                List<View> dyl = new ArrayList<>();
                List<View> ksf = new ArrayList<>();
                List<View> dr = new ArrayList<>();
                if (Config.enableKsLiveHits && isKuaishou) {
                    collectKsLiveTargets(root, ksl, 0);
                }
                if (Config.enableDyLiveHits && !isVideoApp && !isDrama) {
                    collectDyLiveTargets(root, dyl, 0);
                }
                if (Config.enableKsFeedHits && isKuaishou) {
                    collectKsFeedTargets(root, ksf, 0);
                }
                if (Config.enableDramaInfoHits && isDrama) {
                    collectDramaTargets(root, dr, 0);
                }
                commitTargeted(ksLiveTargetList, ksl);
                commitTargeted(dyLiveTargetList, dyl);
                commitTargeted(ksFeedTargetList, ksf);
                commitTargeted(dramaTargetList, dr);
            }
            applyTargetedList(SLOT_KS_LIVE, ksLiveTargetList);
            applyTargetedList(SLOT_DY_LIVE, dyLiveTargetList);
            applyTargetedList(SLOT_KS_FEED, ksFeedTargetList);
            applyTargetedList(SLOT_DRAMA, dramaTargetList);
            dumpThhTargets();
            targetedDiag(ksLiveTargetList.size(), dyLiveTargetList.size(),
                    ksFeedTargetList.size(), dramaTargetList.size());
        } catch (Throwable ex) {
            Log.e(TAG, "THH update failed", ex);
        }
    }

    /** v3.0.7 P1 快手直播间 collector：主播卡结构锚 + 职位榜文案 + 招聘大卡多锚；数字一票否决。 */
    private static void collectKsLiveTargets(View v, List<View> out, int depth) {
        if (v == null || depth > MAX_DEPTH || v.getVisibility() == View.GONE) {
            return;
        }
        if (isOwnedElsewhere(v)) {
            return;
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (targetTextHit(v, KS_LIVE_KEYS) && !isCountLike(t)) {
                // v3.0.11 P0：可点击功能入口 / 用户主页作品列表上下文跳过
                if (isClickableControl(v) || isProfileListContext(v)) {
                    return;
                }
                View tgt = targetLeafUp(v, 2, 0.50f, 0.50f);
                if (tgt != null && !containsDynamicUi(tgt, 0)
                        && !containsVideoSurface(tgt, 0) && !out.contains(tgt)) {
                    out.add(tgt);
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            // 主播卡：复用 v3.0.4 已验证的两类名，但写在独立 collector，不碰 v3.0.4 分支
            if (hasChildByClass(g, "AvatarWithPendantView")
                    && hasChildByClass(g, "FollowButtonView")
                    && targetGeoOk(g, 0.50f, 0.50f)
                    && !containsDynamicUi(g, 0) && !containsVideoSurface(g, 0)
                    && !isProfileListContext(g)) {   // v3.0.11 P0b：用户主页作品卡不隐藏
                if (!out.contains(g)) {
                    out.add(g);
                }
                return; // 主播卡走结构锚，不再下沉吞子节点
            }
            // 招聘大卡：≥2 锚 AND，静态、无动态内容
            if (targetTextAll(g, KS_RECRUIT_KEYS, 2) && targetGeoOk(g, 0.50f, 0.50f)
                    && !containsDynamicUi(g, 0) && !containsVideoSurface(g, 0)) {
                if (!out.contains(g)) {
                    out.add(g);
                }
                return;
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                collectKsLiveTargets(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** v3.0.7 P2 抖音直播间 collector：人气榜/直播广场文案 + 主播卡关注结构锚；数字一票否决。 */
    private static void collectDyLiveTargets(View v, List<View> out, int depth) {
        if (v == null || depth > MAX_DEPTH || v.getVisibility() == View.GONE) {
            return;
        }
        if (isOwnedElsewhere(v)) {
            return;
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (targetTextHit(v, DY_LIVE_KEYS) && !isCountLike(t)) {
                // v3.0.11 P0：可点击功能入口 / 用户主页作品列表上下文跳过
                if (isClickableControl(v) || isProfileListContext(v)) {
                    return;
                }
                View tgt = targetLeafUp(v, 2, 0.50f, 0.50f);
                if (tgt != null && !containsDynamicUi(tgt, 0)
                        && !containsVideoSurface(tgt, 0) && !out.contains(tgt)) {
                    out.add(tgt);
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            // 主播卡：头像类 + 关注/已关注 文案，且未被直播三区认领
            if (hasChildByClass(g, "AvatarWithPendantView")
                    && targetTextAll(g, DY_FOLLOW_KEYS, 1)
                    && targetGeoOk(g, 0.50f, 0.50f)
                    && !containsDynamicUi(g, 0) && !containsVideoSurface(g, 0)
                    && !isProfileListContext(g)) {   // v3.0.11 P0b：用户主页作品卡不隐藏
                if (!out.contains(g)) {
                    out.add(g);
                }
                return;
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                collectDyLiveTargets(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** v3.0.7 P3 快手刷视频 collector：全屏观看 + AI 提示文案；右侧列沿用 enableRightActions 不碰。 */
    private static void collectKsFeedTargets(View v, List<View> out, int depth) {
        if (v == null || depth > MAX_DEPTH || v.getVisibility() == View.GONE) {
            return;
        }
        if (isOwnedElsewhere(v)) {
            return;
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (targetTextHit(v, KS_FEED_KEYS) && !isCountLike(t)) {
                // v3.0.11 P0a：可点击功能入口跳过；P0b：用户主页作品列表上下文跳过
                if (isClickableControl(v) || isProfileListContext(v)) {
                    return;
                }
                View tgt = targetLeafUp(v, 2, 0.50f, 0.50f);
                if (tgt != null && !containsDynamicUi(tgt, 0)
                        && !containsVideoSurface(tgt, 0) && !out.contains(tgt)) {
                    out.add(tgt);
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectKsFeedTargets(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** v3.0.7 P4 红果剧集页 collector：信息区全文案锚点 + 底部导航三锚 AND（宪法例外，已批准）。 */
    private static void collectDramaTargets(View v, List<View> out, int depth) {
        if (v == null || depth > MAX_DEPTH || v.getVisibility() == View.GONE) {
            return;
        }
        if (isOwnedElsewhere(v)) {
            return;
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (targetTextHit(v, DRAMA_KEYS) && !isCountLike(t)) {
                // v3.0.11 P0b：用户主页/作品列表上下文跳过（红果剧集页通常返回 false）
                if (isProfileListContext(v)) {
                    return;
                }
                View tgt = targetLeafUp(v, 2, 0.50f, 0.50f);
                if (tgt != null && !containsDynamicUi(tgt, 0)
                        && !containsVideoSurface(tgt, 0) && !out.contains(tgt)) {
                    out.add(tgt);
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            // 底部导航：三锚 AND + 矮横条几何（高 <= 30%），且不得命中 bottomList（isOwnedElsewhere 已含）
            if (targetTextAll(g, DRAMA_NAV_KEYS, 3) && targetGeoOk(g, 1.0f, 0.30f)
                    && !containsDynamicUi(g, 0) && !containsVideoSurface(g, 0)) {
                if (!out.contains(g)) {
                    out.add(g);
                }
                return;
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                collectDramaTargets(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    /**
     * v3.0.7 P5 只读探针：对 THH 四列表各 dump 元信息（class/id/text/bounds），30s 节流。
     * 只读取 View 属性、不改任何 View、不碰区域收集列表；仅在 Config.enableLog 时落日志。
     * 复用既有 readMeta / READONLY_DUMP_INTERVAL_MS / READONLY_DUMP_MAX，异常在方法内收敛。
     */
    private static void dumpThhTargets() {
        if (!Config.enableLog) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastThhDumpAt != 0L && now - lastThhDumpAt < READONLY_DUMP_INTERVAL_MS) {
            return;
        }
        lastThhDumpAt = now;
        try {
            dumpThhSlot(SLOT_KS_LIVE, ksLiveTargetList);
            dumpThhSlot(SLOT_DY_LIVE, dyLiveTargetList);
            dumpThhSlot(SLOT_KS_FEED, ksFeedTargetList);
            dumpThhSlot(SLOT_DRAMA, dramaTargetList);
        } catch (Throwable ignored) {
            // 只读诊断失败不影响主流程
        }
    }

    private static void dumpThhSlot(int slot, List<View> list) {
        XposedInit.diagForce("THH slot=" + slot + " n=" + list.size());
        int limit = Math.min(list.size(), READONLY_DUMP_MAX);
        for (int i = 0; i < limit; i++) {
            XposedInit.diagForce("THH slot=" + slot + " " + readMeta(list.get(i)));
        }
    }

    // ---------- v3.0.8 P3：直播间评论区/小心心只读 dump + 门控边沿诊断（纯加性）----------
    private static final String[] LIVE_UI_KEYS = {"评论", "小心心", "点赞", "转发", "说点什么"};

    /** v3.0.8 P3-a：DY_LIVE 门控边沿强制落盘，只输出标志位与计数，绝不输出 View 文本。 */
    private static void thhDyLiveTransitionDiag(boolean nowStable, boolean raw) {
        XposedInit.diagForce("THH DY-LIVE " + (nowStable ? "ENTER" : "EXIT")
                + " stable=" + (nowStable ? 1 : 0)
                + " raw=" + (raw ? 1 : 0)
                + " hit=" + thhLiveGate.hitStreak()
                + " miss=" + thhLiveGate.missStreak()
                + " dyL=" + dyLiveTargetList.size());
    }

    /** v3.0.8 P3-b：直播间评论区/小心心只读 dump。零 View 修改、30s 节流、结构元信息无文本输出。 */
    private static void dumpLiveUi(ViewGroup root) {
        if (!Config.enableLog || root == null || !isLive()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastLiveUiDumpAt != 0L && now - lastLiveUiDumpAt < READONLY_DUMP_INTERVAL_MS) {
            return;
        }
        lastLiveUiDumpAt = now;
        final int[] n = {0};
        try {
            dumpLiveUiNode(root, n, 0);
            XposedInit.diag("LIVE-UI dump n=" + n[0]);
        } catch (Throwable ignored) {
            // 只读诊断失败不影响主流程
        }
    }

    private static void dumpLiveUiNode(View v, int[] n, int depth) {
        if (v == null || depth > MAX_DEPTH || n[0] >= READONLY_DUMP_MAX) {
            return;
        }
        String hit = null;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            String s = normText(t) + normText(v.getContentDescription());
            for (String k : LIVE_UI_KEYS) {
                if (s.contains(k)) {
                    hit = k;
                    break;
                }
            }
            // content-desc 也作为锚点（小心心常是纯图标）
            if (hit == null) {
                String d = normText(v.getContentDescription());
                for (String k : LIVE_UI_KEYS) {
                    if (d.contains(k)) {
                        hit = k;
                        break;
                    }
                }
            }
        }
        if (hit != null) {
            // D5 整改：改调 readMetaLite —— 只输出结构元信息，无 txt=/desc=。
            // 上方 getText()/getContentDescription() 仅用于 contains(k) 锚点判定，其返回值不进入日志。
            XposedInit.diag("LIVE-UI " + readMetaLite(v) + " anchor=" + safeDumpText(hit));
            n[0]++;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && n[0] < READONLY_DUMP_MAX; i++) {
                dumpLiveUiNode(g.getChildAt(i), n, depth + 1);
            }
        }
    }

    /**
     * v3.0.8 P3-b / D5 整改（主理人裁决「立即升级 readMetaLite，不再讨论」）：
     * 只输出结构元信息 cls/id/size/pos/click/img/parent，绝不输出任何文本字段。
     * 硬约束：方法体内不得出现 getText / getContentDescription / shortText / shortDesc，
     *         也不得出现 "txt=" / "desc=" 字面量 —— 从结构上切断原文入日志的路径。
     */
    private static String readMetaLite(View v) {
        if (v == null) {
            return "cls=null";
        }
        String cls = v.getClass().getSimpleName();                 // 静态类名，非用户文本
        int id = v.getId();
        String idHex = (id == View.NO_ID) ? "none" : "0x" + Integer.toHexString(id);
        int w = v.getWidth(), h = v.getHeight();                   // size
        int l = v.getLeft(), t = v.getTop();                       // pos（相对父容器）
        boolean click = v.isClickable();
        boolean img = (v instanceof ImageView);
        Object p = v.getParent();
        String parent = (p == null) ? "none" : p.getClass().getSimpleName();
        return "cls=" + cls
                + " id=" + idHex
                + " size=" + w + "x" + h
                + " pos=" + l + "," + t
                + " click=" + (click ? 1 : 0)
                + " img=" + (img ? 1 : 0)
                + " parent=" + parent;
    }

    /** 只回显白名单静态锚点；任何非白名单输入一律 <omitted>，从源头避免手机号/账号/昵称带出。 */
    private static String safeDumpText(String s) {
        for (String k : LIVE_UI_KEYS) {
            if (k.equals(s)) {
                return k;
            }
        }
        return "<omitted>";
    }

    /** v3.0.7：计数签名变化或 30s 心跳才落盘一条诊断，不刷屏。 */
    private static void targetedDiag(int n1, int n2, int n3, int n4) {
        String sig = n1 + "/" + n2 + "/" + n3 + "/" + n4;
        long now = SystemClock.elapsedRealtime();
        if (sig.equals(lastThhDiagSig) && now - lastThhDiagAt < 30_000L) {
            return;
        }
        lastThhDiagSig = sig;
        lastThhDiagAt = now;
        XposedInit.diagForce("THH ksL=" + n1 + " dyL=" + n2 + " ksF=" + n3 + " dr=" + n4);
    }

    /** 短数字文本（角标计数）。 */
    private static boolean isShortDigits(String s) {
        if (s == null || s.isEmpty() || s.length() > 4) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** resource-id 的语义名；取不到时回退数值形式。 */
    private static String resName(View v) {
        int id = -1;
        try {
            id = v.getId();
            if (id > 0) {
                android.content.res.Resources r = v.getResources();
                if (r != null) {
                    return r.getResourceEntryName(id);
                }
            }
        } catch (Throwable ignored) {
            // 混淆包 / 动态 id 无语义名，走下面的数值回退
        }
        return id > 0 ? "#" + Integer.toHexString(id) : "0";
    }

    /** TextView 文本，截断到 24 字符防止日志刷屏。 */
    private static String shortText(View v) {
        if (!(v instanceof TextView)) {
            return "";
        }
        try {
            CharSequence s = ((TextView) v).getText();
            if (s == null || s.length() == 0) {
                return "";
            }
            String t = s.toString();
            return t.length() <= 24 ? t : t.substring(0, 24) + "...";
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 无障碍描述，截断到 24 字符。 */
    private static String shortDesc(View v) {
        try {
            CharSequence d = v.getContentDescription();
            if (d == null || d.length() == 0) {
                return "";
            }
            String s = d.toString();
            return s.length() <= 24 ? s : s.substring(0, 24) + "...";
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 自身或子树是否含 ImageView（只读结构查询，限深 6 层）。 */
    private static boolean containsImage(View v, int depth) {
        if (v == null || depth > 6) {
            return false;
        }
        if (v instanceof ImageView) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsImage(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 只读采集单个 View 的元信息，parent 便于关联右侧大容器。 */
    private static String readMeta(View v) {
        if (v == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder();
        b.append("cls=").append(v.getClass().getSimpleName())
                .append(" id=").append(resName(v))
                .append(" size=").append(v.getWidth()).append('x').append(v.getHeight());
        int[] l = loc(v);
        b.append(" pos=(").append(l == null ? -1 : l[0]).append(',').append(l == null ? -1 : l[1]).append(')');
        b.append(" click=").append(v.isClickable());
        b.append(" img=").append(containsImage(v, 0));
        b.append(" txt=").append(shortText(v));
        b.append(" desc=").append(shortDesc(v));
        ViewParent p = v.getParent();
        b.append(" parent=").append(p == null ? "-" : p.getClass().getSimpleName());
        return b.toString();
    }

    /** 只读读取背景色描述（十六进制，取不到时给类型名）。 */
    private static String bgDesc(View v) {
        try {
            android.graphics.drawable.Drawable d = v.getBackground();
            if (d == null) {
                return "none";
            }
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                return "#" + Integer.toHexString(((android.graphics.drawable.ColorDrawable) d).getColor());
            }
            if (d instanceof GradientDrawable) {
                android.content.res.ColorStateList csl = ((GradientDrawable) d).getColor();
                if (csl != null) {
                    return "#" + Integer.toHexString(csl.getDefaultColor());
                }
            }
            return d.getClass().getSimpleName();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 背景是否为红系（红点典型形态），只读判断。 */
    private static boolean isRedBackground(View v) {
        String s = bgDesc(v);
        if (s == null || s.length() < 7 || s.charAt(0) != '#') {
            return false;
        }
        try {
            int rgb = Integer.parseInt(s.substring(s.length() - 6), 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            return r >= 150 && r > g * 1.5f && r > b * 1.5f;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** 红点候选的完整只读元信息 = 通用元信息 + 背景色描述。 */
    private static String readBadgeMeta(View v) {
        return readMeta(v).concat(" bg=").concat(bgDesc(v));
    }
}
