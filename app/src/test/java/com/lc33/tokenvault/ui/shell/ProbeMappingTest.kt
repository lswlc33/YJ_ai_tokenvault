package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.data.entity.ProbeRunEntity
import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.probe.ProbeItemResult
import com.lc33.tokenvault.screens.model.UiHealth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 探测明细页的两条映射（§13.4）。
 *
 * 值得单独测的理由：这两条映射都在表达红线 11——瞬时失败（网络 / 429 / 5xx）**不改写
 * 持久结论**。映射错了不会崩，只会让明细页把一个"这轮没测成"的项画成"密钥坏了"，
 * 于是用户去换一把其实没问题的钥匙。
 */
class ProbeMappingTest {

    private fun result(
        outcome: ProbeOutcome,
        health: KeyHealth? = null,
    ) = ProbeItemResult(
        taskId = "t",
        providerId = 1,
        providerName = "p",
        keyId = null,
        level = ProbeLevel.L1_REACHABILITY,
        outcome = outcome,
        health = health,
    )

    // ---------------------------------------------------------------- toUiHealth

    @Test
    fun `有判定结论就用它`() {
        assertEquals(UiHealth.Error, result(ProbeOutcome.CONCLUSIVE_FAIL, KeyHealth.UNAUTHORIZED).toUiHealth())
        assertEquals(UiHealth.Warn, result(ProbeOutcome.CONCLUSIVE_FAIL, KeyHealth.INSUFFICIENT).toUiHealth())
        assertEquals(UiHealth.Ok, result(ProbeOutcome.SUCCESS, KeyHealth.OK).toUiHealth())
    }

    @Test
    fun `瞬时失败没有健康结论时按 outcome 给颜色`() {
        // 红线 11：这些 outcome 不带 health，映射要给出"本轮视角"的颜色，而不是 Unknown。
        assertEquals(UiHealth.Warn, result(ProbeOutcome.NETWORK_ERROR).toUiHealth())
        assertEquals(UiHealth.Warn, result(ProbeOutcome.RATE_LIMITED).toUiHealth())
        assertEquals(UiHealth.Warn, result(ProbeOutcome.UPSTREAM_ERROR).toUiHealth())
    }

    @Test
    fun `跳过与取消是未探测`() {
        assertEquals(UiHealth.Unknown, result(ProbeOutcome.SKIPPED).toUiHealth())
        assertEquals(UiHealth.Unknown, result(ProbeOutcome.CANCELLED).toUiHealth())
    }

    @Test
    fun `判定性失败却没有健康结论时兜底画红`() {
        // 这种组合不该出现（分类器要么给 health 要么给瞬时 outcome），但真出现了宁可多告警。
        assertEquals(UiHealth.Error, result(ProbeOutcome.CONCLUSIVE_FAIL).toUiHealth())
    }

    // ---------------------------------------------------------------- toSummary

    @Test
    fun `摘要的未探测等于总数减已完成`() {
        val run = ProbeRunEntity(
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
        assertEquals(1000 + 2000, summary.finishedAtMs) // finishedAt
        assertEquals(2000, summary.durationMs)
        assertEquals(10, summary.total)
        assertEquals(5, summary.succeeded)
        assertEquals(2, summary.failed)
        assertEquals(3, summary.skipped) // 10 - 7
    }

    @Test
    fun `没结束的轮次用开始时间兜底，耗时不为负`() {
        val run = ProbeRunEntity(
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
