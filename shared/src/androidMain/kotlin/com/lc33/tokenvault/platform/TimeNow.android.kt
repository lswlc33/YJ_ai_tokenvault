package com.lc33.tokenvault.platform

import android.os.SystemClock

actual fun nowMillis(): Long = System.currentTimeMillis()

/**
 * 单调时钟用 `SystemClock.elapsedRealtimeNanos` 而不是 `System.nanoTime`：
 * 后者在设备深度睡眠期间不前进，而 [AutoLocker] 的"切后台 N 秒后锁"要跨睡眠计数
 * （§7.4）。阶段4 之前 AutoLocker 直接拿 elapsedRealtime，语义原样保留。
 */
actual fun monotonicNanoTime(): Long = SystemClock.elapsedRealtimeNanos()

/**
 * 进程启动时抓的那一对 (墙上时刻, elapsedRealtime) 锚点。
 *
 * 为什么要有锚点：[sleepAwareElapsedMillis] 收到的是一枚**墙上**时刻，而 Android 上
 * "含睡眠的真实流逝"只有 elapsedRealtime 给得出。两者之间的换算关系就是"进程启动那一刻
 * 它们各自是多少"——elapsedRealtime 与墙上时钟只在用户改系统时间 / NTP 校时那一刻分叉，
 * 所以这一对锚点足以把任意墙上时刻搬到 elapsedRealtime 域里。
 */
private val anchorElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
private val anchorWallMs: Long = System.currentTimeMillis()

actual fun sleepAwareElapsedMillis(sinceEpochMs: Long): Long {
    val sinceInElapsedRealtime = anchorElapsedRealtimeMs + (sinceEpochMs - anchorWallMs)
    return (SystemClock.elapsedRealtime() - sinceInElapsedRealtime).coerceAtLeast(0L)
}
