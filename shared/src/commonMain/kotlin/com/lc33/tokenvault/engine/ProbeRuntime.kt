package com.lc33.tokenvault.engine

/**
 * 探测引擎对"会话锁定态"的最小依赖。
 *
 * [com.lc33.tokenvault.platform.VaultSession] 是 Android 专属的具体类（持有明文 DEK、绑
 * boot 存储），探测引擎只关心"现在解锁了没"这一个事实，所以抽成这个接口让引擎迁到
 * commonMain。实现由 `VaultSession` 直接提供（`isUnlocked` 已是属性）。
 */
interface ProbeSession {
    /** 当前是否解锁（DEK 在内存里）。锁定态探测不能 reveal 密钥。 */
    val isUnlocked: Boolean
}

/**
 * 探测引擎对"前台空闲锁定"的最小依赖。
 *
 * 一轮探测预算可达 120 秒，期间用户不摸屏幕是常态，不挂起空闲锁定会自己锁掉自己
 * （§7.4 / 红线 28）。实现由 [com.lc33.tokenvault.platform.AutoLocker] 提供。
 */
interface IdleLockSuspender {

    /** 长任务开始：挂起前台空闲计时（计数式，可嵌套）。 */
    fun pauseIdleLock()

    /** 长任务结束：恢复前台空闲计时（幂等）。 */
    fun resumeIdleLock()
}
