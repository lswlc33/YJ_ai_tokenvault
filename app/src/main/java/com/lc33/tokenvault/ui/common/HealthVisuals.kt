package com.lc33.tokenvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.AttentionKind
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

/**
 * “需要处理”每一档的那一句话。与 [labelOf] 同一个理由：同一个状态全应用只有一套文案
 * （红线 17）。ViewModel 那一侧只给 [AttentionKind]，它读不到资源（红线 19）。
 *
 * 每一句都要包含“接下来干什么”：“密钥无效”只是结论，而这张卡的名字叫“需要处理”。
 */
@Composable
@ReadOnlyComposable
fun messageOf(kind: AttentionKind): String = stringResource(
    when (kind) {
        AttentionKind.KeyRejected -> R.string.attention_key_rejected
        AttentionKind.ClientBlocked -> R.string.attention_client_blocked
        AttentionKind.ConfigError -> R.string.attention_config_error
        AttentionKind.LowBalance -> R.string.attention_low_balance
    },
)
