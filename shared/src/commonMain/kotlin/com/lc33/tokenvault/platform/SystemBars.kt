package com.lc33.tokenvault.platform

import androidx.compose.runtime.Composable

/**
 * 同步系统状态栏图标的明暗（红线 20：系统栏是平台能力）。
 *
 * `enableEdgeToEdge()`（Android）/ 全屏（iOS）之后，系统栏背景透明、内容顶到屏幕边缘，
 * 但状态栏图标（时间、信号、电量）的颜色不会自动跟随应用配色——浅色主题下需要深色图标，
 * 否则白底白字看不清（问题 4）。由 [com.lc33.tokenvault.ui.miuix.AppTheme] 在解析出
 * [dark] 后调用，把主题明暗同步给系统栏。
 *
 * [dark] = true 表示当前是深色主题，状态栏图标用浅色；false 用深色。
 */
@Composable
expect fun PlatformStatusBarAppearance(dark: Boolean)
