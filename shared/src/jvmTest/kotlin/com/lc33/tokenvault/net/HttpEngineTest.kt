package com.lc33.tokenvault.net

import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
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
import kotlinx.coroutines.test.runTest

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `net/HttpEngine` 的 JVM 单测（阶段2 迁 Ktor 后）。
 *
 * 覆盖 host 门闸的排队 / 加倍 / 衰减语义（红线 29）、[HttpEngine.execute] 的报文组装、
 * 响应透传、日志脱敏（userinfo 与 query）、`http://` 安全闸与响应体字节上限。
 *
 * 门闸用例全部走 `runTest` 虚拟时间：`acquire` 真的会 `delay`，墙钟断言既慢又飘，
 * 而虚拟时间里"第二个请求恰好在 +100ms 放行"是可以精确断言的。
 */
class HttpEngineTest {

    // ------------------------------------------------------------------ HostGate

    @Test
    fun `同 host 相邻请求按最小间隔排队放行`() = runTest {
        val gate = HostGate(defaultMinIntervalMs = 100, nowMillis = { testScheduler.currentTime })
        val releaseTimes = mutableListOf<Long>()
        repeat(3) {
            gate.acquire("a.example.com")
            releaseTimes += testScheduler.currentTime
        }
        // 排队语义：后一个的放行时间在前一个之后再叠一个间隔，哪怕前一个还在睡。
        assertEquals(listOf(0L, 100L, 200L), releaseTimes)
    }

    @Test
    fun `一个 host 在等间隔不挡别的 host`() = runTest {
        val gate = HostGate(defaultMinIntervalMs = 100, nowMillis = { testScheduler.currentTime })
        gate.acquire("a.example.com") // a 有了首次放行时刻，下一次要等到 100
        var bDoneAt = -1L
        val aWaiting = launch { gate.acquire("a.example.com") }
        gate.acquire("b.example.com")
        bDoneAt = testScheduler.currentTime
        // b 的 acquire 直接返回（没排队），而 a 的那次还睡着——锁内不 delay 的语义就在这。
        assertEquals(0L, bDoneAt)
        assertTrue(aWaiting.isActive)
        aWaiting.join()
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test
    fun `429 后 host 间隔加倍`() = runTest {
        val gate = HostGate(defaultMinIntervalMs = 100, maxIntervalMs = 8000, nowMillis = { 0L })
        assertEquals(100L, gate.currentIntervalMs("a.example.com"))
        gate.onRateLimited("a.example.com")
        assertEquals(200L, gate.currentIntervalMs("a.example.com"))
    }

    @Test
    fun `429 带 Retry-After 时取加倍与它的较大者且封顶`() = runTest {
        val gate = HostGate(defaultMinIntervalMs = 100, maxIntervalMs = 8000, nowMillis = { 0L })
        // Retry-After 比加倍值小：仍按加倍走，上游的下限不该反过来放松我们。
        gate.onRateLimited("small.example.com", retryAfterMs = 50)
        assertEquals(200L, gate.currentIntervalMs("small.example.com"))
        // Retry-After 更大：按上游说的来。
        gate.onRateLimited("big.example.com", retryAfterMs = 5_000)
        assertEquals(5_000L, gate.currentIntervalMs("big.example.com"))
        // 封顶：整轮预算才 120s，门闸不该睡得比一轮还久，那种由 429 熔断停掉。
        gate.onRateLimited("cap.example.com", retryAfterMs = 60_000)
        assertEquals(8_000L, gate.currentIntervalMs("cap.example.com"))
    }

    @Test
    fun `持续成功后间隔衰减回默认`() = runTest {
        val gate = HostGate(defaultMinIntervalMs = 100, maxIntervalMs = 8_000, nowMillis = { 0L })
        gate.onRateLimited("a.example.com")
        assertEquals(200L, gate.currentIntervalMs("a.example.com"))
        repeat(HostGate.SUCCESS_RESET_STREAK - 1) { gate.onSuccess("a.example.com") }
        // 差一次没攒够：不能提前复位。
        assertEquals(200L, gate.currentIntervalMs("a.example.com"))
        gate.onSuccess("a.example.com")
        assertEquals(100L, gate.currentIntervalMs("a.example.com"))
    }

    @Test
    fun `host 归一化：大小写、端口与 root 点不拆桶`() {
        // 分桶键是门闸唯一的身份识别：分成两桶等于"改个大小写就绕过间隔"。
        assertEquals("a.example.com", hostOf("https://A.Example.COM/v1/models"))
        assertEquals("a.example.com", hostOf("https://a.example.com:8443/v1/models"))
        assertEquals("a.example.com", hostOf("https://a.example.com./v1/models"))
        assertEquals("a.example.com", hostOf("https://user:pass@a.example.com/v1"))
        // 带括号的 IPv6 直接按 ':' 切会得到 "[::1"，端口也在里面掺和
        assertEquals("::1", hostOf("http://[::1]:8080/v1"))
        assertEquals("::1", hostOf("http://[::1]/v1"))
        // '?' 不该跟着进键：少切一段就会把 query 当成 host 的一部分
        assertEquals("a.example.com", hostOf("https://a.example.com?x=1"))
    }

    @Test
    fun `归一化后的同一个 host 才会互相排队`() = runTest {
        val gate = HostGate(defaultMinIntervalMs = 100, nowMillis = { testScheduler.currentTime })
        gate.acquire(hostOf("https://A.example.com/v1"))
        var secondAt = -1L
        val waiting = launch {
            gate.acquire(hostOf("https://a.example.com:8443/v1"))
            secondAt = testScheduler.currentTime
        }
        waiting.join()
        // 归一化没生效的话这里是 0：换个大小写或端口就等于多开一条不限流的道
        assertEquals(100L, secondAt)
    }

    @Test
    fun `衰减途中再撞 429 则成功计数清零`() = runTest {
        val gate = HostGate(defaultMinIntervalMs = 100, maxIntervalMs = 8_000, nowMillis = { 0L })
        gate.onRateLimited("a.example.com")
        repeat(HostGate.SUCCESS_RESET_STREAK - 1) { gate.onSuccess("a.example.com") }
        gate.onRateLimited("a.example.com")
        // 429 把攒了一半的连续成功抹掉，再来一整串才复位：间隔 200→400。
        assertEquals(400L, gate.currentIntervalMs("a.example.com"))
        repeat(HostGate.SUCCESS_RESET_STREAK - 1) { gate.onSuccess("a.example.com") }
        assertEquals(400L, gate.currentIntervalMs("a.example.com"))
        gate.onSuccess("a.example.com")
        assertEquals(100L, gate.currentIntervalMs("a.example.com"))
    }

    @Test
    fun `429 名单按轮清，间隔跨轮保留`() = runTest {
        val gate = HostGate(defaultMinIntervalMs = 100, maxIntervalMs = 8_000, nowMillis = { 0L })
        gate.onRateLimited("a.example.com")
        assertTrue(gate.isRateLimited("a.example.com"))
        gate.clearRateLimitedMarks()
        assertFalse(gate.isRateLimited("a.example.com"))
        // 名单是"本轮别再碰它"，清掉就好；间隔是真节流，留着。
        assertEquals(200L, gate.currentIntervalMs("a.example.com"))
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
    fun `成功的请求留一条 INFO，日志里没有 query 也没有请求头`() = runBlocking {
        val gate = HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L })
        val audit = RecordingAuditLog()
        val mock = MockEngine { _ -> respond("{}", HttpStatusCode.OK) }
        val engine = HttpEngine(HttpClient(mock), gate, audit)

        engine.execute(
            ProbeRequest(
                method = "POST",
                url = "https://example.com/v1/chat?api_key=QUERY-SECRET#frag",
                headers = listOf("Authorization" to "Bearer HEADER-SECRET"),
            ),
            allowInsecure = true,
        )

        val record = audit.records.single()
        assertEquals(LogLevel.INFO, record.level)
        assertEquals(LogCategory.HTTP, record.category)
        // scheme 留着：http 与 https 在日志里是安全相关的事实（§7.5），实现方刻意不剥
        assertEquals("http POST https://example.com/v1/chat -> 200", record.message)
        assertTrue(record.detail!!.startsWith("latency="))
        // 脱敏：query 与请求头都不进日志，只留 scheme + host + path。
        val text = record.message + record.detail
        assertTrue(!text.contains("QUERY-SECRET"))
        assertTrue(!text.contains("HEADER-SECRET"))
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

    /** `https://u:p@host` 是合法输入（自建 new-api 有人这么填），而 u:p 就是口令。 */
    @Test
    fun `url 里的 userinfo 不进日志`() = runBlocking {
        val gate = HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L })
        val audit = RecordingAuditLog()
        val mock = MockEngine { _ -> respond("{}", HttpStatusCode.OK) }
        val engine = HttpEngine(HttpClient(mock), gate, audit)

        engine.execute(
            ProbeRequest(
                method = "GET",
                url = "https://user:SECRETPASS@example.com/v1/models",
                headers = emptyList(),
            ),
            allowInsecure = true,
        )

        val record = audit.records.single()
        assertEquals("http GET https://example.com/v1/models -> 200", record.message)
        val text = record.message + (record.detail ?: "") + (record.requestUrl ?: "")
        assertFalse(text.contains("SECRETPASS"), "日志里不许出现口令：$text")
        assertFalse(text.contains("user:"))
    }

    @Test
    fun `响应体超过字节上限时截断并留标记，正文头部不被破坏`() = runBlocking {
        val gate = HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L })
        // 首字节故意与后文不同：探测"还有没有下文"的那一读若写进主缓冲区，这里就会红。
        val big = "a" + "x".repeat(HttpEngine.MAX_RESPONSE_BYTES + 4_096)
        val engine = engineWith(HttpStatusCode.OK, big, gate)

        val response = engine.execute(
            ProbeRequest(method = "GET", url = "https://example.com/v1/models", headers = emptyList()),
            allowInsecure = true,
        )

        val marker = "\n… [body truncated at 512KB] …"
        assertTrue(response.body.endsWith(marker))
        assertEquals(HttpEngine.MAX_RESPONSE_BYTES + marker.length, response.body.length)
        assertTrue(response.body.startsWith("a"))
    }

    @Test
    fun `响应体在上限内原样读取，不带截断标记`() = runBlocking {
        val gate = HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L })
        val engine = engineWith(HttpStatusCode.OK, """{"data":[]}""", gate)

        val response = engine.execute(
            ProbeRequest(method = "GET", url = "https://example.com/v1/models", headers = emptyList()),
            allowInsecure = true,
        )

        assertEquals("""{"data":[]}""", response.body)
    }
}
