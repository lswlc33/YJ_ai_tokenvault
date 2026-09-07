package com.lc33.tokenvault.screens.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.unlock_backoff
import tokenvault.shared.generated.resources.unlock_busy
import tokenvault.shared.generated.resources.unlock_free_left
import tokenvault.shared.generated.resources.unlock_next_waits
import tokenvault.shared.generated.resources.unlock_subtitle
import tokenvault.shared.generated.resources.unlock_title
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.UnlockBackoff
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 解锁页（§7.2、§7.4）。
 *
 * 阶段1 迁移后只有 PIN 一条解锁路，生物识别与恢复密钥入口已删。
 *
 * 页面不持有明文 PIN：敲键只上报字符，满位由持有明文的那一侧自动提交（见 [UnlockUiState]）。
 */
@Composable
fun UnlockScreen(
    locked: LockPhase.Locked,
    state: UnlockUiState,
    callbacks: LockCallbacks,
) {
    val tokens = LocalAppTokens.current
    val remaining = rememberRemainingSeconds(locked.backoff)
    val inputEnabled = !state.busy && remaining == 0
    val error = state.error

    LockPage {
        LockPageHeader(
            title = stringResource(Res.string.unlock_title),
            subtitle = stringResource(Res.string.unlock_subtitle),
        )
        Spacer(Modifier.height(tokens.sectionSpacing))

        PinDots(
            filled = state.pinLength,
            slots = state.pinSlots,
            isError = error != null,
        )
        Spacer(Modifier.height(tokens.itemSpacing))

        UnlockStatus(
            busy = state.busy,
            error = error,
            backoff = locked.backoff,
            remainingSeconds = remaining,
        )
        Spacer(Modifier.height(tokens.itemSpacing))

        PinKeypad(
            onDigit = callbacks.onPinDigit,
            onBackspace = callbacks.onPinBackspace,
            enabled = inputEnabled,
        )
    }
}

/**
 * 错误、退避倒计时、"还剩几次免费机会"三条线。
 *
 * 为什么要有第三条：退避是"第 5 次起"才罚（[UnlockBackoff.FREE_ATTEMPTS]），
 * 不提前说的话，第 5 次输错时那 30 秒会显得像随机故障。次数上限来自 domain，
 * 界面不自己写一个 4。
 */
@Composable
private fun UnlockStatus(
    busy: Boolean,
    error: PinError?,
    backoff: UnlockBackoff,
    remainingSeconds: Int,
) {
    val palette = LocalStatusPalette.current
    val freeLeft = UnlockBackoff.FREE_ATTEMPTS - backoff.failedAttempts

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (busy) {
            AppLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
            AppText(
                text = stringResource(Res.string.unlock_busy),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
            )
        }
        when {
            remainingSeconds > 0 -> AppText(
                text = stringResource(Res.string.unlock_backoff, formatCountdown(remainingSeconds)),
                style = AppTextStyle.Body,
                color = palette.warn,
                textAlign = TextAlign.Center,
            )

            error != null -> AppText(
                text = stringResource(pinErrorRes(error)),
                style = AppTextStyle.Body,
                color = palette.error,
                textAlign = TextAlign.Center,
            )
        }
        if (remainingSeconds == 0 && backoff.failedAttempts > 0) {
            AppText(
                text = if (freeLeft > 0) {
                    pluralStringResource(Res.plurals.unlock_free_left, freeLeft, freeLeft)
                } else {
                    stringResource(Res.string.unlock_next_waits)
                },
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}
