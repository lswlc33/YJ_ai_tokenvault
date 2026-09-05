package com.lc33.tokenvault.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.domain.DefaultKeyPolicy
import kotlinx.coroutines.flow.Flow

/**
 * DAO 的两条统一规矩（§6.3）：
 * - **查询一律返回 `Flow`**，所以列表页与详情页天然响应式，不需要"写完手动通知刷新"（红线 10）。
 * - 写操作是 `suspend`，多表写用 `@Transaction`。
 *
 * DAO **不解密**（§6.1 推论 3）：`Flow<List<Entity>>` 只搬密文 `ByteArray`。
 * 在这里解密的后果是列表页在锁定瞬间会在 Flow 内部抛异常，把整条订阅打断。
 */
@Dao
interface GroupDao {

    @Query("SELECT * FROM groups ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<GroupEntity>>

    @Insert
    suspend fun insert(group: GroupEntity): Long

    @Update
    suspend fun update(group: GroupEntity)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: Long)

    /** 拖拽排序。一个事务里把整段顺序写完，避免中间态被 Flow 观察到。 */
    @Transaction
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Query("UPDATE groups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)
}

/** 列表页那条聚合查询的投影（§6.3）。首页只要计数与余额，不该为此拉全量模型。 */
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
     * 列表页用的聚合查询。
     *
     * 计数走子查询而不是 `@Relation`：`@Relation` 会把每个供应商的全部 Key 与 Model 都拉进
     * 内存，而列表页只要四个数字。一家 50 个模型、十家就是 500 行白搬。
     */
    @Query(
        """
        SELECT p.*,
               (SELECT COUNT(*) FROM api_keys k WHERE k.providerId = p.id AND k.enabled = 1) AS keyCount,
               (SELECT COUNT(*) FROM api_keys k WHERE k.providerId = p.id AND k.enabled = 1 AND k.health = 'ok') AS okKeyCount,
               (SELECT COUNT(*) FROM models m WHERE m.providerId = p.id AND m.enabled = 1) AS modelCount,
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

    /** 删供应商连带删 Key、账号、模型——靠外键 CASCADE，但要有测试断言它真的生效（§6.3）。 */
    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE providers SET groupId = :groupId, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setGroup(ids: List<Long>, groupId: Long?, now: Long)

    @Query(
        """
        UPDATE providers SET
            balanceAmount = :amount, balanceUsed = :used, balanceCurrency = :currency,
            balanceRaw = :raw, balanceCheckedAt = :checkedAt, balanceError = :error,
            quotaCalibrated = :calibrated, updatedAt = :checkedAt
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
        calibrated: Boolean,
    )

    /** `quotaPerUnit` 被 `/api/status` 校准之后单独写，这样校准失败时不会把余额一起清掉。 */
    @Query("UPDATE providers SET quotaPerUnit = :quotaPerUnit, quotaCalibrated = 1 WHERE id = :id")
    suspend fun calibrateQuotaPerUnit(id: Long, quotaPerUnit: Double)
}

@Dao
interface ApiKeyDao {

    @Query("SELECT * FROM api_keys WHERE providerId = :providerId ORDER BY sortOrder, id")
    fun observeByProvider(providerId: Long): Flow<List<ApiKeyEntity>>

    @Query("SELECT * FROM api_keys ORDER BY providerId, sortOrder, id")
    fun observeAll(): Flow<List<ApiKeyEntity>>

    @Query("SELECT * FROM api_keys WHERE providerId = :providerId ORDER BY sortOrder, id")
    suspend fun findByProvider(providerId: Long): List<ApiKeyEntity>

    @Query("SELECT * FROM api_keys WHERE id = :id")
    suspend fun findById(id: Long): ApiKeyEntity?

    /** 默认那张。四个余额适配器复用它，所以"至少有一张"这件事很要紧（§6.3）。 */
    @Query("SELECT * FROM api_keys WHERE providerId = :providerId AND isDefault = 1 AND enabled = 1")
    suspend fun findDefault(providerId: Long): ApiKeyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRaw(key: ApiKeyEntity): Long

    @Update
    suspend fun update(key: ApiKeyEntity)

    @Query("DELETE FROM api_keys WHERE id = :id")
    suspend fun deleteRaw(id: Long)

    @Query("UPDATE api_keys SET isDefault = 0 WHERE providerId = :providerId")
    suspend fun clearDefault(providerId: Long)

    @Query("UPDATE api_keys SET isDefault = 1, updatedAt = :now WHERE id = :id")
    suspend fun markDefault(id: Long, now: Long)

    @Query("UPDATE api_keys SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabledRaw(id: Long, enabled: Boolean, now: Long)

    @Query(
        """
        UPDATE api_keys SET
            health = :health, lastOutcome = :lastOutcome, healthDetail = :detail,
            httpStatus = :httpStatus, latencyMs = :latencyMs,
            checkedAt = :checkedAt, okAt = :okAt, updatedAt = :checkedAt
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

    /**
     * **只写瞬时结果**：`lastOutcome` / `checkedAt` / `healthDetail` 变，`health` 与 `okAt` 不动。
     *
     * 这是红线 11 在 SQL 层面的表达——单独一条语句，而不是给 [applyProbeResult] 传
     * "health 保持原值"。传参数的写法迟早有人传错，而这条语句连 health 列都没提到。
     */
    @Query(
        """
        UPDATE api_keys SET
            lastOutcome = :lastOutcome, healthDetail = :detail,
            httpStatus = :httpStatus, checkedAt = :checkedAt, updatedAt = :checkedAt
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

    // ------------------------------------------------------------------ 默认 Key 不变量

    /** 新增。第一张自动设默认（§6.3）。 */
    @Transaction
    suspend fun insert(key: ApiKeyEntity, now: Long): Long {
        val existing = defaultKeyCandidates(key.providerId)
        val newId = insertRaw(key.copy(isDefault = false))
        if (DefaultKeyPolicy.pickAfterInsert(existing, newId) == newId) {
            clearDefault(key.providerId)
            markDefault(newId, now)
        }
        return newId
    }

    /** 设默认。同一个事务里清掉其它的，所以"两张默认"不会有中间态被观察到。 */
    @Transaction
    suspend fun setDefault(providerId: Long, keyId: Long, now: Long) {
        clearDefault(providerId)
        markDefault(keyId, now)
    }

    /** 删除。删掉默认那张后自动把 `sortOrder` 最小的启用 Key 顶上（§6.3）。 */
    @Transaction
    suspend fun delete(id: Long, now: Long) {
        val target = findById(id) ?: return
        deleteRaw(id)
        promoteDefault(
            providerId = target.providerId,
            next = DefaultKeyPolicy.pickAfterRemoval(
                remaining = defaultKeyCandidates(target.providerId),
                removedWasDefault = target.isDefault,
            ),
            now = now,
        )
    }

    /**
     * 停用 / 启用。停用默认那张时同样要顶人——**与删除的行为必须一致**，
     * 否则会留下一个"默认但不启用"的 Key，而它比删掉更难发现（那一行还在列表里）。
     */
    @Transaction
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long) {
        val target = findById(id) ?: return
        setEnabledRaw(id, enabled, now)
        if (enabled) return
        promoteDefault(
            providerId = target.providerId,
            next = DefaultKeyPolicy.pickAfterDisable(defaultKeyCandidates(target.providerId), id),
            now = now,
        )
    }

    /** 顶人。[next] 为 null 表示一张启用的都不剩，那时**不硬塞**（"没有可用密钥"是合法状态）。 */
    @Transaction
    suspend fun promoteDefault(providerId: Long, next: Long?, now: Long) {
        if (next == null) return
        clearDefault(providerId)
        markDefault(next, now)
    }
}

/**
 * 把当前这家的 Key 收成 [DefaultKeyPolicy] 需要的形状。
 *
 * 写成扩展函数而不是 DAO 里的私有方法：Room 的处理器只认抽象方法与带 `@Transaction`
 * 的默认实现，接口里放私有辅助函数是给自己找麻烦。
 */
private suspend fun ApiKeyDao.defaultKeyCandidates(providerId: Long) =
    findByProvider(providerId).map {
        DefaultKeyPolicy.Candidate(
            id = it.id,
            enabled = it.enabled,
            sortOrder = it.sortOrder,
            isDefault = it.isDefault,
        )
    }
