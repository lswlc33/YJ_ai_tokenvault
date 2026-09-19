package com.lc33.tokenvault.net

import io.ktor.client.engine.HttpClientEngine
import com.lc33.tokenvault.domain.HttpConcurrencyPolicy
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

/**
 * Android 端引擎：OkHttp 引擎（§8.1 那张表）。
 *
 * - connect 8s；read / call 由调用方给（探测走 §8.1 的 20s / 35s，备份走更宽的档）。
 * - dispatcher maxRequests 取设置里那一档能选的最大值（[HttpConcurrencyPolicy.MAX]）、
 *   maxRequestsPerHost 3（红线 29，实测 Cloudflare 同 host 2.4 秒内第 3 个请求就 1015）。
 *   引擎这一档必须是**上限的上限**：留在 8 的话，用户选 16/32 只是把请求从应用闸门口
 *   挪进 OkHttp 自己的队列里排队，还一边排队一边占着名额，"最大并发数"就成了假数。
 *
 * **显式关掉重定向跟随**：OkHttp 默认会把 301/302 悄悄跟完再回一个 200，而 iOS 那侧的
 * Darwin 引擎是原样交回 3xx。三端不一致会让"同一个中转站在两台设备上探测结论不同"，
 * 所以这里跟着 `buildClient()` 的 `followRedirects = false` 一起关（Ktor 的 client 层
 * 插件与 OkHttp 引擎层各管一头，两处都要关才真的不跟）。
 */
actual fun platformEngine(readTimeoutMs: Long, callTimeoutMs: Long): HttpClientEngine = OkHttp.create {
    preconfigured = okhttp3.OkHttpClient.Builder()
        .connectTimeout(ENGINE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = HttpConcurrencyPolicy.MAX
                maxRequestsPerHost = HttpConcurrencyPolicy.PER_HOST_MAX_REQUESTS
            },
        )
        .build()
}

internal actual fun clockMillis(): Long = System.currentTimeMillis()
