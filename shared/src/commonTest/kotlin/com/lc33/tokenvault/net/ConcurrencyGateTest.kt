package com.lc33.tokenvault.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 并发闸（§13.4「最大并发数」）。
 *
 * 放在 commonTest 而不是 jvmTest：这类"锁 + 队列 + 取消"的东西，真正的风险是它在
 * Kotlin/Native 的协程调度上表现不一样（iOS 没有共享可变对象的旧内存模型问题，但
 * 挂起/恢复的交错时机不同），而 `:shared:iosSimulatorArm64Test` 会在 CI 的 macOS 上
 * 把这一份再跑一遍。
 *
 * 时间全是 `runTest` 的虚拟时钟：这些用例不等真实超时，也不会因为机器快慢而飘。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyGateTest {

    @Test
    fun `同时最多只有设定数在飞`() = runTest {
        val gate = ConcurrencyGate(3)
        val hold = CompletableDeferred<Unit>()
        var live = 0
        var peak = 0

        val jobs = (1..10).map {
            launch {
                gate.withPermit {
                    live++
                    peak = maxOf(peak, live)
                    hold.await()
                    live--
                }
            }
        }
        runCurrent()
        assertEquals(3, live, "第 4 个不该挤进去")
        assertEquals(3, gate.inFlightCount())

        hold.complete(Unit)
        jobs.joinAll()
        assertEquals(0, gate.inFlightCount(), "跑完必须归零，漏一个名额就永久少一格")
        assertEquals(3, peak, "全程峰值不该越过上限")
    }

    @Test
    fun `等待者按先来后到放行`() = runTest {
        val gate = ConcurrencyGate(1)
        val hold = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        val holder = launch { gate.withPermit { hold.await() } }
        runCurrent()

        // 每个等待者进去后都占着名额直到自己的门铃响，这样 order 记录的就是**放行顺序**。
        val bells = (1..4).map { CompletableDeferred<Unit>() }
        val waiters = bells.mapIndexed { i, bell ->
            launch {
                gate.withPermit {
                    order += i + 1
                    bell.await()
                }
            }
        }
        runCurrent()
        assertEquals(emptyList(), order, "holder 还占着唯一名额，谁都不该进去")

        hold.complete(Unit)
        runCurrent()
        assertEquals(listOf(1), order, "只有一个名额时，该先排队的先走")

        bells.forEach { bell ->
            bell.complete(Unit)
            runCurrent()
        }
        waiters.joinAll()
        assertEquals(listOf(1, 2, 3, 4), order)
    }

    @Test
    fun `取消排队的请求不会漏出一个许可`() = runTest {
        val gate = ConcurrencyGate(1)
        val hold = CompletableDeferred<Unit>()
        val holder = launch { gate.withPermit { hold.await() } }
        runCurrent()

        val queued = (1..3).map { launch { gate.withPermit { } } }
        runCurrent()
        queued[1].cancel()
        runCurrent()

        hold.complete(Unit)
        holder.join()
        queued.forEach { it.join() }

        assertEquals(0, gate.inFlightCount(), "被取消的那个如果没把名额还回去，队列就卡死了")
        var ran = false
        gate.withPermit { ran = true }
        assertTrue(ran, "取消之后闸必须还能用")
    }

    @Test
    fun `取消正在飞的请求立刻把名额交给下一个`() = runTest {
        val gate = ConcurrencyGate(1)
        val first = launch { gate.withPermit { } }
        runCurrent()
        assertTrue(first.isCompleted, "没人抢的时候该直接过")

        var slowIn = 0
        var nextIn = 0
        val slow = launch {
            gate.withPermit {
                slowIn++
                awaitCancellation()
            }
        }
        val waiter = launch { gate.withPermit { nextIn++ } }
        runCurrent()
        assertEquals(1, slowIn)
        assertEquals(0, nextIn, "名额被占着，排队的进不来")

        slow.cancelAndJoin()
        runCurrent()
        assertEquals(1, nextIn, "取消在飞的那个之后，排队的那个要立刻接上")
        assertTrue(waiter.isCompleted)
        assertEquals(0, gate.inFlightCount())
    }

    @Test
    fun `调小上限不抢占已在飞的请求`() = runTest {
        val gate = ConcurrencyGate(4)
        val hold = CompletableDeferred<Unit>()
        val running = (1..4).map { launch { gate.withPermit { hold.await() } } }
        runCurrent()
        assertEquals(4, gate.inFlightCount())

        gate.setLimit(1)
        val fifth = launch { gate.withPermit { } }
        runCurrent()
        assertFalse(fifth.isCompleted, "调小之后新请求要等")
        assertEquals(4, gate.inFlightCount(), "已在飞的不能被掐掉：半路掐断比多跑几个更糟")

        hold.complete(Unit)
        (running + fifth).forEach { it.join() }
        assertEquals(0, gate.inFlightCount())
    }

    @Test
    fun `调大上限立刻放行排队的请求`() = runTest {
        val gate = ConcurrencyGate(1)
        val hold = CompletableDeferred<Unit>()
        val holder = launch { gate.withPermit { hold.await() } }
        runCurrent()

        val queued = (1..3).map { launch { gate.withPermit { } } }
        runCurrent()
        queued.forEach { assertFalse(it.isCompleted) }

        // 一次发满：上限从 1 到 4，排着的三个当下就该走，不用等谁再释放一次。
        gate.setLimit(4)
        runCurrent()
        queued.forEach { assertTrue(it.isCompleted, "调大后要立刻放行") }

        hold.complete(Unit)
        holder.join()
        assertEquals(0, gate.inFlightCount())
    }

    @Test
    fun `反复改上限不会把队列卡死`() = runTest {
        val gate = ConcurrencyGate(2)
        val jobs = (1..20).map { launch { gate.withPermit { delay(10) } } }
        gate.setLimit(8)
        gate.setLimit(1)
        gate.setLimit(32)
        gate.setLimit(2)
        jobs.joinAll()
        assertEquals(0, gate.inFlightCount())
    }

    @Test
    fun `上限小于 1 也当 1 用而不是把网络掐死`() = runTest {
        val gate = ConcurrencyGate(0)
        var ran = false
        gate.withPermit { ran = true }
        assertTrue(ran, "0 或负数只能退化成串行，不能一个请求都发不出去")
        assertEquals(0, gate.inFlightCount())

        gate.setLimit(-5)
        assertEquals(1, gate.currentLimit())
    }
}
