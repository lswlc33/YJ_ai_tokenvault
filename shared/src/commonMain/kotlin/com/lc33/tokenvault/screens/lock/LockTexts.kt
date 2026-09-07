package com.lc33.tokenvault.screens.lock

import org.jetbrains.compose.resources.StringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.pin_error_mismatch
import tokenvault.shared.generated.resources.pin_error_too_simple
import tokenvault.shared.generated.resources.pin_error_wrong

/**
 * 锁闸这几页的"状态 → 文案"映射。
 *
 * 单独收在一个文件里是红线 17 的落实：同一种状态在引导页与解锁页必须是同一句话。
 * 分散在两个页面里各写一遍 `when`，迟早出现"设 PIN 时说两次不一致、改 PIN 时说输入有误"。
 *
 * 阶段1 迁移后生物识别已删，只剩 PIN 相关的文案映射。
 */

fun pinErrorRes(error: PinError): StringResource = when (error) {
    PinError.Mismatch -> Res.string.pin_error_mismatch
    PinError.TooSimple -> Res.string.pin_error_too_simple
    PinError.Wrong -> Res.string.pin_error_wrong
}

/** 倒计时的 `mm:ss`。ASCII 数字，倒计时不需要跟着语言变。KMP 无 String.format，手写补零。 */
fun formatCountdown(totalSeconds: Int): String {
    val safe = if (totalSeconds < 0) 0 else totalSeconds
    val m = safe / 60
    val s = safe % 60
    fun pad(n: Int) = if (n < 10) "0$n" else n.toString()
    return "${pad(m)}:${pad(s)}"
}
