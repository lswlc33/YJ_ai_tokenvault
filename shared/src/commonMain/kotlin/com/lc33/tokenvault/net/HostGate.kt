package com.lc33.tokenvault.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * host 级最小间隔门闸（计划.md §8.1 末尾、红线 29），**全应用唯一一处**间隔执行点。
 *
 * 并发上限（HttpClient 引擎的 per-host 上限）挡不住边缘限流——M0.5 实测 JustDoWork
 * 挂在 Cloudflare 后面，**同 host 约 2.4 秒内的第 3 个请求**就撞 `error code: 1015`。
 * 所以除了并发上限，还要给每个 host 一个**串行最小间隔**：默认 800ms，撞过 429 后加倍，
 * 上限 8s。
 *
 * **间隔只在这里睡**：编排器（`probe/ProbeOrchestrator`）过去也按同一个间隔睡一次，
 * 同 host 相邻两个请求就白等两倍；而它那次 sleep 还写在全局锁里，一个 host 在睡、
 * 别的 host 全被堵住（假并行）。现在编排器只做预算/熔断决策，节流统一交给这里，
 * 语义就是"不同 host 并行、同 host 串行最小间隔"。
 *
 * 线程安全：探测按 host 并行发请求（多个线程同时进来），而这里两份状态
 * （间隔/放行时间、429 名单）原先是裸 `mutableMapOf`，跨线程读写既不保证可见性也会
 * 丢更新。做法是**所有状态操作收进一把 [Mutex]**，`acquire` 算完"什么时候轮到"就
 * **在锁外** `delay`——锁只保护几微秒的记账，睡眠期间不持锁，别的 host 照常过。
 * 不引入 `synchronized`（JVM 专属，挡住 iOS 编译，红线 20）。
 *
 * 间隔只增不减会把一次偶发 429 变成永久降速（封顶 8s 意味着这家以后每个请求都慢 8 秒）。
 * 所以 [onSuccess] 按"连续成功次数"衰减：攒够 [SUCCESS_RESET_STREAK] 次就回到默认值。
 *
 * @param nowMillis 注入的时钟，测试用虚拟时间。**无默认值**：当前时间是平台能力
 *   （红线 20），commonMain 里连 `System` 都拿不到，注入是唯一正确姿势。
 */
class HostGate(
    private val defaultMinIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
    private val nowMillis: () -> Long,
) {

    /**
     * 每个 host 的节流状态。一个 host 一份，避免"间隔"与"连续成功数"两张表对不齐。
     *
     * `intervalMs` 故意没有默认值：嵌套类（非 inner）的构造默认值引用不到外层实例的
     * 属性，硬写 `= defaultMinIntervalMs` 是在赌编译器把默认值内联到调用点。初始值由
     * [freshState] 给，衰减判定由 [decayed] 给——都留在看得见外层属性的地方。
     */
    private data class HostState(
        val intervalMs: Long,
        val consecutiveSuccess: Int = 0,

        /** 上一个请求放行的时刻；null = 这个 host 还没排过队。 */
        val lastRelease: Long? = null,
    ) {
        fun withSuccess() = copy(consecutiveSuccess = consecutiveSuccess + 1)
    }

    private fun freshState() = HostState(intervalMs = defaultMinIntervalMs)

    /**
     * 攒够连续成功就把间隔收回默认值，计数器一起清零（否则下一轮又是白攒 8 次）。
     * 不满足条件就原样返回——衰减是"到点一次性复位"，不是逐步减半。
     */
    private fun HostState.decayed(): HostState =
        if (intervalMs > defaultMinIntervalMs && consecutiveSuccess >= SUCCESS_RESET_STREAK) {
            copy(intervalMs = defaultMinIntervalMs, consecutiveSuccess = 0)
        } else {
            this
        }

    /** 保护 [states] 与 [rateLimited] 的唯一一把锁：临界区里**不许** sleep、不许发请求。 */
    private val stateLock = Mutex()

    private val states = mutableMapOf<String, HostState>()

    /**
     * 本轮撞过 429 的 host。
     *
     * 嗅探（`ProbeEngine.trySniff`）在编排器之外自己发几次请求，那份"这个 host 已经限流了"
     * 的判断原来另存了一个裸 `mutableSetOf`，与编排器线程并发读写。既然这里已经是 host
     * 节流状态的权威存储，就并进来，别再让调用方各自维护一份。
     * 由引擎在每轮开始时 [clearRateLimitedMarks] 一次。
     */
    private val rateLimited = mutableSetOf<String>()

    /**
     * 每个 host 上一个请求**真的发出去**的时刻。与 [HostState.lastRelease]（取号放行时刻）
     * 分开记，理由见 [awaitWireSpacing]。
     */
    private val lastDeparture = mutableMapOf<String, Long>()

    /** 当前 host 的串行间隔。 */
    suspend fun currentIntervalMs(host: String): Long = stateLock.withLock {
        states[host]?.intervalMs ?: defaultMinIntervalMs
    }

    /**
     * 取号并等到这个 host 轮到：挂起直到距离上一个该 host 的请求过去了足够的间隔。
     *
     * 排队语义：同一个 host 连续 acquire 时，后一个的放行时间在前一个之后再叠一个间隔，
     * 所以哪怕前面的请求还在睡，后来的也不会插队。不同 host 之间互不相干。
     *
     * **这一道只保证"放行顺序"，不保证"实发间隔"**：拿到号之后还要在并发闸外排队，
     * 低档位下同 host 两张号可能被同一个瞬间一起放出去。所以发请求前还要再走一次
     * [awaitWireSpacing]，两道各司其职，不是重复。
     */
    suspend fun acquire(host: String) {
        val waitMs = stateLock.withLock {
            val state = states[host] ?: freshState()
            val now = nowMillis()
            val last = state.lastRelease
            val nextAllowed = if (last == null) now else (last + state.intervalMs).coerceAtLeast(now)
            states[host] = state.copy(lastRelease = nextAllowed)
            (nextAllowed - now).coerceAtLeast(0L)
        }
        if (waitMs > 0L) delay(waitMs)
    }

    /**
     * 拿到并发名额、真的发请求之前，再按"上一次实发时刻"守一次间隔。
     *
     * 为什么需要这一道：[acquire] 记的是放行时刻。上限设成 2 时，同 host 相隔 800ms 的两张号
     * 可能都卡在并发闸外，等名额空出来被同一瞬间一起放行——实发间隔≈0，正是红线 29 要挡的
     * 那一幕（M0.5 实测 Cloudflare 同 host 2.4 秒内第 3 个请求就 `error code: 1015`）。
     *
     * 只在"同 host 挤在少数几个名额上"时才会真睡着：绝大多数时候残余是 0，就是锁内一次读写。
     * 睡在锁外，与 [acquire] 同一条纪律。它只会把出发推后，不会提前，所以不可能因此变快。
     */
    suspend fun awaitWireSpacing(host: String) {
        val waitMs = stateLock.withLock {
            val interval = states[host]?.intervalMs ?: defaultMinIntervalMs
            val now = nowMillis()
            val previous = lastDeparture[host]
            val nextAllowed = if (previous == null) now else (previous + interval).coerceAtLeast(now)
            lastDeparture[host] = nextAllowed
            (nextAllowed - now).coerceAtLeast(0L)
        }
        if (waitMs > 0L) delay(waitMs)
    }

    /**
     * 撞了 429：间隔加倍（封顶 [maxIntervalMs]），并记进本轮 429 名单。
     *
     * @param retryAfterMs 上游 `Retry-After` 给的退避量。取"加倍值"与它的较大者——
     *   上游明确说了"60 秒后再来"，还按 1.6 秒的加倍节奏打就是纯浪费配额。
     *   仍然封顶 8s：一轮探测总预算才 120s，门闸间隔不该比整轮还长，那种请求该由
     *   编排器的 429 熔断直接停掉，而不是睡到下一轮。
     */
    suspend fun onRateLimited(host: String, retryAfterMs: Long? = null) {
        stateLock.withLock {
            val current = states[host]?.intervalMs ?: defaultMinIntervalMs
            val doubled = (current * 2).coerceAtMost(maxIntervalMs)
            val target = maxOf(doubled, retryAfterMs?.coerceAtMost(maxIntervalMs) ?: 0L)
            states[host] = (states[host] ?: freshState()).copy(
                intervalMs = target.coerceAtMost(maxIntervalMs),
                consecutiveSuccess = 0,
            )
            rateLimited += host
        }
    }

    /**
     * 拿到过一次响应（非 429）：计入连续成功，攒够就把间隔收回默认值。
     *
     * 为什么非 2xx 也算：门闸惩罚的是"这个 host 觉得我们太吵"（429），401 / 404 说明
     * 我们敲得不多、只是内容不对——按 429 之外的响应继续累加惩罚，会把一家正常站点
     * 永久钉在 8s 间隔上。
     */
    suspend fun onSuccess(host: String) {
        stateLock.withLock {
            val state = (states[host] ?: freshState()).withSuccess()
            states[host] = state.decayed()
        }
    }

    /** 这个 host 本轮是不是撞过 429（红线 29：撞过就停发可选请求）。 */
    suspend fun isRateLimited(host: String): Boolean = stateLock.withLock { host in rateLimited }

    /** 新一轮开始：清掉上一轮的 429 名单（间隔不清——那是真节流，跨轮有效）。 */
    suspend fun clearRateLimitedMarks() {
        stateLock.withLock { rateLimited.clear() }
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 800L
        const val MAX_INTERVAL_MS = 8_000L

        /** 连续这么多次非 429 响应就把间隔放回默认值。8 次 × 最坏 8s ≈ 一分钟，够短。 */
        const val SUCCESS_RESET_STREAK = 8
    }
}
