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
 * 文件本身**不加密**，因为内容里没有明文秘密：`dekWrappedByPin` 是密文，KDF 参数与盐
 * 本来就该跟着密文一起存（红线 3）。
 *
 * `pinFailCount` 放在未加密存储里意味着有文件访问权的攻击者可以把它改回 0——这是明知的
 * 取舍（§6.1 末尾）：失败退避防的是"拿到已锁定手机的人手动试"，防不了离线攻击。
 * 6 位数字 PIN 在离线场景下本来就挡不住有 GPU 的人。
 *
 * **阶段1 迁移**：删掉恢复密钥与生物识别两条路，只留 PIN。`recoveryKdf` /
 * `dekWrappedByRecovery` / `dekWrappedByBiometric` / `biometricEnabled` 四个字段随之移除。
 * 相应地，备份口令也改回沿用 PIN（不再有独立备份口令）。
 *
 * **生物识别回归（2026-09-18）**：重新加回 `biometricEnabled` /
 * `dekWrappedByBiometric` 两项。`biometricEnabled` 是开关的**唯一权威存储**（红线 31）——
 * 锁屏页在解锁前就要知道该不该画生物识别入口，所以只能放 boot。
 * Android 把 Keystore 包裹的密文放 `dekWrappedByBiometric`；iOS 的凭据由 Keychain 的
 * 访问控制自己保管，这一项在 iOS 上恒为 null。
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

    @Serializable(with = ByteArrayAsBase64::class)
    val dekWrappedByPin: ByteArray? = null,

    /** 是否允许用生物识别代替 PIN 解锁。默认关。**开关的唯一权威存储**（红线 31）。 */
    val biometricEnabled: Boolean = false,

    /**
     * Android 侧用 Keystore 硬件密钥包裹的 DEK 密文。iOS 不用它（凭据在 Keychain）。
     * 只有 [biometricEnabled] 为真、且平台是 Android 时才非空。
     */
    @Serializable(with = ByteArrayAsBase64::class)
    val dekWrappedByBiometric: ByteArray? = null,

    /** 连续解锁失败次数。**杀进程不清零**，所以它必须在这里而不是内存里。 */
    val pinFailCount: Int = 0,

    /** 退避终点的绝对时刻。存绝对时刻而不是剩余秒数，理由同上。 */
    val pinLockUntil: Long? = null,

    /**
     * 平台 DEK 那条路（生物识别）的**身份校验密文**：`SecretBox(固定明文, HKDF(DEK,"dekcheck"))`。
     *
     * 为什么需要它：生物识别取回的 DEK 是平台直接交回的字节，没有任何认证 tag 可验，
     * 于是"任何 32 字节都算解锁成功"。有了这一份，会话在采纳之前能确认"这就是当初那把 DEK"。
     *
     * **可空且旧记录允许缺字段**：`format` 仍是 1（加一个带默认值的可选字段不构成破坏性变更，
     * 而 bump 版本会让所有老设备落 BootCorrupt）。缺字段的记录在采纳平台 DEK 时**跳过校验**
     * ——见 `VaultSession.unlockWithDek`，那里写清了为什么"跳过"比"拒绝"更能接受：
     * 拒绝会把升级用户的生物识别入口直接打死，而这条路本来在旧版本上就是无校验的。
     */
    @Serializable(with = ByteArrayAsBase64::class)
    val dekCheck: ByteArray? = null,

    /** 配色模式。锁屏页也要用，所以权威存储在这里。 */
    val themeMode: String = DEFAULT_THEME_MODE,

    /** 应用语言标签（BCP 47），空串表示跟随系统。 */
    val localeTag: String = "",
) {
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
            dekWrappedByPin.contentEquals(other.dekWrappedByPin) &&
            biometricEnabled == other.biometricEnabled &&
            dekWrappedByBiometric.contentEquals(other.dekWrappedByBiometric) &&
            pinFailCount == other.pinFailCount &&
            pinLockUntil == other.pinLockUntil &&
            dekCheck.contentEquals(other.dekCheck) &&
            themeMode == other.themeMode &&
            localeTag == other.localeTag
    }

    override fun hashCode(): Int {
        var result = format
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + onboarded.hashCode()
        result = 31 * result + (pinKdf?.hashCode() ?: 0)
        result = 31 * result + (dekWrappedByPin?.contentHashCode() ?: 0)
        result = 31 * result + biometricEnabled.hashCode()
        result = 31 * result + (dekWrappedByBiometric?.contentHashCode() ?: 0)
        result = 31 * result + pinFailCount
        result = 31 * result + (pinLockUntil?.hashCode() ?: 0)
        result = 31 * result + (dekCheck?.contentHashCode() ?: 0)
        result = 31 * result + themeMode.hashCode()
        result = 31 * result + localeTag.hashCode()
        return result
    }

    /** 刻意不打印任何密文与盐。这个记录会进日志与错误消息。 */
    override fun toString(): String =
        "BootRecord(format=$format, onboarded=$onboarded, biometric=$biometricEnabled, " +
            "pinFailCount=$pinFailCount, wraps=[" +
            listOfNotNull(
                dekWrappedByPin?.let { "pin" },
                dekWrappedByBiometric?.let { "bio" },
                dekCheck?.let { "check" },
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
