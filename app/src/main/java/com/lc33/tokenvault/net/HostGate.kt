package com.lc33.tokenvault.net

/**
 * host 级最小间隔门闸（计划.md §8.1 末尾、红线 29）。
 *
 * 并发上限（OkHttp Dispatcher 的 `maxRequestsPerHost = 3`）挡不住边缘限流——M0.5 实测
 * JustDoWork 挂在 Cloudflare 后面，**同 host 约 2.4 秒内的第 3 个请求**就撞 `error code: 1015`。
 * 所以除了并发上限，还要给每个 host 一个**串行最小间隔**：默认 800ms，撞过 429 后加倍，
 * 上限 8s。
 *
 * 这里不做并发控制（那是 OkHttp Dispatcher 的活），只管"同一 host 相邻两个请求之间的
 * 最小间隔"：`acquire(host)` 挂起直到距离上一个该 host 的请求过去了足够久。
 *
 * @param nowMillis 注入的单调时钟，测试用虚拟时间。
 */
class HostGate(
    private val defaultMinIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 每个 host 当前的间隔要求。 */
    private val intervals = mutableMapOf<String, Long>()

    /** 每个 host 上一个请求放行的时间戳。 */
    private val lastRelease = mutableMapOf<String, Long>()

    /** 当前 host 的串行间隔。 */
    fun currentIntervalMs(host: String): Long = intervals[host] ?: defaultMinIntervalMs

    /**
     * 取号：返回这个 host 的下一个请求可以放行的时间戳。
     *
     * 真正的 `delay` 由调用方（`OkHttpEngine`）负责——这里只记账，保持纯逻辑可测。
     */
    fun acquire(host: String): Long {
        val interval = intervals[host] ?: defaultMinIntervalMs
        val last = lastRelease[host]
        val now = nowMillis()
        val nextAllowed = if (last == null) now else (last + interval).coerceAtLeast(now)
        lastRelease[host] = nextAllowed
        return nextAllowed
    }

    /** 撞了 429：该 host 的间隔加倍，封顶 8s。 */
    fun onRateLimited(host: String) {
        val current = intervals[host] ?: defaultMinIntervalMs
        intervals[host] = (current * 2).coerceAtMost(maxIntervalMs)
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 800L
        const val MAX_INTERVAL_MS = 8_000L
    }
}
