package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.domain.repo.TransactionRunner

import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.toUtf8
import com.lc33.tokenvault.crypto.utf8Chars
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.data.mapper.toEntity
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.domain.repo.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 供应商。
 *
 * 除了 `balanceTokenEnc` 那一列，这张表全是明文（见 CLAUDE.md 的加密边界表），
 * 所以读路径完全不碰 DEK——列表页在锁定瞬间也不会从 `Flow` 内部抛异常（§6.1 推论 3）。
 */
class RoomProviderRepository constructor(
    private val dao: ProviderDao,
    private val cipher: FieldCipher,
    private val transactions: TransactionRunner,
    private val now: () -> Long,
) : ProviderRepository {

    override fun observeSummaries(): Flow<List<ProviderSummary>> =
        dao.observeSummaries().map { rows -> rows.map { it.toDomain() } }

    override fun observeProvider(id: Long): Flow<Provider?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun find(id: Long): Provider? = dao.findById(id)?.toDomain()

    /**
     * 新增或保存。
     *
     * 令牌那一列有三种意图（见接口 KDoc），这里按顺序处理：
     *
     * - 新增时先插入、拿到 id、再用 `providers:{id}:balanceTokenEnc` 加密回填。
     *   和密钥表同一个理由：AAD 要绑主键（红线 24），而主键是插入时才分配的。
     * - 保存时 `balanceToken == null` 表示"别动这一列"，于是从库里读回原密文写回去。
     *   **不能信调用方传来的 `provider.balanceTokenEnc`**：编辑页构造领域对象时手上没有
     *   那份密文（它压根没读过），传下来是 null，照抄就等于每次改备注都把令牌清掉。
     */
    override suspend fun save(provider: Provider, balanceToken: CharArray?): Long =
        transactions.inTransaction {
            val stamp = now()
            if (provider.id == 0L) {
                val id = dao.insert(
                    provider.toEntity().copy(
                        balanceTokenEnc = null,
                        createdAt = stamp,
                        updatedAt = stamp,
                    ),
                )
                sealToken(id, balanceToken)?.let { dao.setBalanceToken(id, it, stamp) }
                id
            } else {
                val existing = dao.findById(provider.id)
                val token = when {
                    // 没碰过那个输入框：保留库里那份密文
                    balanceToken == null -> existing?.balanceTokenEnc
                    else -> sealToken(provider.id, balanceToken)
                }
                dao.update(
                    provider.toEntity().copy(
                        balanceTokenEnc = token,
                        createdAt = existing?.createdAt ?: stamp,
                        updatedAt = stamp,
                    ),
                )
                provider.id
            }
        }

    /** 空数组 = 用户要清掉令牌，所以返回 null 而不是"一段空明文的密文"。 */
    private fun sealToken(providerId: Long, plain: CharArray?): ByteArray? {
        if (plain == null || plain.isEmpty()) return null
        val bytes = plain.toUtf8()
        return try {
            cipher.seal(bytes, FieldAad.of(TABLE, providerId, COLUMN_BALANCE_TOKEN))
        } finally {
            // 只擦自己造的那份字节；调用方那份 CharArray 归调用方擦
            bytes.zeroize()
        }
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun setGroup(ids: List<Long>, groupId: Long?) = dao.setGroup(ids, groupId, now())

    override suspend fun revealBalanceToken(id: Long): CharArray? {
        val enc = dao.findById(id)?.balanceTokenEnc ?: return null
        val bytes = cipher.open(enc, FieldAad.of(TABLE, id, COLUMN_BALANCE_TOKEN))
        return try {
            bytes.utf8Chars()
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun updateBalance(id: Long, snapshot: BalanceSnapshot, calibrated: Boolean) =
        dao.updateBalance(
            id = id,
            amount = snapshot.amount,
            used = snapshot.used,
            currency = snapshot.currency,
            raw = snapshot.raw,
            checkedAt = snapshot.checkedAt ?: now(),
            error = snapshot.error,
            calibrated = calibrated,
        )

    override suspend fun calibrateQuotaPerUnit(id: Long, quotaPerUnit: Double) =
        dao.calibrateQuotaPerUnit(id, quotaPerUnit)

    private companion object {
        const val TABLE = "providers"
        const val COLUMN_BALANCE_TOKEN = "balanceTokenEnc"
    }
}
