package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 测试 14：探测编排（计划.md §14.3，§8.5、红线 29）。
 *
 * 用 `runTest` 虚拟时间验证编排的纯逻辑语义：
 * - 逐项推流（结果按顺序）。
 * - 取消真的停（协程取消后不再发请求）。
 * - 总预算超时后剩余项不再发（调用方据此标 SKIPPED）。
 * - 每 host 请求预算生效。
 * - 429 后立即停止该 host 后续请求（红线 29）。
 * - 同 host 相邻请求间隔 ≥ 最小间隔。
 */
class ProbeOrchestratorTest {

    private fun task(
        id: String,
        host: String = "a.example.com",
        level: ProbeLevel = ProbeLevel.L1_REACHABILITY,
        keyId: Long? = null,
    ) = ProbeTask(
        id = id,
        level = level,
        providerId = 1,
        providerName = "p",
        host = host,
        protocol = Protocol.CHAT,
        keyId = keyId,
        url = "https://$host/v1/models",
        headers = listOf("Authorization" to "Bearer test"),
    )

    private fun okResponse() = ProbeResponse(status = 200, body = "{}", latencyMs = 5)

    @Test
    fun `逐项推流且顺序正确`() = runTest {
        val orchestrator = ProbeOrchestrator(transport = ProbeTransport { _, _ -> okResponse() }, nowMillis = { 0L })
        val results = orchestrator.run(listOf(task("a"), task("b"), task("c"))).toList()
        assertEquals(listOf("a", "b", "c"), results.map { it.taskId })
        assertEquals(ProbeOutcome.SUCCESS, results.first().outcome)
        assertEquals(KeyHealth.OK, results.first().health)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `取消真的停`() = runTest {
        var callCount = 0
        // 每个请求都"慢"：等一个永远不完成的信号，这样取消能打断它。
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { _, _ ->
                callCount++
                // 挂起直到被取消——模拟一个慢请求。
                kotlinx.coroutines.awaitCancellation()
                okResponse()
            },
            nowMillis = { 0L },
        )

        val job = launch {
            try {
                orchestrator.run(listOf(task("a"), task("b"), task("c"))).toList()
            } catch (_: CancellationException) {
                // 取消是预期行为
            }
        }
        testScheduler.advanceTimeBy(1)
        assertEquals(1, callCount) // 第一个请求已发出并挂起
        job.cancel()
        job.join()
        assertEquals(1, callCount) // 取消后不再发第二个
    }

    @Test
    fun `总预算超时后剩余项不再发`() = runTest {
        var now = 0L
        var callCount = 0
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { _, _ ->
                callCount++
                now += 50 // 每个请求耗 50ms
                okResponse()
            },
            nowMillis = { now },
            budget = ProbeBudget(totalBudgetMs = 100),
        )

        val results = orchestrator.run(
            (1..10).map { task("t$it") },
        ).toList()

        // 预算 100ms，每个请求 50ms → 最多发 2 个（第 3 个开始时已 100ms ≥ 预算）。
        assertEquals(2, results.size)
        assertEquals(2, callCount)
    }

    @Test
    fun `每 host 请求预算生效`() = runTest {
        var callCount = 0
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { _, _ ->
                callCount++
                okResponse()
            },
            budget = ProbeBudget(totalBudgetMs = 10_000, perHostBase = 2),
            nowMillis = { 0L },
        )

        val results = orchestrator.run(
            (1..5).map { task("t$it") },
            perHostKeyAndModelCount = mapOf("a.example.com" to 0),
        ).toList()

        assertEquals(2, callCount)
        assertEquals(2, results.size)
    }

    @Test
    fun `429 后立即停止该 host 后续请求`() = runTest {
        var callCount = 0
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { _, _ ->
                callCount++
                if (callCount == 2) ProbeResponse(status = 429, body = "error code: 1015")
                else okResponse()
            },
            budget = ProbeBudget(totalBudgetMs = 10_000),
            nowMillis = { 0L },
        )

        val results = orchestrator.run((1..5).map { task("t$it") }).toList()

        assertEquals(2, callCount)
        assertEquals(2, results.size)
        assertEquals(ProbeOutcome.SUCCESS, results[0].outcome)
        assertEquals(ProbeOutcome.RATE_LIMITED, results[1].outcome)
        assertEquals(null, results[1].health) // 红线 11：429 不改写 health
    }

    @Test
    fun `同 host 相邻请求间隔不小于最小间隔`() = runTest {
        var now = 0L
        var callCount = 0
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { _, _ ->
                callCount++
                okResponse()
            },
            nowMillis = { now },
            hostIntervalMs = { 100L },
            budget = ProbeBudget(totalBudgetMs = 10_000),
        )

        val results = orchestrator.run((1..3).map { task("t$it") }).toList()

        // 三个都发出（间隔 100ms × 2 = 200ms，总预算 10s 足够）
        assertEquals(3, results.size)
        assertEquals(3, callCount)
    }

    @Test
    fun `两个 host 的预算互相独立`() = runTest {
        var callCount = 0
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { _, _ ->
                callCount++
                okResponse()
            },
            budget = ProbeBudget(totalBudgetMs = 10_000, perHostBase = 1),
            nowMillis = { 0L },
        )

        val tasks = listOf(
            task("a1", host = "a.example.com"),
            task("a2", host = "a.example.com"),
            task("b1", host = "b.example.com"),
        )
        val results = orchestrator.run(tasks).toList()

        // a host 预算 1 → 只发 a1；b host 独立 → 发 b1。
        assertEquals(2, callCount)
        assertEquals(setOf("a1", "b1"), results.map { it.taskId }.toSet())
    }

    @Test
    fun `空任务列表不发任何请求`() = runTest {
        var callCount = 0
        val orchestrator = ProbeOrchestrator(transport = ProbeTransport { _, _ ->
            callCount++
            okResponse()
        }, nowMillis = { 0L })
        val results = orchestrator.run(emptyList()).toList()
        assertTrue(results.isEmpty())
        assertEquals(0, callCount)
    }
}
