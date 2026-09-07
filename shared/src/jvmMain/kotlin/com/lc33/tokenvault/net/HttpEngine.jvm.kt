package com.lc33.tokenvault.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * JVM 端引擎：与 Android 一致用 OkHttp 引擎（§8.1 那张表）。JVM 单测在本机跑，
 * 复用同一套超时/并发配置，行为与设备端对齐。
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
