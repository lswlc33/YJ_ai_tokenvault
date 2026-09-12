package com.lc33.tokenvault.di

import android.os.Build
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lc33.tokenvault.data.VaultDatabase
import com.lc33.tokenvault.platform.AndroidSecureClipboard
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.appContext
import com.lc33.tokenvault.platform.applyHandWrittenSchema
import java.io.File
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Android 平台模块（[platformModule] 的 actual）。
 *
 * 数据库走框架 SupportSQLite 路径（`Room.databaseBuilder(Context, …)`），与阶段4 之前
 * :app 里的构建方式一字不差——既有用户的库文件、WAL 行为、外键 PRAGMA 的开启时机
 * 都不能因为搬家而变。
 */
actual val platformModule: Module = module {

    single {
        Room.databaseBuilder(appContext, VaultDatabase::class.java, VaultDatabase.FILE_NAME)
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                        db.applyHandWrittenSchema()
                    }
                },
            )
            .addMigrations(VaultDatabase.MIGRATION_1_2, VaultDatabase.MIGRATION_2_3)
            .build()
    }

    single<BootStore> {
        FileBootStore(File(appContext.filesDir, FileBootStore.FILE_NAME))
    }

    single<SecureClipboard> {
        AndroidSecureClipboard(appContext, get(named(Qualifiers.APP_SCOPE)), get())
    }

    single(named(Qualifiers.PLACEHOLDERS)) {
        mapOf(
            // app_version 与 UpdateEngine 用的是同一份 BuildInfo（gradle.properties 单一来源）
            "app_version" to com.lc33.tokenvault.platform.APP_VERSION_NAME,
            "android_release" to Build.VERSION.RELEASE,
            "arch" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "generic"),
        )
    }
}
