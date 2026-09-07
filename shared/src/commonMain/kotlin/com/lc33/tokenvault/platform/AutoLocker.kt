package com.lc33.tokenvault.platform

import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.AutoLockTimeout
import com.lc33.tokenvault.engine.IdleLockSuspender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 自动锁定（§7.4）。
 *
 * 三条设计决定：
 *
 * 1. **切后台就起一个定时器，到点在后台真的锁掉**，而不是等用户回来再补锁。DEK 在内存里
 *    多待一秒就多一秒被 dump 的窗口，而"回来时再检查"对着一个已经被翻过的进程毫无意义。
 *    回到前台仍然再算一次，因为 Doze 与应用待机会把 [delay] 拖过时限。
 * 2. **时间用 `SystemClock.elapsedRealtime` 而不是墙上时间**（由调用方注入，红线 20）：
 *    用户改系统时间、或者跨过时区，都不该影响"离开了多久"。
 * 3. **锁定这件事要能被界面观察到**。[VaultSession] 刻意不暴露 `StateFlow`，所以这里补一个
 *    只带"锁了"这一个事实的 [locked]；`LockViewModel` 收到就清掉界面上所有明文状态并换树。
 *
 * 还没做的两条（都在 §7.4 里）：前台空闲计时（默认关闭，要在 `dispatchTouchEvent` 里重置，
 * 而且探测或备份进行中必须暂停它）、"屏幕关闭即锁定"。两条都要先有设置项落地。
 */
class AutoLocker(
    private val session: VaultSession,
    private val scope: CoroutineScope,
    /** 锁定发生时顺带通知（探测引擎要停，§7.4 / §8.5）。默认空，测试不传。 */
    private val onLock: () -> Unit = {},
    private val elapsedRealtimeMs: () -> Long,
) : IdleLockSuspender {

    private val guard = Any()

    /**
     * 切后台多久之后锁。
     *
     * **权威存储是 `app_settings` 里的那一个键**（红线 31）：`TokenVaultApp` 订阅
     * `SettingsRepository` 之后写进来，这里不自己再读一份。写成可变字段而不是构造参数，
     * 因为这个对象是应用级单例、活得比任何一次设置变更都长。
     *
     * 默认值只在「设置还没读上来」的那一小段里生效，所以它必须落在**安全的那一侧**
     * （60 秒，而不是从不）。
     */
    @Volatile
    var timeout: AutoLockTimeout = AutoLockPolicy.DEFAULT

    /**
     * 前台空闲锁定开关（§7.4）。开 = [AutoLockPolicy.IDLE_LOCK_SECONDS] 秒不摸屏幕就锁。
     *
     * 与 [timeout] 一样写成可变字段：这个对象是应用级单例，而权威存储是 `app_settings` 里的
     * 键，`TokenVaultApp` 订阅之后写进来。默认 false（安全侧默认值只影响「设置还没读上来」那
     * 一小段，而这一项默认就该是关）。
     */
    @Volatile
    var idleLock: Boolean = false

    /** 屏幕关闭即锁定（§7.4）。开 = 收到 `ACTION_SCREEN_OFF` 当场锁。默认关。 */
    @Volatile
    var lockOnScreenOff: Boolean = false

    private val _locked = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 锁定发生了。只是一个事实，不带原因——界面对"为什么锁"没有不同的反应。 */
    val locked: SharedFlow<Unit> = _locked.asSharedFlow()

    private var backgroundedAtMs: Long? = null
    private var pending: Job? = null

    /** 前台空闲计时的挂起任务。null = 没起（关着 / 锁着 / 暂停中）。 */
    private var idleJob: Job? = null

    /**
     * 长任务（探测 / 备份）进行中挂起空闲计时的计数。用计数而不是布尔，因为探测与备份
     * 可能重叠，任一方结束都不能把另一方的暂停一起解掉。
     */
    private var idlePausedCount = 0

    /**
     * 整个应用进了后台（进程级，Activity 之间切换不算）。
     *
     * 选了「从不」就连定时器都不起，也不记「什么时候进后台的」——记了就会漏出一条路：
     * 在后台期间把设置改回有限时限，回到前台那一下会拿着一个很旧的时间戳立刻锁掉。
     */
    fun onEnterBackground() {
        synchronized(guard) {
            if (!session.isUnlocked) return
            val after = timeout as? AutoLockTimeout.After ?: return
            backgroundedAtMs = elapsedRealtimeMs()
            pending?.cancel()
            pending = scope.launch {
                delay(after.seconds * MILLIS_PER_SECOND)
                synchronized(guard) {
                    backgroundedAtMs = null
                    lockIfUnlocked()
                }
            }
        }
    }

    /** 回到前台。 */
    fun onEnterForeground() {
        synchronized(guard) {
            pending?.cancel()
            pending = null
            val since = backgroundedAtMs ?: return
            backgroundedAtMs = null
            val after = timeout as? AutoLockTimeout.After ?: return
            if (elapsedRealtimeMs() - since >= after.seconds * MILLIS_PER_SECOND) lockIfUnlocked()
        }
    }

    /** 手动"立即锁定"。 */
    fun lockNow() {
        synchronized(guard) {
            backgroundedAtMs = null
            pending?.cancel()
            pending = null
            lockIfUnlocked()
        }
    }

    /**
     * 用户摸了一下屏幕 / 按了一个键（§7.4，`dispatchTouchEvent` 与按键事件里调）。
     *
     * 只在解锁态 + 开关开着时重起空闲计时。锁着时调是空操作——不该把一次锁屏前的
     * 点击当成「解锁后的交互」。
     */
    fun onUserInteraction() {
        synchronized(guard) {
            if (!session.isUnlocked || !idleLock) return
            startIdleTimerLocked()
        }
    }

    /** 屏幕关闭（`ACTION_SCREEN_OFF`）。开开关就当场锁，否则什么都不做。 */
    fun onScreenOff() {
        synchronized(guard) {
            if (!lockOnScreenOff) return
            lockIfUnlocked()
        }
    }

    /**
     * 长任务（探测 / 备份）开始时挂起前台空闲计时（§7.4 / §8.5 / 红线 28）。
     *
     * 纯等待型任务不会触发 `dispatchTouchEvent`，所以「指望用户戳屏幕重置计时」在探测
     * 一轮（预算 120 秒）期间必然自己锁掉自己。挂起之后计时清零，任务结束由
     * [resumeIdleLock] 重新起算。
     */
    override fun pauseIdleLock() {
        synchronized(guard) {
            idlePausedCount++
            cancelIdleLocked()
        }
    }

    /** 长任务结束，恢复前台空闲计时。没被挂起过时是幂等的空操作。 */
    override fun resumeIdleLock() {
        synchronized(guard) {
            if (idlePausedCount <= 0) return
            idlePausedCount--
            if (idlePausedCount == 0) startIdleTimerLocked()
        }
    }

    /**
     * 已经锁着就什么都不做——**不重复发 [locked]**：界面收到它会把状态清成初始值，
     * 而用户此刻可能正在锁屏上输 PIN，清掉等于把他敲的几位吃掉。
     */
    private fun lockIfUnlocked() {
        if (!session.isUnlocked) return
        cancelIdleLocked()
        session.lock()
        onLock()
        _locked.tryEmit(Unit)
    }

    /** 重起前台空闲计时。调用方须持有 [guard]。开关关着 / 锁着 / 暂停中都不起。 */
    private fun startIdleTimerLocked() {
        cancelIdleLocked()
        if (!idleLock || !session.isUnlocked || idlePausedCount > 0) return
        idleJob = scope.launch {
            delay(AutoLockPolicy.IDLE_LOCK_SECONDS * MILLIS_PER_SECOND)
            synchronized(guard) {
                lockIfUnlocked()
            }
        }
    }

    /** 取消前台空闲计时。调用方须持有 [guard]。 */
    private fun cancelIdleLocked() {
        idleJob?.cancel()
        idleJob = null
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
    }
}
