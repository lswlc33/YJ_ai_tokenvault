package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.endpoint.ApiEndpointSet
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.endpoint.normalizeBaseUrl

/**
 * 一轮探测要发的“骨架任务”。
 *
 * 这里不构造鉴权头；那需要密钥明文，属于 `data/` 层的事。
 */
data class PlannedTask(
    val id: String,
    val level: ProbeLevel,
    val providerId: Long,
    val providerName: String,
    val host: String,
    val protocol: Protocol,
    val keyId: Long? = null,
    /** 密钥名称（L1 为 null）。明细页用来看出"探的是哪把 Key"。 */
    val keyLabel: String? = null,
    val url: String,
    val clientProfileId: Long? = null,
    val authStyle: AuthStyle = AuthStyle.AUTO,
    val allowInsecure: Boolean,

    /** `key.settings.timeoutSeconds` 换算成毫秒；null = 用引擎级兜底（§8.1）。 */
    val timeoutMs: Long? = null,
)

data class ProbePlan(
    val tasks: List<PlannedTask>,
    val skippedProviders: List<Provider>,
    val perHostKeyAndModelCount: Map<String, Int>,
)

/**
 * 按 Key 生成探测计划。
 *
 * v3 的关键变化：端点、协议、鉴权与探测权限都来自 `key.settings`。
 * 同一个供应商下的两把 Key 可以指向不同站点、走不同协议、拥有不同探测策略。
 */
object ProbePlanBuilder {

    /**
     * 一把 Key 的落点：规范化后的端点集合、host、换算成毫秒的超时。
     *
     * [build] 与 [buildModelListTasks] 共用它，因为"地址怎么拼"只能有一套口径——
     * 两处各写一遍的话，改一处漏一处，表现是手动刷新能拉到列表而自动轮拉不到。
     *
     * 返回 null 表示这把 Key 发不出请求：端点规范化失败，或它没声明任何协议。
     */
    private class KeyTarget(
        val endpoints: ApiEndpointSet,
        val host: String,
        val timeoutMs: Long?,
    )

    private fun targetOf(key: ApiKey): KeyTarget? {
        val settings = key.settings
        val endpoints = when (
            val result = normalizeBaseUrl(
                settings.apiBaseUrl,
                settings.pathOverrides,
            )
        ) {
            is NormalizeResult.Ok -> result.endpoints
            is NormalizeResult.Err -> return null
        }
        return KeyTarget(
            endpoints = endpoints,
            host = hostOf(endpoints.apiRoot),
            // 每把 Key 自己的超时（秒 → 毫秒）。0 / 负数按"没填"处理：
            // 一个 0 毫秒的超时等于每个请求必失败，那不该是用户能意外得到的结果。
            timeoutMs = settings.timeoutSeconds?.takeIf { it > 0 }?.times(1_000L),
        )
    }

    fun build(
        providers: List<Provider>,
        keysByProvider: (Long) -> List<ApiKey>,
    ): ProbePlan {
        val tasks = mutableListOf<PlannedTask>()
        val skippedProviders = mutableListOf<Provider>()
        val perHost = mutableMapOf<String, Int>()

        for (provider in providers) {
            val keys = keysByProvider(provider.id)
                .filter { it.settings.probe.enabled }
            if (keys.isEmpty()) {
                skippedProviders += provider
                continue
            }

            val providerTasks = mutableListOf<PlannedTask>()
            for (key in keys) {
                val settings = key.settings
                val target = targetOf(key) ?: continue
                val endpoints = target.endpoints
                val host = target.host
                val timeoutMs = target.timeoutMs
                perHost[host] = (perHost[host] ?: 0) + 1

                if (settings.probe.reachability) {
                    settings.supportedProtocols.forEach { protocol ->
                        providerTasks += PlannedTask(
                            id = "l1:${key.id}:${protocol.wireName}",
                            level = ProbeLevel.L1_REACHABILITY,
                            providerId = provider.id,
                            providerName = provider.name,
                            host = host,
                            protocol = protocol,
                            keyId = key.id,
                            keyLabel = key.label,
                            url = endpoints.modelsUrl,
                            clientProfileId = settings.clientProfileId,
                            authStyle = settings.authStyle,
                            allowInsecure = settings.allowInsecure,
                            timeoutMs = timeoutMs,
                        )
                    }
                }

                if (settings.probe.keyValidity) {
                    val protocol = settings.supportedProtocols.firstOrNull() ?: continue
                    providerTasks += PlannedTask(
                        id = "l2:${key.id}",
                        level = ProbeLevel.L2_KEY_VALIDITY,
                        providerId = provider.id,
                        providerName = provider.name,
                        host = host,
                        protocol = protocol,
                        keyId = key.id,
                        keyLabel = key.label,
                        url = endpoints.modelsUrl,
                        clientProfileId = settings.clientProfileId,
                        authStyle = settings.authStyle,
                        allowInsecure = settings.allowInsecure,
                        timeoutMs = timeoutMs,
                    )
                }
            }

            if (providerTasks.isEmpty()) {
                skippedProviders += provider
            } else {
                tasks += providerTasks
            }
        }

        return ProbePlan(
            tasks = tasks,
            skippedProviders = skippedProviders,
            perHostKeyAndModelCount = perHost,
        )
    }

    /**
     * 只为"拉模型列表"铺任务：**每把 Key 恰好一条**，指向它自己的 `modelsUrl`。
     *
     * 为什么不复用 [build] 的那份计划：`modelsUrl` 是与协议无关的（`normalizeBaseUrl`
     * 里就一个 `$apiRoot/$ver/models`，分协议的路径在 `byProtocol`），而 [build] 按
     * "每个协议一条 L1 + 一条 L2"铺开。同一把 Key 于是发两三次一模一样的 GET，
     * 更糟的是每条任务都带着自己的 `protocol`——同一份响应被按协议各归一桶、各写一套行，
     * 界面上就是"每个模型出现两遍"。
     *
     * 这里也**不看** `probe.reachability` / `probe.keyValidity`：那是连通性与密钥有效性的
     * 开关，模型列表有自己的 `probe.models`（由调用方过滤 Key）。挂在别人的开关上，
     * 表现就是"只开模型列表自动更新的 Key 一次都拉不到"。
     *
     * 协议取 `supportedProtocols` 的第一个，与 [build] 的 L2 同口径——手动刷新与自动轮
     * 写出的 `discoveredVia` 才会是同一个协议，不会各认一个。
     *
     * 级别跟着 `keyValidity` 走：`ProbeClassifier` 只在 400 上分级别（L2 算"参数被拒但
     * 密钥有效"），而这条路径在 `keyValidity` 开着时会顺带写密钥健康度，级别得对得上。
     */
    fun buildModelListTasks(
        providers: List<Provider>,
        keysByProvider: (Long) -> List<ApiKey>,
    ): List<PlannedTask> {
        val tasks = mutableListOf<PlannedTask>()
        for (provider in providers) {
            for (key in keysByProvider(provider.id)) {
                if (!key.settings.probe.enabled) continue
                val settings = key.settings
                val protocol = settings.supportedProtocols.firstOrNull() ?: continue
                val target = targetOf(key) ?: continue
                tasks += PlannedTask(
                    id = "models:${key.id}",
                    level = if (settings.probe.keyValidity) {
                        ProbeLevel.L2_KEY_VALIDITY
                    } else {
                        ProbeLevel.L1_REACHABILITY
                    },
                    providerId = provider.id,
                    providerName = provider.name,
                    host = target.host,
                    protocol = protocol,
                    keyId = key.id,
                    keyLabel = key.label,
                    url = target.endpoints.modelsUrl,
                    clientProfileId = settings.clientProfileId,
                    authStyle = settings.authStyle,
                    allowInsecure = settings.allowInsecure,
                    timeoutMs = target.timeoutMs,
                )
            }
        }
        return tasks
    }

    private fun hostOf(apiRoot: String): String {
        val withoutScheme = apiRoot.substringAfter("://")
        return withoutScheme.substringBefore('/').substringBefore(':')
    }
}
