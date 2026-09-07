package com.lc33.tokenvault.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * iOS：阶段4 在 mac CI 补 UIDocumentPickerViewController 实现，暂为 no-op。
 * （本机 Windows 无法编译 iOS 原生，但声明 actual 不影响 android/jvm 构建。）
 */
@Composable
actual fun rememberBackupFilePicker(
    onExportPicked: (suspend (ByteArray) -> Unit) -> Unit,
    onImportPicked: (ByteArray) -> Unit,
): BackupFilePicker = remember { BackupFilePicker(pickExport = {}, pickImport = {}) }
