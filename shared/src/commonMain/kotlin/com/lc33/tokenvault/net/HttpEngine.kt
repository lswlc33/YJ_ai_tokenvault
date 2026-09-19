package com.lc33.tokenvault.net

import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

/**
 * 把 [ProbeRequest] 执行成 [ProbeResponse]（计划.md §8.1）。
 *
 * 阶段2 迁移：底层从 OkHttp 换成 **Ktor Client**（KMP），所以 `net/` 可以进 commonMain。
 * 平台差异缩到 [platformEngine]：Android/JVM 用 OkHttp 引擎、iOS 用 Darwin 引擎，
 * 其余逻辑（host 门闸、http:// 安全闸、首字节延迟）都在这里、两端一致。
 *
 * 引擎级超时与并发上限由 [platformEngine] 的引擎工厂各自配置（§8.1 那张表）；这里负责：
 * - host 级最小间隔（[HostGate]，红线 29）——**间隔只在这里睡一次**；
 * - 每把 Key 自己的 `timeoutSeconds`（[ProbeRequest.timeoutMs]，§8.1 的 per-request 超时）；
 * - `http://` 地址只有 `allowInsecure` 才放行（§7.5）；
 * - 首字节延迟（响应头一到就计时，不把 body 读进来计时）与响应体字节上限。
 *
 * 代理：移动端应用内代理难以可靠实现（Ktor 代理是引擎级配置、iOS Darwin 引擎
 * 对 HTTPS 代理不支持），且用户通常走系统级全局代理，故不提供应用内代理。
 */
class HttpEngine(
    private val client: HttpClient,
    private val hostGate: HostGate,
    private val audit: AuditLogRepository? = null,
) {

    /**
     * 通知某 host 撞了 429：门闸把它的间隔加倍（封顶 8s），并记进本轮 429 名单。
     *
     * @param retryAfterMs 上游 `Retry-After`（由分类器从响应头/body 取出）时一并交给门闸，
     *   否则那句"60 秒后再来"就白读了一次。
     */
    suspend fun onRateLimited(host: String, retryAfterMs: Long? = null) =
        hostGate.onRateLimited(host, retryAfterMs)

    /** 这个 host 本轮是不是撞过 429（红线 29：撞过就停发可选请求，嗅探尤其要问）。 */
    suspend fun isRateLimited(host: String): Boolean = hostGate.isRateLimited(host)

    /** 新一轮探测开始：清掉上一轮的 429 名单。 */
    suspend fun clearRateLimitedMarks() = hostGate.clearRateLimitedMarks()

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

        // host 门闸：等这个 host 轮到。睡在门闸内部（锁外），所以一个 host 在等不会挡住别的 host。
        hostGate.acquire(hostOf(request.url))

        return try {
            val started = clockMillis()
            val response = client.request(request.url) {
                method = HttpMethod.parse(request.method)
                applyHeaders(request.headers)
                // §8.1：per-request 超时。`timeoutSeconds` 是用户在 Key 编辑页填的，
                // 只在这里生效——引擎级超时是三端共用的兜底，挡不住"这家就是慢"。
                // socket 值要一起写：Darwin 引擎只认 socket 超时（它把 NSURLRequest 的
                // timeoutInterval 当这个用），只写 requestTimeoutMillis 的话 iOS 端仍然
                // 在 20s 就断，用户填 60 秒等于没填。
                request.timeoutMs?.let { timeoutMillis ->
                    timeout {
                        requestTimeoutMillis = timeoutMillis
                        socketTimeoutMillis = timeoutMillis
                    }
                }
                request.body?.let { body ->
                    setBody(body)
                    contentType(ContentType.Application.Json)
                }
            }
            val latencyMs = clockMillis() - started
            val responseBody = response.readBodyCapped()
            val status = response.status.value
            val rateLimited = status == 429
            // 间隔复位靠这里：只有 429 之外的响应才算"上游没嫌我们吵"（见 HostGate.onSuccess）。
            // 放在记账之前，保证即便日志写入失败也照样衰减。
            if (!rateLimited) hostGate.onSuccess(hostOf(request.url))
            record(
                level = if (status in 200..299) LogLevel.INFO else LogLevel.WARN,
                category = LogCategory.HTTP,
                message = "http ${request.method} ${safeTarget(request.url)} -> $status",
                detail = "latency=${latencyMs}ms",
                requestUrl = safeTarget(request.url),
                requestBody = request.body,
                responseBody = responseBody,
            )
            ProbeResponse(
                status = status,
                headers = response.headers.entries().associate { it.key to it.value.joinToString(", ") },
                body = responseBody,
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
                requestUrl = safeTarget(request.url),
                requestBody = request.body,
                responseBody = t.message,
            )
            ProbeResponse(status = 0, error = t)
        }
    }

    /**
     * 读响应体，**按字节封顶** [MAX_RESPONSE_BYTES]。
     *
     * `bodyAsText()` 会把整段读进内存，而上游给多少完全不看我们的脸色：地址填错指到
     * 一张图片 CDN、一个模型权重直链，几百 MB 的响应体足够把进程 OOM 掉（模型列表本身
     * 也就几十 KB）。所以自己按块读，超限后放弃剩余内容并留一个截断标记——标记要留在
     * 正文里，分类器和日志才看得出"这份 body 是被砍过的，别当完整 JSON 解析"。
     */
    private suspend fun HttpResponse.readBodyCapped(): String {
        val channel = bodyAsChannel()
        val buffer = ByteArray(MAX_RESPONSE_BYTES)
        var total = 0
        while (total < buffer.size) {
            val read = channel.readAvailable(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        // 探测"还有没有下一个字节"必须用**独立的单字节数组**：往 buffer[0] 里读会把
        // 正文的第一个字节覆盖成第 512KB+1 个字节，`copyOf(total)` 再拷贝时正文已经坏了，
        // 而模型列表 JSON 的首字符恰恰决定它能不能被解析。
        val probe = ByteArray(1)
        val truncated = total == buffer.size && channel.readAvailable(probe, 0, 1) > 0
        if (truncated) channel.cancel(null)
        return buffer.copyOf(total).decodeToString() +
            if (truncated) BODY_TRUNCATED_MARK else ""
    }

    private suspend fun record(
        level: LogLevel,
        category: LogCategory,
        message: String,
        detail: String? = null,
        requestUrl: String? = null,
        requestBody: String? = null,
        responseBody: String? = null,
    ) {
        runCatching {
            audit?.record(
                level = level,
                category = category,
                message = message,
                detail = detail,
                // 报文入库前在仓库层统一过脱敏（红线 32）；这里只做长度截断——
                // 模型列表这类响应几十 KB，整段进库会把日志表撑爆。
                requestUrl = requestUrl,
                requestBody = requestBody?.truncateBody(),
                responseBody = responseBody?.truncateBody(),
            )
        }
    }

    /** 超长报文截断：留个头尾，够定位问题；**中间省略**而不是直接砍掉尾巴。 */
    private fun String.truncateBody(): String =
        if (length <= MAX_BODY_CHARS) {
            this
        } else {
            take(MAX_BODY_CHARS / 2) + TRUNCATED_MARK + takeLast(MAX_BODY_CHARS / 2)
        }

    /**
     * 日志里的目标地址：只留 **host + path**（连 scheme 都不留），去掉 query、fragment
     * 与 **userinfo**。
     *
     * userinfo 那道是必须的：`https://u:p@host` 这种地址是合法输入（自建 new-api 有人
     * 这么填），而 `u:p` 里就是口令。日志表会被用户从"日志页"复制、截图、分享，
     * 口令进了正文就再也收不回来——脱敏器（红线 32）只认得出密钥形态，认不出别人
     * 塞在 URL 里的账号口令。
     */
    private fun safeTarget(url: String): String {
        val base = url.substringBefore('?').substringBefore('#')
        val schemeEnd = base.indexOf("://").let { if (it < 0) 0 else it + 3 }
        // authority = scheme 之后、第一个 '/' 之前的一段；`@` 之前的都是 userinfo。
        val pathStart = base.indexOf('/', startIndex = schemeEnd).let { if (it < 0) base.length else it }
        val authority = base.substring(schemeEnd, pathStart)
        val hostPort = authority.substringAfterLast('@')
        // scheme 保留：日志里 http 与 https 是安全相关的事实（红线 §7.5），不能一起剥掉。
        return base.substring(0, schemeEnd) + hostPort + base.substring(pathStart)
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

        /**
         * 单段报文的落库上限。模型列表能到几十 KB，整段进库会把日志表撑爆。
         * 截断标记用英文：日志正文（`message` / `detail`）本来就是 `http GET …` 这类
         * 技术英文，界面文案才走资源。
         */
        const val MAX_BODY_CHARS = 8_000
        private const val TRUNCATED_MARK = "\n… [truncated] …\n"

        /**
         * 响应体的字节上限（512 KB）。模型列表实测几十 KB，留一个数量级的余量；
         * 再大就不是"上游回得长"而是"地址配错了"。
         */
        const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val BODY_TRUNCATED_MARK = "\n… [body truncated at 512KB] …"
    }
}

/** `http://` 未放行时抛的专用信号（不是真网络异常，是安全闸）。 */
class InsecureEndpointException(message: String) : Exception(message)

/**
 * 平台 HTTP 引擎工厂（阶段2 expect/actual）。
 *
 * Android/JVM → OkHttp 引擎；iOS → Darwin 引擎。并发上限、TLS 配置都在各 actual
 * 里按 §8.1 那张表配好，`HttpEngine` 不关心底层是谁。
 */
expect fun platformEngine(): HttpClientEngine

/**
 * 建默认 client。DI 里单例，供 [HttpEngine] 与 [WebDavClient] 共用。
 *
 * 两件事必须在**这一处**统一，否则三端行为分叉：
 *
 * 1. **不跟随重定向**（`followRedirects = false`）。Ktor 3 的重定向是 client 层的
 *    `HttpRedirect` 插件（装了才会跟），Darwin 引擎的 URLSession 代理也默认把 3xx
 *    原样交回；而 OkHttp 引擎自己会悄悄跟完再回 200。三端不一致的后果是"同一个中转站
 *    在 iOS 上被判需人工检查、在 Android 上被判成功"。统一关掉后，3xx 由
 *    `ProbeClassifier` 归到"要人看一眼"那一档（不钉 CONFIG_ERROR 健康结论）。
 * 2. **装 `HttpTimeout` 插件**。per-request 的 `timeoutSeconds` 靠它落地
 *    （`HttpRequestBuilder.timeout {}` 只是写能力位，没装插件不生效；OkHttp 引擎
 *    甚至直接要求装过插件才肯构造 client）。这里配的两个值是 §8.1 那张表的兜底：
 *    Darwin 引擎把 `socketTimeoutMillis` 当成 NSURLRequest 的超时用（iOS 没有
 *    connect/read 分开的概念），OkHttp 侧引擎工厂已经配好 8/20/35s，值一样、不冲突。
 */
fun buildClient(): HttpClient = HttpClient(platformEngine()) {
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = ENGINE_CONNECT_TIMEOUT_MS
        socketTimeoutMillis = ENGINE_SOCKET_TIMEOUT_MS
    }
}

/** §8.1：connect 8s。 */
const val ENGINE_CONNECT_TIMEOUT_MS = 8_000L

/** §8.1：read 20s。Darwin 引擎用它当整个请求的超时。 */
const val ENGINE_SOCKET_TIMEOUT_MS = 20_000L

/** §8.1：call 35s。iOS 侧对应 `timeoutIntervalForResource`（整个资源给多久，含重连）。 */
const val ENGINE_CALL_TIMEOUT_MS = 35_000L

/** 单调时钟。commonMain 拿不到 `System`，注入（红线 20）。 */
internal expect fun clockMillis(): Long

/**
 * 从 URL 里取 host（解析失败给空串），作为门闸的分桶键。`user:pass@host` 只取 host 部分。
 *
 * 归一化是因为"同一个上游必须落进同一个桶"：`HOST` 与 `host` 只差大小写却是两个桶、
 * 各等各的间隔，改一下大小写就能绕过限流；带括号的 IPv6 按 `:` 切会切出 `[::1` 这种键，
 * 所以方括号形式单独取。端口**刻意不参与**：同一台机器换端口仍是同一个上游，
 * 分开计数等于允许加倍骚扰它。
 *
 * 不把 `127.0.0.1` 与 `2130706433` 这种同址不同写法也并进一个桶——那要真解析 IP，
 * 而这一层挡的是"手滑连点"，出网与否的闸在 Key 自己的 `allowInsecure` 上。
 *
 * `internal` 只为给这套归一化留一条能直接断言的测试；生产调用点仍旧只有本文件的 `execute`。
 */
internal fun hostOf(url: String): String {
    val afterScheme = url.substringAfter("://", "")
    val authority = afterScheme.substringBefore('/').substringBefore('?')
    val hostPort = authority.substringAfterLast('@')
    val host = if (hostPort.startsWith('[')) {
        hostPort.substringAfter('[').substringBefore(']')
    } else {
        hostPort.substringBefore(':')
    }
    // 域名大小写不敏感（RFC 4343）；结尾那个点只是根标签的显式写法，去掉才算同一个 host。
    return host.lowercase().trimEnd('.')
}
