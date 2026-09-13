package com.lc33.tokenvault.net

import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 把 [ProbeRequest] 执行成 [ProbeResponse]（计划.md §8.1）。
 *
 * 阶段2 迁移：底层从 OkHttp 换成 **Ktor Client**（KMP），所以 `net/` 可以进 commonMain。
 * 平台差异缩到 [platformEngine]：Android/JVM 用 OkHttp 引擎、iOS 用 Darwin 引擎，
 * 其余逻辑（host 门闸、http:// 安全闸、首字节延迟）都在这里、两端一致。
 *
 * 超时与并发上限由 [platformEngine] 的引擎工厂各自配置（§8.1 那张表）；这里负责：
 * - host 级最小间隔（[HostGate]，红线 29）；
 * - `http://` 地址只有 `allowInsecure` 才放行（§7.5）；
 * - 首字节延迟（响应头一到就计时，不把 body 读进来计时）。
 *
 * 手动代理（§7.5）：Ktor 的 proxy 是**引擎级**配置，不能像 OkHttp 那样每请求
 * `newBuilder().proxy(...)`。所以持有方（DI）在代理串变化时用 [buildClient] 重建
 * client 再交给这里——"改了就立刻生效"的语义不变，只是从"每请求换 client"变成
 * "变化时换 client"。
 */
class HttpEngine(
    private val client: HttpClient,
    private val hostGate: HostGate,
    private val audit: AuditLogRepository? = null,
) {

    /** 某 host 当前的串行最小间隔。编排器（ProbeEngine）据此决定要不要先睡。 */
    fun hostIntervalMs(host: String): Long = hostGate.currentIntervalMs(host)

    /** 通知某 host 撞了 429。门闸把它的间隔加倍（封顶 8s）。 */
    fun onRateLimited(host: String) = hostGate.onRateLimited(host)

    /**
     * @param allowInsecure 允许 `http://` 地址。只有用户显式打开 `provider.allowInsecure`
     *   才传 true（§7.5）；测试里用本地 mock 服务器也传 true。
     */
    suspend fun execute(request: ProbeRequest, allowInsecure: Boolean = false): ProbeResponse {
        // §7.5：http:// 必须显式 allowInsecure 才放行。
        if (request.url.startsWith("http://") && !allowInsecure) {
            record(
                level = LogLevel.WARN,
                category = LogCategory.HTTP,
                message = "http request blocked",
                detail = "${request.method} ${safeTarget(request.url)}",
            )
            return ProbeResponse(
                status = 0,
                error = InsecureEndpointException("insecure endpoint not allowed"),
            )
        }

        val host = hostOf(request.url)
        // host 门闸：等这个 host 轮到。
        val allowedAt = hostGate.acquire(host)
        val now = clockMillis()
        if (allowedAt > now) delay(allowedAt - now)

        return try {
            val started = clockMillis()
            val response = client.request(request.url) {
                method = HttpMethod.parse(request.method)
                applyHeaders(request.headers)
                request.body?.let { body ->
                    setBody(body)
                    contentType(ContentType.Application.Json)
                }
            }
            val latencyMs = clockMillis() - started
            record(
                level = if (response.status.value in 200..299) LogLevel.INFO else LogLevel.WARN,
                category = LogCategory.HTTP,
                message = "http ${request.method} ${safeTarget(request.url)} -> ${response.status.value}",
                detail = "latency=${latencyMs}ms",
            )
            ProbeResponse(
                status = response.status.value,
                headers = response.headers.entries().associate { it.key to it.value.joinToString(", ") },
                body = response.bodyAsText(),
                latencyMs = latencyMs,
            )
        } catch (cancelled: CancellationException) {
            // 取消不是一次失败响应：必须原样上抛，否则调用方的 Job.cancel 会一直等网络超时。
            throw cancelled
        } catch (t: Throwable) {
            record(
                level = LogLevel.ERROR,
                category = LogCategory.HTTP,
                message = "http ${request.method} ${safeTarget(request.url)} failed",
                detail = t::class.simpleName,
            )
            ProbeResponse(status = 0, error = t)
        }
    }

    private suspend fun record(
        level: LogLevel,
        category: LogCategory,
        message: String,
        detail: String? = null,
    ) {
        runCatching { audit?.record(level = level, category = category, message = message, detail = detail) }
    }

    /** 日志里只放脱敏目标；query/fragment/header/body 永不进入日志。 */
    private fun safeTarget(url: String): String {
        val base = url.substringBefore('?').substringBefore('#')
        val schemeEnd = base.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val pathStart = base.indexOf('/', startIndex = schemeEnd).let { if (it < 0) base.length else it }
        return base.substring(schemeEnd, pathStart) + base.substring(pathStart)
    }

    /** 公共头 + 预设头（User-Agent 兜底）。 */
    private fun HttpRequestBuilder.applyHeaders(headers: List<Pair<String, String>>) {
        var hasUserAgent = false
        var hasAccept = false
        for ((name, value) in headers) {
            header(name, value)
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            if (name.equals("Accept", ignoreCase = true)) hasAccept = true
        }
        // §8.1：不留空 UA——默认引擎会自报 okhttp/x 或 ktor/x，等于告诉上游"我不是 AI 客户端"。
        if (!hasUserAgent) header(HttpHeaders.UserAgent, DEFAULT_USER_AGENT)
        if (!hasAccept) header(HttpHeaders.Accept, "application/json")
    }

    companion object {
        /** M5 阶段的默认 UA；M6 起由客户端预设（HeaderAssembler）提供。 */
        const val DEFAULT_USER_AGENT = "YuanJi/0.1.0 (Android)"
    }
}

/** `http://` 未放行时抛的专用信号（不是真网络异常，是安全闸）。 */
class InsecureEndpointException(message: String) : Exception(message)

/**
 * 平台 HTTP 引擎工厂（阶段2 expect/actual）。
 *
 * Android/JVM → OkHttp 引擎；iOS → Darwin 引擎。超时、并发上限、TLS 配置都在各 actual
 * 里按 §8.1 那张表配好，`HttpEngine` 不关心底层是谁。
 */
expect fun platformEngine(proxy: ProxyConfig?): HttpClientEngine

/** 按当前代理建一个 client。持有方（DI）在代理变化时调用，再交给 [HttpEngine]。 */
fun buildClient(proxy: ProxyConfig?): HttpClient = HttpClient(platformEngine(proxy)) {
    // 关闭自动跟随重定向之外，不需要额外全局配置：头与超时都由引擎/请求层处理。
}

/** 单调时钟。commonMain 拿不到 `System`，注入（红线 20）。 */
internal expect fun clockMillis(): Long

/** 从 URL 里取 host（解析失败给空串）。 */
private fun hostOf(url: String): String {
    val afterScheme = url.substringAfter("://", "")
    val authority = afterScheme.substringBefore('/')
    val hostPort = authority.substringAfterLast('@')
    return hostPort.substringBefore(':')
}
