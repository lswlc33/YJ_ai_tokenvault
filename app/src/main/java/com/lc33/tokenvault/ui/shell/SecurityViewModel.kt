package com.lc33.tokenvault.ui.shell

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.RecoveryKey
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.BiometricAvailability
import com.lc33.tokenvault.domain.PinPolicy
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BiometricCapability
import com.lc33.tokenvault.platform.BiometricOutcome
import com.lc33.tokenvault.platform.BiometricUnlocker
import com.lc33.tokenvault.platform.BootState
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.UnlockResult
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.screens.lock.ChangePinStep
import com.lc33.tokenvault.screens.lock.ChangePinUiState
import com.lc33.tokenvault.screens.lock.PinError
import com.lc33.tokenvault.screens.lock.RecoveryKeyUiState
import com.lc33.tokenvault.screens.settings.BiometricRowState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 安全 里那两页真正干活的地方：改 PIN 与轮换恢复密钥。
 *
 * 和 [LockViewModel] 同一套规矩：明文只活在私有 `CharArray` 里、不进 UiState（红线 1），
 * Argon2 一律跑 [Dispatchers.Default]。
 *
 * **验旧 PIN 走 `unlockWithPin` 而不是另做一个不计次的 `verifyPin`**：后者等于给改 PIN 页
 * 开一个绕过失败退避（§7.2）的入口——捡到已解锁手机的人可以在这一页无限次试旧 PIN。
 * 代价是这一页也会吃到退避倒计时，那正是想要的。
 */
@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val session: VaultSession,
    private val bootStore: BootStore,
    private val clipboard: SecureClipboard,
    private val autoLocker: AutoLocker,
    private val biometricUnlocker: BiometricUnlocker,
    private val capability: BiometricCapability,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _changePin = MutableStateFlow(ChangePinUiState())
    val changePin: StateFlow<ChangePinUiState> = _changePin.asStateFlow()

    private val _recoveryKey = MutableStateFlow(RecoveryKeyUiState(hasKey = hasRecoveryWrap()))
    val recoveryKey: StateFlow<RecoveryKeyUiState> = _recoveryKey.asStateFlow()

    /** 改完 PIN 之后让导航退出去。用一次性事件而不是 UiState 里的 `done` 标志：后者会重放。 */
    private val _pinChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pinChanged: SharedFlow<Unit> = _pinChanged.asSharedFlow()

    private var pinBuffer = CharArray(MAX_PIN)
    private var pinLength = 0

    /** 第二步输入的新 PIN，等第三步比对。比对完立刻擦。 */
    private var newPin: CharArray? = null

    /** 刚轮换出来的恢复密钥明文。离开那一页就擦。 */
    private var rotatedPlain: CharArray? = null

    /** 轮换正在跑。Argon2 要几百毫秒，期间用户会以为没反应而再点一下。 */
    private var rotating = false

    /**
     * 生物识别那一行。
     *
     * **权威是 `boot.biometricEnabled`**（红线 5），所以这里从 [BootStore.revision] 派生而不是
     * 自己记一份布尔值：`BiometricUnlocker` 在失效时会自己把开关关掉（`invalidate()`），
     * 而那条路不经过这个 ViewModel。自己记一份的话，"指纹已失效、开关已被清掉"之后
     * 这一页还画着"已开启"，用户会以为下次能用指纹解锁。
     *
     * `availability` 每次重算：用户可能刚从系统设置里录了指纹再切回来。
     */
    val biometric: StateFlow<BiometricRowState> = bootStore.revision
        .map { readBiometricRow() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, readBiometricRow())

    private fun readBiometricRow(): BiometricRowState {
        val record = (bootStore.read() as? BootState.Ok)?.record
        val enabled = record?.biometricEnabled == true
        return BiometricRowState(
            enabled = enabled,
            // 已经开着的时候不问系统"能不能用"——问了也只会得到 AVAILABLE，
            // 而没开时要用它决定这一行能不能点、以及给出哪一句解释。
            availability = if (enabled) BiometricAvailability.AVAILABLE else capability.current(),
        )
    }

    /**
     * 翻生物识别开关。
     *
     * 开：必须**当场验一次**（`BiometricUnlocker.enable` 会弹系统框，成功后用会话里的 DEK
     * 加密一份存进 boot）。没验成功就什么都不写——所以这一行的状态不需要"正在等用户"这一档，
     * boot 没变、派生出来的开关就还是关着。
     *
     * 关：删 Keystore 别名 + 清包裹 + 关开关，三件事一起做（红线 5）。
     */
    fun onBiometricChange(
        wanted: Boolean,
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negative: String,
    ) {
        if (!wanted) {
            biometricUnlocker.disable()
            return
        }
        viewModelScope.launch { biometricUnlocker.enable(activity, title, subtitle, negative) }
    }

    private fun hasRecoveryWrap(): Boolean =
        (bootStore.read() as? BootState.Ok)?.record?.hasRecoveryWrap ?: false

    // ------------------------------------------------------------------ 改 PIN

    fun onPinDigit(c: Char) {
        if (_changePin.value.busy) return
        if (pinLength >= MAX_PIN) return
        pinBuffer[pinLength++] = c
        _changePin.update { it.copy(pinLength = pinLength) }
        if (pinLength >= PinPolicy.DEFAULT_SLOTS) submit()
    }

    fun onPinBackspace() {
        if (_changePin.value.busy) return
        if (pinLength == 0) return
        pinBuffer[--pinLength] = ' '
        _changePin.update { it.copy(pinLength = pinLength, error = null) }
    }

    private fun takePin(): CharArray {
        val taken = pinBuffer.copyOf(pinLength)
        pinBuffer.zeroize()
        pinLength = 0
        _changePin.update { it.copy(pinLength = 0) }
        return taken
    }

    private fun submit() {
        when (_changePin.value.step) {
            ChangePinStep.Current -> verifyCurrent(takePin())
            ChangePinStep.New -> acceptNew(takePin())
            ChangePinStep.Confirm -> confirmNew(takePin())
        }
    }

    private fun verifyCurrent(pin: CharArray) {
        _changePin.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    session.unlockWithPin(pin)
                } finally {
                    pin.zeroize()
                }
            }
            _changePin.update { state ->
                when (result) {
                    UnlockResult.Success ->
                        state.copy(step = ChangePinStep.New, busy = false, error = null)

                    is UnlockResult.WrongCredential ->
                        state.copy(busy = false, error = PinError.Wrong, backoff = result.backoff)

                    // 退避中这次根本没去试，说"PIN 不对"是假话；倒计时由界面从 backoff 自己画
                    is UnlockResult.InBackoff ->
                        state.copy(busy = false, error = null, backoff = result.backoff)

                    // 已解锁却读不出 boot = 解锁之后 boot 被改坏了。这一页给不出有意义的说法，
                    // 而下次冷启动会落到 BootCorrupt 页去处理它，所以这里只是不往下走。
                    is UnlockResult.Unavailable -> state.copy(busy = false, error = null)
                }
            }
        }
    }

    private fun acceptNew(pin: CharArray) {
        if (PinPolicy.weaknessOf(pin) != null) {
            pin.zeroize()
            _changePin.update { it.copy(error = PinError.TooSimple) }
            return
        }
        newPin?.zeroize()
        newPin = pin
        _changePin.update { it.copy(step = ChangePinStep.Confirm, error = null) }
    }

    private fun confirmNew(second: CharArray) {
        val first = newPin
        if (first == null || !first.contentEquals(second)) {
            second.zeroize()
            _changePin.update { it.copy(error = PinError.Mismatch) }
            return
        }
        second.zeroize()
        _changePin.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    // 红线 2：只重新包裹一次 DEK，一次 boot 写入，业务表零 UPDATE
                    session.changePin(first)
                } finally {
                    first.zeroize()
                    newPin = null
                }
            }
            _changePin.value = ChangePinUiState()
            _pinChanged.tryEmit(Unit)
        }
    }

    /** 退出改 PIN 页：把三个缓冲都擦掉，状态回到初始。 */
    fun onChangePinExit() {
        pinBuffer.zeroize()
        pinLength = 0
        newPin?.zeroize()
        newPin = null
        _changePin.value = ChangePinUiState()
    }

    // ------------------------------------------------------------------ 恢复密钥

    /**
     * 轮换。**旧的立刻失效**（§7.1）：包裹被覆盖，抄在纸上的那一把从此解不开。
     *
     * 重入保护是必要的：这条路要跑一次 Argon2（几百毫秒），期间用户会以为没反应而再点一下。
     */
    fun onRotateRecoveryKey() {
        if (rotating) return
        rotating = true
        viewModelScope.launch {
            val key = withContext(Dispatchers.Default) { session.regenerateRecoveryKey() }
            rotatedPlain?.zeroize()
            rotatedPlain = key
            _recoveryKey.value = RecoveryKeyUiState(
                hasKey = true,
                // 展示串是擦不掉的 String，所以只在这一刻生成，离开那一页就丢引用
                generated = RecoveryKey.formatForDisplay(key),
                saved = false,
            )
            rotating = false
        }
    }

    fun onCopyRecoveryKey(label: String) {
        val key = rotatedPlain ?: return
        clipboard.copy(label, key, SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS)
    }

    fun onRecoveryKeySavedChange(saved: Boolean) {
        _recoveryKey.update { it.copy(saved = saved) }
    }

    /** 退出恢复密钥页：擦明文、丢展示串，只留"有没有这条路"这一个事实。 */
    fun onRecoveryKeyExit() {
        rotatedPlain?.zeroize()
        rotatedPlain = null
        _recoveryKey.value = RecoveryKeyUiState(hasKey = hasRecoveryWrap())
    }

    // ------------------------------------------------------------------ 立即锁定

    fun onLockNow() {
        autoLocker.lockNow()
    }

    // ------------------------------------------------------------------ 自动锁定时限

    /**
     * 下拉当前选中的那一枚。
     *
     * **从仓库派生而不自己记一份**（红线 31）：真正生效的值由 `TokenVaultApp` 从同一条流
     * 写给 [AutoLocker]。自己记一份的表现就是这一项以前那个毛病——设置页画着「立即」、
     * 而实际上等了 60 秒。
     *
     * 初值给默认档而不是 0：流还没发第一个值时先画一个不存在的「立即」，比画默认档更像谎话。
     */
    val autoLockIndex: StateFlow<Int> = settings.observeAutoLockTimeout()
        .map { AutoLockPolicy.indexOf(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AutoLockPolicy.indexOf(AutoLockPolicy.DEFAULT),
        )

    fun onAutoLockIndexChange(index: Int) {
        viewModelScope.launch { settings.setAutoLockTimeout(AutoLockPolicy.at(index)) }
    }

    // ------------------------------------------------------------------ 前台空闲 / 屏幕关闭锁定

    /**
     * 前台空闲锁定开关。权威是 `app_settings.idleLockSeconds`（红线 31），这里从同一条流
     * 派生，不自己记一份。初值 false（这一项默认就是关）。
     */
    val idleLock: StateFlow<Boolean> = settings.observeIdleLock()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onIdleLockChange(enabled: Boolean) {
        viewModelScope.launch { settings.setIdleLock(enabled) }
    }

    /** 屏幕关闭即锁定开关。权威是 `app_settings.lockOnScreenOff`。默认关。 */
    val lockOnScreenOff: StateFlow<Boolean> = settings.observeLockOnScreenOff()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onLockOnScreenOffChange(enabled: Boolean) {
        viewModelScope.launch { settings.setLockOnScreenOff(enabled) }
    }

    override fun onCleared() {
        pinBuffer.zeroize()
        newPin?.zeroize()
        rotatedPlain?.zeroize()
    }

    private companion object {
        const val MAX_PIN = 64
    }
}
