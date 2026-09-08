package com.lc33.tokenvault.platform

import androidx.compose.runtime.Composable

/**
 * iOS：状态栏图标由 Compose 的 `ComposeUIViewController` 按系统深色模式自行处理，
 * 没有 `enableEdgeToEdge` 那种需要手动同步的 API，空实现。
 */
@Composable
actual fun PlatformStatusBarAppearance(dark: Boolean) = Unit
