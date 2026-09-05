package com.lc33.tokenvault.platform

import com.lc33.tokenvault.crypto.ByteArrayAsBase64
import com.lc33.tokenvault.crypto.KdfParams
import kotlinx.serialization.Serializable

/**
 * boot 存储的内容（§6.1 那张表）。
 *
 * 这里放的是**解锁前必须可读**的那几项，而且只有这几项。红线 31：同一个配置项只能有一个
 * 权威存储——`themeMode` / `localeTag` / `onboarded` 的权威存储就是这里（锁屏页要用它们），
 * `app_settings` 不得再放一份。`SettingsRepository` 对这三个键透传到这里，对外仍是同一套
 * `StateFlow`，调用方看不出区别。
 *
 * 文件本身**不加密**，因为内容里没有明文秘密：三份 `dekWrappedBy*` 是密文，KDF 参数与盐
 * 本来就该跟着密文一起存（红线 3）。
 *
 * `pinFailCount` 放在未加密存储里意味着有文件访问权的攻击者可以把它改回 0——这是明知的
 * 取舍（§6.1 末尾）：失败退避防的是"拿到已锁定手机的人手动试"，防不了离线攻击。
 * 6 位数字 PIN 在离线场景下本来就挡不住有 GPU 的人。
 */
@Serializable
data class BootRecord(
    /** 格式版本。**不认识的版本一律落 [BootState.Corrupt]**，不猜、不静默升级（红线 9）。 */
    val format: Int = FORMAT_V1,

    /** 本设备标识。备份包的跨表引用与冲突提示用它（§12.1）。 */
    val deviceId: String,

    /** 引导完成过没有。为假时 `AppRoot` 走引导流程。 */
    val onboarded: Boolean = false,

    /** PIN 那条路的 KDF 参数与盐。引导完成后必然非空。 */
    val pinKdf: KdfParams? = null,

    /** 恢复密钥那条路的 KDF 参数与盐。**与 PIN 各有一份自己的盐**（§7.1）。 */
    val recoveryKdf: KdfParams? = null,

    @Serializable(with = ByteArrayAsBase64::class)
    val dekWrappedByPin: ByteArray? = null,

    @Serializable(with = ByteArrayAsBase64::class)
    val dekWrappedByBiometric: ByteArray? = null,

    @Serializable(with = ByteArrayAsBase64::class)
    val dekWrappedByRecovery: ByteArray? = null,

    /** 连续解锁失败次数。**杀进程不清零**，所以它必须在这里而不是内存里。 */
    val pinFailCount: Int = 0,

    /** 退避终点的绝对时刻。存绝对时刻而不是剩余秒数，理由同上。 */
    val pinLockUntil: Long? = null,

    /**
     * 生物识别开关。**独立持久化**（红线 5）：关掉之后任何解锁路径都不得悄悄打开它，
     * 所以它不能从"`dekWrappedByBiometric` 是否存在"推导出来——那样删不掉的密文
     * 就等于打不开的开关。
     */
    val biometricEnabled: Boolean = false,

    /** 配色模式。锁屏页也要用，所以权威存储在这里。 */
    val themeMode: String = DEFAULT_THEME_MODE,

    /** 应用语言标签（BCP 47），空串表示跟随系统。 */
    val localeTag: String = "",
) {
    /** 有没有恢复密钥那条路。`LockPhase.Locked.hasRecoveryKey` 用它。 */
    val hasRecoveryWrap: Boolean get() = dekWrappedByRecovery != null

    /** 生物识别那条路当前能不能用：开关开着**且**密文在。 */
    val biometricUsable: Boolean get() = biometricEnabled && dekWrappedByBiometric != null

    // data class 带 ByteArray 时自动生成的 equals 比引用，必须手写。
    // 不手写的后果很隐蔽：读回来的记录与写进去的永远判不相等，于是"没变就不写"的优化失效，
    // 每次都多写一次 boot 文件——而每次写都是一次撕裂风险。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BootRecord) return false
        return format == other.format &&
            deviceId == other.deviceId &&
            onboarded == other.onboarded &&
            pinKdf == other.pinKdf &&
            recoveryKdf == other.recoveryKdf &&
            dekWrappedByPin.contentEquals(other.dekWrappedByPin) &&
            dekWrappedByBiometric.contentEquals(other.dekWrappedByBiometric) &&
            dekWrappedByRecovery.contentEquals(other.dekWrappedByRecovery) &&
            pinFailCount == other.pinFailCount &&
            pinLockUntil == other.pinLockUntil &&
            biometricEnabled == other.biometricEnabled &&
            themeMode == other.themeMode &&
            localeTag == other.localeTag
    }

    override fun hashCode(): Int {
        var result = format
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + onboarded.hashCode()
        result = 31 * result + (pinKdf?.hashCode() ?: 0)
        result = 31 * result + (recoveryKdf?.hashCode() ?: 0)
        result = 31 * result + (dekWrappedByPin?.contentHashCode() ?: 0)
        result = 31 * result + (dekWrappedByBiometric?.contentHashCode() ?: 0)
        result = 31 * result + (dekWrappedByRecovery?.contentHashCode() ?: 0)
        result = 31 * result + pinFailCount
        result = 31 * result + (pinLockUntil?.hashCode() ?: 0)
        result = 31 * result + biometricEnabled.hashCode()
        result = 31 * result + themeMode.hashCode()
        result = 31 * result + localeTag.hashCode()
        return result
    }

    /** 刻意不打印任何密文与盐。这个记录会进日志与错误消息。 */
    override fun toString(): String =
        "BootRecord(format=$format, onboarded=$onboarded, biometricEnabled=$biometricEnabled, " +
            "pinFailCount=$pinFailCount, wraps=[" +
            listOfNotNull(
                dekWrappedByPin?.let { "pin" },
                dekWrappedByBiometric?.let { "biometric" },
                dekWrappedByRecovery?.let { "recovery" },
            ).joinToString("/") +
            "])"

    companion object {
        const val FORMAT_V1 = 1
        const val DEFAULT_THEME_MODE = "System"
    }
}

/**
 * 读 boot 的结果。
 *
 * 三档必须分开，这是红线 26 的形状：
 * - [Missing] 是**正常的初始状态**（全新安装），走引导。
 * - [Corrupt] 是**异常**，走 `LockPhase.BootCorrupt`，只给"从备份恢复"和"清空重来"两个出口。
 *
 * 把 [Missing] 与 [Corrupt] 合并成"没读到"是最容易犯的错，代价也最大：那样一次撕裂写入
 * 会被当成全新安装，于是应用高高兴兴地让用户重新设 PIN，而库里所有密文从此永久解不开——
 * 用户会以为"应用把我的数据删了"，而且他确实没救了。
 */
sealed interface BootState {
    data object Missing : BootState
    data class Ok(val record: BootRecord) : BootState
    data class Corrupt(val reason: String) : BootState
}
