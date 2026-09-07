package com.lc33.tokenvault.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * boot 存储的 iOS 实现（阶段4）。
 *
 * 解析与损坏判定在 [BaseBootStore]（commonMain，与 Android 共用一份），
 * 这里只做三个平台原语。
 *
 * 原子写：`NSData.writeToFile(atomically = true)`—— Foundation 先写临时文件再 rename，
 * 是 iOS 侧的标准原子写法。能力边界要诚实：它**不保证 fsync**（官方文档明说
 * "not guaranteed to be durable"），断电窗口比 Android 端（显式 fd.sync）宽。
 * APFS 的 copy-on-write 让 rename 本身是原子的，所以撕裂（半个 JSON）不会发生，
 * 最坏情况是"回到上一次完整版本"——这落回 [BootState] 的语义里是可接受的。
 */
class IosBootStore(
    private val directory: String,
    private val fileName: String = BaseBootStore.FILE_NAME,
    deviceIdFactory: () -> String = { BaseBootStore.randomDeviceId() },
) : BaseBootStore() {

    private val manager = NSFileManager.defaultManager
    private val path = "$directory/$fileName"
    private val deviceId: String by lazy { deviceIdFactory() }

    override fun newDeviceId(): String = deviceId

    override fun fileExists(): Boolean = manager.fileExistsAtPath(path)

    override fun rawRead(): String? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val text = NSString.create(data = data, encoding = NSUTF8StringEncoding)
        // 存在却解不出 UTF-8：返回空串让它落 Corrupt（"boot file is empty"）而不是 Missing。
        // 把它当全新安装的代价是整库永久解不开。
        return text ?: ""
    }

    override fun atomicWrite(text: String) {
        val bytes = text.encodeToByteArray()
        val data = bytes.toNSData()
        if (!data.writeToFile(path, atomically = true)) {
            throw IllegalStateException("failed to write boot file atomically: $path")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun deleteAll() {
        // error = null：不关心失败原因（文件本来就可能不存在）。
        manager.removeItemAtPath(path, error = null)
        manager.removeItemAtPath("$directory/${BaseBootStore.tempNameOf(fileName)}", error = null)
    }

    companion object {
        /** 默认放在 Application Support（应用私有、备份会带上、系统不清理）。 */
        @OptIn(ExperimentalForeignApi::class)
        fun defaultDirectory(): String {
            val dir = NSSearchPathForDirectoriesInDomains(
                NSApplicationSupportDirectory,
                NSUserDomainMask,
                true,
            ).firstOrNull() as? String
                ?: error("Application Support directory not found")
            manager_createDirectory(dir)
            return dir
        }
    }
}

/** 递归建目录。 */
@OptIn(ExperimentalForeignApi::class)
private fun manager_createDirectory(dir: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        dir, withIntermediateDirectories = true, attributes = null, error = null,
    )
}

/** ByteArray → NSData（与 BackupFilePicker.ios.kt 同一个模式）。 */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    // 空数组直接给空 NSData：usePinned 的 addressOf(0) 对空数组会抛越界。
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    } ?: NSData()
}
