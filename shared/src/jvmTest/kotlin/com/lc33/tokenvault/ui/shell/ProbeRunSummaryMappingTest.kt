package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.repo.ProbeRun
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 探测明细页的 `ProbeRun.toSummary` 映射（§13.4）。
 *
 * 阶段3：`toSummary` 随读路径迁 commonMain（接收纯 Kotlin 的 [ProbeRun]）。
 */
class ProbeRunSummaryMappingTest {

    @Test
    fun `摘要的未探测等于总数减已完成`() {
        val run = ProbeRun(
            id = 1,
            scope = "all",
            startedAt = 1000,
            finishedAt = 3000,
            total = 10,
            done = 7,
            okCount = 5,
            failCount = 2,
        )
        val summary = run.toSummary()
        assertEquals(3000, summary.finishedAtMs) // finishedAt
        assertEquals(2000, summary.durationMs)
        assertEquals(10, summary.total)
        assertEquals(5, summary.succeeded)
        assertEquals(2, summary.failed)
        assertEquals(3, summary.skipped) // 10 - 7
    }

    @Test
    fun `没结束的轮次用开始时间兜底，耗时不为负`() {
        val run = ProbeRun(
            id = 1,
            scope = "all",
            startedAt = 5000,
            finishedAt = null,
            total = 0,
            done = 0,
        )
        val summary = run.toSummary()
        assertEquals(5000, summary.finishedAtMs)
        assertEquals(0, summary.durationMs)
    }
}
