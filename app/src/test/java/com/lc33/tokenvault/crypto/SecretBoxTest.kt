package com.lc33.tokenvault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 6 的封套那一半（计划.md §14.3）。
 *
 * 重点不是"能加能解"，而是**该失败的时候真的失败**——尤其红线 24：把 A 行的密文
 * 塞到 B 行必须解密失败。这条如果坏了，编译通过、测试也可能通过，但库里的密文
 * 就变成可以互相顶替的了。
 */
class SecretBoxTest {

    private val box = SecretBox(FixedRandom)
    private val key = ByteArray(32) { it.toByte() }
    private val aad = FieldAad.of("api_keys", 42L, "secretEnc")

    @Test
    fun `封套往返`() {
        val plaintext = "sk-example-plaintext-value".encodeToByteArray()
        val sealed = box.seal(plaintext, key, aad)
        val opened = box.open(sealed, key, aad, "test")
        assertEquals(plaintext.toList(), opened.toList())
    }

    @Test
    fun `空明文也能往返`() {
        // 平台账号只填了用户名没填密码是合法记录（§11.2），所以空值必须能存
        val sealed = box.seal(ByteArray(0), key, aad)
        assertEquals(0, box.open(sealed, key, aad, "test").size)
    }

    @Test
    fun `密文带版本与算法标识`() {
        val sealed = box.seal("x".encodeToByteArray(), key, aad)
        assertEquals("封套第 0 字节是格式版本", 1, sealed[0].toInt())
        assertEquals("第 1 字节是算法标识", 1, sealed[1].toInt())
        assertTrue("至少要有 头 + tag 的长度", sealed.size >= 2 + 12 + 16)
    }

    @Test
    fun `未知封套版本不猜、直接拒绝`() {
        val sealed = box.seal("x".encodeToByteArray(), key, aad)
        sealed[0] = 9
        assertThrows(UnsupportedEnvelopeException::class.java) {
            box.open(sealed, key, aad, "test")
        }
    }

    @Test
    fun `换了密钥解不开`() {
        val sealed = box.seal("x".encodeToByteArray(), key, aad)
        val other = ByteArray(32) { (it + 1).toByte() }
        assertThrows(DecryptionFailedException::class.java) {
            box.open(sealed, other, aad, "test")
        }
    }

    @Test
    fun `篡改 tag 必须失败`() {
        val sealed = box.seal("x".encodeToByteArray(), key, aad)
        sealed[sealed.lastIndex] = (sealed[sealed.lastIndex] + 1).toByte()
        assertThrows(DecryptionFailedException::class.java) {
            box.open(sealed, key, aad, "test")
        }
    }

    @Test
    fun `篡改密文体必须失败`() {
        val sealed = box.seal("some longer plaintext".encodeToByteArray(), key, aad)
        sealed[20] = (sealed[20] + 1).toByte()
        assertThrows(DecryptionFailedException::class.java) {
            box.open(sealed, key, aad, "test")
        }
    }

    /** 红线 24 的回归测试。 */
    @Test
    fun `把 A 行的密文塞到 B 行必须解密失败`() {
        val aadRowA = FieldAad.of("api_keys", 1L, "secretEnc")
        val aadRowB = FieldAad.of("api_keys", 2L, "secretEnc")
        val sealedForA = box.seal("secret-of-row-A".encodeToByteArray(), key, aadRowA)

        // 同一个 DEK、同一张表、同一列，只是行号不同——不绑 AAD 的话这里会解开
        assertThrows(DecryptionFailedException::class.java) {
            box.open(sealedForA, key, aadRowB, "api_keys:2:secretEnc")
        }
    }

    /** 同一行里的两列也不能互换：账号的密文不许被搬到密码列上（红线 21、24）。 */
    @Test
    fun `同一行内跨列搬运也必须失败`() {
        val usernameAad = FieldAad.of("provider_accounts", 7L, "usernameEnc")
        val passwordAad = FieldAad.of("provider_accounts", 7L, "passwordEnc")
        val sealedUsername = box.seal("someone@example.com".encodeToByteArray(), key, usernameAad)

        assertThrows(DecryptionFailedException::class.java) {
            box.open(sealedUsername, key, passwordAad, "provider_accounts:7:passwordEnc")
        }
    }

    @Test
    fun `跨表搬运必须失败`() {
        val keysAad = FieldAad.of("api_keys", 1L, "secretEnc")
        val providersAad = FieldAad.of("providers", 1L, "balanceTokenEnc")
        val sealed = box.seal("value".encodeToByteArray(), key, keysAad)

        assertThrows(DecryptionFailedException::class.java) {
            box.open(sealed, key, providersAad, "providers:1:balanceTokenEnc")
        }
    }

    @Test
    fun `同一份明文两次加密得到不同密文`() {
        // IV 复用是 GCM 最致命的误用，所以这条要能被确定性地测——
        // FixedRandom 每次调用返回递增的 IV，真实实现用 SecureRandom。
        val plaintext = "same".encodeToByteArray()
        val first = box.seal(plaintext, key, aad)
        val second = box.seal(plaintext, key, aad)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `太短的密文当解密失败处理、不越界`() {
        assertThrows(DecryptionFailedException::class.java) {
            box.open(ByteArray(5), key, aad, "test")
        }
    }

    @Test
    fun `定时安全比较`() {
        assertTrue(byteArrayOf(1, 2, 3).constantTimeEquals(byteArrayOf(1, 2, 3)))
        assertFalse(byteArrayOf(1, 2, 3).constantTimeEquals(byteArrayOf(1, 2, 4)))
        assertFalse(byteArrayOf(1, 2).constantTimeEquals(byteArrayOf(1, 2, 3)))
    }

    /** 递增的确定性随机源：IV 可预测，但每次不同，正好用来测"两次加密不一样"。 */
    private object FixedRandom : RandomBytes {
        private var counter = 0

        override fun nextBytes(size: Int): ByteArray {
            counter++
            return ByteArray(size) { i -> (i + counter).toByte() }
        }
    }
}
