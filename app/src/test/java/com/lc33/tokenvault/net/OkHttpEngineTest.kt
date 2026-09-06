package com.lc33.tokenvault.net

import com.lc33.tokenvault.endpoint.ProbeRequest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 测试 2 的 MockWebServer 部分：验证 [OkHttpEngine] 真实发出的报文。
 *
 * 同时覆盖 host 门闸的两个语义（红线 29）：
 * - 同 host 相邻请求间隔 ≥ 最小间隔。
 * - 429 后间隔加倍。
 */
class OkHttpEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: OkHttpEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val gate = HostGate(defaultMinIntervalMs = 100, nowMillis = { 0L })
        engine = OkHttpEngine(OkHttpEngine.buildDefaultClient(), gate)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `GET 请求发出正确的路径与鉴权头`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        val response = engine.execute(
            ProbeRequest(
                method = "GET",
                url = server.url("/v1/models").toString(),
                headers = listOf("Authorization" to "Bearer sk-test"),
            ),
            allowInsecure = true,
        )

        assertEquals(200, response.status)
        val recorded = server.takeRequest()
        assertTrue(recorded.requestLine.startsWith("GET /v1/models"))
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        // 不留空 UA（§8.1）：OkHttp 自报 okhttp/4.x 会暴露非客户端身份
        assertTrue(recorded.headers["User-Agent"]!!.isNotBlank())
    }

    @Test
    fun `POST 发出 JSON body`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        engine.execute(
            ProbeRequest(
                method = "POST",
                url = server.url("/v1/chat/completions").toString(),
                headers = listOf("Authorization" to "Bearer sk-test"),
                body = """{"model":"gpt-5.6-sol","max_tokens":16}""",
            ),
            allowInsecure = true,
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            """{"model":"gpt-5.6-sol","max_tokens":16}""",
            recorded.body!!.utf8(),
        )
    }

    @Test
    fun `响应体与状态码透传`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("Invalid token").build())

        val response = engine.execute(
            ProbeRequest(
                method = "GET",
                url = server.url("/v1/models").toString(),
                headers = listOf("Authorization" to "Bearer bad"),
            ),
            allowInsecure = true,
        )

        assertEquals(401, response.status)
        assertEquals("Invalid token", response.body)
    }

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

    // ---------------------------------------------------------------- parseProxy

    @Test
    fun `空串和空白串返回 null（走系统代理）`() {
        org.junit.Assert.assertNull(OkHttpEngine.parseProxy(null))
        org.junit.Assert.assertNull(OkHttpEngine.parseProxy(""))
        org.junit.Assert.assertNull(OkHttpEngine.parseProxy("   "))
    }

    @Test
    fun `host port 解析出 HTTP 代理`() {
        val proxy = OkHttpEngine.parseProxy("127.0.0.1:7890")!!
        assertEquals(java.net.Proxy.Type.HTTP, proxy.type())
        val addr = proxy.address() as java.net.InetSocketAddress
        assertEquals("127.0.0.1", addr.hostString)
        assertEquals(7890, addr.port)
    }

    @Test
    fun `缺端口时默认 80`() {
        val proxy = OkHttpEngine.parseProxy("proxy.example.com")!!
        val addr = proxy.address() as java.net.InetSocketAddress
        assertEquals(80, addr.port)
    }

    @Test
    fun `IPv6 方括号形式解析正确`() {
        val proxy = OkHttpEngine.parseProxy("[::1]:8080")!!
        val addr = proxy.address() as java.net.InetSocketAddress
        // hostString 会把 `::1` 规范成完整形式 `0:0:0:0:0:0:0:1`，用 InetAddress 比对语义。
        assertEquals(java.net.InetAddress.getByName("::1"), addr.address)
        assertEquals(8080, addr.port)
    }

    @Test
    fun `非法串返回 null`() {
        org.junit.Assert.assertNull(OkHttpEngine.parseProxy("http://proxy.example.com"))
        org.junit.Assert.assertNull(OkHttpEngine.parseProxy(":"))
    }
}
