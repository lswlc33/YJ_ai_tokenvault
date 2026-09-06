package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.endpoint.ProbeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * DeepSeek 官方余额适配器（§9.2）。
 *
 * 请求 `GET {apiRoot}/user/balance`，Bearer 复用该供应商的默认 API Key。
 * 解析 `balance_infos[0].total_balance`（**字符串，要 parse**，M0.5 实测 `"0.89"`），
 * 币种取同条 `currency`（实测 `"CNY"`）。多币种时取第一条，UI 提示。
 *
 * `is_available = false` 表示"查询成功但账号不可用"，要能区分（§9.2）。
 */
class DeepSeekAdapter : BalanceAdapter {

    override val kind: BalanceKind = BalanceKind.DEEPSEEK

    override fun buildRequest(provider: Provider, defaultKey: CharArray?, token: CharArray?): ProbeRequest {
        val root = provider.apiRoot.trimEnd('/')
        val headers = mutableListOf<Pair<String, String>>()
        defaultKey?.let { headers += "Authorization" to "Bearer ${it.concatToString()}" }
        return ProbeRequest(
            method = "GET",
            url = "$root/user/balance",
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

        val infos = root["balance_infos"]?.jsonArray
            ?: throw BalanceParseException(kind, "missing_balance_infos")
        val first = infos.firstOrNull()?.jsonObject
            ?: throw BalanceParseException(kind, "empty_balance_infos")

        // total_balance 是字符串，要 parse（§9.2）。
        val amountStr = first["total_balance"]?.jsonPrimitive?.content
            ?: throw BalanceParseException(kind, "missing_total_balance")
        val amount = amountStr.toDoubleOrNull()
            ?: throw BalanceParseException(kind, "bad_total_balance")

        val currency = first["currency"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: BalanceSnapshot.UNKNOWN_CURRENCY

        return BalanceSnapshot(
            amount = amount,
            used = null,
            currency = currency,
            raw = body,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
}
