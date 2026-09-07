package com.lc33.tokenvault.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Android：SAF 文件选择（§12.1）。
 *
 * `CreateDocument("application/octet-stream")` 选导出目标，`OpenDocument()` 选导入源。
 * 实际读写（contentResolver openOutputStream/openInputStream）在回调里，用注入的
 * [appContext]（`initSystemActions` 注入，见 SystemActions.android.kt）。
 */
@Composable
actual fun rememberBackupFilePicker(
    onExportPicked: (suspend (ByteArray) -> Unit) -> Unit,
    onImportPicked: (ByteArray) -> Unit,
): BackupFilePicker {
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onExportPicked { bytes ->
            appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("cannot open output stream")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@rememberLauncherForActivityResult
        onImportPicked(bytes)
    }

    return remember {
        BackupFilePicker(
            pickExport = { exportLauncher.launch("yuanji-backup.yjv") },
            pickImport = { importLauncher.launch(arrayOf("*/*")) },
        )
    }
}
