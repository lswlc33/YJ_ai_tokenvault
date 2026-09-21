package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.catalog.CatalogSyncState
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.domain.repo.TransactionRunner
import com.lc33.tokenvault.engine.CatalogSync
import com.lc33.tokenvault.engine.ProbeEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 数据页（§13.4，`DataRoute`）的 ViewModel。
 *
 * 这一页会改数据的东西：清空探测结果、清空日志、更新模型目录。前两个是「不可撤销」，
 * 所以确认对话框在页面层弹，这里只负责真动手。
 *
 * **提示由这里发、而不是调用方按完就报"已清空"**：两件清空都是真写库（一次事务 +
 * 一次整表删除），以前协程里没有任何兜底——写失败时异常冒出协程直接崩应用，
 * 而界面早就告诉用户"已清空"了。成功与失败都走 [events]，页面只负责把语义念成文案。
 *
 * 模型目录那一节挂在它而不是另开一页：`CatalogSync` 的注释里写着"设置页的「立即更新」"
 * 与"设置页那句上次更新"，而这两样在界面上从来没有过——手动更新只在模型页那条"目录还没
 * 下载过"的横幅里出现过（成功同步过一次就永远不再出现），`setCatalogAutoUpdate` 更是全仓
 * 没有调用方（默认开着、关不掉）。数据页本来就是"库里的东西怎么维护"的落点，摆在这里。
 */
class DataViewModel constructor(
    private val keys: ApiKeyRepository,
    private val probeRuns: ProbeRunRepository,
    private val audit: AuditLogRepository,
    private val transactions: TransactionRunner,
    /** 只为了清空时把引擎内存里那一轮一起丢掉——明细页读的是它，不是 `probe_runs`。 */
    private val probeEngine: ProbeEngine,
    private val catalog: CatalogSync,
    private val settings: SettingsRepository,
    private val failures: SettingsFailures,
) : ViewModel() {

    /** 一次性事件：只带语义，文案在资源里（红线 19）。 */
    sealed interface Event {
        data object ProbeResultsCleared : Event
        data object LogCleared : Event

        /** 那一次清空没写成，库里还是原样。 */
        data object Failed : Event

        /** 目录这一发跑完了（含 304：上游没变也是一次成功的同步）。 */
        data object CatalogUpdated : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** 目录同步进度。引擎那条流本来就是给界面订阅的，这里只转一手，不另存一份状态。 */
    val syncState: StateFlow<CatalogSyncState> = catalog.state

    /** 上次**成功**同步的时刻（epoch 毫秒）。0 = 从来没成功过。 */
    val catalogLastSyncAt: StateFlow<Long> = settings.observeCatalogLastSyncAt()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** 要不要每 7 天自动更新目录。默认开。 */
    val catalogAutoUpdate: StateFlow<Boolean> = settings.observeCatalogAutoUpdate()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 清空探测结果：重置所有密钥的探测字段 + 清空 `probe_runs`。密钥本身保留。 */
    fun clearProbeResults() {
        viewModelScope.launch {
            runCatching {
                transactions.inTransaction {
                    keys.resetProbeResults()
                    probeRuns.clear()
                }
                // 库清完才丢内存快照：反过来会让"清空失败"这一支把已经跑完的一轮结果
                // 白白抹掉。探测还在跑时这一句也无害——下一轮照常往里填。
                probeEngine.clearLastRound()
                audit.record(
                    level = LogLevel.INFO,
                    category = LogCategory.PROBE,
                    message = "probe results cleared",
                )
            }.onSuccess { _events.send(Event.ProbeResultsCleared) }
                .onFailure { _events.send(Event.Failed) }
        }
    }

    /** 清空日志（audit_log 整表）。 */
    fun clearLog() {
        viewModelScope.launch {
            runCatching { audit.clear() }
                .onSuccess { _events.send(Event.LogCleared) }
                .onFailure { _events.send(Event.Failed) }
        }
    }

    /**
     * 手动更新模型目录。
     *
     * **只有引擎返回 true 才报"已更新"**：已有一发在跑时 `syncNow` 直接返回 false、那一发
     * 自己会把进度发出来，这时候再念一句已更新就是在预告一件没发生的事。失败也不在这里
     * 报——引擎会把 `CatalogSyncState.Failed` 推给界面，界面那一行自己会变成"更新失败"。
     */
    fun updateCatalogNow() {
        viewModelScope.launch {
            runCatching { catalog.syncNow() }
                .onSuccess { ran -> if (ran) _events.send(Event.CatalogUpdated) }
        }
    }

    /** 自动更新开关。写库失败由 [SettingsFailures] 统一报（不能让它冒着协程崩掉整页）。 */
    fun onCatalogAutoUpdateChange(enabled: Boolean) {
        viewModelScope.launch { failures.guard { settings.setCatalogAutoUpdate(enabled) } }
    }
}
