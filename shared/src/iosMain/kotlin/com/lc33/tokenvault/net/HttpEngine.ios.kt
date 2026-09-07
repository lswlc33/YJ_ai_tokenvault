package com.lc33.tokenvault.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS 端引擎：Darwin 引擎（阶段4 在 macOS runner 上编译）。
 *
 * 注意：Darwin 引擎的 HTTP 代理不支持 HTTPS 请求（Ktor 文档明示），所以 HTTPS 走代理
 * 在 iOS 上不可用——这是平台限制，不在这里硬拗。
 *
 * proxy 在 posix（native）端的类型是 `ProxyConfig(url)`，与 JVM 的 `java.net.Proxy` 不同，
 * 所以这个 actual 里用 `http://host:port` 拼一个 Url 传进去。
 */
actual fun platformEngine(proxy: com.lc33.tokenvault.net.ProxyConfig?): HttpClientEngine = Darwin.create {
    if (proxy != null) {
        this.proxy = ProxyConfig(Url("http://${proxy.host}:${proxy.port}"))
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun clockMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()
