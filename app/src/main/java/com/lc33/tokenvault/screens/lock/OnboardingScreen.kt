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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppPrimaryButton
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appPrimaryColor
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 引导（§7.1、§7.2、§7.6）。
 *
 * 六步的顺序不是随便排的：**先把代价说清楚再让人设 PIN**。第一屏那三句话
 * （存什么、6 位 PIN 挡不住什么、最后会给一把恢复密钥）如果放到最后当"提示"，
 * 用户已经设完 PIN、心态是"赶紧进去用"，那三句话就等于没说——而它们恰好对应
 * 这个应用唯一两种真会发生的事故：忘记 PIN，以及以为元数据也是加密的。
 *
 * 每一步都不允许绕过 [OnboardingUiState.busy]：派生密钥时整页不接受输入。
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
            OnboardingStep.Welcome -> WelcomeStep(onNext = callbacks.onOnboardingNext)
            OnboardingStep.SetPin, OnboardingStep.ConfirmPin -> PinStep(state = state, callbacks = callbacks)
            OnboardingStep.Calibrating -> CalibratingStep()
            OnboardingStep.Biometric -> BiometricStep(state = state, callbacks = callbacks)
            OnboardingStep.RecoveryKey -> RecoveryKeyStep(state = state, callbacks = callbacks)
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
            text = stringResource(R.string.onboarding_step_of, index, total),
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
private fun WelcomeStep(onNext: () -> Unit) {
    val tokens = LocalAppTokens.current
    LockPageHeader(
        title = stringResource(R.string.onboarding_welcome_title),
        subtitle = stringResource(R.string.onboarding_welcome_subtitle),
    )
    Spacer(Modifier.height(tokens.sectionSpacing))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        BulletLine(stringResource(R.string.onboarding_welcome_what))
        BulletLine(stringResource(R.string.onboarding_welcome_limit))
        BulletLine(stringResource(R.string.onboarding_welcome_recovery))
    }
    Spacer(Modifier.height(tokens.sectionSpacing))
    AppPrimaryButton(
        text = stringResource(R.string.onboarding_start),
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
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
            if (confirming) R.string.onboarding_pin_confirm_title else R.string.onboarding_pin_title,
        ),
        subtitle = stringResource(
            if (confirming) R.string.onboarding_pin_confirm_subtitle else R.string.onboarding_pin_subtitle,
        ),
        icon = null,
    )
    Spacer(Modifier.height(tokens.sectionSpacing))
    PinDots(filled = state.pinLength, slots = state.pinSlots, isError = state.error != null)
    Spacer(Modifier.height(tokens.itemSpacing))
    if (state.error != null) {
        AppText(
            text = stringResource(pinErrorRes(state.error)),
            style = AppTextStyle.Body,
            color = LocalStatusPalette.current.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(tokens.itemSpacing))
    }
    PinKeypad(
        onDigit = callbacks.onPinDigit,
        onBackspace = callbacks.onPinBackspace,
        enabled = !state.busy,
    )
    if (confirming) {
        Spacer(Modifier.height(tokens.itemSpacing))
        AppTextButton(
            text = stringResource(R.string.onboarding_pin_restart),
            onClick = callbacks.onOnboardingBack,
            enabled = !state.busy,
        )
    }
}

/**
 * 跑 Argon2id 基准（§7.2）。
 *
 * **不可取消、也没有百分比**：BouncyCastle 的 `Argon2BytesGenerator` 不提供进度回调，
 * Argon2 本身也没有可分段的中间状态。给一个假的百分比条比给无限进度更糟——
 * 用户会按着自己的估算去判断"是不是卡死了"。所以这里给的是"这一步只做一次"这个信息，
 * 而不是一个编出来的进度。
 */
@Composable
private fun CalibratingStep() {
    val tokens = LocalAppTokens.current
    LockPageHeader(
        title = stringResource(R.string.onboarding_calibrating_title),
        subtitle = stringResource(R.string.onboarding_calibrating_subtitle),
        icon = null,
    )
    Spacer(Modifier.height(tokens.sectionSpacing))
    AppLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(tokens.itemSpacing))
    AppText(
        text = stringResource(R.string.onboarding_calibrating_note),
        style = AppTextStyle.Footnote,
        color = appSecondaryTextColor,
        textAlign = TextAlign.Center,
    )
}

/**
 * 生物识别（§7.3、红线 4/5）。
 *
 * 可用时只给一个开关，副文案说清它**只是解 DEK 的另一条路**，不是第二道锁——
 * 不说清的话用户会以为开了它更安全，而实际上它换来的是方便。
 *
 * 不可用时逐档给出原因（七档一档不合并），并且只有"去录入"这一档给按钮
 * （见 [offersBiometricEnroll]）。
 */
@Composable
private fun BiometricStep(state: OnboardingUiState, callbacks: LockCallbacks) {
    val tokens = LocalAppTokens.current
    val unavailableRes = biometricUnavailableRes(state.biometric)
    LockPageHeader(
        title = stringResource(R.string.onboarding_biometric_title),
        subtitle = stringResource(R.string.onboarding_biometric_subtitle),
        icon = null,
    )
    Spacer(Modifier.height(tokens.sectionSpacing))

    if (state.biometric.usable) {
        AppSwitchRow(
            title = stringResource(R.string.onboarding_biometric_switch),
            summary = stringResource(R.string.onboarding_biometric_switch_summary),
            checked = state.biometricOptIn,
            onCheckedChange = callbacks.onBiometricOptIn,
        )
    } else if (unavailableRes != null) {
        AppText(
            text = stringResource(unavailableRes),
            style = AppTextStyle.Body,
            color = LocalStatusPalette.current.neutral,
            textAlign = TextAlign.Center,
        )
        if (offersBiometricEnroll(state.biometric)) {
            Spacer(Modifier.height(tokens.itemSpacing))
            AppTextButton(
                text = stringResource(R.string.biometric_enroll_action),
                onClick = callbacks.onOpenBiometricEnroll,
            )
        }
    }

    Spacer(Modifier.height(tokens.sectionSpacing))
    AppPrimaryButton(
        text = stringResource(R.string.onboarding_next),
        onClick = callbacks.onOnboardingNext,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy,
    )
}

/** 最后一步：生成并展示恢复密钥，勾了"我已保存"才能完成（§7.1）。 */
@Composable
private fun RecoveryKeyStep(state: OnboardingUiState, callbacks: LockCallbacks) {
    val tokens = LocalAppTokens.current
    LockPageHeader(
        title = stringResource(R.string.recovery_key_title),
        subtitle = stringResource(R.string.recovery_key_subtitle),
        icon = null,
    )
    Spacer(Modifier.height(tokens.sectionSpacing))

    if (state.recoveryKeyDisplay == null) {
        // 还在生成 / 包裹。这一帧也要有内容，否则最后一步会闪一下空白。
        AppLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
    } else {
        RecoveryKeyPanel(
            display = state.recoveryKeyDisplay,
            saved = state.recoveryKeySaved,
            onSavedChange = callbacks.onRecoveryKeySavedChange,
            onCopy = callbacks.onCopyRecoveryKey,
        )
        Spacer(Modifier.height(tokens.sectionSpacing))
        AppPrimaryButton(
            text = stringResource(R.string.onboarding_finish),
            onClick = callbacks.onOnboardingNext,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.recoveryKeySaved && !state.busy,
        )
    }
}
