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
import com.lc33.tokenvault.data.entity.BalanceHistoryEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.ModelCatalogEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ModelVendorEntity
import com.lc33.tokenvault.data.entity.ProbeRunEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderAccountDao {

    @Query("SELECT * FROM provider_accounts WHERE providerId = :providerId ORDER BY sortOrder, id")
    fun observeByProvider(providerId: Long): Flow<List<ProviderAccountEntity>>

    @Query("SELECT * FROM provider_accounts ORDER BY providerId, sortOrder, id")
    suspend fun findAll(): List<ProviderAccountEntity>

    /** 撤销用：按供应商读回全部账号整行（含密文；按原主键写回时不需要解密）。 */
    @Query("SELECT * FROM provider_accounts WHERE providerId = :providerId ORDER BY sortOrder, id")
    suspend fun findByProvider(providerId: Long): List<ProviderAccountEntity>

    @Query("SELECT * FROM provider_accounts WHERE id = :id")
    suspend fun findById(id: Long): ProviderAccountEntity?

    /** 这一家已有几个账号：新增时的 `sortOrder`，只要一个数，不必把整表读回来。 */
    @Query("SELECT COUNT(*) FROM provider_accounts WHERE providerId = :providerId")
    suspend fun countByProvider(providerId: Long): Int

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

    /** 登录方式的单独更新走 [setMeta]（它本来就带这一列），不再另开一条只有两列的语句。 */

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

    /**
     * 批量整行写回。**只给目录回填那一处用**：`models.catalogKey` 是一个一个模型各算各的，
     * 没有一条 `UPDATE ... WHERE id IN (...)` 能同时写出几百个不同值，而逐行 `update(model)`
     * 就是几百次单独提交。调用方必须自己包在事务里。
     */
    @Update
    suspend fun updateAll(models: List<ModelEntity>)

    /** 目录回填的进度分母：库里总共有多少行模型。 */
    @Query("SELECT COUNT(*) FROM models")
    suspend fun countAll(): Int

    /**
     * 一把 Key 下还没挂上目录的模型行。给 `ModelCatalogRepository.rekeyUnkeyedModelsOfKey`
     * 用：一次模型列表刷新通常只新增几十行，不必把已经挂上的几百行再查一遍。
     */
    @Query(
        """
        SELECT * FROM models
        WHERE providerId = :providerId AND keyId = :keyId AND catalogKey IS NULL
        ORDER BY sortOrder, modelId
        """,
    )
    suspend fun findUnkeyedByProviderAndKey(providerId: Long, keyId: Long): List<ModelEntity>

    /** 回填效果：有多少行模型挂上了目录。 */
    @Query("SELECT COUNT(*) FROM models WHERE catalogKey IS NOT NULL")
    suspend fun countMatched(): Int

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: Long)

    /** 上游列表里没有了的发现项，按 id 批量删。 */
    @Query("DELETE FROM models WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE models SET lastSeenAt = :now WHERE id = :id")
    suspend fun touchLastSeen(id: Long, now: Long)

    /**
     * "上游消失即删除"。
     *
     * **两个限定都不能少**（红线 30）：只动 `source = 'discovered'` 的行（手动录入的永不被
     * 自动同步改动，红线 13），且只动 `discoveredVia = 本轮查询的那个协议` 的行。
     * 少了后者，只拉了 CHAT 列表就会把所有 ANTHROPIC 发现项一起删掉。
     *
     * 删除而不是停用：没有启用状态，留着一条谁都不用的行只会让列表和计数说谎。
     */
    @Query(
        """
        DELETE FROM models
        WHERE providerId = :providerId
          AND source = 'discovered'
          AND discoveredVia = :protocol
          AND keyId = :keyId
          AND modelId NOT IN (:seenModelIds)
        """,
    )
    suspend fun deleteVanished(providerId: Long, keyId: Long, protocol: String, seenModelIds: List<String>)

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

/**
 * 模型目录（`model_catalog`）。
 *
 * 写入方是 `engine/CatalogSync`（整表替换），读取方是 catalogKey 回填链路
 * （`RoomModelCatalogRepository`）与模型页的 JOIN。三级匹配的规则本身在
 * `catalog/ModelCatalogMatcher`（纯函数），这里只负责**按索引取候选**。
 *
 * **候选查询一律 `ORDER BY canonical DESC`**：一个热门 id 会被原创厂商和十几家聚合站
 * 各挂一份，价格互不相同，而匹配器要的恰恰是原创那条。原来 `LIMIT 5` 不带排序，
 * 意味着 SQLite 先返回哪五条看运气，原创条目完全可能在窗口之外——那样
 * `ModelCatalogMatcher` 里新加的 canonical 优先就成了空有优先级却拿不到数据。
 * 把排序下推到 SQL、窗口放宽到 20，才是「先给最好的候选，再让纯函数做决策」。
 */
@Dao
interface ModelCatalogDao {

    @Query("SELECT * FROM model_catalog WHERE `key` = :key")
    suspend fun findByKey(key: String): ModelCatalogEntity?

    /**
     * 一批目录条目一次取。
     *
     * 一把 Key 有 445 个模型时，逐条 [findByKey] 就是 445 次 prepared statement 加 445 次
     * 调度，而模型页要等这轮跑完才画得出能力 chip。这一条 IN 走同一个 `key` 索引，把
     * 那一下压成一次查询。
     */
    @Query("SELECT * FROM model_catalog WHERE `key` IN (:keys)")
    suspend fun findByKeysIn(keys: List<String>): List<ModelCatalogEntity>

    /**
     * 三级匹配的第一级（升级版）：输入形如 `vendor/model` 时的直达查询。
     *
     * 走 [com.lc33.tokenvault.data.entity.ModelCatalogEntity.qualifiedId] 而不是主键：
     * 目录主键是 `providerSlug/mapKey`，聚合站条目会多一层前缀，用输入串去比主键比不上。
     */
    @Query(
        """
        SELECT * FROM model_catalog WHERE qualifiedId = :qualifiedId
        ORDER BY canonical DESC, lastUpdated DESC LIMIT 20
        """,
    )
    suspend fun findByQualifiedId(qualifiedId: String): List<ModelCatalogEntity>

    /** 三级匹配的第二级：精确 modelId。 */
    @Query(
        """
        SELECT * FROM model_catalog WHERE modelId = :modelId
        ORDER BY canonical DESC, lastUpdated DESC LIMIT 20
        """,
    )
    suspend fun findByModelId(modelId: String): List<ModelCatalogEntity>

    /** 三级匹配的第三级：归一化 id。 */
    @Query(
        """
        SELECT * FROM model_catalog WHERE normId = :normId
        ORDER BY canonical DESC, lastUpdated DESC LIMIT 20
        """,
    )
    suspend fun findByNormId(normId: String): List<ModelCatalogEntity>

    /**
     * 全表。**回填 `models.catalogKey` 专用**：694 行模型逐行走三级查询是 2,082 次索引
     * 查询，而整表只有 7.8k 行、一次性读进内存建三份哈希（modelId / qualifiedId / normId）
     * 就够——回填发生在目录同步之后，本来就在后台协程里，内存换的是「刷一次列表等半天」。
     */
    @Query("SELECT * FROM model_catalog")
    suspend fun findAll(): List<ModelCatalogEntity>

    @Query("SELECT COUNT(*) FROM model_catalog")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(entries: List<ModelCatalogEntity>)

    @Query("DELETE FROM model_catalog")
    suspend fun clear()
}

/**
 * models.dev 的厂商表（`model_vendors`，222 行量级）。
 *
 * 只读为主：分组标题的展示名与「查看官方文档」的链接。整表替换由 `CatalogSync` 做，
 * 与目录行同一个事务，避免出现「目录是新的、厂商表还是旧的」。
 */
@Dao
interface ModelVendorDao {

    @Query("SELECT * FROM model_vendors WHERE slug = :slug")
    suspend fun findBySlug(slug: String): ModelVendorEntity?

    @Query("SELECT * FROM model_vendors ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ModelVendorEntity>>

    @Query("SELECT COUNT(*) FROM model_vendors")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(entries: List<ModelVendorEntity>)

    @Query("DELETE FROM model_vendors")
    suspend fun clear()
}

@Dao
interface ProbeRunDao {

    /**
     * 最近一条**已经结完**的轮次。
     *
     * `finishedAt IS NOT NULL` 不能省：一轮跑到一半进程被杀（或异常从收尾里逃出去）会留下
     * 一条没有 finishedAt 的行，而调用方是仪表盘与明细页那句「上次探测」——它会把
     * finishedAt 塌回 startedAt、计数全给 0，于是显示成「上次探测：刚刚 · 共 0 项」，
     * 一个看着完全合理的"跑完但什么都没测"的轮次。没跑完的轮次不该冒充结论。
     */
    @Query("SELECT * FROM probe_runs WHERE finishedAt IS NOT NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(): Flow<ProbeRunEntity?>

    @Query("SELECT * FROM probe_runs WHERE id = :id")
    suspend fun findById(id: Long): ProbeRunEntity?

    @Insert
    suspend fun insert(run: ProbeRunEntity): Long

    @Update
    suspend fun update(run: ProbeRunEntity)

    /**
     * 只保留最近 [keep] 轮：探测明细页只看最近一轮，历史留着只是占地方。
     *
     * 与 `AuditLogDao.trimToCount` 同名同语义（条数上限；天数上限是 `LogMaintenance` 的另一半）。
     * 按条数而不是按时间裁，是因为轮次的 `startedAt` 来自本机时钟：用户改过时间就可能
     * 把整表判成"太老"，条数不受时钟影响。
     */
    @Query("DELETE FROM probe_runs WHERE id NOT IN (SELECT id FROM probe_runs ORDER BY startedAt DESC, id DESC LIMIT :keep)")
    suspend fun trimToCount(keep: Int)

    /** 清空（备份"覆盖恢复"用——红线 28：探测结果不搬，恢复后一律未探测）。 */
    @Query("DELETE FROM probe_runs")
    suspend fun clear()
}

/**
 * 余额历史（`balance_history`）。用量变化报告的唯一数据源。
 *
 * 写路径只有 [insert]，且发生在 `RoomBalanceHistoryRepository.record` 去重之后
 * （同一把 Key 与上一条金额相同就不写）。[latestForKey] 就是那次去重要比对的上一条。
 *
 * 读路径 [observeAll] 按时间升序整表推给报告 ViewModel——聚合（按天分桶、跨 Key 求和）
 * 是纯函数的活，放在 `balance/UsageReportAggregator`，DAO 只管把行取全、别在 SQL 里算。
 * 表按「变化点」增长（去重）再加 [trimOlderThan] 的天数上限，量级远小于日志，整表读没有压力。
 */
@Dao
interface BalanceHistoryDao {

    @Insert
    suspend fun insert(row: BalanceHistoryEntity): Long

    /** 这把 Key 最近一条历史，供 [record] 去重比对；从没记过则为 null。 */
    @Query("SELECT * FROM balance_history WHERE keyId = :keyId ORDER BY capturedAt DESC, id DESC LIMIT 1")
    suspend fun latestForKey(keyId: Long): BalanceHistoryEntity?

    /** 整表按时间升序（报告按此重建每把 Key 的序列，再前推、分桶、求和）。 */
    @Query("SELECT * FROM balance_history ORDER BY capturedAt, id")
    fun observeAll(): Flow<List<BalanceHistoryEntity>>

    @Query("SELECT COUNT(*) FROM balance_history")
    suspend fun count(): Int

    /**
     * 按天数上限裁剪：删掉 [cutoff] 之前的样本。
     *
     * 与 `audit_log` / `probe_runs` 的条数上限不同，这里按**时间**裁：报告本来就只看
     * 近 N 天，更早的点画不进任何时间范围。天数上限的口径在 `LogMaintenance` 里给。
     */
    @Query("DELETE FROM balance_history WHERE capturedAt < :cutoff")
    suspend fun trimOlderThan(cutoff: Long)

    /** 清空（备份"覆盖恢复"用——与探测结果同类，恢复后历史从零重记）。 */
    @Query("DELETE FROM balance_history")
    suspend fun clear()
}

/**
 * 日志列表的轻量投影：**不带请求体 / 返回体**。
 *
 * 那两列单条上限 8KB（`HttpEngine.MAX_BODY_CHARS`），列表一次取 500 条就是几 MB 文本，
 * 而列表上一个字都不画它们——点开详情页才按 id 单独取（[AuditLogDao.findById]）。
 * `requestUrl` 留着：列表要靠它判断"这条能不能点开"。
 */
data class AuditLogSummary(
    val id: Long,
    val at: Long,
    val level: String,
    val category: String,
    val providerId: Long?,
    val keyId: Long?,
    val runId: Long?,
    val message: String,
    val detail: String?,
    val requestUrl: String?,
)

@Dao
interface AuditLogDao {

    /**
     * 列表按等级过滤（日志页的等级开关）。
     *
     * `ORDER BY at DESC, id DESC`：**同一毫秒写进多条**是常态（一轮探测里每个请求一条），
     * 只按 `at` 排时 SQLite 对这些并列行的顺序没有承诺，翻页与"最新在前"都会抖。
     * 主键单调递增，用它兜底就是稳定的插入序。
     */
    @Query(
        """
        SELECT id, at, level, category, providerId, keyId, runId, message, detail, requestUrl
        FROM audit_log WHERE level IN (:levels) ORDER BY at DESC, id DESC LIMIT :limit
        """,
    )
    fun observeRecentByLevels(levels: List<String>, limit: Int): Flow<List<AuditLogSummary>>

    /** 某家供应商的日志（详情页）。同样是 `at` + `id` 双键排序，理由见 [observeRecentByLevels]。 */
    @Query(
        """
        SELECT * FROM audit_log WHERE providerId = :providerId
        ORDER BY at DESC, id DESC LIMIT :limit
        """,
    )
    fun observeByProvider(providerId: Long, limit: Int): Flow<List<AuditLogEntity>>

    /** 单条。日志详情页（网络报文明细）按 id 取，避免把整段报文塞进列表。 */
    @Query("SELECT * FROM audit_log WHERE id = :id")
    suspend fun findById(id: Long): AuditLogEntity?

    @Insert
    suspend fun insert(entry: AuditLogEntity): Long

    @Query("DELETE FROM audit_log")
    suspend fun clear()

    /** 条数上限。`id DESC` 兜并列时间戳，免得同一毫秒的几条谁被裁随机。 */
    @Query("DELETE FROM audit_log WHERE id NOT IN (SELECT id FROM audit_log ORDER BY at DESC, id DESC LIMIT :keep)")
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
