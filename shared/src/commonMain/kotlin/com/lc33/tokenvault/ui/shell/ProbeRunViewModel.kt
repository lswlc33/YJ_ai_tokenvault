package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.probe.ProbeItemResult
import com.lc33.tokenvault.probe.SkipReason
import com.lc33.tokenvault.screens.model.ProbeRunSummary
import com.lc33.tokenvault.screens.probe.ProbeItemRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 探测明细页（§13.4，`ProbeRunRoute`）。
 *
 * 这一页做三件事：看上一轮结果、重试、**中途停止**。数据来自三个地方，都是只读：
 * - [ProbeEngine.lastRound]：最近一轮逐项结果的累计快照（§1418 的 `results`）；
 * - [ProbeRunRepository.observeLatest]：`probe_runs` 最新一行，给"上次探测"摘要；
 * - [ProbeEngine.progress]：这一轮是不是还在跑——停止入口只在那里有意义。
 *
 * 分组规则（§13.4）：失败项、本轮未探测项、成功项三组分开——未探测不是失败（红线 11），
 * 混在一起会让用户以为有 N 个东西坏了而其实只坏了 M 个。
 */
class ProbeRunViewModel constructor(
    private val probeEngine: ProbeEngine,
    probeRunRepo: ProbeRunRepository,
) : ViewModel() {

    data class UiState(
        val lastRun: ProbeRunSummary? = null,
        val failed: List<ProbeItemRow> = emptyList(),
        val skipped: List<ProbeItemRow> = emptyList(),
        val succeeded: List<ProbeItemRow> = emptyList(),
        val nowMs: Long = 0,

        /** 这一轮还在跑：明细页据此画"停止探测"入口，并藏掉重试（引擎本来就拒绝并发轮）。 */
        val running: Boolean = false,
    )

    val state: StateFlow<UiState> = combine(
        probeEngine.lastRound,
        probeRunRepo.observeLatest(),
        probeEngine.progress,
    ) { results, lastRun, progress ->
        UiState(
            lastRun = lastRun?.toSummary(),
            failed = results.filter { it.outcome.isFailure() }.map { it.toRow() },
            skipped = results.filter { it.outcome == ProbeOutcome.SKIPPED || it.outcome == ProbeOutcome.CANCELLED }
                .map { it.toRow() },
            succeeded = results.filter { it.outcome == ProbeOutcome.SUCCESS }.map { it.toRow() },
            nowMs = nowMillis(),
            running = progress?.running == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState())

    /**
     * 仅重试上一轮的失败项与未探测项（§13.4）。无失败项 / 已在跑 / 锁定态时是 no-op。
     *
     * 把引擎的"这一轮到底发出去没有"原样回给页面：发不出去还念一句"正在重试失败项"，
     * 用户等到的就是一句没发生的事（这是审查里"重试按钮忽略返回值"那一条）。
     */
    fun retryFailed(): Boolean = probeEngine.retryFailed()

    /**
     * 停止正在跑的这一轮（§13.4 的"停止探测"）。
     *
     * 是 [ProbeEngine.cancel] 的真调用路径：引擎侧早已是 `Job.cancel()` + NonCancellable
     * 收尾（把 `probe_runs` 写成 cancelled 而不是留半截），此前唯一没人按的按钮就是这里。
     */
    fun cancelProbe() {
        probeEngine.cancel()
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
    keyLabel = keyLabel,
    level = level,
    health = toUiHealth(),
    detail = detail,
    latencyMs = latencyMs,
    // "被取消"的项没有引擎给的 skipReason（它压根没进编排器的判定），补一个 Cancelled
    // 让明细页的"为什么没测"文案有出处，而不是光一行"未探测"。
    skipReason = skipReason ?: outcome.takeIf { it == ProbeOutcome.CANCELLED }?.let { SkipReason.Cancelled },
)
