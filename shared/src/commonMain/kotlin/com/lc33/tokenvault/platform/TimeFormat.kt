package com.lc33.tokenvault.platform

/**
 * 本地化的短日期字符串（"Sep 7, 2026" / "2026年9月7日"）。
 *
 * "用什么格式写日期"是语言与地区的事（红线 20），KMP 的 kotlinx-datetime 只给
 * ISO 格式，做不到本地化，所以这里落成 expect/actual：Android/JVM 用
 * `java.time.format.DateTimeFormatter.ofLocalizedDate`，iOS 用 NSDateFormatter。
 *
 * 这一串只用于给人看（相对时间里"超过 30 天"的绝对日期），不参与任何计算。
 */
expect fun absoluteDateLabel(epochMillis: Long): String
