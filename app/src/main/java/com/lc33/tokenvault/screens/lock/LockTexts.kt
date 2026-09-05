package com.lc33.tokenvault.screens.lock

import androidx.annotation.StringRes
import com.lc33.tokenvault.R
import com.lc33.tokenvault.domain.BiometricAvailability

/**
 * 锁闸这几页的"状态 → 文案"映射。
 *
 * 单独收在一个文件里是红线 17 的落实：同一种状态在引导页与解锁页必须是同一句话。
 * 分散在两个页面里各写一遍 `when`，迟早出现"设 PIN 时说两次不一致、改 PIN 时说输入有误"。
 */

@StringRes
fun pinErrorRes(error: PinError): Int = when (error) {
    PinError.Mismatch -> R.string.pin_error_mismatch
    PinError.TooSimple -> R.string.pin_error_too_simple
    PinError.Wrong -> R.string.pin_error_wrong
    PinError.RecoveryKeyMalformed -> R.string.pin_error_recovery_malformed
    PinError.RecoveryKeyWrong -> R.string.pin_error_recovery_wrong
}

/**
 * 生物识别不可用的原因。七档一档都不合并（见 `BiometricAvailability` 的注释）：
 * 合并成一句"不可用"，就把三种用户自己能解决的情况变成了死路。
 *
 * [BiometricAvailability.AVAILABLE] 返回 null——可用时不需要解释。
 */
@StringRes
fun biometricUnavailableRes(availability: BiometricAvailability): Int? = when (availability) {
    BiometricAvailability.AVAILABLE -> null
    BiometricAvailability.NO_HARDWARE -> R.string.biometric_state_no_hardware
    BiometricAvailability.HARDWARE_UNAVAILABLE -> R.string.biometric_state_hardware_unavailable
    BiometricAvailability.NONE_ENROLLED -> R.string.biometric_state_none_enrolled
    BiometricAvailability.SECURITY_UPDATE_REQUIRED -> R.string.biometric_state_security_update
    BiometricAvailability.UNSUPPORTED -> R.string.biometric_state_unsupported
    BiometricAvailability.NOT_ENABLED_BY_USER -> R.string.biometric_state_not_enabled
}

/**
 * 要不要给"去系统设置录入"那个按钮。
 *
 * 只有 [BiometricAvailability.NONE_ENROLLED] 给：它是唯一一档"跳过去就能当场解决"的。
 * `SECURITY_UPDATE_REQUIRED` 跳到系统更新页帮不上忙（更新什么时候推到这台机器不由用户决定），
 * `HARDWARE_UNAVAILABLE` 是暂时性的、文案里已经说了稍后再试，
 * 给一个点过去发现无事可做的按钮比不给更糟。
 */
fun offersBiometricEnroll(availability: BiometricAvailability): Boolean =
    availability == BiometricAvailability.NONE_ENROLLED

/** 倒计时的 `mm:ss`。用 `Locale.ROOT` 固定成 ASCII 数字，倒计时不需要跟着语言变。 */
fun formatCountdown(totalSeconds: Int): String {
    val safe = if (totalSeconds < 0) 0 else totalSeconds
    return String.format(java.util.Locale.ROOT, "%02d:%02d", safe / 60, safe % 60)
}
