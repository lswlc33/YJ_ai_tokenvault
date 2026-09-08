package com.lc33.tokenvault.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Android：用 [Vibrator] 给触觉反馈。
 *
 * API 31+ 用 [VibratorManager] 取默认 vibrator，之前直接 [Context.getSystemService]。
 * 震动需要 `android.permission.VIBRATE`，该权限为 normal 级、安装即授予。
 */
actual object Haptics {
    private val vibrator: Vibrator?
        get() {
            val ctx = appContext
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }

    actual fun tap() {
        vibrateSafely(10)
    }

    actual fun impact() {
        vibrateSafely(25)
    }

    /** 触觉是增强反馈，不能因为设备策略/权限异常把主流程打崩。 */
    private fun vibrateSafely(durationMs: Long) {
        runCatching {
            vibrator
                ?.takeIf { it.hasVibrator() }
                ?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
