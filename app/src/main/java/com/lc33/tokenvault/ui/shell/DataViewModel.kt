package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.dao.ProbeRunDao
import com.lc33.tokenvault.data.repo.TransactionRunner
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import kotlinx.coroutines.launch

/**
 * 数据页（§13.4，`DataRoute`）的 ViewModel。
 *
 * 这一页只有两个会改数据的东西：清空探测结果、清空日志。它们都是「不可撤销」，
 * 所以确认对话框在页面层弹，这里只负责真动手。
 */
class DataViewModel constructor(
    private val keyDao: ApiKeyDao,
    private val probeRunDao: ProbeRunDao,
    private val audit: AuditLogRepository,
    private val transactions: TransactionRunner,
) : ViewModel() {

    /** 清空探测结果：重置所有密钥的探测字段 + 清空 `probe_runs`。密钥本身保留。 */
    fun clearProbeResults() {
        viewModelScope.launch {
            transactions.inTransaction {
                keyDao.resetProbeResults()
                probeRunDao.clear()
            }
            audit.record(
                level = LogLevel.INFO,
                category = LogCategory.PROBE,
                message = "probe results cleared",
            )
        }
    }

    /** 清空日志（audit_log 整表）。 */
    fun clearLog() {
        viewModelScope.launch { audit.clear() }
    }
}
