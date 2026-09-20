package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.balance.UsageReportAggregator
import com.lc33.tokenvault.domain.model.BalanceSample
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.domain.repo.BalanceHistoryRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.screens.model.ReportMetric
import com.lc33.tokenvault.screens.model.ReportRange
import com.lc33.tokenvault.screens.model.UsageReportProviderSeries
import com.lc33.tokenvault.screens.model.UsageReportUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 用量变化报告（余额增长 / 消耗趋势，逐供应商折线）。**只读**，没有写方法。
 *
 * 三份输入：余额历史流、供应商摘要流（要名字与颜色）、以及界面上选的「看哪维 / 看多久」。
 * 聚合是纯函数 [UsageReportAggregator] 的活（LOCF、按天分桶、按币种求和），这个类只做
 * 「把选项和数据喂给它、再把结果贴上供应商名字与调色板下标」这层编排。
 *
 * 共享策略用 [SharingStarted.Lazily]（仿 [DashboardViewModel]）：切后台再回来不闪骨架。
 */
class UsageReportViewModel constructor(
    history: BalanceHistoryRepository,
    providers: ProviderRepository,
) : ViewModel() {

    private val metric = MutableStateFlow(ReportMetric.BALANCE)
    private val range = MutableStateFlow(ReportRange.D30)

    val state: StateFlow<UsageReportUiState> = combine(
        history.observeAll(),
        providers.observeSummaries(),
        metric,
        range,
    ) { samples, summaries, metric, range ->
        buildState(samples, summaries, metric, range)
    }.stateIn(viewModelScope, SharingStarted.Lazily, UsageReportUiState(loading = true))

    fun setMetric(value: ReportMetric) { metric.value = value }
    fun setRange(value: ReportRange) { range.value = value }

    private fun buildState(
        samples: List<BalanceSample>,
        summaries: List<ProviderSummary>,
        metric: ReportMetric,
        range: ReportRange,
    ): UsageReportUiState {
        // 供应商元数据：名字用于图例/卡片，颜色下标沿用用户在编辑页选的 provider.color；
        // 没选过就按供应商列表里的稳定次序兜底，保证同一家每次同色。
        val nameById = summaries.associate { it.provider.id to it.provider.name }
        val explicitColor = summaries.associate { it.provider.id to it.provider.color }
        val fallbackIndex = summaries.mapIndexed { index, s -> s.provider.id to index }.toMap()

        val aggregated = UsageReportAggregator.aggregate(
            samples = samples,
            rangeDays = range.days,
            now = nowMillis(),
        )

        val series = aggregated.map { s ->
            UsageReportProviderSeries(
                providerId = s.providerId,
                // 供应商可能已被删（历史仍在，因为 keyId 不挂外键、providerId 才 CASCADE——
                // 但删供应商会级联清历史，所以这里通常查得到；查不到给空串，页面兜底显示）。
                providerName = nameById[s.providerId] ?: "",
                currency = s.currency,
                colorIndex = explicitColor[s.providerId] ?: fallbackIndex[s.providerId] ?: 0,
                balancePoints = s.balancePoints,
                usagePoints = s.usagePoints,
                netBalanceChange = s.netBalanceChange,
                totalConsumed = s.totalConsumed,
            )
        }

        return UsageReportUiState(
            loading = false,
            metric = metric,
            range = range,
            series = series,
            nowMs = nowMillis(),
        )
    }
}
