package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.endpoint.ProbeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * new-api / one-api 及衍生（绝大多数中转站）的余额适配器（§9.2）。
 *
 * 请求 `GET {balanceBase}/api/user/self`，头 `Authorization: Bearer <访问令牌>` +
 * `New-Api-User: <用户ID>`。解析 `data.quota / quotaPerUnit` 为可用、
 * `data.used_quota / quotaPerUnit` 为已用。
 *
 * [quotaPerUnit] 默认 [BalanceKind.NEWAPI_DEFAULT_QUOTA_PER_UNIT]（500000，M0.5 两家实测），
 * 可先探 `GET {balanceBase}/api/status` 读 `data.quota_per_unit` 校准。校准是**另一件事**，
 * 抽成 [calibrateQuotaPerUnit] 独立函数——它返回 null 表示校准失败（缺字段 / 解析失败），
 * 调用方据此决定要不要落 `quotaCalibrated`。
 *
 * 两个 M0.5 实测逼出来的约束，都写在这里：
 * - **绝不截断响应体**（[calibrateQuotaPerUnit] 的 [statusBody] 是完整 body）。
 * - **`quota_display_type` 不是必选**：JustDoWork 有、Agent Router 没有。读不到就按 USD，
 *   不因缺字段整条失败。
 */
class NewApiAdapter : BalanceAdapter {

    override val kind: BalanceKind = BalanceKind.NEWAPI

    override fun buildRequest(settings: KeySettings, defaultKey: CharArray?, token: CharArray?): ProbeRequest {
        val base = settings.balanceBaseUrl?.trimEnd('/') ?: settings.apiRoot.trimEnd('/')
        val headers = mutableListOf<Pair<String, String>>()
        token?.let { headers += "Authorization" to "Bearer ${it.concatToString()}" }
        settings.balanceUserId?.let { headers += "New-Api-User" to it }
        return ProbeRequest(
            method = "GET",
            url = "$base/api/user/self",
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

        val quota = data["quota"]?.jsonPrimitive?.longOrNull
            ?: throw BalanceParseException(kind, "missing_quota")
        val usedQuota = data["used_quota"]?.jsonPrimitive?.longOrNull ?: 0L
        val quotaPerUnit = providerQuotaPerUnit(data)

        val amount = quota.toDouble() / quotaPerUnit
        val used = usedQuota.toDouble() / quotaPerUnit
        val currency = data["quota_display_type"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: "USD"

        return BalanceSnapshot(
            amount = amount,
            used = used,
            currency = currency,
            raw = body,
        )
    }

    /**
     * 从 `/api/status` 响应里读 `data.quota_per_unit` 校准换算比。
     *
     * @return 校准值；解析失败或缺字段返回 null（调用方保留默认值并标 `quotaCalibrated = 0`）。
     */
    fun calibrateQuotaPerUnit(statusBody: String): Double? {
        val root = runCatching { json.parseToJsonElement(statusBody).jsonObject }.getOrNull()
            ?: return null
        return root["data"]?.jsonObject?.get("quota_per_unit")?.jsonPrimitive?.doubleOrNull
    }

    /** 从 `/api/user/self` 的 data 里读 `quota_per_unit`（部分站会带，没带用默认）。 */
    private fun providerQuotaPerUnit(data: kotlinx.serialization.json.JsonObject): Double =
        data["quota_per_unit"]?.jsonPrimitive?.doubleOrNull
            ?: BalanceKind.NEWAPI_DEFAULT_QUOTA_PER_UNIT

    private val json = Json { ignoreUnknownKeys = true }
}
