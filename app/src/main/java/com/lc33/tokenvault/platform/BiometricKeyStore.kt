package com.lc33.tokenvault.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore 里那把包裹 DEK 的硬件密钥（§7.3）。
 *
 * 每一条都是踩过的坑，所以逐条写清：
 *
 * - `setUserAuthenticationRequired(true)` + `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`，
 *   `0` 表示**每次都要验证**，不给时间窗口。红线 4 要求生物识别必须由硬件密钥保护。
 * - `setInvalidatedByBiometricEnrollment(true)`：用户新增或删除指纹后这把密钥自动失效，
 *   于是"换了个手指头就能解锁"这件事不可能发生。代价是用户录入新指纹后要用 PIN 重新启用一次，
 *   这个代价必须由 UI 说清楚，否则会被当成 bug。
 * - **`StrongBoxUnavailableException` 是在 `generateKey()` 时抛的，不是在 builder 上。**
 *   所以 try/catch 必须包住生成调用，捕获后用 `setIsStrongBoxBacked(false)` 重来一次。
 * - **加密与解密的 IV 处理不对称**，这是最常见的错误来源：GCM 加密时 IV 由 Keystore 生成，
 *   必须从 `cipher.iv` 取出来和密文一起存；解密时要用 `GCMParameterSpec(128, iv)` 初始化。
 *   忘了存 IV 的表现是"启用当时能用，重启之后永远解不开"。
 */
class BiometricKeyStore {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasKey(): Boolean = runCatching { keyStore.containsAlias(ALIAS) }.getOrDefault(false)

    /**
     * 生成（或重建）密钥。启用生物识别时调用。
     *
     * 每次启用都**重新生成**而不是复用已有的：复用的话，上一次启用留下的密钥可能已经被
     * 指纹变更悄悄作废，而 `containsAlias` 仍返回真——于是启用流程看起来成功了，
     * 下次解锁才发现用不了。
     */
    fun createKey() {
        deleteKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        try {
            generator.init(specBuilder(strongBox = true).build())
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            // 这个异常在 generateKey() 时才抛，所以上面那次 init 是白做的，必须整段重来
            generator.init(specBuilder(strongBox = false).build())
            generator.generateKey()
        }
    }

    fun deleteKey() {
        runCatching { keyStore.deleteEntry(ALIAS) }
    }

    private fun specBuilder(strongBox: Boolean) =
        KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            // 0 = 每次都要验证，不留时间窗口
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)

    private fun secretKey(): SecretKey? =
        runCatching { keyStore.getKey(ALIAS, null) as? SecretKey }.getOrNull()

    /**
     * 造一个待验证的加密 Cipher。交给 `BiometricPrompt` 之后才能 `doFinal`。
     *
     * 返回 null 表示密钥不在或已失效——调用方据此提示"生物识别已失效，请用 PIN 解锁后重新启用"
     * 并清掉 `boot.dekWrappedByBiometric`。
     */
    fun encryptCipherOrNull(): Cipher? {
        val key = secretKey() ?: return null
        return runCatching {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        }.getOrNull()
    }

    /**
     * 造一个待验证的解密 Cipher。[wrapped] 是 [encodeWrapped] 的产物。
     *
     * `KeyPermanentlyInvalidatedException` 在这里被 `runCatching` 收成 null：
     * 指纹变更后密钥作废，而调用方要做的事（删别名 + 清包裹 + 提示重新启用）与
     * "密钥根本不存在"完全一样，分开处理只会多一条走不到的分支。
     */
    fun decryptCipherOrNull(wrapped: ByteArray): Cipher? {
        val key = secretKey() ?: return null
        val iv = decodeIv(wrapped) ?: return null
        return runCatching {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
        }.getOrNull()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "vault_bio"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val TAG_BITS = 128

        /**
         * `{ivLen, iv, ct}` 三段编码。
         *
         * 不复用 `crypto/SecretBox` 的封套：那个封套的语义是"我们自己的 AES-GCM 密钥加的"，
         * 而这里的密钥在 Keystore 里、由系统的 provider 操作，两者不该共用一个版本号空间——
         * 将来换掉其中一个时，另一个不必跟着改。
         *
         * 写 `ivLen` 而不是假定 12 字节：Keystore 的实现理论上可以给别的长度，
         * 而"假定长度"错了的表现是解密失败，与"数据坏了"分不开。
         */
        fun encodeWrapped(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            require(iv.size in 1..255) { "unexpected GCM IV length ${iv.size}" }
            val out = ByteArray(1 + iv.size + ciphertext.size)
            out[0] = iv.size.toByte()
            iv.copyInto(out, 1)
            ciphertext.copyInto(out, 1 + iv.size)
            return out
        }

        fun decodeIv(wrapped: ByteArray): ByteArray? {
            if (wrapped.isEmpty()) return null
            val ivLen = wrapped[0].toInt() and 0xFF
            if (ivLen == 0 || wrapped.size < 1 + ivLen) return null
            return wrapped.copyOfRange(1, 1 + ivLen)
        }

        fun decodeCiphertext(wrapped: ByteArray): ByteArray? {
            if (wrapped.isEmpty()) return null
            val ivLen = wrapped[0].toInt() and 0xFF
            if (ivLen == 0 || wrapped.size <= 1 + ivLen) return null
            return wrapped.copyOfRange(1 + ivLen, wrapped.size)
        }
    }
}
