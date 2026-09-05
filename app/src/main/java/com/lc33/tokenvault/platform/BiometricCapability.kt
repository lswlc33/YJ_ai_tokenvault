package com.lc33.tokenvault.platform

import android.content.Context
import androidx.biometric.BiometricManager
import com.lc33.tokenvault.domain.BiometricAvailability

/**
 * `BiometricManager.canAuthenticate` 的返回码 → [BiometricAvailability]。
 *
 * 单独抽成纯函数是为了**能被单测覆盖**：这一段是七档文案的唯一分流点，而它整体依赖
 * Android 框架，放在类里就只能靠真机点一遍。分出来之后，"新增一个返回码时忘了给文案"
 * 这件事就有地方钉住了。
 *
 * `BIOMETRIC_STATUS_UNKNOWN` 归到 [BiometricAvailability.HARDWARE_UNAVAILABLE]：
 * 它的语义是"这个版本判断不了"，而那一档的文案是"暂时不可用，稍后再试"——比归到
 * "不支持"更接近事实，也不会让用户白跑一趟系统设置。
 */
fun biometricAvailabilityOf(canAuthenticateCode: Int): BiometricAvailability = when (canAuthenticateCode) {
    BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HARDWARE_UNAVAILABLE
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
        BiometricAvailability.SECURITY_UPDATE_REQUIRED
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricAvailability.UNSUPPORTED
    BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> BiometricAvailability.HARDWARE_UNAVAILABLE
    else -> BiometricAvailability.UNSUPPORTED
}

/**
 * 系统的生物识别能力。
 *
 * **只问 `BIOMETRIC_STRONG`**，不带 `DEVICE_CREDENTIAL`（§7.3）：带上之后 Keystore 密钥的
 * 绑定强度会退化到锁屏密码，而锁屏密码往往比本应用的 PIN 更弱、且我们无法控制它的策略。
 */
class BiometricCapability(private val context: Context) {

    fun current(): BiometricAvailability =
        biometricAvailabilityOf(
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG),
        )
}
