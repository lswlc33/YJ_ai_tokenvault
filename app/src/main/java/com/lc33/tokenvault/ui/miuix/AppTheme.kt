package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.lc33.tokenvault.ui.theme.AppColorSchemeMode
import com.lc33.tokenvault.ui.theme.AppTokens
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette
import com.lc33.tokenvault.ui.theme.statusPaletteFor
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private val DefaultTokens = AppTokens()

/**
 * 全应用唯一的主题入口。
 *
 * 与计划.md §13.2 的草稿有一处刻意的修正：暗色判断不能用 `isSystemInDarkTheme()`，
 * 否则用户把配色强制成 Light、而系统处于深色时，状态色会取到暗色那一套。
 * 这里按解析后的模式判断。
 */
@Composable
fun AppTheme(
    mode: AppColorSchemeMode,
    content: @Composable () -> Unit,
) {
    val miuixMode = mode.toMiuixMode()
    val controller = remember(miuixMode) { ThemeController(colorSchemeMode = miuixMode) }
    val dark = when (mode) {
        AppColorSchemeMode.Light, AppColorSchemeMode.MonetLight -> false
        AppColorSchemeMode.Dark, AppColorSchemeMode.MonetDark -> true
        AppColorSchemeMode.System, AppColorSchemeMode.MonetSystem -> isSystemInDarkTheme()
    }
    val statusPalette = remember(dark) { statusPaletteFor(dark) }
    MiuixTheme(controller = controller) {
        CompositionLocalProvider(
            LocalAppTokens provides DefaultTokens,
            LocalStatusPalette provides statusPalette,
            content = content,
        )
    }
}

private fun AppColorSchemeMode.toMiuixMode(): ColorSchemeMode = when (this) {
    AppColorSchemeMode.System -> ColorSchemeMode.System
    AppColorSchemeMode.Light -> ColorSchemeMode.Light
    AppColorSchemeMode.Dark -> ColorSchemeMode.Dark
    AppColorSchemeMode.MonetSystem -> ColorSchemeMode.MonetSystem
    AppColorSchemeMode.MonetLight -> ColorSchemeMode.MonetLight
    AppColorSchemeMode.MonetDark -> ColorSchemeMode.MonetDark
}
