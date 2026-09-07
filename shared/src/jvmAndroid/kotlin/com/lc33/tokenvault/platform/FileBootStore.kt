package com.lc33.tokenvault.platform

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 文件实现（Android / JVM 端，jvmAndroid 共享源集）。
 *
 * 解析与损坏判定在 [BaseBootStore]（commonMain，与 iOS 共用一份），
 * 这里只做三个平台原语：文件在不在、读文本、**临时文件 → fsync → rename** 的原子写。
 *
 * 说清能力边界：`File.renameTo` 在 POSIX 上是原子的，`fd.sync()` 保证内容已落盘。
 * 但 Java 没有 API 去 fsync **目录项**，所以理论上仍存在"rename 已返回、目录项未落盘"
 * 的窗口。这个窗口只在设备突然断电时有意义，而那种情况下 Android 自己的文件系统
 * （ext4/f2fs 带日志）会保证 rename 的原子性。做到这一步是这个平台上能做到的上限，
 * 剩下的靠 [BootState.Corrupt] 兜住——兜不住的是"把损坏当成全新安装"，而那一条我们
 * 在类型层面就排除了。
 */
class FileBootStore(
    private val file: File,
    private val deviceIdFactory: () -> String = { BaseBootStore.randomDeviceId() },
) : BaseBootStore() {

    override fun newDeviceId(): String = deviceIdFactory()

    override fun fileExists(): Boolean = file.isFile

    override fun rawRead(): String? = try {
        file.readText()
    } catch (e: IOException) {
        null
    }

    override fun atomicWrite(text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, BaseBootStore.tempNameOf(file.name))
        val bytes = text.encodeToByteArray()

        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            // 少了这一句，rename 之后内容仍可能只在页缓存里——断电就得到一个长度对、
            // 内容是零的文件，而那正是最坏的形态（能解析出格式版本，解不出密文）。
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            // Windows 上目标存在时 renameTo 会失败；Android 上不会，但测试跑在 JVM 上。
            // 删了再来一次，代价是这一瞬间没有 boot 文件——所以只在 rename 真的失败时做。
            if (!file.delete() || !temp.renameTo(file)) {
                temp.delete()
                throw IOException("failed to replace boot file atomically: ${file.absolutePath}")
            }
        }
    }

    override fun deleteAll() {
        File(file.parentFile, BaseBootStore.tempNameOf(file.name)).delete()
        file.delete()
    }

    companion object {
        const val FILE_NAME = BaseBootStore.FILE_NAME
    }
}
