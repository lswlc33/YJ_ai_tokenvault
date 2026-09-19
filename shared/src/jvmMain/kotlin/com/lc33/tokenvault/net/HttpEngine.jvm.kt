package com.lc33.tokenvault.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

/**
 * JVM 端引擎：与 Android 一致用 OkHttp 引擎（§8.1 那张表）。JVM 单测在本机跑，
 * 复用同一套超时/并发配置，行为与设备端对齐——包括**不跟随重定向**那一条，
 * 否则探测在测试里看到的 3xx 与设备上看到的 200 不是一份结论。
 * read / call 由调用方给：探测是 20s / 35s，备份那条路更宽。
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
                maxRequests = 8
                maxRequestsPerHost = 3
            },
        )
        .build()
}

internal actual fun clockMillis(): Long = System.currentTimeMillis()
