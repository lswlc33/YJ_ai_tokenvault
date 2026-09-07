package com.lc33.tokenvault.screens.lock

import androidx.compose.runtime.Immutable

/**
 * 锁屏与引导这几页的 UI 状态（计划.md §7.1–7.4、§13.1）。
 *
 * 与 `domain/LockPhase` 的分工：`LockPhase` 说的是**会话处在哪个阶段**（后端的 `VaultSession`
 * 持有），这里放的是**只属于界面的那部分**——输入了几位、上一次错在哪、引导走到第几步。
 * 分开的理由很实际：这些东西每敲一下键就变一次，塞进 `LockPhase` 会让整棵树跟着重组。
 *
 * 一条贯穿全文件的规矩：**这里不出现明文 PIN**。明文只允许活在 `CharArray` 里并且能被擦掉
 * （红线 1），而 UiState 会被 Compose 长期持有、还会进快照系统，一旦某个字段是 `String`
 * 就再也擦不掉了。所以界面只上报"敲了哪个字符"，只拿回"有几位"。
 *
 * **阶段1 迁移**：删掉生物识别与恢复密钥两步，引导简化为
 * Welcome → SetPin → ConfirmPin → Calibrating → 完成。
 */

/** 引导的四步。顺序即流程，`ordinal` 用来画进度，所以不要重排。 */
enum class OnboardingStep {
    /** 先把这个应用存什么、丢了会怎样说清楚，再让人设 PIN。 */
    Welcome,
    SetPin,
    ConfirmPin,

    /** 跑 PBKDF2 基准（§7.2）。无百分比、不可取消。 */
    Calibrating,
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
}

/**
 * 引导页。
 *
 * @param pinSlots 画几个点。由持有明文的那一侧给，界面不自己定 PIN 长度策略。
 */
@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val pinLength: Int = 0,
    val pinSlots: Int = 6,
    val error: PinError? = null,
    /** 基准或包裹 DEK 正在跑。为真时整页不接受输入。 */
    val busy: Boolean = false,
)

/**
 * 解锁页。
 *
 * 阶段1 迁移后只有 PIN 一条解锁路，`recoveryMode` 已删。
 */
@Immutable
data class UnlockUiState(
    val pinLength: Int = 0,
    val pinSlots: Int = 6,
    val error: PinError? = null,
    val busy: Boolean = false,
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
    /** 敲了一个字符。 */
    val onPinDigit: (Char) -> Unit = {},
    val onPinBackspace: () -> Unit = {},

    // ---- 引导
    val onOnboardingNext: () -> Unit = {},
    val onOnboardingBack: () -> Unit = {},

    // ---- BootCorrupt（红线 26）：只有这两个出口，没有第三条路
    val onRestoreFromBackup: () -> Unit = {},
    val onWipeAndStartOver: () -> Unit = {},
)
