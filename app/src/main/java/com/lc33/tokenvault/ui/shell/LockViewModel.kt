package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.PinPolicy
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.UnlockResult
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.screens.lock.LockUiState
import com.lc33.tokenvault.screens.lock.OnboardingStep
import com.lc33.tokenvault.screens.lock.PinError
import com.lc33.tokenvault.screens.lock.UnlockUiState
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * 3. **PBKDF2 跑在 `Dispatchers.Default`**（§7.2）。一次派生 300–500ms，放在主线程上
 *    就是一次可见的卡顿，而那一刻用户正盯着自己刚敲完的最后一位。
 *
 * **阶段1 迁移**：删掉生物识别与恢复密钥两条路，只剩 PIN 解锁与 PIN 引导。
 */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val session: VaultSession,
    private val bootStore: BootStore,
    private val autoLocker: AutoLocker,
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
                _phase.value = session.refresh()
                clearError()
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

    /** 跑基准 + 生成 DEK + 包裹。无百分比可给，所以界面画不可取消的无限进度。 */
    private fun runOnboard(pin: CharArray) {
        _uiState.update {
            it.copy(onboarding = it.onboarding.copy(step = OnboardingStep.Calibrating, busy = true, error = null))
        }
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    session.onboard(pin, System::nanoTime)
                } finally {
                    pin.zeroize()
                    firstPin = null
                }
            }
            // 引导完成：落盘 onboarded
            val landed = withContext(Dispatchers.Default) {
                try {
                    session.completeOnboarding()
                    true
                } catch (_: java.io.IOException) {
                    false
                }
            }
            _uiState.update {
                it.copy(onboarding = it.onboarding.copy(busy = false, pinLength = 0))
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
    }

    private companion object {
        /** 缓冲上限。长口令模式（§7.2）做出来时这个值要跟着变。 */
        const val MAX_PIN = 64
    }
}
