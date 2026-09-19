package com.lc33.tokenvault.engine

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * 自建协程作用域上的最后一道兜底，接住"冒到顶但没人接"的异常。
 *
 * 只 [com.lc33.tokenvault.di.coreModule] 的 appScope 与 [ProbeEngine.scope] 用它，因为这两处
 * 恰好是最不该杀进程的地方：前者是启动时首次建库那一发加三条常驻订阅，抛出来就是每次开机
 * 都崩，而且 `BootCorrupt` 那套恢复界面只兜 boot 文件、救不了库；后者是探测轮次的收尾，
 * 抛出来除了崩还会把进度永久停在"正在请求 N/M"。
 *
 * **为什么不落 `audit_log`**：会走到这一道的头号原因就是"库写不进去"，在这里再发一次写等于
 * 火上浇水，而那一次写又落回同一个 handler——自己转成死循环。所以这一道只负责不崩，
 * 说清楚"哪件事没做成"留给调用点自己的 try-catch：探测轮次由 [ProbeEngine] 的 runRound 记一条
 * ERROR，设置写入由 [com.lc33.tokenvault.ui.shell.SettingsFailures] 投一条提示。
 */
internal val scopeCrashGuard = CoroutineExceptionHandler { _, _ -> }
