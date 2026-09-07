package com.lc33.tokenvault.platform

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lc33.tokenvault.data.VaultDatabase

/**
 * 手写 DDL 的 Android 执行点。
 *
 * 在 `RoomDatabase.Callback.onOpen` 而不是只在 `onCreate` 里建索引：
 * `IF NOT EXISTS` 让它幂等，代价是每次开库多一条 DDL（几乎为零）。收益是：
 * 万一某个版本的 `onCreate` 漏了它，用户升级上来时会自动补上，
 * 而不是带着一个缺索引的库一直跑下去。
 *
 * 挂在 shared androidMain 而不是 app 的 DI 里，是因为这条 DDL 属于数据库本身，
 * iOS 端要用同一条（见 iosMain 的驱动包装层），两头必须同源。
 */
fun SupportSQLiteDatabase.applyHandWrittenSchema() {
    execSQL(VaultDatabase.PARTIAL_INDEX_KEYS_DEFAULT)
}
