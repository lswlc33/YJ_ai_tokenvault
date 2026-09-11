package com.lc33.tokenvault.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.KeySettingsEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY sortOrder, id")
    suspend fun findAll(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun findById(id: Long): GroupEntity?

    @Insert
    suspend fun insert(group: GroupEntity): Long

    @Update
    suspend fun update(group: GroupEntity)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM groups")
    suspend fun clear()

    @Transaction
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Query("UPDATE groups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)
}

data class ProviderSummaryRow(
    @Embedded val provider: ProviderEntity,
    val keyCount: Int,
    val okKeyCount: Int,
    val modelCount: Int,
    val accountCount: Int,
)

@Dao
interface ProviderDao {
    @Query(
        """
        SELECT p.*,
               (SELECT COUNT(*) FROM api_keys k WHERE k.providerId = p.id AND k.enabled = 1) AS keyCount,
               (SELECT COUNT(*) FROM api_keys k WHERE k.providerId = p.id AND k.enabled = 1 AND k.health = 'ok') AS okKeyCount,
               (SELECT COUNT(DISTINCT m.modelId) FROM models m WHERE m.providerId = p.id AND m.enabled = 1) AS modelCount,
               (SELECT COUNT(*) FROM provider_accounts a WHERE a.providerId = p.id) AS accountCount
        FROM providers p
        ORDER BY p.pinned DESC, p.sortOrder, p.id
        """,
    )
    fun observeSummaries(): Flow<List<ProviderSummaryRow>>

    @Query("SELECT * FROM providers WHERE id = :id")
    fun observeById(id: Long): Flow<ProviderEntity?>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun findById(id: Long): ProviderEntity?

    @Query("SELECT * FROM providers ORDER BY pinned DESC, sortOrder, id")
    suspend fun findAll(): List<ProviderEntity>

    @Insert
    suspend fun insert(provider: ProviderEntity): Long

    @Update
    suspend fun update(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM providers")
    suspend fun clear()

    @Query("UPDATE providers SET groupId = :groupId, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setGroup(ids: List<Long>, groupId: Long?, now: Long)

    @Query(
        """
        UPDATE providers SET
            websiteLatencyMs = :latencyMs,
            websiteCheckedAt = :checkedAt,
            websiteError = :error,
            updatedAt = :checkedAt
        WHERE id = :id
        """,
    )
    suspend fun updateWebsiteStatus(
        id: Long,
        latencyMs: Long?,
        checkedAt: Long,
        error: String?,
    )
}

data class ApiKeyWithSettingsRow(
    @Embedded val key: ApiKeyEntity,
    @Relation(parentColumn = "id", entityColumn = "keyId")
    val settings: KeySettingsEntity?,
)

@Dao
interface ApiKeyDao {
    @Transaction
    @Query("SELECT * FROM api_keys WHERE providerId = :providerId ORDER BY sortOrder, id")
    fun observeByProvider(providerId: Long): Flow<List<ApiKeyWithSettingsRow>>

    @Transaction
    @Query("SELECT * FROM api_keys ORDER BY providerId, sortOrder, id")
    fun observeAll(): Flow<List<ApiKeyWithSettingsRow>>

    @Transaction
    @Query("SELECT * FROM api_keys ORDER BY providerId, sortOrder, id")
    suspend fun findAll(): List<ApiKeyWithSettingsRow>

    @Transaction
    @Query("SELECT * FROM api_keys WHERE providerId = :providerId ORDER BY sortOrder, id")
    suspend fun findByProvider(providerId: Long): List<ApiKeyWithSettingsRow>

    @Transaction
    @Query("SELECT * FROM api_keys WHERE id = :id")
    suspend fun findById(id: Long): ApiKeyWithSettingsRow?

    @Query("SELECT * FROM api_keys WHERE id = :id")
    suspend fun findRaw(id: Long): ApiKeyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRaw(key: ApiKeyEntity): Long

    @Update
    suspend fun update(key: ApiKeyEntity)

    @Query("UPDATE api_keys SET secretEnc = :secretEnc, fingerprint = :fingerprint, updatedAt = :now WHERE id = :id")
    suspend fun setSecret(id: Long, secretEnc: ByteArray, fingerprint: String, now: Long)

    @Query("UPDATE api_keys SET label = :label, note = :note, sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    suspend fun updateMeta(id: Long, label: String, note: String, sortOrder: Int, now: Long)

    @Query("UPDATE api_keys SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    @Query("DELETE FROM api_keys WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        UPDATE api_keys SET
            balanceAmount = :amount, balanceUsed = :used, balanceCurrency = :currency,
            balanceRaw = :raw, balanceCheckedAt = :checkedAt, balanceError = :error,
            updatedAt = :checkedAt
        WHERE id = :id
        """,
    )
    suspend fun updateBalance(
        id: Long,
        amount: Double?,
        used: Double?,
        currency: String?,
        raw: String?,
        checkedAt: Long,
        error: String?,
    )

    @Query(
        """
        UPDATE api_keys SET
            health = :health, lastOutcome = :lastOutcome, healthDetail = :detail,
            httpStatus = :httpStatus, latencyMs = :latencyMs, checkedAt = :checkedAt,
            okAt = :okAt, updatedAt = :checkedAt
        WHERE id = :id
        """,
    )
    suspend fun applyProbeResult(
        id: Long,
        health: String,
        lastOutcome: String,
        detail: String?,
        httpStatus: Int?,
        latencyMs: Long?,
        checkedAt: Long,
        okAt: Long?,
    )

    @Query(
        """
        UPDATE api_keys SET
            lastOutcome = :lastOutcome, healthDetail = :detail, httpStatus = :httpStatus,
            checkedAt = :checkedAt, updatedAt = :checkedAt
        WHERE id = :id
        """,
    )
    suspend fun applyTransientOutcome(
        id: Long,
        lastOutcome: String,
        detail: String?,
        httpStatus: Int?,
        checkedAt: Long,
    )

    @Query(
        """
        UPDATE api_keys SET
            health = 'unknown', lastOutcome = 'skipped', healthDetail = NULL,
            httpStatus = NULL, latencyMs = NULL, checkedAt = NULL, okAt = NULL
        """,
    )
    suspend fun resetProbeResults()

    @Transaction
    suspend fun reorder(providerId: Long, idsInOrder: List<Long>, now: Long) {
        idsInOrder.forEachIndexed { index, id ->
            setSortOrder(providerId = providerId, id = id, sortOrder = index, now = now)
        }
    }

    @Query("UPDATE api_keys SET sortOrder = :sortOrder, updatedAt = :now WHERE id = :id AND providerId = :providerId")
    suspend fun setSortOrder(providerId: Long, id: Long, sortOrder: Int, now: Long)
}

@Dao
interface KeySettingsDao {
    @Query("SELECT * FROM key_settings WHERE keyId = :keyId")
    fun observeByKey(keyId: Long): Flow<KeySettingsEntity?>

    @Query("SELECT * FROM key_settings WHERE keyId = :keyId")
    suspend fun findByKey(keyId: Long): KeySettingsEntity?

    @Query("SELECT * FROM key_settings ORDER BY keyId")
    suspend fun findAll(): List<KeySettingsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: KeySettingsEntity)

    @Update
    suspend fun update(settings: KeySettingsEntity)

    @Query("UPDATE key_settings SET balanceTokenEnc = :token, updatedAt = :now WHERE keyId = :keyId")
    suspend fun setBalanceToken(keyId: Long, token: ByteArray?, now: Long)

    @Query("UPDATE key_settings SET quotaPerUnit = :quotaPerUnit, quotaCalibrated = 1, updatedAt = :now WHERE keyId = :keyId")
    suspend fun calibrateQuotaPerUnit(keyId: Long, quotaPerUnit: Double, now: Long)

    @Query("DELETE FROM key_settings WHERE keyId = :keyId")
    suspend fun delete(keyId: Long)
}
