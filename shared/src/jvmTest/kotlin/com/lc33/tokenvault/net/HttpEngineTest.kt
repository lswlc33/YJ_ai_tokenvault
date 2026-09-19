package com.lc33.tokenvault.net

import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * 并发闸真的接在 [HttpEngine.execute] 上（§13.4「最大并发数」）。
     *
     * `ConcurrencyGateTest` 证的是闸本身；这三条证的是**接线**——闸没注入、注入的是
     * 另一个实例、或者 `withPermit` 只包住了 `client.request` 而把读体漏在外面，
     * 上面那些用例全都还是绿的。六个不同 host 是为了绕开 host 门闸的串行，
     * 让"同时几个在飞"只由并发闸决定。
     *
     * 三条都用 `runBlocking` 而不是 `runTest`：`MockEngine` 把处理块跑到它自己的
     * `Dispatchers.IO` 上，虚拟时间的 `runCurrent()` 推不动它——那样写出来的断言会
     * 稳定读到 0，看着像"闸把请求卡死了"，其实是测试自己没推进。计数用原子类，
     * 因为写它的是引擎线程、读它的是测试线程。
     */
    @Test
    fun `并发闸生效时 execute 同时最多放行设定数个请求`() = runBlocking {
        val concurrency = ConcurrencyGate(2)
        val hold = CompletableDeferred<Unit>()
        val live = AtomicInteger()
        val peak = AtomicInteger()
        val mock = MockEngine {
            live.incrementAndGet()
            peak.accumulateAndGet(live.get()) { a, b -> maxOf(a, b) }
            hold.await()
            live.decrementAndGet()
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val engine = HttpEngine(
            HttpClient(mock),
            HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L }),
            concurrency = concurrency,
        )

        val jobs = (1..6).map { i ->
            launch {
                engine.execute(url("https://h$i.example.com/v1/models"))
            }
        }
        awaitWithin("六个不同 host 也该有两个同时上网线") { live.get() >= 2 }
        hold.complete(Unit)
        jobs.joinAll()

        assertTrue(peak.get() <= 2, "全程峰值 ${peak.get()} 越过了上限 2")
        assertEquals(0, concurrency.inFlightCount())
    }

    /** 取消一个在飞的请求，名额必须立刻回到排队的那个手上（漏一次就是永久少一格）。 */
    @Test
    fun `取消在飞的请求后名额立刻让给排队的下一个`() = runBlocking {
        val concurrency = ConcurrencyGate(1)
        val served = AtomicInteger()
        val slowOnWire = CompletableDeferred<Unit>()
        val mock = MockEngine { request ->
            if (request.url.toString().contains("slow")) {
                slowOnWire.complete(Unit)
                awaitCancellation()
            }
            served.incrementAndGet()
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val engine = HttpEngine(
            HttpClient(mock),
            HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L }),
            concurrency = concurrency,
        )

        val slow = launch { engine.execute(url("https://slow.example.com/v1/models")) }
        // 慢的那个**真的**上了网线，唯一的名额才算被它占住；不等这步就取消，可能压根没排队。
        awaitWithin("慢请求没进引擎，这条用例没在测名额转移") { slowOnWire.isCompleted }
        val next = launch { engine.execute(url("https://next.example.com/v1/models")) }
        delay(200)
        assertEquals(0, served.get(), "名额还被慢的那个占着")

        slow.cancelAndJoin()
        // 没交回来就在这里超时：永久少一格等于"探测越跑越慢，最后整轮卡住"。
        awaitWithin("取消之后排队的那个要立刻上网线") { served.get() >= 1 }
        next.join()
        assertEquals(0, concurrency.inFlightCount())
    }

    /**
     * 名额**不**包住审计写入：一次 Room 写停顿不该变成网络停顿。
     *
     * 最小那一档（2）下这最要命——日志写慢一点，整个应用就只剩一条网络通道。
     * 断法是：让审计写入卡在挂起函数里，第二个请求仍然能拿到名额上网线。
     */
    @Test
    fun `审计写入不占并发名额`() = runBlocking {
        val concurrency = ConcurrencyGate(1)
        val auditStall = CompletableDeferred<Unit>()
        val served = AtomicInteger()
        val mock = MockEngine {
            served.incrementAndGet()
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val slowAudit = object : AuditLogRepository by RecordingAuditLog() {
            override suspend fun record(
                level: LogLevel,
                category: LogCategory,
                message: String,
                detail: String?,
                providerId: Long?,
                keyId: Long?,
                runId: Long?,
                requestUrl: String?,
                requestBody: String?,
                responseBody: String?,
            ) {
                auditStall.await()
            }
        }
        val engine = HttpEngine(
            HttpClient(mock),
            HostGate(defaultMinIntervalMs = 0, nowMillis = { 0L }),
            audit = slowAudit,
            concurrency = concurrency,
        )

        val first = launch { engine.execute(url("https://a.example.com/v1/models")) }
        // 第一个跑完网线那一段、停在写审计日志上：名额在这一步之前就该还掉了。
        awaitWithin("第一个请求没上网线") { served.get() >= 1 }

        val second = launch { engine.execute(url("https://b.example.com/v1/models")) }
        awaitWithin("日志写得慢不该把网络也拖住") { served.get() >= 2 }

        auditStall.complete(Unit)
        first.join()
        second.join()
        assertEquals(0, concurrency.inFlightCount())
    }

    private fun url(target: String) =
        ProbeRequest(method = "GET", url = target, headers = emptyList())

    /**
     * 等一个由引擎线程写出的条件成立，等不到就红。
     *
     * 不用 `yield` 或固定次数：这里要等的可能是别的线程上真的在跑的磁盘/网络代码，
     * "让一次调度"不稳定；超时兜底才让"永远等不到"这种失败有个能读的消息。
     */
    private suspend fun awaitWithin(what: String, deadlineMs: Long = 5_000, predicate: () -> Boolean) {
        val reached = withTimeoutOrNull(deadlineMs) {
            while (!predicate()) delay(20)
            true
        } ?: false
        assertTrue(reached, "$what（${deadlineMs}ms 内没等到）")
    }
}
