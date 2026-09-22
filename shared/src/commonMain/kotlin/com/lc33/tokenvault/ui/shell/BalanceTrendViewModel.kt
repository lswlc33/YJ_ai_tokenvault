package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.balance.BalanceTrendAggregator
import com.lc33.tokenvault.domain.model.BalanceSample
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.domain.repo.BalanceHistoryRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.screens.model.BalanceTrendUiState
import com.lc33.tokenvault.screens.model.ProviderTrendSeries
import com.lc33.tokenvault.screens.model.TrendRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 余额趋势报告（逐供应商的余额折线）。**只读**，没有写方法。
 *
 * 三份输入：余额历史流、供应商摘要流（要名字与颜色）、以及界面上选的「看多久」。
 * 聚合是纯函数 [BalanceTrendAggregator] 的活（LOCF、按天分桶、按币种求和），这个类只做
 * 「把选项和数据喂给它、再把结果贴上供应商名字与调色板下标」这层编排。
 *
 * 共享策略用 [SharingStarted.Lazily]（仿 [DashboardViewModel]）：切后台再回来不闪骨架。
 */
class BalanceTrendViewModel constructor(
    history: BalanceHistoryRepository,
    providers: ProviderRepository,
) : ViewModel() {

    private val range = MutableStateFlow(TrendRange.D30)

    val state: StateFlow<BalanceTrendUiState> = combine(
        history.observeAll(),
        providers.observeSummaries(),
        range,
    ) { samples, summaries, range ->
        buildState(samples, summaries, range)
    }.stateIn(viewModelScope, SharingStarted.Lazily, BalanceTrendUiState(loading = true))

    fun setRange(value: TrendRange) { range.value = value }

    private fun buildState(
        samples: List<BalanceSample>,
        summaries: List<ProviderSummary>,
        range: TrendRange,
    ): BalanceTrendUiState {
        // 供应商元数据：名字用于图例/卡片；颜色沿用用户在编辑页选的 provider.color，
        // 没选过（NULL）就交给 ProviderPalette 按 id 生成——十家撞在同一枚蓝色上，
        // 图例就得逐行去对名字，这个页要答的问题（"这条线是谁"）就答不上了。
        val nameById = summaries.associate { it.provider.id to it.provider.name }
        val explicitColor = summaries.associate { it.provider.id to it.provider.color }

        val aggregated = BalanceTrendAggregator.aggregate(
            samples = samples,
            rangeDays = range.days,
            now = nowMillis(),
        )

        val series = aggregated.map { s ->
            ProviderTrendSeries(
                providerId = s.providerId,
                // 供应商可能已被删（历史仍在，因为 keyId 不挂外键、providerId 才 CASCADE——
                // 但删供应商会级联清历史，所以这里通常查得到；查不到给空串，页面兜底显示）。
                providerName = nameById[s.providerId] ?: "",
                currency = s.currency,
                colorIndex = explicitColor[s.providerId],
                balancePoints = s.balancePoints,
                netBalanceChange = s.netBalanceChange,
            )
        }

        return BalanceTrendUiState(
            loading = false,
            range = range,
            series = series,
            nowMs = nowMillis(),
        )
    }
}
