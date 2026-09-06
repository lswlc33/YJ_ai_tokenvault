package com.lc33.tokenvault.backup

import com.lc33.tokenvault.crypto.Argon2idKdf
import com.lc33.tokenvault.crypto.RandomBytes
import com.lc33.tokenvault.crypto.SecureRandomBytes
import com.lc33.tokenvault.crypto.zeroize
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer

/**
 * 备份包的编解码（§12.1）。纯 Kotlin，零 Android 依赖，JVM 单测全覆盖。
 *
 * 布局：
 * ```
 * magic "YJVAULT1"(8) ‖ headerLen(4, BE) ‖ header(JSON, 明文) ‖ payload(密文)
 * ```
 *
 * payload 用 AES-256-GCM，key 由 [Argon2idKdf] 从备份口令派生（参数在 header 里），
 * **AAD = header 原始字节**——篡改 header（比如把内存参数改小）会在认证阶段失败（红线 24
 * 在包层面的同一套道理）。
 *
 * 这里**不复用** [com.lc33.tokenvault.crypto.SecretBox]：字段级封套带 format/algorithm 前缀、
 * AAD 是 [com.lc33.tokenvault.crypto.FieldAad]，而备份包的 header 是分离的明文、AAD 是
 * header 原始字节，布局不同。但用同一套 BouncyCastle GCM 原语，行为在 JVM 与 Android 一致。
 *
 * 派生密钥、明文 payload、备份口令都只在函数作用域内短命，用完即擦（红线 1）。
 */
class BackupCodec(private val random: RandomBytes = SecureRandomBytes) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
        val key = try {
            Argon2idKdf.derive(password, header.kdf)
        } finally {
            // password 的生命周期归调用方，这里不擦
        }

        return try {
            val cipher = newCipher(forEncryption = true, key = key, nonce = nonce, aad = headerBytes)
            val ciphertext = ByteArray(cipher.getOutputSize(payload.size))
            var written = cipher.processBytes(payload, 0, payload.size, ciphertext, 0)
            written += cipher.doFinal(ciphertext, written)
            val body = if (written == ciphertext.size) ciphertext else ciphertext.copyOf(written)

            val out = ByteBuffer.allocate(MAGIC.size + 4 + headerBytes.size + body.size)
            out.put(MAGIC)
            out.putInt(headerBytes.size)
            out.put(headerBytes)
            out.put(body)
            out.array()
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
        val headerLen = ByteBuffer.wrap(bytes, MAGIC.size, 4).int
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
        val key = Argon2idKdf.derive(password, header.kdf)
        return try {
            val cipher = newCipher(forEncryption = false, key = key, nonce = header.nonce, aad = headerBytes)
            val plain = ByteArray(cipher.getOutputSize(body.size))
            val written = try {
                var n = cipher.processBytes(body, 0, body.size, plain, 0)
                n += cipher.doFinal(plain, n)
                n
            } catch (t: Throwable) {
                plain.zeroize()
                // 口令错 / AAD 不匹配 / 密文被改，三者刻意不区分（同 SecretBox 的理由）
                throw BackupCorruptException("decrypt failed")
            }
            val payload = if (written == plain.size) plain else plain.copyOf(written).also { plain.zeroize() }
            DecodedBackup(header = header, payload = payload)
        } finally {
            key.zeroize()
        }
    }

    private fun newCipher(
        forEncryption: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): org.bouncycastle.crypto.modes.GCMModeCipher =
        GCMBlockCipher.newInstance(AESEngine.newInstance()).apply {
            init(forEncryption, AEADParameters(KeyParameter(key), TAG_BITS, nonce, aad))
        }

    private companion object {
        val MAGIC = "YJVAULT1".encodeToByteArray()
        const val TAG_BITS = 128
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
