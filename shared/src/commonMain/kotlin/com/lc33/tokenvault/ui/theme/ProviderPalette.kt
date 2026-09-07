package com.lc33.tokenvault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 供应商色块的备选色。
 *
 * 与 [StatusPalette] 分开：状态色表达"好不好"，这一组只是让用户在长列表里认出某一行，
 * 不承担任何语义。`providers.color` 存的是这个列表的下标（红线 16 的唯一入口是编辑页的
 * 色块选择器）。取模访问，所以下标越界不会崩。
 */
@Immutable
data class ProviderPalette(val swatches: List<Color>) {
    fun swatchFor(index: Int): Color = swatches[((index % swatches.size) + swatches.size) % swatches.size]
}

private val LightSwatches = listOf(
    Color(0xFF3B76F0),
    Color(0xFF17A2A2),
    Color(0xFF7A5AF0),
    Color(0xFFD9720B),
    Color(0xFFC63B6E),
    Color(0xFF2E9E52),
    Color(0xFF5B6B7C),
    Color(0xFF9A6A1F),
)

private val DarkSwatches = listOf(
    Color(0xFF6F9BF5),
    Color(0xFF44C2C2),
    Color(0xFF9E88F5),
    Color(0xFFE99A44),
    Color(0xFFE0708F),
    Color(0xFF5FBE7C),
    Color(0xFF8996A5),
    Color(0xFFC49A4F),
)

fun providerPaletteFor(dark: Boolean): ProviderPalette =
    ProviderPalette(if (dark) DarkSwatches else LightSwatches)

val LocalProviderPalette = androidx.compose.runtime.staticCompositionLocalOf {
    ProviderPalette(LightSwatches)
}
