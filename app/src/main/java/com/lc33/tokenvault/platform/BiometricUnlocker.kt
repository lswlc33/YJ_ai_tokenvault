package com.lc33.tokenvault.platform

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.lc33.tokenvault.crypto.zeroize
import java.util.concurrent.Executor
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** 生物识别一次交互的结果。 */
sealed interface BiometricOutcome {
    data object Success : BiometricOutcome

    /** 用户点了取消或返回。**不算失败**，不该罚也不该提示错误。 */
    data object Cancelled : BiometricOutcome

    /**
     * 密钥已失效（通常是用户新增/删除了指纹，或关掉了锁屏）。
     *
     * 调用方要做三件事：删 Keystore 别名、清 `boot.dekWrappedByBiometric` 与
     * `biometricEnabled`、提示"生物识别已失效，请用 PIN 解锁后重新启用"。
     */
    data object KeyInvalidated : BiometricOutcome

    /** 其它错误。[message] 是系统给的文案，直接显示比我们自己编更准。 */
    data class Error(val code: Int, val message: String) : BiometricOutcome
}

/**
 * 生物识别的启用与解锁（§7.3）。
 *
 * 两条流程刻意分开写，因为它们的 Cipher 方向相反、且**IV 的处理不对称**：
 * 启用时 IV 由 Keystore 生成、必须取出来存；解锁时 IV 从存储里读出来喂进去。
 * 写成一个泛化函数的话，这个不对称就会被参数藏起来，而它正是这一块最容易错的地方。
 *
 * `setAllowedAuthenticators` **只给 `BIOMETRIC_STRONG`**，不带 `DEVICE_CREDENTIAL`：
 * 带上之后 Keystore 密钥的绑定强度会退化到锁屏密码，而锁屏密码往往比本应用的 PIN 更弱、
 * 且我们无法控制它的策略。代价是没录生物识别的设备上这个入口直接不显示，这是对的。
 */
class BiometricUnlocker(
    private val keyStore: BiometricKeyStore,
    private val bootStore: BootStore,
    private val session: VaultSession,
) {

    /**
     * 启用：用当前会话里的 DEK 加密一次，把 `{iv, ct}` 存进 boot。
     *
     * 必须**已解锁**才能调用——这也顺带保证了用户刚刚证明过自己知道 PIN（§7.3 的启用流程）。
     */
    suspend fun enable(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeText: String,
    ): BiometricOutcome {
        keyStore.createKey()
        val cipher = keyStore.encryptCipherOrNull() ?: return BiometricOutcome.KeyInvalidated

        return when (val outcome = authenticate(activity, cipher, title, subtitle, negativeText)) {
            is BiometricOutcome.Success -> {
                // 借用而不是复制：复制出去的那一份没人负责擦（红线 6）。
                // 这是全应用唯一需要 DEK 原文的地方——Keystore 的 Cipher 只吃字节。
                val wrapped = try {
                    session.withDek { dek ->
                        BiometricKeyStore.encodeWrapped(cipher.iv, cipher.doFinal(dek))
                    }
                } catch (_: com.lc33.tokenvault.crypto.VaultLockedException) {
                    keyStore.deleteKey()
                    return BiometricOutcome.Error(-1, "vault is locked")
                }
                bootStore.update {
                    it.copy(biometricEnabled = true, dekWrappedByBiometric = wrapped)
                }
                BiometricOutcome.Success
            }

            else -> {
                // 启用没成功就把密钥删掉，不留一把没人用的硬件密钥
                keyStore.deleteKey()
                outcome
            }
        }
    }

    /** 关闭：删 Keystore 别名 + 清包裹 + 关开关。三件事必须一起做（红线 5）。 */
    fun disable() {
        keyStore.deleteKey()
        bootStore.update { it.copy(biometricEnabled = false, dekWrappedByBiometric = null) }
    }

    /** 解锁：读 `{iv, ct}`，验证之后 `doFinal` 得到 DEK，交给 [VaultSession]。 */
    suspend fun unlock(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeText: String,
    ): BiometricOutcome {
        val record = (bootStore.read() as? BootState.Ok)?.record
            ?: return BiometricOutcome.Error(-1, "no boot record")
        if (!record.biometricUsable) return BiometricOutcome.KeyInvalidated

        val wrapped = record.dekWrappedByBiometric ?: return BiometricOutcome.KeyInvalidated
        val cipher = keyStore.decryptCipherOrNull(wrapped) ?: return invalidate()
        val ciphertext = BiometricKeyStore.decodeCiphertext(wrapped) ?: return invalidate()

        return when (val outcome = authenticate(activity, cipher, title, subtitle, negativeText)) {
            is BiometricOutcome.Success -> {
                val dek = try {
                    cipher.doFinal(ciphertext)
                } catch (_: Throwable) {
                    return invalidate()
                }
                // unlockWithDek 会接管这段字节的所有权（校验长度、失败时自己擦）
                when (session.unlockWithDek(dek)) {
                    is UnlockResult.Success -> BiometricOutcome.Success
                    else -> invalidate()
                }
            }

            is BiometricOutcome.KeyInvalidated -> invalidate()
            else -> outcome
        }
    }

    /** 密钥失效的统一善后。三件事一起做，否则会留下"开关开着但永远解不开"的状态。 */
    private fun invalidate(): BiometricOutcome {
        disable()
        return BiometricOutcome.KeyInvalidated
    }

    private suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeText: String,
    ): BiometricOutcome = suspendCancellableCoroutine { continuation ->
        val executor: Executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(BiometricOutcome.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.isActive) return
                    continuation.resume(
                        when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED,
                            -> BiometricOutcome.Cancelled

                            else -> BiometricOutcome.Error(errorCode, errString.toString())
                        },
                    )
                }
                // onAuthenticationFailed 刻意不处理：那是"这一次没认出来"，
                // 系统会让用户继续试，不该由我们提前结束流程。
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}
