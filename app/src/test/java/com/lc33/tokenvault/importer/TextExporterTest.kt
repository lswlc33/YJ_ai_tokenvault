package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextExporterTest {

    private fun settings(
        root: String,
        protocols: Set<Protocol>,
        balanceKind: BalanceKind,
    ) = KeySettings(
        apiBaseUrl = "$root/v1",
        apiRoot = root,
        supportedProtocols = protocols,
        balanceKind = balanceKind,
        balanceBaseUrl = root,
        balanceUserId = "199628",
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

    @Test
    fun `导出再导入能往返`() {
        val keySettings = settings(
            root = "https://ps.air-outer.com",
            protocols = setOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC),
            balanceKind = BalanceKind.NEWAPI,
        )
        val text = TextExporter.exportProvider(
            provider = provider(),
            keys = listOf(key("主号", keySettings) to "sk-TEST0000000000000000000000000001"),
            accounts = listOf(
                TextExporter.ExportedAccount("公司主号", "company@example.com", "Password1"),
            ),
            models = listOf(
                AiModel(providerId = 0, modelId = "gpt-5.6-sol", protocol = Protocol.RESPONSES),
                AiModel(providerId = 0, modelId = "claude-opus-5", protocol = Protocol.ANTHROPIC),
            ),
        )

        val parsed = TextImporter.parse(text)
        assertTrue(parsed.errors.isEmpty())
        val record = parsed.records.single()
        assertEquals("Agent Router", record.name)
        assertEquals("公司专用账号", record.note)
        assertEquals("https://ps.air-outer.com", record.websiteUrl)
        assertEquals(setOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC), record.supportedProtocols)
        assertEquals(1, record.keys.size)
        assertEquals(2, record.models.size)
        assertEquals(BalanceKind.NEWAPI, record.balanceKind)
        assertEquals(1, record.accounts.size)
        assertEquals("company@example.com", record.accounts[0].username!!.concatToString())
    }

    @Test
    fun `备注无导出为无再导入变null`() {
        val keySettings = settings("https://ps.air-outer.com", setOf(Protocol.CHAT), BalanceKind.NONE)
        val text = TextExporter.exportProvider(
            provider = provider(note = null),
            keys = listOf(key("主号", keySettings) to "sk-TEST"),
            accounts = emptyList(),
            models = emptyList(),
        )
        val record = TextImporter.parse(text).records.single()
        assertEquals(null, record.note)
    }

    @Test
    fun `官方接口导出后能按host推断回deepseek`() {
        val keySettings = settings(
            root = "https://api.deepseek.com",
            protocols = setOf(Protocol.CHAT, Protocol.RESPONSES),
            balanceKind = BalanceKind.DEEPSEEK,
        )
        val text = TextExporter.exportProvider(
            provider = Provider(name = "DeepSeek"),
            keys = listOf(key("主号", keySettings) to "sk-TEST"),
            accounts = emptyList(),
            models = emptyList(),
        )
        val record = TextImporter.parse(text).records.single()
        assertEquals(BalanceKind.DEEPSEEK, record.balanceKind)
    }
}
