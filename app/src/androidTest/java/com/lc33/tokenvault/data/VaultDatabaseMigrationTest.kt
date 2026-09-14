package com.lc33.tokenvault.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = VaultDatabase::class.java,
    )

    @Test
    fun migrate1To3KeepsRoomSchemaValid() {
        val dbName = "migration-1-3-test.db"
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            VaultDatabase.MIGRATION_1_2,
            VaultDatabase.MIGRATION_2_3,
        ).close()
    }

    @Test
    fun migrate3To4KeepsRoomSchemaValid() {
        val dbName = "migration-3-4-test.db"
        helper.createDatabase(dbName, 3).close()
        helper.runMigrationsAndValidate(
            dbName,
            4,
            true,
            VaultDatabase.MIGRATION_3_4,
        ).close()
    }

    @Test
    fun migrate2To3KeepsRoomSchemaValid() {
        val dbName = "migration-2-3-test.db"
        helper.createDatabase(dbName, 2).close()
        helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            VaultDatabase.MIGRATION_2_3,
        ).close()
    }

    /**
     * v4 → v5 是重建表的迁移（去掉 `enabled`），重建最容易出的事是**把数据丢了**，
     * 所以这条除了校验 schema，还要求重建后行还在、列值原样。
     */
    @Test
    fun migrate4To5KeepsRowsAndDropsEnabled() {
        val dbName = "migration-4-5-test.db"
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                """
                INSERT INTO providers (id, name, note, websiteUrl, websiteLatencyMs, websiteCheckedAt,
                    websiteError, groupId, color, pinned, sortOrder, createdAt, updatedAt)
                VALUES (1, 'p', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, 1, 1)
                """.trimIndent(),
            )
            // enabled = 0：迁移不改写它——本来就没有"停用"这回事了，行照旧留着。
            execSQL(
                """
                INSERT INTO api_keys (id, providerId, label, note, secretEnc, fingerprint, enabled,
                    health, lastOutcome, healthDetail, httpStatus, latencyMs, checkedAt, okAt,
                    balanceAmount, balanceUsed, balanceCurrency, balanceRaw, balanceCheckedAt,
                    balanceError, sortOrder, createdAt, updatedAt)
                VALUES (1, 1, 'k', 'n', X'00', 'fp', 0, 'ok', 'success', NULL, 200, 12, 3, 3,
                    NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO models (id, providerId, keyId, modelId, protocol, displayName, source,
                    discoveredVia, enabled, favorite, needsReview, catalogKey, probeState,
                    lastOutcome, probeDetail, latencyMs, probedAt, firstSeenAt, lastSeenAt, sortOrder)
                VALUES (1, 1, 1, 'gpt-5.6-sol', 'chat', NULL, 'discovered', 'chat', 0, 0, 0, NULL,
                    'unknown', 'skipped', NULL, NULL, NULL, 1, 1, 0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 5, true, VaultDatabase.MIGRATION_4_5)
        db.query("SELECT label, note, fingerprint, health, latencyMs FROM api_keys WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("k", c.getString(0))
            assertEquals("n", c.getString(1))
            assertEquals("fp", c.getString(2))
            assertEquals("ok", c.getString(3))
            assertEquals(12L, c.getLong(4))
        }
        db.query("SELECT modelId, protocol, source, discoveredVia FROM models WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("gpt-5.6-sol", c.getString(0))
            assertEquals("chat", c.getString(1))
            assertEquals("discovered", c.getString(2))
            assertEquals("chat", c.getString(3))
        }
        db.query("PRAGMA table_info(api_keys)").use { c ->
            val columns = mutableListOf<String>()
            while (c.moveToNext()) columns += c.getString(1)
            assertFalse(columns.contains("enabled"))
        }
        db.close()
    }

    /** v5 → v6 只加三列，老日志的三列是 NULL，不该被迁移搞坏。 */
    @Test
    fun migrate5To6KeepsExistingLogsAndAddsBodies() {
        val dbName = "migration-5-6-test.db"
        helper.createDatabase(dbName, 5).apply {
            execSQL(
                """
                INSERT INTO audit_log (id, at, level, category, providerId, keyId, runId, message, detail)
                VALUES (1, 1, 'info', 'http', NULL, NULL, NULL,
                    'http GET api.test/v1/models -> 200', 'latency=12ms')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 6, true, VaultDatabase.MIGRATION_5_6)
        db.query("SELECT message, requestUrl, requestBody, responseBody FROM audit_log WHERE id = 1")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("http GET api.test/v1/models -> 200", c.getString(0))
                assertTrue(c.isNull(1))
                assertTrue(c.isNull(2))
                assertTrue(c.isNull(3))
            }
        db.close()
    }
}
