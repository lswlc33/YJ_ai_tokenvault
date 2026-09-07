package com.lc33.tokenvault.platform

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lc33.tokenvault.data.VaultDatabase
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * iOS 端的数据库构建（阶段4）。
 *
 * iOS 没有系统 SQLite 的 androidx 驱动，只能用内置的 [BundledSQLiteDriver]
 * （自带一份编好的 SQLite，行为与 Android 端系统库一致，Room KMP 官方推荐）。
 *
 * 外键 PRAGMA 与手写索引走 `RoomDatabase.Callback`——它在 commonMain 里也有，
 * 时机与 Android 端完全对齐：`onOpen` 在 schema 校验之后跑，`IF NOT EXISTS`
 * 保证幂等（万一某个版本的 onCreate 漏了，升级时会自动补上）。
 * journal mode 用 Room 默认（WAL），与 Android 端一致。
 *
 * （JVM 端有一份相同的实现——`Room.databaseBuilder(name)` 的 reified 入口
 * 只存在于 jvm/native 源集，Android target 没有，所以进不了 commonMain。）
 */
fun createVaultDatabase(): VaultDatabase {
    val dir = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory,
        NSUserDomainMask,
        true,
    ).firstOrNull() as? String
        ?: error("Application Support directory not found")
    NSFileManager.defaultManager.createDirectoryAtPath(
        dir, withIntermediateDirectories = true, attributes = null,
    )

    return Room.databaseBuilder<VaultDatabase>(name = "$dir/${VaultDatabase.FILE_NAME}")
        .setDriver(BundledSQLiteDriver())
        .addCallback(
            object : RoomDatabase.Callback() {
                override fun onCreate(connection: SQLiteConnection) {
                    connection.execSQL(VaultDatabase.PARTIAL_INDEX_KEYS_DEFAULT)
                }

                override fun onOpen(connection: SQLiteConnection) {
                    connection.execSQL("PRAGMA foreign_keys = ON")
                    connection.execSQL(VaultDatabase.PARTIAL_INDEX_KEYS_DEFAULT)
                }
            },
        )
        .build()
}
