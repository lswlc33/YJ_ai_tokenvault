package com.lc33.tokenvault.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

/**
 * Android 端引擎：OkHttp 引擎（§8.1 那张表）。
 *
 * - connect 8s / read 20s / call 35s；
 * - dispatcher maxRequests 8、maxRequestsPerHost 3（中转站普遍限流）。
 *
 * **显式关掉重定向跟随**：OkHttp 默认会把 301/302 悄悄跟完再回一个 200，而 iOS 那侧的
 * Darwin 引擎是原样交回 3xx。三端不一致会让"同一个中转站在两台设备上探测结论不同"，
 * 所以这里跟着 `buildClient()` 的 `followRedirects = false` 一起关（Ktor 的 client 层
 * 插件与 OkHttp 引擎层各管一头，两处都要关才真的不跟）。
 */
actual fun platformEngine(): HttpClientEngine = OkHttp.create {
    preconfigured = okhttp3.OkHttpClient.Builder()
        .connectTimeout(ENGINE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ENGINE_SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(ENGINE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
