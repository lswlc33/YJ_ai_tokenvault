package com.lc33.tokenvault.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id 口令派生。
 *
 * 用 BouncyCastle 的纯 Java 实现，不引入 `argon2kt`（JNI）：参数目标已经降到
 * 16 MiB / t=2，纯 Java 完全够快，而原生库会让 APK 必须按 ABI 分包（§7.2）。
 *
 * 这一层**只做派生**，不知道派生出来的密钥拿去干什么——包裹 DEK 是
 * [DekEnvelope] 的事。分开的好处是这里可以被基准测试直接调用（[benchmark]），
 * 不需要造一个假的 DEK。
 */
object Argon2idKdf {

    /**
     * 派生 32 字节密钥（KEK）。
     *
     * [password] 用 [CharArray] 而不是 `String`：`String` 不可变、擦不掉（红线 1）。
     * **本函数不负责擦除入参**——调用方通常还要用它做别的事（比如确认两次输入一致），
     * 擦除时机只有调用方知道。
     */
    fun derive(password: CharArray, params: KdfParams): ByteArray {
        params.requireWithinCap()
        val generator = Argon2BytesGenerator().apply {
            init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(params.memoryKib)
                    .withIterations(params.iterations)
                    .withParallelism(params.parallelism)
                    .withSalt(params.salt)
                    .build(),
            )
        }
        val out = ByteArray(KdfParams.DERIVED_KEY_BYTES)
        generator.generateBytes(password, out)
        return out
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
     * @param elapsedMillis 用 [baseline] 跑出来的耗时。
     */
    fun chooseParams(baseline: KdfParams, elapsedMillis: Long): KdfParams = when {
        elapsedMillis > SLOW_THRESHOLD_MS -> baseline.copy(
            memoryKib = KdfParams.MIN_MEMORY_KIB,
            iterations = KdfParams.DEFAULT_ITERATIONS,
        )

        elapsedMillis < FAST_THRESHOLD_MS -> baseline.copy(
            memoryKib = KdfParams.MAX_MEMORY_KIB,
            iterations = KdfParams.MAX_ITERATIONS,
        )

        else -> baseline
    }

    /** 超过它就降档：解锁体验优先。 */
    const val SLOW_THRESHOLD_MS = 500L

    /** 低于它才升档。150ms 以下说明这台机器有余量。 */
    const val FAST_THRESHOLD_MS = 150L
}
