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
    fun `newapi uses persisted calibrated quota per unit`() {
        val snapshot = NewApiAdapter(calibratedQuotaPerUnit = 100_000.0)
            .parse(200, """{"data":{"quota":250000,"used_quota":50000}}""")
        assertEquals(2.5, snapshot.amount!!, 1e-9)
        assertEquals(0.5, snapshot.used!!, 1e-9)
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

    @Test
    fun `deepseek 账号不可用时回失败快照而不是零余额`() {
        // §9.2：`is_available = false` 是"查询成功但账号不可用"，必须与"没钱了"区分开。
        val snapshot = DeepSeekAdapter().parse(
            200,
            """{"is_available":false,"balance_infos":[]}""",
        )
        assertNull(snapshot.amount) // 不许用 amount = 0 兼表失败
        assertTrue(snapshot.failed)
        assertEquals("account_unavailable", snapshot.error)
        // 原始 body 只留在 raw 里供详情页核对，不进异常与日志（红线 32）。
        assertTrue(snapshot.raw!!.contains("is_available"))
    }

    @Test
    fun `deepseek 缺 is_available 字段时照旧解析余额`() {
        // 老版本接口不回这个字段，认不出 null 不能把一次成功的查询判成失败。
        val snapshot = DeepSeekAdapter().parse(
            200,
            """{"balance_infos":[{"currency":"CNY","total_balance":"12.30"}]}""",
        )
        assertEquals(12.30, snapshot.amount!!, 1e-9)
        assertNull(snapshot.error)
    }

    @Test
    fun `deepseek 字符串与布尔形状的 is_available 都不崩`() {
        // `as? JsonPrimitive` 而不是 `jsonObject` / `jsonArray`：字段形状变了要报
        // BalanceParseException，不许抛 IllegalArgumentException 冒到调用方。
        val e = runCatching {
            DeepSeekAdapter().parse(200, """{"is_available":true,"balance_infos":"oops"}""")
        }.exceptionOrNull()
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

    @Test
    fun `formatMoney 舍入不走科学计数法`() {
        // 回归：旧实现剥 `Double.toString` 的十进制外壳，而它在 `|v| >= 1e7` 与 `< 1e-3`
        // 时输出科学计数法，于是这三种金额分别得到"崩 / 999 分 / 1.23 分"。
        assertEquals(0L, FormatMoney.roundedCents(0.0))
        assertEquals(0L, FormatMoney.roundedCents(8.0E-4)) // 0.0008 元 == 不足半分
        assertEquals(0L, FormatMoney.roundedCents(9.98E-4)) // 0.000998 元同样是 0 分
        assertEquals(1_234_567_800L, FormatMoney.roundedCents(1.2345678E7))
        assertEquals(1_234_567_890L, FormatMoney.roundedCents(1.23456789E7))
    }

    @Test
    fun `formatMoney 负数按远离零进位`() {
        // BigDecimal.setScale(2, HALF_UP) 对负数是**远离零**进位：-0.005 → -1 分。
        assertEquals(-300L, FormatMoney.roundedCents(-3.0))
        assertEquals(-1L, FormatMoney.roundedCents(-0.005))
        assertEquals(1L, FormatMoney.roundedCents(0.005))
        assertEquals("-0.01", FormatMoney.centsToPlainString(FormatMoney.roundedCents(-0.005)))
    }

    @Test
    fun `formatMoney 半分值按十进制语义进位`() {
        // 0.145 的 double 其实是 0.14499999999999999…，纯"乘 100 加半分再截断"会掉到 14 分；
        // 容差判定把它救回十进制直觉的 15 分。
        assertEquals(15L, FormatMoney.roundedCents(0.145))
        assertEquals(268L, FormatMoney.roundedCents(2.675))
        // 但真值确实不到半分时不许被容差抬上去。
        assertEquals(14L, FormatMoney.roundedCents(0.1449999996))
    }

    @Test
    fun `formatMoney 极大值与脏数据不崩`() {
        // 1e15 元（千亿级企业账户）：分数落在 Long 内，字符串不丢位、不出 E。
        assertEquals(100_000_000_000_000_000L, FormatMoney.roundedCents(1e15))
        assertEquals("1000000000000000.00", FormatMoney.centsToPlainString(FormatMoney.roundedCents(1e15)))
        assertEquals(0L, FormatMoney.roundedCents(Double.NaN))
        assertEquals(Long.MAX_VALUE, FormatMoney.roundedCents(Double.POSITIVE_INFINITY))
        assertEquals(-Long.MAX_VALUE, FormatMoney.roundedCents(Double.NEGATIVE_INFINITY))
        assertEquals(Long.MAX_VALUE, FormatMoney.roundedCents(1e30))
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

    @Test
    fun `customJson 请求路径缺前导斜杠时自动补上`() {
        // 用户在配置框里写 `api/user/self` 是很自然的，直接串起来会得到
        // `https://hostapi/user/self` —— 一个看着像 404、其实是路径粘错的请求。
        val adapter = CustomJsonAdapter(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"path":"api/user/self","valuePath":"data.quota"}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        val settings = com.lc33.tokenvault.domain.model.KeySettings(
            apiBaseUrl = "https://host.com",
            apiRoot = "https://host.com",
            balanceKind = BalanceKind.CUSTOM_JSON,
        )
        assertEquals("https://host.com/api/user/self", adapter.buildRequest(settings, null, null).url)
    }

    @Test
    fun `customJson 头值不是标量时报解析失败而非类型转换异常`() {
        // 旧写法 `v.jsonPrimitive` 对 `{"headers":{"x":{"a":1}}}` 抛 IllegalArgumentException，
        // 绕开了 BalanceParseException 的"只报字段名"通道。
        val adapter = CustomJsonAdapter(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"headers":{"x-app":{"nested":1}},"valuePath":"a"}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        val settings = com.lc33.tokenvault.domain.model.KeySettings(
            apiBaseUrl = "https://host.com",
            apiRoot = "https://host.com",
            balanceKind = BalanceKind.CUSTOM_JSON,
        )
        val e = runCatching { adapter.buildRequest(settings, null, null) }.exceptionOrNull()
        assertTrue(e is BalanceParseException)
        assertEquals(BalanceKind.CUSTOM_JSON, (e as BalanceParseException).kind)
        assertTrue(e.message!!.contains("bad_header_value_x-app"))
    }

    @Test
    fun `customJson 余额字段是字符串也认`() {
        val adapter = CustomJsonAdapter(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"valuePath":"data.balance","currency":"CNY"}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        val snapshot = adapter.parse(200, """{"data":{"balance":"12.30"}}""")
        assertEquals(12.30, snapshot.amount!!, 1e-9)
        assertEquals("CNY", snapshot.currency)
    }

    // ------------------------------------------------------------------ simple adapters

    @Test
    fun `simple 余额字段在顶层与 data 下都能读到`() {
        val adapter = BuiltinBalanceAdapters.siliconflow()
        // 顶层写法（既有供应商的形状，不能因为下钻 data 而退化）
        assertEquals(
            42.10,
            adapter.parse(200, """{"totalBalance":42.1}""").amount!!,
            1e-9,
        )
        // 公开文档给的是 data 包裹 + 字符串值（**未用真实账号实测**，§9.2）
        val nested = adapter.parse(200, """{"data":{"totalBalance":"123.45"},"code":200}""")
        assertEquals(123.45, nested.amount!!, 1e-9)
        assertEquals("CNY", nested.currency)
    }

    @Test
    fun `simple 两处都没有字段时报缺字段`() {
        val adapter = BuiltinBalanceAdapters.moonshot()
        val e = runCatching { adapter.parse(200, """{"data":{"other":1}}""") }.exceptionOrNull()
        assertTrue(e is BalanceParseException)
        assertTrue(e!!.message!!.contains("missing_available_balance"))
    }

    // ------------------------------------------------------------------ openrouter

    @Test
    fun `openrouter 余额字段是数字还是字符串都认`() {
        // credits 接口历史上回过字符串形态，把它判成"缺字段"等于自己制造"余额总是查不到"。
        val adapter = OpenRouterAdapter()
        val asNumber = adapter.parse(200, """{"data":{"total_credits":100.0,"total_usage":7.5}}""")
        assertEquals(92.5, asNumber.amount!!, 1e-9)
        val asString = adapter.parse(200, """{"data":{"total_credits":"100.0","total_usage":"7.5"}}""")
        assertEquals(92.5, asString.amount!!, 1e-9)
        // `data` 被回成数组/字符串时报解析失败，而不是抛 IllegalArgumentException
        val e = runCatching { adapter.parse(200, """{"data":"oops"}""") }.exceptionOrNull()
        assertTrue(e is BalanceParseException)
    }

    // ------------------------------------------------------------------ registry

    @Test
    fun `registry 按 kind 选适配器`() {
        assertEquals(BalanceKind.NEWAPI, BalanceRegistry.forSettings(kindOf(BalanceKind.NEWAPI))?.kind)
        assertEquals(BalanceKind.DEEPSEEK, BalanceRegistry.forSettings(kindOf(BalanceKind.DEEPSEEK))?.kind)
        assertEquals(BalanceKind.OPENROUTER, BalanceRegistry.forSettings(kindOf(BalanceKind.OPENROUTER))?.kind)
        assertNull(BalanceRegistry.forSettings(kindOf(BalanceKind.NONE)))
    }

    private fun kindOf(kind: BalanceKind) = com.lc33.tokenvault.domain.model.KeySettings(
        apiBaseUrl = "https://x.com",
        apiRoot = "https://x.com",
        balanceKind = kind,
    )
}
