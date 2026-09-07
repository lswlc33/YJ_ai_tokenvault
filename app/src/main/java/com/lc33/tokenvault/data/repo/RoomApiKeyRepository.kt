package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.toUtf8
import com.lc33.tokenvault.crypto.utf8Chars
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * API 密钥。
 *
 * 明文在这个类里活得尽可能短：转成 UTF-8 字节 → 算指纹 → 加密 → **立刻擦掉那份字节**。
 * 入参那份 `CharArray` 不擦（生命周期归调用方，和 `Pbkdf2Kdf.derive` 同一个约定）。
 *
 * `secretEnc` 的 AAD 是 `api_keys:{id}:secretEnc`（红线 24），而 id 是插入时才分配的，
 * 所以 [add] 必须**插入 → 加密 → 回填**三步走，并且包在一个事务里：
 * 中间那一瞬间密文列是空的，事务保证没人看得见，也保证崩溃后库里不留半成品。
 */
class RoomApiKeyRepository constructor(
    private val dao: ApiKeyDao,
    private val cipher: FieldCipher,
    private val transactions: TransactionRunner,
    // @param: 是显式声明"这个限定符注在构造参数上"。Kotlin 2.3 起不写会告警，
    // 因为将来默认会同时注到属性上，而属性上的限定符对 Dagger 没有意义
    private val now: () -> Long,
) : ApiKeyRepository {

    override fun observeByProvider(providerId: Long): Flow<List<ApiKey>> =
        dao.observeByProvider(providerId).map { rows -> rows.map { it.toDomain() } }

    override fun observeAll(): Flow<List<ApiKey>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun find(id: Long): ApiKey? = dao.findById(id)?.toDomain()

    override suspend fun add(providerId: Long, label: String, secret: CharArray): Long {
        val bytes = secret.toUtf8()
        return try {
            // 指纹不依赖 id，所以插入前就能算好；`(providerId, fingerprint)` 唯一索引
            // 因此在插入那一刻就能挡住重复录入，而不是等回填时才发现
            val fingerprint = cipher.fingerprint(bytes)
            val stamp = now()
            transactions.inTransaction {
                val id = dao.insert(
                    ApiKeyEntity(
                        providerId = providerId,
                        label = label.trim(),
                        secretEnc = ByteArray(0),
                        fingerprint = fingerprint,
                        sortOrder = dao.findByProvider(providerId).size,
                        createdAt = stamp,
                        updatedAt = stamp,
                    ),
                    stamp,
                )
                dao.setSecret(id, cipher.seal(bytes, aadFor(id)), fingerprint, stamp)
                id
            }
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun replaceSecret(id: Long, secret: CharArray) {
        val bytes = secret.toUtf8()
        try {
            // id 已知，所以这条路只有一步；指纹跟着重算，否则去重会拿旧值比
            dao.setSecret(id, cipher.seal(bytes, aadFor(id)), cipher.fingerprint(bytes), now())
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun updateMeta(key: ApiKey) =
        dao.setMeta(key.id, key.label.trim(), key.sortOrder, now())

    override suspend fun reveal(id: Long): CharArray {
        val row = requireNotNull(dao.findById(id)) { "api key $id not found" }
        val plain = cipher.open(row.secretEnc, aadFor(id))
        return try {
            plain.utf8Chars()
        } finally {
            // 中间那份字节擦掉；返回的 CharArray 归调用方擦（红线 1）
            plain.zeroize()
        }
    }

    override suspend fun setDefault(providerId: Long, keyId: Long) =
        dao.setDefault(providerId, keyId, now())

    override suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled, now())

    override suspend fun delete(id: Long) = dao.delete(id, now())

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

    private fun aadFor(id: Long) = FieldAad.of(TABLE, id, COLUMN_SECRET)

    private companion object {
        const val TABLE = "api_keys"
        const val COLUMN_SECRET = "secretEnc"
    }
}
