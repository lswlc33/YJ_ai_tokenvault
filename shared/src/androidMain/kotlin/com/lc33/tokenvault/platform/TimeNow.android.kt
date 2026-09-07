package com.lc33.tokenvault.platform

import android.os.SystemClock

actual fun nowMillis(): Long = System.currentTimeMillis()

/**
 * 单调时钟用 `SystemClock.elapsedRealtimeNanos` 而不是 `System.nanoTime`：
 * 后者在设备深度睡眠期间不前进，而 [AutoLocker] 的"切后台 N 秒后锁"要跨睡眠计数
 * （§7.4）。阶段4 之前 AutoLocker 直接拿 elapsedRealtime，语义原样保留。
 */
actual fun monotonicNanoTime(): Long = SystemClock.elapsedRealtimeNanos()
