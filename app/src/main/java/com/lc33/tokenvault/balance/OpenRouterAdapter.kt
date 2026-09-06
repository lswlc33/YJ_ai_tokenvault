package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.endpoint.ProbeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenRouter 余额适配器（§9.2）。
 *
 * `GET {apiRoot}/{ver}/credits`，Bearer 复用默认 Key。解析 `data.total_credits -
 * data.total_usage` 为可用（**红线 14**：减法只写在明确提供两个量的适配器内部，
 * 这里恰好是那个"明确提供两个量"的适配器）。币种 USD。
 *
 * 字段名以公开文档为依据、未用真实账号验证（§9.2），M7 实测不过就删。
 */
class OpenRouterAdapter : BalanceAdapter {

    override val kind: BalanceKind = BalanceKind.OPENROUTER

    override fun buildRequest(provider: Provider, defaultKey: CharArray?, token: CharArray?): ProbeRequest {
        val root = provider.apiRoot.trimEnd('/')
        val headers = mutableListOf<Pair<String, String>>()
        defaultKey?.let { headers += "Authorization" to "Bearer ${it.concatToString()}" }
        return ProbeRequest(
            method = "GET",
            url = "$root/${provider.apiVersion}/credits",
            headers = headers,
            protocol = null,
        )
    }

    override fun parse(status: Int, body: String): BalanceSnapshot {
        if (status !in 200..299) {
            throw BalanceParseException(kind, "http $status")
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw BalanceParseException(kind, "no_json")
        val data = root["data"]?.jsonObject
            ?: throw BalanceParseException(kind, "missing_data")

        val totalCredits = data["total_credits"]?.jsonPrimitive?.doubleOrNull
            ?: throw BalanceParseException(kind, "missing_total_credits")
        val totalUsage = data["total_usage"]?.jsonPrimitive?.doubleOrNull ?: 0.0

        return BalanceSnapshot(
            amount = totalCredits - totalUsage,
            used = totalUsage,
            currency = "USD",
            raw = body,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
}
