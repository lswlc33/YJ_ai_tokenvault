package com.lc33.tokenvault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 6 的 DEK 那一半（计划.md §14.3）。
 *
 * 三条断言直接对应三条红线：
 * - 改 PIN 只换包裹、DEK 不变（红线 2 → 业务表零 UPDATE）
 * - 三条路径解出同一个 DEK（红线 25）
 * - KDF 参数从存储读、不从常量读（红线 3 → 不改写存储值，否则永久锁库）
 *
 * 测试里的 Argon2 一律用**下限参数**（8 MiB / t=1）：这里验的是逻辑，不是强度，
 * 用默认的 16 MiB × 十几次派生会让整个单测套件慢好几秒。
 */
class DekEnvelopeTest {

    private val envelope = DekEnvelope(SecretBox(SecureRandomBytes), SecureRandomBytes)

    private fun fastParams(saltByte: Byte = 1) = KdfParams(
        memoryKib = KdfParams.MIN_MEMORY_KIB,
        iterations = KdfParams.MIN_ITERATIONS,
        parallelism = 1,
        salt = ByteArray(KdfParams.SALT_BYTES) { saltByte },
    )

    @Test
    fun `包裹与解包往返`() {
        val dek = envelope.generateDek()
        val kek = Argon2idKdf.derive("123456".toCharArray(), fastParams())
        val wrapped = envelope.wrap(dek, kek, DekSlot.Pin)

        assertEquals(dek.toList(), envelope.unwrap(wrapped, kek, DekSlot.Pin).toList())
    }

    @Test
    fun `DEK 是 32 字节且每次都不同`() {
        val a = envelope.generateDek()
        val b = envelope.generateDek()
        assertEquals(32, a.size)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `错误的 PIN 解不开`() {
        val dek = envelope.generateDek()
        val params = fastParams()
        val wrapped = envelope.wrap(dek, Argon2idKdf.derive("123456".toCharArray(), params), DekSlot.Pin)

        val wrongKek = Argon2idKdf.derive("654321".toCharArray(), params)
        assertThrows(DecryptionFailedException::class.java) {
            envelope.unwrap(wrapped, wrongKek, DekSlot.Pin)
        }
    }

    /** 红线 2：改 PIN 只重新包裹一次，DEK 本身不变，所以旧密文照样能解。 */
    @Test
    fun `改 PIN 之后 DEK 不变、旧字段密文仍可解`() {
        val box = SecretBox()
        val dek = envelope.generateDek()
        val fieldKey = Hkdf.fieldKey(dek)
        val aad = FieldAad.of("api_keys", 1L, "secretEnc")
        val cipherFromBeforePinChange = box.seal("sk-old-value".encodeToByteArray(), fieldKey, aad)

        // 改 PIN：新盐 + 新 KEK，重新包裹同一个 DEK
        val oldParams = fastParams(saltByte = 1)
        val newParams = fastParams(saltByte = 2)
        envelope.wrap(dek, Argon2idKdf.derive("111111".toCharArray(), oldParams), DekSlot.Pin)
        val rewrapped = envelope.wrap(dek, Argon2idKdf.derive("222222".toCharArray(), newParams), DekSlot.Pin)

        val recoveredDek = envelope.unwrap(
            rewrapped,
            Argon2idKdf.derive("222222".toCharArray(), newParams),
            DekSlot.Pin,
        )
        assertEquals("改 PIN 不该换 DEK", dek.toList(), recoveredDek.toList())

        val stillReadable = box.open(cipherFromBeforePinChange, Hkdf.fieldKey(recoveredDek), aad, "test")
        assertEquals("sk-old-value", stillReadable.decodeToString())
    }

    /** 红线 25：三条包裹路径包的是同一个 DEK。 */
    @Test
    fun `三条路径解出同一个 DEK`() {
        val dek = envelope.generateDek()
        val pinKek = Argon2idKdf.derive("123456".toCharArray(), fastParams(saltByte = 1))
        val recoveryKek = Argon2idKdf.derive(
            RecoveryKey.generate(SecureRandomBytes),
            fastParams(saltByte = 2),
        )
        // 生物识别那条路的 KEK 来自 Keystore，crypto/ 不碰它，这里用一段固定字节代表
        val bioKek = ByteArray(32) { 7 }

        val fromPin = envelope.unwrap(envelope.wrap(dek, pinKek, DekSlot.Pin), pinKek, DekSlot.Pin)
        val fromRecovery = envelope.unwrap(
            envelope.wrap(dek, recoveryKek, DekSlot.Recovery),
            recoveryKek,
            DekSlot.Recovery,
        )
        val fromBio = envelope.unwrap(
            envelope.wrap(dek, bioKek, DekSlot.Biometric),
            bioKek,
            DekSlot.Biometric,
        )

        assertEquals(dek.toList(), fromPin.toList())
        assertEquals(dek.toList(), fromRecovery.toList())
        assertEquals(dek.toList(), fromBio.toList())
    }

    /**
     * 槽位绑进了 AAD，所以密文不能跨槽位搬。
     *
     * 这条防的不是泄密（还是同一个 DEK），而是"关掉生物识别"被悄悄绕过：
     * 把 PIN 那份拷进生物识别槽位，Keystore 那条路就又能解出 DEK 了（红线 5）。
     */
    @Test
    fun `包裹不能跨槽位搬运`() {
        val dek = envelope.generateDek()
        val kek = ByteArray(32) { 3 }
        val wrappedByPin = envelope.wrap(dek, kek, DekSlot.Pin)

        assertThrows(DecryptionFailedException::class.java) {
            envelope.unwrap(wrappedByPin, kek, DekSlot.Biometric)
        }
    }

    /** 红线 3 的回归测试：参数必须来自存储值，拿常量去算就解不开。 */
    @Test
    fun `KDF 参数必须用存储的那一份、不是编译期常量`() {
        val storedParams = KdfParams(
            memoryKib = KdfParams.MIN_MEMORY_KIB,
            iterations = 1,
            parallelism = 1,
            salt = ByteArray(KdfParams.SALT_BYTES) { 9 },
        )
        val dek = envelope.generateDek()
        val wrapped = envelope.wrap(dek, Argon2idKdf.derive("123456".toCharArray(), storedParams), DekSlot.Pin)

        // 用"编译期默认值"去派生，与存储值不一致
        val defaultsParams = KdfParams(salt = storedParams.salt)
        assertNotEquals(storedParams.memoryKib, defaultsParams.memoryKib)
        assertThrows(DecryptionFailedException::class.java) {
            envelope.unwrap(wrapped, Argon2idKdf.derive("123456".toCharArray(), defaultsParams), DekSlot.Pin)
        }

        // 而读存储值就能解开
        assertEquals(
            dek.toList(),
            envelope.unwrap(
                wrapped,
                Argon2idKdf.derive("123456".toCharArray(), storedParams),
                DekSlot.Pin,
            ).toList(),
        )
    }

    @Test
    fun `子密钥互相推不出来`() {
        val dek = envelope.generateDek()
        val field = Hkdf.fieldKey(dek)
        val fingerprint = Hkdf.fingerprintKey(dek)

        assertEquals(32, field.size)
        assertNotEquals(field.toList(), fingerprint.toList())
        assertNotEquals("子密钥不该等于 DEK 本身", dek.toList(), field.toList())
        assertEquals("同一个 DEK 派生必须稳定", field.toList(), Hkdf.fieldKey(dek).toList())
    }

    @Test
    fun `擦除真的清零`() {
        val secret = ByteArray(8) { 0x41 }
        secret.borrow { assertTrue(it.any { b -> b != 0.toByte() }) }
        assertTrue("borrow 退出后必须已清零", secret.all { it == 0.toByte() })
    }
}
