package com.lc33.tokenvault.screens.model

/**
 * M0.8 阶段的设置项快照。
 *
 * 存在的理由是让开关**看起来是活的**：页面只接 state + 回调，所以状态必须由外面
 * 持有。M0.8 由 NavHost 用 `remember` 兜着（切页保留、杀进程丢弃），M1 / M2 换成
 * `SettingsRepository` 时页面签名不用动。
 *
 * 字段名与 §13.4 那七组一一对应，方便到时候按名字对着接。
 */
data class SettingsDraft(
    // 外观
    val colorSchemeIndex: Int = 0,
    val squircle: Boolean = true,
    val blurNavBar: Boolean = true,

    // 安全
    val biometricUnlock: Boolean = false,
    val autoLockIndex: Int = 2,
    val idleLock: Boolean = false,
    val lockOnScreenOff: Boolean = false,
    val secureFlag: Boolean = true,
    val clipboardClearIndex: Int = 1,

    // 探测
    val autoProbeIndex: Int = 0,
    /**
     * 这四项是**新建供应商时的默认值**，不是总开关（红线 36）。
     *
     * 权威在 `ProviderDraft` 的同名字段上：新建时从这里拷一份，之后各自独立。
     * 改这里不会动已有的供应商——每家站的规则不一样，有的按 ToS 就不允许探测。
     */
    val defaultProbeReachability: Boolean = true,
    val defaultProbeKeys: Boolean = true,
    val defaultProbeBalance: Boolean = true,
    /** L3 默认关：它要花钱，而且只能手动触发。 */
    val defaultProbeModels: Boolean = false,
    val sniffClientProfile: Boolean = true,
    val verboseHttpLog: Boolean = false,

    // 同步
    val autoBackup: Boolean = false,
    val autoBackupWifiOnly: Boolean = true,

    // 更新
    val updateChannelIndex: Int = 0,
    val autoCheckUpdate: Boolean = false,
)
