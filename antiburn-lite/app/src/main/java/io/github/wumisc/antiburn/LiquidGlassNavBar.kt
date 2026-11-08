package io.github.wumisc.antiburn

// 液态玻璃悬浮导航栏 —— 原生 Miuix-blur 内置 API（Kyant0/AndroidLiquidGlass 能力已由 miuix 封装）。
// 3 Tab：首页 / 功能 / 关于；导航栏本体自带 OLED 防烧屏微移（burnInProtect）。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 液态玻璃悬浮导航栏（原生 Miuix API）。
 *
 * @param items 导航项（首页 / 功能 / 关于）
 * @param selectedIndex 当前选中下标
 * @param onItemSelected 点击某下标回调
 * @param blurRadius 背景模糊半径
 * @param refractionHeight 折射过渡带高度
 * @param refractionAmount 折射强度
 * @param chromaticAberration 边缘色散（0 关闭）
 * @param burnInProtect 是否启用 OLED 防烧屏微移（默认开）
 * @param microMoveIntervalMs 防烧屏微移周期（毫秒，默认 45s）
 */
@Composable
fun LiquidGlassNavBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 22.dp,
    refractionHeight: Dp = 20.dp,
    refractionAmount: Dp = 18.dp,
    chromaticAberration: Float = 0.45f,
    burnInProtect: Boolean = true,
    microMoveIntervalMs: Long = 45_000L,
) {
    if (items.isEmpty()) return

    val backdrop = rememberLayerBackdrop()
    val pillShape = RoundedCornerShape(32.dp)
    val density = LocalDensity.current
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val blurRadiusPx = with(density) { blurRadius.toPx() }
    val refractionHeightPx = with(density) { refractionHeight.toPx() }
    val refractionAmountPx = with(density) { refractionAmount.toPx() }

    // 指示器滑动动画（px 位移，按实测宽度均分，保证 3 Tab 对齐）
    val indicatorAnim = remember { Animatable(0f) }

    var barWidthPx by remember { mutableFloatStateOf(0f) }
    val itemWidthPx = if (barWidthPx <= 0f) 0f else barWidthPx / items.size
    val indicatorWidthPx = with(density) { (itemWidthPx - 28.dp.toPx()).coerceAtLeast(0f) }

    LaunchedEffect(selectedIndex, itemWidthPx) {
        if (itemWidthPx > 0f) {
            indicatorAnim.animateTo(
                targetValue = selectedIndex * itemWidthPx + (itemWidthPx - indicatorWidthPx) / 2f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            )
        }
    }

    // ===== OLED 防烧屏：整条导航栏每 45s 上下微移 1dp（像素级），避免固定像素常驻 =====
    var burnOffsetY by remember { mutableStateOf(0.dp) }
    LaunchedEffect(burnInProtect, microMoveIntervalMs) {
        if (!burnInProtect) return@LaunchedEffect
        while (true) {
            burnOffsetY = 1.dp
            delay(microMoveIntervalMs)
            burnOffsetY = 0.dp
            delay(microMoveIntervalMs)
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(0, burnOffsetY.roundToPx()) } // 防烧屏微移：lambda 重载，state 变化稳定生效；不占布局、不裁剪
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(64.dp)
            .onSizeChanged { barWidthPx = it.width.toFloat() }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { pillShape },
                effects = {
                    vibrancy()
                    blur(blurRadiusPx, blurRadiusPx)
                    lens(
                        refractionHeight = refractionHeightPx,
                        refractionAmount = refractionAmountPx,
                        depthEffect = true,
                        chromaticAberration = chromaticAberration,
                    )
                },
                // 兜底底色：即使 AGSL/模糊不可用，导航栏也始终可见
                onDrawSurface = { drawRect(surfaceContainer.copy(alpha = 0.55f)) },
            )
            .clip(pillShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            // 选中胶囊指示器（半透明白，iOS 风格）
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer { translationX = indicatorAnim.value }
                    .width(with(density) { indicatorWidthPx.toDp() })
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.35f)),
            )

            // Tab 点击区
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable { onItemSelected(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
