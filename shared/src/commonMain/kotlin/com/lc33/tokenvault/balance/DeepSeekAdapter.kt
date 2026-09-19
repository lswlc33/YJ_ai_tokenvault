package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.endpoint.ProbeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * DeepSeek 官方余额适配器（§9.2）。
 *
 * 请求 `GET {apiRoot}/user/balance`，Bearer 复用该供应商的默认 API Key。
 * 解析 `balance_infos[0].total_balance`（**字符串，要 parse**，M0.5 实测 `"0.89"`），
 * 币种取同条 `currency`（实测 `"CNY"`）。多币种时取第一条，UI 提示。
 *
 * `is_available = false` 表示"查询成功但账号不可用"，要能区分（§9.2）：这里回一条
 * 带 `account_unavailable` 原因的失败快照（`amount = null`，不进求和），而不是抛
 * HTTP 失败或报 0 元。
 */
class DeepSeekAdapter : BalanceAdapter {

    override val kind: BalanceKind = BalanceKind.DEEPSEEK

    override fun buildRequest(settings: KeySettings, defaultKey: CharArray?, token: CharArray?): ProbeRequest {
        val root = settings.apiRoot.trimEnd('/')
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

        // `is_available = false` 是"查询成功但账号不可用"（§9.2 承诺要能区分）。
        // 它必须在读 balance_infos **之前**判掉：不可用的账号往往连带回一个空数组，
        // 那时抛 `empty_balance_infos` 就把"不可用"这个更有用的结论覆盖掉了。
        // 也不许报成 0 余额——0 是"真没钱了"，两者在 UI 上是两个形状（见 BalanceSnapshot 契约）。
        if ((root["is_available"] as? JsonPrimitive)?.booleanOrNull == false) {
            return BalanceSnapshot(
                amount = null,
                currency = BalanceSnapshot.UNKNOWN_CURRENCY,
                raw = body,
                error = ACCOUNT_UNAVAILABLE,
            )
        }

        // 用 `as?` 而不是 `jsonArray` / `jsonObject`：上游把字段回成字符串或对象时，
        // 那三个访问器抛的是 IllegalArgumentException，会绕过 BalanceParseException
        // 的"只报字段名、不带 body"约定（红线 32）。
        val infos = root["balance_infos"] as? JsonArray
            ?: throw BalanceParseException(kind, "missing_balance_infos")
        val first = infos.firstOrNull()?.let { it as? JsonObject }
            ?: throw BalanceParseException(kind, "empty_balance_infos")

        // total_balance 是字符串，要 parse（§9.2）。
        val amountStr = (first["total_balance"] as? JsonPrimitive)?.contentOrNull
            ?: throw BalanceParseException(kind, "missing_total_balance")
        val amount = amountStr.toDoubleOrNull()
            ?: throw BalanceParseException(kind, "bad_total_balance")

        val currency = (first["currency"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: BalanceSnapshot.UNKNOWN_CURRENCY

        return BalanceSnapshot(
            amount = amount,
            used = null,
            currency = currency,
            raw = body,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        /** 机器可读原因：账号不可用。文案由 UI 层翻译（红线 19）。 */
        const val ACCOUNT_UNAVAILABLE = "account_unavailable"
    }
}
