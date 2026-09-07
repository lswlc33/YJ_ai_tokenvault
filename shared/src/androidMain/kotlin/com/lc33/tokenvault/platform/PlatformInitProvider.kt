package com.lc33.tokenvault.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * 用 ContentProvider 自动初始化 [appContext]（Android 库零配置初始化的惯用法）。
 *
 * 阶段3：`openAppLocaleSettings` / `openExternalUrl` / `rememberBackupFilePicker` 的
 * actual 实现需要 Context，但 commonMain 的 expect 签名里不能有 Context。这里借
 * ContentProvider 在进程启动时拿到 Application Context，app 无需显式调用任何初始化。
 */
internal class PlatformInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        appContext = requireNotNull(context).applicationContext
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
