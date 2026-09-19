package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向导出（§11.1 的写侧）。
 *
 * 核心契约是**导出写字段 = 导入认字段**：这里不校验"导出的样子好不好看"，只校验
 * `import(export(x)) == x`。曾经的两处漏点都由这个断言钉住——
 * 1. `API Key <label> <secret>` 导出、导入却把整段余部当密钥，往返直接出坏密钥；
 * 2. 访问令牌 / 换算比 / 余额配置 / 路径覆盖 / 登录方式只在导入侧认得，导出侧不写，
 *    于是"备份一份、重装后导入"配置全归零。
 */
class TextExporterTest {

    private fun settings(
        root: String,
        protocols: Set<Protocol>,
        balanceKind: BalanceKind,
        pathOverrides: Map<Protocol, String> = emptyMap(),
        quotaPerUnit: Double? = null,
        balanceConfig: String = "{}",
        clientProfileId: Long? = null,
    ) = KeySettings(
        apiBaseUrl = "$root/v1",
        apiRoot = root,
        supportedProtocols = protocols,
        pathOverrides = pathOverrides,
        clientProfileId = clientProfileId,
        balanceKind = balanceKind,
        balanceBaseUrl = root,
        balanceUserId = "199628",
        quotaPerUnit = quotaPerUnit,
        balanceConfig = balanceConfig,
    )

    private fun key(label: String, settings: KeySettings) = ApiKey(
        id = 1,
        providerId = 1,
        label = label,
        note = "",
        secretEnc = ByteArray(1),
        fingerprint = "f",
        settings = settings,
    )

    private fun provider(note: String? = "公司专用账号") = Provider(
        name = "Agent Router",
        note = note,
        websiteUrl = "https://ps.air-outer.com",
    )

    private fun exported(
        label: String,
        settings: KeySettings,
        secret: String,
        balanceToken: String? = null,
    ) = TextExporter.ExportedKey(key = key(label, settings), secret = secret, balanceToken = balanceToken)

    @Test
    fun `导出再导入能往返`() {
        val keySettings = settings(
            root = "https://ps.air-outer.com",
            protocols = setOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC),
            balanceKind = BalanceKind.NEWAPI,
        )
        val secret = "sk-TEST0000000000000000000000000001"
        val text = TextExporter.exportProvider(
            provider = provider(),
            keys = listOf(exported("主号", keySettings, secret)),
            accounts = listOf(
                TextExporter.ExportedAccount("公司主号", "company@example.com", "Password1"),
            ),
            models = listOf(
                AiModel(providerId = 0, modelId = "gpt-5.6-sol", protocol = Protocol.RESPONSES),
                AiModel(providerId = 0, modelId = "claude-opus-5", protocol = Protocol.ANTHROPIC),
            ),
        )

        val parsed = TextImporter.parse(text)
        assertTrue("不应有解析错误，实际 ${parsed.errors}", parsed.errors.isEmpty())
        val record = parsed.records.single()
        assertEquals("Agent Router", record.name)
        assertEquals("公司专用账号", record.note)
        assertEquals("https://ps.air-outer.com", record.websiteUrl)
        assertEquals(setOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC), record.supportedProtocols)
        assertEquals(2, record.models.size)
        assertEquals(1, record.keys.size)
        assertEquals("company@example.com", record.accounts[0].username!!.concatToString())
    }

    @Test
    fun `密钥逐字节往返且 label 里的空格不吃进密钥`() {
        // 回归：导出写 `API Key <label> <secret>`，导入却把行余部全当 secret，
        // 于是"主号 sk-xxx"变成 secret = "主号 sk-xxx"。现在按"最后一个空白段是密钥"切。
        val keySettings = settings("https://ps.air-outer.com", setOf(Protocol.CHAT), BalanceKind.NONE)
        val secret = "sk-TEST0000000000000000000000000000000000000000000000000001"
        val text = TextExporter.exportProvider(
            provider = provider(),
            keys = listOf(
                exported("主号", keySettings, secret),
                exported("工作 备用 号", keySettings, "sk-TEST-second-key"),
            ),
            accounts = emptyList(),
            models = emptyList(),
        )
        val record = TextImporter.parse(text).records.single()
        assertEquals(2, record.keys.size)
        assertTrue(record.keys[0].secret.contentEquals(secret.toCharArray()))
        assertEquals(secret, record.keys[0].secret.concatToString())
        assertEquals("主号", record.keys[0].label)
        // 带空格的 label 也完整回来，密钥不含任何空白
        assertEquals("工作 备用 号", record.keys[1].label)
        assertEquals("sk-TEST-second-key", record.keys[1].secret.concatToString())
    }

    @Test
    fun `备注无导出为无再导入变null`() {
        val keySettings = settings("https://ps.air-outer.com", setOf(Protocol.CHAT), BalanceKind.NONE)
        val text = TextExporter.exportProvider(
            provider = provider(note = null),
            keys = listOf(exported("主号", keySettings, "sk-TEST")),
            accounts = emptyList(),
            models = emptyList(),
        )
        val record = TextImporter.parse(text).records.single()
        assertEquals(null, record.note)
    }

    @Test
    fun `官方接口导出后能读回deepseek`() {
        val keySettings = settings(
            root = "https://api.deepseek.com",
            protocols = setOf(Protocol.CHAT, Protocol.RESPONSES),
            balanceKind = BalanceKind.DEEPSEEK,
        )
        val text = TextExporter.exportProvider(
            provider = Provider(name = "DeepSeek"),
            keys = listOf(exported("主号", keySettings, "sk-TEST")),
            accounts = emptyList(),
            models = emptyList(),
        )
        val record = TextImporter.parse(text).records.single()
        assertEquals(BalanceKind.DEEPSEEK, record.balanceKind)
    }

    @Test
    fun `余额与请求配置全部往返`() {
        // 每一项都是"导入侧认得、导出侧曾经不写"的字段，少写一项就等于一次静默丢配置。
        val config = """{"path":"/api/user/self","valuePath":"data.quota","currency":"CNY"}"""
        val keySettings = settings(
            root = "https://ps.air-outer.com",
            protocols = setOf(Protocol.CHAT, Protocol.ANTHROPIC),
            balanceKind = BalanceKind.NEWAPI,
            pathOverrides = mapOf(
                Protocol.CHAT to "/custom/chat",
                Protocol.ANTHROPIC to "/custom/messages",
            ),
            quotaPerUnit = 500000.0,
            balanceConfig = config,
            clientProfileId = 7L,
        )
        val text = TextExporter.exportProvider(
            provider = provider(),
            keys = listOf(exported("主号", keySettings, "sk-TEST-key", balanceToken = "TESTtoken0000000000000000000000000001=")),
            accounts = listOf(
                TextExporter.ExportedAccount(
                    label = "公司主号",
                    username = "company@example.com",
                    password = "Password1",
                    loginUrl = "https://ps.air-outer.com/login",
                    loginMethods = setOf(LoginMethod.GITHUB, LoginMethod.LINUX_DO),
                ),
            ),
            models = emptyList(),
            clientProfileNames = mapOf(7L to "Claude Code"),
        )
        val record = TextImporter.parse(text).records.single()

        assertEquals(BalanceKind.NEWAPI, record.balanceKind)
        assertEquals("https://ps.air-outer.com", record.balanceBaseUrl)
        assertEquals("199628", record.balanceUserId)
        assertEquals("TESTtoken0000000000000000000000000001=", record.balanceToken!!.concatToString())
        assertEquals(500000.0, record.quotaPerUnit!!, 0.0)
        assertEquals(config, record.balanceConfig)
        assertEquals(
            mapOf(Protocol.CHAT to "/custom/chat", Protocol.ANTHROPIC to "/custom/messages"),
            record.pathOverrides,
        )
        assertEquals("Claude Code", record.clientProfileRef)
        assertEquals(setOf(LoginMethod.GITHUB, LoginMethod.LINUX_DO), record.accounts.single().loginMethods)
        assertEquals("https://ps.air-outer.com/login", record.accounts.single().loginUrl)

        // 解析结果映射到领域配置时同样不能丢（这三项曾经丢在映射那一步）
        val mapped = record.toKeySettings()
        assertEquals(record.pathOverrides, mapped.pathOverrides)
        assertEquals(500000.0, mapped.quotaPerUnit!!, 0.0)
        assertEquals(config, mapped.balanceConfig)
    }

    @Test
    fun `customJson 等带大写的余额类型也往返`() {
        // wireName 是 `customJson`，而导入侧先把值 lowercase() 过：大小写敏感的比较会让
        // 这一档永远读不回来，导出再导入等于把余额查询悄悄关掉。
        for (kind in BalanceKind.entries.filter { it != BalanceKind.NONE }) {
            val keySettings = settings(
                root = "https://x.com",
                protocols = setOf(Protocol.CHAT),
                balanceKind = kind,
            )
            val text = TextExporter.exportProvider(
                provider = Provider(name = "X"),
                keys = listOf(exported("主号", keySettings, "sk-TEST")),
                accounts = emptyList(),
                models = emptyList(),
            )
            val record = TextImporter.parse(text).records.single()
            assertEquals(kind, record.balanceKind)
        }
    }
}
