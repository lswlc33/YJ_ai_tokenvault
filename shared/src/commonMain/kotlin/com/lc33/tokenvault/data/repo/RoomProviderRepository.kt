package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.data.mapper.toEntity
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.domain.repo.UndoableDeletion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomProviderRepository constructor(
    private val dao: ProviderDao,
    private val now: () -> Long,
    private val audit: AuditLogRepository? = null,
    private val restorer: UndoRestorer? = null,
) : ProviderRepository {
    override fun observeSummaries(): Flow<List<ProviderSummary>> =
        dao.observeSummaries().map { rows -> rows.map { it.toDomain() } }

    override fun observeProvider(id: Long): Flow<Provider?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun find(id: Long): Provider? = dao.findById(id)?.toDomain()

    override suspend fun save(provider: Provider): Long {
        val stamp = now()
        if (provider.id == 0L) {
            return dao.insert(provider.toEntity().copy(createdAt = stamp, updatedAt = stamp)).also { id ->
                audit.recordSafe(LogLevel.INFO, LogCategory.VAULT, "provider added", "id=$id name=${provider.name}", providerId = id)
            }
        }
        val existing = dao.findById(provider.id)
        // 带着 id 来更新、但那一行已经不在了（别处刚删掉 / 界面拿着过期 id）：Room 的
        // `@Update` 此时**静默影响 0 行**，接着写一条"已更新"的日志就是在造假——
        // 用户以为存下了，库里那家还是老的。所以这里显式失败，让调用方拿到可懂的错误。
        if (existing == null) {
            throw IllegalStateException("provider ${provider.id} not found while updating")
        }
        dao.update(
            provider.toEntity().copy(
                createdAt = existing.createdAt,
                updatedAt = stamp,
            ),
        )
        audit.recordSafe(LogLevel.INFO, LogCategory.VAULT, "provider updated", "id=${provider.id} name=${provider.name}", providerId = provider.id)
        return provider.id
    }

    override suspend fun delete(id: Long): UndoableDeletion? {
        val undo = restorer?.deleteProvider(id)
        if (restorer == null) dao.delete(id)
        audit.recordSafe(LogLevel.WARN, LogCategory.VAULT, "provider deleted", "id=$id", providerId = id)
        return undo
    }

    override suspend fun setGroup(ids: List<Long>, groupId: Long?) {
        dao.setGroup(ids, groupId, now())
        audit.recordSafe(LogLevel.INFO, LogCategory.VAULT, "providers regrouped", "count=${ids.size} groupId=${groupId ?: -1}")
    }

    override suspend fun reorder(idsInOrder: List<Long>) {
        dao.reorder(idsInOrder, now())
        audit.recordSafe(LogLevel.INFO, LogCategory.VAULT, "providers reordered", "count=${idsInOrder.size}")
    }

    override suspend fun updateWebsiteStatus(
        id: Long,
        latencyMs: Long?,
        checkedAt: Long,
        error: String?,
    ) = dao.updateWebsiteStatus(id, latencyMs, checkedAt, error)
}
