package com.lc33.tokenvault.data

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v4 → v5 迁移的落地验证（JVM，Windows 上就能跑）。
 *
 * 这条迁移是**重建两张表**（minSdk 33 的 SQLite 没有 `DROP COLUMN`），重建最怕两件事：
 * schema 对不上、数据被丢掉。所以这里不用 FakeDao、也不手写 DDL，而是拿 Room 自己导出的
 * `schemas/4.json` 造一个真的 v4 库，灌一行 Key 和一行模型，再用 Room 打开跑迁移——
 * Room 会在迁移后校验整库 schema，对不上就抛异常；数据是否还在由断言兜住。
 *
 * 为什么不只留 androidTest 的那条：`VaultDatabaseMigrationTest` 是 instrumented 测试，
 * 没有设备时根本跑不到；这条让同一个迁移在每次 `:shared:jvmTest` 都被验一遍。
 */
class MigrationV4ToV5Test {

    @Test
    fun `v4 库迁到 v5 后 schema 有效、行与列值原样`() = runBlocking {
        val dir = createTempDirectory(prefix = "vault-migration-").toFile()
        try {
            val dbFile = File(dir, "vault.db")
            createDatabase(dbFile, version = 4)

            // Room 打开时跑迁移；schema 对不上会在这里抛异常。链式跑到最新版本——
            // 真实升级也是这样一路过来的，只测一跳会漏掉"5→6 在 4→5 之后还能不能跑"。
            val database = Room.databaseBuilder<VaultDatabase>(name = dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(
                    VaultDatabase.MIGRATION_4_5,
                    VaultDatabase.MIGRATION_5_6,
                    VaultDatabase.MIGRATION_6_7,
                )
                .build()

            val key = database.apiKeyDao().findRaw(1L)
            assertEquals("k", key?.label)
            assertEquals("n", key?.note)
            assertEquals("fp", key?.fingerprint)
            assertEquals(12L, key?.latencyMs)

            val models = database.modelDao().findByProvider(1L)
            assertEquals(1, models.size)
            assertEquals("gpt-5.6-sol", models.single().modelId)
            assertEquals("discovered", models.single().source)

            // 重建过的表里不该再有 enabled。
            val columns = columnNames(dbFile, "api_keys")
            assertTrue("enabled" !in columns, "api_keys.enabled 应已删除，实际列：$columns")

            database.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `v5 库迁到 v6 后日志多了三列报文`() = runBlocking {
        val dir = createTempDirectory(prefix = "vault-migration-").toFile()
        try {
            val dbFile = File(dir, "vault.db")
            createDatabase(dbFile, version = 5)
            val database = Room.databaseBuilder<VaultDatabase>(name = dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(VaultDatabase.MIGRATION_5_6, VaultDatabase.MIGRATION_6_7)
                .build()

            // 老日志（迁移前就写好的）三列是 null，不该被迁移搞坏。
            val old = database.auditLogDao().findById(1L)
            assertEquals("http GET api.test/v1/models -> 200", old?.message)
            assertEquals(null, old?.requestUrl)

            database.auditLogDao().insert(
                com.lc33.tokenvault.data.entity.AuditLogEntity(
                    at = 2,
                    level = "info",
                    category = "http",
                    message = "http POST api.test/v1/chat -> 200",
                    requestUrl = "api.test/v1/chat",
                    requestBody = "{prompt}",
                    responseBody = "{reply}",
                ),
            )
            val fresh = database.auditLogDao().findById(2L)
            assertEquals("{prompt}", fresh?.requestBody)
            assertEquals("{reply}", fresh?.responseBody)

            database.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `v6 库迁到 v7 后多了 checkWebsite 且默认关`() = runBlocking {
        val dir = createTempDirectory(prefix = "vault-migration-v7-").toFile()
        try {
            val dbFile = File(dir, "vault.db")
            createDatabase(dbFile, version = 6)
            val database = Room.databaseBuilder<VaultDatabase>(name = dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(VaultDatabase.MIGRATION_6_7)
                .build()

            // 老数据原样保留，新列按"默认关"落库——升级不该让老供应商突然开始被 ping。
            val provider = database.providerDao().findById(1L)
            assertEquals("p", provider?.name)
            assertEquals(false, provider?.checkWebsite)

            database.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 4→5 那两条新加的机制：**开头留 seed、末尾 reconcile**。
     *
     * 这一段是本轮改动里最危险、又最容易被"迁移跑通了"三个字糊过去的一处——它赌的是
     * "迁移期间外键到底开没开"，而那由运行时决定。所以两种结果都要钉住：
     * 被级联带走的配置行要原样补回，父行已经不在了的孤儿行要清掉（清不掉的话末尾那次
     * `PRAGMA foreign_key_check` 自检会把整次升级判成失败，测试直接红）。
     */
    @Test
    fun `v4 重建两张表不带走 key_settings，孤儿行由 reconcile 清掉`() = runBlocking {
        val dir = createTempDirectory(prefix = "vault-migration-reconcile-").toFile()
        try {
            val dbFile = File(dir, "vault.db")
            createDatabase(dbFile, version = 4)
            // 造库用的是裸连接，SQLite 的 `PRAGMA foreign_keys` 默认关着，所以 999 那一行
            // （父 Key 早就不在了）插得进去——这正是迁移开始前库里可能有的样子。
            insertKeySettings(dbFile, keyId = 1L, apiRoot = "https://api.example.com")
            insertKeySettings(dbFile, keyId = 999L, apiRoot = "https://orphan.example.com")

            val database = Room.databaseBuilder<VaultDatabase>(name = dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(
                    VaultDatabase.MIGRATION_4_5,
                    VaultDatabase.MIGRATION_5_6,
                    VaultDatabase.MIGRATION_6_7,
                )
                .build()

            val settings = database.keySettingsDao()
            assertEquals(
                "https://api.example.com",
                settings.findByKey(1L)?.apiRoot,
                "DROP TABLE api_keys 的级联不许把 Key 的配置一起带走",
            )
            assertEquals(null, settings.findByKey(999L), "取不到的孤儿行要清掉，别留给自检")
            assertEquals(listOf(1L), settings.findAll().map { it.keyId })
            // models 那一行也还挂在原来那把 Key 上（seed 同样补了它）
            assertEquals(1, database.modelDao().findByProvider(1L).size)

            database.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 用裸连接插一行 `key_settings`。
     *
     * 不走 DAO：DAO 属于当前 schema（v7），在迁移**之前**插入必须按 v4 那份列定义来写，
     * 与 [createDatabase] 里其余几条 INSERT 同一个理由。
     */
    private fun insertKeySettings(file: File, keyId: Long, apiRoot: String) {
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            connection.execSQL(
                """
                INSERT INTO key_settings (keyId, apiBaseUrl, apiRoot, apiVersion, supportedProtocols,
                    pathOverrides, authStyle, allowInsecure, clientProfileId, timeoutSeconds,
                    balanceKind, balanceBaseUrl, balanceUserId, balanceTokenEnc, balanceConfig,
                    quotaPerUnit, quotaCalibrated, probeEnabled, probeReachability, probeKeyValidity,
                    probeBalance, probeModels, probeModelReachability, probeQuickModel, updatedAt)
                VALUES ($keyId, '$apiRoot/v1', '$apiRoot', 'v1', 'chat', '{}', 'auto', 0, NULL, NULL,
                    'none', NULL, NULL, NULL, '{}', NULL, 0, 1, 1, 1, 1, 0, 0, 0, 1)
                """.trimIndent(),
            )
        } finally {
            connection.close()
        }
    }

    /**
     * 用导出 schema 造库：建表语句与索引一律取自对应的 json。
     * 手写一遍 DDL 就测不出"迁移漏了某个索引"这类事故了——索引对不上同样会让 Room 报错。
     */
    private fun createDatabase(file: File, version: Int) {
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            exportedSchema(version).forEach { statement -> connection.execSQL(statement) }
            connection.execSQL("PRAGMA user_version = $version")
            // v7 起 providers 多了 checkWebsite；按版本给对列，否则插不进去。
            if (version >= 7) {
                connection.execSQL(
                    """
                    INSERT INTO providers (id, name, note, websiteUrl, checkWebsite, websiteLatencyMs,
                        websiteCheckedAt, websiteError, groupId, color, pinned, sortOrder,
                        createdAt, updatedAt)
                    VALUES (1, 'p', NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, 0, 0, 1, 1)
                    """.trimIndent(),
                )
            } else {
                connection.execSQL(
                    """
                    INSERT INTO providers (id, name, note, websiteUrl, websiteLatencyMs, websiteCheckedAt,
                        websiteError, groupId, color, pinned, sortOrder, createdAt, updatedAt)
                    VALUES (1, 'p', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, 1, 1)
                    """.trimIndent(),
                )
            }
            if (version <= 4) {
                // v4 的 api_keys 还有 enabled 列，v5 起没有；造库时按版本给对。
                connection.execSQL(
                    """
                    INSERT INTO api_keys (id, providerId, label, note, secretEnc, fingerprint, enabled,
                        health, lastOutcome, healthDetail, httpStatus, latencyMs, checkedAt, okAt,
                        balanceAmount, balanceUsed, balanceCurrency, balanceRaw, balanceCheckedAt,
                        balanceError, sortOrder, createdAt, updatedAt)
                    VALUES (1, 1, 'k', 'n', X'00', 'fp', 0, 'ok', 'success', NULL, 200, 12, 3, 3,
                        NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, 1)
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO models (id, providerId, keyId, modelId, protocol, displayName, source,
                        discoveredVia, enabled, favorite, needsReview, catalogKey, probeState,
                        lastOutcome, probeDetail, latencyMs, probedAt, firstSeenAt, lastSeenAt, sortOrder)
                    VALUES (1, 1, 1, 'gpt-5.6-sol', 'chat', NULL, 'discovered', 'chat', 0, 0, 0, NULL,
                        'unknown', 'skipped', NULL, NULL, NULL, 1, 1, 0)
                    """.trimIndent(),
                )
            } else {
                connection.execSQL(
                    """
                    INSERT INTO api_keys (id, providerId, label, note, secretEnc, fingerprint, health,
                        lastOutcome, healthDetail, httpStatus, latencyMs, checkedAt, okAt,
                        balanceAmount, balanceUsed, balanceCurrency, balanceRaw, balanceCheckedAt,
                        balanceError, sortOrder, createdAt, updatedAt)
                    VALUES (1, 1, 'k', 'n', X'00', 'fp', 'ok', 'success', NULL, 200, 12, 3, 3,
                        NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, 1)
                    """.trimIndent(),
                )
            }
            // v6 之前没有报文列，那条老日志按当时的列插入。
            connection.execSQL(
                """
                INSERT INTO audit_log (id, at, level, category, providerId, keyId, runId, message, detail)
                VALUES (1, 1, 'info', 'http', NULL, NULL, NULL,
                    'http GET api.test/v1/models -> 200', 'latency=12ms')
                """.trimIndent(),
            )
        } finally {
            connection.close()
        }
    }

    /** 从导出的 schema 里取出建表语句与索引语句。 */
    private fun exportedSchema(version: Int): List<String> {
        val path = "com.lc33.tokenvault.data.VaultDatabase/$version.json"
        val file = listOf(File("schemas", path), File("shared", "schemas/$path"))
            .firstOrNull { it.exists() }
            ?: error("找不到 v$version 导出 schema（$path），无法造库")
        val database = Json.parseToJsonElement(file.readText())
            .jsonObject.getValue("database")
            .jsonObject
        val statements = mutableListOf<String>()
        database.getValue("entities").jsonArray.forEach { entity ->
            val table = entity.jsonObject
            val name = table.getValue("tableName").jsonPrimitive.content
            statements += table.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name)
            table["indices"]?.jsonArray?.forEach { index ->
                index.jsonObject["createSql"]?.jsonPrimitive?.content?.let { sql ->
                    statements += sql.replace("\${TABLE_NAME}", name)
                }
            }
        }
        return statements
    }

    private fun columnNames(file: File, table: String): List<String> {
        val names = mutableListOf<String>()
        val connection: SQLiteConnection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            val statement = connection.prepare("PRAGMA table_info($table)")
            try {
                while (statement.step()) {
                    names += statement.getText(1)
                }
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
        return names
    }

    private companion object {
        const val SCHEMA_PATH = "com.lc33.tokenvault.data.VaultDatabase/4.json"
    }
}