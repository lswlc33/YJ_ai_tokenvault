package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.ModelDao
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.di.NowEpochMs
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.repo.ModelRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 模型。
 *
 * 这张表全是明文（见 CLAUDE.md 的加密边界表），所以读路径不碰 DEK，锁定态也能读。
 * 手动录入的行 `source = 'manual'`、`discoveredVia = null`，自动同步永不改动（红线 13）。
 */
@Singleton
class RoomModelRepository @Inject constructor(
    private val dao: ModelDao,
    @param:NowEpochMs private val now: () -> Long,
) : ModelRepository {

    override fun observeByProvider(providerId: Long): Flow<List<AiModel>> =
        dao.observeByProvider(providerId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun add(
        providerId: Long,
        modelId: String,
        protocol: Protocol,
        needsReview: Boolean,
    ): Long {
        val stamp = now()
        return dao.insertIgnoring(
            ModelEntity(
                providerId = providerId,
                modelId = modelId.trim(),
                protocol = protocol.wireName,
                source = "manual",
                discoveredVia = null,
                enabled = true,
                needsReview = needsReview,
                firstSeenAt = stamp,
                sortOrder = dao.findByProvider(providerId).size,
            ),
        )
    }
}
