package com.lc33.tokenvault.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * [BootStore] 的跨平台骨架（阶段4）：解析、校验、损坏判定、"没变就不写"这些**最容易改坏
 * 且改坏了不会报错**的逻辑只留这一份；各平台只实现三个原语——文件在不在、读文本、原子写。
 *
 * **写入必须原子**（红线 26）：临时文件 → fsync（能做的话）→ rename。
 * 撕裂写入 = 永久锁库，而这比"KDF 参数被改写"更容易真的发生——改 PIN、开关生物识别、
 * 累加失败计数都会写 boot，一天可能写十几次。
 */
abstract class BaseBootStore : BootStore {

    /** 文件是否存在（存在但读不出来是 Corrupt，不是 Missing）。 */
    protected abstract fun fileExists(): Boolean

    /**
     * 读全文。文件不存在或字节无法解码成文本时返回 null——两种情况由
     * [fileExists] 区分开，"存在却读不了"必须落 Corrupt 而不是当全新安装。
     */
    protected abstract fun rawRead(): String?

    /** 原子写全文。失败抛异常，**不允许静默丢弃**——写不进去意味着下次启动回到旧状态。 */
    protected abstract fun atomicWrite(text: String)

    /** 删掉正式文件与一切临时残留（"抹掉重来"用）。 */
    protected abstract fun deleteAll()

    /** 新设备的 deviceId。不是秘密，但注入以便测试。 */
    protected open fun newDeviceId(): String = randomDeviceId()

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
        if (!fileExists()) return BootState.Missing
        val text = rawRead() ?: return BootState.Corrupt("boot file unreadable")
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
        atomicWrite(json.encodeToString(BootRecord.serializer(), record))
        // 只有真的换上去了才 +1。放在写之后而不是之前：观察者拿到新 revision 就会
        // 立刻重读文件，而在原子写成功之前重读拿到的还是旧内容。
        _revision.update { it + 1 }
    }

    override fun update(transform: (BootRecord) -> BootRecord): BootRecord {
        val current = when (val state = read()) {
            is BootState.Ok -> state.record
            BootState.Missing -> BootRecord(deviceId = newDeviceId())
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
        deleteAll()
        _revision.update { it + 1 }
    }

    companion object {
        const val FILE_NAME = "boot.json"
        private const val TEMP_SUFFIX = ".tmp"

        /** [FileBootStore] 的临时文件名约定，iOS 实现保持同名以复用同一套清理逻辑。 */
        fun tempNameOf(fileName: String): String = fileName + TEMP_SUFFIX

        /** 16 字节安全随机数的 UUID 形态。deviceId 不是秘密，但不必可预测。 */
        fun randomDeviceId(): String {
            val bytes = com.lc33.tokenvault.crypto.SecureRandomBytes.nextBytes(16)
            val hex = buildString(32) {
                for (b in bytes) {
                    // commonMain 没有 String.format，手写两位十六进制
                    append("0123456789abcdef"[(b.toInt() shr 4) and 0xF])
                    append("0123456789abcdef"[b.toInt() and 0xF])
                }
            }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        }
    }
}
