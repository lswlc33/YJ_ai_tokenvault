package com.lc33.tokenvault.data

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.lc33.tokenvault.data.entity.ModelChangeEntity
import com.lc33.tokenvault.domain.ModelChangeKind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v10 → v11：新增 `model_changes`（「模型变化」页的数据源）+ 回填 + 清掉那个假的"手选颜色"。
 *
 * 造库方式与 [MigrationV9ToV10Test] 一致——拿 Room 导出的 `schemas/10.json` 建真库，而不是
 * 手写 DDL：这条迁移自己写了一份 `CREATE TABLE`，而 Room 打开库时会拿实体算出的 schema 与
 * 实际库比对。**索引名漏一个、列类型差一点，都会在这条测试里抛异常**，而不是等真机上第一
 * 次打开时崩。
 *
 * 三件事各自要验，因为它们各是各的坑：
 *
 * 1. 表与两个索引就位（结构）。
 * 2. 回填只认 `source = 'discovered'` 的行，且时刻取 `firstSeenAt`（数据）。手动录的那批
 *    一旦混进来，页面就会把"我打字"念成"站点上新"。
 * 3. `color = 0` 清成 NULL、其它值原样（那条 UPDATE 是唯一一处"改动用户已有列"的动作，
 *    写错方向就等于把用户选的色全洗掉了）。
 */
class MigrationV10ToV11Test {

    @Test
    fun `v10 库升到 v11 后流水表就位、只回填发现行、默认色清成未选`() = runBlocking {
        val dir = createTempDirectory(prefix = "vault-migration-").toFile()
        try {
            val dbFile = File(dir, "vault.db")
            createV10Database(dbFile)

            val database = Room.databaseBuilder<VaultDatabase>(name = dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*VaultDatabase.ALL_MIGRATIONS.toTypedArray())
                .build()

            // 先走一次 DAO 触发懒打开与迁移（build() 是懒的）。
            val backfilled = database.modelChangeDao().observeAll().first()
            assertEquals(
                listOf("deepseek-chat", "gpt-4o"),
                backfilled.map { it.modelId }.sorted(),
                "只该回填 source=discovered 的两行，手动那行不该算站点上新",
            )
            assertEquals(
                listOf(ModelChangeKind.ADDED.wireName),
                backfilled.map { it.kind }.distinct(),
                "回填的全是新增",
            )
            assertEquals(
                listOf(111L, 222L),
                backfilled.map { it.at }.sorted(),
                "时刻要取 firstSeenAt，不是迁移执行的那一刻",
            )

            // 列与两个索引都在，索引名与 Room 全新库上生成的一致。
            val columns = columnNames(dbFile, "model_changes")
            assertTrue(
                listOf("id", "providerId", "keyId", "modelId", "protocol", "kind", "at").all { it in columns },
                "model_changes 缺列：$columns",
            )
            assertEquals(
                listOf(
                    "index_model_changes_at",
                    "index_model_changes_providerId_at",
                ),
                indexNames(dbFile, "model_changes").sorted(),
                "页面按时间读、按站点分组，两个索引缺一个就是全表扫描",
            )

            // 写一条下架再读回来：证明实体与表结构真的对得上（列缺 / 类型不符会在这步炸）。
            database.modelChangeDao().insert(
                ModelChangeEntity(
                    providerId = 1,
                    keyId = null,
                    modelId = "claude-3-5-sonnet",
                    protocol = "anthropic",
                    kind = ModelChangeKind.REMOVED.wireName,
                    at = 333,
                ),
            )
            val withRemoved = database.modelChangeDao().observeAll().first()
            assertEquals(3, withRemoved.size)
            val removed = withRemoved.last()
            assertEquals(ModelChangeKind.REMOVED.wireName, removed.kind)
            assertNull(removed.keyId, "keyId 可空：Key 轮换不该把这家的事件一起带走")

            // 颜色：0 是编辑页从没动过的默认值，清成 NULL 才会走生成色；3 是用户真选过的，留着。
            assertEquals(null, database.providerDao().findById(1L)?.color, "color=0 应清成未选")
            assertEquals(3, database.providerDao().findById(2L)?.color, "用户手选的颜色不能被动")

            // 升级前那两家供应商还在。
            assertEquals(2, database.providerDao().count())

            database.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 按 v10 导出 schema 造库：两家供应商（一家色是默认的 0、一家是手选的 3）+
     * 三行模型（两行发现的、一行手动录的）。
     */
    private fun createV10Database(file: File) {
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            exportedSchema(10).forEach { statement -> connection.execSQL(statement) }
            connection.execSQL("PRAGMA user_version = 10")
            connection.execSQL(
                """
                INSERT INTO providers (id, name, note, websiteUrl, checkWebsite, websiteLatencyMs,
                    websiteCheckedAt, websiteError, groupId, color, pinned, sortOrder,
                    createdAt, updatedAt)
                VALUES
                    (1, 'p1', NULL, NULL, 0, NULL, NULL, NULL, NULL, 0, 0, 0, 1, 1),
                    (2, 'p2', NULL, NULL, 0, NULL, NULL, NULL, NULL, 3, 0, 1, 1, 1)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO models (providerId, keyId, modelId, protocol, displayName, source,
                    discoveredVia, favorite, needsReview, catalogKey, probeState, lastOutcome,
                    probeDetail, latencyMs, probedAt, firstSeenAt, lastSeenAt, sortOrder)
                VALUES
                    (1, NULL, 'gpt-4o', 'chat', NULL, 'discovered', 'chat', 0, 0, NULL,
                        'unknown', 'skipped', NULL, NULL, NULL, 111, 111, 0),
                    (1, NULL, 'deepseek-chat', 'chat', NULL, 'discovered', 'chat', 0, 0, NULL,
                        'unknown', 'skipped', NULL, NULL, NULL, 222, 222, 1),
                    (1, NULL, 'my-own-alias', 'chat', NULL, 'manual', NULL, 0, 0, NULL,
                        'unknown', 'skipped', NULL, NULL, NULL, 333, NULL, 2)
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
