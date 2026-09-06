package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向导出（§11.3）。
 *
 * 核心断言是**往返**：导出的文本再喂给 [TextImporter] 能解析回同样的东西——
 * 这证明导出格式与导入格式真的是同一套。否则"与应用双向"就是一句空话。
 */
class TextExporterTest {

    private fun sampleProvider() = Provider(
        name = "Agent Router",
        note = "公司专用账号",
        websiteUrl = "https://ps.air-outer.com",
        apiBaseUrl = "https://ps.air-outer.com/v1",
        apiRoot = "https://ps.air-outer.com",
        apiVersion = "v1",
        supportedProtocols = setOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC),
        balanceKind = BalanceKind.NEWAPI,
        balanceBaseUrl = "https://ps.air-outer.com",
        balanceUserId = "199628",
    )

    @Test
    fun `导出再导入能往返`() {
        val text = TextExporter.exportProvider(
            provider = sampleProvider(),
            keys = listOf("主号" to "sk-TEST0000000000000000000000000001"),
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
        val text = TextExporter.exportProvider(
            provider = sampleProvider().copy(note = null),
            keys = emptyList(),
            accounts = emptyList(),
            models = emptyList(),
        )
        val record = TextImporter.parse(text).records.single()
        assertEquals(null, record.note)
    }

    @Test
    fun `官方接口导出后能按host推断回deepseek`() {
        val text = TextExporter.exportProvider(
            provider = Provider(
                name = "DeepSeek",
                apiBaseUrl = "https://api.deepseek.com/v1",
                apiRoot = "https://api.deepseek.com",
                apiVersion = "v1",
                supportedProtocols = setOf(Protocol.CHAT, Protocol.RESPONSES),
                balanceKind = BalanceKind.DEEPSEEK,
            ),
            keys = emptyList(),
            accounts = emptyList(),
            models = emptyList(),
        )
        val record = TextImporter.parse(text).records.single()
        assertEquals(BalanceKind.DEEPSEEK, record.balanceKind)
    }
}
