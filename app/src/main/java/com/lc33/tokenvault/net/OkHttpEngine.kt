package com.lc33.tokenvault.net

import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 把 [ProbeRequest] 执行成 [ProbeResponse]（计划.md §8.1）。
 *
 * 单个 [OkHttpClient]（应用级单例），超时与并发都按 §8.1 那张表：
 * - connect 8s / read 20s / call 35s；
 * - dispatcher maxRequests 8、maxRequestsPerHost 3（中转站普遍限流）；
 * - 每个 host 在 [HostGate] 上串行取号，保证相邻请求间隔 ≥ 800ms（红线 29）。
 *
 * 延迟只测到**首字节**（`LatencyEventListener`），不把 body 读进来计时。
 *
 * 安全约束：`http://` 地址只有 `allowInsecure` 才放行（§7.5）；否则直接返回网络错误，
 * 不发出请求。
 */
class OkHttpEngine(
    private val client: OkHttpClient,
    private val hostGate: HostGate,
) {

    /**
     * @param allowInsecure 允许 `http://` 地址。只有用户显式打开 `provider.allowInsecure`
     *   才传 true（§7.5）；测试里用 MockWebServer（http://localhost）也传 true。
     */
    /** 某 host 当前的串行最小间隔。编排器（[ProbeEngine]）据此决定要不要先睡。 */
    fun hostIntervalMs(host: String): Long = hostGate.currentIntervalMs(host)

    /** 通知某 host 撞了 429。门闸把它的间隔加倍（封顶 8s）。 */
    fun onRateLimited(host: String) = hostGate.onRateLimited(host)

    suspend fun execute(request: ProbeRequest, allowInsecure: Boolean = false): ProbeResponse {
        val host = runCatching { URI(request.url).host ?: "" }.getOrDefault("")

        // host 门闸：等这个 host 轮到。
        val allowedAt = hostGate.acquire(host)
        val now = System.currentTimeMillis()
        if (allowedAt > now) delay(allowedAt - now)

        return withContext(Dispatchers.IO) {
            executeOnClient(request, allowInsecure)
        }
    }

    private suspend fun executeOnClient(request: ProbeRequest, allowInsecure: Boolean): ProbeResponse {
        val okRequest = buildOkHttpRequest(request, allowInsecure) ?: return ProbeResponse(
            status = 0,
            error = IOException("insecure endpoint not allowed"),
        )

        // 首字节延迟：用 per-call EventListener 记录 requestStart → responseHeadersEnd。
        var latencyMs: Long? = null
        val started = System.nanoTime()
        val timedClient = client.newBuilder()
            .eventListener(object : okhttp3.EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) {
                    latencyMs = (System.nanoTime() - started) / 1_000_000
                }
            })
            .build()

        return suspendCancellableCoroutine { continuation ->
            timedClient.newCall(okRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resume(ProbeResponse(status = 0, error = e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isCancelled) {
                        response.close()
                        return
                    }
                    response.use {
                        val body = it.body.string()
                        val headers = it.headers.names().associateWith { name ->
                            it.headers[name] ?: ""
                        }
                        continuation.resume(
                            ProbeResponse(
                                status = it.code,
                                headers = headers,
                                body = body,
                                latencyMs = latencyMs,
                            ),
                        )
                    }
                }
            })
        }
    }

    private fun buildOkHttpRequest(request: ProbeRequest, allowInsecure: Boolean): Request? {
        val url = request.url
        // §7.5：http:// 必须显式 allowInsecure 才放行。
        if (url.startsWith("http://") && !allowInsecure) return null

        val builder = Request.Builder().url(url)
        // 公共头 + 预设头（User-Agent 兜底）
        var hasUserAgent = false
        for ((name, value) in request.headers) {
            builder.header(name, value)
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
        }
        // §8.1：不留空 UA——OkHttp 会自报 okhttp/4.x，等于告诉上游"我不是 AI 客户端"。
        // M5 阶段用 default 预设的 UA；M6 引入 HeaderAssembler 后由预设提供。
        if (!hasUserAgent) builder.header("User-Agent", DEFAULT_USER_AGENT)
        if (!hasHeader(request.headers, "Accept")) builder.header("Accept", "application/json")

        when {
            request.body != null -> {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                builder.method(
                    request.method,
                    request.body.toRequestBody(mediaType),
                )
            }
            request.method == "GET" -> builder.get()
            else -> builder.method(request.method, null)
        }
        return builder.build()
    }

    private fun hasHeader(headers: List<Pair<String, String>>, name: String): Boolean =
        headers.any { it.first.equals(name, ignoreCase = true) }

    companion object {
        /** M5 阶段的默认 UA；M6 起由客户端预设（HeaderAssembler）提供。 */
        const val DEFAULT_USER_AGENT = "YuanJi/0.1.0 (Android)"

        /**
         * 按 §8.1 的配置建单例 client。
         *
         * [allowInsecure] 由上层决定；这里只建 client，不做地址安全判断（那在
         * [buildOkHttpRequest] 里）。
         */
        fun buildDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 8
                    maxRequestsPerHost = 3
                },
            )
            .build()
    }
}
