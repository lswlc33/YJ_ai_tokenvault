package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.model.BalanceSample
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 余额趋势聚合器：LOCF → 按天分桶 → 按 (供应商, 币种) 求和，产出逐供应商的余额线。
 *
 * 时区固定 UTC、时间戳按 UTC 日界构造，断言才可重复（不受跑测机器时区影响）。
 */
class BalanceTrendAggregatorTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 1, 10)
    private val now = dayStart(today) + 12 * HOUR

    private fun dayStart(d: LocalDate): Long = d.atStartOfDayIn(zone).toEpochMilliseconds()

    /** 今天往前 [daysAgo] 天的某个时刻（默认当天正午）。 */
    private fun at(daysAgo: Int, hour: Int = 12): Long =
        dayStart(today.minus(daysAgo, DateTimeUnit.DAY)) + hour * HOUR

    private fun aggregate(samples: List<BalanceSample>, rangeDays: Int = 7) =
        BalanceTrendAggregator.aggregate(samples, rangeDays, now, zone)

    @Test
    fun `空输入给空结果`() {
        assertTrue(aggregate(emptyList()).isEmpty())
    }

    @Test
    fun `只有一个读数时不报变化`() {
        // 余额历史是从 v10 才开始攒的，报告上线的头几天每家基本都只有这一条。
        // 一个点算不出"净变化"，而 0.0 会被界面印成 `¥0.00`——那是在断言
        // "这个区间一分没动"，与真相（只查到过一次）正好相反。所以必须是 null。
        val s = aggregate(
            listOf(
                BalanceSample(
                    providerId = 1,
                    keyId = 1,
                    amount = 100.0,
                    currency = "USD",
                    capturedAt = at(0),
                ),
            ),
        ).single()
        assertEquals(1, s.balancePoints.size)
        assertNull(s.netBalanceChange)
    }

    @Test
    fun `单把 Key 的余额按天前推，缺的那几天沿用上一次的值`() {
        val samples = listOf(
            BalanceSample(providerId = 1, keyId = 1, amount = 100.0, currency = "USD", capturedAt = at(6)),
            BalanceSample(providerId = 1, keyId = 1, amount = 80.0, currency = "USD", capturedAt = at(3)),
            BalanceSample(providerId = 1, keyId = 1, amount = 60.0, currency = "USD", capturedAt = at(1)),
        )
        val series = aggregate(samples)
        assertEquals(1, series.size)
        val s = series.single()
        assertEquals(1L, s.providerId)
        assertEquals("USD", s.currency)
        // 窗口 7 天，首个样本落在第一天，所以 7 天都有值。
        assertEquals(7, s.balancePoints.size)
        assertEquals(100.0, s.balancePoints.first().y, EPS)   // 前几天前推 100
        assertEquals(60.0, s.balancePoints.last().y, EPS)     // 最后前推 60
        // 净变化 = 末 − 首 = 60 − 100。
        assertEquals(-40.0, (s.netBalanceChange ?: Double.NaN), EPS)
        // x 落在当天 UTC 零点。
        assertEquals(dayStart(today), s.balancePoints.last().x)
    }

    @Test
    fun `充值只体现在余额线上，不另算一条消耗线`() {
        // 这个 App 观测不到"消耗了多少"：100→80 可能是用了 20，也可能别的什么，
        // 80→120 是一次充值。能确证的只有余额本身，所以只有一条线。
        val samples = listOf(
            BalanceSample(providerId = 1, keyId = 1, amount = 100.0, currency = "USD", capturedAt = at(6)),
            BalanceSample(providerId = 1, keyId = 1, amount = 80.0, currency = "USD", capturedAt = at(3)),
            BalanceSample(providerId = 1, keyId = 1, amount = 120.0, currency = "USD", capturedAt = at(1)), // 充值
        )
        val s = aggregate(samples).single()
        assertEquals(7, s.balancePoints.size)
        assertEquals(100.0, s.balancePoints.first().y, EPS)
        assertEquals(120.0, s.balancePoints.last().y, EPS)
        // 净变化体现充值：120 − 100 = +20。
        assertEquals(20.0, (s.netBalanceChange ?: Double.NaN), EPS)
    }

    @Test
    fun `同家不同币种拆成两条线，不相加`() {
        val samples = listOf(
            BalanceSample(providerId = 1, keyId = 1, amount = 100.0, currency = "USD", capturedAt = at(2)),
            BalanceSample(providerId = 1, keyId = 2, amount = 200.0, currency = "CNY", capturedAt = at(2)),
        )
        val series = aggregate(samples)
        assertEquals(2, series.size)
        assertEquals(setOf("USD", "CNY"), series.map { it.currency }.toSet())
        // 都归到 provider 1，但分币种。
        assertTrue(series.all { it.providerId == 1L })
    }

    @Test
    fun `同家同币种的多把 Key 当天求和`() {
        val samples = listOf(
            BalanceSample(providerId = 1, keyId = 1, amount = 100.0, currency = "USD", capturedAt = at(2)),
            BalanceSample(providerId = 1, keyId = 2, amount = 50.0, currency = "USD", capturedAt = at(2)),
        )
        val s = aggregate(samples).single()
        // 两把 Key 当天各 100 / 50，求和 150。
        assertEquals(150.0, s.balancePoints.last().y, EPS)
    }

    @Test
    fun `不同供应商各成一条线`() {
        val samples = listOf(
            BalanceSample(providerId = 1, keyId = 1, amount = 100.0, currency = "USD", capturedAt = at(2)),
            BalanceSample(providerId = 2, keyId = 2, amount = 300.0, currency = "USD", capturedAt = at(2)),
        )
        val series = aggregate(samples)
        assertEquals(2, series.size)
        assertEquals(setOf(1L, 2L), series.map { it.providerId }.toSet())
    }

    @Test
    fun `窗口外更早的样本仍被前推进窗口`() {
        // 唯一的样本落在 30 天前，远早于 7 天窗口；它应当被前推，让整段窗口都有值。
        val samples = listOf(
            BalanceSample(providerId = 1, keyId = 1, amount = 42.0, currency = "USD", capturedAt = at(30)),
        )
        val s = aggregate(samples, rangeDays = 7).single()
        assertEquals(7, s.balancePoints.size)
        assertTrue(s.balancePoints.all { it.y == 42.0 })
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val EPS = 1e-9
    }
}
