package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.endpoint.ProbeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

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

    override fun buildRequest(settings: KeySettings, defaultKey: CharArray?, token: CharArray?): ProbeRequest {
        val root = settings.apiRoot.trimEnd('/')
        val headers = mutableListOf<Pair<String, String>>()
        defaultKey?.let { headers += "Authorization" to "Bearer ${it.concatToString()}" }
        return ProbeRequest(
            method = "GET",
            url = "$root/${settings.apiVersion}/credits",
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
        // `as?` 而不是 `jsonObject`：`data` 被回成数组或字符串时，那个访问器抛
        // IllegalArgumentException，绕开 BalanceParseException 的"只报字段名"通道。
        val data = root["data"] as? JsonObject
            ?: throw BalanceParseException(kind, "missing_data")

        // `doubleOrNull` 对 `12.3` 与 `"12.3"` 两种写法都吃，OpenRouter 的 credits 接口
        // 历史上就回过字符串形态，不能因为层级/写法差异判成"缺字段"。
        val totalCredits = (data["total_credits"] as? JsonPrimitive)?.doubleOrNull
            ?: throw BalanceParseException(kind, "missing_total_credits")
        val totalUsage = (data["total_usage"] as? JsonPrimitive)?.doubleOrNull ?: 0.0

        return BalanceSnapshot(
            amount = totalCredits - totalUsage,
            used = totalUsage,
            currency = "USD",
            raw = body,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
}
