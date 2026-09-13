package com.lc33.tokenvault.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.lc33.tokenvault.data.entity.AppSettingEntity
import com.lc33.tokenvault.data.entity.AuditLogEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.ModelCatalogEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProbeRunEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderAccountDao {

    @Query("SELECT * FROM provider_accounts WHERE providerId = :providerId ORDER BY sortOrder, id")
    fun observeByProvider(providerId: Long): Flow<List<ProviderAccountEntity>>

    @Query("SELECT * FROM provider_accounts ORDER BY providerId, sortOrder, id")
    suspend fun findAll(): List<ProviderAccountEntity>

    @Query("SELECT * FROM provider_accounts WHERE id = :id")
    suspend fun findById(id: Long): ProviderAccountEntity?

    @Insert
    suspend fun insert(account: ProviderAccountEntity): Long

    @Update
    suspend fun update(account: ProviderAccountEntity)

    @Query("DELETE FROM provider_accounts WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * 回填用户名密文（新增的两步写，第二步）。
     *
     * AAD 绑主键（红线 24），主键插入时才分配，所以和 `api_keys.secretEnc` 一样分两步。
     * 刻意不复用 `@Update`：那个整行替换，而这里只回填用户名这一列——整行替换会
     * 把密码密文、`loginUrl`、`label` 一起覆盖掉。
     */
    @Query("UPDATE provider_accounts SET usernameEnc = :enc, usernameFp = :fp, updatedAt = :now WHERE id = :id")
    suspend fun setUsername(id: Long, enc: ByteArray?, fp: String?, now: Long)

    /** 编辑明文元数据；凭据列单独写，避免整行替换触碰密文。 */
    @Query(
        """
        UPDATE provider_accounts
        SET label = :label, loginUrl = :loginUrl, loginMethods = :loginMethods,
            note = :note, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun setMeta(
        id: Long,
        label: String,
        loginUrl: String?,
        loginMethods: String,
        note: String?,
        now: Long,
    )

    /** 登录方式是明文元数据，单独写，避免整行替换把两段密文一起暴露给编辑路径。 */
    @Query("UPDATE provider_accounts SET loginMethods = :loginMethods, updatedAt = :now WHERE id = :id")
    suspend fun setLoginMethods(id: Long, loginMethods: String, now: Long)

    /** 回填密码密文，同理只动密码列。 */
    @Query("UPDATE provider_accounts SET passwordEnc = :enc, updatedAt = :now WHERE id = :id")
    suspend fun setPassword(id: Long, enc: ByteArray?, now: Long)
}

@Dao
interface ClientProfileDao {

    @Query("SELECT * FROM client_profiles ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ClientProfileEntity>>

    /** 全量快照（备份导出用）。 */
    @Query("SELECT * FROM client_profiles ORDER BY sortOrder, id")
    suspend fun findAll(): List<ClientProfileEntity>

    @Query("SELECT * FROM client_profiles WHERE id = :id")
    suspend fun findById(id: Long): ClientProfileEntity?

    @Query("SELECT * FROM client_profiles WHERE builtinKey = :builtinKey")
    suspend fun findByBuiltinKey(builtinKey: String): ClientProfileEntity?

    @Insert
    suspend fun insert(profile: ClientProfileEntity): Long

    @Update
    suspend fun update(profile: ClientProfileEntity)

    @Query("DELETE FROM client_profiles WHERE id = :id AND builtinKey IS NULL")
    suspend fun deleteCustom(id: Long)

    /** 清空**自定义**预设（`builtinKey IS NULL`），内置的由覆盖恢复时 `ProfileSeeder` 重建。 */
    @Query("DELETE FROM client_profiles WHERE builtinKey IS NULL")
    suspend fun clearCustom()

    /**
     * 种内置预设（`ProfileSeeder` 用）。
     *
     * **幂等**：按 `builtinKey` 找，不存在就插，存在且 `userEdited = 0` 且 `builtinRev` 更旧
     * 就更新。改过的保留——这样既能随版本修正指纹，又不会覆盖用户抓包校准的结果（§6.2）。
     * 升级路径也能种出来，所以这个方法在每次启动时跑都是安全的。
     */
    @Transaction
    suspend fun seedBuiltin(profile: ClientProfileEntity) {
        val existing = findByBuiltinKey(profile.builtinKey ?: return)
        if (existing == null) {
            insert(profile)
            return
        }
        if (existing.userEdited) return
        if (existing.builtinRev >= profile.builtinRev) return
        update(profile.copy(id = existing.id, verified = existing.verified))
    }
}

@Dao
interface ModelDao {

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY sortOrder, modelId")
    fun observeByProvider(providerId: Long): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models ORDER BY providerId, sortOrder, modelId")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId")
    suspend fun findByProvider(providerId: Long): List<ModelEntity>

    @Query("SELECT * FROM models WHERE providerId = :providerId AND keyId = :keyId ORDER BY sortOrder, modelId")
    suspend fun findByProviderAndKey(providerId: Long, keyId: Long): List<ModelEntity>

    /** 开启「模型列表自动更新」前的确认动作：清掉这家已保存的模型列表。 */
    @Query("DELETE FROM models WHERE providerId = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    /** 全量快照（备份导出用）。 */
    @Query("SELECT * FROM models ORDER BY providerId, sortOrder, modelId")
    suspend fun findAll(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun findById(id: Long): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(model: ModelEntity): Long

    @Update
    suspend fun update(model: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE models SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE models SET lastSeenAt = :now WHERE id = :id")
    suspend fun touchLastSeen(id: Long, now: Long)

    /**
     * "上游消失即停用"。
     *
     * **两个限定都不能少**（红线 30）：只动 `source = 'discovered'` 的行（手动录入的永不被
     * 自动同步改动，红线 13），且只动 `discoveredVia = 本轮查询的那个协议` 的行。
     * 少了后者，只拉了 CHAT 列表就会把所有 ANTHROPIC 发现项一起停用。
     *
     * 停用而不是删除：用户可能还想看到"这个模型上游下架了"，删了就没有这条信息（红线 13）。
     */
    @Query(
        """
        UPDATE models SET enabled = 0
        WHERE providerId = :providerId
          AND source = 'discovered'
          AND discoveredVia = :protocol
          AND keyId = :keyId
          AND modelId NOT IN (:seenModelIds)
        """,
    )
    suspend fun disableVanished(providerId: Long, keyId: Long, protocol: String, seenModelIds: List<String>)

    @Query(
        """
        UPDATE models SET
            probeState = :probeState, lastOutcome = :lastOutcome, probeDetail = :detail,
            latencyMs = :latencyMs, probedAt = :probedAt
        WHERE id = :id
        """,
    )
    suspend fun applyProbeResult(
        id: Long,
        probeState: String,
        lastOutcome: String,
        detail: String?,
        latencyMs: Long?,
        probedAt: Long,
    )

    /** 只写瞬时结果，`probeState` 不动（红线 11 在模型行上的同一条规则）。 */
    @Query(
        """
        UPDATE models SET lastOutcome = :lastOutcome, probeDetail = :detail, probedAt = :probedAt
        WHERE id = :id
        """,
    )
    suspend fun applyTransientOutcome(id: Long, lastOutcome: String, detail: String?, probedAt: Long)
}

@Dao
interface ModelCatalogDao {

    @Query("SELECT * FROM model_catalog WHERE `key` = :key")
    suspend fun findByKey(key: String): ModelCatalogEntity?

    /** 三级匹配的第二级：精确 modelId。 */
    @Query("SELECT * FROM model_catalog WHERE modelId = :modelId LIMIT 5")
    suspend fun findByModelId(modelId: String): List<ModelCatalogEntity>

    /** 三级匹配的第三级：归一化 id。 */
    @Query("SELECT * FROM model_catalog WHERE normId = :normId LIMIT 5")
    suspend fun findByNormId(normId: String): List<ModelCatalogEntity>

    @Query("SELECT COUNT(*) FROM model_catalog")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(entries: List<ModelCatalogEntity>)

    @Query("DELETE FROM model_catalog")
    suspend fun clear()
}

@Dao
interface ProbeRunDao {

    @Query("SELECT * FROM probe_runs ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(): Flow<ProbeRunEntity?>

    @Query("SELECT * FROM probe_runs WHERE id = :id")
    suspend fun findById(id: Long): ProbeRunEntity?

    @Insert
    suspend fun insert(run: ProbeRunEntity): Long

    @Update
    suspend fun update(run: ProbeRunEntity)

    /** 只保留最近几轮：探测明细页只看最近一轮，历史留着只是占地方。 */
    @Query("DELETE FROM probe_runs WHERE id NOT IN (SELECT id FROM probe_runs ORDER BY startedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    /** 清空（备份"覆盖恢复"用——红线 28：探测结果不搬，恢复后一律未探测）。 */
    @Query("DELETE FROM probe_runs")
    suspend fun clear()
}

@Dao
interface AuditLogDao {

    @Query("SELECT * FROM audit_log ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log WHERE providerId = :providerId ORDER BY at DESC LIMIT :limit")
    fun observeByProvider(providerId: Long, limit: Int): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log WHERE keyId = :keyId ORDER BY at DESC LIMIT :limit")
    fun observeByKey(keyId: Long, limit: Int): Flow<List<AuditLogEntity>>

    @Insert
    suspend fun insert(entry: AuditLogEntity): Long

    @Query("DELETE FROM audit_log")
    suspend fun clear()

    /** 条数上限。 */
    @Query("DELETE FROM audit_log WHERE id NOT IN (SELECT id FROM audit_log ORDER BY at DESC LIMIT :keep)")
    suspend fun trimToCount(keep: Int)

    /** 天数上限。两条都要有，因为"十万条但都是今天的"与"十条但有三年前的"都不该留。 */
    @Query("DELETE FROM audit_log WHERE at < :before")
    suspend fun trimOlderThan(before: Long)
}

@Dao
interface AppSettingDao {

    @Query("SELECT * FROM app_settings")
    fun observeAll(): Flow<List<AppSettingEntity>>

    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    suspend fun find(key: String): AppSettingEntity?

    @Upsert
    suspend fun put(setting: AppSettingEntity)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun remove(key: String)
}
