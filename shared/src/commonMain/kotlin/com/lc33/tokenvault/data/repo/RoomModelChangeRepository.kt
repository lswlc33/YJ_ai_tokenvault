package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.ModelChangeDao
import com.lc33.tokenvault.data.entity.ModelChangeEntity
import com.lc33.tokenvault.domain.ModelChangeKind
import com.lc33.tokenvault.domain.model.ModelChange
import com.lc33.tokenvault.domain.repo.ModelChangeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 模型上下架流水的 Room 实现。
 *
 * 这个类里没有任何判断规则：一条事件算不算"站点上新"（本轮还在的跨协议重建行不算、
 * 一张 Key 的首轮整批抓取不算）都在写它的 `RoomModelRepository.applyDiscovered` 里决定，
 * 那里同时看得到合并计划与本轮拉到的列表。这里只负责「翻成整行、批量写、按上限裁」。
 *
 * [record] 不自开事务，见接口注释——它是被调用方的事务包住的那一段写。
 */
class RoomModelChangeRepository constructor(
    private val dao: ModelChangeDao,
) : ModelChangeRepository {

    override fun observeAll(): Flow<List<ModelChange>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun record(changes: List<ModelChange>) {
        if (changes.isEmpty()) return
        dao.insertAll(changes.map { it.toEntity() })
    }

    override suspend fun trimOlderThan(cutoff: Long) = dao.trimOlderThan(cutoff)

    override suspend fun trimToCount(max: Int) = dao.trimToCount(max)

    private fun ModelChangeEntity.toDomain() = ModelChange(
        id = id,
        providerId = providerId,
        keyId = keyId,
        modelId = modelId,
        protocol = protocol,
        kind = ModelChangeKind.fromWireName(kind),
        at = at,
    )

    private fun ModelChange.toEntity() = ModelChangeEntity(
        id = id,
        providerId = providerId,
        keyId = keyId,
        modelId = modelId,
        protocol = protocol,
        kind = kind.wireName,
        at = at,
    )
}
