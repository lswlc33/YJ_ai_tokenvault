package com.lc33.tokenvault.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Argon2id 的参数，**随密文一起存**（红线 3）。
 *
 * 这一条是本项目最容易埋定时炸弹的地方，所以把理由写在类型上：解锁时**一律读存储值**，
 * 绝不拿编译期常量去"纠正"它。一旦某个版本发现存储值与常量不一致就改写存储值，
 * 那么用旧参数包裹的 DEK 立刻永久解不开——而这种 bug 只在升级后的老用户身上出现，
 * 测试机上永远看不见。
 *
 * 参数目标不是抵抗离线穷举（6 位 PIN 只有 10⁶ 空间，一张消费级 GPU 分钟级就能扫完，
 * 无论参数调多高），而是"在不拖慢解锁体验的前提下顺手把成本抬一点"。取舍写在 §7.2
 * 与"关于"页里，不假装能防。
 *
 * @param memoryKib 内存，单位 KiB。
 * @param iterations 迭代次数（Argon2 的 t）。
 * @param parallelism 并行度（Argon2 的 p）。
 * @param salt 16 字节随机盐。PIN 与恢复密钥各有一份自己的盐。
 */
@Serializable
data class KdfParams(
    @SerialName("algo") val algorithm: String = ALGORITHM_ARGON2ID,
    @SerialName("m") val memoryKib: Int = DEFAULT_MEMORY_KIB,
    @SerialName("t") val iterations: Int = DEFAULT_ITERATIONS,
    @SerialName("p") val parallelism: Int = DEFAULT_PARALLELISM,
    @SerialName("salt") val salt: ByteArray,
) {
    init {
        require(algorithm == ALGORITHM_ARGON2ID) { "only argon2id is supported, got $algorithm" }
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes, got ${salt.size}" }
    }

    /**
     * 是否在封顶值以内。
     *
     * 备份包的 KDF 参数由**导出端**写死在 header 里，导入端只能照着算（§12.1）。
     * 快设备导出的高参数包在慢设备上可能几秒甚至 OOM，而那时用户没有任何降参的办法——
     * 所以封顶不是性能优化，是可恢复性的保证。
     */
    fun withinCap(): Boolean =
        memoryKib in MIN_MEMORY_KIB..MAX_MEMORY_KIB &&
            iterations in MIN_ITERATIONS..MAX_ITERATIONS &&
            parallelism in 1..MAX_PARALLELISM

    fun requireWithinCap() {
        if (!withinCap()) {
            throw InvalidKdfParamsException(
                "KDF params out of range: m=$memoryKib t=$iterations p=$parallelism; " +
                    "cap is m=$MAX_MEMORY_KIB t=$MAX_ITERATIONS p=$MAX_PARALLELISM",
            )
        }
    }

    // data class 带 ByteArray 时自动生成的 equals/hashCode 比的是引用，必须手写。
    // 不手写的后果很隐蔽：两份内容相同的参数会被判为不等，于是"参数没变"的判断永远为假。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KdfParams) return false
        return algorithm == other.algorithm &&
            memoryKib == other.memoryKib &&
            iterations == other.iterations &&
            parallelism == other.parallelism &&
            salt.contentEquals(other.salt)
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + memoryKib
        result = 31 * result + iterations
        result = 31 * result + parallelism
        result = 31 * result + salt.contentHashCode()
        return result
    }

    /** 刻意不打印盐。参数会进日志与错误消息，盐不该跟着出去。 */
    override fun toString(): String =
        "KdfParams(algo=$algorithm, m=$memoryKib, t=$iterations, p=$parallelism, salt=<${salt.size}B>)"

    companion object {
        const val ALGORITHM_ARGON2ID = "argon2id"

        /** 16 MiB。纯 Java 实现一次性分配，低端机也不至于 OOM（§7.2）。 */
        const val DEFAULT_MEMORY_KIB = 16 * 1024
        const val DEFAULT_ITERATIONS = 2
        const val DEFAULT_PARALLELISM = 2

        const val SALT_BYTES = 16
        const val DERIVED_KEY_BYTES = 32

        /** 降档下限：基准超过 500ms 时用（§7.2）。 */
        const val MIN_MEMORY_KIB = 8 * 1024
        const val MIN_ITERATIONS = 1

        /** 升档上限。与备份包的封顶一致（§12.1），不允许更高。 */
        const val MAX_MEMORY_KIB = 32 * 1024
        const val MAX_ITERATIONS = 3
        const val MAX_PARALLELISM = 4
    }
}
