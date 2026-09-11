package com.lc33.tokenvault.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
