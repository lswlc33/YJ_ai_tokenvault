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
        const val VERSION = 3
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
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("PRAGMA foreign_keys = OFF")

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

                connection.execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object VaultDatabaseConstructor : RoomDatabaseConstructor<VaultDatabase>
