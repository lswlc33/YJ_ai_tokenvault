package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.PinPolicy
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BiometricPromptText
import com.lc33.tokenvault.platform.BiometricUnlockOutcome
import com.lc33.tokenvault.platform.BiometricVault
import com.lc33.tokenvault.platform.BootState
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.UnlockResult
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.platform.monotonicNanoTime
import com.lc33.tokenvault.screens.lock.LockUiState
import com.lc33.tokenvault.screens.lock.OnboardingStep
import com.lc33.tokenvault.screens.lock.PinError
import com.lc33.tokenvault.screens.lock.UnlockUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 锁闸的状态持有者：把 [VaultSession] 桥成 `LockGate` 要的两个 `StateFlow`。
 *
 * 三条硬规矩：
 *
 * 1. **明文只活在 `CharArray` 里，而且不进 UiState**（红线 1）。PIN 缓冲是这个类的私有
 *    字段，界面只上报"敲了哪个字符"、只拿回"有几位"。UiState 会被 Compose 长期持有、
 *    还会进快照系统，一旦某个字段是 `String` 就再也擦不掉。
 * 2. **PIN 满位由这一侧提交**，界面没有"解锁"按钮——它不知道 PIN 长度策略，也不该知道。
 * 3. **PBKDF2 跑在 `Dispatchers.Default`**（§7.2）。一次派生 300–500ms，放在主线程上
 *    就是一次可见的卡顿，而那一刻用户正盯着自己刚敲完的最后一位。
 *
 * **阶段1 迁移**：删掉生物识别与恢复密钥两条路，只剩 PIN 解锁与 PIN 引导。
 */
class LockViewModel constructor(
    private val session: VaultSession,
    private val bootStore: BootStore,
    private val autoLocker: AutoLocker,
    private val vault: BiometricVault,
) : ViewModel() {

    private val _phase = MutableStateFlow<LockPhase>(LockPhase.Loading)
    val phase: StateFlow<LockPhase> = _phase.asStateFlow()

    private val _uiState = MutableStateFlow(LockUiState())
    val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

    /**
     * 该不该在锁屏上画生物识别入口：开关开着**且**这台设备现在能用。
     * 从 boot 派生（红线 31），不自己记一份——换指纹导致凭据失效时由解锁流程把 boot 关掉。
     */
    val biometricAvailable: StateFlow<Boolean> = bootStore.revision
        .map { readBiometricEnabled() && vault.isAvailable() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, readBiometricEnabled() && vault.isAvailable())

    /** PIN 缓冲。私有、可擦、不进 UiState。 */
    private var pinBuffer = CharArray(MAX_PIN)
    private var pinLength = 0

    /** 引导第一步输入的 PIN，等第二步比对。比对完立刻擦。 */
    private var firstPin: CharArray? = null

    init {
        refresh()
        // 自动锁定发生在这一层之外（进程进后台、或者用户点"立即锁定"），所以必须订阅：
        // 光靠 VaultSession 换了内部阶段，锁闸这棵树不会动。
        viewModelScope.launch {
            autoLocker.locked.collect { onLocked() }
        }
    }

    /**
     * 锁定了（自动或手动）。§7.4：**清空界面上所有明文状态**，`LockGate` 立刻换整棵树。
     *
     * 这里连引导中途的缓冲一起擦。引导没走完时不会有自动锁定（那时还没进过金库），
     * 但"立即锁定"这条路进得来，而把 `firstPin` 留在内存里没有任何好处。
     */
    private fun onLocked() {
        pinBuffer.zeroize()
        pinLength = 0
        firstPin?.zeroize()
        firstPin = null
        _uiState.value = LockUiState(unlock = UnlockUiState(pinSlots = PinPolicy.DEFAULT_SLOTS))
        _phase.value = session.currentPhase()
    }

    /** 设置里的"立即锁定"。 */
    fun onLockNow() {
        autoLocker.lockNow()
    }

    fun refresh() {
        viewModelScope.launch {
            val phase = withContext(Dispatchers.Default) { session.refresh() }
            _phase.value = phase
            if (phase is LockPhase.Locked) {
                _uiState.update { it.copy(unlock = it.unlock.copy(pinSlots = PinPolicy.DEFAULT_SLOTS)) }
            }
        }
    }

    // ------------------------------------------------------------------ PIN 输入

    fun onPinDigit(c: Char) {
        if (busy()) return
        if (pinLength >= MAX_PIN) return
        pinBuffer[pinLength++] = c
        publishPinLength()
        if (pinLength >= PinPolicy.DEFAULT_SLOTS) submitPin()
    }

    fun onPinBackspace() {
        if (busy()) return
        if (pinLength == 0) return
        pinBuffer[--pinLength] = ' '
        publishPinLength()
        clearError()
    }

    private fun publishPinLength() {
        _uiState.update {
            it.copy(
                unlock = it.unlock.copy(pinLength = pinLength),
                onboarding = it.onboarding.copy(pinLength = pinLength),
            )
        }
    }

    private fun takePin(): CharArray {
        val taken = pinBuffer.copyOf(pinLength)
        resetPinBuffer()
        return taken
    }

    private fun resetPinBuffer() {
        pinBuffer.zeroize()
        pinLength = 0
        publishPinLength()
    }

    private fun submitPin() {
        when (_phase.value) {
            is LockPhase.Locked -> submitUnlockPin()
            LockPhase.Onboarding -> submitOnboardingPin()
            else -> resetPinBuffer()
        }
    }

    // ------------------------------------------------------------------ 解锁

    private fun submitUnlockPin() {
        val pin = takePin()
        setBusy(true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    session.unlockWithPin(pin)
                } finally {
                    // 无论成败都擦：成功时 DEK 已经在会话里，这份 PIN 再无用处
                    pin.zeroize()
                }
            }
            setBusy(false)
            applyUnlockResult(result)
        }
    }

    private fun applyUnlockResult(result: UnlockResult) {
        when (result) {
            UnlockResult.Success -> {
                clearError()
                _phase.value = LockPhase.Unlocked
            }

            is UnlockResult.WrongCredential -> {
                _phase.value = session.currentPhase()
                showError(PinError.Wrong)
            }

            is UnlockResult.InBackoff -> {
                // 不显示"PIN 不对"——这次根本没去尝试，说它错了是假话。
                // 倒计时由界面从 LockPhase.Locked.backoff 自己画。
                _phase.value = session.currentPhase()
                clearError()
            }

            is UnlockResult.Unavailable -> {
                // **不再调 `session.refresh()`**：refresh 会把会话钉住的"结构性失败"标记清掉，
                // 于是封套不认识 / KDF 参数超限这类事下一次重组就变回"请输入 PIN"，
                // 用户只会看到"我明明输对了却解不开"，而恢复页从来不出现在他面前。
                // 阶段由会话自己决定（它手里才有那份判定），这里只跟随。
                _phase.value = session.currentPhase()
                clearError()
            }
        }
    }

    // ------------------------------------------------------------------ 生物识别解锁（§7.3）

    /**
     * 用生物识别解锁。验证与取 DEK 都在 [BiometricVault] 里完成，成功时 DEK 已交给
     * [VaultSession]，这里只推进阶段。
     *
     * 凭据失效（换指纹 / Keychain 项没了）时做三件事（红线 5）：删平台凭据、关开关、清包裹，
     * 然后退回 PIN——只清一半会留下"开关开着但永远解不开"的状态。
     *
     * 那三件事里的**写 boot 会抛**：[com.lc33.tokenvault.platform.BaseBootStore.update]
     * 拒绝对损坏文件做增量修改（一次"顺手清一下"把损坏文件覆盖成"看起来正常、实际丢了所有
     * 密文"的新文件，是不可挽回的）。所以这里接住它、把界面留在恢复页，而不是让异常
     * 从锁屏上冒出去。
     */
    fun unlockWithBiometric(prompt: BiometricPromptText) {
        if (busy()) return
        viewModelScope.launch {
            setBusy(true)
            val outcome = vault.unlock(currentBiometricBlob(), prompt)
            setBusy(false)
            when (outcome) {
                BiometricUnlockOutcome.Success -> {
                    clearError()
                    _phase.value = LockPhase.Unlocked
                }

                BiometricUnlockOutcome.Cancelled -> {
                    // 用户取消：什么都不做，红灯留着让他接着输 PIN。
                }

                BiometricUnlockOutcome.Invalidated -> {
                    vault.disable()
                    // 善后那一步不许崩（见函数注释）：写不进去时开关仍从 boot 派生回原值，
                    // 但阶段一定要跟着最新判定走，否则界面停在"请输入 PIN"上打转。
                    runCatching {
                        bootStore.update {
                            it.copy(biometricEnabled = false, dekWrappedByBiometric = null)
                        }
                    }.onFailure { showError(PinError.BootWriteFailed) }
                    _phase.value = session.refresh()
                }

                BiometricUnlockOutcome.LockedOut -> {
                    // 系统只是暂时不答应（连续按错太多次）。**什么都不许改**：boot 里那份
                    // 凭据是好的，清掉它等于把"过两分钟再来一次"变成"必须用 PIN 重开一遍"。
                    _phase.value = session.currentPhase()
                    showError(PinError.BiometryLockedOut)
                }

                is BiometricUnlockOutcome.Error -> {
                    // 报"这一步没成、请用 PIN"，而不是复用 PIN 错误那句：这一次根本没试过 PIN，
                    // 说"PIN 不对"是假话，而且会把用户的注意力推到一件他没做的事上。
                    _phase.value = session.currentPhase()
                    showError(PinError.BiometryFailed)
                }
            }
        }
    }

    private fun readBiometricEnabled(): Boolean =
        (bootStore.read() as? BootState.Ok)?.record?.biometricEnabled == true

    private fun currentBiometricBlob(): ByteArray? =
        (bootStore.read() as? BootState.Ok)?.record?.dekWrappedByBiometric

    // ------------------------------------------------------------------ 引导

    private fun submitOnboardingPin() {
        val onboarding = _uiState.value.onboarding
        when (onboarding.step) {
            OnboardingStep.SetPin -> {
                val pin = takePin()
                val weakness = PinPolicy.weaknessOf(pin)
                if (weakness != null) {
                    pin.zeroize()
                    showError(PinError.TooSimple)
                    return
                }
                firstPin?.zeroize()
                firstPin = pin
                _uiState.update {
                    it.copy(onboarding = it.onboarding.copy(step = OnboardingStep.ConfirmPin, error = null))
                }
            }

            OnboardingStep.ConfirmPin -> {
                val second = takePin()
                val first = firstPin
                if (first == null || !first.contentEquals(second)) {
                    second.zeroize()
                    showError(PinError.Mismatch)
                    return
                }
                second.zeroize()
                runOnboard(first)
            }

            else -> resetPinBuffer()
        }
    }

    /** 跑基准 + 生成 DEK + 包裹。无百分比可给，所以界面画不可取消的无限进度。 */
    private fun runOnboard(pin: CharArray) {
        _uiState.update {
            it.copy(onboarding = it.onboarding.copy(step = OnboardingStep.Calibrating, busy = true, error = null))
        }
        viewModelScope.launch {
            // 这两步都要写 boot，而**写盘会抛**（磁盘满 / rename 失败 / 文件被判损坏）。异常
            // 一律不许从这一层冒出去：Calibrating 那一屏没有键盘也没有取消按钮，一旦停在
            // 这里，用户唯一的出路就是杀掉进程重来。
            // `completeOnboarding()` 原来已经被 catch 过，但 `onboard()` 没有——那一步同样在写
            // 同一份文件，风险一模一样，漏掉它的表现就是引导页崩溃。
            val landed = withContext(Dispatchers.Default) {
                try {
                    session.onboard(pin, ::monotonicNanoTime)
                    // 引导的最后一步才把 onboarded 落盘（见 VaultSession.completeOnboarding）
                    session.completeOnboarding()
                    true
                } catch (_: Exception) {
                    false
                } finally {
                    pin.zeroize()
                    firstPin = null
                }
            }
            _uiState.update {
                it.copy(
                    onboarding = it.onboarding.copy(
                        busy = false,
                        pinLength = 0,
                        // 没写进去就退回「设 PIN」那一步，而不是停在 Calibrating：
                        // 此刻库里还没有任何数据，重来一次的代价只是重新敲六下数字，
                        // 而停在无出口的屏幕上代价是整个金库没能建起来。
                        step = if (landed) OnboardingStep.Calibrating else OnboardingStep.SetPin,
                        error = if (landed) null else PinError.BootWriteFailed,
                    ),
                )
            }
            if (!landed) return@launch
            _phase.value = session.currentPhase()
        }
    }

    fun onOnboardingNext() {
        val current = _uiState.value.onboarding
        when (current.step) {
            OnboardingStep.Welcome -> setOnboardingStep(OnboardingStep.SetPin)
            else -> Unit
        }
    }

    fun onOnboardingBack() {
        resetPinBuffer()
        val current = _uiState.value.onboarding.step
        val previous = when (current) {
            OnboardingStep.SetPin -> OnboardingStep.Welcome
            OnboardingStep.ConfirmPin -> OnboardingStep.SetPin
            else -> return
        }
        firstPin?.zeroize()
        firstPin = null
        setOnboardingStep(previous)
    }

    // ------------------------------------------------------------------ BootCorrupt

    /**
     * 清空重来。**只有 `BootCorrupt` 页那个二次确认之后才允许调**——它把 boot 删掉，
     * 于是库里所有密文永久解不开。
     *
     * 必须**同时**删掉平台侧的生物识别凭据：`boot` 没了之后 `biometricEnabled` 当然没了，
     * 但 Keystore 别名 / Keychain 项还在设备上，而下一次引导会生成一把**全新**的 DEK。
     * 留着那份旧凭据的表现很糟：Android 那一侧的别名是固定的，`enable()` 会先重建密钥再写
     * 新包裹，看着没事；iOS 那份是旧的、没人读的 DEK，一直躺在 Keychain 里直到用户手动关开关。
     * "抹掉重来"就要真的抹干净，不留任何还活着的安全材料。
     *
     * 同理也要把**会话内存里的那把 DEK** 一起交出去（[VaultSession.lock]）：boot 没了后它再也
     * 包不回去，而 [com.lc33.tokenvault.platform.VaultSession.currentPhase] 在 DEK 还在内存时
     * 一律报 Unlocked——那是给"意外读不到文件、还想抢救一次导出备份"留的窗口，不是给主动清库
     * 留的后门。不锁的后果是界面停在金库里、回不到下一次引导。
     */
    fun onWipeAndStartOver() {
        runCatching { vault.disable() }
        bootStore.clear()
        // 剪贴板与已知明文清单由 lock() 顺手清掉——这正是"抹干净"的一部分。
        session.lock()
        // refresh 而不是直接用 lock() 的阶段：它同时清掉会话钉住的结构性失败标记，
        // 而 boot 刚被删成 Missing，重算出来就是引导页。
        _phase.value = session.refresh()
        _uiState.value = LockUiState()
    }

    // ------------------------------------------------------------------ 杂项

    private fun busy(): Boolean =
        _uiState.value.unlock.busy || _uiState.value.onboarding.busy

    private fun setBusy(value: Boolean) {
        _uiState.update {
            it.copy(
                unlock = it.unlock.copy(busy = value),
                onboarding = it.onboarding.copy(busy = value),
            )
        }
    }

    private fun setOnboardingStep(step: OnboardingStep) {
        _uiState.update { it.copy(onboarding = it.onboarding.copy(step = step, error = null)) }
    }

    private fun showError(error: PinError) {
        resetPinBuffer()
        _uiState.update {
            it.copy(
                unlock = it.unlock.copy(error = error),
                onboarding = it.onboarding.copy(error = error),
            )
        }
    }

    private fun clearError() {
        _uiState.update {
            it.copy(unlock = it.unlock.copy(error = null), onboarding = it.onboarding.copy(error = null))
        }
    }

    override fun onCleared() {
        pinBuffer.zeroize()
        firstPin?.zeroize()
    }

    private companion object {
        /** 缓冲上限。长口令模式（§7.2）做出来时这个值要跟着变。 */
        const val MAX_PIN = 64
    }
}
