package com.lc33.tokenvault.screens.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.change_pin_confirm_subtitle
import tokenvault.shared.generated.resources.change_pin_confirm_title
import tokenvault.shared.generated.resources.change_pin_current_subtitle
import tokenvault.shared.generated.resources.change_pin_current_title
import tokenvault.shared.generated.resources.change_pin_new_subtitle
import tokenvault.shared.generated.resources.change_pin_new_title
import tokenvault.shared.generated.resources.change_pin_note
import tokenvault.shared.generated.resources.change_pin_title
import tokenvault.shared.generated.resources.unlock_backoff
import com.lc33.tokenvault.domain.UnlockBackoff
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 改 PIN（设置 → 安全 → 修改 PIN）。
 *
 * 页面底部那句话是这一页存在的全部意义：**改 PIN 只重新包裹数据密钥，不重新加密任何数据**
 * （红线 2）。不说清的话，"改 PIN"听起来像是要把整个库重算一遍，于是没人敢在存了几十条
 * 数据之后改它——而 PIN 恰恰是这个应用里最该能随时更换的东西。
 *
 * 三步都用同一个数字盘。第一步验旧 PIN：不验就等于"捡到已解锁手机的人可以直接换掉 PIN"，
 * 那样连退避（§7.2）都绕过去了。
 */
@Composable
fun ChangePinScreen(
    state: ChangePinUiState,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onBack: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    // 退避只压在"验旧 PIN"那一步：后两步没有可以被穷举的东西。
    val remaining = if (state.step == ChangePinStep.Current) {
        rememberRemainingSeconds(state.backoff)
    } else {
        0
    }
    CredentialPage(titleRes = Res.string.change_pin_title, onBack = onBack) {
        LockPageHeader(
            title = stringResource(
                when (state.step) {
                    ChangePinStep.Current -> Res.string.change_pin_current_title
                    ChangePinStep.New -> Res.string.change_pin_new_title
                    ChangePinStep.Confirm -> Res.string.change_pin_confirm_title
                },
            ),
            subtitle = stringResource(
                when (state.step) {
                    ChangePinStep.Current -> Res.string.change_pin_current_subtitle
                    ChangePinStep.New -> Res.string.change_pin_new_subtitle
                    ChangePinStep.Confirm -> Res.string.change_pin_confirm_subtitle
                },
            ),
            icon = null,
        )
        Spacer(Modifier.height(tokens.sectionSpacing))

        PinDots(filled = state.pinLength, slots = state.pinSlots, isError = state.error != null)
        Spacer(Modifier.height(tokens.itemSpacing))

        // 状态区常驻最小高度：验旧 PIN 派生密钥时，进度条不能把数字键盘和底部说明顶下去。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = tokens.minTouchTarget),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                if (state.busy) {
                    AppLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
                }
                if (remaining > 0) {
                    AppText(
                        text = stringResource(Res.string.unlock_backoff, formatCountdown(remaining)),
                        style = AppTextStyle.Body,
                        color = LocalStatusPalette.current.warn,
                        textAlign = TextAlign.Center,
                    )
                } else if (state.error != null) {
                    AppText(
                        text = stringResource(pinErrorRes(state.error)),
                        style = AppTextStyle.Body,
                        color = LocalStatusPalette.current.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(tokens.itemSpacing))

        PinKeypad(onDigit = onDigit, onBackspace = onBackspace, enabled = !state.busy && remaining == 0)
        Spacer(Modifier.height(tokens.sectionSpacing))
        AppText(
            text = stringResource(Res.string.change_pin_note),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            textAlign = TextAlign.Center,
        )
    }
}
