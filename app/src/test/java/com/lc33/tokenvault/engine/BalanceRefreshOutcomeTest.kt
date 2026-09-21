package com.lc33.tokenvault.engine

import com.lc33.tokenvault.domain.model.BalanceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一趟余额刷新的结果摘要。
 *
 * 钉住的是那个把"全挂"报成"成功"的老毛病：`refreshAll` 原来数的是
 * `refreshKey(key) != null` 的个数，而**失败快照也是非 null**，于是十把全挂它报 10，
 * 首页在那一刻念"余额已刷新"。现在成功与失败分开数，且四种 0 各有名字。
 */
class BalanceRefreshOutcomeTest {

    private fun ok(amount: Double) = BalanceSnapshot(amount = amount, currency = "USD")

    private fun fail(error: String, raw: String? = null) =
        BalanceSnapshot(currency = BalanceSnapshot.UNKNOWN_CURRENCY, error = error, raw = raw)

    @Test
    fun `失败快照不算成功`() {
        val outcome = BalanceRefreshOutcome.of(listOf(ok(1.0), fail("http 401"), fail("no_json")))
        assertEquals(3, outcome.attempted)
        assertEquals(1, outcome.succeeded)
        assertEquals(2, outcome.failed)
        assertTrue(outcome.partiallyFailed)
        assertFalse(outcome.allFailed)
        assertTrue(outcome.needsAttention)
    }

    @Test
    fun `全挂与部分挂分得开`() {
        val all = BalanceRefreshOutcome.of(listOf(fail("http 401"), fail("http 401")))
        assertTrue(all.allFailed)
        assertFalse(all.partiallyFailed)
        assertEquals("http 401", all.failedReason)

        val none = BalanceRefreshOutcome.of(listOf(ok(2.0)))
        assertFalse(none.needsAttention)
    }

    @Test
    fun `没试与试了全挂是两件事`() {
        assertTrue(BalanceRefreshOutcome.NONE.nothingAttempted)
        assertFalse(BalanceRefreshOutcome.NONE.allFailed)

        val aborted = BalanceRefreshOutcome.aborted("db is busy")
        assertTrue(aborted.aborted)
        assertFalse(aborted.nothingAttempted)
        // 整趟没跑起来也要出声——它和"没配余额查询"在界面上必须不是一句话。
        assertTrue(aborted.needsAttention)
        assertEquals("db is busy", aborted.failedReason)
    }

    @Test
    fun `整趟没给原因时给一个兜底码而不是 null`() {
        assertEquals("refresh_failed", BalanceRefreshOutcome.aborted(null).failedReason)
        assertEquals("refresh_failed", BalanceRefreshOutcome.aborted("   ").failedReason)
    }

    @Test
    fun `null 元素不计入分母`() {
        // `BalanceKind.NONE` / 探测开关关着的 Key 压根不该被试，返回 null 表示"没跑"。
        val outcome = BalanceRefreshOutcome.of(listOf(ok(1.0), null, null))
        assertEquals(1, outcome.attempted)
        assertEquals(1, outcome.succeeded)
    }

    @Test
    fun `上游原话从脱敏后的原文里抽出来`() {
        val outcome = BalanceRefreshOutcome.of(
            listOf(fail("http 401", """{"success":false,"message":"安全访问令牌已失效"}""")),
        )
        assertEquals("安全访问令牌已失效", outcome.failedHint)
    }

    @Test
    fun `第一个失败者的原因优先`() {
        val outcome = BalanceRefreshOutcome.of(listOf(ok(1.0), fail("missing_quota"), fail("http 500")))
        assertEquals("missing_quota", outcome.failedReason)
    }
}
