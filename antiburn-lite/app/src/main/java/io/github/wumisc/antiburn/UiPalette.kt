package io.github.wumisc.antiburn

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * v2.6：全 App 唯一配色来源。
 *
 * 旧版颜色割裂：Material3 默认 primary 是紫、LiquidToggle 用 iOS 绿、
 * LiquidSlider 用亮蓝、感谢名单头像用 primary→tertiary（紫→粉），
 * 深浅色切换时更是「一会紫一会蓝一会粉」。现在全部收敛到一套低饱和
 * 「雾青」+ 中性黑白灰，所有控件只从这里取色，深浅模式各一套明度。
 *
 * 底色刻意不用纯黑 / 纯白，而是带柔雾渐变的近黑 / 近白，降低 OLED
 * 上亮字与纯底的对比刺激，观感更接近用户要的「丝滑柔雾」质感。
 */
object UiPalette {

    /** 唯一强调色：低饱和雾青。深底用浅雾青，浅底用深雾青，保证对比度。 */
    private val AccentDark = Color(0xFF8DBDB1)
    private val AccentLight = Color(0xFF3C6F67)

    /** 深色模式正文（近白，绝不出现黑字）；浅色模式正文（近黑）。 */
    private val InkDark = Color(0xFFE7E8EA)
    private val InkLight = Color(0xFF1B1D20)

    /**
     * 警示橙：只给「操作顺序错了会出事」这类提示用（例如关功能前必须先开无障碍）。
     *
     * <p>刻意不复用强调色：雾青在这套配色里代表「可用 / 已生效」，拿它去写警告，
     * 用户会当成又一个普通说明。橙色是这套界面里唯一的非青绿色，看到就知道要停一下。
     * 深浅两档都按 WCAG AA 正文标准挑过（深色底 8.2:1，浅色底 5.4:1）。</p>
     */
    private val WarnDark = Color(0xFFE8A347)
    private val WarnLight = Color(0xFF9A5B00)

    fun accent(dark: Boolean): Color = if (dark) AccentDark else AccentLight
    fun warn(dark: Boolean): Color = if (dark) WarnDark else WarnLight
    fun ink(dark: Boolean): Color = if (dark) InkDark else InkLight

    /** secondary / tertiary 全部收到同一色相，杜绝 Material3 默认给 tertiary 的粉色。 */
    fun scheme(dark: Boolean): ColorScheme = if (dark) {
        darkColorScheme(
            primary = AccentDark,
            onPrimary = Color(0xFF10231F),
            primaryContainer = Color(0xFF27463F),
            onPrimaryContainer = Color(0xFFDCEBE6),
            secondary = Color(0xFF7FAFA3),
            onSecondary = Color(0xFF10231F),
            secondaryContainer = Color(0xFF233F39),
            onSecondaryContainer = Color(0xFFDCEBE6),
            tertiary = AccentDark,
            onTertiary = Color(0xFF10231F),
            tertiaryContainer = Color(0xFF27463F),
            onTertiaryContainer = Color(0xFFDCEBE6),
            background = Color(0xFF131417),
            onBackground = InkDark,
            surface = Color(0xFF17181C),
            onSurface = InkDark,
            surfaceVariant = Color(0xFF23252A),
            onSurfaceVariant = Color(0xFFB9BCC2),
            outline = Color(0xFF7C8088),
            outlineVariant = Color(0xFF33363C),
        )
    } else {
        lightColorScheme(
            primary = AccentLight,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD9EAE6),
            onPrimaryContainer = Color(0xFF17332E),
            secondary = Color(0xFF5A8C83),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE1EFEB),
            onSecondaryContainer = Color(0xFF17332E),
            tertiary = AccentLight,
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFD9EAE6),
            onTertiaryContainer = Color(0xFF17332E),
            background = Color(0xFFF6F5F3),
            onBackground = InkLight,
            surface = Color(0xFFFBFAF8),
            onSurface = InkLight,
            surfaceVariant = Color(0xFFE9E8E4),
            onSurfaceVariant = Color(0xFF54585E),
            outline = Color(0xFF8A8E92),
            outlineVariant = Color(0xFFD6D5D1),
        )
    }

    /** 柔雾丝滑底色：纵向三段低对比渐变，不用纯黑纯白。 */
    fun background(dark: Boolean): Brush = if (dark) {
        Brush.verticalGradient(
            listOf(Color(0xFF1A1C21), Color(0xFF111216), Color(0xFF17181C)),
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0xFFF9F8F6), Color(0xFFFFFFFF), Color(0xFFF0EFEC)),
        )
    }

    /** 叠在底色上的一层柔光，营造细腻的半透丝光质感（alpha 很低，不刺眼）。 */
    fun sheen(dark: Boolean): Brush = Brush.radialGradient(
        colors = if (dark) {
            listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)
        } else {
            listOf(Color.White.copy(alpha = 0.9f), Color.Transparent)
        },
    )

    /** 卡片 / 磁贴统一容器色：半透明、低对比，深浅模式都护眼。 */
    fun surface(dark: Boolean, alpha: Float = 0.4f): Color =
        (if (dark) Color(0xFF23252A) else Color(0xFFE9E8E4)).copy(alpha = alpha)
}
