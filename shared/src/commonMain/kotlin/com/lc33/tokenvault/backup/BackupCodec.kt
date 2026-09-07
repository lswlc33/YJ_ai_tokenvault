package com.lc33.tokenvault.backup

import com.lc33.tokenvault.crypto.CryptoProvider
import com.lc33.tokenvault.crypto.Pbkdf2Kdf
import com.lc33.tokenvault.crypto.RandomBytes
import com.lc33.tokenvault.crypto.SecureRandomBytes
import com.lc33.tokenvault.crypto.zeroize
import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.serialization.json.Json

/**
 * 备份包的编解码（§12.1）。纯 Kotlin，零 Android 依赖，JVM 单测全覆盖。
 *
 * 布局：
 * ```
 * magic "YJVAULT1"(8) ‖ headerLen(4, BE) ‖ header(JSON, 明文) ‖ payload(密文)
 * ```
 *
 * payload 用 AES-256-GCM，key 由 [Pbkdf2Kdf] 从备份口令派生（参数在 header 里），
 * **AAD = header 原始字节**——篡改 header（比如把内存参数改小）会在认证阶段失败（红线 24
 * 在包层面的同一套道理）。
 *
 * 这里**不复用** [com.lc33.tokenvault.crypto.SecretBox]：字段级封套带 format/algorithm 前缀、
 * AAD 是 [com.lc33.tokenvault.crypto.FieldAad]，而备份包的 header 是分离的明文、AAD 是
 * header 原始字节，布局不同。但用同一套 cryptography-kotlin 的 AES-GCM 原语（JDK provider），
 * 行为在 JVM 与 Android 一致。
 *
 * 派生密钥、明文 payload、备份口令都只在函数作用域内短命，用完即擦（红线 1）。
 *
 * **阶段1 迁移**：口令派生从 Argon2id 换成 PBKDF2，AES-GCM 从 BouncyCastle 换成
 * cryptography-kotlin。备份口令默认沿用 PIN（不再有独立备份口令）。
 *
 * **阶段2 迁移**：`java.nio.ByteBuffer` 换成手写大端字节操作（跨平台，iOS 无 java.nio）。
 * 布局字节与旧实现逐字节一致（magic + 4 字节 BE 长度 + header + body）。
 */
class BackupCodec(private val random: RandomBytes = SecureRandomBytes) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val aes: AES.GCM = CryptoProvider.provider.get(AES.GCM)

    /**
     * 加密：header + payload → 完整包字节。
     *
     * **nonce 在这里生成**（[random]），并写回 [header]——调用方不用关心 nonce，
     * 它跟着 header 一起落进包，解密端照 header 里的值还原。两次加密同一个 header
     * 因此得到不同密文（nonce 不复用，GCM 的命门）。
     *
     * @param password 备份口令（默认是当前 PIN，也可单独设）。
     * @param header 明文 header（不含 payload）。
     * @param payload 明文 payload（gzip 后的 JSON 字节）。
     */
    fun encode(password: CharArray, header: BackupHeader, payload: ByteArray): ByteArray {
        header.kdf.requireWithinCap()

        val nonce = random.nextBytes(BackupHeader.NONCE_BYTES)
        val headerBytes = json.encodeToString(
            BackupHeader.serializer(),
            header.copy(nonce = nonce),
        ).encodeToByteArray()
        val key = Pbkdf2Kdf.derive(password, header.kdf)

        return try {
            val cipher = aes.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
                .cipher(TAG_BITS.bits)
            // `密文‖tag`（不带 nonce），我们自己拼进包
            val body = sealWith(cipher, nonce, payload, headerBytes)

            // 手写拼接（替代 ByteBuffer）：magic + 4 字节 BE headerLen + header + body
            val out = ByteArray(MAGIC.size + 4 + headerBytes.size + body.size)
            MAGIC.copyInto(out, 0)
            writeIntBE(out, MAGIC.size, headerBytes.size)
            headerBytes.copyInto(out, MAGIC.size + 4)
            body.copyInto(out, MAGIC.size + 4 + headerBytes.size)
            out
        } finally {
            key.zeroize()
            nonce.zeroize()
        }
    }

    /**
     * 解密：完整包字节 → (header, payload)。
     *
     * 校验 magic、版本、解密认证。任何一步失败都抛 [BackupCorruptException]，**绝不返回 null**
     * （红线 8）。版本高于当前抛 [BackupTooNewException]（提示升级，红线 9）。
     */
    fun decode(bytes: ByteArray, password: CharArray): DecodedBackup {
        if (bytes.size < MAGIC.size + 4) throw BackupCorruptException("too short")
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupCorruptException("bad magic")
        }
        val headerLen = readIntBE(bytes, MAGIC.size)
        if (headerLen <= 0 || headerLen > bytes.size - MAGIC.size - 4) {
            throw BackupCorruptException("bad header length $headerLen")
        }

        val headerBytes = bytes.copyOfRange(MAGIC.size + 4, MAGIC.size + 4 + headerLen)
        val header = runCatching {
            json.decodeFromString(BackupHeader.serializer(), headerBytes.decodeToString())
        }.getOrElse { throw BackupCorruptException("bad header json") }

        if (header.format > BackupHeader.FORMAT_VERSION) {
            throw BackupTooNewException(header.format)
        }
        if (header.schema > BackupHeader.SCHEMA_VERSION) {
            throw BackupTooNewException(header.schema)
        }

        val body = bytes.copyOfRange(MAGIC.size + 4 + headerLen, bytes.size)
        val key = Pbkdf2Kdf.derive(password, header.kdf)
        return try {
            val cipher = aes.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
                .cipher(TAG_BITS.bits)
            val payload = try {
                openWith(cipher, header.nonce, body, headerBytes)
            } catch (t: Throwable) {
                // 口令错 / AAD 不匹配 / 密文被改，三者刻意不区分（同 SecretBox 的理由）
                throw BackupCorruptException("decrypt failed")
            }
            DecodedBackup(header = header, payload = payload)
        } finally {
            key.zeroize()
        }
    }

    @OptIn(DelicateCryptographyApi::class)
    private fun sealWith(
        cipher: AES.IvAuthenticatedCipher,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = cipher.encryptWithIvBlocking(nonce, plaintext, aad)

    @OptIn(DelicateCryptographyApi::class)
    private fun openWith(
        cipher: AES.IvAuthenticatedCipher,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = cipher.decryptWithIvBlocking(nonce, ciphertext, aad)

    private companion object {
        val MAGIC = "YJVAULT1".encodeToByteArray()
        const val TAG_BITS = 128

        /** 往 [out] 的 [offset] 处写 4 字节大端 int（替代 ByteBuffer.putInt）。 */
        fun writeIntBE(out: ByteArray, offset: Int, value: Int) {
            out[offset] = (value ushr 24).toByte()
            out[offset + 1] = (value ushr 16).toByte()
            out[offset + 2] = (value ushr 8).toByte()
            out[offset + 3] = value.toByte()
        }

        /** 从 [bytes] 的 [offset] 处读 4 字节大端 int（替代 ByteBuffer.wrap(...).int）。 */
        fun readIntBE(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
    }
}

/** 解密成功的产物。payload 是 gzip 后的 JSON 字节，交给上层解 gzip + 反序列化。 */
data class DecodedBackup(
    val header: BackupHeader,
    val payload: ByteArray,
)

/** 备份包损坏（magic 不对 / header 解析失败 / 口令错 / 密文被改）。 */
class BackupCorruptException(message: String) : Exception(message)

/** 备份包版本高于当前应用，需要升级后才能恢复（红线 9）。 */
class BackupTooNewException(val version: Int) :
    Exception("backup format/schema $version is newer than this app supports")
