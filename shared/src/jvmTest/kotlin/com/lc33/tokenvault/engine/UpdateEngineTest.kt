package com.lc33.tokenvault.engine

import com.lc33.tokenvault.net.HostGate
import com.lc33.tokenvault.net.HttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「检查更新」整条链的 JVM 单测：HttpEngine（MockEngine 挡掉真网络）→ 解析 → 匹配 →
 * UpdateResult。fixture 用线上 Releases API 的真实形态（倒序、nightly 时间戳 tag、
 * 版本化预发布混在一起），盯的是三个容易断的点：
 * - 端点 URL 必须恰好是 [UpdateEngine.RELEASES_URL]（写错仓库名 = 永远查不到）；
 * - nightly 渠道按 `nightly` 前缀取最新一条（曾经的漂移：CI 打时间戳 tag、匹配端只认
 *   固定 `nightly-build`，这里固化回归）；
 * - 非 2xx 与匹配不到都归 UNREACHABLE，网络异常归 NO_NETWORK。
 */
class UpdateEngineTest {

    /** 线上真实形态：最新 nightly 在前，混着版本化预发布与正式版。 */
    private val liveShapeJson = """
        [
          {"tag_name":"nightly-20260918-030705","prerelease":true,"published_at":"2026-09-18T03:07:10Z","html_url":"https://github.com/lswlc33/YJ_ai_tokenvault/releases/tag/nightly-20260918-030705"},
          {"tag_name":"nightly-20260917-010203","prerelease":true,"html_url":"https://github.com/lswlc33/YJ_ai_tokenvault/releases/tag/nightly-20260917-010203"},
          {"tag_name":"v0.2.0","prerelease":false,"published_at":"2026-09-10T00:00:00Z","html_url":"https://github.com/lswlc33/YJ_ai_tokenvault/releases/tag/v0.2.0"},
          {"tag_name":"v0.1.0-alpha","prerelease":true,"html_url":"https://github.com/lswlc33/YJ_ai_tokenvault/releases/tag/v0.1.0-alpha"}
        ]
    """.trimIndent()

    private var requestedUrl: String? = null

    private fun engine(json: String, status: HttpStatusCode = HttpStatusCode.OK): UpdateEngine {
        requestedUrl = null
        val mock = MockEngine { data: HttpRequestData ->
            requestedUrl = data.url.toString()
            respond(json, status, headersOf("Content-Type", "application/json"))
        }
        val http = HttpEngine(HttpClient(mock), HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L }))
        return UpdateEngine(http, currentVersionName = "0.1.0", repoUrl = UpdateEngine.RELEASES_URL)
    }

    @Test
    fun `nightly 渠道命中最新时间戳 nightly 且恒视为有构建`() = runBlocking {
        val result = engine(liveShapeJson).check(channel = 1)
        assertNull(result.error)
        assertEquals("nightly-20260918-030705", result.latest!!.tagName)
        assertTrue(result.newer)
    }

    @Test
    fun `正式版渠道命中非预发布的 v020 并判定更新`() = runBlocking {
        val result = engine(liveShapeJson).check(channel = 0)
        assertNull(result.error)
        // v0.1.0-alpha 是预发布，不能被正式版渠道选中。
        assertEquals("v0.2.0", result.latest!!.tagName)
        assertTrue(result.newer)
    }

    @Test
    fun `当前已是最新正式版时 newer 为假`() = runBlocking {
        val result = UpdateEngine(
            httpEngineOf(liveShapeJson),
            currentVersionName = "0.2.0",
            repoUrl = UpdateEngine.RELEASES_URL,
        ).check(channel = 0)
        assertNull(result.error)
        assertEquals("v0.2.0", result.latest!!.tagName)
        assertTrue(!result.newer)
    }

    @Test
    fun `只有 nightly 时正式版渠道报打不开`() = runBlocking {
        // 与线上现状一致（v0.1.0-alpha 是预发布）：正式版渠道没有可匹配的 release。
        val json = """[{"tag_name":"nightly-20260918-030705","prerelease":true}]"""
        val result = engine(json).check(channel = 0)
        assertNull(result.latest)
        assertEquals(UpdateErrorKind.UNREACHABLE, result.error)
    }

    @Test
    fun `请求打在 RELEASES_URL 上`() = runBlocking {
        engine(liveShapeJson).check(channel = 0)
        assertEquals(UpdateEngine.RELEASES_URL, requestedUrl)
    }

    @Test
    fun `非 2xx 归为打不开不解析响应体`() = runBlocking {
        val result = engine("""[{"tag_name":"v9.9.9","prerelease":false}]""", HttpStatusCode.Forbidden)
            .check(channel = 0)
        assertEquals(UpdateErrorKind.UNREACHABLE, result.error)
    }

    @Test
    fun `网络异常归为无网络`() = runBlocking {
        val mock = MockEngine { throw java.io.IOException("no route") }
        val http = HttpEngine(HttpClient(mock), HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L }))
        val result = UpdateEngine(http, "0.1.0", UpdateEngine.RELEASES_URL).check(channel = 0)
        assertNull(result.latest)
        assertEquals(UpdateErrorKind.NO_NETWORK, result.error)
    }

    private fun httpEngineOf(json: String): HttpEngine {
        val mock = MockEngine { respond(json, HttpStatusCode.OK) }
        return HttpEngine(HttpClient(mock), HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L }))
    }
}
