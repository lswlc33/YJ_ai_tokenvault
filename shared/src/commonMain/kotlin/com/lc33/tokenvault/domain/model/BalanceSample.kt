package com.lc33.tokenvault.domain.model

/**
 * 一把 Key 在某一时刻的余额历史样本（`balance_history` 的纯 Kotlin 投影）。
 *
 * 与 [BalanceSnapshot] 分工不同：[BalanceSnapshot] 是「最近一次查询的结果」，含原文与
 * 错误信息、每把 Key 只有一份；[BalanceSample] 是「随时间累积的一串点」，只留画趋势要用的
 * 几个量。余额趋势报告的聚合（`balance/BalanceTrendAggregator`）读的就是它。
 */
data class BalanceSample(
    val providerId: Long,
    val keyId: Long,

    /** 可用余额。null 表示那次没拿到金额；正常情况下不会入库（[com.lc33.tokenvault.balance.shouldRecordSample] 会挡掉）。 */
    val amount: Double? = null,
    val used: Double? = null,
    val currency: String? = null,
    val capturedAt: Long,
)
