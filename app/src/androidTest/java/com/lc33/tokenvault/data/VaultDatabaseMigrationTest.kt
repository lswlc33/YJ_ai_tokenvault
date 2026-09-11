package com.lc33.tokenvault.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 SQLite 迁移测试。
 *
 * JVM FakeDao 测不出 Room 在迁移后的 schema 校验：它会读取 PRAGMA，把实际表结构与
 * @Entity 生成的期望结构逐一比对。Android 启动闪退正是这条校验抛出的异常。
 */
@RunWith(AndroidJUnit4::class)
class VaultDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = VaultDatabase::class.java,
    )

    @Test
    fun migrate1To2KeepsRoomSchemaValid() {
        val dbName = "migration-test.db"

        helper.createDatabase(dbName, 1).close()
        // Room 会在这里执行 MIGRATION_1_2 并校验 schema；校验失败会直接抛异常。
        helper.runMigrationsAndValidate(dbName, 2, true, VaultDatabase.MIGRATION_1_2).close()
    }
}
