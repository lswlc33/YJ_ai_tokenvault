package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 14：探测编排（计划.md §14.3，§8.5、红线 29）。
 *
 * 用 `runTest` 虚拟时间验证编排的纯逻辑语义：
 * - 逐项推流（结果按顺序）。
 * - 取消真的停（协程取消后不再发请求）。
 * - 总预算超时 / 撞 host 预算 / 该 host 已 429 → 剩余项**推一条带 SkipReason 的
 *   SKIPPED 结果**（进度与"本轮未探测"分组都靠它收尾，静默丢弃会让 done 永远追不上 total）。
 * - 429 后立即停止该 host 后续请求（红线 29），并把分类器读到的 Retry-After 递给门闸。
 * - **不同 host 并行、同 host 串行**：编排器自己不睡（间隔的唯一权威是 HostGate），
 *   两 host 并发的总耗时约等于单 host，而不是两者之和。
 */
class ProbeOrchestratorTest {

    private fun task(
        id: String,
        host: String = "a.example.com",
        level: ProbeLevel = ProbeLevel.L1_REACHABILITY,
        keyId: Long? = null,
        allowInsecure: Boolean = false,
        timeoutMs: Long? = null,
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
        allowInsecure = allowInsecure,
        timeoutMs = timeoutMs,
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
                awaitCancellation()
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
    fun `总预算超时后剩余项标 SKIPPED 并推流`() = runTest {
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
        assertEquals(2, callCount)
        // 但 10 项全部有下文：2 条真实结果 + 8 条 SKIPPED，进度才能走到终点。
        assertEquals(10, results.size)
        assertEquals(listOf(ProbeOutcome.SUCCESS, ProbeOutcome.SUCCESS), results.take(2).map { it.outcome })
        assertEquals(ProbeOutcome.SKIPPED, results[2].outcome)
        results.drop(2).forEach { assertEquals(SkipReason.TotalBudgetExhausted, it.skipReason) }
        assertNull(results[2].httpStatus) // 跳过的项没发请求，不该有状态码
    }

    @Test
    fun `每 host 请求预算生效，超出的推 SKIPPED`() = runTest {
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
        assertEquals(5, results.size)
        results.take(2).forEach { assertEquals(ProbeOutcome.SUCCESS, it.outcome) }
        results.drop(2).forEach {
            assertEquals(ProbeOutcome.SKIPPED, it.outcome)
            assertEquals(SkipReason.HostBudgetExhausted, it.skipReason)
        }
    }

    @Test
    fun `429 后停发该 host 后续请求并把 Retry-After 递给门闸`() = runTest {
        var callCount = 0
        var notifiedHost: String? = null
        var notifiedRetryAfter: Long? = null
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { _, _ ->
                callCount++
                if (callCount == 2) ProbeResponse(status = 429, body = """{"retry_after":30}""")
                else okResponse()
            },
            onRateLimited = { host, retryAfterMs ->
                notifiedHost = host
                notifiedRetryAfter = retryAfterMs
            },
            budget = ProbeBudget(totalBudgetMs = 10_000),
            nowMillis = { 0L },
        )

        val results = orchestrator.run((1..5).map { task("t$it") }).toList()

        assertEquals(2, callCount)
        assertEquals(5, results.size) // 3..5 也有下文：SKIPPED(HostRateLimited)
        assertEquals(ProbeOutcome.SUCCESS, results[0].outcome)
        assertEquals(ProbeOutcome.RATE_LIMITED, results[1].outcome)
        assertNull(results[1].health) // 红线 11：429 不改写 health
        results.drop(2).forEach {
            assertEquals(ProbeOutcome.SKIPPED, it.outcome)
            assertEquals(SkipReason.HostRateLimited, it.skipReason)
        }
        assertEquals("a.example.com", notifiedHost)
        // 分类器从 body 读出的 retry_after 原样递给门闸，退避不该白读一次。
        assertEquals(30_000L, notifiedRetryAfter)
    }

    @Test
    fun `两个 host 并发总耗时远小于串行`() = runTest {
        // 每个请求真睡 1000ms（虚拟时间）。两 host 各一个任务：
        // 真并行的总耗时 ≈ 1000；"锁内睡觉"的假并行会是 2000。
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { _, _ ->
                delay(1_000)
                okResponse()
            },
            nowMillis = { testScheduler.currentTime },
            budget = ProbeBudget(totalBudgetMs = 100_000),
        )

        val results = orchestrator.run(
            listOf(task("a", host = "a.example.com"), task("b", host = "b.example.com")),
        ).toList()

        assertEquals(2, results.count { it.outcome == ProbeOutcome.SUCCESS })
        assertTrue(
            "总耗时 ${testScheduler.currentTime}ms 应在并发口径内（串行会是 2000ms）",
            testScheduler.currentTime in 1_000..1_500,
        )
    }

    @Test
    fun `同 host 任务保持串行且编排器自己不睡`() = runTest {
        // 编排器不再代门闸睡觉：唯一的耗时来自 transport 本身。
        // 同 host 两个 100ms 的请求恰好 200ms 收尾（没有被编排器额外插入的间隔）。
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { _, _ ->
                delay(100)
                okResponse()
            },
            nowMillis = { testScheduler.currentTime },
            budget = ProbeBudget(totalBudgetMs = 10_000),
        )

        orchestrator.run(listOf(task("t1"), task("t2"))).toList()

        assertEquals(200L, testScheduler.currentTime)
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

        // a host 预算 1 → 只发 a1；b host 独立 → 发 b1。a2 推 SKIPPED 而不是消失。
        assertEquals(2, callCount)
        assertEquals(3, results.size)
        val skipped = results.single { it.outcome == ProbeOutcome.SKIPPED }
        assertEquals("a2", skipped.taskId)
        assertEquals(SkipReason.HostBudgetExhausted, skipped.skipReason)
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

    @Test
    fun `密钥的 insecure 标记原样透传给 transport`() = runTest {
        var received: Boolean? = null
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { _, allowInsecure ->
                received = allowInsecure
                okResponse()
            },
            nowMillis = { 0L },
        )

        orchestrator.run(listOf(task("http", allowInsecure = true))).toList()
        assertEquals(true, received)
    }

    @Test
    fun `每把密钥的超时随任务透传给 transport`() = runTest {
        var received: Long? = null
        val orchestrator = ProbeOrchestrator(
            transport = ProbeTransport { request: ProbeRequest, _ ->
                received = request.timeoutMs
                okResponse()
            },
            nowMillis = { 0L },
        )

        orchestrator.run(listOf(task("t", timeoutMs = 5_000))).toList()
        assertEquals(5_000L, received)
    }
}
