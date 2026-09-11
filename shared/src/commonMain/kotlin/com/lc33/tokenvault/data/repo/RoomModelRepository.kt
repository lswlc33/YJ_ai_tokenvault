package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.ModelDao
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.data.mapper.toEntity
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.repo.ModelRepository
import com.lc33.tokenvault.domain.repo.TransactionRunner
import com.lc33.tokenvault.probe.ModelMerger
import com.lc33.tokenvault.probe.NewDiscoveredModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 模型。
 *
 * 这张表全是明文（见 CLAUDE.md 的加密边界表），所以读路径不碰 DEK，锁定态也能读。
 * 手动录入的行 `source = 'manual'`、`discoveredVia = null`，自动同步永不改动（红线 13）。
 * 每一行还挂着 `keyId`：模型列表是“这把 Key 能看见什么”，不是供应商级的一份大杂烩。
 */
class RoomModelRepository constructor(
    private val dao: ModelDao,
    private val transactions: TransactionRunner,
    private val now: () -> Long,
) : ModelRepository {

    override fun observeByProvider(providerId: Long): Flow<List<AiModel>> =
        dao.observeByProvider(providerId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun add(
        providerId: Long,
        keyId: Long,
        modelId: String,
        protocol: Protocol,
        needsReview: Boolean,
    ): Long {
        val stamp = now()
        return dao.insertIgnoring(
            ModelEntity(
                providerId = providerId,
                keyId = keyId,
                modelId = modelId.trim(),
                protocol = protocol.wireName,
                source = "manual",
                discoveredVia = null,
                enabled = true,
                needsReview = needsReview,
                firstSeenAt = stamp,
                sortOrder = dao.findByProviderAndKey(providerId, keyId).size,
            ),
        )
    }

    override suspend fun applyDiscovered(
        providerId: Long,
        keyId: Long,
        protocol: Protocol,
        modelIds: List<String>,
    ) {
        transactions.inTransaction {
            val fetched = modelIds.map { NewDiscoveredModel(it, protocol) }
            val existingEntities = dao.findByProviderAndKey(providerId, keyId)
            val existing = existingEntities.map { it.toDomain() }
            val plan = ModelMerger.merge(existing, fetched, protocol)
            val stamp = now()
            val baseOrder = existingEntities.size
            plan.toInsert.forEachIndexed { index, model ->
                dao.insertIgnoring(
                    ModelEntity(
                        providerId = providerId,
                        keyId = keyId,
                        modelId = model.modelId,
                        protocol = protocol.wireName,
                        source = "discovered",
                        discoveredVia = protocol.wireName,
                        enabled = true,
                        firstSeenAt = stamp,
                        lastSeenAt = stamp,
                        sortOrder = baseOrder + index,
                    ),
                )
            }
            plan.toTouch.forEach { dao.touchLastSeen(it, stamp) }
            plan.toDisable.forEach { dao.setEnabled(it, false) }
        }
    }

    override suspend fun applyProbeResult(
        id: Long,
        state: String,
        lastOutcome: String,
        detail: String?,
        latencyMs: Long?,
        probedAt: Long,
    ) = dao.applyProbeResult(
        id = id,
        probeState = state,
        lastOutcome = lastOutcome,
        detail = detail,
        latencyMs = latencyMs,
        probedAt = probedAt,
    )

    override suspend fun applyTransientOutcome(
        id: Long,
        lastOutcome: String,
        detail: String?,
        probedAt: Long,
    ) = dao.applyTransientOutcome(
        id = id,
        lastOutcome = lastOutcome,
        detail = detail,
        probedAt = probedAt,
    )

    override suspend fun update(model: AiModel) {
        dao.update(model.toEntity())
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun clearByProvider(providerId: Long) = dao.deleteByProvider(providerId)
}