package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 余额适配器（§9.2）纯函数测试（测试 4、5）。
 *
 * 直接读 `fixtures/balance/` 里的真实脱敏响应（M0.5 采集）。两条回归是踩点逼出来的：
 * - newapi `/api/status` 是 5398 字节、`quota_per_unit` 排在公告之后——适配器**不许截断**。
 * - `quota_display_type` 不是必选（Agent Router 没有、JustDoWork 有）。
 */
class BalanceAdapterTest {

    private fun fixture(name: String): String =
        File("src/test/resources/fixtures/balance/$name").readText()

    // ------------------------------------------------------------------ newapi

    @Test
    fun `newapi 解析 quota 与 used_quota 除以换算比`() {
        val adapter = NewApiAdapter()
        val body = fixture("newapi-user-self-agentrouter.json")
        val snapshot = adapter.parse(200, body)
        // quota = 226870, quotaPerUnit 默认 500000 → 0.45374
        assertEquals(226870.0 / 500000.0, snapshot.amount!!, 1e-9)
        // used_quota = 566773130 → 1133.54626
        assertEquals(566773130.0 / 500000.0, snapshot.used!!, 1e-9)
        assertEquals("USD", snapshot.currency)
        assertNull(snapshot.error)
    }

    @Test
    fun `newapi 缺 quota_display_type 不整条失败按 USD`() {
        val adapter = NewApiAdapter()
        val body = fixture("newapi-user-self-agentrouter.json")
        // Agent Router 的 /api/user/self 没有 quota_display_type 字段，这里断言按 USD 兜底。
        val snapshot = adapter.parse(200, body)
        assertEquals("USD", snapshot.currency)
    }

    @Test
    fun `newapi calibrate 从 status 里读 quota_per_unit`() {
        val adapter = NewApiAdapter()
        val status = fixture("newapi-status-agentrouter.json")
        // 不截断：quota_per_unit 排在公告之后，仍能读到 500000。
        assertEquals(500000.0, adapter.calibrateQuotaPerUnit(status)!!, 1e-9)
    }

    @Test
    fun `newapi calibrate 缺字段返回 null`() {
        val adapter = NewApiAdapter()
        assertNull(adapter.calibrateQuotaPerUnit("""{"data":{}}"""))
        assertNull(adapter.calibrateQuotaPerUnit("not-json"))
    }

    @Test
    fun `newapi 缺 data 抛解析失败`() {
        val adapter = NewApiAdapter()
        val e = runCatching { adapter.parse(200, """{"success":true}""") }.exceptionOrNull()
        assertTrue(e is BalanceParseException)
        assertEquals(BalanceKind.NEWAPI, (e as BalanceParseException).kind)
        // 异常消息里不带原始 body（红线 32）
        assertTrue(!e.message!!.contains("success"))
    }

    @Test
    fun `newapi 非 2xx 抛解析失败`() {
        val adapter = NewApiAdapter()
        val e = runCatching { adapter.parse(401, """{"error":"unauthorized"}""") }.exceptionOrNull()
        assertTrue(e is BalanceParseException)
    }

    // ------------------------------------------------------------------ deepseek

    @Test
    fun `deepseek 解析字符串 total_balance 与 currency`() {
        val adapter = DeepSeekAdapter()
        val body = fixture("deepseek-user-balance.json")
        val snapshot = adapter.parse(200, body)
        // total_balance 是字符串 "0.89"，必须 parse（§9.2）
        assertEquals(0.89, snapshot.amount!!, 1e-9)
        assertEquals("CNY", snapshot.currency)
        assertNull(snapshot.used)
    }

    @Test
    fun `deepseek 缺 balance_infos 抛解析失败`() {
        val adapter = DeepSeekAdapter()
        val e = runCatching { adapter.parse(200, """{"is_available":true}""") }.exceptionOrNull()
        assertTrue(e is BalanceParseException)
    }

    // ------------------------------------------------------------------ formatMoney

    @Test
    fun `formatMoney 定点舍入到两位小数`() {
        assertEquals("$42.10", FormatMoney.format(42.099999999999994, "USD"))
        assertEquals("¥358.00", FormatMoney.format(358.0, "CNY"))
    }

    @Test
    fun `formatMoney 相加前先舍入`() {
        // 0.1 + 0.2 若用原始 Double 相加会得到 0.30000000000000004
        val sum = FormatMoney.add(0.1, 0.2).toDouble()
        assertEquals(0.30, sum, 1e-9)
    }

    @Test
    fun `formatMoney 未知币种不给符号`() {
        assertEquals("42.10 UNKNOWN", FormatMoney.format(42.1, "UNKNOWN"))
    }

    // ------------------------------------------------------------------ customJson

    @Test
    fun `customJson 解析点路径与数组下标`() {
        val adapter = CustomJsonAdapter()
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"data":{"balance_infos":[{"total":123.45}]}}""",
        )
        assertEquals(123.45, adapter.resolvePath(root, "data.balance_infos[0].total")!!.toString().toDouble(), 1e-9)
    }

    // ------------------------------------------------------------------ registry

    @Test
    fun `registry 按 kind 选适配器`() {
        assertEquals(BalanceKind.NEWAPI, BalanceRegistry.forProvider(kindOf(BalanceKind.NEWAPI))?.kind)
        assertEquals(BalanceKind.DEEPSEEK, BalanceRegistry.forProvider(kindOf(BalanceKind.DEEPSEEK))?.kind)
        assertEquals(BalanceKind.OPENROUTER, BalanceRegistry.forProvider(kindOf(BalanceKind.OPENROUTER))?.kind)
        assertNull(BalanceRegistry.forProvider(kindOf(BalanceKind.NONE)))
    }

    private fun kindOf(kind: BalanceKind) = com.lc33.tokenvault.domain.model.Provider(
        name = "t",
        apiBaseUrl = "https://x.com",
        apiRoot = "https://x.com",
        balanceKind = kind,
    )
}
