package com.lc33.tokenvault.balance

import com.lc33.tokenvault.crypto.CryptoProvider
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.platform.nowMillis as platformNowMillis
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 火山引擎 OpenAPI V4 HMAC-SHA256 签名器（header 签名模式，billing 服务）。
 *
 * 逐步对照官方 Python SDK `volcenginesdkcore/signv4.py` 的 `SignerV4.sign`：
 * - `X-Date` / `X-Content-Sha256` 由签名器算出并由调用方写回头部，签名结果只进
 *   `Authorization` 头；查询参数**不带**任何 `X-*` 签名参数（那是预签名 URL 流）。
 * - 参与签名的头固定为 `host;x-content-sha256;x-date`（按 ASCII 排序）。
 * - URL 编码为 RFC 3986：不保留字符只有 `A-Za-z0-9-_.~`，其余按 UTF-8 字节转 `%XX`。
 *
 * @param nowMillis 可注入的墙上时钟（红线 20：balance 包不许直接读平台时间；
 *   测试注入固定值，让签名结果可复现）。默认走 `platform/TimeNow` 的 expect 实现。
 */
class VolcengineSigner(
    private val provider: CryptographyProvider = CryptoProvider.provider,
    private val nowMillis: () -> Long = { platformNowMillis() },
) {

    /** 签名产物：调用方要把这三个值原样写进请求头。 */
    data class Signed(
        val authorization: String,
        val xDate: String,
        val contentSha256: String,
    )

    /**
     * 对空 body 的 GET 请求签名。
     *
     * @param queryParams 业务查询参数（如 Action/Version），签名器负责编码与排序。
     */
    fun signGet(
        accessKeyId: String,
        secretAccessKey: String,
        host: String,
        path: String,
        queryParams: List<Pair<String, String>>,
    ): Signed {
        val xDate = formatXDate(nowMillis())
        val shortDate = xDate.substring(0, 8)
        val bodyHash = EMPTY_BODY_SHA256

        // 官方实现对"编码后"的键值对排序，这里保持一致。
        val canonicalQuery = queryParams
            .map { (k, v) -> urlEncode(k) to urlEncode(v) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (k, v) -> "$k=$v" }

        // 规范头部每行自带行尾 '\n'，joinToString 再补一个——中间那个空行是官方格式的一部分，不是笔误。
        val canonicalHeaders = "host:$host\nx-content-sha256:$bodyHash\nx-date:$xDate\n"
        val signedHeaders = "host;x-content-sha256;x-date"
        val canonicalRequest = listOf(
            "GET",
            path,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            bodyHash,
        ).joinToString("\n")

        val credentialScope = "$shortDate/$REGION/$SERVICE/request"
        val stringToSign = listOf(ALGORITHM, xDate, credentialScope, sha256Hex(canonicalRequest))
            .joinToString("\n")

        val signingKey = deriveSigningKey(secretAccessKey, shortDate)
        val signature = hmacHex(signingKey, stringToSign)
        signingKey.zeroize()

        return Signed(
            authorization = "$ALGORITHM Credential=$accessKeyId/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature",
            xDate = xDate,
            contentSha256 = bodyHash,
        )
    }

    /** 派生链与官方一致：kDate → kRegion → kService → kSigning("request")。 */
    private fun deriveSigningKey(secretAccessKey: String, shortDate: String): ByteArray {
        val kDate = hmacBytes(secretAccessKey.encodeToByteArray(), shortDate)
        val kRegion = hmacBytes(kDate, REGION)
        val kService = hmacBytes(kRegion, SERVICE)
        return hmacBytes(kService, "request")
            .also { kDate.zeroize(); kRegion.zeroize(); kService.zeroize() }
    }

    private fun hmacBytes(key: ByteArray, msg: String): ByteArray {
        val hmac = provider.get(HMAC)
        val decodedKey = hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
        return decodedKey.signatureGenerator().generateSignatureBlocking(msg.encodeToByteArray())
    }

    private fun hmacHex(key: ByteArray, msg: String): String = hmacBytes(key, msg).toHexLower()

    private fun sha256Hex(data: String): String =
        provider.get(SHA256).hasher().hashBlocking(data.encodeToByteArray()).toHexLower()

    /** UTC 的 `yyyyMMdd'T'HHmmss'Z'`（官方用 `datetime.utcnow().strftime`）。 */
    private fun formatXDate(millis: Long): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)
        return buildString {
            append(t.year.toString().padStart(4, '0'))
            append(t.monthNumber.pad2())
            append(t.dayOfMonth.pad2())
            append('T')
            append(t.hour.pad2())
            append(t.minute.pad2())
            append(t.second.pad2())
            append('Z')
        }
    }

    private fun Int.pad2(): String = toString().padStart(2, '0')

    /** RFC 3986 百分号编码：只放行 `-_.~` 与字母数字，其余字节转大写 `%XX`。 */
    private fun urlEncode(s: String): String {
        val bytes = s.encodeToByteArray()
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append(HEX_DIGITS[(b.toInt() shr 4) and 0xF]).append(HEX_DIGITS[b.toInt() and 0xF])
            }
        }
        return sb.toString()
    }

    private fun ByteArray.toHexLower(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS_LOWER[v ushr 4]).append(HEX_DIGITS_LOWER[v and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        const val REGION = "cn-north-1"
        const val SERVICE = "billing"
        const val ALGORITHM = "HMAC-SHA256"

        /** 空请求体的 SHA-256（GET 无 body）。 */
        const val EMPTY_BODY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        const val HEX_DIGITS = "0123456789ABCDEF"
        const val HEX_DIGITS_LOWER = "0123456789abcdef"
    }
}
