package com.lc33.tokenvault.screens.lock

import androidx.compose.runtime.Immutable
import com.lc33.tokenvault.domain.BiometricAvailability

/**
 * 锁屏与引导这几页的 UI 状态（计划.md §7.1–7.4、§13.1）。
 *
 * 与 `domain/LockPhase` 的分工：`LockPhase` 说的是**会话处在哪个阶段**（后端的 `VaultSession`
 * 持有），这里放的是**只属于界面的那部分**——输入了几位、上一次错在哪、引导走到第几步。
 * 分开的理由很实际：这些东西每敲一下键就变一次，塞进 `LockPhase` 会让整棵树跟着重组。
 *
 * 一条贯穿全文件的规矩：**这里不出现明文 PIN 与明文恢复密钥**。明文只允许活在
 * `CharArray` 里并且能被擦掉（红线 1），而 UiState 会被 Compose 长期持有、还会进快照系统，
 * 一旦某个字段是 `String` 就再也擦不掉了。所以界面只上报"敲了哪个字符"，只拿回"有几位"。
 */

/** 引导的六步。顺序即流程，`ordinal` 用来画进度，所以不要重排。 */
enum class OnboardingStep {
    /** 先把这个应用存什么、丢了会怎样说清楚，再让人设 PIN。 */
    Welcome,
    SetPin,
    ConfirmPin,

    /** 跑 Argon2id 基准（§7.2）。无百分比、不可取消。 */
    Calibrating,
    Biometric,

    /** 生成并展示恢复密钥，勾选"我已保存"才能继续（§7.1）。 */
    RecoveryKey,
}

/**
 * 输入错误。做成枚举而不是直接给一句话：文案归 strings.xml（红线 19），
 * 而且同一种错在引导页与解锁页必须是同一句话（红线 17）。
 */
enum class PinError {
    /** 两次输入不一致（引导）。 */
    Mismatch,

    /** 太容易猜：连续数字、全同、生日形状。 */
    TooSimple,

    /** PIN 不对（解锁）。 */
    Wrong,

    /** 恢复密钥不是 32 个 hex 字符。 */
    RecoveryKeyMalformed,

    /** 形状对但解不开。 */
    RecoveryKeyWrong,
}

/**
 * 这一档说的是恢复密钥还是 PIN。
 *
 * 用来挡掉不相干的那条错误：从 PIN 切到恢复密钥输入时，上一次"PIN 不对"还挂在状态里，
 * 而那时标题已经是「恢复密钥」——两句话拼在一起会让人以为恢复密钥也错了。
 */
val PinError.isRecoveryError: Boolean
    get() = this == PinError.RecoveryKeyMalformed || this == PinError.RecoveryKeyWrong

/**
 * 引导页。
 *
 * @param pinSlots 画几个点。由持有明文的那一侧给，界面不自己定 PIN 长度策略——
 *   设置里可以把凭据换成任意长度口令（§7.2），那时这个值没有意义（见 [UnlockUiState.passphraseMode]）。
 * @param recoveryKeyDisplay 分组后的展示串，**只在走到 [OnboardingStep.RecoveryKey] 那一刻**
 *   由 `RecoveryKey.formatForDisplay` 生成。它是一个擦不掉的 `String`，所以那一页必须挂
 *   `SecureScreen()`，离开就丢引用。
 * @param biometric 系统 `canAuthenticate` 的结果，**null 表示还没问出来**。
 *   做成可空是因为"还不知道"是一个真实存在的状态（第一帧、异步探测中、`.copy()` 漏了这个字段），
 *   而它没有任何一档可以用来冒充：`NOT_ENABLED_BY_USER` 在引导里尤其不行——本应用的那个
 *   开关就是这一步本身（[biometricOptIn]），传它会画出"你在本应用里关掉了生物识别"这种
 *   自相矛盾的话。null 时这一步画的是"正在检查"。
 */
@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val pinLength: Int = 0,
    val pinSlots: Int = 6,
    val error: PinError? = null,
    val biometric: BiometricAvailability? = null,
    val biometricOptIn: Boolean = false,
    val recoveryKeyDisplay: String? = null,
    val recoveryKeySaved: Boolean = false,
    /** 基准或包裹 DEK 正在跑。为真时整页不接受输入。 */
    val busy: Boolean = false,
)

/**
 * 解锁页。
 *
 * @param recoveryMode 用户点了「用恢复密钥解锁」。它是另一条解 DEK 的路，不是"重置 PIN"。
 *
 * 这里**没有**"长口令模式"：§7.2 允许在设置里把 6 位 PIN 换成任意长度口令，
 * 但那个设置项本身还不存在。等它做出来时一起补，连带补一个带掩码的输入框——
 * 现在先放一个进不去的分支，只会是一段没人验证过的死代码。
 */
@Immutable
data class UnlockUiState(
    val pinLength: Int = 0,
    val pinSlots: Int = 6,
    val error: PinError? = null,
    val busy: Boolean = false,
    val recoveryMode: Boolean = false,
)

/**
 * `LockGate` 要的全部界面状态：`LockPhase` 之外那些只属于界面的东西。
 *
 * 两个子状态放一个壳里传，是为了让 `AppRoot` → `LockGate` 那一层只有一个 state 参数——
 * 引导与解锁不会同时显示，但把它们合成一个类会得到一堆"这个字段这一步没意义"的空洞。
 */
@Immutable
data class LockUiState(
    val unlock: UnlockUiState = UnlockUiState(),
    val onboarding: OnboardingUiState = OnboardingUiState(),
)

/**
 * 锁闸的全部回调。
 *
 * 做成一个类而不是二十个参数：它要经过 `AppRoot` → `LockGate` → 各页面两层，
 * 参数列表会在每一层重复一遍，加一个回调得改三处。
 *
 * **在 `AppRoot` 里 `remember` 一次**再传下来：每次重组都新建一份的话，
 * `@Immutable` 也救不了跳过重组——Compose 比的是实例相等。
 */
@Immutable
data class LockCallbacks(
    // ---- 解锁
    /** 敲了一个字符。用 `Char` 而不是 `Int`，恢复密钥的 hex 输入能共用同一条路。 */
    val onPinDigit: (Char) -> Unit = {},
    val onPinBackspace: () -> Unit = {},
    val onBiometricUnlock: () -> Unit = {},
    val onEnterRecoveryMode: () -> Unit = {},
    val onExitRecoveryMode: () -> Unit = {},
    /** 恢复密钥解锁。参数未规范化，交给 `RecoveryKey.normalize`；用完必须擦。 */
    val onRecoveryUnlock: (CharArray) -> Unit = {},

    // ---- 引导
    val onOnboardingNext: () -> Unit = {},
    val onOnboardingBack: () -> Unit = {},
    val onBiometricOptIn: (Boolean) -> Unit = {},
    /** 跳系统的生物识别录入页。三种"用户自己能解决"的档位才显示这个按钮。 */
    val onOpenBiometricEnroll: () -> Unit = {},
    /** 复制恢复密钥。剪贴板策略（敏感标记 + 自动清除）在 `platform/`，界面不碰剪贴板。 */
    val onCopyRecoveryKey: () -> Unit = {},
    val onRecoveryKeySavedChange: (Boolean) -> Unit = {},

    // ---- BootCorrupt（红线 26）：只有这两个出口，没有第三条路
    val onRestoreFromBackup: () -> Unit = {},
    val onWipeAndStartOver: () -> Unit = {},
)
