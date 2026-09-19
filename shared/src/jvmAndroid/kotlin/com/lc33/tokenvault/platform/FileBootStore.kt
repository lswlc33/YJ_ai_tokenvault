package com.lc33.tokenvault.platform

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 文件实现（Android / JVM 端，jvmAndroid 共享源集）。
 *
 * 解析与损坏判定在 [BaseBootStore]（commonMain，与 iOS 共用一份），
 * 这里只做几个平台原语：文件在不在、读文本、**临时文件 → fsync → rename** 的原子写、
 * 以及临时残留的读与删。
 *
 * 说清能力边界：`File.renameTo` 在 POSIX 上是原子的，`fd.sync()` 保证内容已落盘。
 * 但 Java 没有 API 去 fsync **目录项**，所以理论上仍存在"rename 已返回、目录项未落盘"
 * 的窗口。这个窗口只在设备突然断电时有意义，而那种情况下 Android 自己的文件系统
 * （ext4/f2fs 带日志）会保证 rename 的原子性。做到这一步是这个平台上能做到的上限，
 * 剩下的靠 [BootState.Corrupt] 兜住——兜不住的是"把损坏当成全新安装"，而那一条我们
 * 在类型层面就排除了。
 *
 * **rename 失败时绝不动正式文件**（曾经的实现是"删掉再 rename"，见下面注释）：
 * 那次删除留下的是一个"boot 文件不存在"的窗口，而它的表现是整库被当成全新安装——
 * 用户被引导去设一个新 PIN，旧数据从此永久解不开。宁可这次写失败（回到旧状态、
 * 下次再试），也不能让正式文件消失。
 */
class FileBootStore(
    private val file: File,
    private val deviceIdFactory: () -> String = { BaseBootStore.randomDeviceId() },
) : BaseBootStore() {

    /**
     * 测试专用：非 null 时用它代替真实 rename，返回值即 rename 的结果。
     *
     * 为什么要能换：`renameTo` 的失败分支（目标被占用、跨设备、只读目录）在 CI 上
     * 造不出来，而"失败了不许删正式文件"恰恰是这一层最要紧的一条。
     */
    var renameOverride: ((source: File, target: File) -> Boolean)? = null

    /**
     * rename 失败的那次诊断信息。null 表示没有待处理的失败。
     * 调用方（设置页 / 日志页）可以把它显示出来，而 [BaseBootStore.write] 不会因此崩。
     */
    var lastWriteFailure: String? = null
        private set

    private val temp: File get() = File(file.parentFile, BaseBootStore.tempNameOf(file.name))

    override fun newDeviceId(): String = deviceIdFactory()

    override fun fileExists(): Boolean = file.isFile

    override fun rawRead(): String? = try {
        file.readText()
    } catch (e: IOException) {
        null
    }

    override fun tempExists(): Boolean = temp.isFile

    override fun tempRawRead(): String? = try {
        if (temp.isFile) temp.readText() else null
    } catch (e: IOException) {
        null
    }

    override fun atomicWrite(text: String) {
        file.parentFile?.mkdirs()
        val bytes = text.encodeToByteArray()

        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            // 少了这一句，rename 之后内容仍可能只在页缓存里——断电就得到一个长度对、
            // 内容是零的文件，而那正是最坏的形态（能解析出格式版本，解不出密文）。
            out.fd.sync()
        }
        if (!rename(temp, file)) {
            // 这里**不删正式文件、也不删临时残留**：
            // - 删正式文件 = 制造一个"boot 不见了"的窗口，下一次读会当成全新安装（不可挽回）；
            // - 删临时残留 = 丢掉这份唯一已经落盘的新内容，而 [BaseBootStore.read] 在正式文件
            //   缺失时本来还能从它恢复。
            // 失败要报告（[BaseBootStore.atomicWrite] 的契约是不静默丢弃），但报告的方式是
            // 抛出去让调用方知道"这次没落上"，而不是顺手把现场清掉。
            val message = "failed to replace boot file atomically, kept both copies: " +
                "${file.absolutePath} / ${temp.absolutePath}"
            lastWriteFailure = message
            throw IOException(message)
        }
        lastWriteFailure = null
    }

    private fun rename(source: File, target: File): Boolean =
        renameOverride?.invoke(source, target) ?: run {
            // File.renameTo 在 Windows（以及目标已存在的多数平台语义）上会直接失败，
            // 而覆盖式改名正是"写完正式文件后再更新一次"的正常路径——用 NIO 的
            // REPLACE_EXISTING 兜住，行为与 POSIX rename 一致。
            if (source.renameTo(target)) return@run true
            try {
                java.nio.file.Files.move(
                    source.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                true
            } catch (_: java.io.IOException) {
                false
            }
        }

    override fun deleteAll() {
        temp.delete()
        file.delete()
        lastWriteFailure = null
    }

    companion object {
        const val FILE_NAME = BaseBootStore.FILE_NAME
    }
}
