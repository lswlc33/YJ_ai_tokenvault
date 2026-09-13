package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository

/** 仓库写入日志失败时不能反过来破坏用户操作；日志是旁路，不是事务的一部分。 */
internal suspend fun AuditLogRepository?.recordSafe(
    level: LogLevel,
    category: LogCategory,
    message: String,
    detail: String? = null,
    providerId: Long? = null,
    keyId: Long? = null,
) {
    if (this == null) return
    runCatching {
        record(
            level = level,
            category = category,
            message = message,
            detail = detail,
            providerId = providerId,
            keyId = keyId,
        )
    }
}
