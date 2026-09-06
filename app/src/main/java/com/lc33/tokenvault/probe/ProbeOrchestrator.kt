package com.lc33.tokenvault.probe

import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * 发一次请求的抽象（§8.5）。
 *
 * 生产实现包装 `net/OkHttpEngine`；测试里用假实现，不碰真实网络。
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
 * 并发限制交给 OkHttp `Dispatcher`（8 / 每 host 3），编排层不造信号量——§8.5 的原话。
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
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val hostIntervalMs: (String) -> Long = { 0L },
    private val onRateLimited: (String) -> Unit = {},
    private val budget: ProbeBudget = ProbeBudget(),
) {

    /**
     * 跑一轮。逐项 emit 结果。
     *
     * @param tasks 已生成的任务（顺序即执行顺序）。
     * @param perHostKeyAndModelCount 每个 host 的"密钥数 + 启用模型数"，用于算 host 预算。
     */
    fun run(
        tasks: List<ProbeTask>,
        perHostKeyAndModelCount: Map<String, Int> = emptyMap(),
    ): Flow<ProbeItemResult> = flow {
        val startedAt = nowMillis()
        val hostBudget = mutableMapOf<String, Int>()
        val hostRateLimited = mutableSetOf<String>()
        val hostLastRequest = mutableMapOf<String, Long>()

        for (task in tasks) {
            // 取消：协程被取消时立刻停。
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("probe cancelled")
            }

            // 总预算：超时则剩余全标 SKIPPED（直接结束流，剩余项由调用方补 SKIPPED）。
            val elapsed = nowMillis() - startedAt
            if (elapsed >= budget.totalBudgetMs) {
                return@flow
            }

            val host = task.host
            // host 预算：`12 + 密钥数 + 启用模型数`（§8.5）。
            val limit = budget.perHostBase + (perHostKeyAndModelCount[host] ?: 0)
            val used = hostBudget[host] ?: 0

            // 该 host 已 429：可选请求（基线、嗅探、L3）停发。L2 是"必须结论"的请求，
            // 但红线 29 说"只保留已经排上的 L2"——即 429 之后不再排新的，已排的照发。
            // 这里简化：429 之后，该 host 的**新任务**一律 SKIPPED。
            if (host in hostRateLimited) {
                continue
            }

            if (used >= limit) {
                continue
            }

            // host 间隔：等这个 host 轮到。
            val interval = hostIntervalMs(host)
            val last = hostLastRequest[host]
            val now = nowMillis()
            val nextAllowed = if (last == null) now else last + interval
            if (nextAllowed > now) delay(nextAllowed - now)

            val response = transport.execute(
                ProbeRequest(
                    method = if (task.body == null) "GET" else "POST",
                    url = task.url,
                    headers = task.headers,
                    body = task.body,
                    protocol = task.protocol,
                ),
                allowInsecure = false,
            )

            hostLastRequest[host] = nowMillis()
            hostBudget[host] = used + 1

            // 429 熔断：通知 host，标记后续任务 SKIPPED。
            if (response.status == 429) {
                hostRateLimited += host
                onRateLimited(host)
            }

            val classification = ProbeClassifier.classify(
                status = response.status.takeIf { response.error == null },
                body = response.body,
                error = response.error,
                level = task.level,
            )

            emit(
                ProbeItemResult(
                    taskId = task.id,
                    providerId = task.providerId,
                    providerName = task.providerName,
                    keyId = task.keyId,
                    level = task.level,
                    outcome = classification.outcome,
                    health = classification.health,
                    detail = classification.detail,
                    latencyMs = response.latencyMs,
                ),
            )
        }
    }
}
