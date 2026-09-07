package com.lc33.tokenvault.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Android 端引擎：OkHttp 引擎（§8.1 那张表）。
 *
 * - connect 8s / read 20s / call 35s；
 * - dispatcher maxRequests 8、maxRequestsPerHost 3（中转站普遍限流）。
 * - 手动代理（§7.5）：有 [proxy] 时套 HTTP 代理。Ktor 的 `HttpClientEngineConfig.proxy`
 *   在 JVM 端是 `java.net.Proxy`，所以 proxy 应用只能落在 actual 里。
 */
actual fun platformEngine(proxy: ProxyConfig?): HttpClientEngine = OkHttp.create {
    preconfigured = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 8
                maxRequestsPerHost = 3
            },
        )
        .build()
    if (proxy != null) {
        this.proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port))
    }
}

internal actual fun clockMillis(): Long = System.currentTimeMillis()
