package io.github.wumisc.antiburn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
data class AbState(
    val enableStatus: Boolean = true,
    val enableNav: Boolean = false,
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
    val whitelist: String = "",
)

/** 保存 UI 状态，避免旋转/深浅色切换时丢掉尚未落盘的改动。 */
private val AbStateSaver = listSaver(
    save = { s: AbState ->
        listOf<Any>(
            s.enableStatus, s.enableNav, s.enablePoem, s.enableBall, s.enableLog,
            s.enableDouyinTop, s.enableDouyinBottom, s.enableDouyinLike,
            s.enableDouyinRt, s.enableDouyinLt, s.enableLive,
            s.enableDramaTop, s.enableDramaBottom, s.enableDramaLike,
            s.enableDramaRt, s.enableDramaLt,
            s.hideSec, s.showSec, s.customPoems, s.whitelist,
        )
    },
    restore = { l: List<Any> ->
        if (l.size < 20) {
            null
        } else {
            AbState(
                enableStatus = l[0] as Boolean,
                enableNav = l[1] as Boolean,
                enablePoem = l[2] as Boolean,
                enableBall = l[3] as Boolean,
                enableLog = l[4] as Boolean,
                enableDouyinTop = l[5] as Boolean,
                enableDouyinBottom = l[6] as Boolean,
                enableDouyinLike = l[7] as Boolean,
                enableDouyinRt = l[8] as Boolean,
                enableDouyinLt = l[9] as Boolean,
                enableLive = l[10] as Boolean,
                enableDramaTop = l[11] as Boolean,
                enableDramaBottom = l[12] as Boolean,
                enableDramaLike = l[13] as Boolean,
                enableDramaRt = l[14] as Boolean,
                enableDramaLt = l[15] as Boolean,
                hideSec = l[16] as Int,
                showSec = l[17] as Int,
                customPoems = l[18] as String,
                whitelist = l[19] as String,
            )
        }
    },
)

/** 读/写 antiburn.conf：统一走 ConfStore（应用内镜像 + root 原子写） */
object AbConf {
    private fun i(v: String?, def: Int): Int = v?.trim()?.toIntOrNull() ?: def
    private fun b(v: String?, def: Boolean = false): Boolean = if (v == null) def else v.trim() == "1"

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
        return AbState(
            enableStatus = b(map["enable_status"], true),
            enableNav = b(map["enable_navigation"]),
            enablePoem = b(map["enable_poem"]),
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
            customPoems = ConfStore.decodeValue(map["custom_poems"] ?: ""),
            whitelist = map["whitelist"] ?: map["blacklist"] ?: "",
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
        lines["enable_navigation"] = if (s.enableNav) "1" else "0"
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
        lines["whitelist"] = s.whitelist.trim()
        lines.remove("blacklist")

        val sb = StringBuilder()
        for (c in comments) sb.append(c).append('\n')
        for ((k, v) in lines) sb.append(k).append('=').append(v).append('\n')
        return ConfStore.writeEx(sb.toString())
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfStore.init(this)
        setContent { BurnInProtectApp() }
    }
}

@Composable
private fun BurnInProtectApp() {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme() else lightColorScheme()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 功能页内部子页：0=通用 1=抖音 2=红果（抖音/红果各是一整页磁贴）
    var featurePage by rememberSaveable { mutableIntStateOf(0) }
    var navMicroSec by rememberSaveable { mutableIntStateOf(45) }
    // 旋转/深浅色切换后保留未落盘的改动
    var state by rememberSaveable(stateSaver = AbStateSaver) { mutableStateOf(AbState()) }
    var savedState by rememberSaveable(stateSaver = AbStateSaver) { mutableStateOf(AbState()) }
    var status by rememberSaveable { mutableStateOf("正在读取配置…") }
    var rootOk by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    // 写入序号：只有最后一次改动真正落盘，取代旧版「每个 onValueChange 都 fork 一个 su」
    var saveSeq by remember { mutableIntStateOf(0) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    val saveMutex = remember { Mutex() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            val st = withContext(Dispatchers.IO) { AbConf.load() }
            state = st
            savedState = st
            status = if (rootOk) "配置已读取 · root 已授权"
            else "配置已读取 · 未检测到 root（仍可保存到应用内配置）"
        }
    }

    /** 防抖自动保存（force=true 时立即保存，对应「保存设置」按钮） */
    fun commit(target: AbState, force: Boolean) {
        val seq = ++saveSeq
        scope.launch {
            if (!force) delay(700)
            // 期间又有新改动 → 放弃这次写入
            if (seq != saveSeq) return@launch
            saving = true
            try {
                // 串行化 read-modify-write：旧任务即使在最后一次写入之后完成，
                // 也不会把旧内容覆盖回磁盘，更不会谎报「已保存」。
                val result: Int? = saveMutex.withLock {
                    if (seq != saveSeq) null
                    else withContext(Dispatchers.IO) { AbConf.save(target) }
                }
                if (result == null || seq != saveSeq) return@launch
                val msg = when (result) {
                    ConfStore.WRITE_OK -> "已保存并写入系统配置 · 下个周期生效"
                    ConfStore.WRITE_MIRROR_ONLY ->
                        "已保存到应用内配置；root 写入失败，部分设备需重启或重新授权后才生效"
                    else -> "保存失败：无法写入配置，请检查 root 授权或存储空间"
                }
                status = msg
                if (result != ConfStore.WRITE_FAILED) {
                    savedState = target
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            } finally {
                // 保证「保存中…」不会卡死导致按钮永久禁用
                if (seq == saveSeq) saving = false
            }
        }
    }

    /** 一键重启 SystemUI：持久化进程被杀后系统自动拉起，LSPosed 重新注入并读到新配置 */
    fun restartSystemUi() {
        if (restarting) return
        scope.launch {
            restarting = true
            val ok = withContext(Dispatchers.IO) { ConfStore.restartSystemUi() }
            restarting = false
            val msg = if (ok) "已重启系统界面，状态栏会短暂重绘后按新配置生效"
            else "重启失败：请确认已授予 root 权限，或手动切换一次开关等待下个周期"
            status = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // 函数式更新：回调里读到的永远是最新 state，同一重组内连点多个磁贴不会互相覆盖
    val onStateChange: ((AbState) -> AbState) -> Unit = { update ->
        val newState = update(state)
        state = newState
        commit(newState, false)
    }

    val wlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // 只有白名单页真正保存成功才刷新，且只更新白名单字段：
        // 取消 / 点窗口外部关闭时不能把主页面其它未落盘改动一起丢弃。
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { AbConf.load() }
                state = state.copy(whitelist = fresh.whitelist)
                savedState = savedState.copy(whitelist = fresh.whitelist)
            }
        }
    }
    val openWhitelist: () -> Unit = {
        wlLauncher.launch(Intent(context, WhitelistPickerActivity::class.java))
    }

    // 只在本次会话首次进入时读盘；旋转重建后 loaded 仍为 true，保留未落盘改动
    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        val pair = withContext(Dispatchers.IO) { AbConf.load() to ConfStore.hasRoot() }
        state = pair.first
        savedState = pair.first
        rootOk = pair.second
        status = if (pair.second) "配置已读取 · root 已授权"
        else "配置已读取 · 未检测到 root（仍可保存到应用内配置）"
        loaded = true
    }

    val saveBar: @Composable () -> Unit = {
        SaveBar(
            status = status,
            dirty = state != savedState,
            saving = saving,
            restarting = restarting,
            onSave = { commit(state, true) },
            onReload = { reload() },
            onRestart = { restartSystemUi() },
        )
    }

    val tabs = remember {
        listOf(
            NavItem("首页", navIcon("home", PATH_HOME)),
            NavItem("功能", navIcon("feature", PATH_FEATURES)),
            NavItem("关于", navIcon("about", PATH_ABOUT)),
        )
    }

    val backgroundBrush = Brush.linearGradient(
        colors = listOf(
            scheme.surfaceVariant.copy(alpha = 0.5f),
            scheme.background,
        ),
    )

    MaterialTheme(colorScheme = scheme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            when (selectedTab) {
                0 -> HomeTilePage(
                    enabled = state.enableStatus,
                    onToggle = { on -> onStateChange { s -> s.copy(enableStatus = on) } },
                    hideSec = state.hideSec,
                    onHideSecChange = { sec -> onStateChange { s -> s.copy(hideSec = sec) } },
                    showSec = state.showSec,
                    whitelist = state.whitelist,
                    navMicroSec = navMicroSec,
                    onNavMicroChange = { navMicroSec = it },
                    onOpenWhitelist = openWhitelist,
                    saveBar = saveBar,
                )
                1 -> FeaturesPage(
                    state = state,
                    onStateChange = onStateChange,
                    onOpenWhitelist = openWhitelist,
                    saveBar = saveBar,
                    featurePage = featurePage,
                    onFeaturePageChange = { featurePage = it },
                )
                2 -> AboutPage()
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
                        onTabSelected = { selectedTab = it },
                        backdrop = rememberLayerBackdrop(),
                        tabsCount = tabs.size,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    ) {
                        tabs.forEachIndexed { index, item ->
                            LiquidBottomTab({ selectedTab = index }) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    tint = if (dark) Color.White.copy(alpha = 0.9f)
                                    else Color.Black.copy(alpha = 0.85f),
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (dark) Color.White.copy(alpha = 0.9f)
                                    else Color.Black.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 顶部保存状态条：明确告诉用户改了什么、有没有真正落盘 */
@Composable
private fun SaveBar(
    status: String,
    dirty: Boolean,
    saving: Boolean,
    restarting: Boolean,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text(
                if (dirty) "● 有未保存的改动" else "✓ 所有改动已保存",
                style = MaterialTheme.typography.labelMedium,
                color = if (dirty) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) { Text(if (saving) "保存中…" else "保存设置") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onReload) { Text("重新读取") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onRestart,
                enabled = !restarting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (restarting) "正在重启系统界面…" else "重启系统界面（让配置立即生效）") }
            Text("重启后状态栏会短暂重绘，属正常现象；需要 root 权限",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ---- 首页 ----
@Composable
private fun HomeTilePage(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    hideSec: Int,
    onHideSecChange: (Int) -> Unit,
    showSec: Int,
    whitelist: String,
    navMicroSec: Int,
    onNavMicroChange: (Int) -> Unit,
    onOpenWhitelist: () -> Unit,
    saveBar: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 96.dp),
    ) {
        Text("防止烧屏", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("LSPosed 模块 · 全面功能", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        saveBar()

        Spacer(Modifier.height(12.dp))

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("防烧屏保护", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("周期隐藏状态栏图标，防 OLED 烧屏。与系统快捷磁贴同步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                    Text(if (enabled) "已开启" else "已关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp))
                }
                LiquidToggle(checked = enabled, onCheckedChange = onToggle)
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("隐藏持续时长", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("每 $hideSec 秒", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text("状态栏/导航栏/抖音各区域每隐藏 $hideSec 秒，再显示 $showSec 秒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                LiquidSlider(
                    value = hideSec.toFloat(),
                    onValueChange = { onHideSecChange(it.roundToInt()) },
                    valueRange = 10f..120f,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("导航栏微移间隔", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("每 $navMicroSec 秒", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text("底部液态玻璃导航栏周期性上下微移 1dp，防止固定像素烧印。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                LiquidSlider(
                    value = navMicroSec.toFloat(),
                    onValueChange = { onNavMicroChange(it.roundToInt()) },
                    valueRange = 30f..120f,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("白名单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenWhitelist) { Text("选择应用") }
                }
                val wl = whitelist
                Text(if (wl.isBlank()) "未设置：所有应用前台都执行防烧屏"
                    else "以下 ${wl.split(",").size} 个应用前台时不隐藏系统栏：$wl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---- 功能：磁贴式开关；抖音 / 红果 各自独立成一页 ----

/** 一个磁贴的数据。enabled=false 表示常驻不可切换（如红果底部剧集入口）。 */
private data class TileItem(
    val title: String,
    val glyph: String,
    val subtitle: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val onToggle: (Boolean) -> Unit,
)

@Composable
private fun FeaturesPage(
    state: AbState,
    onStateChange: ((AbState) -> AbState) -> Unit,
    onOpenWhitelist: () -> Unit,
    saveBar: @Composable () -> Unit,
    featurePage: Int,
    onFeaturePageChange: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 96.dp),
    ) {
        Text("功能磁贴", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("点击磁贴即可开关；改动自动保存，也可按「保存设置」立即落盘",
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
                TileGrid(
                    listOf(
                        TileItem("状态栏图标", "栏", "时钟/信号/电池周期透明化", state.enableStatus) {
                            onStateChange { s -> s.copy(enableStatus = !s.enableStatus) }
                        },
                        TileItem("导航栏", "导", "底部系统导航栏", state.enableNav) {
                            onStateChange { s -> s.copy(enableNav = !s.enableNav) }
                        },
                        TileItem("励志古诗", "诗", "隐藏期间滚动诗句", state.enablePoem) {
                            onStateChange { s -> s.copy(enablePoem = !s.enablePoem) }
                        },
                        TileItem("诊断日志", "志", "写 diag.log 排查", state.enableLog) {
                            onStateChange { s -> s.copy(enableLog = !s.enableLog) }
                        },
                        TileItem("悬浮球", "球", "点击切换隐藏控件", state.enableBall) {
                            onStateChange { s -> s.copy(enableBall = !s.enableBall) }
                        },
                    )
                )

                GroupTitle("周期时长")
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("隐藏 ${state.hideSec} 秒", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { onStateChange { s -> s.copy(hideSec = (s.hideSec - 5).coerceAtLeast(5)) } }) { Text("-5s") }
                            TextButton(onClick = { onStateChange { s -> s.copy(hideSec = (s.hideSec + 5).coerceAtMost(3600)) } }) { Text("+5s") }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("显示 ${state.showSec} 秒", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { onStateChange { s -> s.copy(showSec = (s.showSec - 1).coerceAtLeast(1)) } }) { Text("-1s") }
                            TextButton(onClick = { onStateChange { s -> s.copy(showSec = (s.showSec + 1).coerceAtMost(3600)) } }) { Text("+1s") }
                        }
                    }
                }

                GroupTitle("自定义诗句")
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("每行一首，格式：诗句 或 诗句|作者|出处",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.customPoems,
                            onValueChange = { onStateChange { s -> s.copy(customPoems = it) } },
                            label = { Text("自定义诗句") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 8,
                        )
                    }
                }

                GroupTitle("白名单")
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("白名单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = onOpenWhitelist) { Text("选择应用") }
                        }
                        Text("名单内应用前台时不隐藏系统栏；也可直接填包名，逗号分隔",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.whitelist,
                            onValueChange = { onStateChange { s -> s.copy(whitelist = it) } },
                            label = { Text("包名，逗号分隔") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )
                    }
                }
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
    val active = item.enabled && item.checked
    val container = when {
        !item.enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        item.checked -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 116.dp)
            .clickable(enabled = item.enabled) { item.onToggle(!item.checked) },
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
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        item.glyph,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        !item.enabled -> "常驻"
                        item.checked -> "已开启"
                        else -> "已关闭"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(item.title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text(item.subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun GroupTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

// ---- 关于 ----
@Composable
private fun AboutPage() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 96.dp),
    ) {
        Text("关于", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("防止烧屏 · 精简磁贴版 v" + BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("LSPosed 模块：SystemUI / 抖音 / 红果进程内周期隐藏固定 UI，防 OLED 烧屏。",
                    style = MaterialTheme.typography.bodyMedium)
                Text("功能：状态栏/导航栏透明化、抖音/红果各区+直播间、励志古诗、白名单、悬浮球。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text("功能页为磁贴式开关（通用 / 抖音 / 红果三个子页）；保存后可点「重启系统界面」立即生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text("液态玻璃渲染：Kyant0 / AndroidLiquidGlass（Apache-2.0）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text("配置：/data/local/tmp/antiburn.conf（root）+ 应用内镜像（ConfigProvider）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                Text("需要 root + LSPosed，并在模块作用域勾选 系统界面/抖音/红果；修改后最多 2 秒生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                Text("本模块仅供学习交流使用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
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
