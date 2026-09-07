package com.lc33.tokenvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import com.lc33.tokenvault.screens.model.AttentionKind
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.ui.theme.LocalStatusPalette
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.attention_client_blocked
import tokenvault.shared.generated.resources.attention_config_error
import tokenvault.shared.generated.resources.attention_key_rejected
import tokenvault.shared.generated.resources.attention_low_balance
import tokenvault.shared.generated.resources.health_error
import tokenvault.shared.generated.resources.health_ok
import tokenvault.shared.generated.resources.health_unknown
import tokenvault.shared.generated.resources.health_warn

/**
 * 状态 → 颜色 / 文案的**唯一**映射（红线 17）。
 *
 * 页面不允许自己写 `when (health) { Ok -> palette.ok … }`：那样一处漏改就会出现
 * 同一个状态两种颜色。想加一档状态就改这两个函数。
 */
@Composable
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
fun labelOf(health: UiHealth): String = stringResource(
    when (health) {
        UiHealth.Ok -> Res.string.health_ok
        UiHealth.Warn -> Res.string.health_warn
        UiHealth.Error -> Res.string.health_error
        UiHealth.Unknown -> Res.string.health_unknown
    },
)

/**
 * "需要处理"每一档的那一句话。与 [labelOf] 同一个理由：同一个状态全应用只有一套文案
 * （红线 17）。ViewModel 那一侧只给 [AttentionKind]，它读不到资源（红线 19）。
 *
 * 每一句都要包含"接下来干什么"："密钥无效"只是结论，而这张卡的名字叫"需要处理"。
 */
@Composable
fun messageOf(kind: AttentionKind): String = stringResource(
    when (kind) {
        AttentionKind.KeyRejected -> Res.string.attention_key_rejected
        AttentionKind.ClientBlocked -> Res.string.attention_client_blocked
        AttentionKind.ConfigError -> Res.string.attention_config_error
        AttentionKind.LowBalance -> Res.string.attention_low_balance
    },
)
