package com.lc33.tokenvault.platform

import com.lc33.tokenvault.crypto.isKnownSecretBoxEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * [BootStore] 的跨平台骨架（阶段4）：解析、校验、损坏判定、"没变就不写"这些**最容易改坏
 * 且改坏了不会报错**的逻辑只留这一份；各平台只实现几个原语——文件在不在、读文本、原子写、
 * 以及临时残留的读与删。
 *
 * **写入必须原子**（红线 26）：临时文件 → fsync（能做的话）→ rename。
 * 撕裂写入 = 永久锁库，而这比"KDF 参数被改写"更容易真的发生——改 PIN、开关生物识别、
 * 累加失败计数都会写 boot，一天可能写十几次。
 *
 * **读-改-写必须在自己的互斥区里**，而不是指望调用方（[VaultSession.guard]）替所有人上锁：
 * `bootStore.update` 的调用点有三类（会话、LockViewModel 的失效善后、SecurityViewModel 的
 * 开关生物识别），后两类根本拿不到会话那把锁。不加这一层互斥的表现是并发的两次读-改-写
 * 互相吞掉——生物识别刚写进去的包裹被同时发生的"累加失败计数"覆盖掉，而两份内容都是
 * 合法 JSON，事后从文件里看不出曾经丢过一次写。
 *
 * 用的互斥原语是 [Lock]（platform/Lock.kt 的 expect/actual，commonMain 可用、非挂起）。
 * 刻意不用 `kotlinx.coroutines.sync.Mutex`：`update` 是同步方法（解锁路径在
 * `Dispatchers.Default` 上直接调它，会话那一层还持有着一把阻塞锁），挂起互斥量在这里
 * 要么改成 suspend 传染整条调用链、要么 `runBlocking` 顶着会话那把锁睡——两个都比现在差。
 * [Lock] 是可重入的，所以 [update] 里回调 [read] 不会自锁。
 */
abstract class BaseBootStore : BootStore {

    /** 读-改-写的互斥体。可重入，所以 [update] 嵌套调 [read] / [write] 是安全的。 */
    private val ioGuard = Lock()

    /** 文件是否存在（存在但读不出来是 Corrupt，不是 Missing）。 */
    protected abstract fun fileExists(): Boolean

    /**
     * 读全文。文件不存在或字节无法解码成文本时返回 null——两种情况由
     * [fileExists] 区分开，"存在却读不了"必须落 Corrupt 而不是当全新安装。
     */
    protected abstract fun rawRead(): String?

    /**
     * 原子写全文。失败抛异常，**不允许静默丢弃**——写不进去意味着下次启动回到旧状态。
     *
     * rename 失败时实现方的义务：留着正式文件与临时残留，只报告失败。绝不能"先删了再 rename"
     * ——那会在两次操作之间留下"正式文件消失"的窗口，而那个窗口的表现是整库变成全新安装。
     */
    protected abstract fun atomicWrite(text: String)

    /** 删掉正式文件与一切临时残留（"抹掉重来"用）。 */
    protected abstract fun deleteAll()

    /** 临时残留（`boot.json.tmp`）在不在。默认 false：该平台不会留下可恢复的残留。 */
    protected open fun tempExists(): Boolean = false

    /** 读临时残留的全文，只在正式文件缺失时用作兜底。 */
    protected open fun tempRawRead(): String? = null

    /** 新设备的 deviceId。不是秘密，但注入以便测试。 */
    protected open fun newDeviceId(): String = randomDeviceId()

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * 上一次正式文件缺失、从临时残留读回来的诊断信息。只是给日志与恢复页看的，
     * 不参与判定——读到什么仍然完全由 [read] 的校验决定。
     * 没加 `@Volatile`：它纯粹是诊断线索，读到旧值不影响任何安全判断。
     */
    var lastTempRecovery: String? = null
        protected set

    private val json = Json {
        // 存储格式要能被将来的版本读懂，所以显式写死这几项而不是靠默认值
        prettyPrint = true
        encodeDefaults = true
        // 遇到不认识的键**不忽略**：那说明这份文件来自更新的版本，猜着读会读出错的语义。
        // 落 Corrupt 让用户去恢复备份，比装作读懂了安全得多（红线 9）。
        ignoreUnknownKeys = false
    }

    override fun read(): BootState = ioGuard.withLock { readLocked() }

    private fun readLocked(): BootState {
        if (!fileExists()) return recoverFromTemp() ?: BootState.Missing
        val text = rawRead() ?: return BootState.Corrupt("boot file unreadable")
        // 空文件是撕裂写入最常见的残留形态。它**不是** Missing——把它当全新安装，
        // 用户就会被引导去重设 PIN，而库里的密文从此永久解不开。
        if (text.isBlank()) return BootState.Corrupt("boot file is empty")
        val state = decode(text, where = "boot file")
        // 正式文件读通了，上一次那笔兜底就该翻篇：留着它会让恢复页在一份好文件上说
        // "这次是从临时残留读回来的"，而那是一句假话。
        if (state is BootState.Ok) lastTempRecovery = null
        return state
    }

    /**
     * 正式文件没了、但临时残留里是一份合法记录时从它读回来。
     *
     * 为什么值得兜这一道：rename 失败后实现方**不该**为了重试而先删正式文件（那才是真的
     * 把用户推进"看起来像全新安装"的黑洞），于是残留会留在 .tmp 里。下一次启动如果连
     * 正式文件都没了（进程被杀在两次写之间、或者文件系统把 rename 整个丢了），
     * .tmp 就是唯一那份真相。
     *
     * 判不出来时**照实返回 Corrupt**，而不是"当没有残留"返回 null：正式文件已经不在，
     * 返回 null 就是承认 Missing，于是应用直接回到引导——那恰好是这一道兜底要拦住的表现。
     * 只有"压根没有残留 / 残留是空的"才交回 null，让 Missing 的原有语义自己说话，
     * 不在这里发明第三种状态。
     */
    private fun recoverFromTemp(): BootState? {
        if (!tempExists()) return null
        val text = tempRawRead()?.takeIf { it.isNotBlank() } ?: return null
        val state = decode(text, where = "boot temp file")
        if (state is BootState.Ok) {
            lastTempRecovery = "boot file missing, recovered from temp copy"
        }
        return state
    }

    /** 一份文本 → [BootState]。正式文件与临时残留走同一套校验，判据不许分叉。 */
    private fun decode(text: String, where: String): BootState {
        val record = try {
            json.decodeFromString(BootRecord.serializer(), text)
        } catch (t: Throwable) {
            return BootState.Corrupt("$where is not valid JSON for this format: ${t.message}")
        }
        if (record.format != BootRecord.FORMAT_V1) {
            return BootState.Corrupt("unknown boot format ${record.format}")
        }
        // 引导声称完成，却没有 PIN 那条路——这份文件自相矛盾，只能是坏了。
        // 不修补：修补等于猜，而猜错的代价是整库不可读。
        if (record.onboarded && (record.pinKdf == null || record.dekWrappedByPin == null)) {
            return BootState.Corrupt("onboarded but the PIN wrap is missing")
        }
        // JSON 校验挡得住"格式不对"，挡不住"合法 JSON 但封套是更新的版本写出来的"。
        // 后者交给解锁路径去解会抛异常（崩溃），所以在这里就判损坏，让用户走
        // BootCorrupt 那两条明示的出口（红线 9：跨版本要显式迁移，不猜）。
        record.dekWrappedByPin?.let {
            if (!isKnownSecretBoxEnvelope(it)) return BootState.Corrupt("unrecognized DEK envelope: pin")
        }
        record.dekCheck?.let {
            if (!isKnownSecretBoxEnvelope(it)) return BootState.Corrupt("unrecognized DEK envelope: check")
        }
        return BootState.Ok(record)
    }

    override fun write(record: BootRecord) {
        ioGuard.withLock {
            atomicWrite(json.encodeToString(BootRecord.serializer(), record))
            // 只有真的换上去了才 +1。放在写之后而不是之前：观察者拿到新 revision 就会
            // 立刻重读文件，而在原子写成功之前重读拿到的还是旧内容。
            _revision.update { it + 1 }
        }
    }

    override fun update(transform: (BootRecord) -> BootRecord): BootRecord = ioGuard.withLock {
        val current = when (val state = readLocked()) {
            is BootState.Ok -> state.record
            BootState.Missing -> BootRecord(deviceId = newDeviceId())
            // 明确拒绝在损坏状态上做增量修改。允许的话，一次"累加失败计数"就会把
            // 损坏文件覆盖成一份看起来正常、实际丢了所有密文的新文件。
            is BootState.Corrupt -> throw BootCorruptException(state.reason)
        }
        val updated = transform(current)
        // 没变就不写。每一次写都是一次撕裂风险，而"翻开关又翻回来"这类操作很常见。
        if (updated != current) write(updated)
        updated
    }

    override fun clear() {
        ioGuard.withLock {
            deleteAll()
            lastTempRecovery = null
            _revision.update { it + 1 }
        }
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

/**
 * 在**损坏的** boot 文件上被要求做读-改-写。
 *
 * 为什么不用裸 [IllegalStateException]：这一档不是"程序写错了"，而是"磁盘上那份文件读不通"，
 * 而它是**用户能修好**的一件事（从备份恢复 / 清空重来）。调用点有三处（会话、锁屏的生物识别
 * 失效善后、设置页的开关），后两处只拿到一个异常对象、判不出"到底是文件坏了还是代码写错了"，
 * 只能一律报"没写进去"。专用类型 + [reason] 让它们能原样把原因交给恢复页去说。
 *
 * 仍然继承 [IllegalStateException]：既有调用点（`runCatching` 与按 ISE 兜的 catch）语义不变，
 * 新代码可以按更窄的类型接住它——不这么做的表现是"改一半契约，另一半在运行时才炸"。
 */
class BootCorruptException(val reason: String) :
    IllegalStateException("refusing to update a corrupt boot file: $reason")
