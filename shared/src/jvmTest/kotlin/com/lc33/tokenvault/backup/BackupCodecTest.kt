package com.lc33.tokenvault.backup

import com.lc33.tokenvault.crypto.KdfParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 备份包编解码（§12.1）纯函数测试（测试 11 的核心）。
 *
 * 覆盖：往返、magic 校验、口令错 / 篡改 header / 篡改密文都失败（不返回 null，红线 8）、
 * 版本过高拒绝（红线 9）。
 */
class BackupCodecTest {

    private fun params(): KdfParams = KdfParams(
        iterations = KdfParams.MIN_ITERATIONS, // 测试用最小档，避免单测太慢
        salt = ByteArray(KdfParams.SALT_BYTES) { it.toByte() },
    )

    private fun header() = BackupHeader(
        createdAt = 1757000000000L,
        deviceId = "test-device",
        revision = 42L,
        kdf = params(),
        nonce = ByteArray(BackupHeader.NONCE_BYTES) { (it + 1).toByte() },
        itemCounts = BackupItemCounts(providers = 3, keys = 3),
    )

    @Test
    fun `往返编解码一致`() {
        val codec = BackupCodec()
        val password = "test-password".toCharArray()
        val payload = """{"providers":[]}""".encodeToByteArray()
        val encoded = codec.encode(password, header(), payload)
        val decoded = codec.decode(encoded, password)
        assertEquals(header().deviceId, decoded.header.deviceId)
        assertEquals(header().itemCounts, decoded.header.itemCounts)
        assertTrue(decoded.payload.contentEquals(payload))
    }

    @Test
    fun `magic 不对抛损坏异常`() {
        val codec = BackupCodec()
        val bad = ByteArray(8) { 0 }
        val e = runCatching { codec.decode(bad, "x".toCharArray()) }.exceptionOrNull()
        assertTrue(e is BackupCorruptException)
    }

    @Test
    fun `口令错抛损坏异常且不返回 null`() {
        val codec = BackupCodec()
        val encoded = codec.encode("right".toCharArray(), header(), "{}".encodeToByteArray())
        val e = runCatching { codec.decode(encoded, "wrong".toCharArray()) }.exceptionOrNull()
        assertTrue(e is BackupCorruptException)
    }

    @Test
    fun `篡改 header 抛损坏异常`() {
        val codec = BackupCodec()
        val password = "pw".toCharArray()
        val encoded = codec.encode(password, header(), "{}".encodeToByteArray())
        // 篡改 header 里的 revision（在明文区，但 AAD 绑 header 所以会失败）
        val tampered = encoded.copyOf()
        // revision 是 createdAt 之后的字段，这里简单地把中间某个字节翻转
        tampered[8 + 4 + 20] = (tampered[8 + 4 + 20].toInt() xor 0xFF).toByte()
        val e = runCatching { codec.decode(tampered, password) }.exceptionOrNull()
        assertTrue(e is BackupCorruptException)
    }

    @Test
    fun `篡改密文抛损坏异常`() {
        val codec = BackupCodec()
        val password = "pw".toCharArray()
        val encoded = codec.encode(password, header(), "{}".encodeToByteArray())
        val tampered = encoded.copyOf()
        tampered[tampered.size - 1] = (tampered.last().toInt() xor 0x01).toByte()
        val e = runCatching { codec.decode(tampered, password) }.exceptionOrNull()
        assertTrue(e is BackupCorruptException)
    }

    @Test
    fun `版本过高抛升级异常`() {
        val codec = BackupCodec()
        val h = header().copy(format = BackupHeader.FORMAT_VERSION + 1)
        val password = "pw".toCharArray()
        val encoded = codec.encode(password, h, "{}".encodeToByteArray())
        val e = runCatching { codec.decode(encoded, password) }.exceptionOrNull()
        assertTrue(e is BackupTooNewException)
    }

    @Test
    fun `同一明文两次加密得到不同密文`() {
        val codec = BackupCodec() // 用 SecureRandomBytes，nonce 随机
        val password = "pw".toCharArray()
        val a = codec.encode(password, header(), "{}".encodeToByteArray())
        val b = codec.encode(password, header(), "{}".encodeToByteArray())
        assertFalse(a.contentEquals(b))
    }
}
