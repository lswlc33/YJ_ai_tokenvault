package com.lc33.tokenvault.di

import com.lc33.tokenvault.platform.APP_VERSION_NAME
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.SecureClipboard
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lc33.tokenvault.data.VaultDatabase
import java.io.File
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * JVM 平台模块（[platformModule] 的 actual）。
 *
 * jvm target 存在的意义是**在本机跑纯 Kotlin 单测**（无 Mac 也能验证逻辑），
 * 没有真实的发布形态，所以这里只求"能解析、行为合理"：数据库与 boot 文件放在
 * `~/.tokenvault/`，剪贴板是诚实的 no-op（JVM 没有系统剪贴板可谈）。
 *
 * 数据库回调与 Android 端逐条对齐（外键 PRAGMA；v3 起没有手写部分索引）。
 */
actual val platformModule: Module = module {

    single {
        val dir = File(System.getProperty("user.home"), ".tokenvault").apply { mkdirs() }
        Room.databaseBuilder<VaultDatabase>(name = File(dir, VaultDatabase.FILE_NAME).absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(connection: SQLiteConnection) {
                        connection.execSQL("PRAGMA foreign_keys = ON")
                    }
                },
            )
            .addMigrations(VaultDatabase.MIGRATION_1_2, VaultDatabase.MIGRATION_2_3)
            .build()
    }

    single<BootStore> {
        FileBootStore(File(File(System.getProperty("user.home"), ".tokenvault"), FileBootStore.FILE_NAME))
    }

    single<SecureClipboard> {
        object : SecureClipboard {
            override fun copy(label: String, value: CharArray, autoClearSeconds: Int) = Unit
            override fun clearNow() = Unit
            override fun read(): String? = null
        }
    }

    single(named(Qualifiers.PLACEHOLDERS)) {
        mapOf(
            "app_version" to APP_VERSION_NAME,
            "android_release" to (System.getProperty("os.version") ?: "generic"),
            "arch" to (System.getProperty("os.arch") ?: "generic"),
        )
    }
}
