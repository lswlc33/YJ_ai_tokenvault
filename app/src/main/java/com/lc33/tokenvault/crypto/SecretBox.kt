package com.lc33.tokenvault.crypto

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * AES-256-GCM 的自描述封套。
 *
 * 布局（一律小端无关，因为没有多字节整数）：
 *
 * ```
 * [0]      封套格式版本 = 1
 * [1]      算法标识     = 1（AES-256-GCM / 96-bit IV / 128-bit tag）
 * [2..13]  IV，12 字节
 * [14..]   密文 ‖ tag
 * ```
 *
 * **为什么要自描述**：这些字节会在库里躺很多年，还会进备份包。把版本与算法写在密文里，
 * 换算法时旧密文仍能被正确识别并走显式迁移（红线 9）；不写的话唯一的办法是按长度和
 * 上下文猜，而猜错的表现是"解密失败"，与"数据坏了"无法区分。
 *
 * **IV 长度固定 12 字节**：GCM 在 96-bit IV 下不需要额外的 GHASH 派生，这是唯一
 * 被广泛审计过的取值。
 *
 * 用 BouncyCastle 而不是 `javax.crypto`：同一份实现在 JVM 单测与 Android 上行为一致，
 * 不受各设备 provider 差异影响。代价是慢一点，而字段级密文都很短，无所谓。
 */
class SecretBox(private val random: RandomBytes = SecureRandomBytes) {

    /**
     * 加密。[aad] 绑定行身份（红线 24），解密时必须给出完全相同的值。
     *
     * 不擦 [plaintext] 与 [key]：它们的生命周期归调用方（通常是 `session.withFieldKey {}`）。
     */
    fun seal(plaintext: ByteArray, key: ByteArray, aad: FieldAad): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes, got ${key.size}" }
        val iv = random.nextBytes(IV_BYTES)
        require(iv.size == IV_BYTES) { "random source returned ${iv.size} bytes, need $IV_BYTES" }

        val cipher = newCipher(forEncryption = true, key = key, iv = iv, aad = aad)
        val out = ByteArray(HEADER_BYTES + cipher.getOutputSize(plaintext.size))
        out[0] = FORMAT_VERSION
        out[1] = CIPHER_AES_256_GCM
        iv.copyInto(out, IV_OFFSET)

        var written = HEADER_BYTES
        written += cipher.processBytes(plaintext, 0, plaintext.size, out, written)
        cipher.doFinal(out, written)
        return out
    }

    /**
     * 解密。失败一律抛 [DecryptionFailedException]，**绝不返回 null**（红线 8）。
     *
     * @param where 一句给人看的定位信息（"api_keys:42:secretEnc"），会进错误消息与日志。
     *   **不许把明文或密钥拼进去**。
     */
    fun open(envelope: ByteArray, key: ByteArray, aad: FieldAad, where: String): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes, got ${key.size}" }
        if (envelope.size < HEADER_BYTES + TAG_BYTES) {
            throw DecryptionFailedException("$where (envelope is ${envelope.size} bytes, below minimum)")
        }
        if (envelope[0] != FORMAT_VERSION) {
            throw UnsupportedEnvelopeException("unknown envelope version ${envelope[0]} at $where")
        }
        if (envelope[1] != CIPHER_AES_256_GCM) {
            throw UnsupportedEnvelopeException("unknown cipher id ${envelope[1]} at $where")
        }

        val iv = envelope.copyOfRange(IV_OFFSET, IV_OFFSET + IV_BYTES)
        val cipher = newCipher(forEncryption = false, key = key, iv = iv, aad = aad)
        val body = envelope.copyOfRange(HEADER_BYTES, envelope.size)
        val out = ByteArray(cipher.getOutputSize(body.size))
        return try {
            var written = cipher.processBytes(body, 0, body.size, out, 0)
            written += cipher.doFinal(out, written)
            // getOutputSize 是上界，实际明文可能更短
            if (written == out.size) out else out.copyOf(written).also { out.zeroize() }
        } catch (t: Throwable) {
            // 认证失败、AAD 不匹配、密文被改，三者刻意不区分：区分了就等于告诉攻击者
            // "口令对了但行号不对"。
            out.zeroize()
            throw DecryptionFailedException(where, t)
        }
    }

    private fun newCipher(
        forEncryption: Boolean,
        key: ByteArray,
        iv: ByteArray,
        aad: FieldAad,
    ): org.bouncycastle.crypto.modes.GCMModeCipher =
        GCMBlockCipher.newInstance(AESEngine.newInstance()).apply {
            init(
                forEncryption,
                AEADParameters(KeyParameter(key), TAG_BITS, iv, aad.bytes()),
            )
        }

    companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8

        private const val FORMAT_VERSION: Byte = 1
        private const val CIPHER_AES_256_GCM: Byte = 1
        private const val IV_OFFSET = 2
        private const val HEADER_BYTES = IV_OFFSET + IV_BYTES
    }
}
