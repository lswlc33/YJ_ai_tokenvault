package com.lc33.tokenvault.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CurlParser（§8.2 推荐路径）纯函数测试（测试 9）。
 *
 * 覆盖：`-H/-A/-X/-d` 解析、续行（`\` 与 `^`）、引号、`$'…'` 转义、自动剔除敏感头、
 * bodyPatch 推断。
 */
class CurlParserTest {

    @Test
    fun `解析 UA 与请求头`() {
        val curl = "curl -A 'ClaudeCode/1.0' -H 'x-app: cli' -H 'anthropic-beta: claude-code-20250219' https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals("ClaudeCode/1.0", result.userAgent)
        assertEquals(
            listOf("x-app" to "cli", "anthropic-beta" to "claude-code-20250219"),
            result.headers,
        )
        assertTrue(result.droppedHeaders.isEmpty())
    }

    @Test
    fun `长选项形式也解析`() {
        val curl = "curl --user-agent 'UA/2.0' --header 'x-custom: v' --request POST https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals("UA/2.0", result.userAgent)
        assertEquals("POST", result.method)
        assertEquals(listOf("x-custom" to "v"), result.headers)
    }

    @Test
    fun `POSIX 续行反斜杠`() {
        val curl = "curl -H 'x-app: cli' \\\n  -H 'x-custom: v' https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals(listOf("x-app" to "cli", "x-custom" to "v"), result.headers)
    }

    @Test
    fun `Windows 续行脱字符`() {
        val curl = "curl -H \"x-app: cli\" ^\n  -A \"UA/1.0\" https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals(listOf("x-app" to "cli"), result.headers)
        assertEquals("UA/1.0", result.userAgent)
    }

    @Test
    fun `带空格的引号参数是一个 token`() {
        val curl = "curl -H \"x-stainless-os: MacOS\" -H \"x-stainless-arch: arm64\" https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals(
            listOf("x-stainless-os" to "MacOS", "x-stainless-arch" to "arm64"),
            result.headers,
        )
    }

    @Test
    fun `单引号内不带空格也正确切分键值`() {
        val curl = "curl -H 'x-app:cli' https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals(listOf("x-app" to "cli"), result.headers)
    }

    @Test
    fun `美元单引号转义形式`() {
        val curl = "curl -H \$\u0027x-app: cli\u0027 https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals(listOf("x-app" to "cli"), result.headers)
    }

    @Test
    fun `自动剔除代码接管的头并告知`() {
        val curl = "curl -H 'Authorization: Bearer sk-xxx' -H 'x-api-key: sk-yyy' " +
            "-H 'Cookie: a=b' -H 'Host: x.com' -H 'content-length: 0' " +
            "-H 'x-app: cli' https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals(listOf("x-app" to "cli"), result.headers)
        // 剔除的键都在告知列表里（大小写不敏感，这里统一按输入的键记）
        assertTrue(result.droppedHeaders.any { it.equals("Authorization", ignoreCase = true) })
        assertTrue(result.droppedHeaders.any { it.equals("x-api-key", ignoreCase = true) })
        assertTrue(result.droppedHeaders.any { it.equals("Cookie", ignoreCase = true) })
        assertTrue(result.droppedHeaders.any { it.equals("Host", ignoreCase = true) })
        assertTrue(result.droppedHeaders.any { it.equals("content-length", ignoreCase = true) })
    }

    @Test
    fun `data-raw 推断 bodyPatch 剔除业务字段`() {
        val curl = """curl -X POST -H 'Content-Type: application/json' \
            -d '{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"ping"}],"max_tokens":16,"store":true,"metadata":{"user":"x"}}' https://x.com"""
        val result = CurlParser.parse(curl)
        // model / messages / max_tokens 被剔除，store / metadata 保留
        assertTrue(result.bodyPatch.contains("\"store\""))
        assertTrue(result.bodyPatch.contains("\"metadata\""))
        assertTrue(!result.bodyPatch.contains("\"model\""))
        assertTrue(!result.bodyPatch.contains("messages"))
    }

    @Test
    fun `body 全是业务字段时 bodyPatch 为空对象`() {
        val curl = """curl -d '{"model":"x","messages":[{"role":"user"}]}' https://x.com"""
        val result = CurlParser.parse(curl)
        assertEquals("{}", result.bodyPatch)
    }

    @Test
    fun `没有 body 时 bodyPatch 为空对象`() {
        val curl = "curl -H 'x-app: cli' https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals("{}", result.bodyPatch)
    }

    @Test
    fun `body 不是合法 JSON 时不崩溃返回空对象`() {
        val curl = "curl -d 'not-json' https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals("{}", result.bodyPatch)
    }

    @Test
    fun `没有 UA 时为 null`() {
        val curl = "curl -H 'x-app: cli' https://x.com"
        assertNull(CurlParser.parse(curl).userAgent)
    }
}
