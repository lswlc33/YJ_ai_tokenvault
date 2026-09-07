package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.data.entity.ProbeRunEntity
import com.lc33.tokenvault.screens.model.ProbeRunSummary

/**
 * `probe_runs` 最新一行 → "上次探测"摘要（时间戳，不碰资源，红线 19）。
 *
 * 仪表盘摘要卡与探测明细页共用这一个映射：同一个数字在两处各算一遍，迟早对不上
 * （CLAUDE.md 的原话）。相对时间与耗时文案由页面用 `relativeLabel` / `durationSeconds` 现算。
 *
 * 阶段3：`UiMapping.kt` 的其余纯函数已迁 commonMain，只有这个函数接收 Room 实体
 * （`data/entity/ProbeRunEntity`，data 层专属），所以留在 app 侧。
 */
fun ProbeRunEntity.toSummary(): ProbeRunSummary = ProbeRunSummary(
    finishedAtMs = finishedAt ?: startedAt,
    durationMs = ((finishedAt ?: startedAt) - startedAt).coerceAtLeast(0),
    total = total,
    succeeded = okCount,
    failed = failCount,
    // 未探测 = total - done（§8.5：超预算 / 撞 host 预算 / 锁定被标 SKIPPED 的不算 done）。
    skipped = total - done,
)
