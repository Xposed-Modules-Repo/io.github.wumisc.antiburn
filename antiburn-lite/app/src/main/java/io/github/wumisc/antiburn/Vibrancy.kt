package io.github.wumisc.antiburn

// vibrancy：提饱和度，让液态玻璃更通透 —— 用 miuix-blur 原生 colorControls 实现，无手写 shader。

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/** 轻量 vibrancy：提饱和度，让液态玻璃更通透。 */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
