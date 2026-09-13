package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.domain.repo.TransactionRunner

import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.dao.ApiKeyWithSettingsRow
import com.lc33.tokenvault.data.dao.AppSettingDao
import com.lc33.tokenvault.data.dao.AuditLogDao
import com.lc33.tokenvault.data.dao.ClientProfileDao
import com.lc33.tokenvault.data.dao.GroupDao
import com.lc33.tokenvault.data.dao.KeySettingsDao
import com.lc33.tokenvault.data.dao.ModelDao
import com.lc33.tokenvault.data.dao.ProbeRunDao
import com.lc33.tokenvault.data.dao.ProviderAccountDao
import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.dao.ProviderSummaryRow
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.AppSettingEntity
import com.lc33.tokenvault.data.entity.AuditLogEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.KeySettingsEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProbeRunEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
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

    override suspend fun clear() {
        store.clear()
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

    override suspend fun clear() {
        store.clear()
        revision.value++
    }

    override suspend fun setGroup(ids: List<Long>, groupId: Long?, now: Long) {
        ids.forEach { id -> replace(id) { it.copy(groupId = groupId, updatedAt = now) } }
    }

    override suspend fun setSortOrder(id: Long, sortOrder: Int, now: Long) =
        replace(id) { it.copy(sortOrder = sortOrder, updatedAt = now) }

    override suspend fun updateWebsiteStatus(
        id: Long,
        latencyMs: Long?,
        checkedAt: Long,
        error: String?,
    ) = replace(id) {
        it.copy(
            websiteLatencyMs = latencyMs,
            websiteCheckedAt = checkedAt,
            websiteError = error,
            updatedAt = checkedAt,
        )
    }

    private inline fun replace(id: Long, transform: (ProviderEntity) -> ProviderEntity) {
        val index = store.indexOfFirst { it.id == id }
        if (index < 0) return
        store[index] = transform(store[index])
        revision.value++
    }
}

internal class FakeKeySettingsDao : KeySettingsDao {
    private val store = mutableListOf<KeySettingsEntity>()
    private val revision = MutableStateFlow(0)

    val rows: List<KeySettingsEntity> get() = store.toList()

    fun peek(keyId: Long): KeySettingsEntity? = store.firstOrNull { it.keyId == keyId }

    fun clearForTest() {
        store.clear()
        revision.value++
    }

    override fun observeByKey(keyId: Long): Flow<KeySettingsEntity?> =
        revision.map { store.firstOrNull { it.keyId == keyId } }

    override suspend fun findByKey(keyId: Long): KeySettingsEntity? =
        store.firstOrNull { it.keyId == keyId }

    override suspend fun findAll(): List<KeySettingsEntity> = store.toList()

    override suspend fun insert(settings: KeySettingsEntity) {
        val index = store.indexOfFirst { it.keyId == settings.keyId }
        if (index < 0) store += settings else store[index] = settings
        revision.value++
    }

    override suspend fun update(settings: KeySettingsEntity) = insert(settings)

    override suspend fun setBalanceToken(keyId: Long, token: ByteArray?, now: Long) {
        val index = store.indexOfFirst { it.keyId == keyId }
        if (index >= 0) store[index] = store[index].copy(balanceTokenEnc = token, updatedAt = now)
        revision.value++
    }

    override suspend fun calibrateQuotaPerUnit(keyId: Long, quotaPerUnit: Double, now: Long) {
        val index = store.indexOfFirst { it.keyId == keyId }
        if (index >= 0) {
            store[index] = store[index].copy(quotaPerUnit = quotaPerUnit, quotaCalibrated = true, updatedAt = now)
        }
        revision.value++
    }

    override suspend fun delete(keyId: Long) {
        store.removeAll { it.keyId == keyId }
        revision.value++
    }
}

internal class FakeApiKeyDao(
    private val settingsDao: FakeKeySettingsDao,
) : ApiKeyDao {
    private val store = mutableListOf<ApiKeyEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    val rows: List<ApiKeyEntity> get() = store.toList()

    fun clearForTest() {
        store.clear()
        settingsDao.clearForTest()
        revision.value++
    }

    private fun ordered(list: List<ApiKeyEntity> = store) =
        list.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    private fun row(key: ApiKeyEntity) = ApiKeyWithSettingsRow(
        key = key,
        settings = settingsDao.peek(key.id),
    )

    override fun observeByProvider(providerId: Long): Flow<List<ApiKeyWithSettingsRow>> =
        revision.map { ordered(store.filter { it.providerId == providerId }).map(::row) }

    override fun observeAll(): Flow<List<ApiKeyWithSettingsRow>> =
        revision.map { ordered().map(::row) }

    override suspend fun findAll(): List<ApiKeyWithSettingsRow> = ordered().map(::row)

    override suspend fun findByProvider(providerId: Long): List<ApiKeyWithSettingsRow> =
        ordered(store.filter { it.providerId == providerId }).map(::row)

    override suspend fun findById(id: Long): ApiKeyWithSettingsRow? =
        store.firstOrNull { it.id == id }?.let(::row)

    override suspend fun findRaw(id: Long): ApiKeyEntity? = store.firstOrNull { it.id == id }

    override suspend fun countFingerprint(providerId: Long, fingerprint: String): Int =
        store.count { it.providerId == providerId && it.fingerprint == fingerprint }

    override suspend fun insertRaw(key: ApiKeyEntity): Long {
        val id = nextId++
        store += key.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun update(key: ApiKeyEntity) = replace(key.id) { key }

    override suspend fun setSecret(id: Long, secretEnc: ByteArray, fingerprint: String, now: Long) =
        replace(id) { it.copy(secretEnc = secretEnc, fingerprint = fingerprint, updatedAt = now) }

    override suspend fun updateMeta(id: Long, label: String, note: String, sortOrder: Int, now: Long) =
        replace(id) { it.copy(label = label, note = note, sortOrder = sortOrder, updatedAt = now) }

    override suspend fun setEnabled(id: Long, enabled: Boolean, now: Long) =
        replace(id) { it.copy(enabled = enabled, updatedAt = now) }

    override suspend fun delete(id: Long) {
        store.removeAll { it.id == id }
        settingsDao.delete(id)
        revision.value++
    }

    override suspend fun updateBalance(
        id: Long,
        amount: Double?,
        used: Double?,
        currency: String?,
        raw: String?,
        checkedAt: Long,
        error: String?,
    ) = replace(id) {
        it.copy(
            balanceAmount = amount,
            balanceUsed = used,
            balanceCurrency = currency,
            balanceRaw = raw,
            balanceCheckedAt = checkedAt,
            balanceError = error,
        )
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

    override suspend fun resetProbeResults() {
        store.indices.forEach { i ->
            store[i] = store[i].copy(
                health = "unknown",
                lastOutcome = "skipped",
                healthDetail = null,
                httpStatus = null,
                latencyMs = null,
                checkedAt = null,
                okAt = null,
            )
        }
        revision.value++
    }

    override suspend fun reorder(providerId: Long, idsInOrder: List<Long>, now: Long) {
        idsInOrder.forEachIndexed { index, id ->
            if (store.any { it.id == id && it.providerId == providerId }) {
                setSortOrder(providerId, id, index, now)
            }
        }
    }

    override suspend fun setSortOrder(providerId: Long, id: Long, sortOrder: Int, now: Long) {
        val target = store.firstOrNull { it.id == id && it.providerId == providerId } ?: return
        replace(id) { target.copy(sortOrder = sortOrder, updatedAt = now) }
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

internal class FakeProviderAccountDao : ProviderAccountDao {
    private val store = mutableListOf<ProviderAccountEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    val rows: List<ProviderAccountEntity> get() = store.toList()

    /** 测试专用：直接清空。 */
    fun clearForTest() {
        store.clear()
        revision.value++
    }

    private fun ordered() = store.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override fun observeByProvider(providerId: Long): Flow<List<ProviderAccountEntity>> =
        revision.map { ordered().filter { it.providerId == providerId } }

    override suspend fun findAll(): List<ProviderAccountEntity> = ordered()

    override suspend fun findById(id: Long): ProviderAccountEntity? = store.firstOrNull { it.id == id }

    override suspend fun insert(account: ProviderAccountEntity): Long {
        val id = nextId++
        store += account.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun update(account: ProviderAccountEntity) = replace(account.id) { account }

    override suspend fun delete(id: Long) {
        store.removeAll { it.id == id }
        revision.value++
    }

    override suspend fun setUsername(id: Long, enc: ByteArray?, fp: String?, now: Long) =
        replace(id) { it.copy(usernameEnc = enc, usernameFp = fp, updatedAt = now) }

    override suspend fun setMeta(
        id: Long,
        label: String,
        loginUrl: String?,
        loginMethods: String,
        note: String?,
        now: Long,
    ) = replace(id) {
        it.copy(
            label = label,
            loginUrl = loginUrl,
            loginMethods = loginMethods,
            note = note,
            updatedAt = now,
        )
    }

    override suspend fun setPassword(id: Long, enc: ByteArray?, now: Long) =
        replace(id) { it.copy(passwordEnc = enc, updatedAt = now) }

    override suspend fun setLoginMethods(id: Long, loginMethods: String, now: Long) =
        replace(id) { it.copy(loginMethods = loginMethods, updatedAt = now) }

    private inline fun replace(id: Long, transform: (ProviderAccountEntity) -> ProviderAccountEntity) {
        val index = store.indexOfFirst { it.id == id }
        if (index < 0) return
        store[index] = transform(store[index])
        revision.value++
    }
}

internal class FakeModelDao : ModelDao {
    private val store = mutableListOf<ModelEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    val rows: List<ModelEntity> get() = store.toList()

    /** 测试专用：直接清空。 */
    fun clearForTest() {
        store.clear()
        revision.value++
    }

    private fun ordered() = store.sortedWith(compareBy({ it.sortOrder }, { it.modelId }))

    override fun observeByProvider(providerId: Long): Flow<List<ModelEntity>> =
        revision.map { ordered().filter { it.providerId == providerId } }

    override fun observeAll(): Flow<List<ModelEntity>> = revision.map { ordered() }

    override suspend fun findAll(): List<ModelEntity> = ordered()

    override suspend fun findByProvider(providerId: Long): List<ModelEntity> =
        ordered().filter { it.providerId == providerId }

    override suspend fun findByProviderAndKey(providerId: Long, keyId: Long): List<ModelEntity> =
        ordered().filter { it.providerId == providerId && it.keyId == keyId }

    override suspend fun deleteByProvider(providerId: Long) {
        store.removeAll { it.providerId == providerId }
        revision.value++
    }

    override suspend fun findById(id: Long): ModelEntity? = store.firstOrNull { it.id == id }

    override suspend fun insertIgnoring(model: ModelEntity): Long {
        val id = nextId++
        store += model.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun update(model: ModelEntity) = replace(model.id) { model }

    override suspend fun delete(id: Long) {
        store.removeAll { it.id == id }
        revision.value++
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) =
        replace(id) { it.copy(enabled = enabled) }

    override suspend fun touchLastSeen(id: Long, now: Long) =
        replace(id) { it.copy(lastSeenAt = now) }

    override suspend fun disableVanished(
        providerId: Long,
        keyId: Long,
        protocol: String,
        seenModelIds: List<String>,
    ) {
        store.indices
            .filter {
                val m = store[it]
                m.providerId == providerId && m.keyId == keyId && m.source == "discovered" &&
                    m.discoveredVia == protocol && m.modelId !in seenModelIds
            }
            .forEach { store[it] = store[it].copy(enabled = false) }
        revision.value++
    }

    override suspend fun applyProbeResult(
        id: Long,
        probeState: String,
        lastOutcome: String,
        detail: String?,
        latencyMs: Long?,
        probedAt: Long,
    ) = replace(id) {
        it.copy(
            probeState = probeState,
            lastOutcome = lastOutcome,
            probeDetail = detail,
            latencyMs = latencyMs,
            probedAt = probedAt,
        )
    }

    override suspend fun applyTransientOutcome(id: Long, lastOutcome: String, detail: String?, probedAt: Long) =
        replace(id) { it.copy(lastOutcome = lastOutcome, probeDetail = detail, probedAt = probedAt) }

    private inline fun replace(id: Long, transform: (ModelEntity) -> ModelEntity) {
        val index = store.indexOfFirst { it.id == id }
        if (index < 0) return
        store[index] = transform(store[index])
        revision.value++
    }
}

/**
 * 设置表。主键是 `key`，所以 [put] 是 upsert；同一个键写两次得到一行而不是两行。
 *
 * 同样用「可变列表 + revision」：[AppSettingEntity] 的 `equals` 把 `valueBlob` 算进来了，
 * 而那是个 `ByteArray`——拿 `MutableStateFlow<List<...>>` 当行表时这一类写入的丢掉方式都是静默的。
 */
internal class FakeAppSettingDao : AppSettingDao {
    private val store = mutableListOf<AppSettingEntity>()
    private val revision = MutableStateFlow(0)

    val rows: List<AppSettingEntity> get() = store.toList()

    override fun observeAll(): Flow<List<AppSettingEntity>> = revision.map { store.toList() }

    override suspend fun find(key: String): AppSettingEntity? = store.firstOrNull { it.key == key }

    override suspend fun put(setting: AppSettingEntity) {
        val index = store.indexOfFirst { it.key == setting.key }
        if (index < 0) store += setting else store[index] = setting
        revision.value++
    }

    override suspend fun remove(key: String) {
        store.removeAll { it.key == key }
        revision.value++
    }
}

internal class FakeClientProfileDao : ClientProfileDao {
    private val store = mutableListOf<ClientProfileEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    val rows: List<ClientProfileEntity> get() = store.toList()

    private fun ordered() = store.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override fun observeAll(): Flow<List<ClientProfileEntity>> = revision.map { ordered() }

    override suspend fun findAll(): List<ClientProfileEntity> = ordered()

    override suspend fun findById(id: Long): ClientProfileEntity? = store.firstOrNull { it.id == id }

    override suspend fun findByBuiltinKey(builtinKey: String): ClientProfileEntity? =
        store.firstOrNull { it.builtinKey == builtinKey }

    override suspend fun insert(profile: ClientProfileEntity): Long {
        val id = nextId++
        store += profile.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun update(profile: ClientProfileEntity) = replace(profile.id) { profile }

    override suspend fun deleteCustom(id: Long) {
        store.removeAll { it.id == id && it.builtinKey == null }
        revision.value++
    }

    override suspend fun clearCustom() {
        store.removeAll { it.builtinKey == null }
        revision.value++
    }

    private inline fun replace(id: Long, transform: (ClientProfileEntity) -> ClientProfileEntity) {
        val index = store.indexOfFirst { it.id == id }
        if (index < 0) return
        store[index] = transform(store[index])
        revision.value++
    }
}

internal class FakeProbeRunDao : ProbeRunDao {
    private val store = mutableListOf<ProbeRunEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    val rows: List<ProbeRunEntity> get() = store.toList()

    override fun observeLatest(): Flow<ProbeRunEntity?> = revision.map {
        store.maxByOrNull { it.startedAt }
    }

    override suspend fun findById(id: Long): ProbeRunEntity? = store.firstOrNull { it.id == id }

    override suspend fun insert(run: ProbeRunEntity): Long {
        val id = nextId++
        store += run.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun update(run: ProbeRunEntity) {
        val index = store.indexOfFirst { it.id == run.id }
        if (index < 0) return
        store[index] = run
        revision.value++
    }

    override suspend fun trim(keep: Int) {
        val kept = store.sortedByDescending { it.startedAt }.take(keep).toSet()
        store.removeAll { it !in kept }
        revision.value++
    }

    override suspend fun clear() {
        store.clear()
        revision.value++
    }
}

internal class FakeAuditLogDao : AuditLogDao {
    private val store = mutableListOf<AuditLogEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    val rows: List<AuditLogEntity> get() = store.toList()

    private fun ordered(limit: Int) = store.sortedByDescending { it.at }.take(limit)

    override fun observeRecent(limit: Int): Flow<List<AuditLogEntity>> = revision.map { ordered(limit) }

    override fun observeByProvider(providerId: Long, limit: Int): Flow<List<AuditLogEntity>> =
        revision.map { ordered(limit).filter { it.providerId == providerId } }

    override fun observeByKey(keyId: Long, limit: Int): Flow<List<AuditLogEntity>> =
        revision.map { ordered(limit).filter { it.keyId == keyId } }

    override suspend fun insert(entry: AuditLogEntity): Long {
        val id = nextId++
        store += entry.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun clear() {
        store.clear()
        revision.value++
    }

    override suspend fun trimToCount(keep: Int) {
        val kept = store.sortedByDescending { it.at }.take(keep).toSet()
        store.removeAll { it !in kept }
        revision.value++
    }

    override suspend fun trimOlderThan(before: Long) {
        store.removeAll { it.at < before }
        revision.value++
    }
}



