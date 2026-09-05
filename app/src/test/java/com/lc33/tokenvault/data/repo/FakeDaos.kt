package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.dao.GroupDao
import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.dao.ProviderSummaryRow
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 假 DAO，只给仓库单测用。
 *
 * 为什么是假 DAO 而不是内存 Room：本项目没有 Robolectric，Room 在 JVM 上起不来
 * （见 [TransactionRunner] 的 KDoc）。用假 DAO 能验的是仓库自己的逻辑——映射、AAD
 * 绑对没绑对、两步写的顺序、默认 Key 那套不变量（那几个 `@Transaction` 默认方法在接口上
 * 有真实现，会被继承下来，所以它们是被真正执行的）。
 *
 * **验不到的部分要说清**：外键 CASCADE、部分唯一索引 `idx_keys_default`、
 * `(providerId, fingerprint)` 唯一约束，全都是 SQL 层面的，只有真设备能验。
 *
 * 两条实现上的坑，都是这套假 DAO 第一版真撞到的：
 *
 * 1. **行表不能是 `MutableStateFlow<List<Entity>>`。** 这几个实体的 `equals` 是刻意残缺的
 *    （只比 id、密文、`updatedAt`，因为它是给 Room 用的），而 `MutableStateFlow.value` 的
 *    setter 会先用 `equals` 判断"值变了没"，没变就既不存也不发。于是
 *    `copy(isDefault = true)` 这种只动了未参与比较的字段的写入被**静默丢掉**。
 *    所以这里用"可变列表 + 一个 revision 计数"，写入永远生效。
 * 2. id 分配模仿 `AUTOINCREMENT`：**单调递增、删掉也不复用**。这一点不是细节——
 *    密文的 AAD 绑的是主键，id 复用等于让旧密文能被搬进新行（见 `ApiKeyDao.setSecret`）。
 */
internal class FakeGroupDao : GroupDao {
    private val store = mutableListOf<GroupEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    val rows: List<GroupEntity> get() = store.toList()

    private fun ordered() = store.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override fun observeAll(): Flow<List<GroupEntity>> = revision.map { ordered() }

    override suspend fun findAll(): List<GroupEntity> = ordered()

    override suspend fun findById(id: Long): GroupEntity? = store.firstOrNull { it.id == id }

    override suspend fun insert(group: GroupEntity): Long {
        val id = nextId++
        store += group.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun update(group: GroupEntity) = replace(group.id) { group }

    override suspend fun delete(id: Long) {
        store.removeAll { it.id == id }
        revision.value++
    }

    override suspend fun setSortOrder(id: Long, sortOrder: Int) =
        replace(id) { it.copy(sortOrder = sortOrder) }

    private inline fun replace(id: Long, transform: (GroupEntity) -> GroupEntity) {
        val index = store.indexOfFirst { it.id == id }
        if (index < 0) return
        store[index] = transform(store[index])
        revision.value++
    }
}

internal class FakeProviderDao : ProviderDao {
    private val store = mutableListOf<ProviderEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    val rows: List<ProviderEntity> get() = store.toList()

    /** 计数一律 0：这个假 DAO 里没有别的表，而聚合那条 SQL 本来也只有真库能验。 */
    override fun observeSummaries(): Flow<List<ProviderSummaryRow>> =
        revision.map { store.map { ProviderSummaryRow(it, 0, 0, 0, 0) } }

    override fun observeById(id: Long): Flow<ProviderEntity?> =
        revision.map { store.firstOrNull { it.id == id } }

    override suspend fun findById(id: Long): ProviderEntity? = store.firstOrNull { it.id == id }

    override suspend fun findAll(): List<ProviderEntity> = store.toList()

    override suspend fun insert(provider: ProviderEntity): Long {
        val id = nextId++
        store += provider.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun update(provider: ProviderEntity) = replace(provider.id) { provider }

    override suspend fun delete(id: Long) {
        store.removeAll { it.id == id }
        revision.value++
    }

    override suspend fun setGroup(ids: List<Long>, groupId: Long?, now: Long) {
        ids.forEach { id -> replace(id) { it.copy(groupId = groupId, updatedAt = now) } }
    }

    override suspend fun setBalanceToken(id: Long, token: ByteArray?, now: Long) =
        replace(id) { it.copy(balanceTokenEnc = token, updatedAt = now) }

    override suspend fun updateBalance(
        id: Long,
        amount: Double?,
        used: Double?,
        currency: String?,
        raw: String?,
        checkedAt: Long,
        error: String?,
        calibrated: Boolean,
    ) = replace(id) {
        it.copy(
            balanceAmount = amount,
            balanceUsed = used,
            balanceCurrency = currency,
            balanceRaw = raw,
            balanceCheckedAt = checkedAt,
            balanceError = error,
            quotaCalibrated = calibrated,
            updatedAt = checkedAt,
        )
    }

    override suspend fun calibrateQuotaPerUnit(id: Long, quotaPerUnit: Double) =
        replace(id) { it.copy(quotaPerUnit = quotaPerUnit, quotaCalibrated = true) }

    private inline fun replace(id: Long, transform: (ProviderEntity) -> ProviderEntity) {
        val index = store.indexOfFirst { it.id == id }
        if (index < 0) return
        store[index] = transform(store[index])
        revision.value++
    }
}

internal class FakeApiKeyDao : ApiKeyDao {
    private val store = mutableListOf<ApiKeyEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    val rows: List<ApiKeyEntity> get() = store.toList()

    private fun ordered(list: List<ApiKeyEntity> = store) =
        list.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override fun observeByProvider(providerId: Long): Flow<List<ApiKeyEntity>> =
        revision.map { ordered(store.filter { it.providerId == providerId }) }

    override fun observeAll(): Flow<List<ApiKeyEntity>> = revision.map { ordered() }

    override suspend fun findByProvider(providerId: Long): List<ApiKeyEntity> =
        ordered(store.filter { it.providerId == providerId })

    override suspend fun findById(id: Long): ApiKeyEntity? = store.firstOrNull { it.id == id }

    override suspend fun findDefault(providerId: Long): ApiKeyEntity? =
        store.firstOrNull { it.providerId == providerId && it.isDefault && it.enabled }

    override suspend fun insertRaw(key: ApiKeyEntity): Long {
        val id = nextId++
        store += key.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun update(key: ApiKeyEntity) = replace(key.id) { key }

    override suspend fun deleteRaw(id: Long) {
        store.removeAll { it.id == id }
        revision.value++
    }

    override suspend fun clearDefault(providerId: Long) {
        store.indices
            .filter { store[it].providerId == providerId }
            .forEach { store[it] = store[it].copy(isDefault = false) }
        revision.value++
    }

    override suspend fun markDefault(id: Long, now: Long) =
        replace(id) { it.copy(isDefault = true, updatedAt = now) }

    override suspend fun setEnabledRaw(id: Long, enabled: Boolean, now: Long) =
        replace(id) { it.copy(enabled = enabled, updatedAt = now) }

    override suspend fun setSecret(id: Long, secretEnc: ByteArray, fingerprint: String, now: Long) =
        replace(id) { it.copy(secretEnc = secretEnc, fingerprint = fingerprint, updatedAt = now) }

    override suspend fun setMeta(id: Long, label: String, sortOrder: Int, now: Long) =
        replace(id) { it.copy(label = label, sortOrder = sortOrder, updatedAt = now) }

    override suspend fun applyProbeResult(
        id: Long,
        health: String,
        lastOutcome: String,
        detail: String?,
        httpStatus: Int?,
        latencyMs: Long?,
        checkedAt: Long,
        okAt: Long?,
    ) = replace(id) {
        it.copy(
            health = health,
            lastOutcome = lastOutcome,
            healthDetail = detail,
            httpStatus = httpStatus,
            latencyMs = latencyMs,
            checkedAt = checkedAt,
            okAt = okAt,
            updatedAt = checkedAt,
        )
    }

    /** 和真 SQL 一样**不提 health 与 okAt**（红线 11）：假实现照抄那条语句的形状。 */
    override suspend fun applyTransientOutcome(
        id: Long,
        lastOutcome: String,
        detail: String?,
        httpStatus: Int?,
        checkedAt: Long,
    ) = replace(id) {
        it.copy(
            lastOutcome = lastOutcome,
            healthDetail = detail,
            httpStatus = httpStatus,
            checkedAt = checkedAt,
            updatedAt = checkedAt,
        )
    }

    private inline fun replace(id: Long, transform: (ApiKeyEntity) -> ApiKeyEntity) {
        val index = store.indexOfFirst { it.id == id }
        if (index < 0) return
        store[index] = transform(store[index])
        revision.value++
    }
}

/** 直接执行，不开事务：JVM 上没有真库可开。 */
internal class ImmediateTransactions : TransactionRunner {
    override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
}


