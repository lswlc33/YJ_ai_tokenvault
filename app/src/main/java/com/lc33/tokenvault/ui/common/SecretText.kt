package com.lc33.tokenvault.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import kotlinx.coroutines.delay

/** 明文展开后自动回遮的秒数（§7.5）。 */
const val REVEAL_SECONDS = 30

/**
 * 遮蔽 / 展开 / 自动回遮 / 复制，四件事在一个组件里。
 *
 * **API 密钥与平台账号密码共用它**，这是红线 21 落在代码上的样子：只要只有一个组件
 * 能展示明文，就不可能出现"密钥回遮了、密码忘了回遮"这种偏差。
 *
 * [reveal] 是**按需取明文**的 lambda，不是一个 `String` 参数——明文不该在
 * 重组之间被 Compose 持有。为 null 表示当前拿不到明文（金库锁定，或者像 M0.8 这样
 * 还没有 DEK），此时不显示眼睛按钮，而不是显示一个点了没反应的按钮。
 *
 * 离开页面时 `remember` 一起消失，所以"离开页面立即回遮"是免费得到的。
 */
@Composable
fun SecretText(
    masked: String,
    modifier: Modifier = Modifier,
    reveal: (() -> String)? = null,
    onCopy: (() -> Unit)? = null,
) {
    val tokens = LocalAppTokens.current
    var plaintext by remember { mutableStateOf<String?>(null) }
    var secondsLeft by remember { mutableIntStateOf(0) }

    // 展开后倒数回遮。用 key = plaintext 而不是一个布尔：再次点开会重新起算，
    // 而不是接着上一次的倒数。
    LaunchedEffect(plaintext) {
        if (plaintext == null) return@LaunchedEffect
        secondsLeft = REVEAL_SECONDS
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
        plaintext = null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppText(
                text = plaintext ?: masked,
                style = AppTextStyle.Body,
                fontFamily = tokens.monoFontFamily,
                modifier = Modifier.weight(1f),
            )
            if (reveal != null) {
                AppIconButton(
                    icon = if (plaintext == null) AppIcon.Reveal else AppIcon.Conceal,
                    contentDescription = stringResource(
                        if (plaintext == null) R.string.secret_reveal_cd else R.string.secret_conceal_cd,
                    ),
                    onClick = { plaintext = if (plaintext == null) reveal() else null },
                )
            }
            if (onCopy != null) {
                AppIconButton(
                    icon = AppIcon.Copy,
                    contentDescription = stringResource(R.string.secret_copy_cd),
                    onClick = onCopy,
                )
            }
        }
        if (plaintext != null) {
            AppText(
                text = stringResource(R.string.secret_auto_conceal, secondsLeft),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
        }
    }
}
