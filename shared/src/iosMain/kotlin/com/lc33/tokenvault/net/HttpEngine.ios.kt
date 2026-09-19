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
 * （`timeoutIntervalForResource`），对应 Android/JVM 那侧的 read 20s / call 35s。
 * 不配的话用系统默认（60s / 7 天），一轮探测的总预算会被单个慢请求吃掉。
 *
 * Key 上自己的 `timeoutSeconds` 走 `buildClient()` 装的 HttpTimeout 插件：Darwin 引擎把
 * `socketTimeoutMillis` 写成 `NSMutableURLRequest.timeoutInterval`，所以 [HttpEngine]
 * 在设 per-request 超时时会同时写 socket 值，iOS 这边才真按用户填的秒数停。
 *
 * 重定向：Darwin 引擎的 `KtorNSURLSessionDelegate` 本来就把
 * `willPerformHTTPRedirection` 直接回 `null`（3xx 原样交回），与 `buildClient()` 里
 * `followRedirects = false` 一致——三端都拿到 3xx，分类结论才不会两端分叉。
 */
actual fun platformEngine(): HttpClientEngine = Darwin.create {
    configureSession {
        timeoutIntervalForRequest = ENGINE_SOCKET_TIMEOUT_MS / 1000.0
        timeoutIntervalForResource = ENGINE_CALL_TIMEOUT_MS / 1000.0
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun clockMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()
