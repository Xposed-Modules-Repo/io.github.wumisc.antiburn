package io.github.wumisc.antiburn

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v3.4.7：全 App 唯一的「一句话结果」出口。
 *
 * <p>以前所有反馈都走 Toast。Toast 是系统窗口，TalkBack 会不会念完全看 ROM 心情
 * （HyperOS 上多数情况只念半句或干脆不念），对看不见屏幕的用户等于没有反馈。
 * 现在改成：界面在前台 → 应用内提示条，挂 liveRegion = Priority，读屏一定播报；
 * 界面不在前台（服务、磁贴、退出后的异步回调）→ 退回 Toast，至少还有个东西。</p>
 */
object Hints {
    /** 带序号的提示，seq 让「同一句话再报一次」也能触发状态变化。 */
    data class Hint(val text: String, val seq: Long)

    private val _latest = MutableStateFlow<Hint?>(null)
    val latest: StateFlow<Hint?> = _latest

    @Volatile
    private var uiAlive = false

    /** Compose 侧登记：只有组合在树里才算「界面在前台」。 */
    fun attachUi() {
        uiAlive = true
    }

    fun detachUi() {
        uiAlive = false
    }

    fun show(context: Context?, msg: String, long: Boolean = true) {
        if (msg.isBlank()) return
        if (uiAlive) {
            _latest.value = Hint(msg, System.nanoTime())
        } else {
            val ctx = context ?: return
            Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 提示条本体：底部一条半透明卡片，4 秒后自己消失。
 * 读屏行为：出现即播报（liveRegion），但不抢焦点 —— 用户手上正在调的滑条不会被打断。
 */
@Composable
fun HintBar(hostModifier: Modifier = Modifier) {
    // 组合在树里 = 界面在前台；离开就 detach，异步回调自动退回 Toast。
    DisposableEffect(Unit) {
        Hints.attachUi()
        onDispose { Hints.detachUi() }
    }
    val hint by Hints.latest.collectAsState()
    var visible by remember { mutableStateOf<Hints.Hint?>(null) }
    LaunchedEffect(hint?.seq) {
        val h = hint ?: return@LaunchedEffect
        visible = h
        delay(4000)
        if (visible?.seq == h.seq) visible = null
    }
    val v = visible ?: return
    Card(
        hostModifier.semantics { liveRegion = LiveRegionMode.Assertive },
        shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        ),
    ) {
        Text(
            text = v.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

