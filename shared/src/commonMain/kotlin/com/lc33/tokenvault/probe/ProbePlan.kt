package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.Provider
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
    val url: String,
    val clientProfileId: Long? = null,
    val authStyle: AuthStyle = AuthStyle.AUTO,
    val allowInsecure: Boolean,
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

    fun build(
        providers: List<Provider>,
        keysByProvider: (Long) -> List<ApiKey>,
    ): ProbePlan {
        val tasks = mutableListOf<PlannedTask>()
        val skippedProviders = mutableListOf<Provider>()
        val perHost = mutableMapOf<String, Int>()

        for (provider in providers) {
            val keys = keysByProvider(provider.id)
                .filter { it.enabled && it.settings.probe.enabled }
            if (keys.isEmpty()) {
                skippedProviders += provider
                continue
            }

            val providerTasks = mutableListOf<PlannedTask>()
            for (key in keys) {
                val settings = key.settings
                val endpoints = when (
                    val result = normalizeBaseUrl(
                        settings.apiBaseUrl,
                        settings.pathOverrides,
                    )
                ) {
                    is NormalizeResult.Ok -> result.endpoints
                    is NormalizeResult.Err -> continue
                }
                val host = hostOf(endpoints.apiRoot)
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
                            url = endpoints.modelsUrl,
                            clientProfileId = settings.clientProfileId,
                            authStyle = settings.authStyle,
                            allowInsecure = endpoints.insecure,
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
                        url = endpoints.modelsUrl,
                        clientProfileId = settings.clientProfileId,
                        authStyle = settings.authStyle,
                        allowInsecure = endpoints.insecure,
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

    private fun hostOf(apiRoot: String): String {
        val withoutScheme = apiRoot.substringAfter("://")
        return withoutScheme.substringBefore('/').substringBefore(':')
    }
}
