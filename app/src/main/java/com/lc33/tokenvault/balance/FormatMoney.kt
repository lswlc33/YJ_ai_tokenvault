package com.lc33.tokenvault.balance

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额格式化（§9.1）。
 *
 * 金额只在这里做**定点舍入**：`BigDecimal.valueOf(x).setScale(2, HALF_UP)`，
 * **先舍入再相加**。首页按币种求和时若不这么做，`0.1 + 0.2` 就会出现
 * `0.30000000000000004`。所以任何"两个金额相加"的代码都必须先把操作数过一遍
 * [rounded] 再相加，而不是用原始 `Double` 直接 `+`。
 *
 * 货币符号由币种查表得到（红线 15：不硬编码符号、不硬编码阈值）。
 */
object FormatMoney {

    /** 定点舍入到 2 位小数。所有展示与求和前的唯一入口。 */
    fun rounded(amount: Double): BigDecimal =
        BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)

    /** 两个金额相加（先各自舍入再相加）。 */
    fun add(a: Double, b: Double): BigDecimal = rounded(a).add(rounded(b))

    /**
     * 格式化金额为带货币符号的字符串。
     *
     * @param currency ISO 4217 币种代码，如 `USD` / `CNY`。未知币种回退到不带符号、
     *   只显示数值与币种代码（`42.10 UNKNOWN`）——不猜符号（红线 15）。
     */
    fun format(amount: Double, currency: String): String {
        val symbol = CURRENCY_SYMBOLS[currency.uppercase()]
        val value = rounded(amount).toPlainString()
        return if (symbol != null) "$symbol$value" else "$value $currency"
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
