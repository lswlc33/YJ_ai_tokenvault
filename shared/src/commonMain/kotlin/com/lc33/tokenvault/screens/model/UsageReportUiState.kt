package com.lc33.tokenvault.screens.model

import com.lc33.tokenvault.balance.ChartPoint

/** 报告看哪一维：余额或用量。页面把它翻成 Tab 文案（ViewModel 读不到资源）。 */
enum class ReportMetric { BALANCE, USAGE }

/** 时间范围。天数在 [days]，Tab 文案由页面按序号翻。 */
enum class ReportRange(val days: Int) {
    D7(7),
    D30(30),
    D90(90),
}

/**
 * 用量变化报告的整屏状态。
 *
 * 只带原始数值与枚举、不带已翻译文案（与 [KeyModelsUiState] 同一条约定）：币种符号、
 * 日期、金额都要走 `FormatMoney` / `absoluteDateLabel` 在页面里成形，ViewModel 拿不到
 * `stringResource`。每条 [series] 已经是聚合好的点集，页面只管画。
 */
data class UsageReportUiState(
    val loading: Boolean = true,
    val metric: ReportMetric = ReportMetric.BALANCE,
    val range: ReportRange = ReportRange.D30,
    val series: List<UsageReportProviderSeries> = emptyList(),
    val nowMs: Long = 0L,
) {
    /** 有没有任何一条线有点。没有就画空态（还没攒到历史）。 */
    val hasAnyData: Boolean get() = series.any { it.balancePoints.isNotEmpty() }
}

/**
 * 一家供应商（在某个币种下）的报告序列。
 *
 * @param colorIndex 调色板下标，页面用 `LocalProviderPalette.swatchFor` 取色；
 *   同一家在图与卡上用同一色。
 */
data class UsageReportProviderSeries(
    val providerId: Long,
    val providerName: String,
    val currency: String,
    val colorIndex: Int,
    val balancePoints: List<ChartPoint>,
    val usagePoints: List<ChartPoint>,
    /** 区间净变化（末−首）。正=净充值，负=净消耗。 */
    val netBalanceChange: Double,
    /** 区间总消耗。 */
    val totalConsumed: Double,
)
