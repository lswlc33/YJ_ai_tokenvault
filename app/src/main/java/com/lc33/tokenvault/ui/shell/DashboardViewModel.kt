package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.data.dao.ProbeRunDao
import com.lc33.tokenvault.engine.BalanceEngine
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.probe.ProbeProgress
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.screens.model.DashboardUiState
import com.lc33.tokenvault.screens.model.ProbeRunSummary
import com.lc33.tokenvault.screens.model.UiProviderRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 仪表盘（§13.4）。**只读**，所以这个类里没有一个写方法。
 *
 * 四条决定：
 *
 * 1. **每个数字都来自库，没有一个是编的。** 还没有数据来源的三块（上次探测、备份、
 *    以及两个动作按钮）给的是**空态而不是假数字**——首屏摆一个 `USD 255.41`，
 *    而管理页加起来是另一个数，用户第一眼就知道这软件不可信，之后所有真数字他也不会信了。
 * 2. **计数与聚合走的是管理页那同一条 SQL 与同一批纯函数**（`contentCountsOf` /
 *    `aggregateHealth`）。两个页面各算一遍是 CLAUDE.md 明令禁止的：同一个数字在两处
 *    各算一遍，迟早对不上。
 * 3. **全部密钥一条订阅**（`observeAll`），不是每家一条。按家订阅是 N+1，而且新增一家时
 *    整组 Flow 要重建，列表会闪一下。
 * 4. **不解密**（§6.1 推论 3）：这一页只读 `health` 这类明文列，所以它在锁定态也活得下去
 *    ——虽然锁定时 `LockGate` 会把整棵树换掉，但这条订阅不会在 Flow 内部抛异常。
 */
class DashboardViewModel constructor(
    providers: ProviderRepository,
    keys: ApiKeyRepository,
    settings: SettingsRepository,
    private val probeEngine: ProbeEngine,
    private val balanceEngine: BalanceEngine,
    probeRunDao: ProbeRunDao,
) : ViewModel() {

    /** 一次取好的两份原始数据。分开 map 两次就要 combine 两次，那才会不同步。 */
    private data class Snapshot(val summaries: List<ProviderSummary>, val keys: List<ApiKey>)

    private val snapshot: Flow<Snapshot> =
        combine(providers.observeSummaries(), keys.observeAll()) { summaries, allKeys ->
            Snapshot(summaries, allKeys)
        }

    val state: StateFlow<DashboardUiState> = combine(
        snapshot,
        settings.observeBalanceThresholds(),
        probeEngine.progress,
        probeRunDao.observeLatest(),
    ) { snap, thresholds, progress, lastRun ->
        snap.toUiState(
            thresholds = thresholds,
            progress = progress?.toUiProgress(),
            lastRun = lastRun?.toSummary(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardUiState())

    /**
     * 余额明细那个二级页要的行。
     *
     * 从**同一条** [snapshot] 派生而不是让那一页自己去查：余额卡上的合计与明细页的每一行
     * 必须出自同一批数据，否则"点进去发现加起来不等于卡上那个数"。
     */
    val providerRows: StateFlow<List<UiProviderRow>> = snapshot
        .map { snap -> snap.rows() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** 仪表盘"开始探测"。引擎已接入（M5）。 */
    fun startProbe() = probeEngine.start()

    /** 仪表盘"取消探测"。 */
    fun cancelProbe() = probeEngine.cancel()

    /**
     * 刷新所有已配置余额查询的供应商（§9.3 的刷新图标）。逐家查、逐家落库，
     * 结果经 [providers.observeSummaries] 那条订阅自然流回 UI——不用手动通知（红线 10）。
     */
    fun refreshBalance() {
        viewModelScope.launch {
            val summaries = snapshot.first().summaries
            summaries.forEach { summary ->
                if (summary.provider.balanceKind != com.lc33.tokenvault.domain.BalanceKind.NONE) {
                    runCatching { balanceEngine.refresh(summary.provider.id) }
                }
            }
        }
    }

    private fun Snapshot.rows(): List<UiProviderRow> {
        val healths = keys.groupBy({ it.providerId }, { it.health })
        return summaries.map { summary ->
            summary.toRow(health = aggregateHealth(healths[summary.provider.id].orEmpty()))
        }
    }

    private fun Snapshot.toUiState(
        thresholds: Map<String, Double>,
        progress: com.lc33.tokenvault.screens.model.ProbeProgress?,
        lastRun: ProbeRunSummary?,
    ): DashboardUiState = DashboardUiState(
        balance = balanceSummaryOf(summaries.map { it.provider }),
        counts = contentCountsOf(summaries),
        health = healthBreakdownOf(keys),
        attention = attentionItemsOf(
            summaries = summaries,
            healthByProvider = keys.groupBy({ it.providerId }, { it.health }),
            // 阈值来自设置（§13.4 探测设置页），默认 §9.3 的初值。
            // 不写死数字在这里（红线 15）：初值是有名字、有出处的领域常量。
            thresholds = thresholds,
        ),
        // 上一轮探测摘要：`probe_runs` 最新一行。相对时间由页面算（红线 19），
        // 这里给时间戳。没跑过就是 null → 卡片画"还没探测过"。
        lastRun = lastRun,
        progress = progress,
        // 备份在 M9。空的 BackupStatus 会让那张卡画成 warn 色的"还没有备份"，这是真话
        backup = BackupStatus(),
        nowMs = System.currentTimeMillis(),
    )

    /** 引擎的进度（runId/running/done/total/currentHost）→ 仪表盘卡的进度。 */
    private fun ProbeProgress.toUiProgress(): com.lc33.tokenvault.screens.model.ProbeProgress =
        com.lc33.tokenvault.screens.model.ProbeProgress(
            done = done,
            total = total,
            // currentHost 是 host 名，展示"正在探测哪家"；空时退到"进行中"的占位。
            // ViewModel 读不到资源（红线 19），所以这里给 host 名，文案由页面兜底。
            currentLabel = currentHost ?: "",
        )

    private companion object {
        /** 转屏时别退订：退订会让六块卡在重建后闪一下空态。 */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
