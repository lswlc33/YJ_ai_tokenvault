package com.lc33.tokenvault.screens.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.onboarding_calibrating_note
import tokenvault.shared.generated.resources.onboarding_calibrating_subtitle
import tokenvault.shared.generated.resources.onboarding_calibrating_title
import tokenvault.shared.generated.resources.onboarding_pin_confirm_subtitle
import tokenvault.shared.generated.resources.onboarding_pin_confirm_title
import tokenvault.shared.generated.resources.onboarding_pin_restart
import tokenvault.shared.generated.resources.onboarding_pin_subtitle
import tokenvault.shared.generated.resources.onboarding_pin_title
import tokenvault.shared.generated.resources.onboarding_start
import tokenvault.shared.generated.resources.onboarding_step_of
import tokenvault.shared.generated.resources.onboarding_welcome_limit
import tokenvault.shared.generated.resources.onboarding_welcome_local
import tokenvault.shared.generated.resources.onboarding_welcome_subtitle
import tokenvault.shared.generated.resources.onboarding_welcome_title
import tokenvault.shared.generated.resources.onboarding_welcome_what
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appPrimaryColor
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 引导（§7.1、§7.2、§7.6）。
 *
 * 四步的顺序不是随便排的：**先把代价说清楚再让人设 PIN**。第一屏那三句话
 * （存什么、6 位 PIN 挡不住什么、数据只在本地）如果放到最后当"提示"，
 * 用户已经设完 PIN、心态是"赶紧进去用"，那三句话就等于没说——而它们恰好对应
 * 这个应用唯一真会发生的事故：忘记 PIN，以及以为元数据也是加密的。
 *
 * 每一步都不允许绕过 [OnboardingUiState.busy]：派生密钥时整页不接受输入。
 *
 * **阶段1 迁移**：删掉生物识别与恢复密钥两步，引导简化为四步。
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    callbacks: LockCallbacks,
) {
    val tokens = LocalAppTokens.current
    LockPage {
        StepProgress(step = state.step)
        Spacer(Modifier.height(tokens.sectionSpacing))

        when (state.step) {
            OnboardingStep.Welcome -> WelcomeStep(
                onNext = callbacks.onOnboardingNext,
                enabled = !state.busy,
            )
            OnboardingStep.SetPin, OnboardingStep.ConfirmPin -> PinStep(state = state, callbacks = callbacks)
            OnboardingStep.Calibrating -> CalibratingStep()
        }
    }
}

/** 第几步 / 共几步。引导最怕的是"不知道还有多久"，所以这一行常驻。 */
@Composable
private fun StepProgress(step: OnboardingStep) {
    val total = OnboardingStep.entries.size
    val index = step.ordinal + 1
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppLinearProgress(
            progress = index.toFloat() / total.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        AppText(
            text = stringResource(Res.string.onboarding_step_of, index, total),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
        )
    }
}

/**
 * 第一屏：先说清三件事。
 *
 * 第二条（元数据是明文、6 位 PIN 挡不住离线穷举）写在这里而不是只写在"关于"页，
 * 是因为它会改变用户的决定——知道这一点的人不会把复用过的密码存进来（§7.6）。
 */
@Composable
private fun WelcomeStep(onNext: () -> Unit, enabled: Boolean) {
    val tokens = LocalAppTokens.current
    LockPageHeader(
        title = stringResource(Res.string.onboarding_welcome_title),
        subtitle = stringResource(Res.string.onboarding_welcome_subtitle),
    )
    Spacer(Modifier.height(tokens.sectionSpacing))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        BulletLine(stringResource(Res.string.onboarding_welcome_what))
        BulletLine(stringResource(Res.string.onboarding_welcome_limit))
        BulletLine(stringResource(Res.string.onboarding_welcome_local))
    }
    Spacer(Modifier.height(tokens.sectionSpacing))
    AppActionRow(
        text = stringResource(Res.string.onboarding_start),
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    )
}

@Composable
private fun BulletLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(appPrimaryColor),
        )
        AppText(text = text, style = AppTextStyle.Body)
    }
}

/**
 * 设 PIN 与确认 PIN 共用一屏。
 *
 * 副文案要说清"改 PIN 是 O(1)"（红线 2）：PIN 只用来包裹真正的数据密钥，
 * 所以以后改 PIN 不会重新加密任何数据，也就不必因为"怕以后改起来麻烦"而在这里犹豫。
 */
@Composable
private fun PinStep(state: OnboardingUiState, callbacks: LockCallbacks) {
    val tokens = LocalAppTokens.current
    val confirming = state.step == OnboardingStep.ConfirmPin
    LockPageHeader(
        title = stringResource(
            if (confirming) Res.string.onboarding_pin_confirm_title else Res.string.onboarding_pin_title,
        ),
        subtitle = stringResource(
            if (confirming) Res.string.onboarding_pin_confirm_subtitle else Res.string.onboarding_pin_subtitle,
        ),
        icon = null,
    )
    Spacer(Modifier.height(tokens.sectionSpacing))
    PinDots(filled = state.pinLength, slots = state.pinSlots, isError = state.error != null)
    Spacer(Modifier.height(tokens.itemSpacing))
    if (state.error != null) {
        state.error?.let { error ->
            AppText(
                text = stringResource(pinErrorRes(error)),
                style = AppTextStyle.Body,
                color = LocalStatusPalette.current.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(tokens.itemSpacing))
    }
    PinKeypad(
        onDigit = callbacks.onPinDigit,
        onBackspace = callbacks.onPinBackspace,
        enabled = !state.busy,
    )
    if (confirming) {
        Spacer(Modifier.height(tokens.itemSpacing))
        AppActionRow(
            text = stringResource(Res.string.onboarding_pin_restart),
            onClick = callbacks.onOnboardingBack,
            enabled = !state.busy,
        )
    }
}

/**
 * 跑 PBKDF2 基准（§7.2）。
 *
 * **不可取消、也没有百分比**：PBKDF2 没有可分段的中间状态。给一个假的百分比条比给无限进度
 * 更糟——用户会按着自己的估算去判断"是不是卡死了"。所以这里给的是"这一步只做一次"这个
 * 信息，而不是一个编出来的进度。
 */
@Composable
private fun CalibratingStep() {
    val tokens = LocalAppTokens.current
    LockPageHeader(
        title = stringResource(Res.string.onboarding_calibrating_title),
        subtitle = stringResource(Res.string.onboarding_calibrating_subtitle),
        icon = null,
    )
    Spacer(Modifier.height(tokens.sectionSpacing))
    AppLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(tokens.itemSpacing))
    AppText(
        text = stringResource(Res.string.onboarding_calibrating_note),
        style = AppTextStyle.Footnote,
        color = appSecondaryTextColor,
        textAlign = TextAlign.Center,
    )
}
