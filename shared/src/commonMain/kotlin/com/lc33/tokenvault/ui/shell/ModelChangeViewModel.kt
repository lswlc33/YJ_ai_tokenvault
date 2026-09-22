package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.catalog.ModelChangeSummary
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.ModelChange
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.domain.repo.ModelChangeRepository
import com.lc33.tokenvault.domain.repo.ModelRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.screens.model.ModelChangeUiState
import com.lc33.tokenvault.screens.model.ProviderModelChangeRow
import com.lc33.tokenvault.screens.model.TrendRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 模型变化页（哪家站点新增 / 下架了哪些模型）。**只读**，没有写方法。
 *
 * 四份输入：上下架流水、`models` 当前态（对账用——流水说"上了"而现在库里已经没有的，
 * 是用户自己删的，不是站点给的）、供应商摘要（名字与手选颜色）、界面上选的窗口。
 *
 * 分组、去重、与现状对账全在纯函数 [ModelChangeSummary] 里，这个类只负责把选项和数据
 * 喂给它、再把结果贴上供应商名字。共享策略与 [DashboardViewModel] 一致用
 * [SharingStarted.Lazily]：切后台再回来不闪骨架。
 */
class ModelChangeViewModel constructor(
    changes: ModelChangeRepository,
    models: ModelRepository,
    providers: ProviderRepository,
) : ViewModel() {

    private val range = MutableStateFlow(TrendRange.D30)

    val state: StateFlow<ModelChangeUiState> = combine(
        changes.observeAll(),
        models.observeAll(),
        providers.observeSummaries(),
        range,
    ) { events, liveModels, summaries, range ->
        buildState(events, liveModels, summaries, range)
    }.stateIn(viewModelScope, SharingStarted.Lazily, ModelChangeUiState(loading = true))

    fun setRange(value: TrendRange) { range.value = value }

    private fun buildState(
        events: List<ModelChange>,
        liveModels: List<AiModel>,
        summaries: List<ProviderSummary>,
        range: TrendRange,
    ): ModelChangeUiState {
        val now = nowMillis()
        val nameById = summaries.associate { it.provider.id to it.provider.name }
        val colorById = summaries.associate { it.provider.id to it.provider.color }

        // 当前态按站点摊成集合：对账要问的是"这家此刻还认不认这个模型"，
        // 不是"哪把 Key 的列表里有它"——一家三把 Key 各看得见一半时，站点层面它就是还在。
        val liveByProvider: Map<Long, Set<String>> = liveModels
            .groupBy { it.providerId }
            .mapValues { (_, rows) -> rows.map { it.modelId }.toSet() }

        val summarized = ModelChangeSummary.summarize(
            changes = events,
            liveModelIdsByProvider = liveByProvider,
            rangeDays = range.days,
            now = now,
        )

        return ModelChangeUiState(
            loading = false,
            range = range,
            rows = summarized.map { row ->
                ProviderModelChangeRow(
                    providerId = row.providerId,
                    // 供应商被删时它的流水也跟着 CASCADE 走了，正常情况下都查得到名字；
                    // 查不到给空串，页面兜底显示"已删除的供应商"。
                    providerName = nameById[row.providerId] ?: "",
                    colorIndex = colorById[row.providerId],
                    added = row.added,
                    removed = row.removed,
                )
            },
            hasAnyEvent = events.isNotEmpty(),
            nowMs = now,
        )
    }
}
