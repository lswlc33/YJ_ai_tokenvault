package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.model.BalanceSample
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** 折线上的一个点：x 是那一天零点的 epoch 毫秒，y 是当天的余额。 */
data class ChartPoint(val x: Long, val y: Double)

/**
 * 一条趋势线：某家供应商在某个币种下的余额序列。
 *
 * 为什么线的身份是 (供应商, 币种) 而不是只有供应商：同一家的不同 Key 可能用不同币种
 * （一把美元站、一把人民币站），直接相加是把 $ 和 ¥ 加到一起，那是个没有意义的数。
 * 拆成两条线各自求和，图例上带出币种，用户一眼能分开。
 */
data class BalanceTrendSeries(
    val providerId: Long,
    val currency: String,
    /** 可用余额随时间（跨该家该币种所有 Key 求和）。充值上跳、消耗下滑。 */
    val balancePoints: List<ChartPoint>,
) {
    /**
     * 区间净变化 = 末值 − 首值。正=净充值，负=净消耗。
     *
     * **少于两个点返回 null，而不是 0.0**：一个读数算不出任何"变化"，而 0.0 会被读成
     * "这个区间不增不减"——一句听着可信的假话。余额历史是从 v10 才开始攒的，所以报告
     * 刚上线的那几天几乎每家都只有一个点，这一档不是边角情况。
     */
    val netBalanceChange: Double?
        get() = if (balancePoints.size < 2) null else balancePoints.last().y - balancePoints.first().y
}

/**
 * 把稀疏的余额历史样本聚合成按天的余额趋势线。**纯函数、平台无关，单独测**。
 *
 * 三件事，每一件都是趋势图正确的前提：
 *
 * 1. **LOCF（最近观测前推）**：各把 Key 的探测时刻互不对齐，不能假设「每天都有一条」。
 *    某天的余额取「截至那天结束、这把 Key 最近一条样本」的值——上一次查到多少，就一直算
 *    多少，直到下一次查到。没这一步，多把 Key 的日曲线会因为采样错位而互相穿插成锯齿。
 * 2. **按天分桶**：秒级时间戳画不成「趋势」，且不同 Key 同一天的两条样本不该各占一个 x。
 *    统一落到本地日历日的零点。
 * 3. **按 (供应商, 币种) 求和**：见 [BalanceTrendSeries] 的说明。
 *
 * 只有余额这一条线：这个 App 观测不到"这一段时间被消耗了多少"——上游不给已用字段时，
 * 从余额跌幅反推出来的数里混着充值与退款，那不是消耗。所以这里只陈述真查得到的事：
 * 每次探测到的余额，以及由它算出的区间净变化。
 */
object BalanceTrendAggregator {

    /**
     * @param samples 全部余额历史，顺序不限（内部会排）。
     * @param rangeDays 窗口天数（7 / 30 / 90）。今天算 1 天，所以 7 天 = 今天与前 6 天。
     * @param now 当前墙上时间（epoch 毫秒），注入而非直接读时钟（红线 20）。
     * @param zone 时区，分桶按本地日历日。默认取系统时区。
     */
    fun aggregate(
        samples: List<BalanceSample>,
        rangeDays: Int,
        now: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<BalanceTrendSeries> {
        if (rangeDays <= 0 || samples.isEmpty()) return emptyList()

        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        val windowStart = today.plus(-(rangeDays - 1).toLong(), DateTimeUnit.DAY)
        val windowDays: List<LocalDate> = (0 until rangeDays).map { windowStart.plus(it.toLong(), DateTimeUnit.DAY) }

        // 每把 Key 的样本按时间升序，供 LOCF 扫描。providerId 从样本本身取（一把 Key 只属一家）。
        val byKey: Map<Long, List<BalanceSample>> = samples
            .groupBy { it.keyId }
            .mapValues { (_, rows) -> rows.sortedWith(compareBy({ it.capturedAt }, { it.capturedAt })) }

        // 某把 Key「截至某天结束」的最近一条样本：capturedAt < 次日零点。
        fun locfAt(keySamples: List<BalanceSample>, day: LocalDate): BalanceSample? {
            val nextDayStart = day.plus(1L, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
            return keySamples.lastOrNull { it.capturedAt < nextDayStart }
        }

        // (providerId, currency) → 各天的余额和。
        val balanceByBucket = LinkedHashMap<Pair<Long, String>, MutableMap<LocalDate, Double>>()

        for ((_, keySamples) in byKey) {
            for (day in windowDays) {
                val current = locfAt(keySamples, day)
                if (current?.amount != null) {
                    val bucket = current.providerId to (current.currency ?: UNKNOWN)
                    // 自己累加而不用 `Map.merge`：后者是 java.util 的成员，JVM 目标（含
                    // 跑单测的 jvm 与 Android）能解析，Kotlin/Native 的 iOS 目标没有它，
                    // 于是本地全绿而 CI 的 ios job 才报 unresolved。commonMain 里累加
                    // 一律走这个写法。
                    balanceByBucket.getOrPut(bucket) { LinkedHashMap() }.accumulate(day, current.amount)
                }
            }
        }

        return balanceByBucket.entries.map { (bucket, balanceDays) ->
            val (providerId, currency) = bucket
            val balancePoints = windowDays
                .filter { it in balanceDays }
                .map { day -> ChartPoint(dayStartMillis(day, zone), balanceDays.getValue(day)) }

            BalanceTrendSeries(
                providerId = providerId,
                currency = currency,
                balancePoints = balancePoints,
            )
        }.sortedByDescending { it.balancePoints.lastOrNull()?.y ?: 0.0 }
    }

    private fun dayStartMillis(day: LocalDate, zone: TimeZone): Long =
        day.atStartOfDayIn(zone).toEpochMilliseconds()

    /**
     * 按天累加金额，等价于 JVM 上的 `Map.merge(day, amount, Double::plus)`。
     *
     * 单独写一个而不直接调 merge：`merge` 是 `java.util.Map` 的成员，Android 与 jvm
     * 目标（跑单测的就是它）都能解析，Kotlin/Native 的 iOS 目标根本没这个函数，
     * 于是本地测试全绿、CI 的 ios job 才报 unresolved reference。
     */
    private fun MutableMap<LocalDate, Double>.accumulate(day: LocalDate, amount: Double) {
        this[day] = (this[day] ?: 0.0) + amount
    }

    private const val UNKNOWN = "UNKNOWN"
}
