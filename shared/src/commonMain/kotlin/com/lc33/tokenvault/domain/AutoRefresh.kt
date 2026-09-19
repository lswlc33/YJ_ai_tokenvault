package com.lc33.tokenvault.domain

/**
 * 自动刷新的间隔档位（§13.4 探测设置页）。
 *
 * 与 [AutoLockPolicy] 同一条约束：**[OPTIONS] 的顺序必须与
 * `Res.array.probe_auto_refresh_interval_options` 一一对应**，因为 `AppDropdownRow` 的
 * API 是 `selectedIndex`；数量错了不是崩溃而是错位（选「每 15 分钟」得到 6 小时），
 * 所以 `ArchitectureRulesTest` 会比这两边的条数。
 *
 * 落库存的是**分钟数**而不是下拉下标，理由与自动锁定一样：以后在中间插一档时，
 * 已存的设置不能悄悄改变含义。
 *
 * 档位为什么只到 6 小时：自动刷新的代价是往每一家供应商发真请求（红线 36 之后
 * 自动路径只走不花钱的 L1/可达性与已开自动获取的余额），"每天一次"落在应用后台
 * 定时器上意义不大——那一档不如直接重开应用。
 */
object AutoRefreshPolicy {

    /** 默认档：一小时。落在"回应用时数据大体是新的"与"别把上游打烦"之间。 */
    const val DEFAULT_MINUTES = 60

    /** 与 `probe_auto_refresh_interval_options` 同序：5 分 / 15 分 / 30 分 / 1 小时 / 6 小时。 */
    val OPTIONS: List<Int> = listOf(5, 15, 30, 60, 360)

    /** 下拉下标 → 分钟数。越界回到默认档：越界只可能来自资源与这张表不一致。 */
    fun at(index: Int): Int = OPTIONS.getOrNull(index) ?: DEFAULT_MINUTES

    /** 分钟数 → 下拉下标。表里没有的数落到默认档那一枚，至少有一枚是选中的。 */
    fun indexOf(minutes: Int): Int =
        OPTIONS.indexOf(minutes).takeIf { it >= 0 } ?: OPTIONS.indexOf(DEFAULT_MINUTES)

    /**
     * 存储形态 → 分钟数。
     *
     * [stored] 为 null 表示键还没写过，给 [DEFAULT_MINUTES]；解析不出来或不是正数也回默认，
     * 因为"间隔 0 分钟"意味着一个不停发请求的死循环，宁可退回一小时。
     */
    fun decode(stored: String?): Int =
        stored?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_MINUTES

    /**
     * 分钟数 → 存储形态。
     *
     * 不做钳制：写 0 就存 0，而读方向（[decode]）把 0 与坏值一起落回默认档。
     * 在这里钳成 1 反而更糟——一个传错的参数会变成"每分钟往每一家发一轮"。
     */
    fun encode(minutes: Int): String = minutes.toString()
}
