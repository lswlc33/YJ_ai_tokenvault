package com.lc33.tokenvault.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** JVM（单测/桌面）无文件选择器，no-op。 */
@Composable
actual fun rememberBackupFilePicker(
    onExportPicked: (suspend (ByteArray) -> Unit) -> Unit,
    onImportPicked: (ByteArray) -> Unit,
): BackupFilePicker = remember { BackupFilePicker(pickExport = {}, pickImport = {}) }
