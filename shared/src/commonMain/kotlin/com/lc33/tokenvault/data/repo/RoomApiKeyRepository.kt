package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.toUtf8
import com.lc33.tokenvault.crypto.utf8Chars
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.dao.KeySettingsDao
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.data.mapper.toEntity
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.DuplicateApiKeyException
import com.lc33.tokenvault.domain.repo.TransactionRunner
import com.lc33.tokenvault.domain.repo.UndoableDeletion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomApiKeyRepository constructor(
    private val dao: ApiKeyDao,
    private val settingsDao: KeySettingsDao,
    private val cipher: FieldCipher,
    private val transactions: TransactionRunner,
    private val now: () -> Long,
    private val audit: AuditLogRepository? = null,
    private val restorer: UndoRestorer? = null,
) : ApiKeyRepository {
    override fun observeByProvider(providerId: Long): Flow<List<ApiKey>> =
        dao.observeByProvider(providerId).map { rows -> rows.map { it.toDomain() } }

    override fun observeAll(): Flow<List<ApiKey>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun find(id: Long): ApiKey? = dao.findById(id)?.toDomain()

    override suspend fun fingerprintOf(secret: CharArray): String {
        val bytes = secret.toUtf8()
        return try {
            cipher.fingerprint(bytes)
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun existsFingerprint(providerId: Long, fingerprint: String): Boolean =
        dao.countFingerprint(providerId, fingerprint) > 0

    override suspend fun add(
        providerId: Long,
        label: String,
        note: String,
        secret: CharArray,
        settings: KeySettings,
        balanceToken: CharArray?,
    ): Long {
        val bytes = secret.toUtf8()
        return try {
            val fingerprint = cipher.fingerprint(bytes)
            // 指纹预检（与导入预览用的是同一个口径）：`api_keys(providerId, fingerprint)` 是
            // 唯一索引，撞上了 Room 只会抛 `UNIQUE constraint failed`，用户读不懂、
            // 日志也指不出是哪一家。这里先查一次，把它变成领域错误。
            dao.findIdByFingerprint(providerId, fingerprint)?.let { clash ->
                throw DuplicateApiKeyException(providerId, clash)
            }
            val stamp = now()
            transactions.inTransaction {
                val keyId = dao.insertRaw(
                    ApiKeyEntity(
                        providerId = providerId,
                        label = label.trim(),
                        note = note.trim(),
                        secretEnc = ByteArray(0),
                        fingerprint = fingerprint,
                        // 排到最后只要一个数：把这家全部 Key（含密文行）读回来数一遍，
                        // 是在为一件 O(1) 的事付 O(n) 的内存与拷贝。
                        sortOrder = dao.countByProvider(providerId),
                        createdAt = stamp,
                        updatedAt = stamp,
                    ),
                )
                dao.setSecret(keyId, cipher.seal(bytes, aadForSecret(keyId)), fingerprint, stamp)
                settingsDao.insert(
                    settings.toEntity(keyId, stamp).copy(
                        balanceTokenEnc = sealBalanceToken(keyId, balanceToken),
                    ),
                )
                keyId
            }.also { keyId ->
                audit.recordSafe(
                    LogLevel.INFO,
                    LogCategory.VAULT,
                    "api key added",
                    "label=${label.trim()}",
                    providerId = providerId,
                    keyId = keyId,
                )
            }
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun replaceSecret(id: Long, secret: CharArray) {
        val row = dao.findRaw(id) ?: throw IllegalStateException("api key $id not found")
        val bytes = secret.toUtf8()
        try {
            val fingerprint = cipher.fingerprint(bytes)
            // 同一家里不能有第二把同样的密钥；换成它自己不算冲突（那是幂等操作）。
            val clash = dao.findIdByFingerprint(row.providerId, fingerprint)
            if (clash != null && clash != id) throw DuplicateApiKeyException(row.providerId, clash)
            dao.setSecret(id, cipher.seal(bytes, aadForSecret(id)), fingerprint, now())
            audit.recordSafe(LogLevel.INFO, LogCategory.VAULT, "api key secret replaced", "id=$id", keyId = id)
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun updateMeta(key: ApiKey) {
        dao.updateMeta(key.id, key.label.trim(), key.note.trim(), key.sortOrder, now())
        audit.recordSafe(LogLevel.INFO, LogCategory.VAULT, "api key metadata updated", "id=${key.id} label=${key.label.trim()}", providerId = key.providerId, keyId = key.id)
    }

    override suspend fun updateSettings(
        id: Long,
        settings: KeySettings,
        balanceToken: CharArray?,
    ) {
        val stamp = now()
        val existing = settingsDao.findByKey(id)
        val token = when {
            balanceToken == null -> existing?.balanceTokenEnc
            else -> sealBalanceToken(id, balanceToken)
        }
        settingsDao.insert(settings.toEntity(id, stamp).copy(balanceTokenEnc = token))
        audit.recordSafe(LogLevel.INFO, LogCategory.VAULT, "api key settings updated", "id=$id", keyId = id)
    }

    override suspend fun reveal(id: Long): CharArray {
        val row = requireNotNull(dao.findRaw(id)) { "api key $id not found" }
        val plain = cipher.open(row.secretEnc, aadForSecret(id))
        return try {
            plain.utf8Chars()
        } finally {
            plain.zeroize()
        }
    }

    override suspend fun revealBalanceToken(id: Long): CharArray? {
        val enc = settingsDao.findByKey(id)?.balanceTokenEnc ?: return null
        val plain = cipher.open(enc, aadForBalanceToken(id))
        return try {
            plain.utf8Chars()
        } finally {
            plain.zeroize()
        }
    }

    override suspend fun delete(id: Long): UndoableDeletion? {
        val undo = restorer?.deleteKey(id)
        if (restorer == null) dao.delete(id)
        audit.recordSafe(LogLevel.WARN, LogCategory.VAULT, "api key deleted", "id=$id", keyId = id)
        return undo
    }

    override suspend fun reorder(providerId: Long, idsInOrder: List<Long>) {
        dao.reorder(providerId, idsInOrder, now())
        audit.recordSafe(LogLevel.INFO, LogCategory.VAULT, "api keys reordered", "providerId=$providerId count=${idsInOrder.size}", providerId = providerId)
    }

    override suspend fun applyProbeResult(
        id: Long,
        health: String,
        lastOutcome: String,
        detail: String?,
        httpStatus: Int?,
        latencyMs: Long?,
        checkedAt: Long,
        okAt: Long?,
    ) = dao.applyProbeResult(
        id = id,
        health = health,
        lastOutcome = lastOutcome,
        detail = detail,
        httpStatus = httpStatus,
        latencyMs = latencyMs,
        checkedAt = checkedAt,
        okAt = okAt,
    )

    override suspend fun applyTransientOutcome(
        id: Long,
        lastOutcome: String,
        detail: String?,
        httpStatus: Int?,
        checkedAt: Long,
    ) = dao.applyTransientOutcome(
        id = id,
        lastOutcome = lastOutcome,
        detail = detail,
        httpStatus = httpStatus,
        checkedAt = checkedAt,
    )

    override suspend fun updateBalance(id: Long, snapshot: BalanceSnapshot) =
        dao.updateBalance(
            id = id,
            amount = snapshot.amount,
            used = snapshot.used,
            currency = snapshot.currency,
            raw = snapshot.raw,
            checkedAt = snapshot.checkedAt ?: now(),
            error = snapshot.error,
        )

    /**
     * 清掉全部 Key 的探测结果。
     *
     * 这是一条**全表 UPDATE**，把用户看到的健康状态一起抹平（数据页的「清空探测结果」）。
     * 与删除同类，所以留一条 WARN：事后问"我的状态怎么全没了"，日志要答得上来是哪一步、
     * 谁动的。明细里不含任何密钥内容。
     */
    override suspend fun resetProbeResults() {
        dao.resetProbeResults()
        audit.recordSafe(
            LogLevel.WARN,
            LogCategory.VAULT,
            "api key probe results reset",
            "scope=all",
        )
    }

    private fun sealBalanceToken(keyId: Long, plain: CharArray?): ByteArray? {
        if (plain == null || plain.isEmpty()) return null
        val bytes = plain.toUtf8()
        return try {
            cipher.seal(bytes, aadForBalanceToken(keyId))
        } finally {
            bytes.zeroize()
        }
    }

    private fun aadForSecret(id: Long) = FieldAad.of(TABLE_KEYS, id, COLUMN_SECRET)
    private fun aadForBalanceToken(id: Long) = FieldAad.of(TABLE_SETTINGS, id, COLUMN_BALANCE_TOKEN)

    private companion object {
        const val TABLE_KEYS = "api_keys"
        const val TABLE_SETTINGS = "key_settings"
        const val COLUMN_SECRET = "secretEnc"
        const val COLUMN_BALANCE_TOKEN = "balanceTokenEnc"
    }
}
