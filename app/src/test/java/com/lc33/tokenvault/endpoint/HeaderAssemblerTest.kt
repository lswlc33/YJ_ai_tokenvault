package com.lc33.tokenvault.endpoint

import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ClientProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HeaderAssembler（计划.md §8.2）纯函数测试（测试 10）。
 *
 * 覆盖四件事：组装顺序、鉴权头不可覆盖、占位符展开、bodyPatch 合并。
 */
class HeaderAssemblerTest {

    private val baseHeaders = listOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
    )

    private val authHeaders = listOf("Authorization" to "Bearer sk-test")

    private fun profile(
        userAgent: String = "TestUA/1.0",
        headers: List<Pair<String, String>> = emptyList(),
        bodyPatch: String = "{}",
    ) = ClientProfile(name = "test", userAgent = userAgent, headers = headers, bodyPatch = bodyPatch)

    // ------------------------------------------------------------------ 组装顺序

    @Test
    fun `无预设时只拼基础头与鉴权头`() {
        val result = HeaderAssembler.assemble(baseHeaders, null, authHeaders)
        assertEquals(
            listOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "Authorization" to "Bearer sk-test",
            ),
            result.headers,
        )
        assertTrue(result.warnedAuthKeys.isEmpty())
    }

    @Test
    fun `预设头按序覆盖基础头里的同名键`() {
        val p = profile(headers = listOf("Content-Type" to "application/x-www-form-urlencoded"))
        val result = HeaderAssembler.assemble(baseHeaders, p, authHeaders)
        // Content-Type 被预设覆盖，User-Agent 取预设值
        assertEquals("application/x-www-form-urlencoded", result.headers.first { it.first == "Content-Type" }.second)
        assertEquals("TestUA/1.0", result.headers.first { it.first == "User-Agent" }.second)
    }

    @Test
    fun `鉴权头最后加且预设同名的被忽略`() {
        // 预设里带了 Authorization / x-api-key，应被忽略并记 warn
        val p = profile(
            headers = listOf(
                "Authorization" to "Bearer attacker",
                "x-api-key" to "sk-evil",
                "X-Custom" to "keep",
            ),
        )
        val result = HeaderAssembler.assemble(baseHeaders, p, authHeaders)
        // 鉴权头最终值来自 authHeaders，不是预设
        assertEquals("Bearer sk-test", result.headers.first { it.first == "Authorization" }.second)
        // 预设里的鉴权键被记入 warn
        assertEquals(listOf("Authorization", "x-api-key"), result.warnedAuthKeys)
        // 非鉴权头保留
        assertEquals("keep", result.headers.first { it.first == "X-Custom" }.second)
    }

    @Test
    fun `鉴权头键匹配忽略大小写`() {
        val p = profile(headers = listOf("X-API-Key" to "sk-evil"))
        val result = HeaderAssembler.assemble(baseHeaders, p, authHeaders)
        assertEquals(listOf("X-API-Key"), result.warnedAuthKeys)
    }

    // ------------------------------------------------------------------ 占位符展开

    @Test
    fun `展开注入的占位符值`() {
        val p = profile(
            userAgent = "YuanJi/{app_version} (Android {android_release}; {arch})",
        )
        val result = HeaderAssembler.assemble(
            baseHeaders, p, authHeaders,
            placeholders = mapOf(
                "app_version" to "1.2.3",
                "android_release" to "14",
                "arch" to "arm64",
            ),
        )
        assertEquals(
            "YuanJi/1.2.3 (Android 14; arm64)",
            result.headers.first { it.first == "User-Agent" }.second,
        )
    }

    @Test
    fun `random_hex 占位符现算且长度正确`() {
        val template = "{random_hex:8}"
        val out = HeaderAssembler.expandPlaceholders(template, emptyMap()) { n -> "d".repeat(n) }
        assertEquals("dddddddd", out)
    }

    @Test
    fun `未知占位符原样保留`() {
        val out = HeaderAssembler.expandPlaceholders("abc-{unknown}-{random_hex:4}", emptyMap()) { n -> "a".repeat(n) }
        assertEquals("abc-{unknown}-aaaa", out)
    }

    @Test
    fun `注入的 uuid 优先于现算保证同请求内一致`() {
        val p = profile(headers = listOf("session_id" to "{uuid}", "trace" to "{uuid}"))
        val result = HeaderAssembler.assemble(
            baseHeaders, p, authHeaders,
            placeholders = mapOf("uuid" to "fixed-uuid"),
        )
        val session = result.headers.first { it.first == "session_id" }.second
        val trace = result.headers.first { it.first == "trace" }.second
        assertEquals("fixed-uuid", session)
        assertEquals(session, trace)
    }

    // ------------------------------------------------------------------ bodyPatch 合并

    @Test
    fun `空 patch 或空对象原样返回`() {
        val body = """{"model":"x","max_tokens":16}"""
        assertEquals(body, mergeBodyPatch(body, ""))
        assertEquals(body, mergeBodyPatch(body, "{}"))
    }

    @Test
    fun `patch 覆盖顶层键`() {
        val base = """{"model":"x","max_tokens":16}"""
        val patch = """{"max_tokens":8}"""
        assertEquals("""{"model":"x","max_tokens":8}""", mergeBodyPatch(base, patch))
    }

    @Test
    fun `patch 里 null 删除键`() {
        val base = """{"model":"x","max_tokens":16,"temperature":0.7}"""
        val patch = """{"temperature":null}"""
        assertEquals("""{"model":"x","max_tokens":16}""", mergeBodyPatch(base, patch))
    }

    @Test
    fun `对象递归合并`() {
        val base = """{"messages":[{"role":"user","content":"ping"}]}"""
        val patch = """{"messages":[{"role":"assistant"}]}"""
        // RFC 7386：数组不是递归合并对象，直接覆盖
        assertEquals("""{"messages":[{"role":"assistant"}]}""", mergeBodyPatch(base, patch))
    }

    @Test
    fun `base 不是合法 JSON 时原样返回不崩溃`() {
        assertEquals("not-json", mergeBodyPatch("not-json", """{"a":1}"""))
    }

    @Test
    fun `patch 不是对象时原样返回`() {
        val base = """{"model":"x"}"""
        assertEquals(base, mergeBodyPatch(base, "[1,2,3]"))
    }
}
