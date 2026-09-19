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
    fun `提取 URL 与 Authorization 里的 API Key`() {
        val curl = "curl -H 'Authorization: Bearer sk-abc123' https://api.example.com/v1/chat/completions"
        val result = CurlParser.parse(curl)
        assertEquals("https://api.example.com/v1/chat/completions", result.url)
        assertEquals("sk-abc123", result.apiKey!!.concatToString())
    }

    @Test
    fun `提取 x-api-key 作为 API Key`() {
        val curl = "curl -H 'x-api-key: sk-xyz789' https://api.example.com/v1/messages"
        val result = CurlParser.parse(curl)
        assertEquals("sk-xyz789", result.apiKey!!.concatToString())
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
    fun `Fiddler 风格的美元单引号整段 raw 请求会还原转义`() {
        // Fiddler / mitmproxy 的"Copy as cURL"把整段 raw request 塞进 `--data-binary $'…'`。
        // 回归点：`$'…'` 的外壳在分词阶段就被剥掉了，旧实现事后无从知道哪些反斜杠本来是
        // 转义，于是 `\r\n` 以两个字面字符落进 body，探测发出的 body 永远对不上。
        val curl = "curl -X POST https://api.test/v1/chat --data-binary " +
            "\$'POST /v1/chat HTTP/1.1\\r\\nHost: api.test\\r\\n\\r\\n{\"model\":\"gpt-5.6-sol\"}'"
        val result = CurlParser.parse(curl)
        assertEquals(
            "POST /v1/chat HTTP/1.1\r\nHost: api.test\r\n\r\n{\"model\":\"gpt-5.6-sol\"}",
            result.dataBody,
        )
        assertEquals("POST", result.method)
        assertEquals("https://api.test/v1/chat", result.url)
    }

    @Test
    fun `美元单引号里认不出的转义原样保留`() {
        // 猜语义不如不猜：Windows 路径这类字面反斜杠不该凭空消失（`\p` / `\x` 都不在表里）。
        val curl = "curl -H \$'x-path: C:\\path\\x' https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals(listOf("x-path" to "C:\\path\\x"), result.headers)
    }

    @Test
    fun `美元单引号里的双反斜杠还原成一个`() {
        val curl = "curl -H \$'x-path: a\\\\b' https://x.com"
        assertEquals(listOf("x-path" to "a\\b"), CurlParser.parse(curl).headers)
    }

    @Test
    fun `长选项的等号形式`() {
        // Windows 的"Copy as cURL"输出的是 `--data-raw=...`，旧实现把整个 token 当未知项
        // 放过，结果 body / 头 / 方法全空，用户看到"解析出来什么都没有"。
        val curl = "curl --header='x-app: cli' --request=POST --user-agent=cli/1.0 " +
            "--data-raw='{\"model\":\"m\"}' https://x.com/v1"
        val result = CurlParser.parse(curl)
        assertEquals(listOf("x-app" to "cli"), result.headers)
        assertEquals("POST", result.method)
        assertEquals("cli/1.0", result.userAgent)
        assertEquals("""{"model":"m"}""", result.dataBody)
        assertEquals("m", result.model)
        assertEquals("https://x.com/v1", result.url)
    }

    @Test
    fun `值直接粘在短选项后`() {
        val curl = "curl -XPOST -d'{\"model\":\"m2\"}' https://x.com"
        val result = CurlParser.parse(curl)
        assertEquals("POST", result.method)
        assertEquals("""{"model":"m2"}""", result.dataBody)
        assertEquals("m2", result.model)
    }

    @Test
    fun `多个 data 选项按 curl 语义拼接而不是后者覆盖前者`() {
        // 分段导出的 raw request 会给出多条 -d，旧实现只剩最后一条，bodyPatch 跟着缺字段。
        val curl = "curl -d 'a=1' -d 'b=2' --data=c=3 https://x.com"
        assertEquals("a=1&b=2&c=3", CurlParser.parse(curl).dataBody)
    }

    @Test
    fun `不认识选项里的等号不被拆开`() {
        // URL 的 query 与 --data-urlencode 都不在解析器认得的选项里，动它们只会搅错值。
        val curl = "curl 'https://x.com/v1?key=abc' --data-urlencode=a=1"
        val result = CurlParser.parse(curl)
        assertEquals("https://x.com/v1?key=abc", result.url)
        assertNull(result.dataBody)
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
    fun `提取 URL 密钥与模型`() {
        val curl = """
            curl https://api.example.com/v1/chat/completions \
            -H "Authorization: Bearer sk-TEST0000000000000000000000000000000000000000000000000001" \
            -d '{"model":"gpt-5.6-terra"}'
        """.trimIndent()
        val result = CurlParser.parse(curl)
        assertEquals("https://api.example.com/v1/chat/completions", result.url)
        assertEquals(
            "sk-TEST0000000000000000000000000000000000000000000000000001",
            result.apiKey!!.concatToString(),
        )
        assertEquals("gpt-5.6-terra", result.model)
        assertEquals("""{"model":"gpt-5.6-terra"}""", result.dataBody)
    }

    @Test
    fun `没有 UA 时为 null`() {
        val curl = "curl -H 'x-app: cli' https://x.com"
        assertNull(CurlParser.parse(curl).userAgent)
    }
}
