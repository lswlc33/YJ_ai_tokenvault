package com.lc33.tokenvault.platform

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * 文件实现（Android / JVM 端）。接口 [BootStore] 已迁 commonMain，实现留在 app。
 *
 * **写入必须原子**（红线 26）：临时文件 → flush → fsync → rename。
 * 撕裂写入 = 永久锁库，而这比"KDF 参数被改写"更容易真的发生——改 PIN、开关生物识别、
 * 累加失败计数都会写 boot，一天可能写十几次。
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
    private val deviceIdFactory: () -> String = { java.util.UUID.randomUUID().toString() },
) : BootStore {

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    private val json = Json {
        // 存储格式要能被将来的版本读懂，所以显式写死这几项而不是靠默认值
        prettyPrint = true
        encodeDefaults = true
        // 遇到不认识的键**不忽略**：那说明这份文件来自更新的版本，猜着读会读出错的语义。
        // 落 Corrupt 让用户去恢复备份，比装作读懂了安全得多（红线 9）。
        ignoreUnknownKeys = false
    }

    override fun read(): BootState {
        if (!file.isFile) return BootState.Missing
        val text = try {
            file.readText()
        } catch (e: IOException) {
            return BootState.Corrupt("boot file unreadable: ${e.message}")
        }
        // 空文件是撕裂写入最常见的残留形态。它**不是** Missing——把它当全新安装，
        // 用户就会被引导去重设 PIN，而库里的密文从此永久解不开。
        if (text.isBlank()) return BootState.Corrupt("boot file is empty")

        val record = try {
            json.decodeFromString(BootRecord.serializer(), text)
        } catch (t: Throwable) {
            return BootState.Corrupt("boot file is not valid JSON for this format: ${t.message}")
        }
        if (record.format != BootRecord.FORMAT_V1) {
            return BootState.Corrupt("unknown boot format ${record.format}")
        }
        // 引导声称完成，却没有 PIN 那条路——这份文件自相矛盾，只能是坏了。
        // 不修补：修补等于猜，而猜错的代价是整库不可读。
        if (record.onboarded && (record.pinKdf == null || record.dekWrappedByPin == null)) {
            return BootState.Corrupt("onboarded but the PIN wrap is missing")
        }
        return BootState.Ok(record)
    }

    override fun write(record: BootRecord) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        val bytes = json.encodeToString(BootRecord.serializer(), record).encodeToByteArray()

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
        // 只有真的换上去了才 +1。放在 rename 之后而不是之前：观察者拿到新 revision 就会
        // 立刻重读文件，而在 rename 成功之前重读拿到的还是旧内容。
        _revision.update { it + 1 }
    }

    override fun update(transform: (BootRecord) -> BootRecord): BootRecord {
        val current = when (val state = read()) {
            is BootState.Ok -> state.record
            BootState.Missing -> BootRecord(deviceId = deviceIdFactory())
            // 明确拒绝在损坏状态上做增量修改。允许的话，一次"累加失败计数"就会把
            // 损坏文件覆盖成一份看起来正常、实际丢了所有密文的新文件。
            is BootState.Corrupt -> throw IllegalStateException(
                "refusing to update a corrupt boot file: ${state.reason}",
            )
        }
        val updated = transform(current)
        // 没变就不写。每一次写都是一次撕裂风险，而"翻开关又翻回来"这类操作很常见。
        if (updated != current) write(updated)
        return updated
    }

    override fun clear() {
        File(file.parentFile, file.name + TEMP_SUFFIX).delete()
        file.delete()
        _revision.update { it + 1 }
    }

    companion object {
        const val FILE_NAME = "boot.json"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
