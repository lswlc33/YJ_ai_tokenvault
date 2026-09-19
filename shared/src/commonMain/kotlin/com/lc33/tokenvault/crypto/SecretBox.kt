package com.lc33.tokenvault.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.materials.key.KeyDecoder

/**
 * 封套的格式版本与算法标识。**声明在文件顶部、类外**，因为 [SecretBox] 内部的常量与
 * [isKnownSecretBoxEnvelope] 都要引用它们——`const` 的初始化值必须是编译期常量，
 * 放在类后面会让"前向引用"这件事依赖编译器实现，不赌。
 */
const val SECRET_BOX_FORMAT_VERSION: Byte = 1
const val SECRET_BOX_CIPHER_AES_256_GCM: Byte = 1

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
 * 用 cryptography-kotlin（底层 JCA）而不是 BouncyCastle：阶段1 迁移的目标是
 * 换掉 BouncyCastle、为阶段2 的 KMP 化铺路。cryptography-kotlin 的 JDK provider
 * 在 Android 与 JVM 单测上走同一套 JCA，行为一致。
 *
 * **为什么显式传 IV 而不是让库自动生成**：这个封套的 IV 要写进密文头（自描述），
 * 所以必须我们自己生成、自己拼进封套。`encryptWithIv` 系列方法正是为这个场景设计的
 * ——它不把 IV 前置到输出里，只返回 `密文‖tag`，IV 由调用方保管。
 */
class SecretBox(private val random: RandomBytes = SecureRandomBytes) {

    private val aes: AES.GCM = CryptoProvider.provider.get(AES.GCM)
    private val keyDecoder: KeyDecoder<AES.Key.Format, AES.GCM.Key> = aes.keyDecoder()

    /**
     * 加密。[aad] 绑定行身份（红线 24），解密时必须给出完全相同的值。
     *
     * 不擦 [plaintext] 与 [key]：它们的生命周期归调用方（通常是 `session.withFieldKey {}`）。
     */
    fun seal(plaintext: ByteArray, key: ByteArray, aad: FieldAad): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes, got ${key.size}" }
        val iv = random.nextBytes(IV_BYTES)
        require(iv.size == IV_BYTES) { "random source returned ${iv.size} bytes, need $IV_BYTES" }

        val cipher = decodeKey(key).cipher(TAG_BITS.bits)
        // 返回 `密文‖tag`（不带 IV），我们自己拼进封套头
        val body = sealWith(cipher, iv, plaintext, aad.bytes())

        val out = ByteArray(HEADER_BYTES + body.size)
        out[0] = FORMAT_VERSION
        out[1] = CIPHER_AES_256_GCM
        iv.copyInto(out, IV_OFFSET)
        body.copyInto(out, HEADER_BYTES)
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
        val body = envelope.copyOfRange(HEADER_BYTES, envelope.size)
        return try {
            val cipher = decodeKey(key).cipher(TAG_BITS.bits)
            openWith(cipher, iv, body, aad.bytes())
        } catch (t: Throwable) {
            // 认证失败、AAD 不匹配、密文被改，三者刻意不区分：区分了就等于告诉攻击者
            // "口令对了但行号不对"。
            throw DecryptionFailedException(where, t)
        }
    }

    private fun decodeKey(key: ByteArray): AES.GCM.Key =
        keyDecoder.decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)

    /**
     * `encryptWithIvBlocking` / `decryptWithIvBlocking` 是 `@DelicateCryptographyApi`：
     * 它们把 IV 的保管权交给调用方（正是本封套要的——IV 要写进密文头），
     * 所以这里是全项目唯一需要显式 opt-in 的地方。封装成私有函数，把注解收敛在一处。
     */
    @OptIn(DelicateCryptographyApi::class)
    private fun sealWith(
        cipher: AES.IvAuthenticatedCipher,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = cipher.encryptWithIvBlocking(iv, plaintext, aad)

    @OptIn(DelicateCryptographyApi::class)
    private fun openWith(
        cipher: AES.IvAuthenticatedCipher,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = cipher.decryptWithIvBlocking(iv, ciphertext, aad)

    companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8

        private const val FORMAT_VERSION: Byte = SECRET_BOX_FORMAT_VERSION
        private const val CIPHER_AES_256_GCM: Byte = SECRET_BOX_CIPHER_AES_256_GCM
        private const val IV_OFFSET = 2
        private const val HEADER_BYTES = IV_OFFSET + IV_BYTES

        /** 一份合法封套的最短长度：头 + IV + 至少一个 tag。比它短的一定是撕裂或别的东西。 */
        const val MIN_ENVELOPE_BYTES = HEADER_BYTES + TAG_BYTES
    }
}

/**
 * 这份密文的**封套头**是不是本版本认得的（只看版本与算法标识两个字节，不做解密）。
 *
 * 存在的理由：boot 文件完全可以长得"合法 JSON、字段齐全、但封套来自更新的版本"，
 * 存储层的 JSON 校验挡不住它，而把它交给解锁路径的后果是抛异常（崩溃）。
 * 所以 boot 存储在读的时候先问一句，认不出就直接判损坏，走"从备份恢复"那条明示的出口
 * （红线 9：跨版本必须显式迁移，不猜）。
 *
 * 只适用于 [SecretBox] 自己封套的密文（`dekWrappedByPin` / `dekCheck`）。Android 生物识别
 * 那条包裹是 Keystore 自己的 `{ivLen, iv, ct}` 布局，不归本封套管，不能用这个函数判。
 */
fun isKnownSecretBoxEnvelope(envelope: ByteArray): Boolean =
    envelope.size >= SecretBox.MIN_ENVELOPE_BYTES &&
        envelope[0] == SECRET_BOX_FORMAT_VERSION &&
        envelope[1] == SECRET_BOX_CIPHER_AES_256_GCM
