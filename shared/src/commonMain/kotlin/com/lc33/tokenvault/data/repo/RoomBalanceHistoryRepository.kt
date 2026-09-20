package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.balance.shouldRecordSample
import com.lc33.tokenvault.data.dao.BalanceHistoryDao
import com.lc33.tokenvault.data.entity.BalanceHistoryEntity
import com.lc33.tokenvault.domain.model.BalanceSample
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.repo.BalanceHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 余额历史仓库的 Room 实现。
 *
 * 去重的判断（[shouldRecordSample]）是纯函数、单独测；这里只做「读上一条、问要不要写、
 * 写」这套 I/O 编排，不把业务规则藏在 SQL 或本类里。
 */
class RoomBalanceHistoryRepository constructor(
    private val dao: BalanceHistoryDao,
    private val now: () -> Long,
) : BalanceHistoryRepository {

    override fun observeAll(): Flow<List<BalanceSample>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun record(providerId: Long, keyId: Long, snapshot: BalanceSnapshot) {
        val previous = dao.latestForKey(keyId)?.toDomain()
        if (!shouldRecordSample(previous, snapshot.amount, snapshot.used, snapshot.currency)) return
        dao.insert(
            BalanceHistoryEntity(
                providerId = providerId,
                keyId = keyId,
                amount = snapshot.amount,
                used = snapshot.used,
                currency = snapshot.currency,
                capturedAt = snapshot.checkedAt ?: now(),
            ),
        )
    }

    override suspend fun trimOlderThan(cutoff: Long) = dao.trimOlderThan(cutoff)

    private fun BalanceHistoryEntity.toDomain() = BalanceSample(
        providerId = providerId,
        keyId = keyId,
        amount = amount,
        used = used,
        currency = currency,
        capturedAt = capturedAt,
    )
}
