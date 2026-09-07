package com.lc33.tokenvault.platform

import androidx.compose.runtime.Composable

/** JVM（桌面/单测）没有系统返回键，空实现。 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no-op
}
