package com.lc33.tokenvault.balance

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V4 签名器（§9.2）纯函数测试。
 *
 * 黄金向量由官方 Python SDK `volcenginesdkcore/signv4.py` 的算法独立复算
 * （openssl HMAC-SHA256 链），固定时钟 2026-01-15T08:30:45Z：
 * `epochMillis = 1768465845000`。Kotlin 转录只要偏一个字节，签名就对不上。
 */
class VolcengineSignerTest {

    private val fixedMillis = 1768465845000L // 2026-01-15T08:30:45Z（UTC）

    @Test
    fun `V4 签名与官方 SDK 算法的黄金向量一致`() {
        val signer = VolcengineSigner(nowMillis = { fixedMillis })
        val signed = signer.signGet(
            accessKeyId = "ExampleAccessKeyId",
            secretAccessKey = "ExampleSecretAccessKey",
            host = "open.volcengineapi.com",
            path = "/",
            queryParams = listOf("Action" to "QueryBalanceAcct", "Version" to "2022-01-01"),
        )
        assertEquals("20260115T083045Z", signed.xDate)
        assertEquals(
            "HMAC-SHA256 Credential=ExampleAccessKeyId/20260115/cn-north-1/billing/request, " +
                "SignedHeaders=host;x-content-sha256;x-date, " +
                "Signature=bd680cb92943d72ae989382bad73c72c8445026d2553cc54ab40f825d3c06321",
            signed.authorization,
        )
    }

    @Test
    fun `X-Date 的分秒补零与 UTC 语义`() {
        // 1768464005000 = 2026-01-15T08:00:05Z：分钟为 0、秒为个位数，都要补零。
        val signer = VolcengineSigner(nowMillis = { 1768464005000L })
        val signed = signer.signGet("ak", "sk", "open.volcengineapi.com", "/", emptyList())
        assertEquals("20260115T080005Z", signed.xDate)
        // 凭据作用域取 X-Date 前 8 位，签名头里两者必须同源。
        assertEquals(true, signed.authorization.contains("/20260115/cn-north-1/billing/request"))
    }

    @Test
    fun `同一秒内签名可复现`() {
        val signer = VolcengineSigner(nowMillis = { fixedMillis })
        val args = listOf("Action" to "QueryBalanceAcct", "Version" to "2022-01-01")
        val a = signer.signGet("ak", "sk", "open.volcengineapi.com", "/", args)
        val b = signer.signGet("ak", "sk", "open.volcengineapi.com", "/", args)
        assertEquals(a.authorization, b.authorization)
    }

    @Test
    fun `参数顺序不影响规范查询串`() {
        val signer = VolcengineSigner(nowMillis = { fixedMillis })
        val a = signer.signGet("ak", "sk", "host.example", "/", listOf("Version" to "v", "Action" to "a"))
        val b = signer.signGet("ak", "sk", "host.example", "/", listOf("Action" to "a", "Version" to "v"))
        assertEquals(a.authorization, b.authorization)
    }
}
