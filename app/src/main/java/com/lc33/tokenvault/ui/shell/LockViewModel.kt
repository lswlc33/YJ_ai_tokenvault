package com.lc33.tokenvault.ui.shell

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.RecoveryKey
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.PinPolicy
import com.lc33.tokenvault.platform.BiometricOutcome
import com.lc33.tokenvault.platform.BiometricUnlocker
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.UnlockResult
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.screens.lock.LockUiState
import com.lc33.tokenvault.screens.lock.OnboardingStep
import com.lc33.tokenvault.screens.lock.PinError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 3. **Argon2 跑在 `Dispatchers.Default`**（§7.2）。一次派生 300–500ms，放在主线程上
 *    就是一次可见的卡顿，而那一刻用户正盯着自己刚敲完的最后一位。
 */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val session: VaultSession,
    private val bootStore: BootStore,
    private val biometric: BiometricUnlocker,
    private val clipboard: SecureClipboard,
) : ViewModel() {

    private val _phase = MutableStateFlow<LockPhase>(LockPhase.Loading)
    val phase: StateFlow<LockPhase> = _phase.asStateFlow()

    private val _uiState = MutableStateFlow(LockUiState())
    val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

    /** PIN 缓冲。私有、可擦、不进 UiState。 */
    private var pinBuffer = CharArray(MAX_PIN)
    private var pinLength = 0

    /** 引导第一步输入的 PIN，等第二步比对。比对完立刻擦。 */
    private var firstPin: CharArray? = null

    /** 恢复密钥的明文，只在展示那一步存在。离开就擦。 */
    private var recoveryKeyPlain: CharArray? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val phase = withContext(Dispatchers.Default) { session.refresh() }
            // 引导被打断在最后一步：PIN 与两条包裹都已经写好、DEK 还在会话里，只有"抄下
            // 恢复密钥"没做完（系统返回键、进程被回收、"不保留活动"都会走到这里）。
            // 旧的那串明文随 ViewModel 一起没了，所以轮换一把新的接着展示——不接的话，
            // 用户会带着一个自己没有恢复密钥的库继续用下去，而他并不知道。
            if (phase == LockPhase.Onboarding && session.isUnlocked) {
                resumeRecoveryKeyStep()
                return@launch
            }
            _phase.value = phase
            if (phase is LockPhase.Locked) {
                _uiState.update { it.copy(unlock = it.unlock.copy(pinSlots = PinPolicy.DEFAULT_SLOTS)) }
            }
        }
    }

    /**
     * 重入恢复密钥那一页。
     *
     * 用**轮换**而不是"把旧的再显示一遍"：旧的那串明文已经不存在了（这正是重入的原因），
     * 而 boot 里只有它的包裹。轮换的副作用恰好是想要的——万一用户上一次已经抄下了旧的，
     * 旧的立刻失效，不会留下一把"看着像对、其实解不开"的密钥。
     */
    private suspend fun resumeRecoveryKeyStep() {
        val key = withContext(Dispatchers.Default) { session.regenerateRecoveryKey() }
        recoveryKeyPlain?.zeroize()
        recoveryKeyPlain = key
        _phase.value = LockPhase.Onboarding
        _uiState.update {
            it.copy(
                onboarding = it.onboarding.copy(
                    step = OnboardingStep.RecoveryKey,
                    recoveryKeyDisplay = RecoveryKey.formatForDisplay(key),
                    recoveryKeySaved = false,
                    busy = false,
                    error = null,
                ),
            )
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
                showError(if (_uiState.value.unlock.recoveryMode) PinError.RecoveryKeyWrong else PinError.Wrong)
            }

            is UnlockResult.InBackoff -> {
                // 不显示"密钥不对"——这次根本没去尝试，说它错了是假话。
                // 倒计时由界面从 LockPhase.Locked.backoff 自己画。
                _phase.value = session.currentPhase()
                clearError()
            }

            is UnlockResult.Unavailable -> {
                _phase.value = session.refresh()
                clearError()
            }
        }
    }

    fun onEnterRecoveryMode() {
        resetPinBuffer()
        _uiState.update { it.copy(unlock = it.unlock.copy(recoveryMode = true, error = null)) }
    }

    fun onExitRecoveryMode() {
        resetPinBuffer()
        _uiState.update { it.copy(unlock = it.unlock.copy(recoveryMode = false, error = null)) }
    }

    fun onRecoveryUnlock(input: CharArray) {
        setBusy(true)
        viewModelScope.launch {
            val normalized = RecoveryKey.normalize(input)
            val malformed = !RecoveryKey.isWellFormed(normalized)
            val result = withContext(Dispatchers.Default) {
                try {
                    session.unlockWithRecoveryKey(normalized)
                } finally {
                    normalized.zeroize()
                    input.zeroize()
                }
            }
            setBusy(false)
            if (malformed && result is UnlockResult.WrongCredential) {
                _phase.value = session.currentPhase()
                showError(PinError.RecoveryKeyMalformed)
            } else {
                applyUnlockResult(result)
            }
        }
    }

    fun onBiometricUnlock(activity: FragmentActivity, title: String, subtitle: String, negative: String) {
        setBusy(true)
        viewModelScope.launch {
            val outcome = biometric.unlock(activity, title, subtitle, negative)
            setBusy(false)
            when (outcome) {
                BiometricOutcome.Success -> _phase.value = LockPhase.Unlocked
                // 失效 / 取消 / 出错都退回 PIN：refresh 会把新的 biometric 档位算进去
                else -> _phase.value = session.refresh()
            }
        }
    }

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

    /** 跑基准 + 生成 DEK + 包裹两条路。无百分比可给，所以界面画不可取消的无限进度。 */
    private fun runOnboard(pin: CharArray) {
        _uiState.update {
            it.copy(onboarding = it.onboarding.copy(step = OnboardingStep.Calibrating, busy = true, error = null))
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    session.onboard(pin, System::nanoTime)
                } finally {
                    pin.zeroize()
                    firstPin = null
                }
            }
            recoveryKeyPlain = result.recoveryKey
            _uiState.update {
                it.copy(
                    onboarding = it.onboarding.copy(
                        step = OnboardingStep.Biometric,
                        busy = false,
                        pinLength = 0,
                    ),
                )
            }
        }
    }

    fun onOnboardingNext() {
        val current = _uiState.value.onboarding
        when (current.step) {
            OnboardingStep.Welcome -> setOnboardingStep(OnboardingStep.SetPin)

            OnboardingStep.Biometric -> {
                // 走到恢复密钥那一步才把明文格式化成展示串——它是擦不掉的 String，
                // 所以存在时间越短越好，而那一页必须挂 SecureScreen()
                val display = recoveryKeyPlain?.let { RecoveryKey.formatForDisplay(it) }
                _uiState.update {
                    it.copy(
                        onboarding = it.onboarding.copy(
                            step = OnboardingStep.RecoveryKey,
                            recoveryKeyDisplay = display,
                        ),
                    )
                }
            }

            OnboardingStep.RecoveryKey -> {
                if (!current.recoveryKeySaved) return
                setBusy(true)
                viewModelScope.launch {
                    // 「引导走完了」这一刻才落盘。顺序不能反过来：先擦明文再写 boot，
                    // 写失败就得到一个"阶段没推进、明文也没了"的死角——那正是这一整段要防的。
                    val landed = withContext(Dispatchers.Default) {
                        try {
                            session.completeOnboarding()
                            true
                        } catch (_: IOException) {
                            false
                        }
                    }
                    setBusy(false)
                    if (!landed) return@launch
                    // 落盘了才丢引用并擦掉明文
                    recoveryKeyPlain?.zeroize()
                    recoveryKeyPlain = null
                    _uiState.update {
                        it.copy(onboarding = it.onboarding.copy(recoveryKeyDisplay = null))
                    }
                    _phase.value = session.currentPhase()
                }
            }

            else -> Unit
        }
    }

    fun onOnboardingBack() {
        resetPinBuffer()
        val current = _uiState.value.onboarding.step
        // Calibrating 之后不允许后退：DEK 已经生成并写进 boot 了，"回上一步"没有对应的撤销动作
        val previous = when (current) {
            OnboardingStep.SetPin -> OnboardingStep.Welcome
            OnboardingStep.ConfirmPin -> OnboardingStep.SetPin
            else -> return
        }
        firstPin?.zeroize()
        firstPin = null
        setOnboardingStep(previous)
    }

    fun onBiometricAvailability(availability: com.lc33.tokenvault.domain.BiometricAvailability) {
        _uiState.update { it.copy(onboarding = it.onboarding.copy(biometric = availability)) }
    }

    fun onBiometricOptIn(
        wanted: Boolean,
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negative: String,
    ) {
        if (!wanted) {
            biometric.disable()
            _uiState.update { it.copy(onboarding = it.onboarding.copy(biometricOptIn = false)) }
            return
        }
        viewModelScope.launch {
            val outcome = biometric.enable(activity, title, subtitle, negative)
            _uiState.update {
                it.copy(onboarding = it.onboarding.copy(biometricOptIn = outcome is BiometricOutcome.Success))
            }
        }
    }

    fun onCopyRecoveryKey(label: String) {
        val key = recoveryKeyPlain ?: return
        clipboard.copy(label, key, SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS)
    }

    fun onRecoveryKeySavedChange(saved: Boolean) {
        _uiState.update { it.copy(onboarding = it.onboarding.copy(recoveryKeySaved = saved)) }
    }

    // ------------------------------------------------------------------ BootCorrupt

    /**
     * 清空重来。**只有 `BootCorrupt` 页那个二次确认之后才允许调**——它把 boot 删掉，
     * 于是库里所有密文永久解不开。
     */
    fun onWipeAndStartOver() {
        bootStore.clear()
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
        recoveryKeyPlain?.zeroize()
        super.onCleared()
    }

    private companion object {
        /** 缓冲上限。长口令模式（§7.2）做出来时这个值要跟着变。 */
        const val MAX_PIN = 64
    }
}
