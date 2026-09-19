package com.lc33.tokenvault.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** JVM（单测/桌面）无文件选择器，no-op。三个回调都不会被触发，参数只为与 expect 对齐。 */
@Composable
actual fun rememberBackupFilePicker(
    onExportPicked: (suspend (ByteArray) -> Unit) -> Unit,
    onImportPicked: (ByteArray) -> Unit,
    onCancelled: () -> Unit,
): BackupFilePicker = remember { BackupFilePicker(pickExport = {}, pickImport = {}) }
