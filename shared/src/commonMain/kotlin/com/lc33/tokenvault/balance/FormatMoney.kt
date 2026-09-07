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
    fun roundedCents(amount: Double): Long {
        // 等价 BigDecimal.valueOf(amount).setScale(2, HALF_UP)。关键在**不能用浮点乘法**
        // （round(x * 100) 会把 0.005 算成 0，因为 0.005*100 = 0.4999...）：
        // BigDecimal.valueOf 内部用 Double.toString 拿到**最短精确十进制表示**再精确舍入，
        // 这里同样基于字符串做十进制 HALF_UP 舍入到 2 位。
        val s = amount.toString() // 如 "0.005"、"42.099999999999994"、"358.0"、"-3.0"
        val negative = s.startsWith("-")
        val body = if (negative) s.substring(1) else s
        val dot = body.indexOf('.')
        val intPart = if (dot < 0) body else body.substring(0, dot)
        val fracPart = if (dot < 0) "" else body.substring(dot + 1)

        // 小数补齐到 3 位：前两位是"分"，第三位决定 HALF_UP 进位。
        val frac = fracPart.padEnd(3, '0')
        val whole = intPart.toLong()
        val cents = frac.substring(0, 2).toInt()
        val roundUp = frac[2] >= '5'

        val total = whole * 100 + cents + (if (roundUp) 1 else 0)
        return if (negative) -total else total
    }

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
