package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.KeySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 火山引擎余额适配器（§9.2）纯函数测试。
 *
 * 时钟注入固定值，请求构造可完全复现；响应体走内联 JSON（无真实账号 fixture）。
 */
class VolcengineAdapterTest {

    private val fixedMillis = 1768465845000L // 2026-01-15T08:30:45Z（UTC）

    private fun adapter() = VolcengineAdapter(VolcengineSigner(nowMillis = { fixedMillis }))

    private fun settings(userId: String?) = KeySettings(
        apiBaseUrl = "",
        apiRoot = "",
        balanceKind = BalanceKind.VOLCENGINE,
        balanceUserId = userId,
    )

    @Test
    fun `缺 AccessKey 时发无鉴权请求让上游回 401`() {
        val request = adapter().buildRequest(settings(null), defaultKey = null, token = null)
        assertTrue(request.headers.isEmpty())
        assertTrue(request.url.contains("Action=QueryBalanceAcct"))
    }

    @Test
    fun `只有明文令牌没有 AK 也不签名`() {
        val request = adapter().buildRequest(settings(null), defaultKey = null, token = "sk".toCharArray())
        assertTrue(request.headers.isEmpty())
    }

    @Test
    fun `凭据齐全时 Authorization 与三个签名头就位`() {
        val request = adapter().buildRequest(
            settings("ExampleAccessKeyId"),
            defaultKey = null,
            token = "ExampleSecretAccessKey".toCharArray(),
        )
        val names = request.headers.map { it.first }
        assertEquals(listOf("Authorization", "X-Date", "X-Content-Sha256", "Host"), names)
        val authorization = request.headers.first { it.first == "Authorization" }.second
        assertTrue(authorization.startsWith("HMAC-SHA256 Credential=ExampleAccessKeyId/20260115/"))
        assertEquals("20260115T083045Z", request.headers.first { it.first == "X-Date" }.second)
    }

    @Test
    fun `优先解析 AvailableBalance`() {
        val body = """{"Result":{"AvailableBalance":123.45,"CashBalance":999.0,"ArrearsBalance":66.0}}"""
        val snapshot = adapter().parse(200, body)
        assertEquals(123.45, snapshot.amount!!, 1e-9)
        assertEquals("CNY", snapshot.currency)
        assertNull(snapshot.used)
    }

    @Test
    fun `缺 AvailableBalance 退到 CashBalance`() {
        val body = """{"Result":{"CashBalance":"88.5"}}"""
        assertEquals(88.5, adapter().parse(200, body).amount!!, 1e-9)
    }

    @Test
    fun `欠款字段不当余额报`() {
        // ArrearsBalance 是欠款额，出现在响应里也不能被读成余额。
        val body = """{"Result":{"ArrearsBalance":66.0}}"""
        val error = assertThrows(BalanceParseException::class.java) { adapter().parse(200, body) }
        assertTrue(error.message!!.contains("missing_balance_fields"))
    }

    @Test
    fun `非 2xx 与坏 JSON 都按失败抛异常`() {
        assertThrows(BalanceParseException::class.java) { adapter().parse(401, "{}") }
        assertThrows(BalanceParseException::class.java) { adapter().parse(200, "not-json") }
        assertThrows(BalanceParseException::class.java) { adapter().parse(200, """{"Result":{}}""") }
    }

    @Test
    fun `异常消息不带原始响应体`() {
        // 红线 32：body 里可能有账号信息，只许报"哪个字段缺失"。
        val leaky = """{"Result":{"AvailableBalance":"abc"},"AccountEmail":"who@example.com"}"""
        val error = runCatching { adapter().parse(200, leaky) }.exceptionOrNull()
        assertTrue(error is BalanceParseException)
        assertTrue(!error!!.message!!.contains("who@example.com"))
    }
}
