package com.lc33.tokenvault.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * 子密钥派生（§7.1 的密钥层次）。
 *
 * DEK 本身**从不直接参与加密**：字段级 AES-GCM 用 `HKDF(DEK, "field")`，
 * 指纹 HMAC 用 `HKDF(DEK, "fp")`。这样做的收益很具体——同一个 DEK 下两种用途的密钥
 * 互相推不出来，将来加第三种用途（比如备份内层）也不用动 DEK 的包裹方式。
 *
 * 用 `info` 而不是 `salt` 区分用途：HKDF 的 salt 是可选的、且在 extract 阶段生效，
 * 而 info 是 expand 阶段的域分隔符，正是"同一个主密钥派生多个用途"该用的那个参数。
 */
object Hkdf {

    /** 字段级加密子密钥的域标签。改了它 = 全库密文解不开，所以它是常量而非配置。 */
    const val INFO_FIELD = "yuanji/field/v1"

    /** 指纹 HMAC 子密钥的域标签。 */
    const val INFO_FINGERPRINT = "yuanji/fp/v1"

    /**
     * 从 [masterKey] 派生一个 [length] 字节的子密钥。
     *
     * 不擦 [masterKey]：它是会话持有的 DEK，生命周期由 `VaultSession` 管（红线 6）。
     */
    fun derive(masterKey: ByteArray, info: String, length: Int = 32): ByteArray {
        require(length in 16..64) { "subkey length must be 16..64 bytes, got $length" }
        val generator = HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(masterKey, null, info.encodeToByteArray()))
        }
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }

    fun fieldKey(dek: ByteArray): ByteArray = derive(dek, INFO_FIELD)

    fun fingerprintKey(dek: ByteArray): ByteArray = derive(dek, INFO_FINGERPRINT)
}
