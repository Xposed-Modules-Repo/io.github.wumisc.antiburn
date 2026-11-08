package io.github.wumisc.antiburn

import androidx.compose.ui.graphics.vector.ImageVector

/** 单个导航项：图标 + 文案 */
data class NavItem(
    val label: String,
    val icon: ImageVector,
)
