package com.lc33.tokenvault.data

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.lc33.tokenvault.data.entity.BalanceHistoryEntity
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v9 → v10 迁移：新增 `balance_history`（用量变化报告的数据源）。
 *
 * 造库方式与 [MigrationV8ToV9Test] 一致——用 Room 导出的 `schemas/9.json` 建表，而不是
 * 手写 DDL。这样"迁移漏了某个索引 / 列类型不符"这类事故会在 Room 打开库、拿实际 schema
 * 与 v10 实体比对时抛异常，而不是等到真机上第一次按供应商查历史才发现。
 *
 * 这条迁移是纯建表、无回填（新表在老库里不存在），所以重点验三件事：表建起来了、
 * 两个索引名与 Room 生成的一致、老数据（供应商行）原样还在。
 */
class MigrationV9ToV10Test {

    @Test
    fun `v9 库升到 v10 后 balance_history 就位且老数据不动`() = runBlocking {
        val dir = createTempDirectory(prefix = "vault-migration-").toFile()
        try {
            val dbFile = File(dir, "vault.db")
            createV9Database(dbFile)

            val database = Room.databaseBuilder<VaultDatabase>(name = dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(VaultDatabase.MIGRATION_9_10)
                .build()

            // 先走一次 DAO 触发懒打开与迁移（同 v8→v9 那条注释：build() 是懒的）。
            assertEquals(0, database.balanceHistoryDao().count(), "新表升级后必为空")

            // 写一行、读回来：证明表结构与实体对得上（列缺 / 类型不符会在这步炸）。
            database.balanceHistoryDao().insert(
                BalanceHistoryEntity(
                    providerId = 1,
                    keyId = 1,
                    amount = 42.0,
                    used = 5.0,
                    currency = "USD",
                    capturedAt = 100,
                ),
            )
            assertEquals(1, database.balanceHistoryDao().count())
            assertEquals(42.0, database.balanceHistoryDao().observeAll().first().single().amount)

            // 列与两个索引都在，索引名与 Room 全新库上生成的一致。
            val columns = columnNames(dbFile, "balance_history")
            assertTrue(
                listOf("id", "providerId", "keyId", "amount", "used", "currency", "capturedAt")
                    .all { it in columns },
                "balance_history 缺列：$columns",
            )
            assertEquals(
                listOf(
                    "index_balance_history_keyId_capturedAt",
                    "index_balance_history_providerId_capturedAt",
                ),
                indexNames(dbFile, "balance_history").sorted(),
                "两个复合索引都要有，否则报告按供应商/按 Key 取历史是全表扫描",
            )

            // 升级前那家供应商还在。
            assertEquals(1, database.providerDao().count())

            database.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 按 v9 导出 schema 造库，灌一家供应商（balance_history 的 providerId 外键要有父行可指）。 */
    private fun createV9Database(file: File) {
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            exportedSchema(9).forEach { statement -> connection.execSQL(statement) }
            connection.execSQL("PRAGMA user_version = 9")
            connection.execSQL(
                """
                INSERT INTO providers (id, name, note, websiteUrl, checkWebsite, websiteLatencyMs,
                    websiteCheckedAt, websiteError, groupId, color, pinned, sortOrder,
                    createdAt, updatedAt)
                VALUES (1, 'p', NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, 0, 0, 1, 1)
                """.trimIndent(),
            )
        } finally {
            connection.close()
        }
    }

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
            statements += table.getValue("createSql").jsonPrimitive.content
                .replace("\${TABLE_NAME}", name)
            table["indices"]?.jsonArray?.forEach { index ->
                index.jsonObject["createSql"]?.jsonPrimitive?.content?.let { sql ->
                    statements += sql.replace("\${TABLE_NAME}", name)
                }
            }
        }
        return statements
    }

    private fun columnNames(file: File, table: String): List<String> =
        queryStrings(file, "PRAGMA table_info($table)", 1)

    private fun indexNames(file: File, table: String): List<String> =
        queryStrings(file, "PRAGMA index_list($table)", 1).filterNot { it.startsWith("sqlite_autoindex") }

    private fun queryStrings(file: File, sql: String, column: Int): List<String> {
        val values = mutableListOf<String>()
        val connection: SQLiteConnection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            val statement = connection.prepare(sql)
            try {
                while (statement.step()) {
                    values += statement.getText(column)
                }
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
        return values
    }
}
