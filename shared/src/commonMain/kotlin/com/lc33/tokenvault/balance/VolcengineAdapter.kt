package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.endpoint.ProbeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 火山引擎 QueryBalanceAcct 余额适配器（§9.2）。
 *
 * `GET https://open.volcengineapi.com/?Action=QueryBalanceAcct&Version=2022-01-01`，
 * 鉴权走 [VolcengineSigner] 的 V4 头部签名。凭据复用现有两列：
 * `balanceUserId` 存 AccessKey ID 明文，`balanceTokenEnc` 存 SecretAccessKey 密文
 * （`usesOwnToken = true`，由 `BalanceEngine` 解密后经 [token] 传入）。
 *
 * 解析优先 `Result.AvailableBalance`，缺失退 `Result.CashBalance`；**不**退到
 * `ArrearsBalance`——那是欠款额，报成余额是误导。币种固定 CNY，`used` 恒为 null
 * （接口不返回已用额度）。
 */
class VolcengineAdapter(
    private val signer: VolcengineSigner = VolcengineSigner(),
) : BalanceAdapter {

    override val kind: BalanceKind = BalanceKind.VOLCENGINE

    override fun buildRequest(settings: KeySettings, defaultKey: CharArray?, token: CharArray?): ProbeRequest {
        val url = "$ENDPOINT/?Action=$ACTION&Version=$VERSION"
        val accessKeyId = settings.balanceUserId?.trim().orEmpty()
        val secretAccessKey = token?.concatToString().orEmpty()
        if (accessKeyId.isEmpty() || secretAccessKey.isEmpty()) {
            // 缺任一凭据就发无鉴权请求，让上游用 401 告诉我们查不了（与 defaultKey 缺失同一约定）。
            return ProbeRequest(method = "GET", url = url, headers = emptyList(), protocol = null)
        }
        val signed = signer.signGet(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            host = HOST,
            path = "/",
            queryParams = listOf("Action" to ACTION, "Version" to VERSION),
        )
        return ProbeRequest(
            method = "GET",
            url = url,
            headers = listOf(
                "Authorization" to signed.authorization,
                "X-Date" to signed.xDate,
                "X-Content-Sha256" to signed.contentSha256,
                "Host" to HOST,
            ),
            protocol = null,
        )
    }

    override fun parse(status: Int, body: String): BalanceSnapshot {
        if (status !in 200..299) {
            throw BalanceParseException(kind, "http $status")
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw BalanceParseException(kind, "no_json")
        val result = root["Result"]?.jsonObject
            ?: throw BalanceParseException(kind, "missing_result")

        val amount = result["AvailableBalance"]?.jsonPrimitive?.doubleOrNull
            ?: result["CashBalance"]?.jsonPrimitive?.doubleOrNull
            ?: throw BalanceParseException(kind, "missing_balance_fields")

        return BalanceSnapshot(
            amount = amount,
            used = null,
            currency = "CNY",
            raw = body,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val HOST = "open.volcengineapi.com"
        const val ENDPOINT = "https://$HOST"
        const val ACTION = "QueryBalanceAcct"
        const val VERSION = "2022-01-01"
    }
}
