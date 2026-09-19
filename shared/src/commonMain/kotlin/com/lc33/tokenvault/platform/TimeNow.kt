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

/**
 * 从 [sinceEpochMs]（某一刻的墙上 epoch 毫秒）到现在，**把设备深度睡眠也算进去**过了多久。
 *
 * 为什么需要第三个时钟：[monotonicNanoTime] 在 iOS 上是 `NSProcessInfo.systemUptime`，
 * 它在设备睡眠时**不前进**——这对"跑基准要多久"是对的（睡着的这段时间确实没在算），
 * 但对"用户离开应用多久了"是错的：锁屏放兜里十分钟，systemUptime 可能只走了几秒，
 * 于是"离开 1 分钟后锁定"这一档在 iOS 上永远不生效（§7.4 的那条设置形同虚设）。
 *
 * 各端的取值与各自的代价：
 * - Android：`SystemClock.elapsedRealtime()` 的差值（含深度睡眠、且不受改系统时间影响），
 *   用进程启动时抓的 (墙上, elapsedRealtime) 锚点把 [sinceEpochMs] 换算过去；
 * - iOS：直接取墙钟差值。**代价明说**：用户把系统时间往前调就能绕过后台锁定时长，
 *   换来的是"离开 N 分钟锁定"在 iOS 上真的生效。两害相权取后者——前者需要一个
 *   愿意改自己手机时钟、且已经拿到解锁状态的攻击者，而后者保护的是所有人在所有时候的
 *   DEK 驻留时间；
 * - JVM：与 iOS 同（桌面系统的 uptime 本来就含睡眠，没有额外信息可用）。
 */
expect fun sleepAwareElapsedMillis(sinceEpochMs: Long): Long
