package com.lc33.tokenvault.net

import com.lc33.tokenvault.domain.HttpConcurrencyPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 应用内 HTTP 的并发闸：同时最多 [limit] 个请求在网线上，多出来的在队列里等。
 *
 * 为什么不用 `kotlinx.coroutines.sync.Semaphore`：它的许可数**构造时定死**，而这个上限
 * 是设置页里能改的。"换档就重建一把 Semaphore"看着省事，实际有三个坑：换档瞬间新旧两代
 * 各自数着自己的在飞数（超额没有上界）；队列被丢掉，先排队的人可能永远等不到一次
 * `release`（新 semaphore 的释放不会叫醒旧队列）；以及"inFlight ≤ limit"这条不变量
 * 在测试里根本写不出来。所以这里自己记账。
 *
 * 四条不变量，每条都对应一个具体机制：
 *
 * 1. **任何时刻 `inFlight ≤ limit`。** 发放是**手递手**的：[grantLocked] 在同一个锁内
 *    出队 → 加计数 → `complete`，名额从走掉的那个人直接交到下一个人手上，不存在
 *    "广播一下让大家自己抢"——那种写法在**调大上限**时一定会超额（一次唤醒 N 个、
 *    其实只有 M 个空位）。
 * 2. **锁内绝不挂起。** 临界区只有 `Int` 加减和 `ArrayDeque` 摘挂，`complete` 也不挂起；
 *    所以一个等了 35 秒的请求不会把闸本身冻住。与 `HostGate` 同一套纪律。
 * 3. **取消不漏名额。** [acquire] 的 catch 里把自己从队列摘掉：摘得到 = 名额从来不是我的，
 *    什么都不欠；摘不到 = [grantLocked] 已经把名额算在我头上，必须交回去。
 *    清理包在 `NonCancellable` 里不是装饰——`Mutex.withLock` 在调用者已取消时会**在拿到锁之前**
 *    就抛 CancellationException，不包的话既摘不了队也还不回名额，队列会永久卡住。
 * 4. **对外只有 [withPermit] 与 [setLimit]。** 裸的 acquire/release 是私有的，
 *    于是"重复 release""没拿到就 release"在类型上就写不出来，计数不可能变负。
 *
 * 锁顺序（这是本文件唯一可能把整个应用挂死的途径，写下来给后来的人核）：
 * `ProbeOrchestrator` 的 per-host 锁 → `HostGate` 的状态锁 → 这里的 [lock]。
 * 反向的持有从不发生：`withPermit` 块里不会再取 per-host 锁，两把内层锁也都不跨等待持有。
 */
class ConcurrencyGate(initialLimit: Int = HttpConcurrencyPolicy.DEFAULT) {

    /**
     * 一个排队位。
     *
     * 刻意**不是** `data class`：出队按身份（`===`）匹配，结构相等会把别人的名额摘走。
     */
    private class Waiter {
        val granted = CompletableDeferred<Unit>()
    }

    private val lock = Mutex()
    private var limit = initialLimit.coerceAtLeast(1)
    private var inFlight = 0
    private val waiters = ArrayDeque<Waiter>()

    /**
     * 换档。调大时立刻放行能走的人（见 [grantLocked] 的"一次发满"）；调小时**不抢占**
     * 已经在飞的请求——把它们半路掐掉比多跑几个更糟，新请求自然会在队列外等着。
     */
    suspend fun setLimit(next: Int) {
        lock.withLock {
            limit = next.coerceAtLeast(1)
            grantLocked()
        }
    }

    /** 当前上限。锁内读，不做 `@Volatile` 字段：省掉"忘了 import `kotlin.concurrent.Volatile` 只在 iOS 编译里炸"那一类坑。 */
    suspend fun currentLimit(): Int = lock.withLock { limit }

    /** 现在有几个在飞。给测试与诊断用。 */
    suspend fun inFlightCount(): Int = lock.withLock { inFlight }

    /**
     * 拿一个名额跑 [block]，跑完（含异常与取消）归还。
     *
     * 名额**只包住真的在网线下的那段**：调用方要保证 `HostGate` 的间隔等待在它外面。
     * 反过来的话，一家在等自己那 800ms（撞过 429 是 8s）就会占着一个名额什么都不干，
     * 默认 8 档下变成"一家被限流、整轮跟着爬"，2 档下几乎等于把应用网络串行化。
     */
    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { release() }
        }
    }

    private suspend fun acquire() {
        val waiter = Waiter()
        val admitted = lock.withLock {
            // 队列非空就不插队：FIFO。正常情况下"有排队却有空位"不会存在（grantLocked 一次发满），
            // 这一条是把它变成不可能，而不是依赖那个推论。
            if (waiters.isEmpty() && inFlight < limit) {
                inFlight++
                true
            } else {
                waiters.addLast(waiter)
                false
            }
        }
        if (admitted) return

        try {
            waiter.granted.await()
        } catch (t: Throwable) {
            val wasStillQueued = withContext(NonCancellable) { lock.withLock { waiters.remove(waiter) } }
            if (!wasStillQueued) withContext(NonCancellable) { release() }
            throw t
        }
    }

    private suspend fun release() {
        lock.withLock {
            inFlight--
            grantLocked()
        }
    }

    /** 只能在持锁时调用。一次发满：上限刚从 1 调到 8，排着的 7 个现在就该走。 */
    private fun grantLocked() {
        while (inFlight < limit) {
            val next = waiters.removeFirstOrNull() ?: return
            inFlight++
            next.granted.complete(Unit)
        }
    }
}
