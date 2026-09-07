package com.lc33.tokenvault.platform

import androidx.compose.runtime.Composable

/** iOS 没有系统返回键（导航靠手势/顶栏返回按钮），空实现。 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no-op
}
