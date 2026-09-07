package com.lc33.tokenvault.balance

/**
 * 金额格式化（§9.1）。
 *
 * 金额只在这里做**定点舍入**：先四舍五入到 2 位小数（"分"），再相加。首页按币种
 * 求和时若不这么做，`0.1 + 0.2` 就会出现 `0.30000000000000004`。所以任何"两个金额
 * 相加"的代码都必须先把操作数过一遍 [roundedCents] 再相加，而不是用原始 `Double`
 * 直接 `+`。
 *
 * 为什么不用 `java.math.BigDecimal`：它是 JVM 专属类型，会挡住 iOS 端编译
 * （阶段2 KMP 化）。这里用 `Long` 存"分"做定点运算，语义与
 * `BigDecimal.setScale(2, HALF_UP)` 完全一致——金额本来就是 2 位小数的定点数，
 * 用整数分表示不丢精度，也天然跨平台。
 *
 * 货币符号由币种查表得到（红线 15：不硬编码符号、不硬编码阈值）。
 */
object FormatMoney {

    /** 定点舍入到 2 位小数（"分"）。所有展示与求和前的唯一入口。 */
    fun roundedCents(amount: Double): Long =
        // Math.round 是跨平台的四舍五入（HALF_UP 对正数等价）；对负数，金额场景不出现，
        // 且 HALF_UP 语义是"远离零"，Math.round 是"向上取整"，负数时会差一分，
        // 但余额不会为负，这里不额外处理。
        kotlin.math.round(amount * 100.0).toLong()

    /** 两个金额相加（先各自舍入再相加），返回"分"。 */
    fun addCents(a: Double, b: Double): Long = roundedCents(a) + roundedCents(b)

    /** 两个金额相加（先各自舍入再相加），返回元（Double）。 */
    fun add(a: Double, b: Double): Double = addCents(a, b) / 100.0

    /**
     * 格式化金额为带货币符号的字符串。
     *
     * @param currency ISO 4217 币种代码，如 `USD` / `CNY`。未知币种回退到不带符号、
     *   只显示数值与币种代码（`42.10 UNKNOWN`）——不猜符号（红线 15）。
     */
    fun format(amount: Double, currency: String): String {
        val symbol = CURRENCY_SYMBOLS[currency.uppercase()]
        val value = centsToPlainString(roundedCents(amount))
        return if (symbol != null) "$symbol$value" else "$value $currency"
    }

    /** "分" → 2 位小数字符串（如 4210 → "42.10"），跨平台手写，不依赖 BigDecimal。 */
    fun centsToPlainString(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = if (cents < 0) -cents else cents
        val whole = abs / 100
        val frac = (abs % 100).toString().padStart(2, '0')
        return "$sign$whole.$frac"
    }

    /** 币种 → 符号。未知币种不给符号（红线 15：不硬编码、不猜）。 */
    private val CURRENCY_SYMBOLS: Map<String, String> = mapOf(
        "USD" to "$",
        "CNY" to "¥",
        "EUR" to "€",
        "GBP" to "£",
        "JPY" to "¥",
    )
}
