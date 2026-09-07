package com.lc33.tokenvault.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * PBKDF2-HMAC-SHA256 口令派生（阶段1 迁移，替代 Argon2id）。
 *
 * 用 cryptography-kotlin 的 JDK provider 实现（底层 JCA `PBKDF2WithHmacSHA256`），
 * 不引入任何 native 库——Android 与 JVM 单测行为一致，且为阶段2 的 iOS 端铺路
 * （cryptography-kotlin 在 iOS 上用 CryptoKit 原生 PBKDF2，同样参数得到同样结果）。
 *
 * 这一层**只做派生**，不知道派生出来的密钥拿去干什么——包裹 DEK 是
 * [DekEnvelope] 的事。分开的好处是这里可以被基准测试直接调用（[benchmark]），
 * 不需要造一个假的 DEK。
 */
object Pbkdf2Kdf {

    /**
     * 派生 32 字节密钥（KEK）。
     *
     * [password] 用 [CharArray] 而不是 `String`：`String` 不可变、擦不掉（红线 1）。
     * **本函数不负责擦除入参**——调用方通常还要用它做别的事（比如确认两次输入一致），
     * 擦除时机只有调用方知道。
     *
     * PBKDF2 的 password 在 JCA 里是 `char[]`，但 cryptography-kotlin 的
     * `deriveSecretToByteArrayBlocking` 只收 `ByteArray`。所以这里先把 [CharArray]
     * 转成 UTF-8 [ByteArray]（[toUtf8]，不经过 String），派生完立刻擦掉这个中间产物。
     */
    fun derive(password: CharArray, params: KdfParams): ByteArray {
        params.requireWithinCap()
        val passwordBytes = password.toUtf8()
        return try {
            val pbkdf2 = CryptoProvider.provider.get(PBKDF2)
            val derivation = pbkdf2.secretDerivation(
                SHA256,
                iterations = params.iterations,
                outputSize = KdfParams.DERIVED_KEY_BYTES.bytes,
                salt = params.salt,
            )
            derivation.deriveSecretToByteArrayBlocking(passwordBytes)
        } finally {
            passwordBytes.zeroize()
        }
    }

    /**
     * 跑一次派生并返回耗时（毫秒）。
     *
     * 首次设置 PIN 时用它挑档（§7.2）：目标 300–500ms。**它必须由调用方注入时间源**——
     * `crypto/` 是纯 Kotlin 层，不允许直接读当前时间（红线 20），否则一切与耗时有关的
     * 测试都会变成时间敏感的。
     *
     * @param nanoTime 单调时钟，通常是 `System::nanoTime`。
     */
    fun benchmark(params: KdfParams, nanoTime: () -> Long): Long {
        val probe = charArrayOf('0', '0', '0', '0', '0', '0')
        val started = nanoTime()
        try {
            derive(probe, params).zeroize()
        } finally {
            probe.zeroize()
        }
        return (nanoTime() - started) / 1_000_000
    }

    /**
     * 按基准耗时挑一档参数。
     *
     * 分档的理由写在 §7.2：既然 6 位 PIN 挡不住离线穷举，就不该为一个达不到的目标
     * 去付低端机上 1–3 秒的解锁延迟。所以这里是**往下调比往上调更积极**。
     *
     * PBKDF2 只有一个自由度（迭代次数），挑档就是调 iterations。
     *
     * @param elapsedMillis 用 [benchmark] 跑出来的耗时。
     */
    fun chooseParams(baseline: KdfParams, elapsedMillis: Long): KdfParams = when {
        elapsedMillis > SLOW_THRESHOLD_MS -> baseline.copy(
            iterations = KdfParams.MIN_ITERATIONS,
        )

        elapsedMillis < FAST_THRESHOLD_MS -> baseline.copy(
            iterations = KdfParams.MAX_ITERATIONS,
        )

        else -> baseline
    }

    /** 超过它就降档：解锁体验优先。 */
    const val SLOW_THRESHOLD_MS = 500L

    /** 低于它才升档。150ms 以下说明这台机器有余量。 */
    const val FAST_THRESHOLD_MS = 150L
}
