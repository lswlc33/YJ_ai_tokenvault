package com.lc33.tokenvault.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.dao.AppSettingDao
import com.lc33.tokenvault.data.dao.AuditLogDao
import com.lc33.tokenvault.data.dao.ClientProfileDao
import com.lc33.tokenvault.data.dao.GroupDao
import com.lc33.tokenvault.data.dao.KeySettingsDao
import com.lc33.tokenvault.data.dao.ModelCatalogDao
import com.lc33.tokenvault.data.dao.ModelDao
import com.lc33.tokenvault.data.dao.ProbeRunDao
import com.lc33.tokenvault.data.dao.ProviderAccountDao
import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.AppSettingEntity
import com.lc33.tokenvault.data.entity.AuditLogEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.KeySettingsEntity
import com.lc33.tokenvault.data.entity.ModelCatalogEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProbeRunEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.entity.ProviderEntity

@Database(
    entities = [
        GroupEntity::class,
        ProviderEntity::class,
        ApiKeyEntity::class,
        KeySettingsEntity::class,
        ProviderAccountEntity::class,
        ClientProfileEntity::class,
        ModelEntity::class,
        ModelCatalogEntity::class,
        ProbeRunEntity::class,
        AuditLogEntity::class,
        AppSettingEntity::class,
    ],
    version = VaultDatabase.VERSION,
    exportSchema = true,
)
@ConstructedBy(VaultDatabaseConstructor::class)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun groupDao(): GroupDao
    abstract fun providerDao(): ProviderDao
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun keySettingsDao(): KeySettingsDao
    abstract fun providerAccountDao(): ProviderAccountDao
    abstract fun clientProfileDao(): ClientProfileDao
    abstract fun modelDao(): ModelDao
    abstract fun modelCatalogDao(): ModelCatalogDao
    abstract fun probeRunDao(): ProbeRunDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        const val VERSION = 8
        const val FILE_NAME = "vault.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE providers ADD COLUMN reachabilityLatencyMs INTEGER")
                connection.execSQL("ALTER TABLE providers ADD COLUMN reachabilityCheckedAt INTEGER")
                connection.execSQL("ALTER TABLE providers ADD COLUMN reachabilityError TEXT")
                connection.execSQL("ALTER TABLE providers ADD COLUMN probeModelReachability INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("UPDATE providers SET probeModels = 0")

                connection.execSQL("ALTER TABLE api_keys ADD COLUMN balanceAmount REAL")
                connection.execSQL("ALTER TABLE api_keys ADD COLUMN balanceUsed REAL")
                connection.execSQL("ALTER TABLE api_keys ADD COLUMN balanceCurrency TEXT")
                connection.execSQL("ALTER TABLE api_keys ADD COLUMN balanceRaw TEXT")
                connection.execSQL("ALTER TABLE api_keys ADD COLUMN balanceCheckedAt INTEGER")
                connection.execSQL("ALTER TABLE api_keys ADD COLUMN balanceError TEXT")
                connection.execSQL(
                    """
                    UPDATE api_keys SET
                        balanceAmount = (
                            SELECT providers.balanceAmount FROM providers
                            WHERE providers.id = api_keys.providerId
                        ),
                        balanceUsed = (
                            SELECT providers.balanceUsed FROM providers
                            WHERE providers.id = api_keys.providerId
                        ),
                        balanceCurrency = (
                            SELECT providers.balanceCurrency FROM providers
                            WHERE providers.id = api_keys.providerId
                        ),
                        balanceRaw = (
                            SELECT providers.balanceRaw FROM providers
                            WHERE providers.id = api_keys.providerId
                        ),
                        balanceCheckedAt = (
                            SELECT providers.balanceCheckedAt FROM providers
                            WHERE providers.id = api_keys.providerId
                        ),
                        balanceError = (
                            SELECT providers.balanceError FROM providers
                            WHERE providers.id = api_keys.providerId
                        )
                    WHERE api_keys.isDefault = 1
                    """.trimIndent(),
                )

                connection.execSQL("DROP INDEX IF EXISTS idx_keys_default")
                connection.execSQL("ALTER TABLE provider_accounts ADD COLUMN loginMethods TEXT NOT NULL DEFAULT ''")
                connection.execSQL(
                    "ALTER TABLE models ADD COLUMN keyId INTEGER REFERENCES api_keys(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE",
                )
                connection.execSQL(
                    """
                    UPDATE models SET keyId = (
                        SELECT id FROM api_keys
                        WHERE api_keys.providerId = models.providerId AND api_keys.isDefault = 1
                        LIMIT 1
                    )
                    """.trimIndent(),
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_models_keyId ON models(keyId)")
                connection.execSQL("DROP INDEX IF EXISTS index_models_providerId_modelId_protocol")
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_models_providerId_keyId_modelId_protocol " +
                        "ON models(providerId, keyId, modelId, protocol)",
                )
            }
        }

        /**
         * v3：供应商降级为 Key 合集，行为配置全部下沉到 key_settings。
         *
         * 旧供应商上的连接 / 余额 / 探测配置复制到该供应商每一把 Key；
         * 旧默认 Key 排到最前，作为排序优先级的迁移结果。
         *
         * 末尾跑 [reconcileForeignKeys]：重建两张表期间外键是关着的（见那里的说明），
         * 级联到底有没有 fire、有没有留下悬空行，都由那一次清理 + 自检收口。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                // 这里原来写着一对 `PRAGMA foreign_keys = OFF / ON`：**在事务里改这个开关是
                // 无效的**（SQLite 明确忽略，迁移整段就跑在事务里），所以它从没起过作用。
                // 真正让重建表不被级联删空的是 Room 自己——它在整个迁移前后调
                // `setForeignKeyConstraintsEnabled(false)`。开关留着只会让人以为
                // "重建完外键就自动恢复成 ON 了"，那与 `reconcileForeignKeys` 的自检互相矛盾。

                // 1) key_settings：一把 Key 一行。
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS key_settings (
                        keyId INTEGER NOT NULL,
                        apiBaseUrl TEXT NOT NULL,
                        apiRoot TEXT NOT NULL,
                        apiVersion TEXT NOT NULL,
                        supportedProtocols TEXT NOT NULL,
                        pathOverrides TEXT NOT NULL,
                        authStyle TEXT NOT NULL,
                        allowInsecure INTEGER NOT NULL,
                        clientProfileId INTEGER,
                        timeoutSeconds INTEGER,
                        balanceKind TEXT NOT NULL,
                        balanceBaseUrl TEXT,
                        balanceUserId TEXT,
                        balanceTokenEnc BLOB,
                        balanceConfig TEXT NOT NULL,
                        quotaPerUnit REAL,
                        quotaCalibrated INTEGER NOT NULL,
                        probeEnabled INTEGER NOT NULL,
                        probeReachability INTEGER NOT NULL,
                        probeKeyValidity INTEGER NOT NULL,
                        probeBalance INTEGER NOT NULL,
                        probeModels INTEGER NOT NULL,
                        probeModelReachability INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(keyId),
                        FOREIGN KEY(keyId) REFERENCES api_keys(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(clientProfileId) REFERENCES client_profiles(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_key_settings_keyId ON key_settings(keyId)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_key_settings_clientProfileId " +
                        "ON key_settings(clientProfileId)",
                )
                connection.execSQL(
                    """
                    INSERT INTO key_settings (
                        keyId, apiBaseUrl, apiRoot, apiVersion, supportedProtocols, pathOverrides,
                        authStyle, allowInsecure, clientProfileId, timeoutSeconds, balanceKind,
                        balanceBaseUrl, balanceUserId, balanceTokenEnc, balanceConfig, quotaPerUnit,
                        quotaCalibrated, probeEnabled, probeReachability, probeKeyValidity,
                        probeBalance, probeModels, probeModelReachability, updatedAt
                    )
                    SELECT
                        k.id, p.apiBaseUrl, p.apiRoot, p.apiVersion, p.supportedProtocols,
                        p.pathOverrides, p.authStyle, p.allowInsecure, p.clientProfileId,
                        p.timeoutSeconds, p.balanceKind, p.balanceBaseUrl, p.balanceUserId,
                        p.balanceTokenEnc, p.balanceConfig, p.quotaPerUnit, p.quotaCalibrated,
                        p.probeEnabled, p.probeReachability, p.probeKeyValidity, p.probeBalance,
                        p.probeModels, p.probeModelReachability, k.updatedAt
                    FROM api_keys k
                    JOIN providers p ON p.id = k.providerId
                    """.trimIndent(),
                )
                // 下一段要 `DROP TABLE api_keys`，而 `key_settings.keyId` 是 `ON DELETE CASCADE`。
                // 外键在迁移期间是不是真的关着，取决于运行时（见 `reconcileForeignKeys`），
                // 所以先留一份 seed，末尾把被连带删掉的行原样补回来——配置不能靠赌。
                connection.execSQL("CREATE TEMP TABLE key_settings_seed AS SELECT * FROM key_settings")
                // 同一份"不靠赌"必须覆盖到这两张子表：它们同样是 `ON DELETE CASCADE` 的下游
                // （`models.keyId` → api_keys，`provider_accounts.providerId` → providers），
                // 而本跳不重建它们，所以 `SELECT *` 就够。只 seed `key_settings` 的话，级联真
                // 发生时补回来的只有配置——模型列表与账号会静默消失（4→5 那份就是两张都留）。
                connection.execSQL("CREATE TEMP TABLE models_seed AS SELECT * FROM models")
                connection.execSQL("CREATE TEMP TABLE provider_accounts_seed AS SELECT * FROM provider_accounts")

                // 2) api_keys：去掉 isDefault，补 note，保留原 id / 外键 / 探测结果。
                connection.execSQL(
                    """
                    CREATE TABLE api_keys_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        providerId INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        note TEXT NOT NULL,
                        secretEnc BLOB NOT NULL,
                        fingerprint TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        health TEXT NOT NULL,
                        lastOutcome TEXT NOT NULL,
                        healthDetail TEXT,
                        httpStatus INTEGER,
                        latencyMs INTEGER,
                        checkedAt INTEGER,
                        okAt INTEGER,
                        balanceAmount REAL,
                        balanceUsed REAL,
                        balanceCurrency TEXT,
                        balanceRaw TEXT,
                        balanceCheckedAt INTEGER,
                        balanceError TEXT,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(providerId) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO api_keys_new (
                        id, providerId, label, note, secretEnc, fingerprint, enabled, health,
                        lastOutcome, healthDetail, httpStatus, latencyMs, checkedAt, okAt,
                        balanceAmount, balanceUsed, balanceCurrency, balanceRaw,
                        balanceCheckedAt, balanceError, sortOrder, createdAt, updatedAt
                    )
                    SELECT
                        id, providerId, label, '', secretEnc, fingerprint, enabled, health,
                        lastOutcome, healthDetail, httpStatus, latencyMs, checkedAt, okAt,
                        balanceAmount, balanceUsed, balanceCurrency, balanceRaw,
                        balanceCheckedAt, balanceError,
                        CASE WHEN isDefault = 1 THEN -1 ELSE sortOrder END,
                        createdAt, updatedAt
                    FROM api_keys
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE api_keys")
                connection.execSQL("ALTER TABLE api_keys_new RENAME TO api_keys")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_api_keys_providerId_sortOrder_id " +
                        "ON api_keys(providerId, sortOrder, id)",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_api_keys_providerId_fingerprint " +
                        "ON api_keys(providerId, fingerprint)",
                )

                // 3) providers：只保留合集信息与官网连通性。
                connection.execSQL(
                    """
                    CREATE TABLE providers_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        note TEXT,
                        websiteUrl TEXT,
                        websiteLatencyMs INTEGER,
                        websiteCheckedAt INTEGER,
                        websiteError TEXT,
                        groupId INTEGER,
                        color INTEGER,
                        pinned INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(groupId) REFERENCES groups(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO providers_new (
                        id, name, note, websiteUrl, websiteLatencyMs, websiteCheckedAt,
                        websiteError, groupId, color, pinned, sortOrder, createdAt, updatedAt
                    )
                    SELECT
                        id, name, note, websiteUrl, NULL, NULL, NULL,
                        groupId, color, pinned, sortOrder, createdAt, updatedAt
                    FROM providers
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE providers")
                connection.execSQL("ALTER TABLE providers_new RENAME TO providers")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_providers_groupId ON providers(groupId)")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_providers_pinned_sortOrder_id " +
                        "ON providers(pinned, sortOrder, id)",
                )

                // 补回被 `DROP TABLE api_keys` 连带级联掉的配置行（seed 的列序与 key_settings 一致）。
                connection.execSQL(
                    """
                    INSERT INTO key_settings
                    SELECT * FROM key_settings_seed s
                    WHERE s.keyId NOT IN (SELECT keyId FROM key_settings)
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE key_settings_seed")
                // 同上：按主键判缺，两边都在的行不会被这一句重复插。
                connection.execSQL(
                    """
                    INSERT INTO models
                    SELECT * FROM models_seed s
                    WHERE s.id NOT IN (SELECT id FROM models)
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE models_seed")
                connection.execSQL(
                    """
                    INSERT INTO provider_accounts
                    SELECT * FROM provider_accounts_seed s
                    WHERE s.id NOT IN (SELECT id FROM provider_accounts)
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE provider_accounts_seed")

                connection.reconcileForeignKeys("2->3")
            }
        }

        /** v4：把最近一轮探测拆成供应商级与密钥级两组进度，首页分别展示。 */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                listOf(
                    "providerTotal",
                    "providerDone",
                    "providerOk",
                    "providerFail",
                    "keyTotal",
                    "keyDone",
                    "keyOk",
                    "keyFail",
                ).forEach { column ->
                    connection.execSQL("ALTER TABLE probe_runs ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                }
                connection.execSQL(
                    "ALTER TABLE key_settings ADD COLUMN probeQuickModel INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL("UPDATE key_settings SET probeQuickModel = probeModelReachability")
            }
        }

        /**
         * v5：去掉 Key 与模型的启用状态。
         *
         * 「不想用了」和「删掉」是同一件事：留着一个谁都不用的开关，只会让列表、计数与
         * 探测计划在同一个问题上各说各话。所以两张表都去掉 `enabled` 列。
         *
         * 旧数据里 `enabled = 0` 的行**不删**，只是不再有这层含义——它们会重新参与探测与展示。
         * 迁移里删用户数据是不可接受的：真不要了，用户自己在列表里删。
         * （`reconcileForeignKeys` 删的是**父行已经不在了**的悬空行：那种行任何查询都取不到，
         * 留着只会让末尾的外键自检把整次升级判成失败。）
         *
         * minSdk 33 的 SQLite 没有 `ALTER TABLE ... DROP COLUMN`（3.35 才有），
         * 所以照 v3 的做法重建两张表。
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                // 与 v3 同一处订正：事务里改 `PRAGMA foreign_keys` 无效，Room 已在迁移整段
                // 前后关掉外键约束，这里不再重复写那两行。

                // 0) 先留两份 seed：下面 `DROP TABLE api_keys` 的级联口径由运行时决定
                //    （`key_settings.keyId` 与 `models.keyId` 都是 `ON DELETE CASCADE`），
                //    万一真的级联了，末尾还能原样补回，而不是把用户的配置与模型列表陪葬。
                //    `models` 那份必须显式列列：v4 的 models 还有 `enabled`，重建后没有。
                connection.execSQL("CREATE TEMP TABLE key_settings_seed AS SELECT * FROM key_settings")
                connection.execSQL(
                    """
                    CREATE TEMP TABLE models_seed AS
                    SELECT id, providerId, keyId, modelId, protocol, displayName, source,
                           discoveredVia, favorite, needsReview, catalogKey, probeState,
                           lastOutcome, probeDetail, latencyMs, probedAt, firstSeenAt,
                           lastSeenAt, sortOrder
                    FROM models
                    """.trimIndent(),
                )

                // 1) api_keys：去掉 enabled。
                connection.execSQL(
                    """
                    CREATE TABLE api_keys_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        providerId INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        note TEXT NOT NULL,
                        secretEnc BLOB NOT NULL,
                        fingerprint TEXT NOT NULL,
                        health TEXT NOT NULL,
                        lastOutcome TEXT NOT NULL,
                        healthDetail TEXT,
                        httpStatus INTEGER,
                        latencyMs INTEGER,
                        checkedAt INTEGER,
                        okAt INTEGER,
                        balanceAmount REAL,
                        balanceUsed REAL,
                        balanceCurrency TEXT,
                        balanceRaw TEXT,
                        balanceCheckedAt INTEGER,
                        balanceError TEXT,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(providerId) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO api_keys_new (
                        id, providerId, label, note, secretEnc, fingerprint, health,
                        lastOutcome, healthDetail, httpStatus, latencyMs, checkedAt, okAt,
                        balanceAmount, balanceUsed, balanceCurrency, balanceRaw,
                        balanceCheckedAt, balanceError, sortOrder, createdAt, updatedAt
                    )
                    SELECT
                        id, providerId, label, note, secretEnc, fingerprint, health,
                        lastOutcome, healthDetail, httpStatus, latencyMs, checkedAt, okAt,
                        balanceAmount, balanceUsed, balanceCurrency, balanceRaw,
                        balanceCheckedAt, balanceError, sortOrder, createdAt, updatedAt
                    FROM api_keys
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE api_keys")
                connection.execSQL("ALTER TABLE api_keys_new RENAME TO api_keys")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_api_keys_providerId_sortOrder_id " +
                        "ON api_keys(providerId, sortOrder, id)",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_api_keys_providerId_fingerprint " +
                        "ON api_keys(providerId, fingerprint)",
                )

                // 2) models：去掉 enabled。
                connection.execSQL(
                    """
                    CREATE TABLE models_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        providerId INTEGER NOT NULL,
                        keyId INTEGER,
                        modelId TEXT NOT NULL,
                        protocol TEXT NOT NULL,
                        displayName TEXT,
                        source TEXT NOT NULL,
                        discoveredVia TEXT,
                        favorite INTEGER NOT NULL,
                        needsReview INTEGER NOT NULL,
                        catalogKey TEXT,
                        probeState TEXT NOT NULL,
                        lastOutcome TEXT NOT NULL,
                        probeDetail TEXT,
                        latencyMs INTEGER,
                        probedAt INTEGER,
                        firstSeenAt INTEGER NOT NULL,
                        lastSeenAt INTEGER,
                        sortOrder INTEGER NOT NULL,
                        FOREIGN KEY(providerId) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(keyId) REFERENCES api_keys(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO models_new (
                        id, providerId, keyId, modelId, protocol, displayName, source,
                        discoveredVia, favorite, needsReview, catalogKey, probeState,
                        lastOutcome, probeDetail, latencyMs, probedAt, firstSeenAt,
                        lastSeenAt, sortOrder
                    )
                    SELECT
                        id, providerId, keyId, modelId, protocol, displayName, source,
                        discoveredVia, favorite, needsReview, catalogKey, probeState,
                        lastOutcome, probeDetail, latencyMs, probedAt, firstSeenAt,
                        lastSeenAt, sortOrder
                    FROM models
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE models")
                connection.execSQL("ALTER TABLE models_new RENAME TO models")
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_models_providerId_keyId_modelId_protocol " +
                        "ON models(providerId, keyId, modelId, protocol)",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_models_keyId ON models(keyId)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_models_catalogKey ON models(catalogKey)")

                // 补回被级联带走的行（按主键判缺，两边都在的行走不到这里）。
                connection.execSQL(
                    """
                    INSERT INTO key_settings
                    SELECT * FROM key_settings_seed s
                    WHERE s.keyId NOT IN (SELECT keyId FROM key_settings)
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO models
                    SELECT * FROM models_seed s
                    WHERE s.id NOT IN (SELECT id FROM models)
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE key_settings_seed")
                connection.execSQL("DROP TABLE models_seed")

                connection.reconcileForeignKeys("4->5")
            }
        }

        /**
         * v6：日志带上网络报文明细。
         *
         * 三列都是可空的、只给 HTTP 类日志填，所以是三条 `ADD COLUMN`——不必重建表
         * （重建会连索引一起重来，而这次没有任何列要删或要改类型）。
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE audit_log ADD COLUMN requestUrl TEXT")
                connection.execSQL("ALTER TABLE audit_log ADD COLUMN requestBody TEXT")
                connection.execSQL("ALTER TABLE audit_log ADD COLUMN responseBody TEXT")
            }
        }

        /**
         * v7：供应商多一个「允许检查官网连通性」。
         *
         * 一条 `ADD COLUMN` 即可——单列，没有列要删或改类型，不必重建表。
         * **默认 0（关）**：老库里已经填了官网地址的那些家，不会因为升级就突然开始被 ping；
         * 用户在新版本里明确打开之后才发请求。
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE providers ADD COLUMN checkWebsite INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v8：清掉历史刷新写出的重复模型行。**纯数据，schema 一字未动**。
         *
         * 来由：`refreshModelsInner` 以前复用探测计划铺任务，而计划里同一把 Key 有
         * "每个协议一条 L1 + 一条 L2"，它们的 url 都是同一个 `modelsUrl`（模型列表端点
         * 不分协议）。同一份响应于是被按协议各归一桶、各写一套行；`ModelMerger` 的
         * "消失即删"限定 `discoveredVia == 本轮协议`，两套行互不清理，重复永久留在库里，
         * 界面上每个模型出现两遍。请求侧已经在 `ProbePlanBuilder.buildModelListTasks`
         * 收口成每把 Key 一次，这里补上存量。
         *
         * 只动 `source = 'discovered'` 的行（手动录入的永不被自动同步改动，红线 13），
         * 同组保留 id 最小的那条——即第一次发现它的那一行，协议归属也就是它。
         * 与手动行并存的发现行**不删**：这条迁移要解决的是"刷新写出来的重复"，那才是它的
         * 责任范围；手动行旁边那条发现行属于另一件事，删错了直接毁掉用户手敲的数据，
         * 而它在界面上本来就靠协议尾巴区分得开。
         *
         * `keyId IS models.keyId`：SQLite 的 `IS` 是 NULL 安全的等值比较。`keyId` 可空
         * （备份恢复链路会留空值），用 `=` 的话两侧都是 NULL 时匹配不上，那些重复组会漏掉。
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    DELETE FROM models
                    WHERE source = 'discovered'
                      AND EXISTS (
                          SELECT 1 FROM models AS keep
                          WHERE keep.source = 'discovered'
                            AND keep.providerId = models.providerId
                            AND keep.keyId IS models.keyId
                            AND keep.modelId = models.modelId
                            AND keep.id < models.id
                      )
                    """.trimIndent(),
                )
            }
        }
    }
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object VaultDatabaseConstructor : RoomDatabaseConstructor<VaultDatabase>

/**
 * 重建表之后收一次外键的尾：先把**父行已经不存在**的悬空行清掉，再用
 * `PRAGMA foreign_key_check` 自检；仍有违例就让这条迁移抛异常失败。
 *
 * 为什么事后要自己收：整段迁移跑在事务里，而 `PRAGMA foreign_keys` 在事务里改是**无效**的
 * （SQLite 直接忽略）——外键开还是关完全由 Room 在迁移前后决定。于是 `DROP TABLE api_keys`
 * 到底有没有把 `key_settings` / `models` 一起按 `ON DELETE CASCADE` 级联掉，取决于运行时的
 * SQLite 版本与驱动实现，不是这条迁移里能写死的。与其赌它，不如事后核对一次并补上缺口
 * （缺口的来源是各迁移开头的 seed 临时表）。
 *
 * 这里删的不是用户数据：所有读路径都从 `providers` / `api_keys` 起 JOIN，父行没了的子行
 * 在界面上根本取不到，留着只会在下一次探测时以"配置行不存在"的形式回来。
 */
private fun SQLiteConnection.reconcileForeignKeys(from: String) {
    execSQL("DELETE FROM api_keys WHERE providerId NOT IN (SELECT id FROM providers)")
    execSQL("DELETE FROM key_settings WHERE keyId NOT IN (SELECT id FROM api_keys)")
    execSQL("DELETE FROM provider_accounts WHERE providerId NOT IN (SELECT id FROM providers)")
    execSQL(
        """
        DELETE FROM models
        WHERE providerId NOT IN (SELECT id FROM providers)
           OR (keyId IS NOT NULL AND keyId NOT IN (SELECT id FROM api_keys))
        """.trimIndent(),
    )
    execSQL(
        "UPDATE providers SET groupId = NULL " +
            "WHERE groupId IS NOT NULL AND groupId NOT IN (SELECT id FROM groups)",
    )
    execSQL(
        "UPDATE key_settings SET clientProfileId = NULL " +
            "WHERE clientProfileId IS NOT NULL AND clientProfileId NOT IN (SELECT id FROM client_profiles)",
    )
    if (hasForeignKeyViolations()) {
        throw IllegalStateException("foreign key violations remain after migration $from")
    }
}

/**
 * `PRAGMA foreign_key_check` 有没有输出行 = 有没有违例。
 *
 * 用 `prepare` + `step` 而不是表值函数 `pragma_foreign_key_check()`：Android 系统那份 SQLite
 * 不一定编进了 pragma 表值函数（编译选项问题），而 PRAGMA 语句本身一定能 prepare。
 */
private fun SQLiteConnection.hasForeignKeyViolations(): Boolean {
    val statement = prepare("PRAGMA foreign_key_check")
    try {
        return statement.step()
    } finally {
        statement.close()
    }
}
