package com.lc33.tokenvault.platform

import androidx.compose.runtime.Composable

/**
 * 备份文件的「选文件读写」能力（红线 20：文件选择器是平台能力，§12.1 同步导出/恢复）。
 *
 * Android 用 SAF（`CreateDocument`/`OpenDocument` + `ActivityResultRegistry`），
 * iOS 用 `UIDocumentPickerViewController`。两者机制完全不同，所以落成 expect/actual：
 * Compose 层只拿到两个动作，不碰平台的文件选择 API。
 *
 * 导出：用户选目标文件后，平台调用 [onExportPicked] 回调，传入「写字节」的 suspend 函数；
 * 导入：用户选源文件后，平台读字节并调用 [onImportPicked] 回调。
 *
 * @param onCancelled 用户**关掉**选择器时调用。调用方通常在此之前弹了一层口令输入框、
 *   并且要等选完文件才收口，所以取消必须也给它一个出口：没有这个回调的话那层弹层会一直
 *   挂在选完文件回来的界面上（Android SAF 与 iOS documentPicker 的表现一致）。
 *   默认什么都不做，只为不强制所有调用方一起改。
 */
@Composable
expect fun rememberBackupFilePicker(
    onExportPicked: (suspend (ByteArray) -> Unit) -> Unit,
    onImportPicked: (ByteArray) -> Unit,
    onCancelled: () -> Unit = {},
): BackupFilePicker

/** 两个动作的句柄。 */
data class BackupFilePicker(
    val pickExport: () -> Unit,
    val pickImport: () -> Unit,
)
