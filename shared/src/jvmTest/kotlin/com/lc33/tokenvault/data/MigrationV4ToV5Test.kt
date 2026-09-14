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
                .addMigrations(VaultDatabase.MIGRATION_4_5, VaultDatabase.MIGRATION_5_6)
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
                .addMigrations(VaultDatabase.MIGRATION_5_6)
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

    /**
     * 用导出 schema 造库：建表语句与索引一律取自对应的 json。
     * 手写一遍 DDL 就测不出"迁移漏了某个索引"这类事故了——索引对不上同样会让 Room 报错。
     */
    private fun createDatabase(file: File, version: Int) {
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            exportedSchema(version).forEach { statement -> connection.execSQL(statement) }
            connection.execSQL("PRAGMA user_version = $version")
            connection.execSQL(
                """
                INSERT INTO providers (id, name, note, websiteUrl, websiteLatencyMs, websiteCheckedAt,
                    websiteError, groupId, color, pinned, sortOrder, createdAt, updatedAt)
                VALUES (1, 'p', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, 1, 1)
                """.trimIndent(),
            )
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