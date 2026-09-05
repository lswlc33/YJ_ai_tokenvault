package com.lc33.tokenvault.screens.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.R
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.UnlockBackoff
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppPrimaryButton
import com.lc33.tokenvault.ui.miuix.AppSecretTextField
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.rememberSecretTextFieldState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette
import kotlinx.coroutines.delay

/**
 * 解锁页（§7.2、§7.4）。
 *
 * 三件事必须同时成立，缺一个都会让人以为应用坏了：
 * - 退避倒计时是**活的**。`UnlockBackoff` 存的是绝对时刻，这里每秒重算剩余秒数，
 *   到 0 自动把键盘放开——不需要用户杀进程重进（那正是最容易发生的误解）。
 * - 生物识别入口**只在真能用时出现**（七档里只有 `AVAILABLE`）。
 * - 恢复密钥入口只在**确实有那份包裹**时出现。给一个点进去用不了的入口比不给更糟。
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
    // 只显示属于当前输入方式的那条错误。切换方式时不清错误的责任不该压在后端身上——
    // 漏清一次的表现是"标题写着恢复密钥，下面写着 PIN 不对"。
    val error = state.error?.takeIf { it.isRecoveryError == state.recoveryMode }

    LockPage {
        LockPageHeader(
            title = stringResource(
                if (state.recoveryMode) R.string.unlock_recovery_title else R.string.unlock_title,
            ),
            subtitle = stringResource(
                if (state.recoveryMode) R.string.unlock_recovery_subtitle else R.string.unlock_subtitle,
            ),
        )
        Spacer(Modifier.height(tokens.sectionSpacing))

        if (!state.recoveryMode) {
            PinDots(
                filled = state.pinLength,
                slots = state.pinSlots,
                isError = error != null,
            )
            Spacer(Modifier.height(tokens.itemSpacing))
        }

        UnlockStatus(
            busy = state.busy,
            error = error,
            backoff = locked.backoff,
            remainingSeconds = remaining,
            // 退避罚的是 PIN 试错。恢复密钥是 128 位熵、穷举不现实，所以那条路不受退避限制——
            // 于是倒计时与"还能错几次"在恢复模式下都不该出现：一边让人等，一边输入框是活的，
            // 结果是被锁在门外的人干等着一件其实没挡住他的事。
            pinMode = !state.recoveryMode,
        )
        Spacer(Modifier.height(tokens.itemSpacing))

        if (state.recoveryMode) {
            RecoveryKeyInput(
                enabled = !state.busy,
                onSubmit = callbacks.onRecoveryUnlock,
                onCancel = callbacks.onExitRecoveryMode,
            )
        } else {
            PinKeypad(
                onDigit = callbacks.onPinDigit,
                onBackspace = callbacks.onPinBackspace,
                enabled = inputEnabled,
            )
            // 这里刻意只留一个 itemSpacing：加上错误行与"还能错几次"那行之后，
            // 360dp 宽 / 640dp 高的屏上整页正好卡在临界点，而被挤下去的恰好是
            // 「改用恢复密钥解锁」——被锁在门外的人唯一的出路（真机上撞过一次）。
            Spacer(Modifier.height(tokens.itemSpacing))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                if (locked.biometric.usable) {
                    AppTextButton(
                        text = stringResource(R.string.unlock_biometric),
                        onClick = callbacks.onBiometricUnlock,
                        enabled = inputEnabled,
                    )
                }
                if (locked.hasRecoveryKey) {
                    AppTextButton(
                        text = stringResource(R.string.unlock_use_recovery),
                        onClick = callbacks.onEnterRecoveryMode,
                        enabled = !state.busy,
                    )
                }
            }
        }
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
    pinMode: Boolean,
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
                text = stringResource(R.string.unlock_busy),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
            )
        }
        when {
            remainingSeconds > 0 && pinMode -> AppText(
                text = stringResource(R.string.unlock_backoff, formatCountdown(remainingSeconds)),
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
        if (pinMode && remainingSeconds == 0 && backoff.failedAttempts > 0) {
            AppText(
                text = if (freeLeft > 0) {
                    pluralStringResource(R.plurals.unlock_free_left, freeLeft, freeLeft)
                } else {
                    stringResource(R.string.unlock_next_waits)
                },
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 恢复密钥的回填。
 *
 * 用输入框而不是把键盘扩成 hex 盘：恢复密钥的正常保管方式是抄在纸上或存在密码管理器里，
 * 不让人粘贴等于逼着他们手敲 32 个字符，而这是一条"忘记 PIN 之后的最后出路"，
 * 在这里制造摩擦的代价最高。
 *
 * 输入框状态用 [rememberSecretTextFieldState]（不可保存）：可保存的那个会把内容序列化进
 * Activity 的 saved instance state，转个屏就把能解开整个库的东西交给了系统进程（红线 1）。
 *
 * 空格与短横线不用管——规范化由 `crypto/RecoveryKey.normalize` 做（页面层不碰 `crypto/`，
 * 所以形状对不对由后端回一个 [PinError]）。换行是唯一的例外，见 [withoutLineBreaks]。
 */
@Composable
private fun RecoveryKeyInput(
    enabled: Boolean,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val field = rememberSecretTextFieldState()
    // 离开这一页立刻清空：明文恢复密钥不该在返回之后还留在输入框里（§7.5 的"离开页面立即回遮"同理）
    DisposableEffect(Unit) {
        onDispose { field.clear() }
    }
    val submit = { onSubmit(field.chars.withoutLineBreaks()) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        AppSecretTextField(
            state = field,
            label = stringResource(R.string.unlock_recovery_label),
            singleLine = false,
            supportingText = stringResource(R.string.unlock_recovery_hint),
            onDone = submit,
        )
        AppPrimaryButton(
            text = stringResource(R.string.unlock_recovery_submit),
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
        AppTextButton(
            text = stringResource(R.string.unlock_recovery_back),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 去掉换行，顺手擦掉中间产物。
 *
 * `RecoveryKey.normalize` 处理空格、短横线与制表符，但不处理换行；而换行只可能来自**粘贴**
 * （密码管理器的备注、txt 文件几乎一定带一个结尾换行），也绝不可能是密钥的一部分。
 * 粘贴发生在界面这一侧，所以在界面挡掉——不挡的话，用户会在"忘记 PIN 之后唯一的出路"
 * 这一页上被告知"你抄对了的东西是错的"。
 */
private fun CharArray.withoutLineBreaks(): CharArray {
    if (none { it == '\n' || it == '\r' }) return this
    val kept = CharArray(size)
    var n = 0
    for (c in this) {
        if (c != '\n' && c != '\r') kept[n++] = c
    }
    val trimmed = kept.copyOf(n)
    // 这两份都是明文，交出去的只有 trimmed
    kept.fill(' ')
    fill(' ')
    return trimmed
}

/**
 * 每秒重算一次的退避剩余秒数。
 *
 * 时间从这里读（`System.currentTimeMillis()`）而不是从 domain 读：红线 20 只允许平台层
 * 碰当前时间，`UnlockBackoff` 因此把 `now` 做成参数。到 0 之后循环自然结束，不再唤醒。
 */
@Composable
private fun rememberRemainingSeconds(backoff: UnlockBackoff): Int {
    var remaining by remember(backoff) {
        mutableIntStateOf(backoff.remainingSeconds(System.currentTimeMillis()))
    }
    LaunchedEffect(backoff) {
        while (remaining > 0) {
            delay(1_000)
            remaining = backoff.remainingSeconds(System.currentTimeMillis())
        }
    }
    return remaining
}
