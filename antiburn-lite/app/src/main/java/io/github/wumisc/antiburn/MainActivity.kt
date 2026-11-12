package io.github.wumisc.antiburn

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
 * 防止烧屏 · 精简磁贴版 v2.0
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
/** 界面适配可选方案：五大厂商 + 原生（id 与 conf 里的 ui_style 一一对应） */
private val UI_STYLE_LABELS = listOf(
    "hyperos" to "小米 HyperOS",
    "magicos" to "荣耀 MagicOS",
    "coloros" to "OPPO ColorOS",
    "originos" to "vivo OriginOS",
    "harmonyos" to "华为 HarmonyOS",
    "aosp" to "原生 AOSP",
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
    /**
     * 目标 UI 适配方案：hyperos / magicos / coloros / originos / harmonyos / aosp。
     * 决定 Hook 侧用哪套「胶囊保护区 + 导航栏结构」配置，见 profiles/ 与 Config.uiStyle。
     */
    val uiStyle: String = "aosp",
    val enablePoem: Boolean = false,
    val enableBall: Boolean = false,
    val enableLog: Boolean = false,
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
                uiStyle = Str(22, "aosp"),
                listMode = ConfStore.normMode(Str(23)),
                blackList = Str(24),
                // v2.6 新增：旧 Bundle 只有 25 项，getOrNull 会返回 null → 取默认值
                engine = ConfStore.normEngine(Str(25, ConfStore.ENGINE_LSPOSED)),
                // v2.6.4 新增：默认 true，与「总闸开则状态栏隐藏」的历史行为一致
                enableStatusBar = Bool(26, true),
            )
        }
    },
)

/** 读/写 antiburn.conf：统一走 ConfStore（应用内镜像 + root 原子写） */
object AbConf {
    private fun i(v: String?, def: Int): Int = v?.trim()?.toIntOrNull() ?: def
    private fun b(v: String?, def: Boolean = false): Boolean = if (v == null) def else v.trim() == "1"

    /** 已知的 UI 适配方案 id（与 Hook 侧 UiStyle.IDS 同一份真源） */
    val UI_STYLE_IDS: List<String> = UiStyle.IDS

    /** 未知 / 空值一律归到 aosp。归一逻辑与 Config/UiStyle 共用，避免跨进程不一致 */
    fun normUiStyle(v: String?): String = UiStyle.norm(v)

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
            uiStyle = normUiStyle(map["ui_style"]),
            // 诗句功能已下线（Hook 侧实现同步移除）：界面无入口、消费端已删，
            // 这里固定 false，避免老配置里的 enable_poem=1 被读进来再写回去。
            enablePoem = false,
            enableBall = b(map["enable_ball"]),
            enableLog = b(map["enable_log"]),
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
        lines["ui_style"] = normUiStyle(s.uiStyle)
        lines["enable_navigation"] = if (s.enableNav) "1" else "0"
        // 字段恒为 false（load 已固定），因此这里恒写 0：老配置里的 1
        // 在任意一次保存后即被清零，下游也已无消费者。
        lines["enable_poem"] = if (s.enablePoem) "1" else "0"
        lines["enable_ball"] = if (s.enableBall) "1" else "0"
        lines["enable_log"] = if (s.enableLog) "1" else "0"
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
        lines["hide_duration"] = s.hideSec.coerceIn(5, 3600).toString()
        lines["show_duration"] = s.showSec.coerceIn(1, 3600).toString()
        // 多行文本必须编码成单行，否则后面的 key 会被挤成非法行、配置解析错乱
        lines["custom_poems"] = ConfStore.encodeValue(s.customPoems)
        lines["list_mode"] = s.listMode
        lines["whitelist"] = s.whiteList.trim()
        lines["blacklist"] = s.blackList.trim()
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
        if (old.uiStyle != new.uiStyle) m["ui_style"] = normUiStyle(new.uiStyle)
        putB("enable_navigation", old.enableNav, new.enableNav)
        // 诗句字段恒为 false（功能已下线），此行使字段与 conf 的写入路径保持完整，
        // 实际不会产生 diff；删掉会让「字段→落盘」审计失去依据。
        putB("enable_poem", old.enablePoem, new.enablePoem)
        putB("enable_ball", old.enableBall, new.enableBall)
        putB("enable_log", old.enableLog, new.enableLog)
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
        if (old.hideSec != new.hideSec) m["hide_duration"] = new.hideSec.coerceIn(5, 3600).toString()
        if (old.showSec != new.showSec) m["show_duration"] = new.showSec.coerceIn(1, 3600).toString()
        if (old.customPoems != new.customPoems) {
            m["custom_poems"] = ConfStore.encodeValue(new.customPoems)
        }
        if (old.whiteList != new.whiteList) m["whitelist"] = new.whiteList.trim()
        if (old.blackList != new.blackList) m["blacklist"] = new.blackList.trim()
        if (old.listMode != new.listMode) {
            m["list_mode"] = ConfStore.normMode(new.listMode)
            // 切模式必须与两份名单同批落盘：Hook 侧一旦看到 list_mode 就把它当成
            // 完整快照（目标名单缺键即视为空），只写模式键会把当前名单清空。
            m["whitelist"] = new.whiteList.trim()
            m["blacklist"] = new.blackList.trim()
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
    }

    override fun onStop() {
        // 点完开关立刻返回 / 切到桌面时，Compose 的防抖协程会被取消，
        // 改动永远写不进磁盘 —— 这里在页面离开前强制补一次保存。
        flushPending?.invoke()
        super.onStop()
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
    // 功能页内部子页：0=通用 1=抖音 2=红果（抖音/红果各是一整页磁贴）
    var featurePage by rememberSaveable { mutableIntStateOf(0) }
    // 二级菜单栈：null=主界面；thanks/donate/cycle 各是一个独立二级页。
    // 用 saveable 保存，旋转/深浅色切换后不会退回主页。
    var subPage by rememberSaveable { mutableStateOf<String?>(null) }
    val openSub: (String) -> Unit = { subPage = it }
    var navMicroSec by rememberSaveable { mutableIntStateOf(45) }
    // 旋转/深浅色切换后保留未落盘的改动
    var state by rememberSaveable(stateSaver = AbStateSaver) { mutableStateOf(AbState()) }
    var savedState by rememberSaveable(stateSaver = AbStateSaver) { mutableStateOf(AbState()) }
    var status by rememberSaveable { mutableStateOf("正在读取配置…") }
    var rootOk by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    // 尚未落盘的最新状态；Activity onStop 时用它补一次
    var pendingSave by remember { mutableStateOf<AbState?>(null) }
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
        saving = true
        val diff = AbConf.diffOf(savedState, target)
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
                state = disk
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

    // 函数式更新：回调里读到的永远是最新 state，同一重组内连点多个磁贴不会互相覆盖
    val onStateChange: ((AbState) -> AbState) -> Unit = { update ->
        val newState = update(state)
        state = newState
        commit(newState)
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
            AbConf.load() to ConfStore.hasRoot()
        }
        state = pair.first
        savedState = pair.first
        rootOk = pair.second
        status = if (pair.second) "配置已读取 · root 已授权"
        else "配置已读取 · 未检测到 root（仍可保存到应用内配置）"
        loaded = true
        // 加载窗口内用户点过的开关，在这里补写一次，不再被丢弃
        val pending = pendingSave
        if (pending != null && pending != state) {
            commit(pending)
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
                            listMode = state.listMode,
                            activeList = state.activeList,
                            onListModeChange = { m -> onStateChange { s -> s.copy(listMode = m) } },
                            onListTextChange = { txt ->
                                onStateChange { s ->
                                    if (s.listMode == ConfStore.MODE_BLACK) s.copy(blackList = txt)
                                    else s.copy(whiteList = txt)
                                }
                            },
                            uiStyle = state.uiStyle,
                            onUiStyleChange = { id -> onStateChange { s -> s.copy(uiStyle = id) } },
                            onOpenList = { openList(state.listMode) },
                            onSelfCheck = { selfCheckList() },
                            saveBar = saveBar,
                            engineCard = {
                                EngineCard(
                                    state = state,
                                    onStateChange = onStateChange,
                                    dark = dark,
                                )
                            },
                        )
                        1 -> FeaturesPage(
                            state = state,
                            onStateChange = onStateChange,
                            saveBar = saveBar,
                            featurePage = featurePage,
                            onFeaturePageChange = { featurePage = it },
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
                "两条引擎互斥，同一时刻只有一条生效。有 root 走 LSPosed（真隐藏 + 像素微移）；" +
                        "没有 root 走无障碍遮盖（盖黑系统栏，同样让那块像素停止发光）。",
                style = MaterialTheme.typography.bodySmall,
                color = sub,
                modifier = Modifier.padding(top = 4.dp),
            )

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
                        TextButton(
                            onClick = {
                                BurnInMaskService.requestPreview()
                                toast("接下来 5 秒会强制遮盖，看看状态栏有没有变黑")
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("预览 5 秒", style = MaterialTheme.typography.labelMedium)
                        }
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
@Composable
private fun HomeTilePage(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    listMode: String,
    activeList: String,
    onListModeChange: (String) -> Unit,
    onListTextChange: (String) -> Unit,
    uiStyle: String,
    onUiStyleChange: (String) -> Unit,
    onOpenList: () -> Unit,
    onSelfCheck: () -> Unit,
    saveBar: @Composable () -> Unit,
    engineCard: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 96.dp),
    ) {
        Text("防止烧屏", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            color = UiPalette.ink(dark))
        // 不再写死「LSPosed 模块」：应用同时支持免 root 的无障碍遮盖引擎，
        // 无 root 用户看到旧文案会以为这个软件自己用不了。
        Text("支持 LSPosed / 无障碍遮盖双引擎", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        saveBar()

        Spacer(Modifier.height(12.dp))

        // 运行方式放在最前面：无 root 用户开机第一件事就是得知道「我该选哪个」，
        // 埋在功能页里等于没有。
        engineCard()

        Spacer(Modifier.height(12.dp))

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

        // 周期时长已移到「功能 → 通用」磁贴，点磁贴弹小窗调整，首页不再放第二处入口。
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

        Spacer(Modifier.height(12.dp))

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
}

// ---- 功能：磁贴式开关；抖音 / 红果 各自独立成一页 ----

/**
 * 一个磁贴的数据。enabled=false 表示常驻不可切换（如红果底部剧集入口）。
 * navClick 不为空时是「入口磁贴」：点击进二级菜单，不显示开关状态。
 *
 * <p>参数顺序有硬约束：**onToggle 必须是最后一个**。所有调用点都写成
 * <code>TileItem(a, b, c, checked) { toggle() }</code> 的尾随 lambda 形式，
 * Kotlin 把尾随 lambda 绑给「最后一个参数」。v2.9 重排时把 navClick 挪到了末尾，
 * 于是二十多个功能磁贴的尾随 lambda 全被绑成 navClick，onToggle 退化成空实现。</p>
 *
 * <p>后果不是点不动（TileCard 里 nav != null 时也会 invoke），而是
 * <code>isEntry = navClick != null</code> 恒为真 —— 每个磁贴右上角都显示「›」、
 * 永远不显示「已开启 / 已关闭」。用户看不出开关状态，只好反复点，
 * 于是一个本来开着的东西被点成关闭，反馈成「改一下设置它自己就关了」。
 * 真机日志里 shift 从 true 变 false 只隔 2 秒，正是这么来的。</p>
 */
private data class TileItem(
    val title: String,
    val glyph: String,
    val subtitle: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val navClick: (() -> Unit)? = null,
    val onToggle: (Boolean) -> Unit = {},
)

@Composable
private fun FeaturesPage(
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    saveBar: @Composable () -> Unit,
    featurePage: Int,
    onFeaturePageChange: (Int) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    // 周期时长点磁贴弹小窗，不再跳二级页；避免和首页入口两处状态不一致。
    var showCycleDialog by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 96.dp),
    ) {
        Text("功能磁贴", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            color = UiPalette.ink(dark))
        Text("点击磁贴即可开关；带 › 的磁贴会打开设置小窗",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        saveBar()
        Spacer(Modifier.height(10.dp))

        // 子页切换：通用 / 抖音 / 红果，其中抖音与红果是两张独立磁贴页
        Row(Modifier.fillMaxWidth()) {
            listOf("通用", "抖音", "红果").forEachIndexed { index, label ->
                Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    if (index == featurePage) {
                        Button(onClick = { onFeaturePageChange(index) }, modifier = Modifier.fillMaxWidth()) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(onClick = { onFeaturePageChange(index) }, modifier = Modifier.fillMaxWidth()) {
                            Text(label)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        when (featurePage) {
            0 -> {
                GroupTitle("系统栏与通用")
                // v2.7：删除原「状态栏图标」磁贴。它错绑了 enableStatus（总闸），
                // 点一下等于把整个模块关掉，与首页总开关重复且严重误导
                // （日志里 status 反复 0↔1 正来源于此）。状态栏图标隐藏本就是
                // 总闸开启后的固有行为（hideStatusKeepNotify），无需第二个入口。
                //
                // v2.6.4：上面这个结论被真机推翻了。删掉之后状态栏变成「总闸一开必隐藏」，
                // 用户想把状态栏留下、只隐藏导航栏时无路可走，反馈成
                // 「总开关一开，状态栏导航栏百分百一起隐藏」。这次不绑总闸，
                // 绑独立的 enable_status_bar（默认 true，老配置行为不变）。
                TileGrid(
                    listOf(
                        TileItem("状态栏", "栏", "顶部图标周期性隐藏", state.enableStatusBar) {
                            onStateChange { s -> s.copy(enableStatusBar = !s.enableStatusBar) }
                        },
                        TileItem("导航栏", "导", "底部系统导航栏", state.enableNav) {
                            onStateChange { s -> s.copy(enableNav = !s.enableNav) }
                        },
                        // v2.6.5：按需求删除「状态栏偏移」开关。它在真机上反复引发
                        // 「改一下设置就自己关掉」的反馈（v2.9 磁贴参数绑错的那次）。
                        // 像素微移是视觉无感的温和防护，改为内置常开、不再暴露开关：
                        // AbState / Config / Controller 链路原样保留，避免动 Saver 索引
                        // 让老用户升级后 Bundle 恢复错位。字段值在 load/save 固定为开。
                        TileItem("诊断日志", "志", "写 diag.log 排查", state.enableLog) {
                            onStateChange { s -> s.copy(enableLog = !s.enableLog) }
                        },
                        TileItem("悬浮球", "球", "点击切换隐藏控件", state.enableBall) {
                            onStateChange { s -> s.copy(enableBall = !s.enableBall) }
                        },
                        // v2.6.5：周期时长从首页 ListRow 挪到这里。它是全功能页唯一
                        // 有意的「入口磁贴」，用命名参数 navClick 显式标注，点开弹小窗，
                        // 不会像 2.9 那次被尾随 lambda 误绑成一整片的入口。
                        TileItem(
                            "周期时长", "时", "隐藏 ${state.hideSec}s · 显示 ${state.showSec}s",
                            false, navClick = { showCycleDialog = true },
                        ),
                    )
                )

                // v2.6.5：名单卡片只在首页保留一份，此处不再重复。
                // 同一份设置在两个页面各摆一张卡片时，两处入口的展开状态、滚动位置
                // 互不同步，用户会以为是两套名单；且功能页这张的表单会把页面撑长，
                // 压掉下方抖音 / 红果子页的可见性。
                RoadmapCard(dark)
            }
            1 -> {
                GroupTitle("抖音 · 视频页六个区域")
                TileGrid(
                    listOf(
                        TileItem("顶部导航", "顶", "直播/团购/关注/商城", state.enableDouyinTop) {
                            onStateChange { s -> s.copy(enableDouyinTop = !s.enableDouyinTop) }
                        },
                        TileItem("底部导航", "底", "首页/朋友/消息/我", state.enableDouyinBottom) {
                            onStateChange { s -> s.copy(enableDouyinBottom = !s.enableDouyinBottom) }
                        },
                        TileItem("右侧按钮列", "赞", "点赞/评论/收藏/分享", state.enableDouyinLike) {
                            onStateChange { s -> s.copy(enableDouyinLike = !s.enableDouyinLike) }
                        },
                        TileItem("右上角", "右", "搜索/分享/更多", state.enableDouyinRt) {
                            onStateChange { s -> s.copy(enableDouyinRt = !s.enableDouyinRt) }
                        },
                        TileItem("左上角", "左", "三横线菜单按钮", state.enableDouyinLt) {
                            onStateChange { s -> s.copy(enableDouyinLt = !s.enableDouyinLt) }
                        },
                        TileItem("直播间隐藏", "播", "直播页顶栏/底栏/右下", state.enableLive) {
                            onStateChange { s -> s.copy(enableLive = !s.enableLive) }
                        },
                    )
                )
                HintText("打开后，抖音进程内对应区域会按周期隐藏。若直播黑屏，可关闭「直播间隐藏」。")
            }
            2 -> {
                GroupTitle("红果短剧 · 视频页区域")
                TileGrid(
                    listOf(
                        TileItem("顶部区域", "顶", "视频页顶部栏", state.enableDramaTop) {
                            onStateChange { s -> s.copy(enableDramaTop = !s.enableDramaTop) }
                        },
                        TileItem("右侧按钮", "赞", "点赞/收藏等按钮列", state.enableDramaLike) {
                            onStateChange { s -> s.copy(enableDramaLike = !s.enableDramaLike) }
                        },
                        TileItem("右上角", "右", "右上角操作按钮", state.enableDramaRt) {
                            onStateChange { s -> s.copy(enableDramaRt = !s.enableDramaRt) }
                        },
                        TileItem("左上角", "左", "左上角菜单按钮", state.enableDramaLt) {
                            onStateChange { s -> s.copy(enableDramaLt = !s.enableDramaLt) }
                        },
                        TileItem("底部入口", "底", "剧集/选集入口，始终常驻", false, enabled = false) { },
                    )
                )
                HintText("红果底部是「完整观看 / 选集」等功能入口，模块刻意不隐藏，避免点不进去。")
            }
        }
        Spacer(Modifier.height(8.dp))

        // 周期时长：点「通用」里的入口磁贴就地弹小窗，不再跳二级页。
        if (showCycleDialog) {
            CycleDialog(
                state = state,
                onStateChange = onStateChange,
                dark = dark,
                onDismiss = { showCycleDialog = false },
            )
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
}

/** 两列磁贴网格；用普通 Row/Column 而不是 LazyGrid，避免在 verticalScroll 里出现无限高度。 */
@Composable
private fun TileGrid(items: List<TileItem>) {
    Column(Modifier.fillMaxWidth()) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                row.forEach { item ->
                    Box(Modifier.weight(1f).padding(horizontal = 5.dp)) {
                        TileCard(item)
                    }
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f).padding(horizontal = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun TileCard(item: TileItem) {
    val dark = isSystemInDarkTheme()
    val isEntry = item.navClick != null
    val active = item.enabled && item.checked
    val container = when {
        !item.enabled -> UiPalette.surface(dark, 0.18f)
        item.checked -> MaterialTheme.colorScheme.primaryContainer
        else -> UiPalette.surface(dark, 0.45f)
    }
    val nav = item.navClick
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 116.dp)
            .clickable(enabled = item.enabled) {
                if (nav != null) nav.invoke() else item.onToggle(!item.checked)
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active || isEntry) UiPalette.accent(dark)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        item.glyph,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (active || isEntry) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        isEntry -> "›"
                        !item.enabled -> "常驻"
                        item.checked -> "已开启"
                        else -> "已关闭"
                    },
                    style = if (isEntry) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.labelSmall,
                    color = if (active || isEntry) UiPalette.accent(dark)
                    else MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(item.title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, maxLines = 1, color = UiPalette.ink(dark))
            Text(item.subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun GroupTitle(t: String) {
    val dark = isSystemInDarkTheme()
    Text(t, style = MaterialTheme.typography.titleSmall,
        color = UiPalette.accent(dark), fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
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
)

/** 项目仓库地址（GitHub）——关于页「项目地址」点击跳转。 */
private const val PROJECT_URL = "https://github.com/Xposed-Modules-Repo/io.github.wumisc.antiburn"

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
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 96.dp),
    ) {
        Text("关于", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            color = UiPalette.ink(dark))
        Text("防止烧屏 · 精简磁贴版 v" + BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.titleMedium,
            color = UiPalette.accent(dark), fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(8.dp))

        ListRow("感谢名单", "${THANKS.size} 位朋友的支持与建议", { onOpen("thanks") }, dark)
        ListRow("支持捐赠", "支付宝 / 微信 · 金额随意，心意最重", { onOpen("donate") }, dark)
        ListRow("致敬名单", "${CREDITS.size} 个借鉴过的开源项目 · 点击查看", { onOpen("credit") }, dark)
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
) {
    val dark = isSystemInDarkTheme()
    val title = when (page) {
        "thanks" -> "感谢名单"
        "donate" -> "支持捐赠"
        "credit" -> "致敬名单"
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
            when (page) {
                "thanks" -> ThanksPage(dark)
                "donate" -> DonatePage(dark)
                "credit" -> CreditPage(dark, onOpenUrl)
            }
        }
    }
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
 * 适配计划占位卡片（v2.6.5）。
 *
 * <p>用户提出「功能区计划增加对快手和哔哩哔哩的支持」，本版选择先放计划说明，
 * 不动 Hook、不改 xposed_scope、也不放假开关。必须写清楚「规划中、不生效」，
 * 否则用户会在功能区找开关却找不到，又变成一次「装了没效果」。</p>
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
            Text("适配计划", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = UiPalette.ink(dark))
            Text("快手、哔哩哔哩的视频页区域隐藏已列入计划，当前版本尚未支持。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
            Text("本版仅作计划说明，不提供开关；状态栏、导航栏与抖音 / 红果短剧的现有功能不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * 周期时长小窗（v2.6.5）。
 *
 * <p>以前是首页 ListRow 跳一个二级页：要先回首页、再进二级页、改完再返回。
 * 现在入口放在「功能 → 通用」磁贴，点开就在这页弹窗调，改完点保存自动关窗。
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

    Text("一个周期 = 隐藏时长 + 显示时长。LSPosed 引擎下状态栏/导航栏与抖音、红果短剧" +
            "各区域周期性隐藏，无障碍遮盖引擎下按周期盖黑系统栏，如此往复防止固定像素常亮。",
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
