package com.lc33.tokenvault.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS：UIDocumentPickerViewController 文件选择（§12.1，阶段4）。
 *
 * 与 Android SAF 的「先选位置、后写字节」契约对齐：
 * - 导出：先在临时目录建一个 0 字节占位文件，用 `forExportingURLs` 让用户选保存位置；
 *   delegate 回调 `didPickDocumentAtURL` 返回系统拷贝后的目标 URL，此时调用 [onExportPicked]
 *   传入「写字节」函数（把真实字节覆盖写到该目标 URL），与 expect 契约一致。
 * - 导入：用 `forOpeningContentTypes` 让用户选源文件，delegate 回调读字节交给 [onImportPicked]。
 *
 * iOS：UIDocumentPickerViewController 文件选择（§12.1，阶段4）。
 *
 * 与 Android SAF 的「先选位置、后写字节」契约对齐：
 * - 导出：先在临时目录建一个 0 字节占位文件，用 `forExportingURLs` 让用户选保存位置；
 *   delegate 回调 `didPickDocumentAtURL` 返回系统拷贝后的目标 URL，此时调用 [onExportPicked]
 *   传入「写字节」函数（把真实字节覆盖写到该目标 URL），与 expect 契约一致。
 * - 导入：用 `forOpeningContentTypes` 让用户选源文件，delegate 回调读字节交给 [onImportPicked]。
 *
 * 两个 picker 都用 `LocalUIViewController` 拿到宿主 VC 做 present，与 CMP 的 UIKit 互操作一致。
 */
@Composable
actual fun rememberBackupFilePicker(
    onExportPicked: (suspend (ByteArray) -> Unit) -> Unit,
    onImportPicked: (ByteArray) -> Unit,
): BackupFilePicker {
    val viewController = LocalUIViewController.current

    return remember(viewController, onExportPicked, onImportPicked) {
        // `picker.delegate` 在 ObjC 侧是 weak 引用：Kotlin 不持强引用的话，回调对象可能在
        // 弹窗还开着时就被 GC 回收，`didPickDocumentAtURL` 从此不响。用这个列表钉住
        // 已创建的 delegate，让它们与 remember 的值同生共死。
        val retainedDelegates = mutableListOf<NSObject>()

        BackupFilePicker(
            pickExport = {
                // 占位源文件：forExportingURLs 要求一个真实存在的本地文件才能弹出保存界面。
                // 内容是 0 字节，真实字节在 delegate 回调拿到目标 URL 后再覆盖写入。
                val placeholderUrl = newTempBackupUrl()
                // 用空 NSData 落一个 0 字节占位文件（避免 NSFileManager 复杂签名）。
                NSData().writeToURL(placeholderUrl, atomically = false)
                val picker = UIDocumentPickerViewController(
                    forExportingURLs = listOf(placeholderUrl),
                    asCopy = false,
                )
                val delegate = ExportDelegate { targetUrl ->
                    onExportPicked { bytes ->
                        withContext(Dispatchers.Default) {
                            // 目标可能在沙盒外（iCloud / 「我的 iPhone」），写之前显式申请
                            // 安全作用域；普通本地路径这里只会返回 false，无副作用。
                            val scoped = targetUrl.startAccessingSecurityScopedResource()
                            try {
                                bytes.toNSData().writeToURL(targetUrl, atomically = true)
                            } finally {
                                if (scoped) targetUrl.stopAccessingSecurityScopedResource()
                            }
                        }
                    }
                }
                retainedDelegates += delegate
                picker.delegate = delegate
                viewController?.presentViewController(picker, animated = true, completion = null)
            },
            pickImport = {
                val picker = UIDocumentPickerViewController(
                    forOpeningContentTypes = listOf(UTTypeItem),
                    asCopy = true,
                )
                val delegate = ImportDelegate { url ->
                    // asCopy = true：系统先把源文件拷进临时目录，URL 无需安全作用域即可读。
                    val data = NSData.dataWithContentsOfURL(url) ?: return@ImportDelegate
                    onImportPicked(data.toByteArray())
                }
                retainedDelegates += delegate
                picker.delegate = delegate
                viewController?.presentViewController(picker, animated = true, completion = null)
            },
        )
    }
}

/** 在 NSTemporaryDirectory 下生成一个唯一的备份临时文件 URL。 */
private fun newTempBackupUrl(): NSURL {
    val fileName = "tokenvault-export-${NSUUID().UUIDString}.yjv"
    return NSURL.fileURLWithPath(NSTemporaryDirectory() + fileName)
}

/** ByteArray → NSData。 */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    // 空数组直接给空 NSData：usePinned 的 addressOf(0) 对空数组会抛越界。
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    } ?: NSData()
}

/** NSData → ByteArray。 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    return ByteArray(size).also { bytes ->
        if (size > 0) {
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), this.bytes, length)
            }
        }
    }
}

/** 导出回调：`didPickDocumentAtURL` 拿到目标 URL 后，调用 [onTargetPicked] 传入写函数。 */
private class ExportDelegate(
    private val onTargetPicked: (NSURL) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentAtURL: NSURL,
    ) {
        onTargetPicked(didPickDocumentAtURL)
    }
}

/** 导入回调：`didPickDocumentAtURL` 拿到源文件 URL 后，读字节交给上层。 */
private class ImportDelegate(
    private val onPicked: (NSURL) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentAtURL: NSURL,
    ) {
        onPicked(didPickDocumentAtURL)
    }
}
