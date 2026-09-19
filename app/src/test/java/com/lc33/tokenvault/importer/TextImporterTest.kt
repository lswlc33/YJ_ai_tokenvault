package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本导入解析器（计划.md §14.3 测试 8）。
 *
 * 输入是提交进仓库的脱敏 fixture（`sample_import.txt`），它与真实 `示例数据.md` 逐字节
 * 等价——同样的字段顺序、空行位置、"访问令牌值在下一行"、"备注 无"、`DeepSeek V4 Pro`
 * 这些边界都在，只是密钥 / 令牌 / 密码换成了假值。
 */
class TextImporterTest {

    private fun loadFixture(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("sample_import.txt")
            ?: error("sample_import.txt 缺失")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun parse(text: String): List<ParsedRecord> {
        val result = TextImporter.parse(text)
        assertTrue("不应有解析错误，实际 ${result.errors}", result.errors.isEmpty())
        return result.records
    }

    @Test
    fun `脱敏 fixture 三供应商`() {
        val records = parse(loadFixture())
        assertEquals(4, records.size)

        val air = records[0]
        val jdw = records[1]
        val deepseek = records[2]
        val multi = records[3]

        // ---- Agent Router ----
        assertEquals("Agent Router", air.name)
        assertEquals("公司专用账号", air.note)
        assertEquals("https://ps.air-outer.com", air.websiteUrl)
        assertEquals("https://ps.air-outer.com/v1", air.apiBaseUrl)
        assertEquals(setOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC), air.supportedProtocols)
        assertEquals(1, air.keys.size)
        assertEquals("主号", air.keys[0].label)
        assertEquals(3, air.models.size)
        assertEquals(BalanceKind.NEWAPI, air.balanceKind)
        assertEquals("https://ps.air-outer.com", air.balanceBaseUrl)
        assertEquals("TESTtoken0000000000000000000000000001=", air.balanceToken!!.concatToString())
        assertEquals("199628", air.balanceUserId)

        // ---- JustDoWork ----
        assertEquals("JustDoWork", jdw.name)
        assertEquals("备用账号", jdw.note)
        assertEquals(setOf(Protocol.ANTHROPIC), jdw.supportedProtocols)
        assertEquals(2, jdw.models.size)
        assertEquals(BalanceKind.NEWAPI, jdw.balanceKind)

        // ---- DeepSeek：备注 无 → null；官方接口按 host 推断 deepseek ----
        assertEquals("DeepSeek", deepseek.name)
        assertNull(deepseek.note)
        assertEquals(setOf(Protocol.RESPONSES, Protocol.CHAT), deepseek.supportedProtocols)
        assertEquals(BalanceKind.DEEPSEEK, deepseek.balanceKind)

        // ---- 多账号 ----
        assertEquals(2, multi.accounts.size)
    }

    @Test
    fun `密钥与模型总数`() {
        val records = parse(loadFixture())
        val keyCount = records.sumOf { it.keys.size }
        val modelCount = records.sumOf { it.models.size }
        assertEquals(4, keyCount)
        assertEquals(8, modelCount)
    }

    @Test
    fun `DeepSeek V4 Pro 标 needsReview`() {
        val records = parse(loadFixture())
        val deepseek = records.first { it.name == "DeepSeek" }
        val model = deepseek.models.first { it.modelId == "DeepSeek V4 Pro" }
        assertTrue(model.needsReview)
        assertTrue(deepseek.issues.contains(ImportIssue.MODEL_NAME_REVIEW))
        // 其它小写无空格的模型不标
        assertTrue(deepseek.models.first { it.modelId == "deepseek-v4-flash" }.let { !it.needsReview })
    }

    @Test
    fun `协议分配正确`() {
        val records = parse(loadFixture())
        val air = records.first { it.name == "Agent Router" }
        assertEquals(
            mapOf(
                "gpt-5.6-sol" to Protocol.RESPONSES,
                "claude-opus-5" to Protocol.ANTHROPIC,
                "deepseek-v4-flash" to Protocol.RESPONSES,
            ),
            air.models.associate { it.modelId to it.protocol },
        )
    }

    @Test
    fun `平台账号分组`() {
        val records = parse(loadFixture())
        val multi = records.first { it.name == "多账号" }
        assertEquals(2, multi.accounts.size)
        assertEquals("公司主号", multi.accounts[0].label)
        assertEquals("company@example.com", multi.accounts[0].username!!.concatToString())
        assertEquals("Password1", multi.accounts[0].password!!.concatToString())
        assertEquals("备用号", multi.accounts[1].label)
        assertEquals("13800138000", multi.accounts[1].username!!.concatToString())
        assertEquals("Password2", multi.accounts[1].password!!.concatToString())
    }

    // ------------------------------------------------------------------ 块字段终止规则（三组用例）

    @Test
    fun `块字段终止规则一 支持端点类型后有空行`() {
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            支持端点类型

            - Chat Completions
            - Anthropic Messages
            余额查询类型 NewAPI
        """.trimIndent()
        val record = parse(text).single()
        assertEquals(setOf(Protocol.CHAT, Protocol.ANTHROPIC), record.supportedProtocols)
        assertEquals(BalanceKind.NEWAPI, record.balanceKind)
    }

    @Test
    fun `块字段终止规则二 模型列表后紧跟余额查询类型`() {
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            支持端点类型
            - Chat
            模型列表
            gpt-5.6-sol Chat
            余额查询类型 NewAPI
        """.trimIndent()
        val record = parse(text).single()
        // `余额查询类型 NewAPI` 是字段不是模型
        assertEquals(1, record.models.size)
        assertEquals("gpt-5.6-sol", record.models[0].modelId)
        assertEquals(BalanceKind.NEWAPI, record.balanceKind)
    }

    @Test
    fun `块字段终止规则三 最后一条记录没有分隔符`() {
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            模型列表
            m1 Chat
            余额查询类型 NewAPI
        """.trimIndent()
        val record = parse(text).single()
        assertEquals(1, record.models.size)
        assertEquals(BalanceKind.NEWAPI, record.balanceKind)
    }

    @Test
    fun `访问令牌值在下一行`() {
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            余额查询类型 NewAPI
            请求地址 https://a.com
            访问令牌(在个人安全设置里获取)
            TESTtoken1234567890=
            用户ID 123
        """.trimIndent()
        val record = parse(text).single()
        assertEquals("TESTtoken1234567890=", record.balanceToken!!.concatToString())
        assertEquals("123", record.balanceUserId)
    }

    @Test
    fun `备注无变null`() {
        val text = """
            供应商名称 A
            备注 无
            API请求地址 https://a.com/v1
        """.trimIndent()
        val record = parse(text).single()
        assertNull(record.note)
    }

    @Test
    fun `单个连字符的备注不再被当成空值`() {
        // EMPTY_VALUES 里曾有 `-`：真实备注就写"-"（"待补"的速记）时整条备注被吞成 null，
        // 而导出侧的空值一律写 `无`，所以 `-` 只会误伤、不会漏认。
        val text = """
            供应商名称 A
            备注 -
            API请求地址 https://a.com/v1
        """.trimIndent()
        assertEquals("-", parse(text).single().note)
    }

    @Test
    fun `登录方式解析后要落进账号`() {
        // 回归：解析写进了 acc.loginMethods，构造 ParsedAccount 时漏传，
        // 于是导入后的账号永远没有登录方式。
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            平台账号 company@example.com
            平台密码 Password1
            账号备注 公司主号
            登录地址 https://a.com/login
            登录方式 github，linuxdo
        """.trimIndent()
        val account = parse(text).single().accounts.single()
        assertEquals(
            setOf(LoginMethod.GITHUB, LoginMethod.LINUX_DO),
            account.loginMethods,
        )
        assertEquals("https://a.com/login", account.loginUrl)
    }

    @Test
    fun `登录方式的斜杠与逗号分隔与未知值`() {
        // 分隔符历史上认逗号、空格与 `/`；未知值跳过而不是让整份导入失败。
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            平台账号 a@example.com
            登录方式 github/wechat
        """.trimIndent()
        val account = parse(text).single().accounts.single()
        assertEquals(setOf(LoginMethod.GITHUB), account.loginMethods)
    }

    @Test
    fun `换算比与余额配置与路径覆盖落到记录上`() {
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            路径覆盖 chat /v1/chat/completions
            余额查询类型 customJson
            请求地址 https://a.com
            余额配置 {"path":"/api/user/self","valuePath":"data.quota"}
            换算比 500000.0
            用户ID 199628
        """.trimIndent()
        val record = parse(text).single()
        // `customJson` 的 wireName 带大写，而值先被 lowercase()：大小写敏感就永远读不回来
        assertEquals(BalanceKind.CUSTOM_JSON, record.balanceKind)
        assertEquals(mapOf(Protocol.CHAT to "/v1/chat/completions"), record.pathOverrides)
        assertEquals("""{"path":"/api/user/self","valuePath":"data.quota"}""", record.balanceConfig)
        assertEquals(500000.0, record.quotaPerUnit!!, 0.0)
        assertEquals("199628", record.balanceUserId)
    }

    @Test
    fun `换算比只收正数与路径覆盖认不出别名则跳过`() {
        // 0 / 负数会把余额除成 Infinity；挂错协议的路径覆盖比没有覆盖更糟。
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            路径覆盖 geminipro /v1/x
            余额查询类型 NewAPI
            换算比 0
        """.trimIndent()
        val record = parse(text).single()
        assertTrue(record.pathOverrides.isEmpty())
        assertNull(record.quotaPerUnit)
    }

    @Test
    fun `API Key 只有密钥时按顺序分配 label`() {
        // 旧样本写法（`示例数据.md`）：没有 label，第一张是 `主号`、第二张 `备用 2`。
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            API Key sk-TEST0001
            API Key sk-TEST0002
        """.trimIndent()
        val record = parse(text).single()
        assertEquals(listOf("主号", "备用 2"), record.keys.map { it.label })
        assertEquals("sk-TEST0002", record.keys[1].secret.concatToString())
    }

    @Test
    fun `缺供应商名称报错`() {
        val text = """
            备注 无
            API Key sk-xxx
        """.trimIndent()
        val result = TextImporter.parse(text)
        assertTrue(result.records.isEmpty())
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `协议别名大小写空格括号`() {
        val text = """
            供应商名称 A
            支持端点类型
            - chat completions
            - Responses (原生)
            - Anthropic Messages
            - claude
        """.trimIndent()
        val record = parse(text).single()
        assertEquals(
            setOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC),
            record.supportedProtocols,
        )
    }

    @Test
    fun `模型协议不在支持集合则补入并提示`() {
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            支持端点类型
            - Chat
            模型列表
            claude-opus-5 Anthropic
        """.trimIndent()
        val record = parse(text).single()
        assertTrue(Protocol.ANTHROPIC in record.supportedProtocols)
        assertTrue(record.issues.contains(ImportIssue.MODEL_PROTOCOL_ADDED))
    }

    @Test
    fun `newapi缺凭据标提示`() {
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            余额查询类型 NewAPI
        """.trimIndent()
        val record = parse(text).single()
        assertTrue(record.issues.contains(ImportIssue.BALANCE_MISSING_CREDENTIAL))
    }

    @Test
    fun `请求地址剥离行尾说明文字`() {
        val text = """
            供应商名称 A
            API请求地址 https://a.com/v1
            余额查询类型 NewAPI
            请求地址 https://a.com 不填默认是端点域名
        """.trimIndent()
        val record = parse(text).single()
        assertEquals("https://a.com", record.balanceBaseUrl)
    }
}
