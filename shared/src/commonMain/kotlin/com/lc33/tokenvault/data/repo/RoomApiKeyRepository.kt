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
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomApiKeyRepository constructor(
    private val dao: ApiKeyDao,
    private val settingsDao: KeySettingsDao,
    private val cipher: FieldCipher,
    private val transactions: TransactionRunner,
    private val now: () -> Long,
) : ApiKeyRepository {
    override fun observeByProvider(providerId: Long): Flow<List<ApiKey>> =
        dao.observeByProvider(providerId).map { rows -> rows.map { it.toDomain() } }

    override fun observeAll(): Flow<List<ApiKey>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun find(id: Long): ApiKey? = dao.findById(id)?.toDomain()

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
            val stamp = now()
            transactions.inTransaction {
                val keyId = dao.insertRaw(
                    ApiKeyEntity(
                        providerId = providerId,
                        label = label.trim(),
                        note = note.trim(),
                        secretEnc = ByteArray(0),
                        fingerprint = fingerprint,
                        sortOrder = dao.findByProvider(providerId).size,
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
            }
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun replaceSecret(id: Long, secret: CharArray) {
        val bytes = secret.toUtf8()
        try {
            dao.setSecret(id, cipher.seal(bytes, aadForSecret(id)), cipher.fingerprint(bytes), now())
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun updateMeta(key: ApiKey) =
        dao.updateMeta(key.id, key.label.trim(), key.note.trim(), key.sortOrder, now())

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

    override suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled, now())

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun reorder(providerId: Long, idsInOrder: List<Long>) =
        dao.reorder(providerId, idsInOrder, now())

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

    override suspend fun resetProbeResults() = dao.resetProbeResults()

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
