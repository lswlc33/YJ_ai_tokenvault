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
import kotlin.concurrent.Volatile
import kotlinx.coroutines.launch

/**
 * 自动锁定（§7.4）。
 *
 * 四条设计决定：
 *
 * 1. **切后台就起一个定时器，到点在后台真的锁掉**，而不是等用户回来再补锁。DEK 在内存里
 *    多待一秒就多一秒被 dump 的窗口，而"回来时再检查"对着一个已经被翻过的进程毫无意义。
 *    回到前台仍然再算一次，因为 Doze 与应用待机会把 [delay] 拖过时限。
 * 2. **"离开了多久"要有两个时钟**（时间一律注入，红线 20）：注入的单调时钟给用户改系统时间、
 *    跨时区都动不了它，但在 iOS 上它不含设备睡眠；墙钟含睡眠却改得动。两个一起看，
 *    任一超时即锁（见 [sleepAwareElapsedMillis] 与下面第 4 条）。
 * 3. **锁定这件事要能被界面观察到**。[VaultSession] 刻意不暴露 `StateFlow`，所以这里补一个
 *    只带"锁了"这一个事实的 [locked]；`LockViewModel` 收到就清掉界面上所有明文状态并换树。
 *
 * 4. **前台空闲锁定与"屏幕关闭即锁定"是平台能力，不是通用能力**：Android 有
 *    `Activity.onUserInteraction` 与 `ACTION_SCREEN_OFF`，iOS 两个都没有（前者要 swizzle
 *    `UIApplication.sendEvent`）。所以这两项由 [supportsIdleLock] / [supportsLockOnScreenOff]
 *    决定进不进设置页，而这里再各自挡一道——**挡第二道是为了防"从 Android 备份恢复过来的
 *    设置值"在 iOS 上真的生效**：那条 30 秒空闲计时在 iOS 没有重置方，表现是"进应用 30 秒
 *    必锁"，比不实现更糟。
 */
class AutoLocker(
    private val session: VaultSession,
    private val scope: CoroutineScope,
    /** 锁定发生时顺带通知（探测引擎要停，§7.4 / §8.5）。默认空，测试不传。 */
    private val onLock: () -> Unit = {},
    /**
     * 进后台那一次的墙上时刻（epoch 毫秒），给 [sleepAwareElapsedMillis] 用。
     *
     * 声明位置在 [elapsedRealtimeMs] **之前**不是随手排的：现有调用点与测试都用
     * `AutoLocker(session, scope) { … }` 的尾随 lambda 形式，尾随 lambda 绑的是**最后一个**
     * 参数，插到后面会把它悄悄换成另一个时钟，测试当场失真。
     */
    private val epochNowMs: () -> Long = { nowMillis() },
    private val elapsedRealtimeMs: () -> Long,
) : IdleLockSuspender {

    private val guard = Lock()

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
        set(value) {
            guard.withLock {
                field = value
                rescheduleBackgroundLockLocked()
            }
        }

    /**
     * 前台空闲锁定开关（§7.4）。开 = [AutoLockPolicy.IDLE_LOCK_SECONDS] 秒不摸屏幕就锁。
     *
     * 与 [timeout] 一样写成可变字段：这个对象是应用级单例，而权威存储是 `app_settings` 里的
     * 键，`TokenVaultApp` 订阅之后写进来。默认 false（安全侧默认值只影响「设置还没读上来」那
     * 一小段，而这一项默认就该是关）。
     */
    @Volatile
    var idleLock: Boolean = false
        set(value) {
            guard.withLock {
                field = value
                if (value) startIdleTimerLocked() else cancelIdleLocked()
            }
        }

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

    /**
     * 进后台那一次的墙上时刻，和 [backgroundedAtMs] 一起记、一起清。
     *
     * 为什么要两份：注入进来的 [elapsedRealtimeMs] 在 Android 上就是 elapsedRealtime（含睡眠），
     * 而在 iOS 上来自 `systemUptime`（**睡眠不计时**）。同一个"离开多久"的判断，
     * 一端对、一端错，所以回到前台时两个都问、任一超时即锁（详见 [sleepAwareElapsedMillis]）。
     */
    private var backgroundedAtEpochMs: Long? = null

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
        guard.withLock {
            if (!session.isUnlocked) return@withLock
            val after = timeout as? AutoLockTimeout.After ?: return@withLock
            backgroundedAtMs = elapsedRealtimeMs()
            backgroundedAtEpochMs = epochNowMs()
            scheduleBackgroundLockLocked(after, backgroundedAtMs!!)
        }
    }

    /** 回到前台。 */
    fun onEnterForeground() {
        guard.withLock {
            pending?.cancel()
            pending = null
            val since = backgroundedAtMs ?: return@withLock
            val sinceEpoch = backgroundedAtEpochMs
            backgroundedAtMs = null
            backgroundedAtEpochMs = null
            val after = timeout as? AutoLockTimeout.After ?: return@withLock
            val limit = after.seconds * MILLIS_PER_SECOND
            // 两条时间各管一头：注入的单调时钟在 iOS 上不含睡眠（只认它 = 那条设置永远不生效），
            // 墙钟在 Android 上会被用户改系统时间带跑（只认它 = 往前调一分钟就多一分钟豁免）。
            // 任一超时即锁：这是"多锁一次"与"该锁没锁"之间的选择，选前者。
            if (elapsedRealtimeMs() - since >= limit) {
                lockIfUnlocked()
            } else if (sinceEpoch != null && sleepAwareElapsedMillis(sinceEpoch) >= limit) {
                lockIfUnlocked()
            }
        }
    }

    /** 手动"立即锁定"。 */
    fun lockNow() {
        guard.withLock {
            backgroundedAtMs = null
            backgroundedAtEpochMs = null
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
        guard.withLock {
            if (!session.isUnlocked || !idleLock) return@withLock
            startIdleTimerLocked()
        }
    }

    /** 屏幕关闭（`ACTION_SCREEN_OFF`）。开开关就当场锁，否则什么都不做。 */
    fun onScreenOff() {
        guard.withLock {
            // 能力位先挡一道：iOS 上既没有广播也没有设置入口，这一句是给"从 Android 恢复
            // 过来的设置值"准备的——那台设备上没有重置计时的对手机制，直接照做就是假承诺。
            if (!supportsLockOnScreenOff || !lockOnScreenOff) return@withLock
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
        guard.withLock {
            idlePausedCount++
            cancelIdleLocked()
        }
    }

    /** 长任务结束，恢复前台空闲计时。没被挂起过时是幂等的空操作。 */
    override fun resumeIdleLock() {
        guard.withLock {
            if (idlePausedCount <= 0) return@withLock
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

    /** 重起前台空闲计时。调用方须持有 [guard]。开关关着 / 锁着 / 暂停中 / 平台不支持都不起。 */
    private fun startIdleTimerLocked() {
        cancelIdleLocked()
        if (!idleLock || !session.isUnlocked || idlePausedCount > 0) return
        // 能力位：见类注释第 4 条。iOS 上没有"用户摸了一下屏幕"的公开钩子，起了计时器
        // 就是"进应用 30 秒必锁"，所以这一层直接不起计时，而不只是把设置页那一行藏掉。
        if (!supportsIdleLock) return
        idleJob = scope.launch {
            delay(AutoLockPolicy.IDLE_LOCK_SECONDS * MILLIS_PER_SECOND)
            guard.withLock {
                lockIfUnlocked()
            }
        }
    }

    /** 设置在后台期间变化时，用新时限替换旧的延时任务。调用方须持有 [guard]。 */
    private fun rescheduleBackgroundLockLocked() {
        val since = backgroundedAtMs ?: return
        pending?.cancel()
        pending = null
        val after = timeout as? AutoLockTimeout.After ?: return
        scheduleBackgroundLockLocked(after, since)
    }

    /** 按进入后台的实际时刻计算剩余时间，不能因改设置而重新赠送一段时限。 */
    private fun scheduleBackgroundLockLocked(after: AutoLockTimeout.After, since: Long) {
        val elapsed = (elapsedRealtimeMs() - since).coerceAtLeast(0)
        val remaining = after.seconds * MILLIS_PER_SECOND - elapsed
        if (remaining <= 0) {
            backgroundedAtMs = null
            lockIfUnlocked()
            return
        }
        pending?.cancel()
        pending = scope.launch {
            delay(remaining)
            guard.withLock {
                backgroundedAtMs = null
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
