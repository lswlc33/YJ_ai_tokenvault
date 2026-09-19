package com.lc33.tokenvault.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS 端引擎：Darwin 引擎（阶段4 在 macOS runner 上编译）。
 *
 * 不提供应用内代理：Darwin 引擎的 HTTP 代理不支持 HTTPS 请求（Ktor 文档明示），
 * 移动端只能依赖系统级全局代理。
 *
 * §8.1 的超时语义在 iOS 上要显式配：NSURLSession 没有 connect / read 分开的概念，
 * 只有"这个请求多久没进展算超时"（`timeoutIntervalForRequest`）与"整个资源最多给多久"
 * （`timeoutIntervalForResource`），对应 Android/JVM 那侧的 read / call 两个入参。
 * 探测传的是 §8.1 那张表的 20s / 35s，WebDAV 传的是更宽的一档（见
 * `WEBDAV_SOCKET_TIMEOUT_MS`）——`timeoutIntervalForResource` 是会话级的，
 * 所以这个数只能由引擎拿到，per-request 的 `timeout {}` 盖不住它。
 * 不配的话用系统默认（60s / 7 天），一轮探测的总预算会被单个慢请求吃掉。
 *
 * Key 上自己的 `timeoutSeconds` 走 `buildClient()` 装的 HttpTimeout 插件：Darwin 引擎把
 * `socketTimeoutMillis` 写成 `NSMutableURLRequest.timeoutInterval`，所以 [HttpEngine]
 * 在设 per-request 超时时会同时写 socket 值，iOS 这边才真按用户填的秒数停。
 *
 * 重定向：Darwin 引擎的 `KtorNSURLSessionDelegate` 本来就把
 * `willPerformHTTPRedirection` 直接回 `null`（3xx 原样交回），与 `buildClient()` 里
 * `followRedirects = false` 一致——三端都拿到 3xx，分类结论才不会两端分叉。
 *
 * **每主机并发这一条留给 macOS 收**：Android/JVM 的引擎里是 `maxRequestsPerHost = 3`
 * （红线 29：同 host 2.4 秒内第 3 个请求就撞 Cloudflare 1015），而这里没设，走
 * NSURLSession 默认的 6——iOS 比安卓更不设防。要对齐的是
 * `NSURLSessionConfiguration.HTTPMaximumConnectionsPerHost`（`NSInteger`，得给 `Long`），
 * 但 Kotlin/Native 对这个首字母缩写的绑定名到底保留 `HTTP…` 还是改成 `Http…`，
 * 在 Windows 上没有任何办法验证（`~/.konan` 的平台 klib 都不在），而猜错就是
 * iOS 编译红。全局那一档不依赖它：`net/ConcurrencyGate` 在 commonMain，
 * 三端同一个闸，所以设置页里的「最大并发数」在 iOS 上从今天起是真数。
 */
actual fun platformEngine(readTimeoutMs: Long, callTimeoutMs: Long): HttpClientEngine = Darwin.create {
    configureSession {
        timeoutIntervalForRequest = readTimeoutMs / 1000.0
        timeoutIntervalForResource = callTimeoutMs / 1000.0
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun clockMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()
