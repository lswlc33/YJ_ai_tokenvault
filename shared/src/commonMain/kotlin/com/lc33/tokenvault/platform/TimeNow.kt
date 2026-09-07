package com.lc33.tokenvault.platform

/**
 * 当前 epoch 毫秒（红线 20：当前时间算平台能力，不直接用 System.currentTimeMillis）。
 *
 * UI 层（ViewModel）需要"现在"来算相对时间，落成 expect/actual 而不是在 commonMain
 * 里直接调 `System.currentTimeMillis()`（native 无 System 类）。
 */
expect fun nowMillis(): Long

/**
 * 单调时钟纳秒（红线 20）。KDF 基准测试用（`Pbkdf2Kdf.benchmark`），需要单调时钟
 * 而非墙上时间（墙上时间会回拨）。JVM 用 `System.nanoTime`，iOS 用 `mach_absolute_time`。
 */
expect fun monotonicNanoTime(): Long
