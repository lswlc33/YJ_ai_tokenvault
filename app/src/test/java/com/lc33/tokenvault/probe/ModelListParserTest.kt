package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

        val models = ModelListParser.parse(body, Protocol.CHAT).orEmpty()

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

        val models = ModelListParser.parse(body, Protocol.CHAT).orEmpty()

        assertEquals(listOf(NewDiscoveredModel("deepseek-v4-flash", Protocol.CHAT)), models)
    }

    @Test
    fun `空列表是合法结果`() {
        assertEquals(emptyList<NewDiscoveredModel>(), ModelListParser.parse("""{"data":[]}""", Protocol.CHAT))
    }

    @Test
    fun `解析失败返回 null 而不是空列表`() {
        assertNull(ModelListParser.parse("not-json", Protocol.CHAT))
        assertNull(ModelListParser.parse("""{"object":"list"}""", Protocol.CHAT))
    }
}

