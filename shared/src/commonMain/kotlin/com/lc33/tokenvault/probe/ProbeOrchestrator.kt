package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeResponse
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 发一次请求的抽象（§8.5）。
 *
 * 生产实现包装 `net/HttpEngine`；测试里用假实现，不碰真实网络。
 */
fun interface ProbeTransport {
    suspend fun execute(request: ProbeRequest, allowInsecure: Boolean): ProbeResponse
}

/**
 * 一轮探测的预算（§8.5、红线 29）。
 *
 * - [totalBudgetMs] 整轮总预算，超时后剩余项标 SKIPPED。
 * - [perHostBase] 每 host 每轮的请求上限基数，实际上限 = `perHostBase + 密钥数 + 启用模型数`。
 */
data class ProbeBudget(
    val totalBudgetMs: Long = 120_000,
    val perHostBase: Int = 12,
)

/**
 * 探测编排（§8.5）的核心，**纯逻辑、可测**。
 *
 * 职责只有"任务生成之外的部分"：按 host 分组统计预算、429 熔断、总预算超时、逐项推流。
 *
 * **并发模型**：不同 host 的任务并行发出，同 host 的任务保持顺序（per-host Mutex）。
 * HTTP 层的并发上限仍由引擎的 `Dispatcher`（8 / 每 host 3）兜底。
 *
 * **这里不睡 host 间隔**。间隔的唯一权威是 [com.lc33.tokenvault.net.HostGate]：
 * 原来编排器在**全局** `stateLock` 里按 `hostIntervalMs` 睡一次，`HttpEngine.execute`
 * 里门闸又睡一次——同 host 相邻两个请求白等两倍间隔，而更糟的是那个 sleep 写在全局锁里，
 * 一个 host 在睡，所有别的 host 的任务都堵在锁上，"按 host 并行"其实是假的串行。
 * 现在锁只保护几微秒的预算/熔断记账，`delay` 全在门闸内部、锁外。
 *
 * 关键语义（测试 14 逐条覆盖）：
 * - 逐项推流 [ProbeItemResult]，UI 不等整轮结束。
 * - 取消是真的停：协程被取消时立刻抛 [CancellationException]，已完成的结果不丢。
 * - 总预算超时 / 撞 host 预算 / 该 host 已 429 → **emit 一条 SKIPPED 结果**并带上
 *   [SkipReason]。原来这三类只是不发请求、什么都不推，于是 `done` 永远追不上 `total`
 *   （进度条卡在 87/90 不动），明细页的"本轮未探测"分组也永远是空的——而设计里那一组
 *   本来就该有内容（"跳过 N"）。
 * - 每 host 请求预算生效。
 * - **本轮该 host 出现 429 后，立即停止该 host 的后续可选请求**（红线 29）。
 *
 * @param transport 发请求的抽象。
 * @param nowMillis 注入时钟（测试用虚拟时间）。
 * @param onRateLimited 通知 host 撞了 429（生产实现调 HostGate，并把分类器读到的
 *   `Retry-After` 一起递过去）。suspend：它要改的就是门闸那份受锁保护的状态。
 */
class ProbeOrchestrator(
    private val transport: ProbeTransport,
    private val nowMillis: () -> Long,
    private val onRateLimited: suspend (host: String, retryAfterMs: Long?) -> Unit = { _, _ -> },
    private val budget: ProbeBudget = ProbeBudget(),
    private val clientKeywords: List<String> = ProbeClassifier.DEFAULT_CLIENT_KEYWORDS,
) {

    /**
     * 跑一轮。逐项 emit 结果（含被跳过的项）。
     *
     * 不同 host 的任务并行执行，同 host 的任务按顺序串行（保证 429 熔断按序判定；
     * 间隔由门闸保证）。
     *
     * @param tasks 已生成的任务。
     * @param perHostKeyAndModelCount 每个 host 的"密钥数 + 启用模型数"，用于算 host 预算。
     */
    fun run(
        tasks: List<ProbeTask>,
        perHostKeyAndModelCount: Map<String, Int> = emptyMap(),
    ): Flow<ProbeItemResult> = channelFlow {
        val startedAt = nowMillis()

        // 跨 host 共享的记账——所有读写都在 stateLock 内，且临界区里**不睡眠、不发请求**。
        val hostBudget = mutableMapOf<String, Int>()
        val hostRateLimited = mutableSetOf<String>()
        val stateLock = Mutex()
        // 同 host 串行：保证 429 熔断与预算判定按序发生。不同 host 各拿各的锁，互不阻塞。
        val perHostMutexes = mutableMapOf<String, Mutex>()

        /** 这个任务现在该不该发。返回 null 表示可以发。纯判定，不睡眠。 */
        fun decideSkip(task: ProbeTask): SkipReason? {
            if (nowMillis() - startedAt >= budget.totalBudgetMs) return SkipReason.TotalBudgetExhausted
            if (task.host in hostRateLimited) return SkipReason.HostRateLimited
            val limit = budget.perHostBase + (perHostKeyAndModelCount[task.host] ?: 0)
            if ((hostBudget[task.host] ?: 0) >= limit) return SkipReason.HostBudgetExhausted
            return null
        }

        coroutineScope {
            for (task in tasks) {
                // 预算超时的快速路径：不在这里排队，直接把这一项结掉。
                if (nowMillis() - startedAt >= budget.totalBudgetMs) {
                    send(task.skipped(SkipReason.TotalBudgetExhausted))
                    continue
                }

                val hostMutex = perHostMutexes.getOrPut(task.host) { Mutex() }
                launch {
                    if (!currentCoroutineContext().isActive) return@launch
                    hostMutex.withLock {
                        // 取消 / 预算与熔断在排队期间变化：按对应原因结掉这一项。
                        if (!currentCoroutineContext().isActive) return@launch
                        val skip = stateLock.withLock { decideSkip(task) }
                        if (skip != null) {
                            send(task.skipped(skip))
                            return@launch
                        }

                        val response = transport.execute(
                            ProbeRequest(
                                method = if (task.body == null) "GET" else "POST",
                                url = task.url,
                                headers = task.headers,
                                body = task.body,
                                protocol = task.protocol,
                                timeoutMs = task.timeoutMs,
                            ),
                            allowInsecure = task.allowInsecure,
                        )

                        val classification = ProbeClassifier.classify(
                            status = response.status.takeIf { response.error == null },
                            body = response.body,
                            error = response.error,
                            level = task.level,
                            clientKeywords = clientKeywords,
                            headers = response.headers,
                        )

                        val rateLimited = response.status == 429 && response.error == null
                        stateLock.withLock {
                            hostBudget[task.host] = (hostBudget[task.host] ?: 0) + 1
                            if (rateLimited) hostRateLimited += task.host
                        }
                        // 门闸通知在锁外、在同 host 的互斥区内：后来的同 host 任务必然看得见。
                        if (rateLimited) onRateLimited(task.host, classification.retryAfterMs)

                        send(
                            ProbeItemResult(
                                taskId = task.id,
                                providerId = task.providerId,
                                providerName = task.providerName,
                                keyId = task.keyId,
                                keyLabel = task.keyLabel,
                                level = task.level,
                                outcome = classification.outcome,
                                health = classification.health,
                                detail = classification.detail,
                                httpStatus = classification.httpStatus,
                                latencyMs = response.latencyMs,
                                body = response.body,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** 被跳过的项：没有状态码、没有延迟，只有"为什么没测"。 */
    private fun ProbeTask.skipped(reason: SkipReason) = ProbeItemResult(
        taskId = id,
        providerId = providerId,
        providerName = providerName,
        keyId = keyId,
        keyLabel = keyLabel,
        level = level,
        outcome = ProbeOutcome.SKIPPED,
        skipReason = reason,
    )
}
