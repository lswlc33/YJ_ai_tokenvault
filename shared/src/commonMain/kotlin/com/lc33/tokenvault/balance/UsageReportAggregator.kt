package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.model.BalanceSample
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** 折线上的一个点：x 是那一天零点的 epoch 毫秒，y 是当天的值（余额或累计消耗）。 */
data class ChartPoint(val x: Long, val y: Double)

/**
 * 一条趋势线：某家供应商在某个币种下的余额与用量序列。
 *
 * 为什么线的身份是 (供应商, 币种) 而不是只有供应商：同一家的不同 Key 可能用不同币种
 * （一把美元站、一把人民币站），直接相加是把 $ 和 ¥ 加到一起，那是个没有意义的数。
 * 拆成两条线各自求和，图例上带出币种，用户一眼能分开。
 */
data class UsageReportSeries(
    val providerId: Long,
    val currency: String,
    /** 可用余额随时间（跨该家该币种所有 Key 求和）。充值上跳、消耗下滑。 */
    val balancePoints: List<ChartPoint>,
    /** 自窗口起点的累计消耗。单调不减。 */
    val usagePoints: List<ChartPoint>,
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

    /** 区间总消耗 = 累计线的末值。同样要两个以上点才说得出"消耗了多少"。 */
    val totalConsumed: Double?
        get() = if (usagePoints.size < 2) null else usagePoints.last().y
}

/**
 * 把稀疏的余额历史样本聚合成按天的趋势线。**纯函数、平台无关，单独测**。
 *
 * 三件事，每一件都是趋势图正确的前提：
 *
 * 1. **LOCF（最近观测前推）**：各把 Key 的探测时刻互不对齐，不能假设「每天都有一条」。
 *    某天的余额取「截至那天结束、这把 Key 最近一条样本」的值——上一次查到多少，就一直算
 *    多少，直到下一次查到。没这一步，多把 Key 的日曲线会因为采样错位而互相穿插成锯齿。
 * 2. **按天分桶**：秒级时间戳画不成「趋势」，且不同 Key 同一天的两条样本不该各占一个 x。
 *    统一落到本地日历日的零点。
 * 3. **按 (供应商, 币种) 求和**：见 [UsageReportSeries] 的说明。
 *
 * 消耗（用量线）的口径：优先用上游给的「已用」增量（`used` 单调增，delta = 今−昨）；
 * 上游没给已用就退回「余额跌幅」（`max(0, 昨−今)`，充值区间自然记 0）。两者都以 LOCF 后的
 * 值计算，跨窗口起点前一天取基线，好让窗口第一天也能算出消耗。
 */
object UsageReportAggregator {

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
    ): List<UsageReportSeries> {
        if (rangeDays <= 0 || samples.isEmpty()) return emptyList()

        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        val windowStart = today.plus(-(rangeDays - 1).toLong(), DateTimeUnit.DAY)
        // 基线日（窗口前一天）只用于算窗口第一天的消耗，不产出余额点。
        val baseline = windowStart.plus(-1L, DateTimeUnit.DAY)
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

        // (providerId, currency) → 各天的余额和 / 各天的当日消耗。
        val balanceByBucket = LinkedHashMap<Pair<Long, String>, MutableMap<LocalDate, Double>>()
        val consumeByBucket = LinkedHashMap<Pair<Long, String>, MutableMap<LocalDate, Double>>()

        for ((_, keySamples) in byKey) {
            // 预取每把 Key 在「基线 + 窗口内每天」的 LOCF 值，避免重复扫描。
            val locfByDay = HashMap<LocalDate, BalanceSample?>(rangeDays + 1)
            locfByDay[baseline] = locfAt(keySamples, baseline)
            for (day in windowDays) locfByDay[day] = locfAt(keySamples, day)

            var previous = locfByDay[baseline]
            for (day in windowDays) {
                val current = locfByDay[day]
                if (current?.amount != null) {
                    val bucket = current.providerId to (current.currency ?: UNKNOWN)
                    // 自己累加而不用 `Map.merge`：后者是 java.util 的成员，JVM 目标（含
                    // 跑单测的 jvm 与 Android）能解析，Kotlin/Native 的 iOS 目标没有它，
                    // 于是本地全绿而 CI 的 ios job 才报 unresolved。commonMain 里累加
                    // 一律走这个写法。
                    balanceByBucket.getOrPut(bucket) { LinkedHashMap() }.accumulate(day, current.amount)

                    val consumed = consumedBetween(previous, current)
                    if (consumed > 0.0) {
                        consumeByBucket.getOrPut(bucket) { LinkedHashMap() }.accumulate(day, consumed)
                    }
                }
                // 前推：这一天没有样本时保持昨天的值，好让下一天的消耗仍以真值为基线。
                if (current != null) previous = current
            }
        }

        val buckets = (balanceByBucket.keys + consumeByBucket.keys).toSet()
        return buckets.map { bucket ->
            val (providerId, currency) = bucket
            val balanceDays = balanceByBucket[bucket].orEmpty()
            val consumeDays = consumeByBucket[bucket].orEmpty()

            val balancePoints = windowDays
                .filter { it in balanceDays }
                .map { day -> ChartPoint(dayStartMillis(day, zone), balanceDays.getValue(day)) }

            // 累计消耗：从窗口第一天起把每天的当日消耗滚加，只在有余额点的那些天产出（与余额线对齐）。
            var running = 0.0
            val usagePoints = windowDays.mapNotNull { day ->
                running += consumeDays[day] ?: 0.0
                if (day in balanceDays) ChartPoint(dayStartMillis(day, zone), running) else null
            }

            UsageReportSeries(
                providerId = providerId,
                currency = currency,
                balancePoints = balancePoints,
                usagePoints = usagePoints,
            )
        }.sortedByDescending { it.balancePoints.lastOrNull()?.y ?: 0.0 }
    }

    /** 相邻两条 LOCF 样本之间这把 Key 消耗了多少：优先已用增量，退回余额跌幅。 */
    private fun consumedBetween(previous: BalanceSample?, current: BalanceSample?): Double {
        if (current == null) return 0.0
        val prevUsed = previous?.used
        val curUsed = current.used
        if (prevUsed != null && curUsed != null) {
            return (curUsed - prevUsed).coerceAtLeast(0.0)
        }
        val prevAmount = previous?.amount
        val curAmount = current.amount
        if (prevAmount != null && curAmount != null) {
            return (prevAmount - curAmount).coerceAtLeast(0.0)
        }
        return 0.0
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
