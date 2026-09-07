package com.lc33.tokenvault.domain.model

import com.lc33.tokenvault.domain.BalanceState

/**
 * 金额。
 *
 * `Double` 只用于运算，**展示与求和一律先定点舍入**（§9.1）：`BigDecimal.valueOf(x)
 * .setScale(2, HALF_UP)`，先舍入再相加。不这么做首页就会出现 `42.099999999999994`。
 * 格式化收敛在一个 `formatMoney` 里，货币符号由币种查表得到（红线 15：不硬编码符号）。
 */
data class Money(
    val amount: Double,
    /** ISO 4217，未知则 `"UNKNOWN"`。**必须带币种**（红线 15）。 */
    val currency: String,
)

/**
 * 一次余额查询的结果（§9.1）。
 *
 * [amount] 为 null 且 [error] 非空 = 查询失败；[amount] 为 0 = 真的没钱了。
 * 这两件事**在 UI 上必须可区分**（§9.3），所以它们在类型上就是两个不同的形状，
 * 而不是"amount = 0 兼表失败"。
 */
data class BalanceSnapshot(
    /** 可用余额。**是"可用额度"而不是"总额减已用"**（红线 14）。 */
    val amount: Double? = null,

    /** 已用，仅部分上游提供。减法只写在明确提供两个量的那个适配器内部。 */
    val used: Double? = null,

    val currency: String = UNKNOWN_CURRENCY,

    /** 上游原始文本，详情页折叠区显示，方便与上游页面核对。 */
    val raw: String? = null,

    val checkedAt: Long? = null,

    /** 非空表示这次查询失败。**不许用 amount = 0 表示失败**。 */
    val error: String? = null,
) {
    val failed: Boolean get() = error != null

    /**
     * 派生状态。**不入库**（§5.3）：阈值改了之后库里的状态就是错的，而没人会想到去重算它。
     *
     * @param thresholds 按币种配置的阈值。查不到该币种时不判 LOW——猜一个阈值等于
     *   编一个结论（红线 15：不硬编码阈值）。
     */
    fun state(thresholds: Map<String, Double>): BalanceState = when {
        error != null -> BalanceState.ERROR
        amount == null -> BalanceState.UNKNOWN
        amount < 0 -> BalanceState.NEGATIVE
        thresholds[currency]?.let { amount < it } == true -> BalanceState.LOW
        else -> BalanceState.OK
    }

    companion object {
        const val UNKNOWN_CURRENCY = "UNKNOWN"

        /** 默认阈值（§9.3）。用户可在设置里改，所以这只是初值。 */
        val DEFAULT_THRESHOLDS = mapOf("USD" to 5.0, "CNY" to 30.0)
    }
}
