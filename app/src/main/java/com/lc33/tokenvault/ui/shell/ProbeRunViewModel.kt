package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.data.dao.ProbeRunDao
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.probe.ProbeItemResult
import com.lc33.tokenvault.screens.model.ProbeRunSummary
import com.lc33.tokenvault.screens.probe.ProbeItemRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 探测明细页（§13.4，`ProbeRunRoute`）。
 *
 * 这一页只做两件事：看上一轮结果、重试。数据来自两个地方，都是只读：
 * - [ProbeEngine.lastRound]：最近一轮逐项结果的累计快照（§1418 的 `results`）；
 * - [ProbeRunDao.observeLatest]：`probe_runs` 最新一行，给"上次探测"摘要。
 *
 * 分组规则（§13.4）：失败项、本轮未探测项、成功项三组分开——未探测不是失败（红线 11），
 * 混在一起会让用户以为有 N 个东西坏了而其实只坏了 M 个。
 */
@HiltViewModel
class ProbeRunViewModel @Inject constructor(
    private val probeEngine: ProbeEngine,
    probeRunDao: ProbeRunDao,
) : ViewModel() {

    data class UiState(
        val lastRun: ProbeRunSummary? = null,
        val failed: List<ProbeItemRow> = emptyList(),
        val skipped: List<ProbeItemRow> = emptyList(),
        val succeeded: List<ProbeItemRow> = emptyList(),
        val nowMs: Long = 0,
    )

    val state: StateFlow<UiState> = combine(
        probeEngine.lastRound,
        probeRunDao.observeLatest(),
    ) { results, lastRun ->
        UiState(
            lastRun = lastRun?.toSummary(),
            failed = results.filter { it.outcome.isFailure() }.map { it.toRow() },
            skipped = results.filter { it.outcome == ProbeOutcome.SKIPPED || it.outcome == ProbeOutcome.CANCELLED }
                .map { it.toRow() },
            succeeded = results.filter { it.outcome == ProbeOutcome.SUCCESS }.map { it.toRow() },
            nowMs = System.currentTimeMillis(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState())

    /** 仅重试上一轮的失败项与未探测项（§13.4）。无失败项 / 已在跑 / 锁定态时是 no-op。 */
    fun retryFailed() {
        probeEngine.retryFailed()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** 判定性失败 + 瞬时失败都算"失败组"；跳过/取消算"未探测"，成功单独一组。 */
private fun ProbeOutcome.isFailure(): Boolean = when (this) {
    ProbeOutcome.CONCLUSIVE_FAIL, ProbeOutcome.NETWORK_ERROR,
    ProbeOutcome.RATE_LIMITED, ProbeOutcome.UPSTREAM_ERROR,
    -> true
    ProbeOutcome.SUCCESS, ProbeOutcome.SKIPPED, ProbeOutcome.CANCELLED -> false
}

/** 逐项结果 → 明细页的一行。health 用 [toUiHealth]（判定结论优先，瞬时失败给 Warn）。 */
private fun ProbeItemResult.toRow(): ProbeItemRow = ProbeItemRow(
    providerName = providerName,
    providerId = providerId,
    health = toUiHealth(),
    detail = detail,
    latencyMs = latencyMs,
)
