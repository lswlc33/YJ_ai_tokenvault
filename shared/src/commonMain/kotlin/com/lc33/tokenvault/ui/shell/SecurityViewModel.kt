package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.PinPolicy
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BiometricEnableOutcome
import com.lc33.tokenvault.platform.BiometricPromptText
import com.lc33.tokenvault.platform.BiometricVault
import com.lc33.tokenvault.platform.BootRecord
import com.lc33.tokenvault.platform.BootState
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.UnlockResult
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.screens.lock.ChangePinStep
import com.lc33.tokenvault.screens.lock.ChangePinUiState
import com.lc33.tokenvault.screens.lock.PinError
import kotlinx.coroutines.CancellationException
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
 * 设置 → 安全 里真正干活的地方：改 PIN。
 *
 * 和 [LockViewModel] 同一套规矩：明文只活在私有 `CharArray` 里、不进 UiState（红线 1），
 * PBKDF2 一律跑 [Dispatchers.Default]。
 *
 * **验旧 PIN 走 `unlockWithPin` 而不是另做一个不计次的 `verifyPin`**：后者等于给改 PIN 页
 * 开一个绕过失败退避（§7.2）的入口——捡到已解锁手机的人可以在这一页无限次试旧 PIN。
 * 代价是这一页也会吃到退避倒计时，那正是想要的。
 *
 * **阶段1 迁移**：删掉生物识别与恢复密钥两行。
 */
class SecurityViewModel constructor(
    private val session: VaultSession,
    private val autoLocker: AutoLocker,
    private val settings: SettingsRepository,
    private val vault: BiometricVault,
    private val bootStore: BootStore,
    // 这一页的三项设置写入（自动锁定时限 / 前台空闲 / 息屏锁定）本来裸跑在
    // `viewModelScope.launch` 里：Room `dao.put` 抛一次（磁盘满、探测并发撞 SQLITE_BUSY）就是
    // 杀进程，而同类页面全都走 [SettingsFailures.guard]。补上，别再留第三种写法。
    private val failures: SettingsFailures,
) : ViewModel() {

    private val _changePin = MutableStateFlow(ChangePinUiState())
    val changePin: StateFlow<ChangePinUiState> = _changePin.asStateFlow()

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
            // changePin 会抛 IllegalStateException / BootCorruptException（已解锁却读不出 boot：
            // 解锁之后那份文件被改坏了）。不接住它就是从设置页冒到 Compose 之外。
            val changed = withContext(Dispatchers.Default) {
                runCatching {
                    // 红线 2：只重新包裹一次 DEK，一次 boot 写入，业务表零 UPDATE
                    session.changePin(first)
                }.isSuccess
            }
            if (!changed) {
                first.zeroize()
                newPin = null
                // 这句话要说在**改 PIN 这一页**上，而不是挂到 [_bootWriteFailed] 去：后者是安全页
                // 上生物识别那一组的说明行，用户回到那一页会看到一条与他刚才做的事无关的话，
                // 而此刻这一屏只剩"忙碌图标消失了"这种静默。
                _changePin.update { it.copy(busy = false, error = PinError.BootWriteFailed) }
                return@launch
            }
            first.zeroize()
            newPin = null
            _changePin.value = ChangePinUiState()
            _pinChanged.tryEmit(Unit)
        }
    }

    /** 退出改 PIN 页：把两个缓冲都擦掉，状态回到初始。 */
    fun onChangePinExit() {
        pinBuffer.zeroize()
        pinLength = 0
        newPin?.zeroize()
        newPin = null
        _changePin.value = ChangePinUiState()
    }

    // ------------------------------------------------------------------ 生物识别解锁（§7.3）

    /**
     * 这台设备此刻能不能用强生物识别。硬件能力不会在我们进程活着的时候变，
     * 所以在构造时问一次即可；用户去系统里录了指纹再回来，重进这一页会拿到新的 VM。
     */
    val biometricAvailable: StateFlow<Boolean> = MutableStateFlow(vault.isAvailable()).asStateFlow()

    /**
     * 开关的当前值。**从 boot 派生而不自己记一份**（红线 31）：平台侧凭据失效时
     * （换指纹、Keychain 项没了）由解锁流程把 boot 关掉，自己记一份就会画着"已开启"。
     */
    val biometricEnabled: StateFlow<Boolean> = bootStore.revision
        .map { readBiometricEnabled() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, readBiometricEnabled())

    /** 正在跑启用/关闭流程（要弹系统验证框）。界面据此把开关暂时按住。 */
    private val _biometricBusy = MutableStateFlow(false)
    val biometricBusy: StateFlow<Boolean> = _biometricBusy.asStateFlow()

    /**
     * 上一次启用生物识别时系统给的那一句（`NSError.localizedDescription` /
     * `BiometricPrompt` 的 errString）。null = 没有失败。
     *
     * 为什么不在这里自己编一句话：这一项失败的原因至少有四种（没设锁屏密码、刚换了指纹、
     * 系统弹窗起不来、Keychain 写入被拒），而系统那一句本来就是跟着用户手机语言走的。
     * 我们编的"开启失败"只会让人再点一次。
     */
    private val _biometricError = MutableStateFlow<String?>(null)
    val biometricError: StateFlow<String?> = _biometricError.asStateFlow()

    /** 生物识别被系统暂时锁住（连续失败太多次）。与 [biometricError] 分开：这一档不该出现"失败"字样。 */
    private val _biometricLockedOut = MutableStateFlow(false)
    val biometricLockedOut: StateFlow<Boolean> = _biometricLockedOut.asStateFlow()

    /**
     * 关闭这一侧时，平台上那份凭据没删掉（开关已经是关的，但设备里可能还留着一份 DEK）。
     *
     * 单独一个布尔而不是把话塞进 [biometricError]：这里通常没有系统原文可念
     * （`Throwable.message` 经常是 null），而往流里填一句英文兜底就是把红线 19 破在
     * 最没人防的那一格上——兜底文案该由资源出，VM 只出语义。
     */
    private val _biometricDisableFailed = MutableStateFlow(false)
    val biometricDisableFailed: StateFlow<Boolean> = _biometricDisableFailed.asStateFlow()

    /**
     * boot 写不进去（[com.lc33.tokenvault.platform.BaseBootStore.update] 抛
     * [IllegalStateException]：文件已损坏，或并发的另一次读-改-写抢在了前面）。
     *
     * 这一档必须是提示、不能是崩溃：`bootStore.update` 的调用点有三个（会话、锁屏的失效善后、
     * 这里的开关），后两个都不在会话那把锁的保护下，而抛出来的 ISE 会直接从设置页冒到
     * Compose 之外——用户只是翻了个开关。
     */
    private val _bootWriteFailed = MutableStateFlow(false)
    val bootWriteFailed: StateFlow<Boolean> = _bootWriteFailed.asStateFlow()

    /**
     * 开关生物识别。开启时验证并包裹 DEK，成功才写 boot；关闭时删平台凭据并清包裹。
     *
     * 取消或失败都**不写 boot**，于是开关的状态流仍读回旧值、界面自动弹回，不需要额外的回滚。
     * 两条失败路各自要说清：拿不到凭据（取消 / 锁住 / 设备不支持）与写不进文件（boot 坏了）。
     */
    fun onBiometricChange(enabled: Boolean, prompt: BiometricPromptText) {
        if (_biometricBusy.value) return
        if (enabled && !vault.isAvailable()) return
        _biometricError.value = null
        _biometricLockedOut.value = false
        _biometricDisableFailed.value = false
        _bootWriteFailed.value = false
        viewModelScope.launch {
            if (enabled) {
                _biometricBusy.value = true
                // `vault.enable` 里那把硬件密钥是现建的，`KeyGenerator.getInstance/init/generateKey`
                // 在部分 ROM 上会抛（Keystore 服务不可用、算法参数被拒），而**同一个函数里的
                // `vault.disable()` 两条都是包着调的**——这里裸调等于既崩进程又把开关永久按住
                // （`_biometricBusy` 停在 true，后面再也翻不动）。取消要照旧往外抛，不能当成一次失败。
                val outcome = try {
                    vault.enable(prompt)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    _biometricError.value = error.message
                    null
                } finally {
                    _biometricBusy.value = false
                }
                when (outcome) {
                    null -> Unit

                    is BiometricEnableOutcome.Success ->
                        if (!writeBoot { it.copy(biometricEnabled = true, dekWrappedByBiometric = outcome.blob) }) {
                            // 平台侧已经存好了一份，而 boot 里没记 —— 把它一起撤掉，
                            // 否则设备上会留下一份没人读的 DEK（开关是关的，谁也用不到它）。
                            runCatching { vault.disable() }
                        }

                    BiometricEnableOutcome.Cancelled -> Unit

                    BiometricEnableOutcome.Unavailable ->
                        // 界面那行 summary 已经在说"这台设备用不了"，不再叠一条。
                        Unit

                    BiometricEnableOutcome.LockedOut -> _biometricLockedOut.value = true

                    is BiometricEnableOutcome.Error -> _biometricError.value = outcome.message
                }
            } else {
                // 关这一侧要**先删平台凭据、再清 boot**：反过来会在两步之间留下
                // "boot 说不认识它、设备上还存着一份 DEK"的状态，而这一份再也没人来删。
                runCatching { vault.disable() }
                    .onFailure {
                        // 有系统原文就念原文，没有就靠 [biometricDisableFailed] 那条资源文案说话。
                        _biometricError.value = it.message
                        _biometricDisableFailed.value = true
                    }
                writeBoot { it.copy(biometricEnabled = false, dekWrappedByBiometric = null) }
            }
        }
    }

    /** [BootStore.update] 的包装：把"写不进去"从异常变成界面上的一句话。返回是否写成功。 */
    private fun writeBoot(transform: (BootRecord) -> BootRecord): Boolean =
        runCatching { bootStore.update(transform) }
            .onFailure { _bootWriteFailed.value = true }
            .isSuccess

    private fun readBiometricEnabled(): Boolean =
        (bootStore.read() as? BootState.Ok)?.record?.biometricEnabled == true

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
        viewModelScope.launch { failures.guard { settings.setAutoLockTimeout(AutoLockPolicy.at(index)) } }
    }

    // ------------------------------------------------------------------ 前台空闲 / 屏幕关闭锁定

    /**
     * 前台空闲锁定开关。权威是 `app_settings.idleLockSeconds`（红线 31），这里从同一条流
     * 派生，不自己记一份。初值 false（这一项默认就是关）。
     */
    val idleLock: StateFlow<Boolean> = settings.observeIdleLock()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onIdleLockChange(enabled: Boolean) {
        viewModelScope.launch { failures.guard { settings.setIdleLock(enabled) } }
    }

    /** 屏幕关闭即锁定开关。权威是 `app_settings.lockOnScreenOff`。默认关。 */
    val lockOnScreenOff: StateFlow<Boolean> = settings.observeLockOnScreenOff()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onLockOnScreenOffChange(enabled: Boolean) {
        viewModelScope.launch { failures.guard { settings.setLockOnScreenOff(enabled) } }
    }

    override fun onCleared() {
        pinBuffer.zeroize()
        newPin?.zeroize()
    }

    private companion object {
        const val MAX_PIN = 64
    }
}
