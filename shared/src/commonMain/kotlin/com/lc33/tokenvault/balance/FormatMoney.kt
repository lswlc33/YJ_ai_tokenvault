package com.lc33.tokenvault.balance

import kotlin.math.abs
import kotlin.math.floor

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
 * `BigDecimal.setScale(2, HALF_UP)` 一致——金额本来就是 2 位小数的定点数，
 * 用整数分表示不丢精度，也天然跨平台。
 *
 * 货币符号由币种查表得到（红线 15：不硬编码符号、不硬编码阈值）。
 */
object FormatMoney {

    /** 定点舍入到 2 位小数（"分"）。所有展示与求和前的唯一入口。 */
    fun roundedCents(amount: Double): Long {
        // 等价 BigDecimal.valueOf(amount).setScale(2, HALF_UP)，但**全程算术、不碰字符串表示**。
        //
        // 旧实现是剥 `Double.toString` 的十进制外壳，而 `toString` 在 `|v| >= 1e7` 或
        // `< 1e-3` 时输出科学计数法：`8.0E-4` 直接 NumberFormatException，`9.98E-4` 被算成
        // 999 分，`1.2345678E7` 被算成 1.23 分。金额可以任意大（火山那类企业账户上千万元），
        // 所以那条路径早晚会撞上。
        if (amount.isNaN()) return 0L // 脏数据不该让仪表盘崩掉，也没有"分"可舍
        val negative = amount < 0
        // 局部量刻意不叫 `abs`：下面还要用 `kotlin.math.abs` 这个函数，同名变量在调用位置
        // 上容易读成"把 Double 当函数调"，改名比让读者去查重载解析规则便宜。
        val magnitude = abs(amount)
        if (!magnitude.isFinite() || magnitude * 100.0 >= Long.MAX_VALUE.toDouble()) {
            // 夹到极值而不是抛：调用方是首页求和，一个坏值不该掀掉整屏。
            return if (negative) -Long.MAX_VALUE else Long.MAX_VALUE
        }

        val scaled = magnitude * 100.0
        var cents = floor(scaled).toLong()
        val frac = scaled - cents
        // HALF_UP 是**十进制**语义：`0.145` 应当进位，但它的 `double` 是 0.14499999999999999…，
        // 乘 100 得 14.499999999999998，严格按"加半分后截断"会掉到 14 分。
        // 判等用的容差只需盖住乘法自身的舍入误差（约 scaled * 2^-53），再放宽一点仍然远小于
        // 半分，既救回 0.145 / 2.675 这类"恰好半分"，也不会把 0.1449999996 这种真值误抬。
        //
        // **必须封顶**：`scaled * 1e-14` 只在 scaled 小于约 5e13（也就是金额约 5e11 元）时才是
        // "半分的一个零头"。1e15 元时它算出来是 1000 分，于是 `abs(frac - 0.5) <= tolerance`
        // 对任何值都成立——每一笔大额的余额都被凭空抬高一 cent（1e15 → 100000000000000001 分）。
        // 到了 double 连"分以下"都表示不出来的量级，半分判断已经没有真值可依，一律不进位。
        val tolerance = (1e-9 + scaled * 1e-14).coerceAtMost(HALF_CENT_SNAP_CEILING)
        if (frac > 0.5 || abs(frac - 0.5) <= tolerance) cents += 1

        // 负数按绝对值舍入再取负号 == 远离零进位，与 BigDecimal 的 HALF_UP 对 -0.005 → -1 分一致。
        return if (negative) -cents else cents
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

    /**
     * 半分判等容差的上限，单位是"分"（即千分之一分）。
     *
     * 为什么是这个量级：容差要盖住的只是 `double` 的表示误差（约 `scaled * 2^-53`），
     * 一旦它长到千分之一分以上，抬上去的那一位就不再是"救回恰好半分"，而是凭空造出一分。
     * 它同时保证容差永远够不到半分（0.5），所以 `frac == 0` 的整数金额不会被无条件进位。
     */
    private const val HALF_CENT_SNAP_CEILING = 1e-3
}
