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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 日志页：等级筛选、保留期和清空。 */
class LogViewModel constructor(
    private val audit: AuditLogRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

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
        viewModelScope.launch { settings.setLogLevelFilter(level) }
    }

    fun setRetention(value: LogRetention) {
        viewModelScope.launch {
            settings.setLogRetention(value)
            val days = value.days ?: return@launch
            audit.trimOlderThan(nowMillis() - days * DAY_MILLIS)
        }
    }

    fun clear() {
        viewModelScope.launch { audit.clear() }
    }

    private companion object {
        const val RECENT_LIMIT = 500
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
