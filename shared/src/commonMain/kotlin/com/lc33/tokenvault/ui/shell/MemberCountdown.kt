package com.lc33.tokenvault.ui.shell

/**
 * 会员卡的娱乐倒计时（设置页那张卡）。
 *
 * 规则：每点一次会员状态，到期年份减 10；点满 [TAPS_TO_REVERT] 次取消会员身份。
 * 纯算术抽出来是给 JVM 单测钉住门槛——"第 10 次才取消"这种边界最容易在
 * `>=` 和 `>` 之间写错。
 *
 * **它不改变任何真实行为**：这个倒计时只驱动卡面上显示的年份与"要不要把
 * `app_settings.member` 翻回 false"，与 `MemberViewModel` 的注释同一套说法。
 */
object MemberCountdown {

    /** 初始到期年份（与 `settings_member_expiry` 文案里的 2099 一致）。 */
    const val BASE_YEAR = 2099

    /** 每点一次减多少年。 */
    const val YEARS_PER_TAP = 10

    /** 点满几次取消会员。 */
    const val TAPS_TO_REVERT = 10

    /** 点了 [taps] 次之后，卡面上应该显示的到期年份。 */
    fun expiryYear(taps: Int): Int = BASE_YEAR - taps * YEARS_PER_TAP

    /** 点满 [TAPS_TO_REVERT] 次就该取消会员了。 */
    fun shouldRevert(taps: Int): Boolean = taps >= TAPS_TO_REVERT
}