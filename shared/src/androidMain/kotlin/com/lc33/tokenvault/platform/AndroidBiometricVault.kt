package com.lc33.tokenvault.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 生物识别当前前台 Activity 的寄存处。
 *
 * androidx.biometric 1.1.0 的 [BiometricPrompt] 只接受 [FragmentActivity]（没有纯 `Context`
 * 的构造器），而平台层拿不到 Compose 的 `LocalContext`。`MainActivity`（本身就是
 * `FragmentActivity`）在 `onResume`/`onPause` 里把自己放进 / 撤出这里，取用时拿到的
 * 就是**正在前台的这一个**。
 */
object BiometricActivityHolder {
    @Volatile
    var current: FragmentActivity? = null
}

/**
 * 生物识别解锁（Android 端，§7.3）：Keystore 硬件密钥包裹 DEK + BiometricPrompt 验证。
 *
 * 每一条都是踩过的坑：
 *
 * - `setUserAuthenticationRequired(true)` + `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`，
 *   `0` 表示**每次都要验证**，不留时间窗口。
 * - `setInvalidatedByBiometricEnrollment(true)`：新增或删除指纹后这把密钥自动失效，
 *   于是"换了个手指头就能解锁"不可能发生。代价是录入新指纹后要用 PIN 重新启用一次。
 * - **`StrongBoxUnavailableException` 在 `generateKey()` 时抛，不在 builder 上**，
 *   所以 try/catch 包住生成调用，捕获后整段重来。
 * - **加密与解密的 IV 处理不对称**：加密时 IV 由 Keystore 生成、必须和密文一起存；
 *   解密时用 `GCMParameterSpec` 把 IV 喂回去。忘存 IV 的表现是"启用当时能用，重启就永远解不开"。
 *
 * 只用 `BIOMETRIC_STRONG`，不带 `DEVICE_CREDENTIAL`：带上之后密钥绑定强度会退化到锁屏密码。
 */
class AndroidBiometricVault(
    private val context: Context,
    private val session: VaultSession,
) : BiometricVault {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val mainExecutor: Executor =
        Executor { command -> Handler(Looper.getMainLooper()).post(command) }

    override fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
        ) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun enable(prompt: BiometricPromptText): BiometricEnableOutcome {
        if (!isAvailable()) return BiometricEnableOutcome.Unavailable
        val activity = BiometricActivityHolder.current
            ?: return BiometricEnableOutcome.Error("no foreground activity")
        // 每次启用都重建密钥：复用的话，上次留下的密钥可能已被指纹变更悄悄作废，
        // 而 containsAlias 仍为真——启用看起来成功了，下次解锁才发现用不了。
        createKey()
        val cipher = encryptCipherOrNull()
            ?: return BiometricEnableOutcome.Error("keystore key unavailable")
        return when (val outcome = authenticate(activity, cipher, prompt)) {
            is BiometricEnableOutcome.Success -> {
                val wrapped = try {
                    session.withDek { dek -> Blob.encode(cipher.iv, cipher.doFinal(dek)) }
                } catch (_: Exception) {
                    // 未解锁（理论上进不来）或 Keystore 报错：删掉刚建的密钥，不留孤儿。
                    deleteKey()
                    return BiometricEnableOutcome.Error("vault is locked")
                }
                BiometricEnableOutcome.Success(wrapped)
            }
            else -> {
                deleteKey()
                outcome
            }
        }
    }

    override suspend fun unlock(blob: ByteArray?, prompt: BiometricPromptText): BiometricUnlockOutcome {
        val wrapped = blob ?: return BiometricUnlockOutcome.Invalidated
        val ciphertext = Blob.decodeCiphertext(wrapped) ?: return invalidate()
        val activity = BiometricActivityHolder.current
            ?: return BiometricUnlockOutcome.Error("no foreground activity")
        val cipher = decryptCipherOrNull(wrapped) ?: return invalidate()
        return when (val outcome = authenticate(activity, cipher, prompt)) {
            is BiometricEnableOutcome.Success -> {
                val dek = try {
                    cipher.doFinal(ciphertext)
                } catch (_: Throwable) {
                    return invalidate()
                }
                when (session.unlockWithDek(dek)) {
                    is UnlockResult.Success -> BiometricUnlockOutcome.Success
                    else -> invalidate()
                }
            }
            BiometricEnableOutcome.Cancelled -> BiometricUnlockOutcome.Cancelled
            BiometricEnableOutcome.Unavailable -> BiometricUnlockOutcome.Invalidated
            // 暂时锁住 ≠ 凭据失效：boot 里那份包裹是好的，什么都不许改，等一会儿再来。
            BiometricEnableOutcome.LockedOut -> BiometricUnlockOutcome.LockedOut
            is BiometricEnableOutcome.Error -> BiometricUnlockOutcome.Error(outcome.message)
        }
    }

    override fun disable() {
        deleteKey()
    }

    /** 凭据失效的统一善后：删别名。开关与 boot 包裹由调用方清（红线 5：三件事一起做）。 */
    private fun invalidate(): BiometricUnlockOutcome {
        deleteKey()
        return BiometricUnlockOutcome.Invalidated
    }

    private suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        prompt: BiometricPromptText,
    ): BiometricEnableOutcome = suspendCancellableCoroutine { continuation ->
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(BiometricEnableOutcome.Success(null))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!continuation.isActive) return
                continuation.resume(
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED,
                        -> BiometricEnableOutcome.Cancelled

                        // 锁住单独一档：`errString` 那一句（"尝试次数过多，请稍后再试"）
                        // 会随 ROM 变化，而调用方要做的判断（**不许清 boot**）只取决于这一档本身。
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                        -> BiometricEnableOutcome.LockedOut

                        else -> BiometricEnableOutcome.Error(errString.toString())
                    },
                )
            }
            // onAuthenticationFailed 刻意不处理：那是"这一次没认出来"，系统会让用户继续试，
            // 不该由我们提前结束流程。
        }
        val promptHandle = BiometricPrompt(activity, mainExecutor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(prompt.title)
            .setSubtitle(prompt.subtitle)
            .setNegativeButtonText(prompt.cancel)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        promptHandle.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        continuation.invokeOnCancellation { promptHandle.cancelAuthentication() }
    }

    /**
     * 生成（或重建）硬件密钥。
     *
     * `StrongBoxUnavailableException` 在 `generateKey()` 时才抛，所以上面那次 `init` 是白做的，
     * 必须整段重来。
     */
    private fun createKey() {
        deleteKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        try {
            generator.init(specBuilder(strongBox = true).build())
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(specBuilder(strongBox = false).build())
            generator.generateKey()
        }
    }

    private fun deleteKey() {
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

    private fun encryptCipherOrNull(): Cipher? {
        val key = secretKey() ?: return null
        return runCatching {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        }.getOrNull()
    }

    /**
     * 造一个待验证的解密 Cipher。[wrapped] 是 [Blob.encode] 的产物。
     *
     * `KeyPermanentlyInvalidatedException` 被 `runCatching` 收成 null：指纹变更后密钥作废，
     * 而调用方要做的事与"密钥根本不存在"完全一样，分开只会多一条走不到的分支。
     */
    private fun decryptCipherOrNull(wrapped: ByteArray): Cipher? {
        val key = secretKey() ?: return null
        val iv = Blob.decodeIv(wrapped) ?: return null
        return runCatching {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
        }.getOrNull()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "vault_bio"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
    }
}

/**
 * `{ivLen, iv, ct}` 三段编码。
 *
 * 不复用 `crypto/SecretBox` 的封套：那个封套的语义是"我们自己的 AES-GCM 密钥加的"，
 * 而这里的密钥在 Keystore 里、由系统 provider 操作，两者不该共用一个版本号空间。
 * 写 `ivLen` 而不是假定 12 字节：Keystore 的实现理论上可以给别的长度，而"假定长度"错了
 * 的表现是解密失败，与"数据坏了"分不开。
 */
private object Blob {

    fun encode(iv: ByteArray, ciphertext: ByteArray): ByteArray {
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
