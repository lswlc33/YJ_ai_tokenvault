package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.data.dao.AuditLogDao
import com.lc33.tokenvault.data.entity.AuditLogEntity
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.domain.model.AuditEntry
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 日志。
 *
 * 脱敏（红线 32）在这里收口：[record] 入库前把 `message` 与 `detail` 都过 [Redactor.scrub]。
 * 日志表没有秘密列（它只有文本），所以锁定态也能写——日志记录的是"探测结果如何"，
 * 那件事在锁定态不该被卡住。
 *
 * [Redactor] 的"已知明文秘密"第一道由 [com.lc33.tokenvault.crypto.KnownSecrets] 提供
 * （探测 reveal / 用户展开明文时登记，锁定时清空），正则只作兜底。
 */
class RoomAuditLogRepository constructor(
    private val dao: AuditLogDao,
    private val redactor: Redactor,
    private val now: () -> Long,
) : AuditLogRepository {

    override suspend fun record(
        level: LogLevel,
        category: LogCategory,
        message: String,
        detail: String?,
        providerId: Long?,
        keyId: Long?,
    ) {
        dao.insert(
            AuditLogEntity(
                at = now(),
                level = level.wireName,
                category = category.wireName,
                providerId = providerId,
                keyId = keyId,
                message = redactor.scrub(message),
                detail = detail?.let { redactor.scrub(it) },
            ),
        )
    }

    override fun observeRecent(limit: Int): Flow<List<AuditEntry>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun clear() = dao.clear()
}
