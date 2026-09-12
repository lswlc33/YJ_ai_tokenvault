package com.lc33.tokenvault.net

import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `net/HttpEngine` 的 JVM 单测（阶段2 迁 Ktor 后）。
 *
 * 覆盖 host 门闸的两个语义（红线 29）与 [HttpEngine.execute] 的报文组装、响应透传、
 * `http://` 安全闸。
 */
class HttpEngineTest {

    // ------------------------------------------------------------------ HostGate

    @Test
    fun `host 门闸相邻请求间隔单调递增`() {
        val gate = HostGate(defaultMinIntervalMs = 100, nowMillis = { 0L })
        val first = gate.acquire("a.example.com")
        val second = gate.acquire("a.example.com")
        assertTrue(second - first >= 100)
    }

    @Test
    fun `429 后 host 间隔加倍`() {
        val gate = HostGate(defaultMinIntervalMs = 100, maxIntervalMs = 8000, nowMillis = { 0L })
        assertEquals(100, gate.currentIntervalMs("a.example.com"))
        gate.onRateLimited("a.example.com")
        assertEquals(200, gate.currentIntervalMs("a.example.com"))
    }

    // ------------------------------------------------------------------ parseProxy

    @Test
    fun `空串和空白串返回 null（走系统代理）`() {
        assertNull(parseProxy(null))
        assertNull(parseProxy(""))
        assertNull(parseProxy("   "))
    }

    @Test
    fun `host port 解析出 HTTP 代理`() {
        val proxy = parseProxy("127.0.0.1:7890")!!
        assertEquals("127.0.0.1", proxy.host)
        assertEquals(7890, proxy.port)
    }

    @Test
    fun `缺端口时默认 80`() {
        val proxy = parseProxy("proxy.example.com")!!
        assertEquals(80, proxy.port)
    }

    @Test
    fun `IPv6 方括号形式解析正确`() {
        val proxy = parseProxy("[::1]:8080")!!
        assertEquals("::1", proxy.host)
        assertEquals(8080, proxy.port)
    }

    @Test
    fun `非法串返回 null`() {
        assertNull(parseProxy("http://proxy.example.com"))
        assertNull(parseProxy(":"))
    }

    // ------------------------------------------------------------------ execute

    private fun engineWith(status: HttpStatusCode, body: String, gate: HostGate): HttpEngine {
        val mock = MockEngine { _ ->
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpEngine(HttpClient(mock), gate)
    }

    @Test
    fun `响应体与状态码透传`() = runBlocking {
        val gate = HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L })
        val engine = engineWith(HttpStatusCode.Unauthorized, "Invalid token", gate)

        val response = engine.execute(
            ProbeRequest(
                method = "GET",
                url = "https://example.com/v1/models",
                headers = listOf("Authorization" to "Bearer bad"),
            ),
            allowInsecure = true,
        )

        assertEquals(401, response.status)
        assertEquals("Invalid token", response.body)
        assertNull(response.error)
    }

    @Test
    fun `取消挂在请求上时不会被吞成失败响应`() = runBlocking {
        val mock = MockEngine { awaitCancellation() }
        val engine = HttpEngine(
            HttpClient(mock),
            HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L }),
        )
        val completed = CompletableDeferred<ProbeResponse?>()
        val job = launch {
            completed.complete(
                runCatching {
                    engine.execute(
                        ProbeRequest(
                            method = "GET",
                            url = "https://example.com/v1/models",
                            headers = emptyList(),
                        ),
                        allowInsecure = true,
                    )
                }.getOrNull(),
            )
        }

        yield()
        job.cancelAndJoin()
        assertNull(withTimeout(1_000) { completed.await() })
    }

    @Test
    fun `http 未放行时返回错误不发请求`() = runBlocking {
        val gate = HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L })
        val engine = engineWith(HttpStatusCode.OK, "{}", gate)

        val response = engine.execute(
            ProbeRequest(
                method = "GET",
                url = "http://insecure.example.com/v1/models",
                headers = emptyList(),
            ),
            allowInsecure = false,
        )

        assertEquals(0, response.status)
        assertTrue(response.error is InsecureEndpointException)
    }
}
