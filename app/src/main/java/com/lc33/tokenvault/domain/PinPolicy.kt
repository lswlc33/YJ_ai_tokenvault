package com.lc33.tokenvault.domain

/**
 * PIN 的弱口令判定。
 *
 * §7.2 已经承认 6 位数字 PIN 挡不住离线穷举，所以这一层**不是**为了提升强度——
 * 它挡的是"随手设成 000000 然后忘了自己设过"这种情况。10⁶ 的空间里这几类占不到千分之一，
 * 拦掉它们几乎不损失可选空间，却能挡住绝大多数"被人当面看一眼就猜中"的情形。
 *
 * 刻意**不做**的事：不查生日、不查手机号后六位、不查常见 PIN 排行榜。
 * 那些要么需要用户的个人信息（我们不该有），要么需要一张会过时的表；而它们能挡住的
 * 增量远小于"用户以为应用在保护他"的错觉带来的代价（§7.6 不假装能防）。
 */
object PinPolicy {

    /** 默认长度。设置里可以换成任意长度口令（§7.2），那时这个值不参与判定。 */
    const val DEFAULT_SLOTS = 6

    /** 太弱的原因。返回原因而不是布尔值，这样 UI 有机会说清"为什么不行"。 */
    enum class Weakness {
        /** 全是同一个数字：`000000`。 */
        AllSame,

        /** 连续递增或递减：`123456` / `987654`。 */
        Sequential,

        /** 两位或三位的重复：`121212` / `123123`。 */
        RepeatedPattern,
    }

    /**
     * 判定。返回 null 表示可以用。
     *
     * 只对**纯数字**输入做这些判断：换成长口令之后"连续递增"之类的概念没有意义，
     * 而硬套会把 `abcdef` 这种其实还行的口令拦掉。
     */
    fun weaknessOf(pin: CharArray): Weakness? {
        if (pin.size < 2) return null
        if (!pin.all { it in '0'..'9' }) return null

        if (pin.all { it == pin[0] }) return Weakness.AllSame

        if (isSequential(pin, step = 1) || isSequential(pin, step = -1)) return Weakness.Sequential

        // 周期性重复：121212（周期 2）、123123（周期 3）。周期必须真的小于长度，
        // 否则整串自己就是一个"周期"，任何 PIN 都会被判成重复。
        for (period in 1 until pin.size) {
            if (pin.size % period != 0) continue
            if (period == pin.size) continue
            if (isPeriodic(pin, period)) {
                return if (period == 1) Weakness.AllSame else Weakness.RepeatedPattern
            }
        }
        return null
    }

    fun isAcceptable(pin: CharArray): Boolean = weaknessOf(pin) == null

    private fun isSequential(pin: CharArray, step: Int): Boolean {
        for (i in 1 until pin.size) {
            if (pin[i].code - pin[i - 1].code != step) return false
        }
        return true
    }

    private fun isPeriodic(pin: CharArray, period: Int): Boolean {
        for (i in period until pin.size) {
            if (pin[i] != pin[i - period]) return false
        }
        return true
    }
}
