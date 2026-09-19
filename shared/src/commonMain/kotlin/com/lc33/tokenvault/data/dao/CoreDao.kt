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

    /** 现有分组数：新增时的 `sortOrder`，只要一个数。 */
    @Query("SELECT COUNT(*) FROM groups")
    suspend fun count(): Int

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

    /**
     * 重排。[setSortOrder] 返回受影响行数，**0 行就是那一行已经不在了**（用户在别处删了它，
     * 或者传进来的是过期 id）：静默跳过会让列表按一份不存在的顺序排好，界面下次刷新时
     * 顺序又自己变了，用户只会看到"拖了没反应"。
     */
    @Transaction
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { index, id ->
            check(setSortOrder(id, index) > 0) { "group $id not found while reordering" }
        }
    }

    @Query("UPDATE groups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int): Int
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
    /**
     * 列表页每行的聚合列。
     *
     * `okKeyCount` 要把**关掉密钥有效性检测**的 Key 也算成可用：那是用户说了"别判断它"，
     * 展示层一律按可用显示（`ApiKey.effectiveHealth`），这个计数必须跟着一起算，
     * 否则会出现列表写"0 / 1 张可用"、而那一行的状态点是绿的。
     */
    @Query(
        """
        SELECT p.*,
               (SELECT COUNT(*) FROM api_keys k WHERE k.providerId = p.id) AS keyCount,
               (SELECT COUNT(*) FROM api_keys k
                  LEFT JOIN key_settings s ON s.keyId = k.id
                 WHERE k.providerId = p.id
                   AND (k.health = 'ok' OR s.probeKeyValidity = 0)) AS okKeyCount,
               (SELECT COUNT(DISTINCT m.modelId) FROM models m WHERE m.providerId = p.id) AS modelCount,
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

    /** 现有供应商数：新增时的 `sortOrder`，只要一个数，不把整表读回来。 */
    @Query("SELECT COUNT(*) FROM providers")
    suspend fun count(): Int

    @Query("UPDATE providers SET sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int, now: Long): Int

    /** 0 行 = 这一行已经不在（过期 id / 别处删了），上抛而不是悄悄少排一家。 */
    @Transaction
    suspend fun reorder(idsInOrder: List<Long>, now: Long) {
        idsInOrder.forEachIndexed { index, id ->
            check(setSortOrder(id, index, now) > 0) { "provider $id not found while reordering" }
        }
    }
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

    @Query("SELECT COUNT(*) FROM api_keys WHERE providerId = :providerId AND fingerprint = :fingerprint")
    suspend fun countFingerprint(providerId: Long, fingerprint: String): Int

    /** 冲突预检用：同一把 Key 在本机已有时，把那一行的 id 带回去，让上层能说"和哪一把重复"。 */
    @Query("SELECT id FROM api_keys WHERE providerId = :providerId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun findIdByFingerprint(providerId: Long, fingerprint: String): Long?

    /** 这一家已有几把 Key：新增时的 `sortOrder`，只 COUNT，不把整家读回来再数。 */
    @Query("SELECT COUNT(*) FROM api_keys WHERE providerId = :providerId")
    suspend fun countByProvider(providerId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRaw(key: ApiKeyEntity): Long

    @Query("UPDATE api_keys SET secretEnc = :secretEnc, fingerprint = :fingerprint, updatedAt = :now WHERE id = :id")
    suspend fun setSecret(id: Long, secretEnc: ByteArray, fingerprint: String, now: Long)

    @Query("UPDATE api_keys SET label = :label, note = :note, sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    suspend fun updateMeta(id: Long, label: String, note: String, sortOrder: Int, now: Long)

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
            // 带上 providerId 条件：别的家的同 id 密钥不可能被排到这家来，0 行就是真出问题了。
            check(setSortOrder(providerId = providerId, id = id, sortOrder = index, now = now) > 0) {
                "api key $id not in provider $providerId while reordering"
            }
        }
    }

    @Query("UPDATE api_keys SET sortOrder = :sortOrder, updatedAt = :now WHERE id = :id AND providerId = :providerId")
    suspend fun setSortOrder(providerId: Long, id: Long, sortOrder: Int, now: Long): Int
}

@Dao
interface KeySettingsDao {
    /**
     * 读某一行的行为配置。
     *
     * 只有同步版：Key 的读路径全部走 `ApiKeyDao` 的 `@Transaction` 查询（Key + 配置一起出来），
     * 单独再开一条 Flow 会把同一份配置读两遍、还各推各的变更。
     */
    @Query("SELECT * FROM key_settings WHERE keyId = :keyId")
    suspend fun findByKey(keyId: Long): KeySettingsEntity?

    @Query("SELECT * FROM key_settings ORDER BY keyId")
    suspend fun findAll(): List<KeySettingsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: KeySettingsEntity)

    @Update
    suspend fun update(settings: KeySettingsEntity)

    /**
     * 额度换算系数校准（余额页）。
     *
     * 只动这两列：整行 `@Update` 要先把 `balanceTokenEnc` 读出来再写回去，
     * 而校准这件事不需要知道令牌。
     */
    @Query("UPDATE key_settings SET quotaPerUnit = :quotaPerUnit, quotaCalibrated = 1, updatedAt = :now WHERE keyId = :keyId")
    suspend fun calibrateQuotaPerUnit(keyId: Long, quotaPerUnit: Double, now: Long)

    @Query("DELETE FROM key_settings WHERE keyId = :keyId")
    suspend fun delete(keyId: Long)
}
