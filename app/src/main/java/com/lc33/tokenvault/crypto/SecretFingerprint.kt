package com.lc33.tokenvault.crypto

import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * 秘密值的**本机指纹**：`HMAC-SHA256(HKDF(DEK,"fp"), 明文)` 取前 32 个 hex 字符。
 *
 * 用途只有一个——**同一台设备上判断"这个值已经录过了"**：
 * `api_keys` 上有 `(providerId, fingerprint)` 唯一索引，平台账号的用户名同理。
 *
 * 三条必须记住的性质：
 *
 * 1. **它不是校验和，是带密钥的 MAC。** 不用裸 SHA-256 的理由很实际：API 密钥的熵集中在
 *    很短的一段里，裸哈希等于给拿到数据库文件的人一张可以离线穷举比对的表。
 *    带 DEK 派生的密钥之后，没有 DEK 就算不出任何指纹。
 * 2. **不能跨设备比较**（红线 27）。DEK 不同则指纹不同，所以备份恢复时指纹必须在导入端
 *    用新设备的 DEK **重算**，绝不能从备份包里搬。搬过来的表现是"去重永远不命中"，
 *    于是同一把密钥被导入两次。
 * 3. **截断到 128 位是够的**：它防的是同一家供应商下的意外重复录入，不是抗碰撞攻击；
 *    而全长 hex 会让唯一索引白占一倍空间。
 */
object SecretFingerprint {

    /** 前 32 个 hex 字符 = 128 位。 */
    const val HEX_LENGTH = 32

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * @param plaintext 明文字节。**不擦**：生命周期归调用方（通常在 `withFieldKey {}` 那一层）。
     * @param fingerprintKey `Hkdf.fingerprintKey(dek)`，由 `VaultSession.withFingerprintKey` 借出。
     */
    fun of(plaintext: ByteArray, fingerprintKey: ByteArray): String {
        val hmac = CryptoProvider.provider.get(HMAC)
        val key = hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, fingerprintKey)
        val mac = key.signatureGenerator().generateSignatureBlocking(plaintext)
        return try {
            buildString(HEX_LENGTH) {
                // 只取前 16 个字节：两个 hex 字符一个字节
                for (i in 0 until HEX_LENGTH / 2) {
                    val b = mac[i].toInt() and 0xFF
                    append(HEX_DIGITS[b ushr 4])
                    append(HEX_DIGITS[b and 0x0F])
                }
            }
        } finally {
            mac.zeroize()
        }
    }
}
