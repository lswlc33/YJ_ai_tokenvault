package com.lc33.tokenvault.endpoint

import com.lc33.tokenvault.domain.Protocol
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 2：协议请求构造（计划.md §14.3，§5.2 + §8.3）。
 *
 * 三种协议的 method / URL / 头 / body 逐字段断言（含 `max_tokens = 16`）。
 * 真实发出的报文在 `shared/jvmTest` 的 `net/HttpEngineTest` 里用 MockEngine 验证。
 */
class ProbeRequestBuilderTest {

    @Test
    fun `CHAT 模型列表是 GET 且带 Bearer`() {
        val req = ProbeRequestBuilder.modelsList(
            "https://api.example.com/v1/models",
            Protocol.CHAT,
            "sk-test".toCharArray(),
        )
        assertEquals("GET", req.method)
        assertEquals("https://api.example.com/v1/models", req.url)
        assertEquals(listOf("Authorization" to "Bearer sk-test"), req.headers)
        assertEquals(null, req.body)
    }

    @Test
    fun `ANTHROPIC 用 x-api-key 加 anthropic-version`() {
        val req = ProbeRequestBuilder.modelsList(
            "https://api.example.com/v1/models",
            Protocol.ANTHROPIC,
            "sk-ant-test".toCharArray(),
        )
        assertEquals(
            listOf(
                "x-api-key" to "sk-ant-test",
                "anthropic-version" to "2023-06-01",
            ),
            req.headers,
        )
    }

    @Test
    fun `CHAT 极简推理 body 含 max_tokens 16`() {
        val req = ProbeRequestBuilder.inference(
            "https://api.example.com/v1/chat/completions",
            Protocol.CHAT,
            "sk-test".toCharArray(),
            "gpt-5.6-sol",
        )
        assertEquals("POST", req.method)
        val body = requireNotNull(req.body)
        assertTrue(body.contains("\"max_tokens\":16"))
        assertTrue(body.contains("\"model\":\"gpt-5.6-sol\""))
    }

    @Test
    fun `RESPONSES 用 max_output_tokens`() {
        val req = ProbeRequestBuilder.inference(
            "https://api.example.com/v1/responses",
            Protocol.RESPONSES,
            "sk-test".toCharArray(),
            "gpt-5.6-sol",
        )
        val responsesBody = requireNotNull(req.body)
        assertTrue(responsesBody.contains("\"max_output_tokens\":16"))
        assertTrue(responsesBody.contains("ping"))
    }

    @Test
    fun `ANTHROPIC 极简推理 body 含 messages`() {
        val req = ProbeRequestBuilder.inference(
            "https://api.example.com/v1/messages",
            Protocol.ANTHROPIC,
            "sk-ant-test".toCharArray(),
            "claude-opus-5",
        )
        val anthropicBody = requireNotNull(req.body)
        assertTrue(anthropicBody.contains("\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]"))
        assertTrue(anthropicBody.contains("\"max_tokens\":16"))
    }

    @Test
    fun `无密钥时基线检测用无效令牌而不是空头`() {
        val req = ProbeRequestBuilder.modelsList(
            "https://api.example.com/v1/models",
            Protocol.CHAT,
            null,
        )
        assertEquals(listOf("Authorization" to "Bearer yj-probe-invalid"), req.headers)
    }

    /** §8.1：Key 上的 `timeoutSeconds` 要一路带到 net 层，这里先看构造端有没有接上。 */
    @Test
    fun `超时随请求透传`() {
        val list = ProbeRequestBuilder.modelsList(
            "https://api.example.com/v1/models",
            Protocol.CHAT,
            "sk-test".toCharArray(),
            timeoutMs = 30_000,
        )
        assertEquals(30_000L, list.timeoutMs)
        val inference = ProbeRequestBuilder.inference(
            "https://api.example.com/v1/chat/completions",
            Protocol.CHAT,
            "sk-test".toCharArray(),
            "gpt-5.6-sol",
            timeoutMs = 45_000,
        )
        assertEquals(45_000L, inference.timeoutMs)
    }

    @Test
    fun `inference body escapes arbitrary strings`() {
        val body = requireNotNull(
            ProbeRequestBuilder.inference(
                "https://api.example.com/v1/chat/completions",
                Protocol.CHAT,
                "sk-test".toCharArray(),
                "model\"quoted",
                prompt = "line1\nline2\\path",
            ).body,
        )
        val parsed = Json.parseToJsonElement(body).toString()
        assertTrue(parsed.contains("model\\\"quoted"))
        assertTrue(parsed.contains("line1\\nline2\\\\path"))
    }
}
