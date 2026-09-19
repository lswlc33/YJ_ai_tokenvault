package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/models` 响应解析（§8.3 三路合并的输入端）。
 *
 * 核心不是"能不能解析出来"，而是**三种结局的边界**：只有 Confirmed（至少一条带 id）
 * 允许往下走到"消失即删"的合并；`data` 为空的 SuspiciousEmpty 与根本不是列表的
 * Unparseable 都必须被引擎挡住——一次网关登录页回的空壳 JSON 不该把用户库里
 * 发现过的模型整片删掉。
 */
class ModelListParserTest {

    @Test
    fun `解析 new-api 端点类型并展开到协议`() {
        val body = """
            {
              "data": [
                {
                  "id": "claude-opus-5",
                  "supported_endpoint_types": ["anthropic", "openai"]
                }
              ]
            }
        """.trimIndent()

        val parsed = ModelListParser.parse(body, Protocol.CHAT)

        assertTrue("应为 Confirmed，实际 $parsed", parsed is ModelListParse.Confirmed)
        val models = (parsed as ModelListParse.Confirmed).models
        assertEquals(
            setOf(
                NewDiscoveredModel("claude-opus-5", Protocol.CHAT),
                NewDiscoveredModel("claude-opus-5", Protocol.RESPONSES),
                NewDiscoveredModel("claude-opus-5", Protocol.ANTHROPIC),
            ),
            models.toSet(),
        )
    }

    @Test
    fun `没有端点类型时使用请求协议`() {
        val body = """{"object":"list","data":[{"id":"deepseek-v4-flash"}]}"""

        val parsed = ModelListParser.parse(body, Protocol.CHAT)

        assertTrue(parsed is ModelListParse.Confirmed)
        assertEquals(listOf(NewDiscoveredModel("deepseek-v4-flash", Protocol.CHAT)), (parsed as ModelListParse.Confirmed).models)
    }

    @Test
    fun `data 为空数组是可疑空，不是确认空`() {
        // "200 但零条带 id"在中转站上几乎总是异常（登录网关、字段改名、上游在发布），
        // 判成"确认没有"就会触发删除。这里锁死它不落删除口径。
        assertEquals(ModelListParse.SuspiciousEmpty, ModelListParser.parse("""{"data":[]}""", Protocol.CHAT))
    }

    @Test
    fun `条目全都没有 id 也算可疑空`() {
        val body = """{"data":[{"object":"model"},{"object":"model"}]}"""
        assertEquals(ModelListParse.SuspiciousEmpty, ModelListParser.parse(body, Protocol.CHAT))
    }

    @Test
    fun `非 JSON 或缺 data 数组判不可解析`() {
        assertEquals(ModelListParse.Unparseable, ModelListParser.parse("not-json", Protocol.CHAT))
        assertEquals(ModelListParse.Unparseable, ModelListParser.parse("""{"object":"list"}""", Protocol.CHAT))
        assertEquals(ModelListParse.Unparseable, ModelListParser.parse(null, Protocol.CHAT))
        assertEquals(ModelListParse.Unparseable, ModelListParser.parse("   ", Protocol.CHAT))
    }

    @Test
    fun `混合时只要有带 id 的条目就是 Confirmed`() {
        // 一条好条目 + 一条缺 id：好条目照常进合并，缺 id 的那条被跳过——
        // "可疑"的判据是**一条可用的都没有**，而不是"存在坏条目"。
        val body = """{"data":[{"id":"gpt-x"},{"object":"model"}]}"""
        val parsed = ModelListParser.parse(body, Protocol.CHAT)
        assertTrue(parsed is ModelListParse.Confirmed)
        assertEquals(listOf(NewDiscoveredModel("gpt-x", Protocol.CHAT)), (parsed as ModelListParse.Confirmed).models)
    }
}
