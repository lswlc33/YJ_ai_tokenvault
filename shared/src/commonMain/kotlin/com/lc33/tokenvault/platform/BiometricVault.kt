package com.lc33.tokenvault.platform

/**
 * 生物识别解锁（§7.3）。
 *
 * 界面上它只是设置里一个开关、锁屏上一个入口，真正的工作全在各平台实现里：
 * 启用时把当前 DEK 交给系统的安全存储（Android Keystore / iOS Keychain）保管，
 * 解锁时验证通过再把 DEK 取回来交给 [VaultSession]。
 *
 * **为什么需要它而不是"弹个框、返回一个布尔值"**：生物识别必须是**通向同一个数据密钥的
 * 另一条路**，而不是第二道门。如果只在软件层拦一下、包裹仍用一个普通密钥存着，
 * 那么绕过这个布尔值就等于绕过生物识别。Android 用 Keystore 里
 * `setUserAuthenticationRequired(true)` 的硬件密钥包裹、iOS 用带生物识别访问控制的
 * Keychain 项，验证由系统在取密钥这一步强制执行，软件层拦不住也绕不过。
 *
 * 开关的**唯一权威存储**是 `boot.biometricEnabled`（红线 31）：锁屏页在解锁前就要知道
 * 该不该画这个入口。平台侧凭据（Keystore 别名 / Keychain 项）不算权威存储，它没了就是
 * "失效"，由调用方把开关一起关掉。
 *
 * **阶段1 迁移**曾删掉这条路径，2026-09-18 重新加回，Android / iOS 两端都实现。
 */
interface BiometricVault {

    /**
     * 这台设备此刻能不能用**强**生物识别。只问强生物识别，不带设备凭据：
     * 带上之后 Keystore / Keychain 的绑定强度会退化到锁屏密码，而锁屏密码往往比本应用的
     * PIN 更弱、且我们无法控制它的策略。没录生物识别的设备上这个入口直接不显示，这是对的。
     */
    fun isAvailable(): Boolean

    /**
     * 启用：先做一次生物识别验证，再把当前会话里的 DEK 交给平台安全存储。
     *
     * 必须**已解锁**（[VaultSession.withDek] 会抛 [VaultLockedException]）——这也顺带保证
     * 用户刚刚证明过自己知道 PIN。
     */
    suspend fun enable(prompt: BiometricPromptText): BiometricEnableOutcome

/**
 * 解锁：验证通过后取回 DEK 并交给 [VaultSession]。
 *
 * @param blob Android 侧上一次 [enable] 返回、并写进 `boot.dekWrappedByBiometric` 的密文；
 *   iOS 侧不用它（凭据在 Keychain），传 null 即可。
 */
suspend fun unlock(blob: ByteArray?, prompt: BiometricPromptText): BiometricUnlockOutcome

/** 关闭：删掉平台侧凭据。密钥失效的善后也走它。 */
fun disable()
}

/**
 * 系统生物识别弹窗的文案。
 *
 * 这个弹窗由系统画、不由我们画，所以三段文案必须**能独立成句**。文案归 strings.xml
 * （红线 19），而 ViewModel / 平台层都读不到资源，所以由 composable 层解析好再传下来。
 */
data class BiometricPromptText(
    val title: String,
    val subtitle: String,
    val cancel: String,
)

/** 启用（包裹 DEK 并存起来）的结果。 */
sealed interface BiometricEnableOutcome {
    /**
     * 成功。[blob] 是要写进 `boot.dekWrappedByBiometric` 的包裹：
     * Android 是 Keystore 密文；iOS 的凭据由 Keychain 保管，这里为 null。
     */
    data class Success(val blob: ByteArray?) : BiometricEnableOutcome

    /** 用户取消或按了"改用 PIN"。不算失败，不该提示错误。 */
    data object Cancelled : BiometricEnableOutcome

    /** 这台设备现在用不了（没录指纹 / 硬件不可用）。 */
    data object Unavailable : BiometricEnableOutcome

    /** 其它错误。[message] 是系统给的文案，直接显示比我们自己编更准。 */
    data class Error(val message: String) : BiometricEnableOutcome
}

/** 解锁（验证并取回 DEK）的结果。 */
sealed interface BiometricUnlockOutcome {
    /**
     * 成功。DEK 已经交给 [VaultSession]（由它接管所有权），调用方只需要推进阶段到已解锁。
     */
    data object Success : BiometricUnlockOutcome

    /** 用户取消。 */
    data object Cancelled : BiometricUnlockOutcome

    /**
     * 凭据已失效（新增 / 删除指纹，或 Keychain 项没了）。
     * 调用方要关开关、清 `boot.dekWrappedByBiometric`，并提示改用 PIN 解锁后重新启用。
     */
    data object Invalidated : BiometricUnlockOutcome

    data class Error(val message: String) : BiometricUnlockOutcome
}
