package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.domain.repo.TransactionRunner
import com.lc33.tokenvault.engine.ProbeEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 数据页（§13.4，`DataRoute`）的 ViewModel。
 *
 * 这一页只有两个会改数据的东西：清空探测结果、清空日志。它们都是「不可撤销」，
 * 所以确认对话框在页面层弹，这里只负责真动手。
 *
 * **提示由这里发、而不是调用方按完就报"已清空"**：两件清空都是真写库（一次事务 +
 * 一次整表删除），以前协程里没有任何兜底——写失败时异常冒出协程直接崩应用，
 * 而界面早就告诉用户"已清空"了。成功与失败都走 [events]，页面只负责把语义念成文案。
 */
class DataViewModel constructor(
    private val keys: ApiKeyRepository,
    private val probeRuns: ProbeRunRepository,
    private val audit: AuditLogRepository,
    private val transactions: TransactionRunner,
    /** 只为了清空时把引擎内存里那一轮一起丢掉——明细页读的是它，不是 `probe_runs`。 */
    private val probeEngine: ProbeEngine,
) : ViewModel() {

    /** 一次性事件：只带语义，文案在资源里（红线 19）。 */
    sealed interface Event {
        data object ProbeResultsCleared : Event
        data object LogCleared : Event

        /** 那一次清空没写成，库里还是原样。 */
        data object Failed : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

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
}
