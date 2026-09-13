package com.lc33.tokenvault.engine

import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.first

/** 日志保留期维护：默认删除 7 天前记录，永久选项不按时间删除。 */
class LogMaintenance(
    private val settings: SettingsRepository,
    private val audit: AuditLogRepository,
    private val now: () -> Long,
) {
    suspend fun run() {
        val retention = settings.observeLogRetention().first()
        val days = retention.days ?: return
        audit.trimOlderThan(now() - days * DAY_MILLIS)
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
