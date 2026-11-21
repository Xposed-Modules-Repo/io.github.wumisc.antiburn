package io.github.wumisc.antiburn

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页注入状态轮询间隔（v3.0.10 P2）：新鲜度以 24h 为粒度，15s 刷新对判定无实质影响。
 * 仅在设置页前台的 LaunchedEffect 中使用，降低协程唤醒频率。
 */
private const val STATUS_REFRESH_MS = 15_000L

/**
 * 防止烧屏 · v2.0 起
 *
 * v2.7.8：功能页由两列磁贴 + 五个横向 tab 改为单行列表，五个分类各进
 *       一个二级菜单；关于页新增「功能建议」二级页与 GitHub 提交按钮。
 *
 * v1.8：功能页改为磁贴式开关，抖音 / 红果分别独立成一张磁贴页；
 *       保存条新增「重启系统界面」，改完一键让 SystemUI 重新注入生效。
 *
 * 上一轮修复（保存相关）：
 *  - 新增明确的「保存设置」按钮 + 保存状态/未保存改动提示，不再只能靠开关隐式保存；
 *  - 所有改动走 700ms 防抖 + 单一写入任务，滑动条不再每帧 fork 一次 su 覆盖同一文件；
 *  - 保存结果区分「root 写入成功 / 仅应用内镜像 / 彻底失败」，失败时明确提示而不是静默；
 *  - 保存时保留 conf 里的未知 key 与注释，不再整表重建把 enable_icon 等设置抹掉；
 *  - custom_poems 多行文本编码为单行，不再把 conf 切成非法行；新增可编辑输入框；
 *  - 白名单支持直接填包名（选择页失败时仍可保存），切换 Tab 不再丢弃未保存的改动。
 */
// v3.0.12 P2：首页四入口对应的二级菜单 key（与 feat-* 平级的内部导航契约）。
// 用 const val 而非散落字面量：SubPageHost 的 title/content 两处分支各引用同一常量，
// 避免拼写漂移，也便于静态审计按标识符计数。
private const val PAGE_HOME_ENGINE = "home-engine"
private const val PAGE_HOME_INJECT = "home-inject"
private const val PAGE_HOME_UISTYLE = "home-uistyle"
private const val PAGE_HOME_LIST = "home-list"

/**
 * 界面适配可选方案：六大厂商 + 原生（id 与 conf 里的 ui_style 一一对应）。
 * v2.7.4：新增魅族 Flyme —— 魅族 20/21 等中置挖孔机型会在正中绘制环形电池，
 * 不避让会跟着像素微移一起抖。
 */
private val UI_STYLE_LABELS = listOf(
    "hyperos" to "小米 HyperOS",
    "magicos" to "荣耀 MagicOS",
    "coloros" to "OPPO ColorOS",
    "originos" to "vivo OriginOS",
    "harmonyos" to "华为 HarmonyOS",
    "flyme" to "魅族 Flyme",
    "aosp" to "原生 AOSP",
)

/**
 * 首次加载时的界面方案：按机型自动识别，用户改过之后一律以配置为准。
 * 识别错了也没有副作用 —— 首页的适配按钮点一下就会覆盖并落盘。
 */
private fun detectedUiStyle(): String = UiStyle.detect(
    Build.MANUFACTURER, Build.BRAND, Build.DISPLAY, Build.PRODUCT,
)

data class AbState(
    val enableStatus: Boolean = true,
    val enableNav: Boolean = false,
    /**
     * 像素偏移（AOSP BurnInProtection 同思路的温和模式）。
     * v2.6.5 起内置常开：界面不再暴露开关，load() 固定 true、Hook 侧解析也固定 true。
     * 字段与 Saver 索引保留，仅为兼容老配置与 Bundle 恢复，用户无需再操心它。
     */
    val enableShift: Boolean = true,
    /** 状态栏图标单独开关（与 enable_status 分离，供只想隐藏图标不想隐藏整栏的用户用） */
    val enableIcon: Boolean = false,
    /**
     * 状态栏隐藏开关（默认开，与 v2.7 之前「总闸一开状态栏必隐藏」行为一致）。
     *
     * <p>v2.7 删掉「状态栏图标」磁贴时把「状态栏能不能单独关」这件事一起删了，
     * 只留下总闸。于是用户在总闸打开后无论怎么调，状态栏都会跟着隐藏，
     * 表现为「状态栏和导航栏一起消失，单独开关无效」。这里补一个独立开关，
     * 键名与 enable_icon 分开：后者是历史死字段，旧配置里普遍是 0，
     * 直接复用会让所有老用户升级后状态栏突然不再隐藏。</p>
     */
    val enableStatusBar: Boolean = true,
    /** v3.0.20：隐藏状态栏时连左侧时间一起清；关掉则只清右侧图标。缺键按 true。 */
    val statusClockOn: Boolean = true,
    /** v3.0.20：隐藏状态栏时清右侧信号/网络/电量；缺键按 true。 */
    val statusIconsOn: Boolean = true,
    /**
     * 目标 UI 适配方案：hyperos / magicos / coloros / originos / harmonyos / flyme / aosp。
     * 决定 Hook 侧用哪套「胶囊保护区 + 导航栏结构」配置，见 profiles/ 与 Config.uiStyle。
     * 默认值改为按机型识别（v2.7.4）：魅族机器首次打开就是 Flyme，不必自己找。
     */
    val uiStyle: String = detectedUiStyle(),
    val enablePoem: Boolean = false,
    val enableBall: Boolean = false,
    val enableLog: Boolean = false,
    /**
     * v3.0.16：无障碍模式的应用内遮盖，默认开。
     *
     * <p>仅 overlay（无障碍）引擎生效：免 root 没有位置微移，周期遮盖是保护
     * 抖音/红果/快手/B站固定区域的唯一通道。LSPosed 引擎下 Hook 侧仍不铺黑，
     * 所以这里默认 true 不会让 LSPosed 用户看到黑块。</p>
     */
    val enableAppOverlay: Boolean = true,
    /** v2.8.6：SystemUI 覆盖层同上，恒 false。 */
    val enableSysOverlay: Boolean = false,
    val enableDouyinTop: Boolean = false,
    val enableDouyinBottom: Boolean = false,
    val enableDouyinLike: Boolean = false,
    val enableDouyinRt: Boolean = false,
    val enableDouyinLt: Boolean = false,
    val enableLive: Boolean = true,
    val enableDramaTop: Boolean = false,
    val enableDramaBottom: Boolean = false,
    val enableDramaLike: Boolean = false,
    val enableDramaRt: Boolean = false,
    val enableDramaLt: Boolean = false,
    /**
     * 红果短剧播放页右侧中部那条竖排「左滑查看详情 «」引导文字。
     * 它是长期静止的半透明白字，容易烧屏；与顶部/底部/右侧按钮列/角落按钮
     * 都不重合，只能按文案识别后单独隐藏。默认关闭，老配置缺键行为不变。
     */
    val enableDramaSwipe: Boolean = false,
    /** v2.6.8 快手 / 哔哩哔哩：每个 App 一个总开关，默认关 */
    val enableKuaishou: Boolean = false,
    val enableBilibili: Boolean = false,
    /** v2.7.0 快手独立四区域开关（与 B站完全独立，互不影响），默认关 */
    val enableKsRight: Boolean = false,
    val enableKsTop: Boolean = false,
    val enableKsBottom: Boolean = false,
    val enableKsCorner: Boolean = false,
    /** v2.7.0 哔哩哔哩独立四区域开关，默认关 */
    val enableBiliRight: Boolean = false,
    val enableBiliTop: Boolean = false,
    val enableBiliBottom: Boolean = false,
    val enableBiliCorner: Boolean = false,
    /** v3.0.6：三处精准隐藏开关，默认 false（纯加性，不动任何旧键/索引/默认值） */
    val enableBottomCaption: Boolean = false,
    val enableRightActions: Boolean = false,
    val enableRedDot: Boolean = false,
    // v3.0.7：四图圈选内容定向隐藏，默认 false（纯加性，不动任何旧键/索引/默认值）
    val enableKsLiveHits: Boolean = false,
    val enableDyLiveHits: Boolean = false,
    val enableKsFeedHits: Boolean = false,
    val enableDramaInfoHits: Boolean = false,
    val hideSec: Int = 30,
    val showSec: Int = 10,
    val customPoems: String = "",
    /** 名单模式：white / black / off（off = 不使用名单，所有应用都干预） */
    val listMode: String = ConfStore.MODE_WHITE,
    /** 白名单内容：名单内应用前台时不干预 */
    val whiteList: String = "",
    /** 黑名单内容：只有名单内应用才干预（为空时退回全部干预） */
    val blackList: String = "",
    /**
     * 运行引擎：lsposed（在 SystemUI 里 Hook，真隐藏 + 像素微移，需 root）/
     * overlay（无障碍覆盖层遮盖，免 root，只能盖黑）。
     *
     * 默认 lsposed 是刻意的：老用户升级后配置里没有这个键，必须保持原行为，
     * 不能因为「新功能是免 root」就把所有人切到遮盖引擎。
     */
    val engine: String = ConfStore.ENGINE_LSPOSED,
) {
    /** 当前模式对应的那份名单文本，UI 的输入框与卡片都读它 */
    val activeList: String
        get() = if (listMode == ConfStore.MODE_BLACK) blackList else whiteList

    /** 是否走免 root 遮盖引擎 */
    val isOverlay: Boolean
        get() = ConfStore.normEngine(engine) == ConfStore.ENGINE_OVERLAY
}

/** 改动后延迟写盘：合并连续点击，短时间内退出由 MainActivity.onStop 强制补齐 */
private const val SAVE_DEBOUNCE_MS = 700L

/** 保存 UI 状态，避免旋转/深浅色切换时丢掉尚未落盘的改动。 */
private val AbStateSaver = listSaver(
    save = { s: AbState ->
        listOf<Any>(
            s.enableStatus, s.enableNav, s.enablePoem, s.enableBall, s.enableLog,
            s.enableDouyinTop, s.enableDouyinBottom, s.enableDouyinLike,
            s.enableDouyinRt, s.enableDouyinLt, s.enableLive,
            s.enableDramaTop, s.enableDramaBottom, s.enableDramaLike,
            s.enableDramaRt, s.enableDramaLt,
            s.hideSec, s.showSec, s.customPoems, s.whiteList,
            s.enableShift, s.enableIcon, s.uiStyle, s.listMode, s.blackList,
            // v2.6：运行引擎。索引由 25 增到 26。
            s.engine,
            // v2.6.4：状态栏独立开关。索引 26。
            s.enableStatusBar,
            // v2.6.6：红果「左滑查看详情」引导条开关。索引 27（追加在末尾，不动旧索引）。
            s.enableDramaSwipe,
            // v2.6.8：快手 / B站 六个开关。索引 28..33 追加在末尾，旧索引一律不动。
            s.enableKuaishou, s.enableBilibili,
            s.enableKsRight, s.enableKsTop,
            s.enableKsBottom, s.enableKsCorner,
            s.enableBiliRight, s.enableBiliTop,
            s.enableBiliBottom, s.enableBiliCorner,
            // v2.8.2：应用内区域兜底。索引 38，追加在末尾不动旧索引。
            s.enableAppOverlay,
            s.enableSysOverlay,
            // v3.0.6：索引 40/41/42，纯追加不动旧索引 0-39。
            s.enableBottomCaption, s.enableRightActions, s.enableRedDot,
            // v3.0.7：索引 43/44/45/46，纯追加不动旧索引 0-42。
            s.enableKsLiveHits, s.enableDyLiveHits,
            s.enableKsFeedHits, s.enableDramaInfoHits,
            // v3.0.20：索引 47/48，状态栏左右两侧独立开关。
            s.statusClockOn, s.statusIconsOn,
        )
    },
    restore = { l: List<Any> ->
        if (l.size < 23) {
            null
        } else {
            // v2.5：跨版本升级时 Bundle 里的元素类型可能变化，旧版无条件 as
            // 会直接 ClassCastException 崩溃；逐项用 as? + 默认值兜底。
            // v2.8：末尾新增 listMode / blackList，条数由 23 增到 25。
            // 用 getOrNull 而不是直接下标，v2.7 存的 23 项 Bundle 仍能恢复
            // （索引 19 在旧版就是名单文本，语义正好对应 whiteList），
            // 新增字段取默认值，不会整体丢状态。
            fun Bool(i: Int, def: Boolean = false) = l.getOrNull(i) as? Boolean ?: def
            fun Intv(i: Int, def: Int) = l.getOrNull(i) as? Int ?: def
            fun Str(i: Int, def: String = "") = l.getOrNull(i) as? String ?: def
            AbState(
                enableStatus = Bool(0, true),
                enableNav = Bool(1),
                enablePoem = Bool(2),
                enableBall = Bool(3),
                enableLog = Bool(4),
                enableDouyinTop = Bool(5),
                enableDouyinBottom = Bool(6),
                enableDouyinLike = Bool(7),
                enableDouyinRt = Bool(8),
                enableDouyinLt = Bool(9),
                enableLive = Bool(10, true),
                enableDramaTop = Bool(11),
                enableDramaBottom = Bool(12),
                enableDramaLike = Bool(13),
                enableDramaRt = Bool(14),
                enableDramaLt = Bool(15),
                hideSec = Intv(16, 30),
                showSec = Intv(17, 10),
                customPoems = Str(18),
                whiteList = Str(19),
                enableShift = Bool(20, true),
                enableIcon = Bool(21),
                uiStyle = Str(22, detectedUiStyle()),
                listMode = ConfStore.normMode(Str(23)),
                blackList = Str(24),
                // v2.6 新增：旧 Bundle 只有 25 项，getOrNull 会返回 null → 取默认值
                engine = ConfStore.normEngine(Str(25, ConfStore.ENGINE_LSPOSED)),
                // v2.6.4 新增：默认 true，与「总闸开则状态栏隐藏」的历史行为一致
                enableStatusBar = Bool(26, true),
                // v2.6.6 新增：默认 false，旧 Bundle 只有 27 项时 getOrNull 取默认值
                enableDramaSwipe = Bool(27),
                // v2.6.8 新增：六个开关默认 false；旧 Bundle 缺项由 getOrNull 兜底，
                // 不插队、不改旧索引，升级后旋转屏幕不会串位。
                enableKuaishou = Bool(28),
                enableBilibili = Bool(29),
                enableKsRight = Bool(30),
                enableKsTop = Bool(31),
                enableKsBottom = Bool(32),
                enableKsCorner = Bool(33),
                enableBiliRight = Bool(34),
                enableBiliTop = Bool(35),
                enableBiliBottom = Bool(36),
                enableBiliCorner = Bool(37),
                // v3.0.16：旧 Bundle 缺这项时按开——仅 overlay 引擎生效，不会误铺黑
                enableAppOverlay = Bool(38, true),
                enableSysOverlay = Bool(39, false),
                // v3.0.6：旧 Bundle 缺这三项时 getOrNull 兜底 false，开关默认关。
                enableBottomCaption = Bool(40),
                enableRightActions = Bool(41),
                enableRedDot = Bool(42),
                // v3.0.7：旧 Bundle 缺这四项时 getOrNull 兜底 false，开关默认关。
                enableKsLiveHits = Bool(43),
                enableDyLiveHits = Bool(44),
                enableKsFeedHits = Bool(45),
                enableDramaInfoHits = Bool(46),
                statusClockOn = Bool(47, true),
                statusIconsOn = Bool(48, true),
            )
        }
    },
)

/** 读/写 antiburn.conf：统一走 ConfStore（应用内镜像 + root 原子写） */
object AbConf {
    private fun i(v: String?, def: Int): Int = v?.trim()?.toIntOrNull() ?: def
    private fun b(v: String?, def: Boolean = false): Boolean = if (v == null) def else v.trim() == "1"

    /**
     * v2.7.1 迁移优先级：独立键一旦存在就以其为准，旧共用键仅在新键缺失时兜底。
     * 不能用「新值 || 旧值」：旧值 1 会让用户永远无法把新开关关成 0，并在保存回读时
     * 被误报成「已被其它入口改写」。
     */
    private fun migratedB(map: Map<String, String>, key: String, legacy: String): Boolean =
        if (map.containsKey(key)) b(map[key]) else b(map[legacy])

    /** 已知的 UI 适配方案 id（与 Hook 侧 UiStyle.IDS 同一份真源） */
    val UI_STYLE_IDS: List<String> = UiStyle.IDS

    /** 未知 / 空值一律归到 aosp。归一逻辑与 Config/UiStyle 共用，避免跨进程不一致 */
    fun normUiStyle(v: String?): String = UiStyle.norm(v)

    /**
     * 名单文本归一：换行 / 中英文逗号 / 分号都当分隔符，去空白去重后按英文逗号拼回。
     * 与 ConfStore.parseWhitelist 同一套规则。直接拿输入框原文做 diff / 落盘会出两个真问题：
     *  - 用户敲一个结尾逗号，回读被归一化 → 误报「已被其它入口改写」，逗号还会被吞掉；
     *  - 粘贴多行时换行被原样写进 conf，整行变成没有 '=' 的非法行，此后每次保存都失败。
     */
    fun canonList(text: String): String = ConfStore.parseWhitelist(text).joinToString(",")

    fun load(): AbState {
        // conf 与镜像都读不到 → 用类默认值（enableStatus/enableLive 为 true），
        // 旧版这里会把缺失 key 一律解析成 false，一进页面就把总开关显示成"已关闭"。
        val conf = ConfStore.read() ?: return AbState()
        val map = mutableMapOf<String, String>()
        for (line in conf.split("\n")) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val idx = t.indexOf('=')
            if (idx < 0) continue
            map[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
        }
        val lists = ConfStore.readLists(conf)
        return AbState(
            enableStatus = b(map["enable_status"], true),
            enableNav = b(map["enable_navigation"]),
            // v2.6.5：像素微移改为内置常开，界面不再暴露开关。固定 true 而不是读 map，
            // 是为了让「老配置里曾把它关成 0」的用户升级后也自动拉回开，不必找不到入口去恢复。
            // Hook 侧 Config.parse 同样固定 true；这里保留字段只为兼容 Bundle 与落盘审计。
            enableShift = true,
            enableIcon = b(map["enable_icon"]),
            // 缺失键按 true：v2.6.3 及以前的配置没有这个键，升级后行为必须保持不变
            enableStatusBar = b(map["enable_status_bar"], true),
            statusClockOn = b(map["status_clock_on"], true),
            statusIconsOn = b(map["status_icons_on"], true),
            // v2.7.4：缺键时按机型识别（魅族 → flyme），已有键一律以用户选择为准
            uiStyle = if (map.containsKey("ui_style")) normUiStyle(map["ui_style"]) else detectedUiStyle(),
            // 诗句功能已下线（Hook 侧实现同步移除）：界面无入口、消费端已删，
            // 这里固定 false，避免老配置里的 enable_poem=1 被读进来再写回去。
            enablePoem = false,
            enableBall = b(map["enable_ball"]),
            enableLog = b(map["enable_log"]),
            // v3.0.16：缺键默认开（免 root 开箱可用）；LSPosed 引擎由服务端拦掉，不铺黑
            enableAppOverlay = b(map["enable_app_overlay"], true),
            enableSysOverlay = false,
            enableDouyinTop = b(map["enable_douyin_top"]),
            enableDouyinBottom = b(map["enable_douyin_bottom"]),
            enableDouyinLike = b(map["enable_douyin_like"]),
            enableDouyinRt = b(map["enable_douyin_right_top"]),
            enableDouyinLt = b(map["enable_douyin_left_top"]),
            enableLive = b(map["enable_live"], true),
            enableDramaTop = b(map["enable_drama_top"]),
            enableDramaBottom = b(map["enable_drama_bottom"]),
            enableDramaLike = b(map["enable_drama_like"]),
            enableDramaRt = b(map["enable_drama_right_top"]),
            enableDramaLt = b(map["enable_drama_left_top"]),
            enableDramaSwipe = b(map["enable_drama_swipe"]),
            enableKuaishou = b(map["enable_kuaishou"]),
            enableBilibili = b(map["enable_bilibili"]),
            // v2.7.1：新独立键优先；只有新键缺失时才迁移旧共用键，允许用户正常关闭
            enableKsRight = migratedB(map, "enable_ks_right", "enable_video_right"),
            enableKsTop = migratedB(map, "enable_ks_top", "enable_video_top"),
            enableKsBottom = migratedB(map, "enable_ks_bottom", "enable_video_bottom"),
            enableKsCorner = migratedB(map, "enable_ks_corner", "enable_video_corner"),
            enableBiliRight = migratedB(map, "enable_bili_right", "enable_video_right"),
            // v2.9.2：B站只剩右侧点赞区，另外三项已从 UI 删除。这里读盘后强制归零，
            // 老配置里的 1 会在下次保存时被写回 0，不会留下「显示已开启、实际不生效」的假状态。
            enableBiliTop = false,
            enableBiliBottom = false,
            enableBiliCorner = false,
            // v3.0.6：新键缺失取默认 false（老配置升级后三功能全关，行为不变）
            enableBottomCaption = b(map["enable_bottom_caption"]),
            enableRightActions = b(map["enable_right_actions"]),
            enableRedDot = b(map["enable_red_dot"]),
            // v3.0.7：新键缺失取默认 false（老配置升级后四功能全关，行为不变）
            enableKsLiveHits = b(map["enable_ks_live_hits"]),
            enableDyLiveHits = b(map["enable_dy_live_hits"]),
            enableKsFeedHits = b(map["enable_ks_feed_hits"]),
            enableDramaInfoHits = b(map["enable_drama_info_hits"]),
            hideSec = i(map["hide_duration"], 30),
            showSec = i(map["show_duration"], 10),
            customPoems = "",
            // 模式与两份名单统一由 ConfStore.readLists 解析：迁移规则（旧别名
            // blacklist 其实是白名单）只写一份，UI 与 Hook 不会各读各的。
            listMode = lists.mode,
            whiteList = lists.white.joinToString(","),
            blackList = lists.black.joinToString(","),
            // 缺 engine 键的旧配置按 lsposed 解释（normEngine 兜底），升级后行为不变
            engine = ConfStore.normEngine(map["engine"]),
        )
    }

    /** 重建 conf 全文并原子写入，返回 ConfStore.WRITE_* 状态码 */
    fun save(s: AbState): Int {
        // 以默认配置打底，再叠加现有 conf 的键值 —— 保留未知 key，避免"保存一次丢一批设置"
        val lines = LinkedHashMap<String, String>()
        for (line in ConfStore.defaultContent().split("\n")) {
            val idx = line.indexOf('=')
            if (idx > 0) lines[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        val comments = mutableListOf<String>()
        val current = ConfStore.read()
        if (current != null) {
            for (line in current.split("\n")) {
                val t = line.trim()
                if (t.isEmpty()) continue
                if (t.startsWith("#")) {
                    comments.add(line)
                    continue
                }
                val idx = t.indexOf('=')
                if (idx < 0) continue // 丢弃旧版多行诗句留下的非法行
                lines[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
            }
        }
        lines["enable_status"] = if (s.enableStatus) "1" else "0"
        // s.enableShift 恒为 true（load 固定、界面无入口），因此恒写 1：
        // 老配置里遗留的 enable_shift=0 在任意一次保存后即被纠正。
        lines["enable_shift"] = if (s.enableShift) "1" else "0"
        lines["enable_icon"] = if (s.enableIcon) "1" else "0"
        lines["enable_status_bar"] = if (s.enableStatusBar) "1" else "0"
        lines["status_clock_on"] = if (s.statusClockOn) "1" else "0"
        lines["status_icons_on"] = if (s.statusIconsOn) "1" else "0"
        lines["ui_style"] = normUiStyle(s.uiStyle)
        lines["enable_navigation"] = if (s.enableNav) "1" else "0"
        // 字段恒为 false（load 已固定），因此这里恒写 0：老配置里的 1
        // 在任意一次保存后即被清零，下游也已无消费者。
        lines["enable_poem"] = if (s.enablePoem) "1" else "0"
        lines["enable_ball"] = if (s.enableBall) "1" else "0"
        lines["enable_log"] = if (s.enableLog) "1" else "0"
        // v2.8.6：这两个键保留写盘只为一个目的 —— 每个配置项都有明确落点，审计时
        // 能一眼看出「通道已废弃」而不是「漏写了」。字段本身恒 false（fromMap 强制、
        // AbState 默认 false、Saver 默认 false 三条路都是 false），所以这里恒写 0。
        lines["enable_app_overlay"] = if (s.enableAppOverlay) "1" else "0"
        lines["enable_sys_overlay"] = if (s.enableSysOverlay) "1" else "0"
        lines["enable_douyin_top"] = if (s.enableDouyinTop) "1" else "0"
        lines["enable_douyin_bottom"] = if (s.enableDouyinBottom) "1" else "0"
        lines["enable_douyin_like"] = if (s.enableDouyinLike) "1" else "0"
        lines["enable_douyin_right_top"] = if (s.enableDouyinRt) "1" else "0"
        lines["enable_douyin_left_top"] = if (s.enableDouyinLt) "1" else "0"
        lines["enable_live"] = if (s.enableLive) "1" else "0"
        lines["enable_drama_top"] = if (s.enableDramaTop) "1" else "0"
        lines["enable_drama_bottom"] = if (s.enableDramaBottom) "1" else "0"
        lines["enable_drama_like"] = if (s.enableDramaLike) "1" else "0"
        lines["enable_drama_right_top"] = if (s.enableDramaRt) "1" else "0"
        lines["enable_drama_left_top"] = if (s.enableDramaLt) "1" else "0"
        lines["enable_drama_swipe"] = if (s.enableDramaSwipe) "1" else "0"
        lines["enable_kuaishou"] = if (s.enableKuaishou) "1" else "0"
        lines["enable_bilibili"] = if (s.enableBilibili) "1" else "0"
        lines["enable_ks_right"] = if (s.enableKsRight) "1" else "0"
        lines["enable_ks_top"] = if (s.enableKsTop) "1" else "0"
        lines["enable_ks_bottom"] = if (s.enableKsBottom) "1" else "0"
        lines["enable_ks_corner"] = if (s.enableKsCorner) "1" else "0"
        lines["enable_bili_right"] = if (s.enableBiliRight) "1" else "0"
        lines["enable_bili_top"] = if (s.enableBiliTop) "1" else "0"
        lines["enable_bili_bottom"] = if (s.enableBiliBottom) "1" else "0"
        lines["enable_bili_corner"] = if (s.enableBiliCorner) "1" else "0"
        lines["enable_bottom_caption"] = if (s.enableBottomCaption) "1" else "0"
        lines["enable_right_actions"] = if (s.enableRightActions) "1" else "0"
        lines["enable_red_dot"] = if (s.enableRedDot) "1" else "0"
        // v3.0.7：四键与 backend §6.1 逐字对齐，纯追加
        lines["enable_ks_live_hits"] = if (s.enableKsLiveHits) "1" else "0"
        lines["enable_dy_live_hits"] = if (s.enableDyLiveHits) "1" else "0"
        lines["enable_ks_feed_hits"] = if (s.enableKsFeedHits) "1" else "0"
        lines["enable_drama_info_hits"] = if (s.enableDramaInfoHits) "1" else "0"
        lines["hide_duration"] = s.hideSec.coerceIn(5, 3600).toString()
        lines["show_duration"] = s.showSec.coerceIn(1, 3600).toString()
        // 多行文本必须编码成单行，否则后面的 key 会被挤成非法行、配置解析错乱
        lines["custom_poems"] = ConfStore.encodeValue(s.customPoems)
        lines["list_mode"] = s.listMode
        lines["whitelist"] = canonList(s.whiteList)
        lines["blacklist"] = canonList(s.blackList)
        lines["engine"] = ConfStore.normEngine(s.engine)
        // 供 Hook 侧识别混装（App 已升级、SystemUI 仍跑旧 dex）
        lines["app_ver"] = BuildConfig.VERSION_NAME

        val sb = StringBuilder()
        for (c in comments) sb.append(c).append('\n')
        for ((k, v) in lines) sb.append(k).append('=').append(v).append('\n')
        return ConfStore.writeEx(sb.toString())
    }

    /**
     * 只把「相对上次成功保存的状态真正变化」的字段写回磁盘。
     *
     * 旧版 save() 拿内存里的 AbState 整表重建 conf：只要 App 还停在后台没被回收，
     * 用户从快捷磁贴改了 enable_status，再回 App 点任意一个开关保存，
     * 就会用旧快照把磁贴刚写的值覆盖回去 —— 表现就是「这个开关保存不住」。
     * 改成字段级 diff 后，没动过的键一律不碰，磁贴 / 其它入口的改动不会被回滚。
     */
    fun saveDiff(old: AbState, new: AbState): Int = applyDiff(diffOf(old, new))

    /**
     * 计算「相对上次成功保存真正变化」的字段。
     *
     * 抽出来是为了让保存与回读校验用同一份 key 集合：写完拿这份 m 去磁盘逐个核对，
     * 才能真正证明「这几个键确实落盘了」，而不是只看写入函数有没有抛异常。
     */
    fun diffOf(old: AbState, new: AbState): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        fun putB(key: String, a: Boolean, b: Boolean) {
            if (a != b) m[key] = if (b) "1" else "0"
        }
        putB("enable_status", old.enableStatus, new.enableStatus)
        // 恒 true，不会产生 diff；保留这一行让「字段 → 落盘」审计仍成立。
        putB("enable_shift", old.enableShift, new.enableShift)
        putB("enable_icon", old.enableIcon, new.enableIcon)
        putB("enable_status_bar", old.enableStatusBar, new.enableStatusBar)
        putB("status_clock_on", old.statusClockOn, new.statusClockOn)
        putB("status_icons_on", old.statusIconsOn, new.statusIconsOn)
        if (old.uiStyle != new.uiStyle) m["ui_style"] = normUiStyle(new.uiStyle)
        putB("enable_navigation", old.enableNav, new.enableNav)
        // 诗句字段恒为 false（功能已下线），此行使字段与 conf 的写入路径保持完整，
        // 实际不会产生 diff；删掉会让「字段→落盘」审计失去依据。
        putB("enable_poem", old.enablePoem, new.enablePoem)
        putB("enable_ball", old.enableBall, new.enableBall)
        putB("enable_log", old.enableLog, new.enableLog)
        putB("enable_app_overlay", old.enableAppOverlay, new.enableAppOverlay)
        putB("enable_sys_overlay", old.enableSysOverlay, new.enableSysOverlay)
        putB("enable_douyin_top", old.enableDouyinTop, new.enableDouyinTop)
        putB("enable_douyin_bottom", old.enableDouyinBottom, new.enableDouyinBottom)
        putB("enable_douyin_like", old.enableDouyinLike, new.enableDouyinLike)
        putB("enable_douyin_right_top", old.enableDouyinRt, new.enableDouyinRt)
        putB("enable_douyin_left_top", old.enableDouyinLt, new.enableDouyinLt)
        putB("enable_live", old.enableLive, new.enableLive)
        putB("enable_drama_top", old.enableDramaTop, new.enableDramaTop)
        putB("enable_drama_bottom", old.enableDramaBottom, new.enableDramaBottom)
        putB("enable_drama_like", old.enableDramaLike, new.enableDramaLike)
        putB("enable_drama_right_top", old.enableDramaRt, new.enableDramaRt)
        putB("enable_drama_left_top", old.enableDramaLt, new.enableDramaLt)
        putB("enable_drama_swipe", old.enableDramaSwipe, new.enableDramaSwipe)
        putB("enable_kuaishou", old.enableKuaishou, new.enableKuaishou)
        putB("enable_bilibili", old.enableBilibili, new.enableBilibili)
        putB("enable_ks_right", old.enableKsRight, new.enableKsRight)
        putB("enable_ks_top", old.enableKsTop, new.enableKsTop)
        putB("enable_ks_bottom", old.enableKsBottom, new.enableKsBottom)
        putB("enable_ks_corner", old.enableKsCorner, new.enableKsCorner)
        putB("enable_bili_right", old.enableBiliRight, new.enableBiliRight)
        putB("enable_bili_top", old.enableBiliTop, new.enableBiliTop)
        putB("enable_bili_bottom", old.enableBiliBottom, new.enableBiliBottom)
        putB("enable_bili_corner", old.enableBiliCorner, new.enableBiliCorner)
        putB("enable_bottom_caption", old.enableBottomCaption, new.enableBottomCaption)
        putB("enable_right_actions", old.enableRightActions, new.enableRightActions)
        putB("enable_red_dot", old.enableRedDot, new.enableRedDot)
        // v3.0.7：diffOf 与 save 共用同一份 key 集合，保证回读校验覆盖新键
        putB("enable_ks_live_hits", old.enableKsLiveHits, new.enableKsLiveHits)
        putB("enable_dy_live_hits", old.enableDyLiveHits, new.enableDyLiveHits)
        putB("enable_ks_feed_hits", old.enableKsFeedHits, new.enableKsFeedHits)
        putB("enable_drama_info_hits", old.enableDramaInfoHits, new.enableDramaInfoHits)
        if (old.hideSec != new.hideSec) m["hide_duration"] = new.hideSec.coerceIn(5, 3600).toString()
        if (old.showSec != new.showSec) m["show_duration"] = new.showSec.coerceIn(1, 3600).toString()
        if (old.customPoems != new.customPoems) {
            m["custom_poems"] = ConfStore.encodeValue(new.customPoems)
        }
        // 按归一后的集合比较：只差一个结尾逗号 / 一个换行不算改动，也不写脏值
        if (canonList(old.whiteList) != canonList(new.whiteList)) {
            m["whitelist"] = canonList(new.whiteList)
        }
        if (canonList(old.blackList) != canonList(new.blackList)) {
            m["blacklist"] = canonList(new.blackList)
        }
        if (old.listMode != new.listMode) {
            m["list_mode"] = ConfStore.normMode(new.listMode)
            // 切模式必须与两份名单同批落盘：Hook 侧一旦看到 list_mode 就把它当成
            // 完整快照（目标名单缺键即视为空），只写模式键会把当前名单清空。
            m["whitelist"] = canonList(new.whiteList)
            m["blacklist"] = canonList(new.blackList)
        }
        // 引擎切换是「开关语义级」的改动：Hook 侧看到 overlay 就整体让位，
        // 遮盖引擎看到 lsposed 就不接管，因此必须单独落盘，不能和别的键合并省略。
        if (ConfStore.normEngine(old.engine) != ConfStore.normEngine(new.engine)) {
            m["engine"] = ConfStore.normEngine(new.engine)
        }
        return m
    }

    /** 把 diff 落到磁盘。名单三键由 diffOf 保证同批提交，这里无需再删旧别名。 */
    fun applyDiff(m: Map<String, String>): Int {
        if (m.isEmpty()) return ConfStore.WRITE_OK
        // 每次落盘都带上写入方版本。Hook 侧拿它与自己 dex 里内联的版本比对：
        // 不一致说明「App 已升级、SystemUI 还在跑旧版 Hook」，此时新增配置键会被
        // 旧代码静默忽略，表现为「开关点了没反应」。旧版 Hook 不认识这个键，
        // 走 default 分支丢弃，不影响向下兼容。
        val withVer = LinkedHashMap(m)
        withVer["app_ver"] = BuildConfig.VERSION_NAME
        return ConfStore.updateEx(withVer)
    }
}

class MainActivity : ComponentActivity() {
    /** 由 Compose 侧注入：把 700ms 防抖窗口里还没写完的改动立刻落盘。 */
    internal var flushPending: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfStore.init(this)
        setContent { BurnInProtectApp() }
        // v3.0.11 P2：冷启动时按 prefs 真值对本任务栈幂等生效（onResume 亦会再校正一次）。
        applyRecentsVisibility(this)
    }

    override fun onStop() {
        // 点完开关立刻返回 / 切到桌面时，Compose 的防抖协程会被取消，
        // 改动永远写不进磁盘 —— 这里在页面离开前强制补一次保存。
        flushPending?.invoke()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // v3.0.11 P2：启动/回到前台时读 prefs 真值，对本任务栈幂等生效。
        // 顶层 Composable（AboutPage）也复用同一实现，见文件顶层 applyRecentsVisibility。
        applyRecentsVisibility(this)
    }
}

// v3.0.11 P2：文件顶层私有函数（MainActivity 之后、AboutPage 之前）。
// 统一契约见 changes/20261120-applyRecentsVisibility契约统一.md：
// 仅对本任务栈标记 excludeFromRecents，接收 Context 以便顶层 Composable 调用。
// 每次读 prefs 真值；用 AppTask + taskId 精确匹配，不用 finishAndRemoveTask()。
private fun applyRecentsVisibility(context: Context) {
    val on = AppVisibilityStore.isHideRecents(context)
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
    val activity = context as? Activity ?: return
    val taskId = activity.taskId
    for (t in am.appTasks) {
        if (t.taskInfo?.id == taskId) {
            t.setExcludeFromRecents(on) // API 21+；minSdk 33
            break
        }
    }
}

/**
 * v2.2：这里原本有个进程级 SaveScope，用来把「700ms 防抖后的异步写」从
 * composition 生命周期里救出来。现在保存是点击即同步落盘，异步窗口已经不存在，
 * 作用域、互斥量、在途计数一并移除 —— 保留它们只会让下一次改动误以为还有异步路径。
 */

@Composable
private fun BurnInProtectApp() {
    val dark = isSystemInDarkTheme()
    // v2.6：全 App 统一配色，深色正文恒为浅色，不再出现深色模式黑字。
    val scheme = UiPalette.scheme(dark)

    // v2.7：主内容改为可左右滑动的 Pager，selectedTab 直接派生自当前页，
    // 液态玻璃导航栏读同一个值，滑动/点击都会让指示器跟随，不会出现「页面换了、高亮没换」。
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val selectedTab = pagerState.currentPage
    fun goTab(index: Int) {
        if (index in 0 until 3 && index != pagerState.currentPage) {
            scope.launch { pagerState.animateScrollToPage(index) }
        }
    }
    // 二级菜单栈：null=主界面；thanks/donate/cycle 各是一个独立二级页。
    // 用 saveable 保存，旋转/深浅色切换后不会退回主页。
    var subPage by rememberSaveable { mutableStateOf<String?>(null) }
    val openSub: (String) -> Unit = { subPage = it }
    val navMicroSec = 45
    // 旋转/深浅色切换后保留未落盘的改动
    var state by rememberSaveable(stateSaver = AbStateSaver) { mutableStateOf(AbState()) }
    var savedState by rememberSaveable(stateSaver = AbStateSaver) { mutableStateOf(AbState()) }
    var status by rememberSaveable { mutableStateOf("正在读取配置…") }
    var rootOk by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    // 尚未落盘的最新状态；Activity onStop 时用它补一次
    var pendingSave by remember { mutableStateOf<AbState?>(null) }
    // v2.7.4：配置还没加载完时用户点过的改动，按「增量」记录。
    // 旧版把「默认值 + 这一处改动」整份塞进 pendingSave，加载完成后按全字段 diff 回写，
    // 等于用默认值把用户磁盘上的配置全部冲掉（ui_style、名单、周期、各开关一起没）。
    // 这里只记函数，加载完在磁盘状态上重放，用户点哪改哪。
    val pendingOps = remember { mutableStateListOf<(AbState) -> AbState>() }
    // loaded 不能用 rememberSaveable：进程被系统回收后重建时，saveable 会把 loaded 恢复成 true，
    // 于是永远不再读盘，界面展示的是被杀之前的内存快照（可能根本没保存成功）。
    var loaded by remember { mutableStateOf(false) }

    val context = LocalContext.current

    /** 打开外部链接（项目地址 / 开源项目致敬名单），失败时给出明确提示而不是静默无反应。 */
    val openUrl: (String) -> Unit = { url ->
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(context, "无法打开链接，请检查是否安装了浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    fun clock(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    /**
     * 保存：点击即同步落盘，写完立刻回读校验。
     *
     * 旧实现把写入推迟 700ms（防抖），而 HyperOS 在 onStop 后会很快冻结后台进程，
     * 推迟的那次写入经常还没执行、进程就没了 —— 表现就是「提示已保存，重开又是关闭」。
     * 配置只有 1~2KB，同步写 + fsync 约 1~3ms，这点开销换「点了就一定存住」。
     *
     * 写完用同一份 diff 回读比对：只有磁盘上确实是新值才提示成功，不再谎报。
     */
    fun commit(target: AbState) {
        pendingSave = target
        // v2.5：配置加载协程（含 su）可能要几秒，期间 savedState 还是默认值。
        // 旧版无守卫，用户此时点开关写入后会被 LaunchedEffect 的加载结果覆盖，
        // 表现就是「点了没反应 / 开关回滚」。现在先存进 pendingSave，加载完补写。
        if (!loaded) return
        if (target == savedState) {
            pendingSave = null
            return
        }
        val diff = AbConf.diffOf(savedState, target)
        if (diff.isEmpty()) {
            // 只有名单文本的写法差异（结尾逗号、空格、换行）不算改动：不必重写文件。
            // 旧版这里会照写一次 + 回读一次，用户每敲一个字符就 fsync 一遍。
            savedState = target
            pendingSave = null
            status = "已保存 · " + clock()
            return
        }
        saving = true
        var result = ConfStore.WRITE_FAILED
        var bad: Set<String> = diff.keys
        try {
            var attempt = 0
            // 最多两次：目录刚被重建、存储瞬时不可用这类错误，重试一次通常就能过
            while (attempt < 2) {
                if (attempt > 0) android.os.SystemClock.sleep(80L)
                result = AbConf.applyDiff(diff)
                bad = if (result == ConfStore.WRITE_FAILED) diff.keys else ConfStore.verify(diff)
                if (result != ConfStore.WRITE_FAILED && bad.isEmpty()) break
                attempt++
            }
        } catch (t: Throwable) {
            result = ConfStore.WRITE_FAILED
            bad = diff.keys
        } finally {
            saving = false
        }
        if (result != ConfStore.WRITE_FAILED && bad.isEmpty()) {
            // 关键：写完把整份配置重新读回来，让界面显示的就是磁盘上的真值。
            // 这样即使有别的入口（磁贴 / 白名单页 / 旧进程）把值改回去，界面也立刻如实反映，
            // 不会再出现「开关看着是开的、下次打开又变回关」这种没人说得清的状态。
            val disk = try {
                AbConf.load()
            } catch (t: Throwable) {
                null
            }
            if (disk != null && disk != target) {
                // 只把「本次真正写下去的键」算进偏差。名单文本经过 trim / 去重后回读
                // 可能与输入框原文不完全一致（例如用户多打了一个逗号），这不是覆盖，
                // 按整份状态比较会误报「已被其它入口改写」。其它入口改的键也不该报警，
                // 直接以磁盘为准刷新界面即可。
                val drift = AbConf.diffOf(disk, target).keys.filter { it in diff.keys }
                // 名单文本以输入框为准，不做整体回灌：磁盘上是归一化后的值，
                // 回灌会把用户刚敲的逗号 / 换行吞掉，多包名根本敲不完。
                // 其余字段仍以磁盘为准，保证「界面显示的就是真值」。
                state = disk.copy(whiteList = target.whiteList, blackList = target.blackList)
                savedState = disk
                pendingSave = null
                if (drift.isNotEmpty()) {
                    status = "未完全生效：" + drift.take(3).joinToString("、") + " 已被其它入口改写"
                    Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                    ConfStore.appendAudit(
                        "SAVE-DRIFT want_status=" + target.enableStatus + " disk_status=" + disk.enableStatus
                                + " keys=" + drift,
                    )
                    return
                }
            }
            if (disk != null) {
                state = disk
                savedState = disk
            } else {
                savedState = target
            }
            if (pendingSave == target) pendingSave = null
            status = "已保存 · " + clock()
            ConfStore.appendAudit(
                "SAVE ok status=" + target.enableStatus + " style=" + target.uiStyle + " keys=" + diff.keys,
            )
        } else {
            status = "保存失败：" + bad.take(3).joinToString("、") + " 未能落盘，请检查 root 与存储"
            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
        }
    }

    // 页面离开（onStop）时立刻补一次保存，不再依赖 700ms 防抖能等到
    val hostActivity = context as? MainActivity
    SideEffect {
        if (hostActivity != null) {
            hostActivity.flushPending = {
                val pending = pendingSave
                if (pending != null && pending != savedState) {
                    commit(pending)
                }
            }
        }
    }

    /** 一键重启 SystemUI：持久化进程被杀后系统自动拉起，LSPosed 重新注入并读到新配置 */
    fun restartSystemUi() {
        if (restarting) return
        scope.launch {
            restarting = true
            try {
                val ok = withContext(Dispatchers.IO) { ConfStore.restartSystemUi() }
                val msg = if (ok) "已重启系统界面，状态栏会短暂重绘后按新配置生效"
                else "重启失败：请确认已授予 root 权限，或手动切换一次开关等待下个周期"
                status = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } finally {
                // v2.5：Activity 销毁 / 重组会取消协程，旧版只在正常返回时复位，
                // 一旦被取消 restarting 永远是 true，「重启界面」按钮彻底失效。
                restarting = false
            }
        }
    }

    // 函数式更新：回调里读到的永远是最新 state，同一重组内连点多个开关行不会互相覆盖
    val onStateChange: ((AbState) -> AbState) -> Unit = { update ->
        val newState = update(state)
        state = newState
        if (!loaded) {
            // 还没读到磁盘：只记录这一处改动，绝不能拿默认值整份回写
            pendingOps.add(update)
            pendingSave = newState
        } else {
            commit(newState)
        }
    }

    // v3.0.12 P2：「四个 App 全开」原内联于 HomeTilePage 调用的 onEnableAll 实参，
    // 随注入状态卡迁入 home-inject 二级页，提取为具名 val 逐字保留逻辑（G7 保存路径不变）。
    val onEnableAll: () -> Unit = {
        // 四个 App 的区域开关一次性打开。默认值全是关的，
        // 用户只说「快手没反应」时，最可能的原因不是规则坏了，
        // 而是那个 App 的开关压根没开过。
        onStateChange { s ->
            s.copy(
                enableDouyinTop = true, enableDouyinBottom = true,
                enableDouyinLike = true, enableDouyinRt = true,
                enableDouyinLt = true,
                enableDramaTop = true, enableDramaLike = true,
                enableDramaRt = true, enableDramaLt = true,
                enableDramaSwipe = true,
                enableKuaishou = true, enableKsRight = true,
                enableKsTop = true, enableKsBottom = true,
                enableKsCorner = true,
                enableBilibili = true, enableBiliRight = true,
                // v2.9.2：B站只剩右侧一项，其余三项保持关闭
                enableBiliTop = false, enableBiliBottom = false,
                enableBiliCorner = false,
            )
        }
    }

    val listLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // 只有名单页真正保存成功才刷新，且只更新名单相关字段：
        // 取消 / 点窗口外部关闭时不能把主页面其它未落盘改动一起丢弃。
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { AbConf.load() }
                state = state.copy(
                    listMode = fresh.listMode, whiteList = fresh.whiteList, blackList = fresh.blackList,
                )
                savedState = savedState.copy(
                    listMode = fresh.listMode, whiteList = fresh.whiteList, blackList = fresh.blackList,
                )
            }
        }
    }
    // off 模式没有「生效名单」可编辑，此时打开选择页仍编辑白名单，便于用户先备好内容再启用
    val openList: (String) -> Unit = { mode ->
        val edit = if (mode == ConfStore.MODE_OFF) ConfStore.MODE_WHITE else mode
        listLauncher.launch(
            Intent(context, WhitelistPickerActivity::class.java)
                .putExtra(WhitelistPickerActivity.EXTRA_MODE, edit)
        )
    }

    // 每次 Activity 重建都重新读盘（loaded 不再参与 saveable），
    // 读之前先等进行中的保存任务归零，避免 onStop 补存还没写完就读到旧值。
    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        val pair = withContext(Dispatchers.IO) {
            // 先把日志目录准备好：Android 11+ 只能在 su 下建 /sdcard/Android/ 子目录，
            // 建好并放开权限后，SystemUI 才能把诊断日志直接写进用户能看到的路径。
            ConfStore.prepareSharedDirs()
            // v2.8.3：启动即写一次自检（SELF + A11Y）。
            // 用户抓日志基本是直接拷文件、不点「导出日志」，自检必须落在启动路径上才有用。
            ConfStore.logSelfCheck(context)
            // v2.8.5：只读查询 LSPosed 作用域。作用域没勾，注入与位置微移都不会发生，
            // 这是「改什么都没反应」最前面那道门。v2.8.4 用 sqlite3 命令行读，
            // 而真机根本没有 sqlite3，这行日志一直停在 sqlite3=missing；
            // 现在改成 root 复制 + 系统 SQLiteDatabase 读，结果里直接列出缺哪几个包。
            ConfStore.logLine(LspScope.ensure(context))
            AbConf.load() to ConfStore.hasRoot()
        }
        // 加载窗口内用户点过的开关：在磁盘状态上按顺序重放，再一次性落盘。
        // 这样既不会丢用户的点击，也不会用默认值覆盖已有配置。
        var merged = pair.first
        for (op in pendingOps.toList()) merged = op(merged)
        pendingOps.clear()
        state = merged
        savedState = pair.first
        rootOk = pair.second
        status = if (pair.second) "配置已读取 · root 已授权"
        else "配置已读取 · 未检测到 root（仍可保存到应用内配置）"
        loaded = true
        if (merged != pair.first) {
            pendingSave = merged
            commit(merged)
        } else {
            pendingSave = null
        }
    }

    /** 把兜底目录里的日志汇总到用户指定目录，便于直接用文件管理器 / 电脑取走 */
    fun exportLogs() {
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                ConfStore.prepareSharedDirs()
                ConfStore.exportLogs()
            }
            status = if (out.isNotBlank()) "日志已就绪 · " + clock()
            else "还没有日志：请先打开「诊断日志」，让模块跑一个周期再导出"
            Toast.makeText(context, status + "\n" + ConfStore.LOG_DIR, Toast.LENGTH_LONG).show()
        }
    }

    val saveBar: @Composable () -> Unit = {
        SaveBar(
            status = status,
            restarting = restarting,
            onRestart = { restartSystemUi() },
            onExportLog = { exportLogs() },
        )
    }

    val tabs = remember {
        listOf(
            NavItem("首页", navIcon("home", PATH_HOME)),
            NavItem("功能", navIcon("feature", PATH_FEATURES)),
            NavItem("关于", navIcon("about", PATH_ABOUT)),
        )
    }

    /** 名单生效自检：把「名单有没有真正落盘」和「怎么确认模块侧命中」分开讲清楚。 */
    fun selfCheckList() {
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                val conf = ConfStore.read()
                if (conf == null) {
                    "自检：读不到应用内配置，请先点一次保存"
                } else {
                    val lists = ConfStore.readLists(conf)
                    val n = lists.active().size
                    val mode = lists.mode
                    val modeName = when (mode) {
                        ConfStore.MODE_BLACK -> "黑名单"
                        ConfStore.MODE_OFF -> "关闭名单"
                        else -> "白名单"
                    }
                    val visible = if (mode == ConfStore.MODE_BLACK) state.blackList else state.whiteList
                    if (mode == ConfStore.MODE_OFF) {
                        "自检：当前已关闭名单，所有应用前台都会执行防烧屏（名单内容仍保留）"
                    } else if (n == 0) {
                        if (mode == ConfStore.MODE_BLACK) {
                            "自检：黑名单为空，按兜底规则所有应用仍会执行防烧屏；" +
                                    "勾选应用后只有这些应用生效"
                        } else {
                            "自检：白名单为空，所有应用前台都会执行防烧屏（当前 conf 已落盘）"
                        }
                    } else if (visible.isBlank()) {
                        "自检异常：磁盘有 $n 个$modeName 应用，但界面没显示，请重新读取"
                    } else {
                        "自检通过：$modeName 已落盘 $n 个应用。命中判定由模块每 2 秒执行，" +
                                "打开名单内应用后，诊断日志出现 hit=true 即代表生效"
                    }
                }
            }
            status = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    MaterialTheme(colorScheme = scheme) {
        // 系统返回键先回二级页，不直接退出 App
        BackHandler(enabled = subPage != null) { subPage = null }
        Box(
            Modifier
                .fillMaxSize()
                .background(UiPalette.background(dark))
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // 柔光叠层：极淡的径向高光，深色近黑、浅色近白，细腻半透不刺眼
            Box(Modifier.fillMaxSize().background(UiPalette.sheen(dark)))

            if (subPage == null) {
                // v2.7：左右滑动切换首页/功能/关于。selectedTab 派生自 pagerState.currentPage，
                // 底部液态玻璃导航栏读同一个值，滑动过程中指示器随页面一起跟随。
                // userScrollEnabled 保持开启；首页界面适配 chips 的横向滚动由 Pager 的
                // 嵌套滚动机制让位，不会与整页滑动互相抢手势。
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 2,
                ) { page ->
                    when (page) {
                        0 -> HomeTilePage(
                            enabled = state.enableStatus,
                            onToggle = { on -> onStateChange { s -> s.copy(enableStatus = on) } },
                            onOpen = openSub,
                            saveBar = saveBar,
                        )
                        1 -> FeaturesPage(
                            state = state,
                            onOpen = openSub,
                            onStateChange = onStateChange,
                            saveBar = saveBar,
                        )
                        2 -> AboutPage(onOpen = openSub, onOpenUrl = openUrl)
                    }
                }

                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
                    var burnOffsetY by remember { mutableStateOf(0.dp) }
                    LaunchedEffect(navMicroSec) {
                        val ms = navMicroSec * 1000L
                        while (true) {
                            burnOffsetY = 1.dp
                            delay(ms)
                            burnOffsetY = 0.dp
                            delay(ms)
                        }
                    }
                    Box(Modifier.offset { IntOffset(0, burnOffsetY.roundToPx()) }) {
                        LiquidBottomTabs(
                            selectedTabIndex = { selectedTab },
                            onTabSelected = { goTab(it) },
                            backdrop = rememberLayerBackdrop(),
                            tabsCount = tabs.size,
                            // currentPageOffsetFraction 表示当前页相对整页的偏移（翻页中为小数），
                            // 两者相加即 0f..2f 的连续进度，导航栏据此逐帧跟随
                            tabProgress = {
                                pagerState.currentPage + pagerState.currentPageOffsetFraction
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        ) {
                            tabs.forEachIndexed { index, item ->
                                LiquidBottomTab({ goTab(index) }) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.label,
                                        tint = UiPalette.ink(dark).copy(alpha = 0.9f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = UiPalette.ink(dark).copy(alpha = 0.9f),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                SubPageHost(
                    page = subPage!!,
                    onBack = { subPage = null },
                    onOpenUrl = openUrl,
                    state = state,
                    onStateChange = onStateChange,
                    saveBar = saveBar,
                    onEnableAll = onEnableAll,
                    onOpenList = { openList(state.listMode) },
                    onSelfCheck = { selfCheckList() },
                )
            }
        }
    }
}

/** 顶部保存状态条：明确告诉用户改了什么、有没有真正落盘 */
@Composable
private fun SaveBar(
    status: String,
    restarting: Boolean,
    onRestart: () -> Unit,
    onExportLog: () -> Unit,
) {
    // 自动保存可靠之后，「保存设置」「重新读取」就是多余负担 —— 用户不该需要记得点保存。
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onExportLog,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) { Text("日志", style = MaterialTheme.typography.labelMedium) }
            FilledTonalButton(
                onClick = onRestart,
                enabled = !restarting,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            ) {
                Text(
                    if (restarting) "重启中…" else "重启界面",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// ---- 名单（白名单 / 黑名单 / 关闭）----

/** 卡片标题跟着模式走，避免「模式是黑名单、标题写白名单」的自相矛盾 */
private fun listTitle(mode: String): String = when (ConfStore.normMode(mode)) {
    ConfStore.MODE_BLACK -> "黑名单"
    ConfStore.MODE_OFF -> "名单（已关闭）"
    else -> "白名单"
}

/** 一句话讲清「当前模式 + 这份名单」到底会怎么表现 */
private fun listSummary(mode: String, text: String): String {
    // 与 ConfStore.parseWhitelist / Config.parse 用同一套分隔符，否则用户填中文逗号时
    // 卡片上的「以下 N 个应用」会显示成 1 个（整串当一项），与实际生效数量不符
    val n = text.split(",", "，", ";", "；").count { it.trim().isNotEmpty() }
    return when (ConfStore.normMode(mode)) {
        ConfStore.MODE_OFF -> "已关闭名单：所有应用前台都执行防烧屏"
        ConfStore.MODE_BLACK ->
            if (n == 0) "黑名单为空：按兜底仍对所有应用生效；勾选应用后只有名单内的会生效"
            else "只在以下 $n 个应用前台时执行防烧屏：$text"
        else ->
            if (n == 0) "未设置：所有应用前台都执行防烧屏"
            else "以下 $n 个应用前台时不隐藏系统栏：$text"
    }
}

/** 模式三选一。切换只改解释，不动两份名单内容，用户来回切不会丢数据。 */
@Composable
private fun ListModeChips(mode: String, onChange: (String) -> Unit) {
    val items = listOf(
        ConfStore.MODE_WHITE to "白名单",
        ConfStore.MODE_BLACK to "黑名单",
        ConfStore.MODE_OFF to "关闭",
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((id, label) in items) {
            FilterChip(
                selected = ConfStore.normMode(mode) == id,
                onClick = { onChange(id) },
                label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            )
        }
    }
}

/**
 * LSPosed 引擎跑不起来的真实原因。
 *
 * <p>三态必须分开写：把「root 未授权」说成「未检测到 root」，K90 这类已经刷了
 * KernelSU 的机器会被误判成「这模块不支持我的手机」，而用户其实只需要去点一下允许。</p>
 */
private fun hookBlockReason(c: Capability?): String {
    if (c == null) {
        return "正在检测运行环境…"
    }
    return when {
        c.rootState == Capability.ROOT_DENIED ->
            "root 还没授权给本应用（su 被拒），LSPosed 引擎暂时用不了"
        c.rootState != Capability.ROOT_GRANTED ->
            "这台手机没有 root，LSPosed 引擎跑不起来"
        !c.lsposedPresent ->
            if (c.note.isNotEmpty()) c.note else "有 root，但系统里没找到 LSPosed 模块"
        // 走到这里说明探测结果自相矛盾（root 与 LSPosed 都齐了却没被算作可用），
        // 不编一个原因，直接让用户重测 —— 编出来的解释比不给更误导。
        else ->
            "环境探测结果不完整，点「重新检测」再看一次"
    }
}

// ---- 运行方式（v2.6：Hook / 无障碍遮盖 双引擎）----

/** 从任意 Context 找到宿主 Activity：Shizuku 的授权对话框必须挂在一个 Activity 上。 */
private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/**
 * 运行方式卡片。
 *
 * <p>设计要点（三条都来自之前的教训）：</p>
 * <ol>
 *   <li><b>不可用的选项不隐藏，灰显 + 写明原因。</b>v2.9 那次「状态栏偏移磁贴消失」就是
 *       把入口删了但功能还在跑，用户以为坏了。这里反过来：没 root 也把 LSPosed 选项摆出来，
 *       点它时明确告诉你缺什么。</li>
 *   <li><b>选完立刻告诉用户还差哪一步。</b>遮盖引擎选了不等于能用——还要开无障碍；
 *       开了不等于在跑——服务可能没连上。三个状态分开显示，不合并成一句「已开启」。</li>
 *   <li><b>不记忆实验性动作。</b>「真隐藏状态栏」用 shell 禁用状态栏，一旦落盘成配置项，
 *       用户换引擎或卸载应用后状态栏就再也回不来。所以它只是两个手动按钮，不进配置。</li>
 * </ol>
 */
@Composable
private fun EngineCard(
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    dark: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cap by remember { mutableStateOf<Capability?>(null) }
    var hook by remember { mutableStateOf<HookState?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf("") }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    // 能力探测要 fork su，必须离开主线程；refreshTick 变化即重探（从设置页返回、授权完成等）
    LaunchedEffect(refreshTick) {
        val probed = withContext(Dispatchers.IO) { Capability.probe(context) }
        cap = probed
        // Hook 版本探测同样要 root（读别人的 /proc），root 没授权时直接跳过，
        // 否则会白等 8 秒 su 超时，首屏卡片一直停在「正在检测」。
        hook = withContext(Dispatchers.IO) {
            if (probed.rootState == Capability.ROOT_GRANTED) HookState.probe(context) else null
        }
    }

    // 用户去系统设置里手动开完无障碍、按返回键回来时刷新，否则界面会一直显示「未开启」
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Shizuku 授权结果回到界面：成功/拒绝都要有反馈，不能静默。
    // key 用 refreshTick 而不是 Unit：Shizuku 常见的使用顺序是「先装本应用、之后才启动
    // Shizuku 服务」，只在首次组合时注册会错过之后的回调，用户点完授权看不到任何反应。
    DisposableEffect(refreshTick) {
        ShizukuBridge.watchPermission { granted ->
            toast(if (granted) "Shizuku 已授权" else "Shizuku 授权被拒绝，可到 Shizuku 应用里手动授予")
            refreshTick++
        }
        onDispose { ShizukuBridge.unwatchPermission() }
    }

    val c = cap
    val engine = ConfStore.normEngine(state.engine)
    val overlay = engine == ConfStore.ENGINE_OVERLAY
    val hookUsable = c?.hookUsable() == true
    val ink = UiPalette.ink(dark)
    val sub = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = UiPalette.surface(dark)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "运行方式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (overlay) "无障碍遮盖" else "LSPosed 模块",
                    style = MaterialTheme.typography.labelMedium,
                    color = UiPalette.accent(dark),
                )
            }

            // 总开关是总闸：它关着的时候，下面选哪条引擎都不会动手。真机日志里连续三次
            // 看到用户在两张芯片之间来回切、而 status 全程 false —— 问题从来不在引擎，
            // 在最下面那张卡片的开关上，而那里原先只有一行同色小字「已关闭」，看不出来。
            // 放在芯片之前，是因为它是「切了也不会有反应」的第一顺位原因。
            if (!state.enableStatus) {
                MasterOffNotice(dark) { onStateChange { it.copy(enableStatus = true) } }
            }

            Text(
                "两条引擎互斥，同一时刻只有一条生效。有 root 走 LSPosed（App 内控件周期真隐藏）；" +
                        "没有 root 走无障碍遮盖（只盖系统栏，App 内控件动不了）。",
                style = MaterialTheme.typography.bodySmall,
                color = sub,
                modifier = Modifier.padding(top = 4.dp),
            )

            // v2.7.7：ColorOS 15 / Android 15 的状态栏改由独立图层自绘，
            // LSPosed 引擎对 SystemUI 状态栏 View 调 setAlpha(0) 不会再影响真正绘制的那一层
            // （真机表现：日志里周期在跑、屏幕毫无变化）。这类设备、以及「临时 root」
            // （重启后 root 丢失 → 模块不加载）都必须用无障碍遮盖引擎。直接在卡片上点明，
            // 别让用户在两条引擎之间反复试、反复重启。
            val romHint = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.DISPLAY)
                .lowercase()
            val oplusLayered = Build.VERSION.SDK_INT >= 35
                    && (romHint.contains("oppo") || romHint.contains("oneplus")
                    || romHint.contains("realme") || romHint.contains("coloros")
                    || romHint.contains("oplus"))
            if (oplusLayered && !overlay) {
                Text(
                    "⚠ 检测到 ColorOS / Android 15：本系统状态栏为独立图层，" +
                            "LSPosed 引擎的隐藏在此机型不生效（日志在跑、屏幕没反应）。" +
                            "请点下方「无障碍遮盖」并去系统设置开启本应用的无障碍服务。" +
                            "临时 root 重启后模块会失效，也建议用这条引擎。",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiPalette.accent(dark),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !overlay,
                    onClick = {
                        if (!hookUsable) {
                            toast("没检测到 root + LSPosed，这条引擎跑不起来。没有 root 请选「无障碍遮盖」。")
                        } else {
                            onStateChange { it.copy(engine = ConfStore.ENGINE_LSPOSED) }
                            toast(
                                "已切换到 LSPosed 引擎。还要确认两件事：" +
                                        "① LSPosed 里已勾选本应用（作用域含「系统界面」）；" +
                                        "② 重启一次系统界面。" +
                                        (if (state.enableStatus) ""
                                        else "另外，防烧屏保护总开关现在是关着的，不打开不会有任何效果。")
                            )
                        }
                    },
                    label = { Text("LSPosed 模块", style = MaterialTheme.typography.bodySmall) },
                )
                FilterChip(
                    selected = overlay,
                    onClick = {
                        onStateChange { it.copy(engine = ConfStore.ENGINE_OVERLAY) }
                        // 切到遮盖引擎时，如果之前用 Shizuku 禁用过状态栏，先恢复再切
                        if (ShizukuBridge.hasPermission()) {
                            scope.launch { withContext(Dispatchers.IO) { ShizukuBridge.restoreStatusBar() } }
                        }
                        // 原先这条分支一声不响：点完芯片界面上除了一行小字没有任何变化，
                        // 用户不知道下一步该去系统设置里打开无障碍。
                        toast(
                            if (!state.enableStatus) {
                                "已切换到无障碍遮盖引擎。注意：防烧屏保护总开关还关着，打开才会生效。"
                            } else {
                                "已切换到无障碍遮盖引擎。去系统设置的无障碍里打开本应用的服务，才会真正开始遮盖。"
                            }
                        )
                    },
                    label = { Text("无障碍遮盖", style = MaterialTheme.typography.bodySmall) },
                )
            }

            Text(
                c?.summary() ?: "正在检测运行环境…",
                style = MaterialTheme.typography.labelSmall,
                color = sub,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (!hookUsable && !overlay) {
                // 关键提示：新用户（无 root）第一次打开时，默认引擎是 lsposed，跑不起来。
                // 只写「不可用」会让人以为这软件用不了；给一个一键切换，他才知道下一步点哪。
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "· " + hookBlockReason(c),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onStateChange { it.copy(engine = ConfStore.ENGINE_OVERLAY) } },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("改用它", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (!hookUsable && overlay) {
                Text(
                    "· LSPosed 那条不可用：" + hookBlockReason(c).removePrefix("· "),
                    style = MaterialTheme.typography.labelSmall,
                    color = sub,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 「root 未授权」是唯一一种「用户自己点两下就能修好」的状态，必须单独给按钮：
            // 停在「未检测到 root」会让 K90 这类已 root 的机器被误判成不支持。
            if (c?.rootState == Capability.ROOT_DENIED) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "去 KernelSU / Magisk / APatch 里给本应用 root 权限，然后点右边重新检测。",
                        style = MaterialTheme.typography.labelSmall,
                        color = sub,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            ConfStore.invalidateRootCache()
                            Capability.invalidate()
                            HookState.invalidate()
                            refreshTick++
                            toast("正在重新检测…首次授权可能要等几秒")
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("重新检测", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // 「系统界面里还是旧版」与选哪条引擎无关，所以放在两条引擎各自的区块之前：
            // 用户切引擎时最容易被这条误导，提示必须在切换点之前出现。
            HookStaleNotice(
                hook = hook,
                dark = dark,
                busy = busy,
                onRestart = {
                    if (busy.isNotEmpty()) return@HookStaleNotice
                    scope.launch {
                        busy = "restart"
                        try {
                            val out = withContext(Dispatchers.IO) { HookState.restartSystemUi() }
                            if (out != null && out.contains("ab-restart-ok")) {
                                HookState.invalidate()
                                toast(
                                    "已发出重启指令。屏幕会黑一下、通知栏消失几秒，系统界面会自动恢复；" +
                                            "等通知栏回来后这里是新版状态才算真正加载。"
                                )
                                delay(5000)
                                refreshTick++
                            } else {
                                toast("重启失败，可在终端执行：su -c \"killall com.android.systemui\"")
                            }
                        } finally {
                            busy = ""
                        }
                    }
                },
            )

            if (overlay) {
                Spacer(Modifier.height(10.dp))
                val a11yOn = c?.a11yEnabled == true
                val running = BurnInMaskService.connected
                Text(
                    when {
                        !a11yOn -> "第 1 步：开启本应用的无障碍服务，遮盖才会工作。"
                        !running -> "无障碍已开，但服务没连上。到设置里把它关掉再打开一次；仍不行就重启手机。"
                        else -> "遮盖引擎正在运行：按周期遮住系统栏，触摸完全穿透。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (a11yOn && running) UiPalette.accent(dark) else sub,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!a11yOn || !running) {
                        TextButton(
                            onClick = {
                                try {
                                    val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(i)
                                } catch (t: Throwable) {
                                    toast("打不开无障碍设置页，请到「设置 → 无障碍」里手动开启")
                                }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                if (a11yOn) "去设置里重开" else "去开启无障碍",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    if (c?.shizukuGranted == true) {
                        TextButton(
                            onClick = {
                                if (busy.isNotEmpty()) return@TextButton
                                scope.launch {
                                    busy = "a11y"
                                    try {
                                        val out = withContext(Dispatchers.IO) {
                                            ShizukuBridge.enableAccessibility(context)
                                        }
                                        if (out == null) {
                                            toast("Shizuku 执行失败：" + ShizukuBridge.lastError())
                                        } else if (out.contains("ab-ok")) {
                                            toast("已通过 Shizuku 开通无障碍，系统生效可能需要几秒")
                                            refreshTick++
                                        } else {
                                            toast("执行结果：" + out.take(140))
                                        }
                                    } finally {
                                        busy = ""
                                    }
                                }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("用 Shizuku 一键开通", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (running) {
                        // v2.8.6：移除遮盖预览入口。它会强制画一帧应用内黑块，
                        // 用户看到的是整屏被盖住，与「不要铺黑、只要偏移」的结论相冲。
                        Text(
                            "遮盖预览已随盖黑通道一并移除；本版 App 内控件走周期真隐藏。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (running && BurnInMaskService.lastError.isNotEmpty()) {
                    Text(
                        "· " + BurnInMaskService.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                A11yRequiredNotice(dark)

            // 可选：Shizuku 增强与上面的无障碍状态同属遮盖引擎专属内容。
            // LSPosed 引擎不需要借 Shizuku 代开无障碍，展示出来只会让有 root 的用户
            // 误以为必须装 Shizuku 才能用模块 —— 按需求整块收进 if (overlay)。
            Spacer(Modifier.height(10.dp))
            Text(
                "可选：Shizuku 增强",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ink,
            )
            Text(
                when {
                    c == null -> "正在检测…"
                    !c.shizukuInstalled -> "未安装 Shizuku。装了可以用它代开无障碍、省掉手动找设置页。"
                    !c.shizukuReady -> "Shizuku 已安装但服务没在跑，请在 Shizuku 应用里启动服务。"
                    !c.shizukuGranted -> "已连上 Shizuku，但本应用还没被授权。"
                    else -> "已授权。可用它一键开通无障碍，或临时隐藏状态栏。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = sub,
                modifier = Modifier.padding(top = 2.dp),
            )

            if (c != null && c.shizukuReady && !c.shizukuGranted) {
                TextButton(
                    onClick = {
                        val act = context.findHostActivity()
                        if (act == null) {
                            toast("找不到宿主界面，无法发起授权")
                            return@TextButton
                        }
                        val ok = ShizukuBridge.requestPermission(act)
                        if (!ok) {
                            toast("无法发起授权：请打开 Shizuku 应用确认服务在运行，并在其中手动授予本应用权限")
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) {
                    Text("授权 Shizuku", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (c?.shizukuGranted == true) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            if (busy.isNotEmpty()) return@TextButton
                            scope.launch {
                                busy = "hide"
                                try {
                                    val out = withContext(Dispatchers.IO) { ShizukuBridge.setStatusBarDisabled(true) }
                                    toast(
                                        if (out == null) "执行失败：" + ShizukuBridge.lastError()
                                        else "状态栏已临时隐藏。留意：下拉通知栏会一起失效，记得点「恢复」。"
                                    )
                                } finally {
                                    busy = ""
                                }
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("临时隐藏状态栏", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick = {
                            if (busy.isNotEmpty()) return@TextButton
                            scope.launch {
                                busy = "restore"
                                try {
                                    val out = withContext(Dispatchers.IO) { ShizukuBridge.restoreStatusBar() }
                                    toast(if (out == null) "执行失败：" + ShizukuBridge.lastError() else "已恢复状态栏")
                                } finally {
                                    busy = ""
                                }
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("恢复状态栏", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    "「临时隐藏状态栏」不写入配置，重启手机或点「恢复」即还原。",
                    style = MaterialTheme.typography.labelSmall,
                    color = sub,
                )
            }
            }   // 闭合 if (overlay)：无障碍状态 + Shizuku 增强都只在遮盖引擎下显示
        }
    }
}

/**
 * 「防烧屏总开关没开」的提示块。
 *
 * <p>为什么值得单独占一块：总开关是总闸，引擎只是执行方式。它关着的时候，
 * 用户在「运行方式」卡片里怎么切都不会有任何反应，而界面上原先只有总开关卡片里
 * 一行同色小字「已关闭」——连续三份真机日志里，用户都在这张卡片上来回切引擎，
 * 而 status 全程 false。属于「功能没坏，但用户看不出现在不生效」，与旧版检测同类。</p>
 *
 * <p>样式用警示橙而非错误红：这不是故障，是一个用户自己点一下就好的状态，
 * 但必须比正文更先被看到。按钮直接开总闸，不让用户滚下去找开关。</p>
 */
@Composable
private fun MasterOffNotice(dark: Boolean, onEnable: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(UiPalette.warn(dark).copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            "防烧屏保护总开关没开",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = UiPalette.warn(dark),
        )
        Text(
            "总开关是总闸，引擎只是执行方式 —— 它关着的时候，下面选哪一条都不会动手，" +
                    "隐藏、像素偏移一律不跑。日志里这一项是 status=false。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
        TextButton(
            onClick = onEnable,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                "开启防烧屏保护",
                style = MaterialTheme.typography.labelMedium,
                color = UiPalette.warn(dark),
            )
        }
    }
}

/**
 * 「系统界面里跑的还是旧版」的提示块。
 *
 * <p>这不是可选提示：本模块的 Hook 代码注入在系统界面进程里，而系统界面是常驻进程，
 * 装包、覆盖安装都不会让它重启。用户装完新版后，App 界面已经是新的，但真正干活的
 * 还是旧 dex —— 所有新增开关都表现为「点了没反应」，而界面上没有任何地方能看出来。
 * 该现象在真机反馈里出现过三次，与其每次让用户贴日志，不如自己算出来直接给按钮。</p>
 *
 * <p>判定不靠日志：拿系统界面进程的启动时刻和本 APK 的安装时刻比大小，进程更老就是旧版。
 * 探不到（没 root、ROM 改了进程名）时整块不显示，不猜也不误报。</p>
 */
@Composable
private fun HookStaleNotice(
    hook: HookState?,
    dark: Boolean,
    busy: String,
    onRestart: () -> Unit,
) {
    if (hook == null || !hook.stale()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(UiPalette.warn(dark).copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            "系统界面里跑的还是旧版",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = UiPalette.warn(dark),
        )
        Text(
            "隐藏逻辑注入在系统界面进程里，而它是常驻进程 —— 装包不会让它重启。" +
                    "它现在这份是 " + agoText(hook.runningForMs()) + "启动的，比你装这一版更早，" +
                    "所以这次改的东西点了不会有反应。" +
                    (if (hook.pid > 0) "（pid " + hook.pid + "）" else ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
        TextButton(
            onClick = onRestart,
            enabled = busy.isEmpty(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                if (busy == "restart") "正在重启…" else "重启系统界面",
                style = MaterialTheme.typography.labelMedium,
                color = UiPalette.warn(dark),
            )
        }
    }
}

/** 把毫秒时长写成「3 分钟前」这种人话；超过一天就报天数，不让数字堆成一长串。 */
internal fun agoText(ms: Long): String {
    if (ms <= 0L) return "刚刚"
    val min = ms / 60_000L
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "$min 分钟前"
        min < 60 * 24 -> "${min / 60} 小时前"
        else -> "${min / (60 * 24)} 天前"
    }
}

/**
 * 「关闭也必须开着无障碍」的说明块。
 *
 * <p>这条提示不是免责声明，是在解释一个真实机制：遮盖窗口由无障碍服务创建，
 * 服务一停窗口立刻销毁，功能其实已经停了，但 App 里的开关还是「开」——
 * 用户下次看到开关会以为功能在跑，或者以为关不掉。所以必须写明
 * 「先开无障碍，再回这里关开关」，而不是让他去系统设置里关无障碍当关功能。</p>
 */
@Composable
private fun A11yRequiredNotice(dark: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(UiPalette.warn(dark).copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            "如果要关闭该功能，必须开启无障碍功能，然后关闭下方总开关",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = UiPalette.warn(dark),
        )
        Text(
            "遮盖窗口随无障碍服务一起创建、一起销毁。直接在系统设置里关掉无障碍，" +
                    "功能会立刻停，但下面的总开关还是「开」——你会以为它还在跑，也找不到地方关。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

// ---- 首页 ----
/**
 * v3.0.12 P1/P3：首页只保留入口与两个可见性开关，四张详情大卡片下沉到二级菜单。
 * 可见元素顺序（G1）：标题区 → saveBar → 防烧屏总开关 → 四入口 → 隐藏桌面图标 → 从最近任务隐藏。
 * 四入口副标题采用 PRD G3 短版（设计评审 S1 拍板）。
 */
@Composable
private fun HomeTilePage(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    saveBar: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    // ===== v3.0.12 P3：两个可见性开关从 AboutPage 迁入（真源=独立 prefs，非 AbState） =====
    val context = LocalContext.current
    // 用 remember 而非 rememberSaveable：切换即落盘，重建时从 prefs 重读，避免 v3.0.9 那种
    // saveable 旧快照覆盖 prefs 真值的事故（G13）。
    var hideLauncher by remember { mutableStateOf(AppVisibilityStore.isHideLauncherIcon(context)) }
    var hideRecents by remember { mutableStateOf(AppVisibilityStore.isHideRecents(context)) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 96.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("护眼防烧屏", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = UiPalette.ink(dark))
                // 不再写死「LSPosed 模块」：应用同时支持免 root 的无障碍遮盖引擎，
                // 无 root 用户看到旧文案会以为自己用不了。
                Text("支持 LSPosed / 无障碍遮盖双引擎", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // v2.7.0：用户点名的首页标语；「护眼防烧屏」是项目第一目标（核心价值观）
            Text("为人民服务", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = UiPalette.accent(dark))
        }
        Text("为天地立心", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)

        saveBar()

        Spacer(Modifier.height(12.dp))

        // 防烧屏总开关（原 L1958-1983 卡片整体上移至 saveBar 之后、四入口之前，内容交互不变）
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = UiPalette.surface(dark)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("防烧屏总开关", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
                    Text("总闸：关闭后所有隐藏与遮盖都不生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                    Text(if (enabled) "已开启" else "已关闭",
                        style = MaterialTheme.typography.bodySmall,
                        // 「已关闭」用警示橙：它是「所有功能都不生效」的唯一开关，
                        // 与强调色同色时，视线扫过去和「已开启」没有区别。
                        color = if (enabled) UiPalette.accent(dark) else UiPalette.warn(dark),
                        modifier = Modifier.padding(top = 6.dp))
                }
                LiquidToggle(checked = enabled, onCheckedChange = onToggle)
            }
        }

        Spacer(Modifier.height(12.dp))

        // v3.0.12 P1：四张详情卡改为四个 ListRow 入口，点击进各自二级页（G3 短版副标题）
        ListRow("运行方式", "Hook / 无障碍双引擎", { onOpen(PAGE_HOME_ENGINE) }, dark)
        ListRow("注入状态", "四个 App 注入情况", { onOpen(PAGE_HOME_INJECT) }, dark)
        ListRow("界面适配", "按系统界面避让", { onOpen(PAGE_HOME_UISTYLE) }, dark)
        ListRow("白名单", "名单内应用不干预", { onOpen(PAGE_HOME_LIST) }, dark)

        Spacer(Modifier.height(12.dp))

        // ===== v3.0.12 P3：本 App 自身可见性（四入口之后，逐字迁入，逻辑不变） =====
        // P1 桌面图标隐藏：OptionRow 内嵌 Material3 Switch（整行可点），切换即落盘 + 组件别名生效。
        OptionRow(
            title = "隐藏桌面图标",
            subtitle = "开后桌面无图标，可从 LSPosed 或系统设置打开",
            checked = hideLauncher,
            dark = dark,
            onCheckedChange = { on ->
                // 内部已含 applyLauncherVisibility(DONT_KILL_APP)：写 prefs + 切组件别名，App 不重启
                AppVisibilityStore.setHideLauncherIcon(context, on)
                hideLauncher = on // 更新本地镜像，图标即时消失
            },
        )
        // 恢复提示：常驻可见（开/关都显示），口径与 PRD §一 / design-backend §5.3 一致，
        // 避免用户自锁后无措；不承诺备用图标/通知/磁贴，adb 兜底不塞进 UI。
        Text(
            "隐藏后桌面不再显示本应用；需要恢复时，从 LSPosed 模块管理器或「设置→应用管理→防烧屏」打开本应用，回到此处关闭开关即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
        )

        // P2 最近任务隐藏：setHideRecents 只写 prefs，生效由顶层 applyRecentsVisibility 立即触发。
        OptionRow(
            title = "从最近任务隐藏",
            subtitle = "开后按 Home，本应用不出现在最近任务列表",
            checked = hideRecents,
            dark = dark,
            onCheckedChange = { on ->
                AppVisibilityStore.setHideRecents(context, on) // 仅写 prefs（后端 §8 锁定签名）
                applyRecentsVisibility(context)               // 立即对本任务栈生效，不等 onResume
                hideRecents = on                              // 更新本地镜像
            },
        )
    }
}

// ---- v3.0.12 P2：首页四入口对应的四个二级页（原卡片内容原样搬迁） ----

/** 运行方式二级页（home-engine）：薄壳整体调用原 EngineCard，控件与副作用零遗漏（G4）。 */
@Composable
private fun EnginePage(
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    dark: Boolean,
) {
    EngineCard(state = state, onStateChange = onStateChange, dark = dark)
}

/** 注入状态二级页（home-inject）：薄壳整体调用原 InjectStatusCard（G5）。 */
@Composable
private fun InjectStatusPage(
    dark: Boolean,
    onEnableAll: () -> Unit,
) {
    InjectStatusCard(dark = dark, onEnableAll = onEnableAll)
}

/** 界面适配二级页（home-uistyle）：原首页内联 Card 原样搬入，7 厂商 chips 一项不删（G5）。 */
@Composable
private fun UiStylePage(
    uiStyle: String,
    onUiStyleChange: (String) -> Unit,
    dark: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = UiPalette.surface(dark)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("界面适配", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
            Text("选择你手机的系统界面，模块按它的胶囊 / 导航栏结构避让，不会误隐藏灵动岛、原子岛。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((id, label) in UI_STYLE_LABELS) {
                    FilterChip(
                        selected = uiStyle == id,
                        onClick = { onUiStyleChange(id) },
                        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
        }
    }
}

/** 白名单二级页（home-list）：原首页内联 Card 原样搬入，模式 chips + 包名输入 + 自检一项不删（G5）。 */
@Composable
private fun WhitelistPage(
    listMode: String,
    activeList: String,
    onListModeChange: (String) -> Unit,
    onListTextChange: (String) -> Unit,
    onOpenList: () -> Unit,
    onSelfCheck: () -> Unit,
    dark: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = UiPalette.surface(dark)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(listTitle(listMode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = UiPalette.ink(dark), modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenList) { Text("选择应用") }
            }
            ListModeChips(listMode, onListModeChange)
            Text(listSummary(listMode, activeList),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            // 手动填包名：图形选择页是主路径，这里保留快速粘贴的能力
            if (ConfStore.normMode(listMode) != ConfStore.MODE_OFF) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = activeList,
                    onValueChange = onListTextChange,
                    label = {
                        Text(if (ConfStore.normMode(listMode) == ConfStore.MODE_BLACK)
                            "黑名单包名，逗号分隔" else "白名单包名，逗号分隔")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
            }
            // 生效自检：明确告诉用户名单有没有落盘、怎么确认模块侧命中
            TextButton(onClick = onSelfCheck, modifier = Modifier.padding(top = 2.dp)) {
                Text("检测是否生效", style = MaterialTheme.typography.labelMedium,
                    color = UiPalette.accent(dark))
            }
        }
    }
}

/**
 * 注入状态卡（v2.9.1）。
 *
 * <p>真机连着几版都卡在同一件事上：用户说「快手 / 红果 / B站没反应」，
 * 而日志里看不到任何 INJECTED 行，只能靠猜——是没注入？还是开关没开？
 * 还是识别规则没扫到？三者表现完全一样。这里把「模块代码到底进没进这个 App 进程」
 * 直接摆到首页：注入进程每写一条 ENTER / INJECTED，App 进程就记一次时间戳，
 * 一屏看四个 App 的状态。没检测到就是 LSPosed 作用域没勾（或勾了没重启 App），
 * 点右下角直接跑作用域自检，不用再去翻日志。</p>
 */
@Composable
private fun InjectStatusCard(dark: Boolean, onEnableAll: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var scopeText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // 用户切去视频 App 再切回来时，状态要跟着更新，所以 15 秒轮询一次
        while (true) {
            delay(STATUS_REFRESH_MS)
            now = System.currentTimeMillis()
        }
    }
    val apps = listOf(
        "douyin" to "抖音",
        "kuaishou" to "快手",
        "drama" to "红果",
        "bilibili" to "B站",
    )
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = UiPalette.surface(dark)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("注入状态", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
            Text("「未检测到」= 模块代码没进这个 App 进程：LSPosed 里没勾它的作用域，或勾了但没重启该 App。" +
                "若是覆盖安装/更新了本模块，需重启手机一次（不是只重启系统界面）：SystemUI 会自动重注，" +
                "但抖音/快手等三方 App 只有重启手机后才会重新加载新 dex。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            for ((kind, label) in apps) {
                val ts = ConfigProvider.lastInject(context, kind)
                // 24 小时内的记录才算数：更早的痕迹说明不了「刚装这版」的状态
                val fresh = ts > 0L && now - ts < 24L * 3600_000L
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium,
                        color = UiPalette.ink(dark), modifier = Modifier.weight(1f))
                    Text(
                        if (fresh) "已注入 · " + agoText(now - ts) else "未检测到",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fresh) UiPalette.accent(dark) else UiPalette.warn(dark),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        scopeText = "正在读取 LSPosed 配置…"
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                runCatching { LspScope.diagnose(context) }
                                    .getOrElse { "读取失败：" + (it.message ?: it.javaClass.simpleName) }
                            }
                            scopeText = text
                            withContext(Dispatchers.IO) { ConfStore.logLine(LspScope.ensure(context)) }
                        }
                    },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text("作用域自检", style = MaterialTheme.typography.labelMedium,
                        color = UiPalette.accent(dark))
                }
                TextButton(
                    onClick = onEnableAll,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text("四个 App 全开", style = MaterialTheme.typography.labelMedium,
                        color = UiPalette.accent(dark))
                }
            }
        }
    }
    scopeText?.let { text ->
        ScopeDialog(
            text = text,
            dark = dark,
            onOpenLsposed = { openLsposedManager(context) },
            onDismiss = { scopeText = null },
        )
    }
}

// ---- 功能：一行一行的分组入口 + 二级菜单 ----
/**
 * 功能页：与关于页一致的单行列表（ListRow），点开一项进二级菜单。
 *
 * <p>v2.7.8 之前这里是两列磁贴 + 五个横向 tab：tab 与外层 HorizontalPager
 * 抢左右滑动手势，磁贴只有「已开启/已关闭」两态、放不下说明文字，
 * 且抖音/红果的开关在免 root 遮盖引擎下没置灰（假开关）。
 * 改成列表后入口与内容分离，副标题直接给出已开启数量。</p>
 */
@Composable
private fun FeaturesPage(
    state: AbState,
    onOpen: (String) -> Unit,
    onStateChange: ((AbState) -> AbState) -> Unit,
    saveBar: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    var showCycleDialog by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 96.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom) {
            Text("功能", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = UiPalette.ink(dark))
            Text("为生民立命", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
        Text("按应用分组设置，点开一项逐条调整 · 开关改动即时保存",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        saveBar()
        Spacer(Modifier.height(10.dp))

        // v3.0.20：周期时间从「通用」提到功能首页 —— 它是整套隐藏机制的节奏，
        // 放在二级页里太多人找不到。调完立即落盘，各区域共用同一节奏。
        ListRow("周期时间", "藏 ${state.hideSec}s · 显 ${state.showSec}s · 状态栏与视频控件共用",
            { showCycleDialog = true }, dark, trailing = "›")

        val generalOn = listOf(
            state.enableStatusBar, state.enableNav, state.enableLog, state.enableBall,
        ).count { it }
        ListRow("通用", "系统栏 / 日志 / 悬浮球 · 已开启 $generalOn/3 项",
            { onOpen("feat-general") }, dark)

        val douyinOn = listOf(
            state.enableDouyinTop, state.enableDouyinBottom, state.enableDouyinLike,
            state.enableDouyinRt, state.enableDouyinLt, state.enableLive,
        ).count { it }
        ListRow("抖音", "视频页五区 + 直播间 · 已开启 $douyinOn/6 项",
            { onOpen("feat-douyin") }, dark)

        val dramaOn = listOf(
            state.enableDramaTop, state.enableDramaLike, state.enableDramaRt,
            state.enableDramaLt, state.enableDramaSwipe,
        ).count { it }
        ListRow("红果", "短剧视频页五个区域 · 已开启 $dramaOn/5 项",
            { onOpen("feat-drama") }, dark)

        val ksOn = listOf(
            state.enableKuaishou, state.enableKsRight, state.enableKsTop,
            state.enableKsBottom, state.enableKsCorner,
        ).count { it }
        ListRow("快手", "总开关 + 四个区域 · 已开启 $ksOn/5 项",
            { onOpen("feat-kuaishou") }, dark)

        // v2.9.2：B站只做右侧点赞区。其余三项识别不到稳定容器，已从 UI 删除，
        // 这里也不再计入，避免「5 项开了 3 项」这种看不出实际效果的计数。
        val biliOn = if (state.enableBilibili && state.enableBiliRight) 1 else 0
        ListRow("B站", if (biliOn == 1) "右侧点赞区 · 已开启" else "右侧点赞区 · 未开启",
            { onOpen("feat-bilibili") }, dark)

        Spacer(Modifier.height(14.dp))
        RoadmapCard(dark)
    }
    if (showCycleDialog) {
        CycleDialog(
            state = state,
            onStateChange = onStateChange,
            dark = dark,
            onDismiss = { showCycleDialog = false },
        )
    }
}

/**
 * 开关行：标题 + 副标题 + 右侧 Switch，整行可点，底部细分隔线。
 * 与关于页 ListRow 同一套排版，深浅色一致。
 *
 * <p>所有调用点一律用命名参数传 onCheckedChange。v2.9 那次事故是
 * 六参 TileItem 的尾随 lambda 被绑到最后一个参数 navClick，二十多个
 * 开关的 onToggle 集体退化成空实现。这里从签名上就不留尾随 lambda
 * 的误绑空间，且 checked 由 state 直接给出，开关值不会自己漂移。</p>
 */
@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    dark: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    disabledLabel: String = "",
    // v3.0.7：红果未注入提示角标，仅新调用点传；旧调用默认空串，渲染结果零变化
    subtitleWarn: String = "",
) {
    val ink = if (enabled) UiPalette.ink(dark) else MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = ink)
            if (subtitle.isNotEmpty()) {
                Text(
                    // 角标用 warn 着色，且只放在页面背景上（不挪进 surface 卡片）
                    text = if (subtitleWarn.isEmpty()) AnnotatedString(subtitle)
                    else buildAnnotatedString {
                        append(subtitle)
                        withStyle(SpanStyle(color = UiPalette.warn(dark))) {
                            append(subtitleWarn)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (enabled) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        } else {
            Text(if (disabledLabel.isNotEmpty()) disabledLabel else "常驻",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp)
        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)))
}

/** 免 root 遮盖引擎下统一的能力边界提示：置灰开关，不放假入口。 */
@Composable
private fun OverlayNotice(dark: Boolean, appLabel: String) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = UiPalette.warn(dark).copy(alpha = 0.12f)),
    ) {
        Text("当前不是 Hook 引擎。系统栏照常按周期遮住；$appLabel 内的固定控件（右侧按钮列 / 顶部标题 / 底部导航）需要模块注入该 App 进程才能隐藏，本机没注入时这几项不会生效。想在 $appLabel 里也用上，请在有 LSPosed 的环境给本模块勾上它的作用域。",
            style = MaterialTheme.typography.bodySmall,
            color = UiPalette.warn(dark),
            modifier = Modifier.padding(14.dp))
    }
}

/**
 * v3.0.7 红果「未注入 / 待勾选作用域」提示卡（仅 LSPosed 引擎且 24h 内无注入痕迹时出现）。
 *
 * <p>对比度按 UI §3.3/§6 修正（评审 S3，不照抄前端 §3.3 的 4.04:1 浅色示例）：
 * 浅色卡标题/正文用 ink(false)，按钮改 Filled（accent 底 + 白字，5.73:1）；
 * 深色卡维持 warn 文字 + accent 文字按钮。警示语义由文案承载，颜色非唯一载体。</p>
 */
@Composable
private fun ScopePendingNotice(dark: Boolean, onSelfCheck: () -> Unit) {
    // 浅色 warnLight #9A5B00 压 12% 橙底最暗处仅 4.04:1，不达 AA，故换 ink
    val alertText = if (dark) UiPalette.warn(dark) else UiPalette.ink(dark)
    Card(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = UiPalette.warn(dark).copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("红果未注入 · 待勾选作用域",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = alertText)
            Text("本机未检测到红果注入，开关可先开但暂不生效；请在 LSPosed 勾选红果谷歌版作用域后重启。",
                style = MaterialTheme.typography.bodySmall,
                color = alertText, modifier = Modifier.padding(top = 6.dp))
            if (dark) {
                TextButton(onClick = onSelfCheck) {
                    Text("作用域自检", style = MaterialTheme.typography.labelMedium,
                        color = UiPalette.accent(dark))
                }
            } else {
                // 浅色：Filled Button（accent 底 + 白字），UI §3.3 对比度修正
                Button(
                    onClick = onSelfCheck,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UiPalette.accent(false),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("作用域自检", style = MaterialTheme.typography.labelMedium,
                        color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun GeneralFeaturePage(
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    dark: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scopeText by remember { mutableStateOf<String?>(null) }
    GroupTitle("系统栏与通用")
    OptionRow("状态栏", "顶部图标周期性隐藏", checked = state.enableStatusBar, dark = dark,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableStatusBar = on) } })
    // v3.0.20：状态栏细分为「左侧时间」与「右侧图标」两个独立开关。
    OptionRow("隐藏时间", "左侧时钟；关掉就只清右侧图标", checked = state.statusClockOn, dark = dark,
        enabled = state.enableStatusBar,
        onCheckedChange = { on -> onStateChange { s -> s.copy(statusClockOn = on) } })
    OptionRow("隐藏右侧图标", "信号 / 网络 / 电量 / 网速", checked = state.statusIconsOn, dark = dark,
        enabled = state.enableStatusBar,
        onCheckedChange = { on -> onStateChange { s -> s.copy(statusIconsOn = on) } })
    OptionRow("导航栏", "底部系统导航栏", checked = state.enableNav, dark = dark,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableNav = on) } })
    OptionRow("诊断日志", "写 diag.log 排查，出问题请先打开它", checked = state.enableLog, dark = dark,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableLog = on) } })
    OptionRow("悬浮球", "点击切换隐藏控件", checked = state.enableBall, dark = dark,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableBall = on) } })
    // v3.0.16：无障碍模式的应用内遮盖开关。仅 overlay 引擎生效，LSPosed 引擎不铺黑。
    OptionRow("无障碍应用内遮盖", "免 root：周期遮盖抖音/红果/快手/B站固定区域（会短暂出现黑块）",
        checked = state.enableAppOverlay, dark = dark,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableAppOverlay = on) } })
    HintText("App 内固定控件按周期真隐藏：藏 ${state.hideSec} 秒（像素不发光）→ 显示 ${state.showSec} 秒。隐藏的是叠加在画面上的按钮/栏，不影响视频与弹幕本身。")
    HintText("隐藏期间这些控件不显示也不响应点击；需要操作时点悬浮球或等显示窗口。只在模块注入对应 App 进程时生效，首页「注入状态」可直接看有没有注入。")
    HintText("各 App 的区域开关决定哪几块参与隐藏；四个都关时按「右侧按钮列 + 顶部标题 + 底部导航」三区执行。")
    // v2.8.5：作用域自检入口。用户反馈「改什么都没反应」时，绝大多数情况是
    // LSPosed 没给视频 App 勾作用域 —— 模块代码根本没进那个进程，
    // 真隐藏 / 位置微移都不存在。以前只能靠导日志看 LSPDB 行猜，
    // 现在点一下就把「哪几个 App 没勾」直接列出来，并给一个跳转管理器的按钮。
    ListRow("作用域自检", "查 LSPosed 是否给抖音 / 红果 / 快手 / B站 勾了作用域", {
        scopeText = "正在读取 LSPosed 配置…"
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { LspScope.diagnose(context) }
                    .getOrElse { "读取失败：" + (it.message ?: it.javaClass.simpleName) }
            }
            scopeText = text
            withContext(Dispatchers.IO) { ConfStore.logLine(LspScope.ensure(context)) }
        }
    }, dark, trailing = "›")
    scopeText?.let { text ->
        ScopeDialog(
            text = text,
            dark = dark,
            onOpenLsposed = { openLsposedManager(context) },
            onDismiss = { scopeText = null },
        )
    }
}

@Composable
private fun DouyinFeaturePage(
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    dark: Boolean,
) {
    val overlay = state.isOverlay
    if (overlay) OverlayNotice(dark, "抖音") // 红线：能力边界警示常显，不折叠
    val context = LocalContext.current
    // 摘要只读 state / prefs，不写配置：9 项口径 = 一级页 6 项 + 精准 2 + 定向 1
    val douyinOpen = listOf(
        state.enableDouyinTop, state.enableDouyinBottom, state.enableDouyinLike,
        state.enableDouyinRt, state.enableDouyinLt, state.enableLive,
        state.enableBottomCaption, state.enableRedDot, state.enableDyLiveHits,
    ).count { it }
    FeatureSummaryBar(open = douyinOpen, total = 9,
        injected = recentlyInjected(context, "douyin"), dark = dark)

    // 核心区域组：默认展开、首屏可见（红线：核心区域组不折叠）
    GroupTitle("视频页区域")
    OptionRow("顶部导航", "直播/团购/关注/商城", checked = state.enableDouyinTop, dark = dark,
        enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDouyinTop = on) } })
    OptionRow("底部导航", "首页/朋友/消息/我", checked = state.enableDouyinBottom, dark = dark,
        enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDouyinBottom = on) } })
    OptionRow("右侧按钮列", "点赞/评论/收藏/分享", checked = state.enableDouyinLike, dark = dark,
        enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDouyinLike = on) } })
    OptionRow("右上角", "搜索/分享/更多", checked = state.enableDouyinRt, dark = dark,
        enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDouyinRt = on) } })
    OptionRow("左上角", "三横线菜单按钮", checked = state.enableDouyinLt, dark = dark,
        enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDouyinLt = on) } })
    GroupTitle("直播间隐藏")
    OptionRow("直播间隐藏", "直播页顶栏 / 底部按钮 / 右侧礼物列", checked = state.enableLive,
        dark = dark, enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableLive = on) } })
    // 直播间差异化说明保留在核心组内（design-ui §2.4：不并入通用说明）
    HintText("直播间单独一套：只藏顶部主播条、底部非输入按钮、右侧礼物列；输入框、弹幕、播放画面一律不碰。判定要求「底部有说点什么输入条 且 没有首页 Tab 栏」，普通视频页不会误入。")
    HintText("关掉「直播间隐藏」直播间即刻全部恢复。日志里 lin=1 表示识别到输入条、lhid>0 表示确实藏到了控件。")

    // 追加能力组：默认折叠，去版本号，带「已开 N 项」角标（design-ui §2.2.1）
    CollapsibleGroup("精准隐藏（标题栏 / 红点）",
        openCount = listOf(state.enableBottomCaption, state.enableRedDot).count { it }) {
        OptionRow("下方标题栏", "视频描述行，@作者不动", checked = state.enableBottomCaption, dark = dark,
            enabled = !overlay,
            onCheckedChange = { on -> onStateChange { s -> s.copy(enableBottomCaption = on) } })
        OptionRow("红点", "角标，点赞数/进度条绝不动", checked = state.enableRedDot, dark = dark,
            enabled = !overlay,
            onCheckedChange = { on -> onStateChange { s -> s.copy(enableRedDot = on) } })
    }
    CollapsibleGroup("定向隐藏（圈选内容）",
        openCount = if (state.enableDyLiveHits) 1 else 0) {
        OptionRow("直播间圈选内容", "直播间内只藏圈选静态元素，输入条 / 礼物 / 画面不动",
            checked = state.enableDyLiveHits, dark = dark, enabled = !overlay,
            onCheckedChange = { on -> onStateChange { s -> s.copy(enableDyLiveHits = on) } })
    }
    // 重复说明收敛为单一真源（默认折叠）；原 6 条说明未删，归入 MechanismNote 4 条
    MechanismNote("抖音", state)
    PendingProbeHint() // 原本就默认折叠、无开关，保持
}

@Composable
private fun DramaFeaturePage(
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    dark: Boolean,
) {
    val overlay = state.isOverlay
    if (overlay) OverlayNotice(dark, "红果短剧")

    // v3.0.7：复用首页 InjectStatusCard 的单一真源与 24h 口径检测注入痕迹
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var scopeText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // 与首页同款 15s 轮询：切去 LSPosed 勾选再回来自动刷新，无需重进设置页
        while (true) {
            delay(STATUS_REFRESH_MS)
            now = System.currentTimeMillis()
        }
    }
    val injectTs = ConfigProvider.lastInject(context, "drama")
    val dramaFresh = injectTs > 0L && now - injectTs < 24L * 3600_000L
    if (!overlay && !dramaFresh) {
        ScopePendingNotice(dark) {
            scopeText = "正在读取 LSPosed 配置…"
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { LspScope.diagnose(context) }
                        .getOrElse { "读取失败：" + (it.message ?: it.javaClass.simpleName) }
                }
                scopeText = text
                withContext(Dispatchers.IO) { ConfStore.logLine(LspScope.ensure(context)) }
            }
        }
    }

    GroupTitle("红果短剧 · 视频页区域")
    OptionRow("顶部区域", "视频页顶部栏", checked = state.enableDramaTop, dark = dark,
        enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDramaTop = on) } })
    OptionRow("右侧按钮", "点赞/收藏等按钮列", checked = state.enableDramaLike, dark = dark,
        enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDramaLike = on) } })
    OptionRow("右上角", "右上角操作按钮", checked = state.enableDramaRt, dark = dark,
        enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDramaRt = on) } })
    OptionRow("左上角", "左上角菜单按钮", checked = state.enableDramaLt, dark = dark,
        enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDramaLt = on) } })
    OptionRow("左滑查看详情", "右侧竖排引导文字，按文案识别", checked = state.enableDramaSwipe,
        dark = dark, enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDramaSwipe = on) } })
    OptionRow("底部入口", "剧集/选集入口，模块刻意不隐藏", checked = false, dark = dark,
        enabled = false, disabledLabel = "始终常驻", onCheckedChange = {})
    HintText("红果底部是「完整观看 / 选集」等功能入口，模块刻意不隐藏，避免点不进去。")
    HintText("「左滑查看详情」按文案识别引导条，同样按周期真隐藏。以上都需要模块注入红果进程，日志里出现 INJECTED Drama 即已生效；谷歌版（.oversea.gp）包名已被识别，若日志里连 INJECTED Drama 都没有，就是 LSPosed 没加载进它的进程。")
    // v3.0.6：红果本次无前台样本，不写专属识别规则，两项占位置灰
    GroupTitle("红果 · v3.0.6 精准隐藏（待数据）")
    OptionRow("下方标题栏（待数据）", "本次无红果样本，规则未启用", checked = false, dark = dark,
        enabled = false, disabledLabel = "待数据", onCheckedChange = {})
    OptionRow("红点（待数据）", "本次无红果样本，规则未启用", checked = false, dark = dark,
        enabled = false, disabledLabel = "待数据", onCheckedChange = {})
    // v3.0.7：红果信息区 + 底部导航定向隐藏（未注入也不禁用，杜绝假开关）
    GroupTitle("红果 · v3.0.7 定向隐藏")
    OptionRow("信息区与底部导航", "藏剧集信息区与底部导航栏，视频画面与选集入口不动",
        checked = state.enableDramaInfoHits, dark = dark, enabled = !overlay,
        subtitleWarn = if (!dramaFresh) " · 待勾选作用域（勾选后自动生效）" else "",
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableDramaInfoHits = on) } })
    HintText("「定向隐藏」是真隐藏：把圈定的控件透明度设为 0 并标记为不可见（alpha=0 + INVISIBLE），相当于从画面里拿掉——像素不发光、也不响应点击；不是在上面盖一层黑块。视频画面、输入条与播放本身照常工作，退出对应页面后控件立即恢复。")
    HintText("点赞、评论、分享、进度等纯数字互动计数一律不进隐藏名单，红点走独立白名单；宁可少藏，绝不误藏。识别不到时功能会静默跳过，不会误伤画面或让你点不到按钮。")
    HintText("这里的「底部导航」只指 首页 / 剧场 / 我的 三 Tab 栏；观看完整漫剧 / 选集 入口保持常驻，不受影响。")
    scopeText?.let { text ->
        ScopeDialog(
            text = text,
            dark = dark,
            onOpenLsposed = { openLsposedManager(context) },
            onDismiss = { scopeText = null },
        )
    }
}

/**
 * 快手 / 哔哩哔哩共用子页：各一套独立开关，互不影响。
 * 免 root 遮盖引擎下整页置灰，不放假开关。
 */
@Composable
private fun VideoAppSubPage(
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    dark: Boolean,
    appLabel: String,
    isKuaishou: Boolean,
) {
    val overlay = state.isOverlay
    if (overlay) OverlayNotice(dark, appLabel)

    if (!isKuaishou) {
        // v2.9.2：B站只保留右侧点赞区。
        // 顶部 / 底部 / 角落三项在 B站 8.9x 上识别不到稳定容器，真机打开也没有效果，
        // 按用户要求把入口一起删掉 —— 与其留三个按了没反应的开关，不如只留一个真的。
        val on = state.enableBilibili && state.enableBiliRight
        GroupTitle("B站 · 视频页区域")
        OptionRow("右侧点赞区", "点赞/评论/收藏/投币/分享", checked = on, dark = dark,
            enabled = !overlay,
            onCheckedChange = { v -> onStateChange { s ->
                s.copy(enableBilibili = v, enableBiliRight = v)
            } })
        HintText("打开后按周期真隐藏（藏 ${state.hideSec}s / 显示 ${state.showSec}s），右侧竖排按钮列像素不发光；关掉立即恢复。")
        HintText("需要模块注入 B站进程才生效。首页「注入状态」显示「已注入」即正常；「未检测到」就是 LSPosed 没勾它（或勾了没重启），点「作用域自检」按提示补齐。")
        // v3.0.6：B站下方标题栏（只藏视频标题，UP主名和创作声明不动）
        OptionRow("下方标题栏", "视频标题，UP主名和创作声明不动", checked = state.enableBottomCaption,
            dark = dark, enabled = !overlay,
            onCheckedChange = { v -> onStateChange { s -> s.copy(enableBottomCaption = v) } })
        return
    }

    GroupTitle("$appLabel · 视频页区域")
    val master = state.enableKuaishou
    val right = state.enableKsRight
    val top = state.enableKsTop
    val bottom = state.enableKsBottom
    val corner = state.enableKsCorner
    OptionRow("$appLabel 总开关", "关则本应用所有区域都不参与隐藏", checked = master, dark = dark,
        enabled = !overlay,
        onCheckedChange = { v -> onStateChange { s -> s.copy(enableKuaishou = v) } })
    OptionRow("右侧互动列", "点赞/评论/收藏/分享", checked = right, dark = dark,
        enabled = !overlay,
        onCheckedChange = { v -> onStateChange { s -> s.copy(enableKsRight = v) } })
    OptionRow("顶部工具栏", "返回/搜索/更多/台标", checked = top, dark = dark,
        enabled = !overlay,
        onCheckedChange = { v -> onStateChange { s -> s.copy(enableKsTop = v) } })
    OptionRow("底部操作栏", "标签栏/进度条/评论入口", checked = bottom, dark = dark,
        enabled = !overlay,
        onCheckedChange = { v -> onStateChange { s -> s.copy(enableKsBottom = v) } })
    OptionRow("角落按钮", "右上/左上角常驻按钮", checked = corner, dark = dark,
        enabled = !overlay,
        onCheckedChange = { v -> onStateChange { s -> s.copy(enableKsCorner = v) } })
    // v3.0.6：快手精准隐藏三开关（与 v2.6.9 四区域独立，旧键/索引一律不动）
    GroupTitle("$appLabel · v3.0.6 精准隐藏")
    OptionRow("下方标题栏", "视频正文文案，@作者和声明不动", checked = state.enableBottomCaption,
        dark = dark, enabled = !overlay,
        onCheckedChange = { v -> onStateChange { s -> s.copy(enableBottomCaption = v) } })
    OptionRow("右侧点赞栏", "点赞·评论·收藏·分享，头像关注不动", checked = state.enableRightActions,
        dark = dark, enabled = !overlay,
        onCheckedChange = { v -> onStateChange { s -> s.copy(enableRightActions = v) } })
    OptionRow("红点", "未读角标，互动数字绝不动", checked = state.enableRedDot,
        dark = dark, enabled = !overlay,
        onCheckedChange = { v -> onStateChange { s -> s.copy(enableRedDot = v) } })
    HintText("打开的区域按周期真隐藏（藏 ${state.hideSec}s / 显示 ${state.showSec}s）。快手只有这一套开关，与抖音、B站互不影响。")
    HintText("需要模块注入该进程才生效。首页「注入状态」显示「已注入」即正常；「未检测到」就是 LSPosed 没勾这个 App（或勾了没重启），点「作用域自检」按提示补齐。")
    // v3.0.7：快手直播间 / 刷视频两行；必须在 !isKuaishou 的 return 之后，B站页零变化
    GroupTitle("$appLabel · v3.0.7 定向隐藏")
    OptionRow("直播间圈选内容", "直播间内只藏圈选静态元素，输入条 / 礼物 / 画面不动",
        checked = state.enableKsLiveHits, dark = dark, enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableKsLiveHits = on) } })
    OptionRow("刷视频圈选内容", "红点走原开关；本项只藏全屏观看 / AI 提示等静态元素，互动数字绝不藏",
        checked = state.enableKsFeedHits, dark = dark, enabled = !overlay,
        onCheckedChange = { on -> onStateChange { s -> s.copy(enableKsFeedHits = on) } })
    HintText("「定向隐藏」是真隐藏：把圈定的控件透明度设为 0 并标记为不可见（alpha=0 + INVISIBLE），相当于从画面里拿掉——像素不发光、也不响应点击；不是在上面盖一层黑块。视频画面、输入条与播放本身照常工作，退出对应页面后控件立即恢复。")
    HintText("点赞、评论、分享、进度等纯数字互动计数一律不进隐藏名单，红点走独立白名单；宁可少藏，绝不误藏。识别不到时功能会静默跳过，不会误伤画面或让你点不到按钮。")
    // v3.0.8 P3：必须在 !isKuaishou 的 return 之后，B站页零变化
    PendingProbeHint()
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
}

@Composable
private fun GroupTitle(t: String) {
    val dark = isSystemInDarkTheme()
    Text(t, style = MaterialTheme.typography.titleSmall,
        color = UiPalette.ink(dark), fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

// ===== v3.0.11 P6：UI 简洁化三件套（design-ui §2）——纯视觉收纳，不删任何开关 =====
// 注入痕迹是否新鲜（24h 口径，与首页 InjectStatusCard 单一真源一致）。只读 prefs，不写配置。
private fun recentlyInjected(context: Context, kind: String): Boolean {
    val ts = ConfigProvider.lastInject(context, kind)
    return ts > 0L && System.currentTimeMillis() - ts < 24L * 3600_000L
}

/**
 * 通用折叠组（design-ui §2.7 的无动画降级版）：折叠态用 remember（瞬态、不持久化、
 * 不进 AbState/ConfStore/越界哈希）。核心区域组不调用本组件，只有「精准/定向隐藏」
 * 与说明区使用；openCount 非空时显示「已开 N 项」角标，箭头为文本而非颜色单独承载。
 */
@Composable
private fun CollapsibleGroup(
    title: String,
    openCount: Int? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val dark = isSystemInDarkTheme()
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp) // WCAG 2.2：折叠头点击区域 ≥ 48dp
                .clickable(onClickLabel = if (expanded) "收起 $title" else "展开 $title") {
                    expanded = !expanded
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = UiPalette.ink(dark), modifier = Modifier.weight(1f))
            if (openCount != null) {
                Text("已开 $openCount 项", style = MaterialTheme.typography.labelSmall,
                    color = UiPalette.accent(dark), modifier = Modifier.padding(end = 6.dp))
            }
            // 箭头用文本」▾/▸」作载体，灰度/色弱下也能辨识折叠态（颜色非唯一载体）
            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium,
                color = UiPalette.accent(dark))
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                content() // 原有 OptionRow / HintText 原样迁入，参数与回调一字不改
            }
        }
        // 组间用留白，不画分隔线；组内分隔线仍由 OptionRow 负责
    }
}

/** 二级页顶部状态摘要栏（design-ui §2.3）：只读 state，不写状态、不新增配置项。 */
@Composable
private fun FeatureSummaryBar(
    open: Int,
    total: Int,
    injected: Boolean,
    dark: Boolean,
    warnText: String? = null,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(UiPalette.surface(dark).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 数字 + 文字双载体，符合 WCAG「颜色非唯一信息载体」
            Text("已开 $open / $total 项", style = MaterialTheme.typography.titleSmall,
                color = if (open > 0) UiPalette.accent(dark) else UiPalette.ink(dark))
            Spacer(Modifier.weight(1f))
            Text(if (injected) "已注入" else "未检测到",
                style = MaterialTheme.typography.labelMedium,
                color = if (injected) UiPalette.accent(dark) else UiPalette.warn(dark))
        }
        if (warnText != null) {
            Text(warnText, style = MaterialTheme.typography.bodySmall,
                color = UiPalette.warn(dark), modifier = Modifier.padding(top = 4.dp))
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** 原理说明单一真源（design-ui §2.4）：全 App 只此一处承载重复的 4 条说明，默认折叠。 */
@Composable
private fun MechanismNote(appLabel: String, state: AbState) {
    CollapsibleGroup(title = "这些开关怎么工作", initiallyExpanded = false) {
        HintText("打开的区域按周期真隐藏：藏 ${state.hideSec}s → 显示 ${state.showSec}s，循环往复。")
        HintText("「真隐藏」= 控件 alpha=0 且 INVISIBLE（像素不发光、不响应点击），不是盖黑块；视频画面、输入条与播放本身照常工作。")
        HintText("点赞、评论、分享、进度等纯数字互动计数一律不进隐藏名单，红点走独立白名单；宁可少藏，绝不误藏。")
        HintText("需要模块注入 $appLabel 进程才生效；首页「注入状态」显示「已注入」即正常，显示「未检测到」请在「通用」做一次作用域自检。")
    }
}

// ---- v3.0.8 P3：动态元素"待探测"轻提示（默认折叠、无开关、低优先级；B站页不调用） ----
@Composable
private fun PendingProbeHint() {
    var expanded by remember { mutableStateOf(false) }
    GroupTitle("动态元素 · 待探测")
    val detail = "这两类元素本版没有隐藏规则：日志里还没有它们的真实结构，按项目约束不能靠猜去藏。" +
        "想让下一版适配的话，请在直播间复现一次——把评论区展开、点一下转发小心心——" +
        "然后用首页「日志」按钮导出并回传。只读探针只记录结构信息（class / id / 位置 / 大小），" +
        "不含账号或聊天内容。这条提示不提供开关，也不影响现有功能。"
    val content = if (expanded) {
        AnnotatedString(detail)
    } else {
        buildAnnotatedString {
            append("评论区 / 转发小心心 · 待探测")
            withStyle(SpanStyle(color = UiPalette.accent(isSystemInDarkTheme()))) {
                append("（点开看怎么帮）")
            }
        }
    }
    Text(
        text = content,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(top = 6.dp, bottom = 4.dp),
    )
}

// ---- 通用单行列表项（不是卡片，护眼、层级清晰） ----

/**
 * 单行列表项：标题 + 副标题 + 右侧箭头，整行可点击。
 * 关于页与首页入口统一用它，去掉了大卡片堆叠，深浅色下都清爽。
 */
@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    dark: Boolean,
    trailing: String = "›",
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
            Text(trailing, style = MaterialTheme.typography.titleLarge,
                color = UiPalette.accent(dark))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp)
        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)))
}

// ---- 关于 ----

/**
 * 感谢名单：只记昵称与贡献，金额一律不入库、不展示。
 * 加人只改这张表的一行，二级菜单会自动多出一行。
 */
private data class Thanks(val name: String, val badge: String, val contribution: String)

private val THANKS = listOf(
    Thanks("辉哥", "辉", "资金支持"),
    Thanks("酷安@专属的小哈基米", "哈", "提供悬浮球思路"),
    Thanks(".哥", "哥", "资金支持"),
    Thanks("酷安@酷安总作践自己", "酷", "资金支持"),
    // v2.7.4 新增
    Thanks("酷安", "酷", "社区支持与反馈"),
    Thanks("心之所及", "心", "支持与建议"),
    // v2.9.4 新增：帮忙在 ColorOS 15 环境做真机测试
    Thanks("酷安@bzm123123", "B", "帮忙测试 ColorOS 15 环境"),
)

/** 项目仓库地址（GitHub）——关于页「项目地址」点击跳转。 */
private const val PROJECT_URL = "https://github.com/Xposed-Modules-Repo/io.github.wumisc.antiburn"

/** 功能建议：GitHub 新建 Issue 页。按钮会追加标题与正文模板参数。 */
private const val PROJECT_ISSUES_URL =
    "https://github.com/Xposed-Modules-Repo/io.github.wumisc.antiburn/issues/new"

/**
 * 致敬名单：本模块借鉴过的开源项目。只放确有依赖/参考关系的仓库，
 * 链接经可用性校验，点击用系统浏览器打开。加项目只改这张表一行。
 */
private data class Credit(val name: String, val desc: String, val url: String)

private val CREDITS = listOf(
    Credit("AndroidLiquidGlass · Kyant0", "液态玻璃渲染：backdrop / lens / vibrancy（Apache-2.0）",
        "https://github.com/Kyant0/AndroidLiquidGlass"),
    Credit("Miuix · YuKongA", "液态玻璃导航栏、blur 与 squircle 组件",
        "https://github.com/YuKongA/Miuix"),
    Credit("LSPosed", "Hook 框架与模块运行环境",
        "https://github.com/LSPosed/LSPosed"),
    Credit("XposedBridge · rovo89", "Xposed API（编译期依赖）",
        "https://github.com/rovo89/XposedBridge"),
    Credit("Jetpack Compose · AndroidX", "声明式 UI 工具包与 Material 3",
        "https://developer.android.com/jetpack/compose"),
    Credit("kotlinx.coroutines · Kotlin", "协程与异步调度",
        "https://github.com/Kotlin/kotlinx.coroutines"),
)

@Composable
private fun AboutPage(onOpen: (String) -> Unit, onOpenUrl: (String) -> Unit) {
    val dark = isSystemInDarkTheme()
    // v3.0.12 P3：两个可见性开关及其 remember 状态已迁入 HomeTilePage，此处仅保留关于信息。
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 96.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom) {
            Text("关于", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = UiPalette.ink(dark))
            Column(horizontalAlignment = Alignment.End) {
                Text("为往圣继绝学", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                Text("为万世开太平", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
        Text("护眼防烧屏 · v" + BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.titleMedium,
            color = UiPalette.accent(dark), fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp))
        // v2.7.0：核心价值观 —— 护眼防烧屏是项目第一目标
        Text("护眼防烧屏是本项目的第一目标：让屏幕上的静止元素周期歇息，延长屏幕寿命，也保护眼睛。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))

        ListRow("感谢名单", "${THANKS.size} 位朋友的支持与建议", { onOpen("thanks") }, dark)
        ListRow("支持捐赠", "支付宝 / 微信 · 金额随意，心意最重", { onOpen("donate") }, dark)
        ListRow("致敬名单", "${CREDITS.size} 个借鉴过的开源项目 · 点击查看", { onOpen("credit") }, dark)
        ListRow("功能建议", "想加功能、适配新机型？来 GitHub 提一个", { onOpen("suggest") }, dark)
        // v2.7：项目地址单独一行，点击直接跳 GitHub；不进二级菜单，减少一层跳转
        ListRow("项目地址", "github.com/Xposed-Modules-Repo/…", { onOpenUrl(PROJECT_URL) }, dark,
            trailing = "↗")

        // v2.6.5：原文案把运行方式写死成单一环境，免 root 用户在关于页会以为
        // 自己根本用不了这个软件。改成把两种运行方式都讲清楚，并且不夸大能力。
        Text("有 root 与 LSPosed 框架用 Hook 引擎（真隐藏）；没有 root 用无障碍遮盖引擎（盖黑系统栏、触摸穿透）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            textAlign = TextAlign.Center)
        Text("本应用仅供学习交流使用 · 支持 LSPosed / 免 root 无障碍两种运行方式",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            textAlign = TextAlign.Center)
    }
}

// ---- 二级菜单容器 ----
@Composable
private fun SubPageHost(
    page: String,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    saveBar: @Composable () -> Unit,
    // v3.0.12 P2：三个动作回调原挂在 HomeTilePage 调用点，随卡片下沉改挂此处，逻辑零改。
    onEnableAll: () -> Unit,
    onOpenList: () -> Unit,
    onSelfCheck: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val title = when (page) {
        "thanks" -> "感谢名单"
        "donate" -> "支持捐赠"
        "credit" -> "致敬名单"
        "suggest" -> "功能建议"
        PAGE_HOME_ENGINE -> "运行方式"
        PAGE_HOME_INJECT -> "注入状态"
        PAGE_HOME_UISTYLE -> "界面适配"
        PAGE_HOME_LIST -> "白名单"
        "feat-general" -> "通用"
        "feat-douyin" -> "抖音"
        "feat-drama" -> "红果短剧"
        "feat-kuaishou" -> "快手"
        "feat-bilibili" -> "哔哩哔哩"
        else -> "设置"
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp),
    ) {
        // 顶部返回条
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Text("‹", style = MaterialTheme.typography.headlineLarge,
                    color = UiPalette.accent(dark))
            }
            Text(title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            // v3.0.12：功能页与首页四入口二级页都经 onStateChange 即时保存，顶部给状态反馈；
            // 关于类页面不需要。home-* 加入后，改动即时保存提示正常（G7），既有 key 零回归（G8）。
            if (page.startsWith("feat-") || page.startsWith("home-")) saveBar()
            when (page) {
                "thanks" -> ThanksPage(dark)
                "donate" -> DonatePage(dark)
                "credit" -> CreditPage(dark, onOpenUrl)
                "suggest" -> SuggestPage(dark, onOpenUrl)
                PAGE_HOME_ENGINE -> EnginePage(state = state, onStateChange = onStateChange, dark = dark)
                PAGE_HOME_INJECT -> InjectStatusPage(dark = dark, onEnableAll = onEnableAll)
                PAGE_HOME_UISTYLE -> UiStylePage(
                    uiStyle = state.uiStyle,
                    onUiStyleChange = { id -> onStateChange { s -> s.copy(uiStyle = id) } },
                    dark = dark,
                )
                PAGE_HOME_LIST -> WhitelistPage(
                    listMode = state.listMode,
                    activeList = state.activeList,
                    onListModeChange = { m -> onStateChange { s -> s.copy(listMode = m) } },
                    onListTextChange = { txt ->
                        onStateChange { s ->
                            if (s.listMode == ConfStore.MODE_BLACK) s.copy(blackList = txt)
                            else s.copy(whiteList = txt)
                        }
                    },
                    onOpenList = onOpenList,
                    onSelfCheck = onSelfCheck,
                    dark = dark,
                )
                "feat-general" -> GeneralFeaturePage(state, onStateChange, dark)
                "feat-douyin" -> DouyinFeaturePage(state, onStateChange, dark)
                "feat-drama" -> DramaFeaturePage(state, onStateChange, dark)
                "feat-kuaishou" -> VideoAppSubPage(state, onStateChange, dark, "快手",
                    isKuaishou = true)
                "feat-bilibili" -> VideoAppSubPage(state, onStateChange, dark, "哔哩哔哩",
                    isKuaishou = false)
                else -> Text("页面不存在，请返回上一页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 功能建议二级页：讲清楚怎么提、提什么，再给一个直达 GitHub 新建 Issue 的按钮。 */
@Composable
private fun SuggestPage(dark: Boolean, onOpenUrl: (String) -> Unit) {
    Text("想加新功能、适配新机型，或者觉得哪里不好用，都欢迎提。建议写得越具体，越容易落地。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    GroupTitle("提之前可以先看看")
    listOf(
        "适配新机型" to "写清机型、系统版本与 ROM（如 ColorOS 15、Flyme 11），附一张状态栏截图最好",
        "新增功能" to "说清想解决什么问题、期望怎么开关，比只写「加个 XX」更容易实现",
        "问题反馈" to "附首页「日志」按钮导出的诊断日志与复现步骤，能省掉大量来回确认",
        "能力边界" to "LSPosed 真隐藏必须由模块注入目标 App 进程，前提是在 LSPosed 里给本模块勾选那个 App；没勾时该 App 内不会有任何效果，请先在「通用」里做一次作用域自检",
    ).forEach { (itemTitle, desc) ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp)) {
            Text(itemTitle, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
            Text(desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)))
    }
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = {
            val url = Uri.parse(PROJECT_ISSUES_URL).buildUpon()
                .appendQueryParameter("title", "[功能建议] ")
                .appendQueryParameter(
                    "body",
                    "设备机型：\n系统版本：\n模块版本：" + BuildConfig.VERSION_NAME
                            + "\n\n想要的功能：\n为什么需要：\n",
                )
                .build().toString()
            onOpenUrl(url)
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text("前往 GitHub 提交建议 ↗", fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(10.dp))
    Text("按钮会打开发布页新建 Issue，标题已带「功能建议」标签，正文附了填写模板。没有 GitHub 账号，也可以在酷安同名帖下留言。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
}

/** 感谢名单二级页：单行列表，不用卡片。 */
@Composable
private fun ThanksPage(dark: Boolean) {
    Text("感谢每一位支持与帮助过模块的朋友。名单只展示昵称与贡献，金额不予展示。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    THANKS.forEach { t ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                    .background(UiPalette.accent(dark)),
                contentAlignment = Alignment.Center,
            ) {
                Text(t.badge, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
                Text(t.contribution, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)))
    }
    Text("名单按加入顺序排列 · 谢谢你们",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        textAlign = TextAlign.Center)
}

/** 支持捐赠二级页：支付宝 + 微信二维码，白底保证可扫。 */
@Composable
private fun DonatePage(dark: Boolean) {
    Text("如果模块帮到了你，欢迎扫码支持。金额随意，心意最重；名单里不会展示金额。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    QrCard("支付宝", R.drawable.qr_alipay, dark)
    Spacer(Modifier.height(16.dp))
    QrCard("微信支付", R.drawable.qr_wechat, dark)
    Text("保存图片后可用对应 App「扫一扫」识别；长按二维码图片也可保存。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        textAlign = TextAlign.Center)
}

@Composable
private fun QrCard(label: String, resId: Int, dark: Boolean) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
        Spacer(Modifier.height(10.dp))
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            // 二维码必须白底才能可靠扫码，这里不随主题变色
            Image(
                painter = painterResource(resId),
                contentDescription = label + "收款二维码",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}

/**
 * 致敬名单二级页：把借鉴过的开源项目以可点击超链接一一列出。
 * 名称用主色 + 下划线 + ↗ 明确「可点」，点击用系统浏览器打开仓库；
 * 深色模式下主色是浅雾青，不会出现黑字看不清。
 */
@Composable
private fun CreditPage(dark: Boolean, onOpenUrl: (String) -> Unit) {
    Text("本模块站在这些开源项目的肩膀上。点击名称可跳转对应仓库，感谢每一位作者。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    CREDITS.forEach { c ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { onOpenUrl(c.url) }
                .padding(horizontal = 4.dp, vertical = 13.dp),
        ) {
            Text(
                c.name + "  ↗",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = UiPalette.accent(dark),
                textDecoration = TextDecoration.Underline,
            )
            Text(c.desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp))
            Text(c.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)))
    }
    Text("按借鉴顺序排列 · 开源让工具更好",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        textAlign = TextAlign.Center)
}

/**
 * 短视频应用适配说明卡（v2.6.5 计划说明 → v2.6.8 已上线）。
 *
 * <p>早期版本这里只列适配计划、尚无开关入口。v2.6.8 起快手 / 哔哩哔哩
 * 已在功能页开通真开关，本卡改为能力边界与作用域勾选提示，不再误导用户。</p>
 */
@Composable
private fun RoadmapCard(dark: Boolean) {
    Spacer(Modifier.height(4.dp))
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = UiPalette.surface(dark, 0.55f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("短视频应用适配", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
            Text("快手、哔哩哔哩已支持：在功能页点开「快手」「B站」逐项开启对应开关。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
            Text("LSPosed 真隐藏需要模块注入目标进程。本版只做位置偏移，不再用覆盖层铺黑：注入没通时不会画任何东西，日志里可看 INJECTED 与作用域自检结果。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp))
            Text("直播页与白名单应用自动跳过；只隐藏可确认的固定控件，内容区不误伤。底部「+」随「底部操作栏」开关一起隐藏。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * 拉起 LSPosed 管理器（v2.8.5）。
 *
 * <p>模块只能「读」作用域，改还是得用户自己在管理器里点。少一步「自己去桌面找图标」
 * 就少一步出错的机会，所以这里按几个已知包名依次试；都没有就提示手动打开，
 * 不弹浏览器、不猜市场链接。</p>
 */
private fun openLsposedManager(context: Context) {
    val names = arrayOf(
        "org.lsposed.manager",
        "org.lsposed.manager.canary",
        "org.lsposed.manager.debug",
    )
    for (p in names) {
        try {
            val i = context.packageManager.getLaunchIntentForPackage(p)
            if (i != null) {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        } catch (ignored: Throwable) {
        }
    }
    Toast.makeText(context, "没找到 LSPosed 管理器，请从桌面图标手动打开", Toast.LENGTH_LONG).show()
}

/**
 * 作用域自检结果（v2.8.5）：等宽字体逐行列出「已勾选的包 / 设备上装了的目标 App / 缺哪几个」。
 * 提供复制按钮，方便把结论贴到反馈里。
 */
@Composable
private fun ScopeDialog(
    text: String,
    dark: Boolean,
    onOpenLsposed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clip = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text("LSPosed 作用域自检", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
                Spacer(Modifier.height(10.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clip.setText(AnnotatedString(text)) }) { Text("复制") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenLsposed) { Text("打开 LSPosed") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/**
 * 周期时长小窗（v2.6.5）。
 *
 * <p>以前是首页 ListRow 跳一个二级页：要先回首页、再进二级页、改完再返回。
 * 现在入口放在「功能 → 通用」二级页，点开就在这页弹窗调，改完点保存自动关窗。
 * 内容复用 CyclePage，滑条与保存逻辑只有一份，避免两处漂移。</p>
 */
@Composable
private fun CycleDialog(
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    dark: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("周期时长", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
                Spacer(Modifier.height(14.dp))
                CyclePage(state, onStateChange, dark, onSaved = onDismiss)
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("取消")
                }
            }
        }
    }
}

/** 周期时长表单内容：本地暂存，点保存一次落盘，避免滑条每帧写盘。小窗与旧二级页共用。 */
@Composable
private fun CyclePage(
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    dark: Boolean,
    onSaved: () -> Unit = {},
) {
    var hide by remember(state.hideSec) { mutableIntStateOf(state.hideSec) }
    var show by remember(state.showSec) { mutableIntStateOf(state.showSec) }
    val changed = hide != state.hideSec || show != state.showSec

    Text("一个周期 = 隐藏时长 + 显示时长。系统栏、导航栏与抖音、红果短剧、快手、B站 的固定区域都按同一节奏周期性隐藏（像素直接不发光），如此往复防止固定像素常亮。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    Spacer(Modifier.height(18.dp))
    Text("隐藏时长 · $hide 秒", style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
    Text("建议 20~60 秒；越长省电但恢复越慢",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(8.dp))
    LiquidSlider(
        value = hide.toFloat(),
        onValueChange = { hide = it.roundToInt() },
        valueRange = 10f..120f,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { hide = (hide - 5).coerceAtLeast(5) }) { Text("-5s") }
        TextButton(onClick = { hide = (hide + 5).coerceAtMost(3600) }) { Text("+5s") }
        Spacer(Modifier.weight(1f))
    }

    Spacer(Modifier.height(20.dp))
    Text("显示时长 · $show 秒", style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
    Text("建议 5~15 秒；太短会频繁闪烁",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(8.dp))
    LiquidSlider(
        value = show.toFloat(),
        onValueChange = { show = it.roundToInt() },
        valueRange = 1f..60f,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { show = (show - 1).coerceAtLeast(1) }) { Text("-1s") }
        TextButton(onClick = { show = (show + 1).coerceAtMost(3600) }) { Text("+1s") }
        Spacer(Modifier.weight(1f))
    }

    Spacer(Modifier.height(22.dp))
    Button(
        onClick = {
            onStateChange { s -> s.copy(hideSec = hide, showSec = show) }
            onSaved()
        },
        enabled = changed,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (changed) "保存时长设置" else "当前已是所设时长")
    }
    Text("改动自动保存，最多 2 秒生效；想立刻看到效果可点顶部「重启界面」。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        textAlign = TextAlign.Center)
}

// ---- Tab 图标 ----
private fun navIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
        .build()

private const val PATH_HOME = "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"
private const val PATH_FEATURES = "M3,13h8L11,3 3,3v10zM3,21h8v-6L3,15v6zM13,21h8L21,11h-8v10zM13,3v6h8L21,3h-8z"
private const val PATH_ABOUT = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2L11,7h2v2z"
