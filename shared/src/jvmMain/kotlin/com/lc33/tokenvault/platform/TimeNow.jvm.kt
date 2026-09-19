package com.lc33.tokenvault.platform

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun monotonicNanoTime(): Long = System.nanoTime()

/**
 * JVM（只用来在本机跑单测）：桌面系统的 uptime 本来就含睡眠，没有比墙钟更好的信息源，
 * 所以与 iOS 的 actual 取同一条路。单测里真正用到的退避时间都由注入的假时钟给。
 */
actual fun sleepAwareElapsedMillis(sinceEpochMs: Long): Long =
    (nowMillis() - sinceEpochMs).coerceAtLeast(0L)
