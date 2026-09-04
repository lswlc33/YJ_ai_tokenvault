package com.lc33.tokenvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 状态 → 颜色 / 文案的**唯一**映射（红线 17）。
 *
 * 页面不允许自己写 `when (health) { Ok -> palette.ok … }`：那样一处漏改就会出现
 * 同一个状态两种颜色。想加一档状态就改这两个函数。
 */
@Composable
@ReadOnlyComposable
fun colorOf(health: UiHealth): Color {
    val palette = LocalStatusPalette.current
    return when (health) {
        UiHealth.Ok -> palette.ok
        UiHealth.Warn -> palette.warn
        UiHealth.Error -> palette.error
        UiHealth.Unknown -> palette.neutral
    }
}

@Composable
@ReadOnlyComposable
fun labelOf(health: UiHealth): String = stringResource(
    when (health) {
        UiHealth.Ok -> R.string.health_ok
        UiHealth.Warn -> R.string.health_warn
        UiHealth.Error -> R.string.health_error
        UiHealth.Unknown -> R.string.health_unknown
    },
)
