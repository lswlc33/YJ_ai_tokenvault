package com.lc33.tokenvault.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * models.dev 快照 → 目录行的纯函数测试。
 *
 * 这份 fixture 是**照真实结构手写的截断版**，不是完整快照：4.7 MB 的 `api.json` 进不了
 * 仓库（也不该进），而这里要守的是"怎么把上游读成我们的四个 id"这一层规则。
 * 上游真改了结构时的第一道闸是这条测试，不是用户手机上那次失败的同步。
 *
 * 断言重点是 `key` / `modelId` / `qualifiedId` / `normId` / `canonical` 这五个值——
 * 它们错了不会崩，只会**静默匹配到隔壁厂商**，界面上看着一切正常。
 */
class CatalogParserTest {

    private val snapshotJson = """
        {
          "deepseek": {
            "id": "deepseek",
            "name": "DeepSeek",
            "api": "https://api.deepseek.com",
            "doc": "https://api-docs.deepseek.com",
            "models": {
              "deepseek-chat": {
                "id": "deepseek-chat",
                "name": "DeepSeek Chat",
                "description": "Official chat model",
                "family": "deepseek",
                "attachment": false,
                "reasoning": false,
                "tool_call": true,
                "structured_output": true,
                "temperature": true,
                "open_weights": true,
                "release_date": "2025-01-01",
                "last_updated": "2026-02-01",
                "modalities": {"input": ["text"], "output": ["text"]},
                "limit": {"context": 65536, "output": 8192},
                "cost": {"input": 0.27, "output": 1.1, "cache_read": 0.07}
              }
            }
          },
          "tokengo": {
            "id": "tokengo",
            "name": "TokenGo",
            "api": "https://api.tokengo.com/v1",
            "doc": "https://www.tokengo.com/docs",
            "models": {
              "deepseek/deepseek-chat": {
                "id": "deepseek/deepseek-chat",
                "name": "DeepSeek Chat (resale)",
                "reasoning_options": [{"type": "toggle"}],
                "limit": {"context": 1000000.0, "output": 131072},
                "cost": {"input": 1.4, "output": 4.4, "tiers": [{"context": 200000}]}
              },
              "qwen/qwen3.5-397b-a17b": {
                "id": "qwen/qwen3.5-397b-a17b",
                "name": "Qwen3.5 397B",
                "status": "preview",
                "knowledge": "2025-04"
              }
            }
          }
        }
    """.trimIndent()

    private fun parse() = CatalogParser.parse(CatalogParser.decode(snapshotJson))

    @Test
    fun `厂商取 mapKey 的斜杠前缀而不是外层 slug`() {
        val resale = parse().models.first { it.key == "tokengo/deepseek/deepseek-chat" }
        // 外层是聚合站 tokengo，但用户要的「DeepSeek 一类」来自 mapKey 的前缀。
        assertEquals("deepseek", resale.vendor)
        assertEquals("modelId 保留前缀原样", "deepseek/deepseek-chat", resale.modelId)
        assertEquals("tokengo", resale.providerSlug)
        assertFalse("聚合站那条不是原创", resale.canonical)

        val official = parse().models.first { it.key == "deepseek/deepseek-chat" }
        assertEquals("deepseek", official.vendor)
        assertEquals("deepseek-chat", official.modelId)
        assertTrue(official.canonical)
    }

    @Test
    fun `主键带外层 slug 所以多家重复挂出不会互相覆盖`() {
        val rows = parse().models
        val sameModel = rows.filter { it.qualifiedId == "deepseek/deepseek-chat" }
        assertEquals(
            "同一模型的两条候选都要留下来，否则留下哪一家的价格全看 JSON 顺序",
            listOf("deepseek/deepseek-chat", "tokengo/deepseek/deepseek-chat"),
            sameModel.map { it.key }.sorted(),
        )
        assertEquals("主键必须无重复", rows.size, rows.map { it.key }.distinct().size)
    }

    @Test
    fun `展示名按前缀那家查而不是外层`() {
        val resale = parse().models.first { it.key == "tokengo/deepseek/deepseek-chat" }
        assertEquals("标成 TokenGo 就是把转售方当成了厂商", "DeepSeek", resale.vendorName)
    }

    @Test
    fun `归一化 id 用裸 id 算`() {
        val official = parse().models.first { it.key == "deepseek/deepseek-chat" }
        assertEquals("deepseek-chat", official.normId)
        // 聚合站那条的裸 id 也是 deepseek-chat，两级归一才能落进同一个桶。
        val resale = parse().models.first { it.key == "tokengo/deepseek/deepseek-chat" }
        assertEquals("deepseek-chat", resale.normId)
    }

    @Test
    fun `小数形式的整数上限也能解出来`() {
        // limit 声明成 Double 再转 Int 就是为了这一条：上游写 1000000.0 时
        // 直接声明 Int 会让整次同步在解码那一步炸掉。
        val resale = parse().models.first { it.key == "tokengo/deepseek/deepseek-chat" }
        assertEquals(1_000_000, resale.contextLimit)
        assertEquals(131_072, resale.outputLimit)
    }

    @Test
    fun `未知字段与阶梯价被丢掉而不是让解码失败`() {
        // reasoning_options（数组套对象）和 cost.tiers 都没在 DTO 里声明。
        val resale = parse().models.first { it.key == "tokengo/deepseek/deepseek-chat" }
        assertEquals(1.4, resale.costInput)
        assertFalse(resale.reasoning)
    }

    @Test
    fun `缺字段发 null 与空列表而不是崩溃`() {
        val qwen = parse().models.first { it.key == "tokengo/qwen/qwen3.5-397b-a17b" }
        assertNull(qwen.costInput)
        assertNull(qwen.contextLimit)
        assertNull(qwen.description)
        assertTrue(qwen.inputModalities.isEmpty())
        assertEquals("preview", qwen.status)
        assertEquals("2025-04", qwen.knowledgeCutoff)
        assertEquals("qwen", qwen.vendor)
    }

    @Test
    fun `厂商表一家一行`() {
        val vendors = parse().vendors.associateBy { it.slug }
        assertEquals(2, vendors.size)
        assertEquals("DeepSeek", vendors["deepseek"]?.name)
        assertEquals("https://api-docs.deepseek.com", vendors["deepseek"]?.docUrl)
        assertEquals("https://api.tokengo.com/v1", vendors["tokengo"]?.apiUrl)
    }

    @Test
    fun `布尔能力位按上游原样落位`() {
        val official = parse().models.first { it.key == "deepseek/deepseek-chat" }
        assertTrue(official.toolCall)
        assertTrue(official.structuredOutput)
        assertTrue(official.openWeights)
        assertFalse(official.attachment)
        assertFalse(official.reasoning)
        assertEquals(listOf("text"), official.inputModalities)
    }

    @Test
    fun `上游 name 给空串时退回 slug 而不是留一个空分组标题`() {
        val json = """{"weird":{"id":"weird","name":"","models":{"m":{"id":"m"}}}}"""
        val vendors = CatalogParser.parse(CatalogParser.decode(json)).vendors
        assertEquals("weird", vendors.single().name)
    }
}
