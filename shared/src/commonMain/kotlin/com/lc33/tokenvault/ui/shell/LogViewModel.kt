package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.model.AuditEntry
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.LogRetention
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.platform.nowMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 日志页：等级筛选、保留期和清空。
 *
 * 清空与保留期都是真写库，异常不许冒出协程（会把应用直接崩掉），成败走 [events] 由页面念成文案。
 * 筛选值本身只是内存状态，落库那份失败由 [SettingsFailures] 统一报一条提示。
 */
class LogViewModel constructor(
    private val audit: AuditLogRepository,
    private val settings: SettingsRepository,
    private val failures: SettingsFailures,
) : ViewModel() {

    /** 一次性事件：只带语义，文案在资源里（红线 19）。 */
    sealed interface Event {
        data object Cleared : Event
        data object ClearFailed : Event

        /**
         * 保留期没落成。与 [ClearFailed] 分档：两条文案说的不是一回事——
         * 合成一条"清空失败"，用户在改保留期时就会以为日志被删了又没删。
         */
        data object RetentionFailed : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val _levelFilter = MutableStateFlow(LogLevel.INFO)
    val levelFilter: StateFlow<LogLevel> = _levelFilter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<AuditEntry>> = _levelFilter
        .flatMapLatest { level -> audit.observeRecent(RECENT_LIMIT, level) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val retention: StateFlow<LogRetention> = settings.observeLogRetention()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LogRetention.SEVEN_DAYS)

    init {
        viewModelScope.launch {
            settings.observeLogLevelFilter().onEach { _levelFilter.value = it }.collect {}
        }
    }

    fun setLevelFilter(level: LogLevel) {
        _levelFilter.value = level
        viewModelScope.launch { failures.guard { settings.setLogLevelFilter(level) } }
    }

    fun setRetention(value: LogRetention) {
        viewModelScope.launch {
            // 两步都要跑完才算改成：保留期写进去了但旧日志没清，卡面上写"保留 7 天"
            // 而列表里还躺着三个月前的条目。失败必须说话（清日志是要删数据的）。
            val result = runCatching {
                settings.setLogRetention(value)
                val days = value.days ?: return@runCatching
                audit.trimOlderThan(nowMillis() - days * DAY_MILLIS)
            }
            if (result.isFailure) _events.send(Event.RetentionFailed)
        }
    }

    /** 清空日志。成功才回"已清空"——先报再删等于可能报一句假话。 */
    fun clear() {
        viewModelScope.launch {
            runCatching { audit.clear() }
                .onSuccess { _events.send(Event.Cleared) }
                .onFailure { _events.send(Event.ClearFailed) }
        }
    }

    companion object {
        /**
         * 列表一次最多取这么多条。
         *
         * 页尾那句"仅显示最近 N 条"引用的就是它（[screens.settings.LogScreen]）：数字写两遍
         * 的话，改查询上限的人不会想到去改文案，于是页面开始说谎。
         */
        const val RECENT_LIMIT = 500

        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}

/**
 * 一条日志的详情（网络报文明细）。
 *
 * 单独一个 ViewModel 而不是把报文塞进列表：一段 8KB 的响应体跟着列表流每帧比较，
 * 100 条日志就是每帧比 800KB 文本。这里按 id 取一次，读完就完。
 */
class LogEntryViewModel constructor(
    private val audit: AuditLogRepository,
    private val entryId: Long,
) : ViewModel() {

    val entry: StateFlow<AuditEntry?> = flow {
        emit(audit.findById(entryId))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
