package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.AuditEntry
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import kotlinx.coroutines.flow.Flow

/**
 * 日志（`audit_log` 表）。
 *
 * 这一版只有 [record] 与 [observeRecent]：写入端是探测 / 余额 / 备份 / 锁定这几条会
 * "动到秘密"的链路，展示端是日志页。日志是**可解释性**，不是审计留痕——它记录的是
 * "发生了什么"以便用户排查，不承担"谁在什么时候看了什么"这种职责。
 *
 * 脱敏（红线 32）发生在实现里：`message` 与 `detail` 入库前必须过 `Redactor.scrub`。
 * 脱敏需要"当前会话已知的秘密"，那是 `VaultSession` 的知识，所以这一层不做——
 * 放在这里会变成"有时候脱敏了有时候没脱"。
 */
interface AuditLogRepository {

    /**
     * 写一条日志。**入库前必须脱敏**（红线 32），这件事由实现负责，调用方只管传
     * 原始 message / detail。
     */
    suspend fun record(
        level: LogLevel,
        category: LogCategory,
        message: String,
        detail: String? = null,
        providerId: Long? = null,
        keyId: Long? = null,
    )

    /** 最近 N 条，倒序。日志页读它。 */
    fun observeRecent(limit: Int): Flow<List<AuditEntry>>

    /** 清空（数据页的「清空日志」）。 */
    suspend fun clear()
}
