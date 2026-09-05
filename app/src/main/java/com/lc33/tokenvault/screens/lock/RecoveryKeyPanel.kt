package com.lc33.tokenvault.screens.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.common.SecureScreen
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppCheckboxRow
import com.lc33.tokenvault.ui.miuix.AppSecondaryButton
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 恢复密钥的展示（§7.1、红线 25）。
 *
 * 它自己挂 [SecureScreen]，不依赖调用方记得挂：这一页上是一串**能解开整个库**的明文，
 * 而它有两个入口（引导最后一步、设置里重新生成），漏一个就等于漏了防截屏。
 *
 * 三条文案都不要"优化"成更轻松的说法——这是全应用唯一一次"用户不动手就会在将来丢数据"
 * 的时刻，而丢数据的形式是"忘记 6 位 PIN，整库再也打不开"（§7.1）。
 *
 * 只给「复制」一个出口，[display] 不做成可选中文本：选中复制走的是系统剪贴板，
 * 绕开了 §7.5 那套（敏感标记 + 60 秒自动清除）；复制的实现在 `platform/`，界面不碰剪贴板。
 */
@Composable
fun RecoveryKeyPanel(
    display: String,
    saved: Boolean,
    onSavedChange: (Boolean) -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SecureScreen()
    val tokens = LocalAppTokens.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        AppCard {
            AppText(
                text = display,
                style = AppTextStyle.Title,
                fontFamily = tokens.monoFontFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AppText(
            text = stringResource(R.string.recovery_key_why),
            style = AppTextStyle.Body,
            color = LocalStatusPalette.current.warn,
        )
        AppText(
            text = stringResource(R.string.recovery_key_where),
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
        )
        AppSecondaryButton(
            text = stringResource(R.string.recovery_key_copy),
            onClick = onCopy,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
        AppCheckboxRow(
            text = stringResource(R.string.recovery_key_saved),
            checked = saved,
            onCheckedChange = onSavedChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
    }
}
