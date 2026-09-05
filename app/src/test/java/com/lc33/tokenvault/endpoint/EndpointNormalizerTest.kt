package com.lc33.tokenvault.endpoint

import com.lc33.tokenvault.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL 规范化（计划.md §14.3 测试 1）。
 *
 * 三条示例数据是 §5.2 那张表里的固定用例，M0.5 也用它们打过真实请求，所以这里的
 * 期望值不是推测出来的。
 */
class EndpointNormalizerTest {

    private fun ok(input: String, overrides: Map<Protocol, String> = emptyMap()): ApiEndpointSet {
        val result = normalizeBaseUrl(input, overrides)
        assertTrue("期望成功，实际 $result", result is NormalizeResult.Ok)
        return (result as NormalizeResult.Ok).endpoints
    }

    private fun err(input: String): EndpointError {
        val result = normalizeBaseUrl(input)
        assertTrue("期望失败，实际 $result", result is NormalizeResult.Err)
        return (result as NormalizeResult.Err).error
    }

    @Test
    fun `示例数据三条`() {
        val air = ok("https://ps.air-outer.com/v1")
        assertEquals("https://ps.air-outer.com", air.apiRoot)
        assertEquals("v1", air.ver)
        assertEquals("https://ps.air-outer.com", air.origin)
        assertEquals("https://ps.air-outer.com/v1/chat/completions", air.byProtocol[Protocol.CHAT])
        assertEquals("https://ps.air-outer.com/v1/responses", air.byProtocol[Protocol.RESPONSES])
        assertEquals("https://ps.air-outer.com/v1/messages", air.byProtocol[Protocol.ANTHROPIC])
        assertEquals("https://ps.air-outer.com/v1/models", air.modelsUrl)

        val jdw = ok("https://api.justwoker.icu/v1")
        assertEquals("https://api.justwoker.icu", jdw.apiRoot)
        assertEquals("https://api.justwoker.icu/v1/messages", jdw.byProtocol[Protocol.ANTHROPIC])

        val ds = ok("https://api.deepseek.com/v1")
        assertEquals("https://api.deepseek.com", ds.apiRoot)
        assertEquals("https://api.deepseek.com/v1/chat/completions", ds.byProtocol[Protocol.CHAT])
    }

    /**
     * DeepSeek 的 Anthropic 端点在 `/anthropic` 下——M0.5 实测确认：默认路径
     * `/v1/messages` 返回 404 且响应体为空，`/anthropic/v1/messages` 才是 200。
     * 这就是 `pathOverrides` 存在的理由。
     */
    @Test
    fun `路径覆盖`() {
        val ds = ok(
            "https://api.deepseek.com/v1",
            mapOf(Protocol.ANTHROPIC to "/anthropic/v1/messages"),
        )
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", ds.byProtocol[Protocol.ANTHROPIC])
        // 覆盖只影响被覆盖的那个协议
        assertEquals("https://api.deepseek.com/v1/chat/completions", ds.byProtocol[Protocol.CHAT])
    }

    @Test
    fun `无 scheme 补 https`() {
        val e = ok("api.deepseek.com/v1")
        assertEquals("https://api.deepseek.com", e.apiRoot)
        assertTrue(!e.insecure)
    }

    @Test
    fun `带端口`() {
        val e = ok("http://192.168.1.9:3000/v1")
        assertEquals("http://192.168.1.9:3000", e.apiRoot)
        assertEquals("http://192.168.1.9:3000", e.origin)
        // 自建 new-api 常见形态。insecure 为真时调用方必须要求显式开 allowInsecure（§7.5）
        assertTrue(e.insecure)
    }

    @Test
    fun `带子路径`() {
        val e = ok("https://openrouter.ai/api/v1")
        assertEquals("https://openrouter.ai/api", e.apiRoot)
        assertEquals("https://openrouter.ai", e.origin)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", e.byProtocol[Protocol.CHAT])
    }

    @Test
    fun `多余斜杠与空白`() {
        val e = ok("  https://api.deepseek.com/v1///  ")
        assertEquals("https://api.deepseek.com", e.apiRoot)
    }

    @Test
    fun `没有版本段时用默认值且不剥路径`() {
        val e = ok("https://api.example.com/openai")
        assertEquals("https://api.example.com/openai", e.apiRoot)
        assertEquals("v1", e.ver)
        assertEquals("https://api.example.com/openai/v1/models", e.modelsUrl)
    }

    @Test
    fun `v1beta 也算版本段`() {
        val e = ok("https://generativelanguage.googleapis.com/v1beta")
        assertEquals("https://generativelanguage.googleapis.com", e.apiRoot)
        assertEquals("v1beta", e.ver)
    }

    /** 带 query 必须报错，不允许静默丢掉——丢掉会得到一个永远 401 的端点。 */
    @Test
    fun `带 query 或 fragment 报错`() {
        assertEquals(EndpointError.HasQueryOrFragment, err("https://host/v1?key=abc"))
        assertEquals(EndpointError.HasQueryOrFragment, err("https://host/v1#frag"))
    }

    @Test
    fun `非法输入`() {
        assertEquals(EndpointError.Empty, err("   "))
        assertEquals(EndpointError.UnsupportedScheme, err("ftp://host/v1"))
        assertEquals(EndpointError.NoHost, err("https:///v1"))
    }
}
