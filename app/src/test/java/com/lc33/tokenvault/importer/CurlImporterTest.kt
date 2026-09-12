package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cURL 供应商导入测试。
 *
 * 密钥全部是 `sk-TEST...` 假值；这里的输入形态对应聊天工具里常见的
 * “Markdown 包住 URL + 反斜杠续行 + 多条命令连续粘贴”。
 */
class CurlImporterTest {

    @Test
    fun `两条命令导入两家供应商`() {
        val text = """
            curl [https://motomoto.lol/v1/chat/completions](https://motomoto.lol/v1/chat/completions) \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer sk-TEST0000000000000000000000000000000000000000000000000001" \
            -d '{"model":"gpt-5.6-terra","messages":[{"role":"user","content":"Say hello in one sentence."}]}'

            curl [https://iceberg.tiktok.vip/v1/chat/completions](https://iceberg.tiktok.vip/v1/chat/completions) \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer sk-TEST0000000000000000000000000000000000000000000000000002" \
            -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Say hello in one sentence."}]}'
        """.trimIndent()

        val result = CurlImporter.parse(text)

        assertTrue("不应有解析错误，实际 ${result.errors}", result.errors.isEmpty())
        assertEquals(2, result.records.size)

        val first = result.records[0]
        assertEquals("motomoto", first.name)
        assertEquals("https://motomoto.lol", first.websiteUrl)
        assertEquals("https://motomoto.lol/v1", first.apiBaseUrl)
        assertEquals(setOf(Protocol.CHAT), first.supportedProtocols)
        assertEquals(1, first.keys.size)
        assertEquals("主号", first.keys[0].label)
        assertEquals(
            "sk-TEST0000000000000000000000000000000000000000000000000001",
            first.keys[0].secret.concatToString(),
        )
        assertEquals("gpt-5.6-terra", first.models.single().modelId)
        assertEquals(Protocol.CHAT, first.models.single().protocol)

        val second = result.records[1]
        assertEquals("tiktok", second.name)
        assertEquals("https://iceberg.tiktok.vip/v1", second.apiBaseUrl)
        assertEquals("gpt-4o-mini", second.models.single().modelId)
    }

    @Test
    fun `同根地址多条命令合并为一家供应商`() {
        val first = """
            curl https://api.example.com/v1/chat/completions \
            -H "Authorization: Bearer sk-TEST0000000000000000000000000000000000000000000000000003" \
            -d '{"model":"gpt-5.6-terra"}'
        """.trimIndent()
        val second = """
            curl https://api.example.com/v1/responses \
            -H "Authorization: Bearer sk-TEST0000000000000000000000000000000000000000000000000004" \
            -d '{"model":"gpt-5.6-sol"}'
        """.trimIndent()

        val result = CurlImporter.parse("$first\n\n$second")

        assertTrue(result.errors.isEmpty())
        val record = result.records.single()
        assertEquals("example", record.name)
        assertEquals("https://api.example.com/v1", record.apiBaseUrl)
        assertEquals(setOf(Protocol.CHAT, Protocol.RESPONSES), record.supportedProtocols)
        assertEquals(listOf("主号", "备用 2"), record.keys.map { it.label })
        assertEquals(
            listOf("gpt-5.6-terra", "gpt-5.6-sol"),
            record.models.map { it.modelId },
        )
    }

    @Test
    fun `Windows 续行与普通 URL 也支持`() {
        val text = """
            curl https://api.deepseek.com/v1/chat/completions ^
            -H "Authorization: Bearer sk-TEST0000000000000000000000000000000000000000000000000005" ^
            -d "{\"model\":\"deepseek-v4-flash\"}"
        """.trimIndent()

        val record = CurlImporter.parse(text).records.single()
        assertEquals("deepseek", record.name)
        assertEquals("https://api.deepseek.com/v1", record.apiBaseUrl)
        assertEquals("deepseek-v4-flash", record.models.single().modelId)
    }

    @Test
    fun `缺 URL 或密钥记为解析错误`() {
        val noUrl = "curl -H 'Authorization: Bearer sk-TEST1' -d '{\"model\":\"x\"}'"
        val noKey = "curl https://api.example.com/v1/chat/completions -d '{\"model\":\"x\"}'"

        val result = CurlImporter.parse("$noUrl\n\n$noKey")

        assertTrue(result.records.isEmpty())
        assertEquals(listOf("no url", "no key"), result.errors.map { it.message })
    }

    @Test
    fun `query 与 http 地址带出问题提示`() {
        val text = """
            curl 'http://api.example.com/v1/chat/completions?api-key=x' \
            -H 'Authorization: Bearer sk-TEST0000000000000000000000000000000000000000000000000006'
        """.trimIndent()

        val record = CurlImporter.parse(text).records.single()
        assertEquals("http://api.example.com/v1?api-key=x", record.apiBaseUrl)
        assertTrue(ImportIssue.BAD_ENDPOINT in record.issues)
        assertTrue(ImportIssue.INSECURE_ENDPOINT in record.issues)
    }

    @Test
    fun `模型 id 含大写时标记复核`() {
        val text = """
            curl https://api.example.com/v1/chat/completions \
            -H 'Authorization: Bearer sk-TEST0000000000000000000000000000000000000000000000000007' \
            -d '{"model":"GPT 4o"}'
        """.trimIndent()

        val record = CurlImporter.parse(text).records.single()
        assertTrue(record.models.single().needsReview)
        assertTrue(ImportIssue.MODEL_NAME_REVIEW in record.issues)
    }
}
