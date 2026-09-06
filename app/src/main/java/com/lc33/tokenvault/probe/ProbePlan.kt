package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.endpoint.normalizeBaseUrl

/**
 * 一轮探测要发的"骨架任务"（计划.md §8.3、§8.6）。
 *
 * 刻意**不含鉴权头**：构造鉴权头需要密钥明文（要 reveal、要借 DEK），那是 `data/` 层
 * 的事，不该出现在这个纯 Kotlin 包里。所以这里只产出"要发哪个请求、归谁、为什么"，
 * 密钥明文由 [com.lc33.tokenvault.engine.ProbeEngine] 在发之前 reveal 并填进
 * [ProbeTask.headers]。
 *
 * M5 只生成 L1（供应商 × 协议的模型列表）与 L2（每张启用 Key 发一次 L1），不做 L3 / L4
 * （红线 36：花钱的只手动）——§8.3 的原话。
 */
data class PlannedTask(
    /** 与 [ProbeTask.id] 一致，用于进度去重与测试断言。 */
    val id: String,
    val level: ProbeLevel,
    val providerId: Long,
    val providerName: String,
    val host: String,
    val protocol: Protocol,

    /** L2 时才非空；构造鉴权头时要 reveal 这张 Key。 */
    val keyId: Long? = null,

    /** 请求 URL（models 列表）。 */
    val url: String,

    /** 该家选的客户端伪装预设；null = 用内置 default。组装头时由引擎解析。 */
    val clientProfileId: Long? = null,

    /** 该家的鉴权风格。`AUTO` 落到协议默认（§5.2 双向兜底）。 */
    val authStyle: AuthStyle = AuthStyle.AUTO,

    /** 供应商是否显式允许 `http://`（§7.5）。 */
    val allowInsecure: Boolean,
)

/**
 * 一轮探测的计划（§8.6 第一步：过滤供应商）。
 *
 * [tasks] 是已按"供应商 → 协议 → Key"排好序的骨架任务；[skippedProviders] 是
 * `probeEnabled = 0` 被整家跳过的那几家（进度文案里"跳过 N 项"要体现，§8.6）。
 */
data class ProbePlan(
    val tasks: List<PlannedTask>,

    /** `probeEnabled = 0` 的供应商，整家跳过。 */
    val skippedProviders: List<Provider>,

    /** 每个 host 的"密钥数 + 启用模型数"，用于算 host 请求预算（§8.5）。 */
    val perHostKeyAndModelCount: Map<String, Int>,
)

/**
 * 把供应商与密钥清单转成一轮探测的计划。**纯函数**，可 JVM 单测。
 *
 * 规则（§8.6）：
 * - `probeEnabled = 0` 的整家跳过，进 [ProbePlan.skippedProviders]。
 * - `probeReachability = 0` 则不发这家任何 L1 基线；`probeKeyValidity = 0` 则不发 L2。
 * - 端点由 [normalizeBaseUrl] 从 `provider.apiBaseUrl` + `pathOverrides` 现算——
 *   规范化失败（空 / 带 query / 无 host）的那家跳过，不产任务。
 *
 * @param providers 全量供应商（已由调用方过滤或未过滤均可，这里会再按 probeEnabled 过滤）。
 * @param keysByProvider 每家的启用 Key。L2 只为启用的 Key 生成。
 */
object ProbePlanBuilder {

    fun build(
        providers: List<Provider>,
        keysByProvider: (Long) -> List<ApiKey>,
    ): ProbePlan {
        val tasks = mutableListOf<PlannedTask>()
        val skippedProviders = mutableListOf<Provider>()
        val perHostKeyAndModelCount = mutableMapOf<String, Int>()

        for (provider in providers) {
            if (!provider.probe.enabled) {
                skippedProviders += provider
                continue
            }

            val endpoints = when (val r = normalizeBaseUrl(provider.apiBaseUrl, provider.pathOverrides)) {
                is NormalizeResult.Ok -> r.endpoints
                is NormalizeResult.Err -> {
                    skippedProviders += provider
                    continue
                }
            }

            val host = hostOf(endpoints.apiRoot)
            val keys = if (provider.probe.keyValidity) {
                // 只探测**启用**的 Key：停用的 Key 不参与，也不占 host 预算。
                keysByProvider(provider.id).filter { it.enabled }
            } else {
                emptyList()
            }

            // host 预算的基数：密钥数 + 启用模型数（模型数在 M5 不拉，用 0）。
            perHostKeyAndModelCount[host] =
                (perHostKeyAndModelCount[host] ?: 0) + keys.size

            // L1：供应商 × 协议 的模型列表。用"不鉴权基线"（无效令牌），零成本。
            if (provider.probe.reachability) {
                provider.supportedProtocols.forEach { protocol ->
                    tasks += PlannedTask(
                        id = "l1:${provider.id}:${protocol.wireName}",
                        level = ProbeLevel.L1_REACHABILITY,
                        providerId = provider.id,
                        providerName = provider.name,
                        host = host,
                        protocol = protocol,
                        url = endpoints.modelsUrl,
                        clientProfileId = provider.clientProfileId,
                        authStyle = provider.authStyle,
                        allowInsecure = endpoints.insecure,
                    )
                }
            }

            // L2：每张启用 Key 发一次 L1（零成本，§8.3）。
            if (provider.probe.keyValidity) {
                keys.forEach { key ->
                    // L2 的请求同样打 models 列表路由，只是带上这张 Key 的鉴权头。
                    // 协议取该 Key 所属供应商的第一个协议——L2 只验证"这张 Key 是否有效"，
                    // 与协议无关（§8.4 的 L2 语义）。若供应商无协议，L2 无端点可打，跳过。
                    val protocol = provider.supportedProtocols.firstOrNull() ?: return@forEach
                    tasks += PlannedTask(
                        id = "l2:${key.id}",
                        level = ProbeLevel.L2_KEY_VALIDITY,
                        providerId = provider.id,
                        providerName = provider.name,
                        host = host,
                        protocol = protocol,
                        keyId = key.id,
                        url = endpoints.modelsUrl,
                        clientProfileId = provider.clientProfileId,
                        authStyle = provider.authStyle,
                        allowInsecure = endpoints.insecure,
                    )
                }
            }
        }

        return ProbePlan(
            tasks = tasks,
            skippedProviders = skippedProviders,
            perHostKeyAndModelCount = perHostKeyAndModelCount,
        )
    }

    /** 从 `apiRoot` 提 host（`scheme://host[:port]` → host）。 */
    private fun hostOf(apiRoot: String): String {
        val withoutScheme = apiRoot.substringAfter("://")
        return withoutScheme.substringBefore('/').substringBefore(':')
    }
}
