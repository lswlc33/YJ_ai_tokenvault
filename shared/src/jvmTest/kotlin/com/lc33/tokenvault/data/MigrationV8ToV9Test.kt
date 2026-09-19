package com.lc33.tokenvault.data

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.lc33.tokenvault.data.entity.ModelCatalogEntity
import com.lc33.tokenvault.data.entity.ModelVendorEntity
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v8 → v9 迁移：把 models.dev 目录从「表建好了但没人写」变成真的能用。
 *
 * 造库方式和 [MigrationV4ToV5Test] 一样——拿 Room 自己导出的 `schemas/8.json` 建表，
 * 而不是手写一遍 DDL。这是有意的：v9 这条迁移加了 9 列、建了一张表、补了 4 个索引，
 * 手写 DDL 的话「迁移漏了某个索引」这种事故根本测不出来，只会在新版本第一次按厂商分组时
 * 表现成一次全表扫描。Room 在迁移后拿实际 schema 和实体定义比对，列缺失、类型不符、
 * nullability 不符都会在这里抛异常。
 *
 * 老库里 `model_catalog` 是**空表**（自 v1 就在，但从没有写入路径），所以这条迁移
 * 不需要任何回填；这里也顺手把「空表加 NOT NULL 列不报错」验一遍。
 */
class MigrationV8ToV9Test {

    @Test
    fun `v8 库升到 v9 后目录新列与厂商表就位、老模型行原样`() = runBlocking {
        val dir = createTempDirectory(prefix = "vault-migration-").toFile()
        try {
            val dbFile = File(dir, "vault.db")
            createV8Database(dbFile)

            val database = Room.databaseBuilder<VaultDatabase>(name = dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(VaultDatabase.MIGRATION_8_9)
                .build()

            // **先走一次 DAO 再看 schema**：`build()` 是懒的，Room 要到第一次真正取数时才
            // 打开库、比对版本并跑迁移。下面的 `columnNames` 用的是另一个裸连接，如果排在
            // 前面，读到的是**还没迁移的 v8 那份列**——这条测试会红得像迁移写坏了，
            // 而实际上一行 DDL 都还没执行。
            assertEquals(0, database.modelCatalogDao().count(), "老库里这张表就该是空的")

            // 新增的列都在，且老行没被这次升级动过。
            val catalogColumns = columnNames(dbFile, "model_catalog")
            assertTrue(
                listOf(
                    "providerSlug", "qualifiedId", "canonical", "vendorName", "description",
                    "structuredOutput", "openWeights", "status", "knowledgeCutoff",
                ).all { it in catalogColumns },
                "model_catalog 缺列：$catalogColumns",
            )
            assertEquals(28, catalogColumns.size, "v8 的 19 列 + 本次 9 列")

            // 厂商表连同它的唯一索引一起建起来了。
            assertTrue(columnNames(dbFile, "model_vendors").contains("slug"))
            assertEquals(
                listOf("index_model_vendors_slug"),
                indexNames(dbFile, "model_vendors"),
            )
            assertEquals(
                listOf(
                    "index_model_catalog_family",
                    "index_model_catalog_modelId",
                    "index_model_catalog_normId",
                    "index_model_catalog_providerSlug",
                    "index_model_catalog_qualifiedId",
                    "index_model_catalog_vendor",
                ),
                indexNames(dbFile, "model_catalog").sorted(),
                "分组与三个查找键都要有索引，否则整页分组是全表扫描",
            )

            // 升级前那行模型还在，catalogKey 仍是 null——回填发生在首次目录同步，不在迁移里。
            val models = database.modelDao().findByProvider(1L)
            assertEquals(1, models.size)
            assertEquals("gpt-4o", models.first().modelId)
            assertNull(models.first().catalogKey)

            database.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `升级后的空目录一写入就能按 qualifiedId 取到候选`() = runBlocking {
        val dir = createTempDirectory(prefix = "vault-migration-").toFile()
        try {
            val dbFile = File(dir, "vault.db")
            createV8Database(dbFile)

            val database = Room.databaseBuilder<VaultDatabase>(name = dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(VaultDatabase.MIGRATION_8_9)
                .build()

            // 同一个模型的两条候选：原创那条 + 聚合站转售那条。DAO 把 canonical 排在前面，
            // 这条断言守的是「价格取原创、不取转售」这个决定的落地那一半。
            database.modelCatalogDao().upsertAll(
                listOf(
                    catalogRow(
                        key = "tokengo/deepseek/deepseek-chat",
                        providerSlug = "tokengo",
                        vendor = "deepseek",
                        modelId = "deepseek/deepseek-chat",
                        qualifiedId = "deepseek/deepseek-chat",
                        canonical = false,
                        costInput = 1.4,
                    ),
                    catalogRow(
                        key = "deepseek/deepseek-chat",
                        providerSlug = "deepseek",
                        vendor = "deepseek",
                        modelId = "deepseek-chat",
                        qualifiedId = "deepseek/deepseek-chat",
                        canonical = true,
                        costInput = 0.27,
                    ),
                ),
            )
            database.modelVendorDao().upsertAll(
                listOf(
                    ModelVendorEntity(
                        slug = "deepseek",
                        name = "DeepSeek",
                        apiUrl = "https://api.deepseek.com",
                        docUrl = "https://api-docs.deepseek.com",
                    ),
                ),
            )

            val candidates = database.modelCatalogDao().findByQualifiedId("deepseek/deepseek-chat")
            assertEquals(2, candidates.size)
            assertTrue(candidates.first().canonical, "原创那条必须排在转售那条之前")
            assertEquals(0.27, candidates.first().costInput, "排第一的必须是原创价，不是聚合站转售价")

            // 裸 id 走第二级：中转站列表里给的就是不带前缀的 deepseek-chat。
            val byModelId = database.modelCatalogDao().findByModelId("deepseek-chat")
            assertEquals(1, byModelId.size)
            assertEquals("deepseek/deepseek-chat", byModelId.first().key)

            assertEquals("DeepSeek", database.modelVendorDao().findBySlug("deepseek")?.name)
            assertEquals(2, database.modelCatalogDao().count())

            database.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun catalogRow(
        key: String,
        providerSlug: String,
        vendor: String,
        modelId: String,
        qualifiedId: String,
        canonical: Boolean,
        costInput: Double,
    ) = ModelCatalogEntity(
        key = key,
        providerSlug = providerSlug,
        vendor = vendor,
        modelId = modelId,
        qualifiedId = qualifiedId,
        normId = modelId.substringAfter('/'),
        canonical = canonical,
        costInput = costInput,
    )

    /**
     * 按 v8 那份导出 schema 造一个真库。
     *
     * 只灌这次要验的那点数据：一家供应商、一把 Key、一行模型。`key_settings` 不灌，
     * 因为 v9 那条链路不读它，硬凑一行反而会让「这次迁移动了什么」变得看不出来。
     */
    private fun createV8Database(file: File) {
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            exportedSchema(8).forEach { statement -> connection.execSQL(statement) }
            connection.execSQL("PRAGMA user_version = 8")
            connection.execSQL(
                """
                INSERT INTO providers (id, name, note, websiteUrl, checkWebsite, websiteLatencyMs,
                    websiteCheckedAt, websiteError, groupId, color, pinned, sortOrder,
                    createdAt, updatedAt)
                VALUES (1, 'p', NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, 0, 0, 1, 1)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO api_keys (id, providerId, label, note, secretEnc, fingerprint, health,
                    lastOutcome, healthDetail, httpStatus, latencyMs, checkedAt, okAt,
                    balanceAmount, balanceUsed, balanceCurrency, balanceRaw, balanceCheckedAt,
                    balanceError, sortOrder, createdAt, updatedAt)
                VALUES (1, 1, 'k', '', X'00', 'fp', 'ok', 'success', NULL, 200, 12, 3, 3,
                    NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, 1)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO models (id, providerId, keyId, modelId, protocol, displayName, source,
                    discoveredVia, favorite, needsReview, catalogKey, probeState, lastOutcome,
                    probeDetail, latencyMs, probedAt, firstSeenAt, lastSeenAt, sortOrder)
                VALUES (1, 1, 1, 'gpt-4o', 'chat', NULL, 'discovered', 'chat', 0, 0, NULL,
                    'unknown', 'skipped', NULL, NULL, NULL, 1, 1, 0)
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

    /**
     * 表上显式建的索引。
     *
     * 过滤掉 `sqlite_autoindex_*`：`model_catalog` 的主键是 TEXT，SQLite 会给它自动生成
     * 一个隐式索引，那不是这次要守的东西——要守的是 Room 按 `index_<表>_<列>` 命名建出来的
     * 那一批，它们决定了分组和三级匹配走不走索引。
     */
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
