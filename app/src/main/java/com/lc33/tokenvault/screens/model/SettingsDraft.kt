package com.lc33.tokenvault.screens.model

/**
 * M0.8 阶段的设置项快照。
 *
 * 存在的理由是让开关**看起来是活的**：页面只接 state + 回调，所以状态必须由外面
 * 持有。M0.8 由 NavHost 用 `remember` 兜着（切页保留、杀进程丢弃），M2 换成
 * `SettingsRepository` 时页面签名不用动。
 *
 * 字段名与 §13.4 那七组一一对应，方便到时候按名字对着接。
 *
 * **已经有自己权威存储的三项不在这里**（红线 31：一个配置项只能有一个权威存储）：
 * 配色模式（`boot.themeMode`）、生物识别开关（`boot.biometricEnabled`）与
 * 自动锁定时限（`app_settings.autoLockSeconds`）。在这里再留一份的后果很具体——
 * 界面读草稿、真正生效的那一方读自己的存储，于是「关掉生物识别」在设置页看起来生效了、
 * 锁屏页照旧弹指纹框，而「离开应用后锁定」选了「立即」却仍然等了 60 秒。
 */
data class SettingsDraft(
    // 外观
    val squircle: Boolean = true,
    val blurNavBar: Boolean = true,

    // 安全
    val secureFlag: Boolean = true,
)
