package com.lc33.tokenvault.platform

import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * iOS：用 `NSProcessInfo.systemUptime`（单调时钟，秒）转纳秒。
 *
 * 注意它**不含设备睡眠时间**——这正是 [sleepAwareElapsedMillis] 存在的原因，
 * 不要拿它去算"用户离开了多久"。跑 KDF 基准要的是"这台机器算了多久"，用它是对的。
 */
actual fun monotonicNanoTime(): Long =
    (NSProcessInfo.processInfo.systemUptime * 1_000_000_000.0).toLong()

/**
 * iOS 上唯一含睡眠的可用信息就是墙钟：`systemUptime` 睡着不前进、`mach_absolute_time` 同源。
 *
 * 于是接受"改系统时间可以绕过后台锁定时长"这个代价，换来"离开 N 分钟锁定"真的生效。
 * 理由与代价都写在 commonMain 的 expect 声明上，这里不重复。
 */
actual fun sleepAwareElapsedMillis(sinceEpochMs: Long): Long =
    (nowMillis() - sinceEpochMs).coerceAtLeast(0L)
