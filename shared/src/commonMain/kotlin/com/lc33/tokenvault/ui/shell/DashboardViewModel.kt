package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.engine.BalanceEngine
import com.lc33.tokenvault.engine.BalanceRefreshOutcome
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.probe.ProbeProgress
import com.lc33.tokenvault.screens.model.DashboardUiState
import com.lc33.tokenvault.screens.model.ProbeRunSummary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
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
    probeRunRepo: ProbeRunRepository,
) : ViewModel() {

    /** 一次取好的两份原始数据。分开 map 两次就要 combine 两次，那才会不同步。 */
    private data class Snapshot(val summaries: List<ProviderSummary>, val keys: List<ApiKey>)

    private val snapshot: Flow<Snapshot> =
        combine(providers.observeSummaries(), keys.observeAll()) { summaries, allKeys ->
            Snapshot(summaries, allKeys)
        }

    // 共享策略用 Lazily 而不是 WhileSubscribed(5s)：后者退订会把 StateFlow 复位成
    // `loading = true`，于是切后台再回来整页闪一次加载态（六块卡全变回骨架）。
    // Room 的流是事件驱动而不是轮询，ViewModel 活着就一直订着没有额外代价。
    val state: StateFlow<DashboardUiState> = combine(
        snapshot,
        settings.observeBalanceThresholds(),
        probeEngine.progress,
        probeRunRepo.observeLatest(),
    ) { snap, thresholds, progress, lastRun ->
        snap.toUiState(
            thresholds = thresholds,
            progress = progress?.toUiProgress(),
            lastRun = lastRun?.toSummary(),
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, DashboardUiState(loading = true))

    /** 仪表盘”开始探测”。结果与进度由 ProbeEngine 的状态流回 UI。 */
    fun startProbe(): Boolean = probeEngine.start()

    /**
     * 一次性事件：余额刷新的结果。文案由 Shell 用资源解析。
     *
     * 为什么要有这一条：以前 `refreshBalance()` 是发出去就不管，而提示在**发出的那一刻**
     * 就说”余额已刷新”。余额接口全部失败时用户照样看到那句已刷新，而且卡片上的数字
     * 还是旧的——这句话就成了假话，还是唯一一句让他以为”钱查过了”的话。
     */
    sealed interface Event {
        /**
         * 这一趟跑完了，带的是"几把成、几把挂、第一个失败的原因与上游原话"。
         * 提示按结果说，见 `ui/common/BalanceFailureText.kt` 的 `balanceRoundMessage`。
         */
        data class BalanceRan(val outcome: BalanceRefreshOutcome) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /**
     * 这一趟余额刷新在跑。
     *
     * 挡的是**重复花钱**：每一趟都会把开了余额查询的密钥逐个打一遍上游接口，连点五下
     * 就是五遍全量请求。页面据此把刷新图标置灰，并在卡片上说出”正在查询余额”，
     * 不让人对着一个没反应的图标反复按。
     */
    private val _refreshingBalance = MutableStateFlow(false)
    val refreshingBalance: StateFlow<Boolean> = _refreshingBalance.asStateFlow()

    /**
     * 刷新所有已配置余额查询的供应商（§9.3 的刷新图标）。逐家查、逐家落库，
     * 结果经 [providers.observeSummaries] 那条订阅自然流回 UI——不用手动通知（红线 10）；
     * 但”这一趟成没成”要由 [Event] 说，见上面的理由。
     */
    fun refreshBalance() {
        if (_refreshingBalance.value) return
        _refreshingBalance.value = true
        viewModelScope.launch {
            try {
                _events.send(
                    Event.BalanceRan(runBalanceRefresh { balanceEngine.refreshAll() }),
                )
            } finally {
                _refreshingBalance.value = false
            }
        }
    }

    /**
     * 顶栏刷新：一次把该发的都发出去——全量探测（密钥 L1/L2 + 开了自动获取的模型列表）、
     * 官网连通性、余额。
     *
     * [excludeProbe] 为 true 时**只发**官网连通性与余额——探测那一发由调用方先用
     * [startProbe] 的返回值判断"这轮有没有真的启动"（正在跑就不重复发，探测要花钱），
     * 再决定要不要提示“已开始”。两条路径拆开是为了让“重复点击不叠加请求”和
     * “余额可以随时再刷”互不妨碍。
     */
    fun refreshStatus(excludeProbe: Boolean = false) {
        viewModelScope.launch {
            probeEngine.refreshReachability()
            val outcome = runBalanceRefresh { balanceEngine.refreshAll() }
            // 与管理页同一条：探测一轮由 `roundResults` 统一播，余额查不到在首页不会
            // 让任何一个数字动起来（失败的快照不参与合计），不出声就等于没刷。
            if (outcome.needsAttention) _events.trySend(Event.BalanceRan(outcome))
        }
        if (!excludeProbe) probeEngine.start()
    }

    private fun Snapshot.balanceByProvider(): Map<Long, com.lc33.tokenvault.domain.model.BalanceSnapshot?> =
        summaries.associate { summary ->
            summary.provider.id to aggregateBalanceOf(keys.filter { it.providerId == summary.provider.id })
        }

    private fun Snapshot.toUiState(
        thresholds: Map<String, Double>,
        progress: com.lc33.tokenvault.screens.model.ProbeProgress?,
        lastRun: ProbeRunSummary?,
    ): DashboardUiState = DashboardUiState(
        loading = false,
        balance = keyBalanceSummaryOf(keys),
        counts = contentCountsOf(summaries),
        health = healthBreakdownOf(keys),
        attention = attentionItemsOf(
            summaries = summaries,
            balanceByProvider = balanceByProvider(),
            // 关掉「密钥有效性」的 Key 不进告警：用户明确说过别判断它，
            // 就不该在「需要处理」里报它的故障（与展示层的 effectiveHealth 同一条规则）。
            healthByProvider = keys.filter { it.settings.probe.keyValidity }
                .groupBy({ it.providerId }, { it.health }),
            // 阈值来自设置（§13.4 探测设置页），默认 §9.3 的初值。
            // 不写死数字在这里（红线 15）：初值是有名字、有出处的领域常量。
            thresholds = thresholds,
        ),
        // 上一轮探测摘要：`probe_runs` 最新一行。相对时间由页面算（红线 19），
        // 这里给时间戳。没跑过就是 null → 卡片画"还没探测过"。
        lastRun = lastRun,
        progress = progress,
        // 备份状态不在这里：总览按规格只有余额 / 概览 / 探测三块，而以前这里硬编码一个
        // 空的 BackupStatus，等于对界面撒谎说"这台机器还没备份过"。真话（最近一次成功
        // 备份的时间与落点）在同步页那张卡上，来自 app_settings 的持久记录。
        nowMs = nowMillis(),
    )

    /** 引擎的进度（runId/running/done/total/currentHost）→ 仪表盘卡的进度。 */
    private fun ProbeProgress.toUiProgress(): com.lc33.tokenvault.screens.model.ProbeProgress =
        com.lc33.tokenvault.screens.model.ProbeProgress(
            done = done,
            total = total,
            // currentHost 是 host 名，展示"正在探测哪家"；空时退到"进行中"的占位。
            // ViewModel 读不到资源（红线 19），所以这里给 host 名，文案由页面兜底。
            currentLabel = currentHost ?: "",
            providerDone = providerDone,
            providerTotal = providerTotal,
            providerSucceeded = providerOk,
            providerFailed = providerFail,
            keyDone = keyDone,
            keyTotal = keyTotal,
            keySucceeded = keyOk,
            keyFailed = keyFail,
        )
}
