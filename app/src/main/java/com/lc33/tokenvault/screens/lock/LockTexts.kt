package com.lc33.tokenvault.screens.lock

import androidx.annotation.StringRes
import com.lc33.tokenvault.R

/**
 * 锁闸这几页的"状态 → 文案"映射。
 *
 * 单独收在一个文件里是红线 17 的落实：同一种状态在引导页与解锁页必须是同一句话。
 * 分散在两个页面里各写一遍 `when`，迟早出现"设 PIN 时说两次不一致、改 PIN 时说输入有误"。
 *
 * 阶段1 迁移后生物识别已删，只剩 PIN 相关的文案映射。
 */

@StringRes
fun pinErrorRes(error: PinError): Int = when (error) {
    PinError.Mismatch -> R.string.pin_error_mismatch
    PinError.TooSimple -> R.string.pin_error_too_simple
    PinError.Wrong -> R.string.pin_error_wrong
}

/** 倒计时的 `mm:ss`。用 `Locale.ROOT` 固定成 ASCII 数字，倒计时不需要跟着语言变。 */
fun formatCountdown(totalSeconds: Int): String {
    val safe = if (totalSeconds < 0) 0 else totalSeconds
    return String.format(java.util.Locale.ROOT, "%02d:%02d", safe / 60, safe % 60)
}
