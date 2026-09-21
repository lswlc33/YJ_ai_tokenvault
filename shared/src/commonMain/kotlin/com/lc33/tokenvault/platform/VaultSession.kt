package com.lc33.tokenvault.platform

import com.lc33.tokenvault.crypto.CryptoException
import com.lc33.tokenvault.crypto.DecryptionFailedException
import com.lc33.tokenvault.crypto.DekEnvelope
import kotlin.concurrent.Volatile
import com.lc33.tokenvault.crypto.DekSlot
import com.lc33.tokenvault.engine.ProbeSession
import com.lc33.tokenvault.crypto.Hkdf
import com.lc33.tokenvault.crypto.KdfParams
import com.lc33.tokenvault.crypto.KnownSecrets
import com.lc33.tokenvault.crypto.Pbkdf2Kdf
import com.lc33.tokenvault.crypto.RandomBytes
import com.lc33.tokenvault.crypto.SecureRandomBytes
import com.lc33.tokenvault.crypto.VaultLockedException
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.UnlockBackoff

/** 解锁的结果。 */
sealed interface UnlockResult {
    data object Success : UnlockResult

    /** 凭据不对。[backoff] 是**累加之后**的状态，UI 直接拿它画倒计时与"还能再错 N 次"。 */
    data class WrongCredential(val backoff: UnlockBackoff) : UnlockResult

    /** 还在退避中，这次**根本没有去尝试**——所以它不累加失败计数。 */
    data class InBackoff(val backoff: UnlockBackoff) : UnlockResult

    /**
     * 结构性问题：boot 损坏、封套版本不认识、KDF 参数超出封顶、或者这条路的包裹不存在。
     * 与"凭据不对"分开，因为它不该罚用户。
     *
     * 抛出这一档的同时，会话已经把阶段钉在 [LockPhase.BootCorrupt] 上（见
     * [structuralBootFailure]）——界面据此画恢复页，而不是让异常一路冒到 Compose 之外。
     */
    data class Unavailable(val reason: String) : UnlockResult
}

/**
 * 会话（§7.4）。**唯一持有明文 DEK 的对象**（红线 6、25）。
 *
 * 三条设计决定，每一条都有具体代价：
 *
 * 1. **不持有 Room 实例。** 库是应用级单例、启动即建；锁定 = 清零 DEK + 跳锁屏，**不关库**
 *    （§6.1 推论 1）。这样只碰公开数据的后台任务（models.dev 同步、日志清理）在锁定态
 *    也能跑。反过来说，任何需要明文秘密的调用在锁定态必须抛 [VaultLockedException]，
 *    而不是返回空——返回空会让列表页看起来"数据没了"。
 * 2. **子密钥在解锁时一次派生好并缓存。** 反正 DEK 已经在内存里，缓存两个子密钥不增加
 *    任何暴露面，却省掉每次访问一次 HKDF。锁定时它们和 DEK 一起清零。
 * 3. **借用而不是交出。** [withFieldKey] 把引用限制在 lambda 里；调用方存下来是能存的，
 *    但那会是显式的错误行为，而不是 API 默许的。
 *
 * 时间一律注入（红线 20），所以退避逻辑的测试不是时间敏感的。
 *
 * **阶段1 迁移**：安全模型简化为只有 PIN 一条解锁路。生物识别（Keystore）与恢复密钥
 * 两条路已删；`onboard` 因此不再生成恢复密钥，`unlockWithRecoveryKey` /
 * `regenerateRecoveryKey` 一并移除。生物识别 2026-09-18 加回，走 [unlockWithDek]。
 */
class VaultSession(
    private val bootStore: BootStore,
    private val nowEpochMs: () -> Long,
    private val random: RandomBytes = SecureRandomBytes,
    private val dekEnvelope: DekEnvelope = DekEnvelope(random = random),
    private val knownSecrets: KnownSecrets = KnownSecrets(),
    /**
     * 单调时钟。退避的"进程内剩余时间"用它判（见 [backoffAnchor]），所以它必须可注入——
     * 否则那条防回拨的逻辑在测试里根本没法验证。
     */
    private val monotonicNano: () -> Long = ::monotonicNanoTime,
) : ProbeSession {

    private val guard = Lock()

    private var dek: ByteArray? = null
    private var fieldKey: ByteArray? = null
    private var fingerprintKey: ByteArray? = null

    private var phase: LockPhase = LockPhase.Loading

    /**
     * boot 在**结构上**不可用的原因。非 null 时阶段一律是 [LockPhase.BootCorrupt]。
     *
     * 为什么要单独记一份而不是"让 [computePhase] 自己从文件里再判一次"：触发这一条的
     * 往往是"文件是合法 JSON、但封套/参数不认"，存储层与密码学层各执一词，
     * 而解锁已经失败过一次了。不钉住的话，界面下一次 refresh 阶段就会回到"请输入 PIN"，
     * 用户只会看到"我明明输对了却解不开"，而真正的出路（从备份恢复 / 清空重来）从来不出现。
     *
     * 清空它的只有 [refresh]（用户已经换了一份 boot：清空重来、或从备份恢复之后）与
     * [onboard]（重新引导会写一份全新的记录）。
     */
    private var structuralBootFailure: String? = null

    /**
     * 进程内的退避锚点：写下 `pinLockUntil` 那一刻同时抓的 (墙上, 单调) 一对读数。
     *
     * 为什么要它：`pinLockUntil` 是一枚**墙上**时刻，把系统时间往前调（调快）就能提前解除退避。
     * 进程活着的时候单调时钟是绕不过去的，所以剩余时间取两者的较大值（见 [displayBackoff]）。
     * 跨进程（杀了再开）没有锚点可用，只能回到墙上时钟——这是明知的上限：
     * 攻击者真要绕过退避，直接改 boot 里那两个字段就行（§6.1 末尾）。
     */
    private class BackoffAnchor(
        val deadlineEpochMs: Long,
        val epochAtWriteMs: Long,
        val nanoAtWriteNs: Long,
    )

    private var backoffAnchor: BackoffAnchor? = null

    /**
     * 当前阶段。
     *
     * 刻意不在这一层做成 `StateFlow`：`platform/` 不该决定 UI 用什么订阅机制，
     * 而且这样这个类在 JVM 单测里不需要协程环境。`di/` 里的包装把它桥成 `StateFlow`。
     */
    fun currentPhase(): LockPhase = guard.withLock { phase }

    /**
     * 从 boot 存储重新计算阶段。冷启动与"清空重来 / 从备份恢复"之后调用。
     *
     * 这里也是**唯一**清除 [structuralBootFailure] 的地方：重新读盘意味着用户可能已经换了
     * 一份 boot，那一次判定就当它是新的。
     */
    fun refresh(): LockPhase = guard.withLock {
        structuralBootFailure = null
        phase = computePhase()
        phase
    }

    private fun computePhase(): LockPhase {
        structuralBootFailure?.let { return LockPhase.BootCorrupt(it) }
        val state = bootStore.read()
        if (dek != null) {
            // DEK 在内存里，但**引导可能还没走完**：`onboard()` 之后还有"确认"那一步，
            // 而 `onboarded` 要到用户确认之后才落盘。这里不看这一眼的后果很具体——
            // Activity 被销毁（系统返回键、进程被回收、"不保留活动"）之后重建时，
            // 阶段会直接报 Unlocked，于是引导流程被跳过去。
            if (state is BootState.Ok && !state.record.onboarded) return LockPhase.Onboarding
            // boot 读不出来（Missing / Corrupt）时**不**把用户踢出已解锁的会话：
            // DEK 还在内存里，这一刻还能导出备份，踢出去等于把唯一的救命窗口关掉。
            return LockPhase.Unlocked
        }
        return when (state) {
            BootState.Missing -> LockPhase.Onboarding
            is BootState.Corrupt -> LockPhase.BootCorrupt(state.reason)
            is BootState.Ok -> {
                val record = state.record
                if (!record.onboarded) {
                    LockPhase.Onboarding
                } else {
                    LockPhase.Locked(backoff = displayBackoff(record))
                }
            }
        }
    }

    /** 把 boot 判成结构性不可用，并把阶段钉在恢复页上。返回给解锁路径当结果用。 */
    private fun markBootStructurallyUnusable(reason: String): UnlockResult {
        structuralBootFailure = reason
        phase = LockPhase.BootCorrupt(reason)
        return UnlockResult.Unavailable(reason)
    }

    /**
     * 结构性失败之外的"这条路走不通"（没有 boot、缺包裹）：阶段按存储重算一次再返回，
     * 让界面停在该停的地方（全新安装就是引导页）。
     *
     * 与上面那个函数的分工很重要：**能读通但内容不认**才钉 [LockPhase.BootCorrupt]，
     * "根本没有记录"钉上去等于把新装用户关在恢复页里。
     */
    private fun unavailableWithPhase(reason: String): UnlockResult {
        phase = computePhase()
        return UnlockResult.Unavailable(reason)
    }

    // ------------------------------------------------------------------ 引导

    /**
     * 引导：生成 DEK、跑基准挑参数、包裹 PIN 这一条路、写一次 boot。
     *
     * **这不是引导的终点**：`onboarded` 要到 [completeOnboarding] 才落盘，理由见那个方法。
     *
     * **不擦 [pin]**：调用方通常还要用它做"两次输入一致"的比对，擦除时机只有调用方知道。
     */
    fun onboard(pin: CharArray, benchmarkNanoTime: () -> Long) {
        guard.withLock {
            val baseline = KdfParams(salt = random.nextBytes(KdfParams.SALT_BYTES))
            val elapsed = Pbkdf2Kdf.benchmark(baseline, benchmarkNanoTime)
            val chosen = Pbkdf2Kdf.chooseParams(baseline, elapsed)

            val pinKdf = chosen.copy(salt = random.nextBytes(KdfParams.SALT_BYTES))

            val newDek = dekEnvelope.generateDek()

            val pinKek = Pbkdf2Kdf.derive(pin, pinKdf)
            try {
                // 引导写下的是一份**全新**记录：上一轮引导残留的生物识别状态必须一起清掉。
                // 不清的后果很具体——DEK 已经换了一把，而 boot 里还留着旧 DEK 的 Keystore
                // 包裹与 `biometricEnabled = true`，于是锁屏画着一个"通向旧密钥"的入口，
                // 按下去拿回来的字节和新 DEK 对不上（旧版本没有 dekCheck，甚至校验不出来），
                // 用户看到的是"生物识别能解锁，但数据全解不开"。
                // 同理清掉 structuralBootFailure：重新引导就是"这件事已经处理过了"。
                structuralBootFailure = null
                bootStore.update { current ->
                    current.copy(
                        // **这里刻意还不写 `onboarded = true`**：引导要到用户确认
                        // 才算走完（[completeOnboarding]）。在那之前被杀掉，下次启动应当回到引导，
                        // 而不是进到一个没走完引导的库里。
                        onboarded = false,
                        pinKdf = pinKdf,
                        dekWrappedByPin = dekEnvelope.wrap(newDek, pinKek, DekSlot.Pin),
                        dekCheck = dekEnvelope.sealCheck(newDek),
                        biometricEnabled = false,
                        dekWrappedByBiometric = null,
                        pinFailCount = 0,
                        pinLockUntil = null,
                    )
                }
                backoffAnchor = null
                adoptDek(newDek)
                // 仍是 Onboarding：DEK 已经在内存里（[isUnlocked] 为真），但阶段上还没走完。
                phase = computePhase()
            } finally {
                pinKek.zeroize()
            }
        }
    }

    /**
     * 引导的最后一步：用户确认。**只有到这一刻 `onboarded` 才落盘。**
     *
     * 分成两次写入是刻意的：引导期间多写一次 boot 文件的风险，远小于"用户带着一个
     * 没走完引导的库继续用下去"。
     *
     * @throws VaultLockedException 未解锁。走到这一步必然是刚 [onboard] 完，所以真抛了
     *   说明调用顺序错了，而不是用户做了什么。
     */
    fun completeOnboarding() = guard.withLock {
        if (dek == null) throw VaultLockedException()
        bootStore.update { it.copy(onboarded = true) }
        phase = computePhase()
    }

    // ------------------------------------------------------------------ 解锁

    /**
     * 用 PIN 解锁。
     *
     * **异常分类是这一层的安全边界**（红线 8 的另一面）：`crypto/` 一律抛异常而不返回 null，
     * 所以这里必须把"猜错了"和"这东西本版本不认识"分成两类处理——
     * 前者累加失败计数（[DecryptionFailedException]），后者一律不罚用户，
     * 并把会话钉在 [LockPhase.BootCorrupt] 上（[markBootStructurallyUnusable]）。
     * 只 catch `DecryptionFailedException` 的旧写法会让
     * [com.lc33.tokenvault.crypto.UnsupportedEnvelopeException] /
     * [com.lc33.tokenvault.crypto.InvalidKdfParamsException] 一路冒到 Compose 之外，
     * 表现是锁屏页白屏崩溃——而那正是用户唯一还该能操作的那一屏。
     */
    fun unlockWithPin(pin: CharArray): UnlockResult = guard.withLock {
        val record = when (val state = bootStore.read()) {
            is BootState.Ok -> state.record
            BootState.Missing -> return@withLock unavailableWithPhase("no boot record")
            is BootState.Corrupt -> return@withLock markBootStructurallyUnusable(state.reason)
        }
        val backoff = displayBackoff(record)
        if (backoff.isActive(nowEpochMs())) return@withLock UnlockResult.InBackoff(backoff)

        val params = record.pinKdf ?: return@withLock unavailableWithPhase("no wrap for ${DekSlot.Pin.storageKey}")
        val wrapped = record.dekWrappedByPin
            ?: return@withLock unavailableWithPhase("no wrap for ${DekSlot.Pin.storageKey}")

        // `pin` 的擦除归调用方（LockViewModel 的 finally），这里只负责擦自己派出的 KEK。
        // 捕获整个 [CryptoException]：派生这一步没有"凭据不对"这种失败——口令对不对要到
        // 下面解包时才看得出来。所以它抛任何密码学异常都只可能是参数/算法层面的结构性问题。
        val kek = try {
            Pbkdf2Kdf.derive(pin, params)
        } catch (e: CryptoException) {
            // 参数超出封顶：算下去要么慢到 ANR、要么 OOM，而这与"PIN 猜错"没有任何关系。
            return@withLock markBootStructurallyUnusable(e.message ?: "kdf params rejected")
        }

        val unwrapped = try {
            dekEnvelope.unwrap(wrapped, kek, DekSlot.Pin)
        } catch (_: DecryptionFailedException) {
            return@withLock penalizeAndBuildResult()
        } catch (e: CryptoException) {
            // 封套版本/算法不认识（跨版本写入、或 boot 被改过）：走恢复页，不罚计数。
            return@withLock markBootStructurallyUnusable(e.message ?: "unrecognized DEK envelope")
        } finally {
            kek.zeroize()
        }

        adoptDek(unwrapped)
        phase = LockPhase.Unlocked
        // 清零计数放在解锁**之后**：这一步失败（boot 在解锁这一瞬被人改坏了）不该把一次
        // 成功的解锁变成失败——用户此刻是对的。失败只是"下次还会看到旧的退避计数"，
        // 偏严不偏松，所以记不下就留给下一次写。
        //
        // 同一趟写顺手**补 `dekCheck`**（缺什么补什么，已有就原样留着）：0.1.1 及更早的记录里
        // 没有这一项，而只有此刻这把 DEK 是 PIN 槽 AEAD tag 认证过的——生物识别那条路拿回来的
        // 字节正是待验对象，不能拿它去盖章。不补的话，升级设备只要用户不改 PIN，
        // `unlockWithDek` 里的身份校验就一直空转（见那里的 `check != null` 分支）。
        runCatching {
            bootStore.update { current ->
                current.copy(
                    pinFailCount = 0,
                    pinLockUntil = null,
                    dekCheck = current.dekCheck ?: dekEnvelope.sealCheck(unwrapped),
                )
            }
        }.onSuccess { backoffAnchor = null }
        return@withLock UnlockResult.Success
    }

    /**
     * 记下一次"PIN 猜错"并换算成给界面看的退避。
     *
     * **这一步不许把异常交出去**：调用点在解锁路径上，而 [LockViewModel] 那一边只有
     * `try { … } finally { pin.zeroize() }`——没有 catch，异常会直接冒到 Compose 之外，
     * 表现还是锁屏页白屏崩溃（红线 8）。这里两类失败分开：
     * - [BootCorruptException]：文件本身读不通了（解锁这一瞬被人改坏），钉 [LockPhase.BootCorrupt]
     *   让用户走那两条明示的出口；
     * - 其它（磁盘满、rename 失败的 IOException）：文件没坏，只是这次写不上。钉恢复页就成了假话，
     *   所以只报"这条路暂时不可用"，让用户重试或改用 PIN——**不解锁**，偏严不偏松。
     */
    private fun penalizeAndBuildResult(): UnlockResult {
        val updated = try {
            bootStore.update { current ->
                val attempts = current.pinFailCount + 1
                current.copy(
                    pinFailCount = attempts,
                    pinLockUntil = UnlockBackoff.nextLockedUntil(attempts, nowEpochMs()),
                )
            }
        } catch (e: BootCorruptException) {
            return markBootStructurallyUnusable(e.reason)
        } catch (e: Exception) {
            return unavailableWithPhase("failed to record the failed attempt: ${e.message}")
        }
        // 写下 deadline 的那一刻抓锚点：晚一步抓就会漏掉"写完到下一次读"之间的真实流逝，
        // 而那正是"进程内把时钟往前拨"能偷到的额度。
        backoffAnchor = updated.pinLockUntil?.let {
            BackoffAnchor(deadlineEpochMs = it, epochAtWriteMs = nowEpochMs(), nanoAtWriteNs = monotonicNano())
        }
        phase = computePhase()
        return UnlockResult.WrongCredential(updated.backoff())
    }

    /**
     * 把 boot 里的退避状态换算成"给界面看的那一份"。
     *
     * 判据取两个时钟里**走得慢的那个**：墙钟说"到点了"但单调时钟只走了 1 秒，就是有人在
     * 进程活着的时候把系统时间往前拨——这种拨法正是绕开 §7.2 最省事的做法。
     * 单调时钟在 iOS 上不含设备睡眠（见 [monotonicNanoTime] 的说明），但退避最长 1 小时、
     * 而锁屏退避期间进程基本不会被睡掉，这里的偏差方向是"多等一会儿"，可以接受。
     *
     * 没有锚点（跨进程重启）时原样返回：持久化的 deadline 是唯一依据。
     * 这是明知的上限，攻击者真要绕过退避，改 boot 里那两个字段就够了（§6.1 末尾）。
     */
    private fun displayBackoff(record: BootRecord): UnlockBackoff {
        val persisted = record.backoff()
        val deadline = persisted.lockedUntilEpochMs ?: return persisted
        val anchor = backoffAnchor?.takeIf { it.deadlineEpochMs == deadline } ?: return persisted
        val monotonicElapsedMs = (monotonicNano() - anchor.nanoAtWriteNs) / 1_000_000
        val remainingFromMonotonic = deadline - (anchor.epochAtWriteMs + monotonicElapsedMs)
        val remainingFromWall = deadline - nowEpochMs()
        val remaining = maxOf(remainingFromWall, remainingFromMonotonic)
        // 换成"从现在起算的等价 deadline"，好让 UnlockBackoff 的语义（对注入时刻求剩余）
        // 在两条路上完全一致——界面只认 remainingSeconds(now)。
        return UnlockBackoff(
            failedAttempts = persisted.failedAttempts,
            lockedUntilEpochMs = if (remaining > 0) nowEpochMs() + remaining else null,
        )
    }

    /**
     * 用生物识别那条路取回的明文 DEK 解锁（§7.3）。
     *
     * **[candidate] 的所有权交出去**：成功时它直接成为会话的 DEK（锁定时统一擦除），
     * 失败或长度不对时由这里擦掉。调用方**不要**在移交之后再擦它。
     *
     * **光校验长度不够**：平台（Keystore / Keychain）把 DEK 原样交回，路上没有任何认证 tag
     * 可验，于是"任何 32 字节都算解锁成功"。所以这里用 `boot.dekCheck` 确认
     * "交回来的确实是被包裹的那一把"（见 [DekEnvelope.verifyCheck]）。不校验的表现不是
     * "解不开"而是"解得开但全是乱码"——平台给回了别的东西时字段密文一律对不上，
     * 用户会以为数据被改了，而应用自始至终显示"已解锁"。
     *
     * 旧记录（[BootRecord.dekCheck] 为 null）**跳过**校验：那是升级设备上的既成事实，
     * 拒绝等于把已经在用的生物识别入口直接打死。缺的这一份会在下一次引导或改 PIN 时补上。
     */
    fun unlockWithDek(candidate: ByteArray): UnlockResult = guard.withLock {
        if (candidate.size != DekEnvelope.DEK_BYTES) {
            candidate.zeroize()
            return@withLock UnlockResult.Unavailable("unexpected DEK length ${candidate.size}")
        }
        val record = when (val state = bootStore.read()) {
            is BootState.Ok -> state.record
            BootState.Missing -> {
                candidate.zeroize()
                return@withLock unavailableWithPhase("no boot record")
            }
            is BootState.Corrupt -> {
                candidate.zeroize()
                return@withLock markBootStructurallyUnusable(state.reason)
            }
        }
        val check = record.dekCheck
        if (check != null) {
            val matches = try {
                dekEnvelope.verifyCheck(candidate, check)
            } catch (e: CryptoException) {
                // 校验密文本身的封套不认识：boot 被改坏 / 来自更新的版本，与"凭据不对"是两件事。
                candidate.zeroize()
                return@withLock markBootStructurallyUnusable(e.message ?: "unrecognized dek-check envelope")
            }
            if (!matches) {
                candidate.zeroize()
                // 不钉 BootCorrupt：boot 没坏，坏的是平台侧那条路。界面上这属于"凭据失效"，
                // 出路是用 PIN 解锁后重新开启生物识别。
                return@withLock UnlockResult.Unavailable("platform DEK does not match this vault")
            }
        }
        // 生物识别不参与 PIN 的失败计数：拿不到凭据是"这条路失效了"，
        // 不是"PIN 猜错了"，不该累加退避罚用户。
        adoptDek(candidate)
        phase = LockPhase.Unlocked
        runCatching { bootStore.update { it.copy(pinFailCount = 0, pinLockUntil = null) } }
            .onSuccess { backoffAnchor = null }
        return@withLock UnlockResult.Success
    }

    /**
     * 借用明文 DEK，**只给生物识别的启用流程用**：那一处必须把 DEK 交给 Keystore / Keychain
     * 去包裹，而那两个接口只收字节。**不提供"复制一份 DEK 出去"的 API**——复制出去的那一份
     * 没人负责擦。锁定态抛 [VaultLockedException]。
     */
    fun <R> withDek(block: (ByteArray) -> R): R = guard.withLock {
        block(dek ?: throw VaultLockedException())
    }

    // ------------------------------------------------------------------ 锁定与借用

    /**
     * 锁定：清零 DEK 与两个子密钥，**不关库**（§6.1 推论 1）。
     *
     * 锁定的语义是"我不再持有这些秘密了"，所以明文暂时都清掉。
     *
     * 系统性说明：锁定这一步**不再清空系统剪贴板**（决策 none.md §7.5，移除自动清除机制
     * 后一并去掉）。剪贴板里的内容由用户自行处理。
     */
    fun lock() = guard.withLock {
        dek?.zeroize()
        fieldKey?.zeroize()
        fingerprintKey?.zeroize()
        dek = null
        fieldKey = null
        fingerprintKey = null
        // 锁定即清空「已知明文清单」：脱敏器（红线 32 第一道）不该在锁上之后还记着
        // 上一把被展开过的密钥明文。
        knownSecrets.clear()
        phase = computePhase()
    }

    override val isUnlocked: Boolean get() = guard.withLock { dek != null }

    /**
     * 借用字段级加密子密钥。
     *
     * 锁定态抛 [VaultLockedException]，而不是返回 null——红线 8 的同一条道理：
     * 一个返回 null 的 API 迟早被写成 `?: ""`。
     */
    fun <R> withFieldKey(block: (ByteArray) -> R): R = guard.withLock {
        block(fieldKey ?: throw VaultLockedException())
    }

    fun <R> withFingerprintKey(block: (ByteArray) -> R): R = guard.withLock {
        block(fingerprintKey ?: throw VaultLockedException())
    }

    // ------------------------------------------------------------------ 改 PIN

    /**
     * 改 PIN。**必须 O(1)**（红线 2）：只重新包裹一次 DEK，一次 boot 写入，业务表零 UPDATE。
     *
     * 用内存里的 DEK 而不是"用旧 PIN 解一次再用新 PIN 包一次"，所以要求当前已解锁。
     * **这里刻意不验旧 PIN**：验旧 PIN 该走 [unlockWithPin]，那样它才受退避约束——
     * 单独做一个不计次的 `verifyPin()` 等于给改 PIN 页开一个绕过 §7.2 的入口。
     *
     * 换新盐：不换的话，改 PIN 之后旧 PIN 派生出的 KEK 与新的只差在口令上，
     * 而攻击者手里那份旧密文仍然对应同一个盐。
     *
     * @throws VaultLockedException 未解锁。
     * @throws IllegalStateException boot 记录读不出来或缺 PIN 参数——**不返回 false**：
     *   返回布尔值会让调用方拿不到原因，而红线 8 的同一条道理在这里也成立
     *   （一个返回 false 的写操作迟早被写成 `if (!ok) {}`）。这两种情况在"已解锁"的前提下
     *   都不该发生，真发生了说明 boot 在解锁之后被改坏了，属于要让用户知道的事。
     */
    fun changePin(newPin: CharArray) = guard.withLock {
        val currentDek = dek ?: throw VaultLockedException()
        val record = (bootStore.read() as? BootState.Ok)?.record
            ?: throw IllegalStateException("boot record unavailable while changing PIN")
        val existing = record.pinKdf
            ?: throw IllegalStateException("boot record has no PIN kdf params")
        val params = existing.copy(salt = random.nextBytes(KdfParams.SALT_BYTES))
        val kek = Pbkdf2Kdf.derive(newPin, params)
        try {
            bootStore.update {
                it.copy(
                    pinKdf = params,
                    dekWrappedByPin = dekEnvelope.wrap(currentDek, kek, DekSlot.Pin),
                    // 顺手补上平台 DEK 的身份校验密文。DEK 没变所以校验内容不变，
                    // 但**旧版本引导的设备第一次有了这一份**——改 PIN 是升级后最常发生的
                    // 一次 boot 重写，用它来补齐比再加一个"迁移入口"少一处能写错的地方。
                    dekCheck = dekEnvelope.sealCheck(currentDek),
                    pinFailCount = 0,
                    pinLockUntil = null,
                )
            }
            backoffAnchor = null
        } finally {
            kek.zeroize()
        }
    }

    private fun adoptDek(newDek: ByteArray) {
        dek?.zeroize()
        fieldKey?.zeroize()
        fingerprintKey?.zeroize()
        dek = newDek
        fieldKey = Hkdf.fieldKey(newDek)
        fingerprintKey = Hkdf.fingerprintKey(newDek)
    }
}

private fun BootRecord.backoff() = UnlockBackoff(
    failedAttempts = pinFailCount,
    lockedUntilEpochMs = pinLockUntil,
)
