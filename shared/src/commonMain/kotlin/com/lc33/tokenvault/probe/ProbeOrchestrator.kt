package com.lc33.tokenvault.probe

import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 发一次请求的抽象（§8.5）。
 *
 * 生产实现包装
et/OkHttpEngine`；测试里用假实现，不碰真实网络。
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
 * HTTP 层的并发上限仍由 OkHttp `Dispatcher`（8 / 每 host 3）兜底。
 *
 * 关键语义（测试 14 逐条覆盖）：
 * - 逐项推流 [ProbeItemResult]，UI 不等整轮结束。
 * - 取消是真的停：协程被取消时立刻抛 [CancellationException]，已完成的结果不丢。
 * - 总预算超时后剩余项标 SKIPPED。
 * - 每 host 请求预算生效：超出的任务标 SKIPPED。
 * - **本轮该 host 出现 429 后，立即停止该 host 的后续可选请求**（红线 29）。
 * - 同 host 相邻请求间隔 ≥ 最小间隔（由 [com.lc33.tokenvault.net.HostGate] 保证，这里用
 *   [hostIntervalMs] 提供的间隔做 sleep）。
 *
 * @param transport 发请求的抽象。
 * @param nowMillis 注入时钟（测试用虚拟时间）。
 * @param hostIntervalMs 返回某 host 的最小间隔；生产实现读 HostGate。
 * @param onRateLimited 通知 host 撞了 429（生产实现调 HostGate.onRateLimited）。
 */
class ProbeOrchestrator(
    private val transport: ProbeTransport,
    private val nowMillis: () -> Long,
    private val hostIntervalMs: (String) -> Long = { 0L },
    private val onRateLimited: (String) -> Unit = {},
    private val budget: ProbeBudget = ProbeBudget(),
    private val clientKeywords: List<String> = ProbeClassifier.DEFAULT_CLIENT_KEYWORDS,
) {

    /**
     * 跑一轮。逐项 emit 结果。
     *
     * 不同 host 的任务并行执行，同 host 的任务按顺序串行（保证间隔与 429 熔断语义）。
     *
     * @param tasks 已生成的任务。
     * @param perHostKeyAndModelCount 每个 host 的"密钥数 + 启用模型数"，用于算 host 预算。
     */
    fun run(
        tasks: List<ProbeTask>,
        perHostKeyAndModelCount: Map<String, Int> = emptyMap(),
    ): Flow<ProbeItemResult> = channelFlow {
        val startedAt = nowMillis()

        // 并发下的共享状态——所有读写都在 stateLock 内。
        val hostBudget = mutableMapOf<String, Int>()
        val hostRateLimited = mutableSetOf<String>()
        val hostLastRequest = mutableMapOf<String, Long>()
        val stateLock = Mutex()
        // 同 host 串行：保证间隔与 429 熔断按序判定。
        val perHostMutexes = mutableMapOf<String, Mutex>()

        coroutineScope {
            for (task in tasks) {
                // 预算超时快速跳过：避免所有任务都排队 stateLock。
                if (nowMillis() - startedAt >= budget.totalBudgetMs) continue

                val hostMutex = perHostMutexes.getOrPut(task.host) { Mutex() }
                launch {
                    if (!currentCoroutineContext().isActive) return@launch
                    hostMutex.withLock {
                        // 取消 / 预算在排队期间过期：跳过。
                        if (!currentCoroutineContext().isActive) return@launch
                        if (nowMillis() - startedAt >= budget.totalBudgetMs) return@launch

                        stateLock.withLock {
                            if (task.host in hostRateLimited) return@launch
                            val limit = budget.perHostBase + (perHostKeyAndModelCount[task.host] ?: 0)
                            if ((hostBudget[task.host] ?: 0) >= limit) return@launch

                            // host 间隔：等这个 host 轮到。
                            val interval = hostIntervalMs(task.host)
                            val last = hostLastRequest[task.host]
                            val now = nowMillis()
                            val nextAllowed = if (last == null) now else last + interval
                            if (nextAllowed > now) delay(nextAllowed - now)
                        }

                        val response = transport.execute(
                            ProbeRequest(
                                method = if (task.body == null) "GET" else "POST",
                                url = task.url,
                                headers = task.headers,
                                body = task.body,
                                protocol = task.protocol,
                            ),
                            allowInsecure = task.allowInsecure,
                        )

                        val classification = stateLock.withLock {
                            hostLastRequest[task.host] = nowMillis()
                            hostBudget[task.host] = (hostBudget[task.host] ?: 0) + 1
                            if (response.status == 429) {
                                hostRateLimited += task.host
                                onRateLimited(task.host)
                            }
                            ProbeClassifier.classify(
                                status = response.status.takeIf { response.error == null },
                                body = response.body,
                                error = response.error,
                                level = task.level,
                                clientKeywords = clientKeywords,
                            )
                        }

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
}
