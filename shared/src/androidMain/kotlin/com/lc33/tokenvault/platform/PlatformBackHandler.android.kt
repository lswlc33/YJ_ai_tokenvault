package com.lc33.tokenvault.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Android：拦截系统返回键。 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
