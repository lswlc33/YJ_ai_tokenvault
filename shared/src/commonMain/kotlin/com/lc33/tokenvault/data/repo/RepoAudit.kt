package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import kotlinx.coroutines.CancellationException

/**
 * 仓库写入日志失败时不能反过来破坏用户操作；日志是旁路，不是事务的一部分。
 *
 * 但**协程取消不是"日志写失败"**：吞掉 `CancellationException` 会让一个已经被取消的
 * 协程继续往下跑完剩下的写操作（撤销、恢复这类多步路径上尤其危险），而且它会把取消
 * 变成"看不见的延迟"。所以这里只兜真正的异常，取消原样上抛。
 */
internal suspend fun AuditLogRepository?.recordSafe(
    level: LogLevel,
    category: LogCategory,
    message: String,
    detail: String? = null,
    providerId: Long? = null,
    keyId: Long? = null,
    runId: Long? = null,
) {
    if (this == null) return
    try {
        record(
            level = level,
            category = category,
            message = message,
            detail = detail,
            providerId = providerId,
            keyId = keyId,
            runId = runId,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // 日志写坏了不影响用户那一步已经做成的操作。
    }
}
