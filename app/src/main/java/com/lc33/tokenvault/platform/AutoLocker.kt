package com.lc33.tokenvault.platform

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
    private val elapsedRealtimeMs: () -> Long,
) {

    private val guard = Any()

    /**
     * 切后台多少秒之后锁。
     *
     * 权威存储将来在 `app_settings`（M2 的 `SettingsRepository`），那时把它改成从仓库读；
     * 现在只有默认值，所以先放一个可写的字段而不是硬编码常量——硬编码的话，接设置项时
     * 要改的地方会散在两处。
     */
    var timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS

    private val _locked = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 锁定发生了。只是一个事实，不带原因——界面对"为什么锁"没有不同的反应。 */
    val locked: SharedFlow<Unit> = _locked.asSharedFlow()

    private var backgroundedAtMs: Long? = null
    private var pending: Job? = null

    /** 整个应用进了后台（进程级，Activity 之间切换不算）。 */
    fun onEnterBackground() {
        synchronized(guard) {
            if (!session.isUnlocked) return
            backgroundedAtMs = elapsedRealtimeMs()
            pending?.cancel()
            pending = scope.launch {
                delay(timeoutSeconds * MILLIS_PER_SECOND)
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
            if (elapsedRealtimeMs() - since >= timeoutSeconds * MILLIS_PER_SECOND) lockIfUnlocked()
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
     * 已经锁着就什么都不做——**不重复发 [locked]**：界面收到它会把状态清成初始值，
     * 而用户此刻可能正在锁屏上输 PIN，清掉等于把他敲的几位吃掉。
     */
    private fun lockIfUnlocked() {
        if (!session.isUnlocked) return
        session.lock()
        _locked.tryEmit(Unit)
    }

    companion object {
        /** §7.4 的默认值。 */
        const val DEFAULT_TIMEOUT_SECONDS = 60
        private const val MILLIS_PER_SECOND = 1000L
    }
}
