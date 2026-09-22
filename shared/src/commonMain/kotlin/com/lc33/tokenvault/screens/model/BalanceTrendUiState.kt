package com.lc33.tokenvault.screens.model

import com.lc33.tokenvault.balance.ChartPoint

/** 时间范围。天数在 [days]，Tab 文案由页面按序号翻。 */
enum class TrendRange(val days: Int) {
    D7(7),
    D30(30),
    D90(90),
}

/**
 * 余额趋势报告的整屏状态。
 *
 * 只带原始数值与枚举、不带已翻译文案（与 [KeyModelsUiState] 同一条约定）：币种符号、
 * 日期、金额都要走 `FormatMoney` / `absoluteDateLabel` 在页面里成形，ViewModel 拿不到
 * `stringResource`。每条 [series] 已经是聚合好的点集，页面只管画。
 */
data class BalanceTrendUiState(
    val loading: Boolean = true,
    val range: TrendRange = TrendRange.D30,
    val series: List<ProviderTrendSeries> = emptyList(),
    val nowMs: Long = 0L,
) {
    /** 有没有任何一条线有点。没有就画空态（还没攒到历史）。 */
    val hasAnyData: Boolean get() = series.any { it.balancePoints.isNotEmpty() }
}

/**
 * 一家供应商（在某个币种下）的余额趋势序列。
 *
 * @param colorIndex 手选颜色下标，**null = 没选过，用按 id 生成的身份色**。页面一律走
 *   `LocalProviderPalette.colorFor(providerId, colorIndex)` 取色，所以同一家在图上线、
 *   卡上圆点、管理页那排列表里是同一个颜色。
 */
data class ProviderTrendSeries(
    val providerId: Long,
    val providerName: String,
    val currency: String,
    val colorIndex: Int?,
    val balancePoints: List<ChartPoint>,
    /**
     * 区间净变化（末−首）。正=净充值，负=净消耗。
     * null = 这个区间只有一个（或零个）读数，变化**算不出来**——页面不许把它印成 `¥0.00`。
     */
    val netBalanceChange: Double?,
)
