package com.lc33.tokenvault.net

import com.lc33.tokenvault.domain.model.AuditEntry
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 只负责"记下写了什么"的假日志仓库。
 *
 * 网络层的用例关心的是脱敏与级别，不关心 Room：`HttpEngine` / `WebDavClient` 拿到的
 * 是接口，这里把每一次 `record` 原样收下来供断言。
 */
class RecordingAuditLog : AuditLogRepository {

    data class Recorded(
        val level: LogLevel,
        val category: LogCategory,
        val message: String,
        val detail: String?,
    )

    val records = mutableListOf<Recorded>()

    override suspend fun record(
        level: LogLevel,
        category: LogCategory,
        message: String,
        detail: String?,
        providerId: Long?,
        keyId: Long?,
    ) {
        records += Recorded(level, category, message, detail)
    }

    override fun observeRecent(limit: Int, minLevel: LogLevel): Flow<List<AuditEntry>> =
        flowOf(emptyList())

    override suspend fun trimOlderThan(before: Long) = Unit

    override suspend fun clear() {
        records.clear()
    }
}
