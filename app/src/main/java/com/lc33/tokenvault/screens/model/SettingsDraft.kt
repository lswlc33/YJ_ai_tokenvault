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
    val enableL3: Boolean = false,
    /** §8.3：models 路由不鉴权的站，L2 会退化成一次真实调用，所以这一项会花钱。 */
    val allowUpgradedL2: Boolean = true,
    val sniffClientProfile: Boolean = true,
    val verboseHttpLog: Boolean = false,

    // 同步
    val autoBackup: Boolean = false,
    val autoBackupWifiOnly: Boolean = true,

    // 更新
    val updateChannelIndex: Int = 0,
    val autoCheckUpdate: Boolean = false,
)
