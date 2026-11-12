package io.github.wumisc.antiburn

// Kyant0/AndroidLiquidGlass 高级组件（LiquidToggle / LiquidSlider）的轻量移植。
// 视觉内核忠实复刻：胶囊轨道 + 玻璃滑块（按下时折射 lens 强化 + 模糊减弱 + 高光/白膜渐变）。
// 实现基于当前工程已用的 miuix 玻璃栈（drawBackdrop + blur + lens + vibrancy），无新增依赖、无额外库。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/** 液态玻璃开关（Kyant0 同款视觉）：胶囊轨道 + 可拖拽/点击的玻璃滑块。 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    // v2.6：统一强调色。旧版开关是 iOS 绿、滑条是亮蓝，加上主题紫/粉，观感割裂。
    val accent = UiPalette.accent(dark)
    val trackColor = if (!dark) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)

    val density = LocalDensity.current
    val dragWidth = with(density) { 20f.dp.toPx() }
    val scope = rememberCoroutineScope()
    // pointerInput 的 key 恒为 Unit，手势回调会捕获首次组合的 checked；
    // 用 rememberUpdatedState 让拖拽结束时的比较始终读到最新值。
    val currentChecked by rememberUpdatedState(checked)
    val currentOnChange by rememberUpdatedState(onCheckedChange)
    val fraction = remember { Animatable(if (checked) 1f else 0f) }
    val press = remember { Animatable(0f) }

    LaunchedEffect(checked) {
        fraction.animateTo(if (checked) 1f else 0f, spring())
    }

    val trackBackdrop = rememberLayerBackdrop()

    Box(modifier, contentAlignment = Alignment.CenterStart) {
        // 轨道：胶囊玻璃底 + 由开关颜色填充
        Box(
            Modifier
                .size(64.dp, 28.dp)
                .drawBackdrop(
                    backdrop = trackBackdrop,
                    shape = { RoundedCornerShape(50) },
                    effects = { blur(8f.dp.toPx(), 8f.dp.toPx()) },
                    onDrawSurface = { drawRect(lerp(trackColor, accent, fraction.value)) },
                )
        )

        // 滑块：玻璃（按下时折射/色散增强，模糊减弱）
        Box(
            Modifier
                .graphicsLayer {
                    val pad = with(density) { 2f.dp.toPx() }
                    translationX = lerpFloat(pad, pad + dragWidth, fraction.value)
                    val s = lerpFloat(1f, 1.25f, press.value)
                    scaleX = s; scaleY = s
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { scope.launch { press.animateTo(1f, spring()) } },
                        onDragEnd = {
                            scope.launch {
                                press.animateTo(0f, spring())
                                val target = if (fraction.value >= 0.5f) 1f else 0f
                                fraction.animateTo(target, spring())
                                if ((target == 1f) != currentChecked) currentOnChange(target == 1f)
                            }
                        },
                        onDragCancel = { scope.launch {
                            press.animateTo(0f, spring())
                            // v2.5：拖拽取消必须把进度复位到磁盘真值，否则界面停在
                            // 拖到的位置而 conf 里还是旧值，看起来「开关又回去了」。
                            fraction.animateTo(if (currentChecked) 1f else 0f, spring())
                        } },
                        onHorizontalDrag = { change, drag ->
                            change.consume()
                            scope.launch { fraction.snapTo((fraction.value + drag / dragWidth).coerceIn(0f, 1f)) }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        scope.launch {
                            press.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
                            press.animateTo(0f, spring())
                            val target = if (fraction.value >= 0.5f) 0f else 1f
                            fraction.animateTo(target, spring())
                            currentOnChange(target == 1f)
                        }
                    })
                }
                .drawBackdrop(
                    backdrop = rememberLayerBackdrop(),
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        val p = press.value
                        blur(8f.dp.toPx() * (1f - p))
                        lens(
                            refractionHeight = 5f.dp.toPx() * p,
                            refractionAmount = 10f.dp.toPx() * p,
                            depthEffect = false,
                            chromaticAberration = 0.35f * p,
                        )
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - press.value)) },
                )
                .size(40.dp, 24.dp)
        )
    }
}

/** 液态玻璃滑块（Kyant0 同款视觉）：胶囊轨道 + 可拖拽/点击的玻璃滑块。 */
@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    // v2.6：滑条与开关共用同一强调色，不再一处蓝一处绿。
    val accent = UiPalette.accent(dark)
    val trackColor = if (!dark) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)

    val density = LocalDensity.current
    val thumbWidth = with(density) { 40f.dp.toPx() }
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val currentFrac = ((value.coerceIn(valueRange) - valueRange.start) / span).coerceIn(0f, 1f)
    LaunchedEffect(currentFrac) {
        progress.animateTo(currentFrac, spring())
    }

    fun fracToValue(frac: Float) = valueRange.start + frac.coerceIn(0f, 1f) * span

    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        val trackWidth = constraints.maxWidth.toFloat()
        val thumbTravel = (trackWidth - thumbWidth).coerceAtLeast(0f)

        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .drawBackdrop(
                    backdrop = trackBackdrop,
                    shape = { RoundedCornerShape(50) },
                    effects = { blur(6f.dp.toPx(), 6f.dp.toPx()) },
                    onDrawSurface = { drawRect(trackColor) },
                )
        )
        // 已填充段
        Box(
            Modifier
                .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(accent)
        )

        // 玻璃滑块
        Box(
            Modifier
                .graphicsLayer {
                    translationX = progress.value.coerceIn(0f, 1f) * thumbTravel
                    val s = lerpFloat(1f, 1.3f, press.value)
                    scaleX = s; scaleY = s
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        scope.launch {
                            val f = (pos.x / trackWidth).coerceIn(0f, 1f)
                            progress.animateTo(f, spring())
                            onValueChange(fracToValue(f))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { scope.launch { press.animateTo(1f, spring()) } },
                        onDragEnd = { scope.launch { press.animateTo(0f, spring()) } },
                        onDragCancel = { scope.launch {
                            press.animateTo(0f, spring())
                            progress.animateTo(currentFrac, spring())
                        } },
                        onHorizontalDrag = { change, drag ->
                            change.consume()
                            scope.launch {
                                val f = (progress.value + drag / trackWidth).coerceIn(0f, 1f)
                                progress.snapTo(f)
                                onValueChange(fracToValue(f))
                            }
                        },
                    )
                }
                .drawBackdrop(
                    backdrop = rememberLayerBackdrop(),
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        val p = press.value
                        blur(8f.dp.toPx() * (1f - p))
                        lens(
                            refractionHeight = 6f.dp.toPx() * p,
                            refractionAmount = 10f.dp.toPx() * p,
                            depthEffect = false,
                            chromaticAberration = 0.35f * p,
                        )
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - press.value)) },
                )
                .size(40.dp, 24.dp)
        )
    }
}
