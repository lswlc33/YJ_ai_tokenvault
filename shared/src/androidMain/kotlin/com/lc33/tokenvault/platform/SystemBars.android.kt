package com.lc33.tokenvault.platform

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Android：用 [WindowCompat.getInsetsController] 设置状态栏图标明暗。
 *
 * `enableEdgeToEdge()` 之后系统默认给浅色图标（适配深色主题），浅色主题下必须
 * 显式把 `isAppearanceLightStatusBars` 设为 true，否则状态栏图标白底白字。
 */
@Composable
actual fun PlatformStatusBarAppearance(dark: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
}
