package com.lc33.tokenvault.domain

/**
 * 应用内 HTTP 最大并发数的档位（§13.4 探测设置页）。
 *
 * 与 [AutoLockPolicy]、[AutoRefreshPolicy] 同一条约束：**[OPTIONS] 的顺序必须与
 * `Res.array.probe_max_concurrency_options` 一一对应**，因为 `AppDropdownRow` 的 API 是
 * `selectedIndex`；错位一格就是"选 8 得到 32"，等于一次把四倍请求打给上游——那正是
 * 红线 29 要挡的事，所以 `ArchitectureRulesTest` 会比两边的条数。
 *
 * 落库存的是**并发数本身**而不是下拉下标：以后在中间插一档时，已存的设置不能悄悄改含义。
 *
 * 这一档管的是"同时有几个请求真的在网线上"，不是"开几个线程"：协程等 I/O 不占线程，
 * 而 iOS 的 URLSession 压根没有全局并发概念，所以这个数只能在应用层兑现（见
 * `net/ConcurrencyGate`），三端才是同一个语义。
 */
object HttpConcurrencyPolicy {

    /**
     * 默认档 8：它**正好等于**这次改动之前 Android/JVM 引擎里写死的 `maxRequests = 8`，
     * 所以默认值在安卓上是个可验证的空操作，不会一装上就改变既有行为。
     * （iOS 之前没有全局上限，默认 8 对它是收紧——这是三端对齐的目的，不是副作用。）
     */
    const val DEFAULT = 8

    /**
     * 与 `probe_max_concurrency_options` 同序：2 / 4 / 8 / 16 / 32。
     *
     * **没有 1 这一档**：1 就是一根全局串行队列，一家挂死的站能占到调用超时（35s 或 Key
     * 自己填的秒数），期间连"检查更新"这种一次性的交互请求都得排队。最低给 2 就是留一条
     * 不被单个上游完全冻住的缝。
     */
    val OPTIONS: List<Int> = listOf(2, 4, 8, 16, 32)

    /** 最高档：引擎的 `maxRequests` 必须 ≥ 它，否则顶两档只是把排队从闸门口挪进 OkHttp 队列。 */
    val MAX: Int = OPTIONS.max()

    /**
     * 每家主机同时在飞的上限，三端同一个值。
     *
     * 这个数字不是调出来的：`net/HostGate.kt` 记着 M0.5 实测——挂 Cloudflare 的站同 host
     * 约 2.4 秒内的第 3 个请求就撞 `error code: 1015`。并发数调大也不放开这一条，
     * 所以"选 32"只在**很多家不同的供应商**同时有活时才快得起来，界面文案必须照实说。
     */
    const val PER_HOST_MAX_REQUESTS = 3

    /** 下拉下标 → 并发数。越界回到默认档：越界只可能来自资源与这张表不一致。 */
    fun at(index: Int): Int = OPTIONS.getOrNull(index) ?: DEFAULT

    /** 并发数 → 下拉下标。表里没有的数落到默认档那一枚，保证下拉永远有一项选中。 */
    fun indexOf(count: Int): Int =
        OPTIONS.indexOf(count).takeIf { it >= 0 } ?: OPTIONS.indexOf(DEFAULT)

    /**
     * 存储形态 → 并发数。
     *
     * null（没写过）、解析不出来、0 或负数一律回默认档。**0 尤其要挡**：那意味着
     * "一个请求都别想出去"，坏数据不能把整个应用的网络掐死。
     */
    fun decode(stored: String?): Int =
        stored?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT

    /** 并发数 → 存储形态。不做钳制：钳集会让人以为 0 是可达状态，而读方向根本不认它。 */
    fun encode(count: Int): String = count.toString()
}
