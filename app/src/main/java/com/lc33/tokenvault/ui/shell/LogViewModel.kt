package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.model.AuditEntry
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 日志页。
 *
 * 只读 [AuditLogRepository.observeRecent]：日志由探测 / 余额 / 备份引擎在别处写入，
 * 这一页不产生日志。「清空」从数据页进来（那里才该有二次确认），这里不放清空按钮。
 */
@HiltViewModel
class LogViewModel @Inject constructor(
    private val audit: AuditLogRepository,
) : ViewModel() {

    private companion object {
        const val RECENT_LIMIT = 200
    }

    val entries: StateFlow<List<AuditEntry>> = audit.observeRecent(RECENT_LIMIT)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun clear() {
        viewModelScope.launch { audit.clear() }
    }
}
