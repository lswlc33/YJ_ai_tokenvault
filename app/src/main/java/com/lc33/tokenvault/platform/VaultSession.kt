package com.lc33.tokenvault.platform

import com.lc33.tokenvault.crypto.Argon2idKdf
import com.lc33.tokenvault.crypto.DekEnvelope
import com.lc33.tokenvault.crypto.DekSlot
import com.lc33.tokenvault.crypto.DecryptionFailedException
import com.lc33.tokenvault.crypto.Hkdf
import com.lc33.tokenvault.crypto.KdfParams
import com.lc33.tokenvault.crypto.RandomBytes
import com.lc33.tokenvault.crypto.RecoveryKey
import com.lc33.tokenvault.crypto.SecureRandomBytes
import com.lc33.tokenvault.crypto.VaultLockedException
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.BiometricAvailability
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.UnlockBackoff

/** 解锁的结果。 */
sealed interface UnlockResult {
    data object Success : UnlockResult

    /** 凭据不对。[backoff] 是**累加之后**的状态，UI 直接拿它画倒计时与"还能再错 N 次"。 */
    data class WrongCredential(val backoff: UnlockBackoff) : UnlockResult

    /** 还在退避中，这次**根本没有去尝试**——所以它不累加失败计数。 */
    data class InBackoff(val backoff: UnlockBackoff) : UnlockResult

    /** 结构性问题：boot 损坏、或者这条路的包裹不存在。与"凭据不对"分开，因为它不该罚用户。 */
    data class Unavailable(val reason: String) : UnlockResult
}

/** 引导完成的产物。[recoveryKey] 必须展示给用户并要求勾选"我已保存"，之后立刻擦除。 */
class OnboardResult(val recoveryKey: CharArray)

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
 */
class VaultSession(
    private val bootStore: BootStore,
    private val nowEpochMs: () -> Long,
    private val random: RandomBytes = SecureRandomBytes,
    private val dekEnvelope: DekEnvelope = DekEnvelope(random = random),
    private val biometricAvailability: () -> BiometricAvailability = {
        BiometricAvailability.NO_HARDWARE
    },
) {

    private val guard = Any()

    private var dek: ByteArray? = null
    private var fieldKey: ByteArray? = null
    private var fingerprintKey: ByteArray? = null

    private var phase: LockPhase = LockPhase.Loading

    /**
     * 当前阶段。
     *
     * 刻意不在这一层做成 `StateFlow`：`platform/` 不该决定 UI 用什么订阅机制，
     * 而且这样这个类在 JVM 单测里不需要协程环境。`di/` 里的包装把它桥成 `StateFlow`。
     */
    fun currentPhase(): LockPhase = synchronized(guard) { phase }

    /** 从 boot 存储重新计算阶段。冷启动与"清空重来"之后调用。 */
    fun refresh(): LockPhase = synchronized(guard) {
        phase = computePhase()
        phase
    }

    private fun computePhase(): LockPhase {
        if (dek != null) return LockPhase.Unlocked
        return when (val state = bootStore.read()) {
            BootState.Missing -> LockPhase.Onboarding
            is BootState.Corrupt -> LockPhase.BootCorrupt(state.reason)
            is BootState.Ok -> {
                val record = state.record
                if (!record.onboarded) {
                    LockPhase.Onboarding
                } else {
                    LockPhase.Locked(
                        backoff = record.backoff(),
                        biometric = if (record.biometricEnabled) {
                            biometricAvailability()
                        } else {
                            // 红线 5：用户关掉之后不得由任何解锁路径悄悄打开，
                            // 所以这里连"系统能不能用"都不问——问了就会有人顺手打开它。
                            BiometricAvailability.NOT_ENABLED_BY_USER
                        },
                        hasRecoveryKey = record.hasRecoveryWrap,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 引导

    /**
     * 引导：生成 DEK、跑基准挑参数、包裹 PIN 与恢复密钥两条路、写一次 boot。
     *
     * **不擦 [pin]**：调用方通常还要用它做"两次输入一致"的比对，擦除时机只有调用方知道。
     * 返回的恢复密钥由调用方负责擦。
     */
    fun onboard(pin: CharArray, benchmarkNanoTime: () -> Long): OnboardResult {
        synchronized(guard) {
            val baseline = KdfParams(salt = random.nextBytes(KdfParams.SALT_BYTES))
            val elapsed = Argon2idKdf.benchmark(baseline, benchmarkNanoTime)
            val chosen = Argon2idKdf.chooseParams(baseline, elapsed)

            val pinKdf = chosen.copy(salt = random.nextBytes(KdfParams.SALT_BYTES))
            // PIN 与恢复密钥**各有一份自己的盐**（§7.1）。共用一份盐的话，
            // 两条路的 KEK 只差在口令上，而恢复密钥的强度优势就被削掉了一半。
            val recoveryKdf = chosen.copy(salt = random.nextBytes(KdfParams.SALT_BYTES))

            val newDek = dekEnvelope.generateDek()
            val recoveryKey = RecoveryKey.generate(random)

            val pinKek = Argon2idKdf.derive(pin, pinKdf)
            val recoveryKek = Argon2idKdf.derive(recoveryKey, recoveryKdf)
            try {
                bootStore.update { current ->
                    current.copy(
                        onboarded = true,
                        pinKdf = pinKdf,
                        recoveryKdf = recoveryKdf,
                        dekWrappedByPin = dekEnvelope.wrap(newDek, pinKek, DekSlot.Pin),
                        dekWrappedByRecovery = dekEnvelope.wrap(newDek, recoveryKek, DekSlot.Recovery),
                        // 引导阶段不开生物识别：它要求先有 DEK 才能用 Keystore 加密，
                        // 而那一步在引导之后单独做（§7.3 的启用流程）。
                        biometricEnabled = false,
                        dekWrappedByBiometric = null,
                        pinFailCount = 0,
                        pinLockUntil = null,
                    )
                }
                adoptDek(newDek)
                phase = LockPhase.Unlocked
                return OnboardResult(recoveryKey)
            } finally {
                pinKek.zeroize()
                recoveryKek.zeroize()
            }
        }
    }

    // ------------------------------------------------------------------ 解锁

    fun unlockWithPin(pin: CharArray): UnlockResult =
        unlockWithPassword(pin, DekSlot.Pin) { it.pinKdf }

    /**
     * 用恢复密钥解锁。输入先过 [RecoveryKey.normalize]——用户照着分组抄，回填时几乎一定
     * 带空格或短横线，不规范化的表现是"我抄得没错但它说不对"。
     */
    fun unlockWithRecoveryKey(input: CharArray): UnlockResult {
        val normalized = RecoveryKey.normalize(input)
        return try {
            if (!RecoveryKey.isWellFormed(normalized)) {
                // 格式就不对，不必去跑 Argon2（那要几百毫秒），但**照样累加失败计数**：
                // 否则它就成了一条不受退避约束的探测通道。
                penalizeAndBuildResult()
            } else {
                unlockWithPassword(normalized, DekSlot.Recovery) { it.recoveryKdf }
            }
        } finally {
            normalized.zeroize()
        }
    }

    private fun unlockWithPassword(
        password: CharArray,
        slot: DekSlot,
        kdfOf: (BootRecord) -> KdfParams?,
    ): UnlockResult = synchronized(guard) {
        val record = when (val state = bootStore.read()) {
            is BootState.Ok -> state.record
            BootState.Missing -> return UnlockResult.Unavailable("no boot record")
            is BootState.Corrupt -> return UnlockResult.Unavailable(state.reason)
        }
        val backoff = record.backoff()
        if (backoff.isActive(nowEpochMs())) return UnlockResult.InBackoff(backoff)

        val params = kdfOf(record) ?: return UnlockResult.Unavailable("no wrap for ${slot.storageKey}")
        val wrapped = record.wrapFor(slot) ?: return UnlockResult.Unavailable("no wrap for ${slot.storageKey}")

        val kek = Argon2idKdf.derive(password, params)
        val unwrapped = try {
            dekEnvelope.unwrap(wrapped, kek, slot)
        } catch (_: DecryptionFailedException) {
            return penalizeAndBuildResult()
        } finally {
            kek.zeroize()
        }

        bootStore.update { it.copy(pinFailCount = 0, pinLockUntil = null) }
        adoptDek(unwrapped)
        phase = LockPhase.Unlocked
        return UnlockResult.Success
    }

    /**
     * 生物识别那条路：Keystore 已经把 DEK 解出来了，这里只负责接收。
     *
     * `crypto/` 不碰 Keystore，所以解密发生在 `platform/BiometricKeyStore` 里，
     * 这个方法是两者之间的唯一接口。**不校验 DEK 内容**——它来自我们自己包裹的密文，
     * GCM 的 tag 已经在 Keystore 那一侧校过了。
     */
    fun unlockWithDek(unwrapped: ByteArray): UnlockResult = synchronized(guard) {
        if (unwrapped.size != DekEnvelope.DEK_BYTES) {
            unwrapped.zeroize()
            return UnlockResult.Unavailable("biometric path produced a wrong-sized DEK")
        }
        bootStore.update { it.copy(pinFailCount = 0, pinLockUntil = null) }
        adoptDek(unwrapped)
        phase = LockPhase.Unlocked
        return UnlockResult.Success
    }

    private fun penalizeAndBuildResult(): UnlockResult {
        val updated = bootStore.update { current ->
            val attempts = current.pinFailCount + 1
            current.copy(
                pinFailCount = attempts,
                pinLockUntil = UnlockBackoff.nextLockedUntil(attempts, nowEpochMs()),
            )
        }
        phase = computePhase()
        return UnlockResult.WrongCredential(updated.backoff())
    }

    // ------------------------------------------------------------------ 锁定与借用

    /** 锁定：清零 DEK 与两个子密钥，**不关库**（§6.1 推论 1）。 */
    fun lock() = synchronized(guard) {
        dek?.zeroize()
        fieldKey?.zeroize()
        fingerprintKey?.zeroize()
        dek = null
        fieldKey = null
        fingerprintKey = null
        phase = computePhase()
    }

    val isUnlocked: Boolean get() = synchronized(guard) { dek != null }

    /**
     * 借用字段级加密子密钥。
     *
     * 锁定态抛 [VaultLockedException]，而不是返回 null——红线 8 的同一条道理：
     * 一个返回 null 的 API 迟早被写成 `?: ""`。
     */
    fun <R> withFieldKey(block: (ByteArray) -> R): R = synchronized(guard) {
        block(fieldKey ?: throw VaultLockedException())
    }

    fun <R> withFingerprintKey(block: (ByteArray) -> R): R = synchronized(guard) {
        block(fingerprintKey ?: throw VaultLockedException())
    }

    /**
     * 借用 **DEK 本身**。
     *
     * 只有一个合法调用方：生物识别的**启用**流程——它必须把 DEK 交给 Keystore 的 Cipher
     * 去加密（§7.3 的启用流程），而那是这个应用里唯一需要 DEK 原文的地方。
     * 其余所有加解密都该走 [withFieldKey]，因为字段级密钥是从 DEK 派生的、暴露它不等于
     * 暴露 DEK（红线 6 的"他人短暂借用"就是这个意思）。
     *
     * 之所以不提供"复制一份 DEK 出去"的 API：复制出去的那一份没人负责擦，
     * 而 `finally` 里擦不掉别人存下来的引用。
     */
    fun <R> withDek(block: (ByteArray) -> R): R = synchronized(guard) {
        block(dek ?: throw VaultLockedException())
    }

    // ------------------------------------------------------------------ 改 PIN

    /**
     * 改 PIN。**必须 O(1)**（红线 2）：只重新包裹一次 DEK，一次 boot 写入，业务表零 UPDATE。
     *
     * 用内存里的 DEK 而不是"用旧 PIN 解一次再用新 PIN 包一次"，所以要求当前已解锁——
     * 这也顺带保证了调用方确实证明过自己知道旧 PIN。
     *
     * 换新盐：不换的话，改 PIN 之后旧 PIN 派生出的 KEK 与新的只差在口令上，
     * 而攻击者手里那份旧密文仍然对应同一个盐。
     */
    fun changePin(newPin: CharArray): Boolean = synchronized(guard) {
        val currentDek = dek ?: throw VaultLockedException()
        val record = (bootStore.read() as? BootState.Ok)?.record ?: return false
        val params = (record.pinKdf ?: return false).copy(salt = random.nextBytes(KdfParams.SALT_BYTES))
        val kek = Argon2idKdf.derive(newPin, params)
        try {
            bootStore.update {
                it.copy(
                    pinKdf = params,
                    dekWrappedByPin = dekEnvelope.wrap(currentDek, kek, DekSlot.Pin),
                    pinFailCount = 0,
                    pinLockUntil = null,
                )
            }
        } finally {
            kek.zeroize()
        }
        return true
    }

    /**
     * 重新生成恢复密钥（设置里的那个入口）。旧的**立刻失效**：包裹被覆盖，旧密钥再也解不开。
     */
    fun regenerateRecoveryKey(): CharArray? = synchronized(guard) {
        val currentDek = dek ?: throw VaultLockedException()
        val record = (bootStore.read() as? BootState.Ok)?.record ?: return null
        val params = (record.recoveryKdf ?: KdfParams(salt = ByteArray(KdfParams.SALT_BYTES)))
            .copy(salt = random.nextBytes(KdfParams.SALT_BYTES))
        val key = RecoveryKey.generate(random)
        val kek = Argon2idKdf.derive(key, params)
        try {
            bootStore.update {
                it.copy(
                    recoveryKdf = params,
                    dekWrappedByRecovery = dekEnvelope.wrap(currentDek, kek, DekSlot.Recovery),
                )
            }
        } finally {
            kek.zeroize()
        }
        return key
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

private fun BootRecord.wrapFor(slot: DekSlot): ByteArray? = when (slot) {
    DekSlot.Pin -> dekWrappedByPin
    DekSlot.Biometric -> dekWrappedByBiometric
    DekSlot.Recovery -> dekWrappedByRecovery
}
