package com.lc33.tokenvault.screens.lock

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/** 改 PIN 的三步。顺序即流程。 */
enum class ChangePinStep {
    Current,
    New,
    Confirm,
}

/**
 * 改 PIN（红线 2、§7.1）。
 *
 * 和 [OnboardingUiState] 一样：**不放明文**，只放位数与上一次错在哪。
 */
@Immutable
data class ChangePinUiState(
    val step: ChangePinStep = ChangePinStep.Current,
    val pinLength: Int = 0,
    val pinSlots: Int = 6,
    val error: PinError? = null,
    val busy: Boolean = false,
)

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
    CredentialPage(titleRes = R.string.change_pin_title, onBack = onBack) {
        LockPageHeader(
            title = stringResource(
                when (state.step) {
                    ChangePinStep.Current -> R.string.change_pin_current_title
                    ChangePinStep.New -> R.string.change_pin_new_title
                    ChangePinStep.Confirm -> R.string.change_pin_confirm_title
                },
            ),
            subtitle = stringResource(
                when (state.step) {
                    ChangePinStep.Current -> R.string.change_pin_current_subtitle
                    ChangePinStep.New -> R.string.change_pin_new_subtitle
                    ChangePinStep.Confirm -> R.string.change_pin_confirm_subtitle
                },
            ),
            icon = null,
        )
        Spacer(Modifier.height(tokens.sectionSpacing))

        PinDots(filled = state.pinLength, slots = state.pinSlots, isError = state.error != null)
        Spacer(Modifier.height(tokens.itemSpacing))

        if (state.busy) {
            AppLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(tokens.itemSpacing))
        }
        if (state.error != null) {
            AppText(
                text = stringResource(pinErrorRes(state.error)),
                style = AppTextStyle.Body,
                color = LocalStatusPalette.current.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(tokens.itemSpacing))
        }

        PinKeypad(onDigit = onDigit, onBackspace = onBackspace, enabled = !state.busy)
        Spacer(Modifier.height(tokens.sectionSpacing))
        AppText(
            text = stringResource(R.string.change_pin_note),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            textAlign = TextAlign.Center,
        )
    }
}
