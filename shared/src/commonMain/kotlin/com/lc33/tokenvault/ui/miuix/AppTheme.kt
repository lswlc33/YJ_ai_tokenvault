package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.lc33.tokenvault.platform.PlatformStatusBarAppearance
import com.lc33.tokenvault.ui.theme.AppColorSchemeMode
import com.lc33.tokenvault.ui.theme.AppTokens
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalProviderPalette
import com.lc33.tokenvault.ui.theme.LocalStatusPalette
import com.lc33.tokenvault.ui.theme.providerPaletteFor
import com.lc33.tokenvault.ui.theme.statusPaletteFor
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private val DefaultTokens = AppTokens()

/**
 * 解析后的暗色判定（由 [AppTheme] 提供）。
 *
 * 不能直接用 isSystemInDarkTheme()：用户把配色强制成 Light 而系统处于深色时，
 * 液态玻璃底栏会错误地取深色那套表面色。和状态色一样，必须按**解析后的模式**走。
 */
val LocalAppDarkTheme = staticCompositionLocalOf { false }

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
    val providerPalette = remember(dark) { providerPaletteFor(dark) }
    // 状态栏图标明暗随主题走：浅色主题要深色图标，否则白底白字看不清（问题 4）。
    PlatformStatusBarAppearance(dark = dark)
    MiuixTheme(controller = controller) {
        CompositionLocalProvider(
            LocalAppTokens provides DefaultTokens,
            LocalStatusPalette provides statusPalette,
            LocalProviderPalette provides providerPalette,
            LocalAppDarkTheme provides dark,
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
