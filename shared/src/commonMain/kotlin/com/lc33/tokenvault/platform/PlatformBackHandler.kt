package com.lc33.tokenvault.platform

import androidx.compose.runtime.Composable

/**
 * 系统返回键处理（红线 20：返回键是平台能力）。
 *
 * Android 用 `androidx.activity.compose.BackHandler` 拦截系统返回键；iOS 没有
 * 系统返回键（导航靠手势/顶栏），所以空实现。
 *
 * [enabled] 为 true 时才拦截；[onBack] 在返回键按下时触发。
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
