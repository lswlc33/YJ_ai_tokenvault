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
 * - 两条路都还要接**取消**：`documentPickerWasCancelled` 是唯一一句"用户没选"的回执。
 *   漏掉它的话调用方挂在 picker 下面那层口令弹层就再也关不掉——picker 自己会消失，
 *   回到界面上留下的是一道没人按的口令输入框（Android SAF 返回 null uri 是同一件事）。
 *
 * 两个 picker 都用 `LocalUIViewController` 拿到宿主 VC 做 present，与 CMP 的 UIKit 互操作一致。
 */
@Composable
actual fun rememberBackupFilePicker(
    onExportPicked: (suspend (ByteArray) -> Unit) -> Unit,
    onImportPicked: (ByteArray) -> Unit,
    onCancelled: () -> Unit,
): BackupFilePicker {
    val viewController = LocalUIViewController.current

    // `picker.delegate` 在 ObjC 侧是 weak 引用：Kotlin 不持强引用的话，回调对象可能在
    // 弹窗还开着时就被 GC 回收，`didPickDocumentAtURL` 从此不响。用这个列表钉住 delegate。
    // 它**不带 remember 键**：下面的 picker 实例会因为回调 lambda 换身份而重建，若列表跟着重建，
    // "picker 还开着时恰好重组一次"就会把唯一一个强引用丢掉（那正是这里要防的事）。
    val retainedDelegates = remember { mutableListOf<NSObject>() }

    return remember(viewController, onExportPicked, onImportPicked, onCancelled) {
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
                val delegate = PickerDelegate(retainedDelegates, onPicked = { targetUrl ->
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
                }, onCancelled = onCancelled)
                retainedDelegates += delegate
                picker.delegate = delegate
                viewController?.presentViewController(picker, animated = true, completion = null)
            },
            pickImport = {
                val picker = UIDocumentPickerViewController(
                    forOpeningContentTypes = listOf(UTTypeItem),
                    asCopy = true,
                )
                val delegate = PickerDelegate(retainedDelegates, onPicked = { url ->
                    // asCopy = true：系统先把源文件拷进临时目录，URL 无需安全作用域即可读。
                    val data = NSData.dataWithContentsOfURL(url)
                    if (data != null) onImportPicked(data.toByteArray())
                }, onCancelled = onCancelled)
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

/**
 * 两个 picker 共用的 delegate：只需要「选中的 URL」和「用户取消了」两个出口，
 * 导出与导入的差别全在传进来的 [onPicked] 里，不必有两个类。
 *
 * [retained] 是调用点那份钉住 delegate 的列表（ObjC 侧 `delegate` 是 weak 引用）。
 * 两条回执中的任一条一到就把自己从列表里摘掉：那是系统最后一次还可能调用这个对象，
 * 继续钉着的代价是列表只增不减——它挂在 `remember` 里，会跟着这一屏活到用户离开。
 * 在回调**自己执行期间**摘掉自己是安全的：ARC 保证一次消息发送的接收方在发送期间存活。
 */
private class PickerDelegate(
    private val retained: MutableList<NSObject>,
    private val onPicked: (NSURL) -> Unit,
    private val onCancelled: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentAtURL: NSURL,
    ) {
        retained.remove(this)
        onPicked(didPickDocumentAtURL)
    }

    /** 用户按了取消 / 划走了 picker：系统不会给别的回执，这一条就是唯一的"没选"。 */
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        retained.remove(this)
        onCancelled()
    }
}
