package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.data.mapper.toEntity
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.domain.repo.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomProviderRepository constructor(
    private val dao: ProviderDao,
    private val now: () -> Long,
) : ProviderRepository {
    override fun observeSummaries(): Flow<List<ProviderSummary>> =
        dao.observeSummaries().map { rows -> rows.map { it.toDomain() } }

    override fun observeProvider(id: Long): Flow<Provider?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun find(id: Long): Provider? = dao.findById(id)?.toDomain()

    override suspend fun save(provider: Provider): Long {
        val stamp = now()
        if (provider.id == 0L) {
            return dao.insert(provider.toEntity().copy(createdAt = stamp, updatedAt = stamp))
        }
        val existing = dao.findById(provider.id)
        dao.update(
            provider.toEntity().copy(
                createdAt = existing?.createdAt ?: stamp,
                updatedAt = stamp,
            ),
        )
        return provider.id
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun setGroup(ids: List<Long>, groupId: Long?) =
        dao.setGroup(ids, groupId, now())

    override suspend fun updateWebsiteStatus(
        id: Long,
        latencyMs: Long?,
        checkedAt: Long,
        error: String?,
    ) = dao.updateWebsiteStatus(id, latencyMs, checkedAt, error)
}
