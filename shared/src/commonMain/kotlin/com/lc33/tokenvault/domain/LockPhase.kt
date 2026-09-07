package com.lc33.tokenvault.domain

/**
 * 解锁失败退避（§7.2）。
 *
 * 第 5 次起 30s → 1m → 5m → 15m → 1h，上限 1h，**杀进程不清零**（存在 boot 里）。
 *
 * 说清它防什么：防的是"拿到已锁定手机的人手动试"。它**防不了离线穷举**——
 * `pinFailCount` 就在未加密的 boot 文件里，有文件访问权的人可以直接改回 0。
 * 这是明知的取舍（§6.1 末尾），6 位 PIN 在离线场景下本来就挡不住有 GPU 的人。
 *
 * @param failedAttempts 连续失败次数。解锁成功后清零。
 * @param lockedUntilEpochMs 退避结束的绝对时刻；null 表示当前没在退避。
 *   存**绝对时刻**而不是剩余秒数：剩余秒数在杀进程后就没有意义了，而退避必须跨进程存活。
 */
data class UnlockBackoff(
    val failedAttempts: Int = 0,
    val lockedUntilEpochMs: Long? = null,
) {
    /**
     * 还要等多少秒。[nowEpochMs] 由调用方注入（红线 20：纯 Kotlin 层不读当前时间）。
     *
     * 已经过期时返回 0 而不是负数——UI 直接拿它做倒计时，负数会画出"还剩 -3 秒"。
     */
    fun remainingSeconds(nowEpochMs: Long): Int {
        val until = lockedUntilEpochMs ?: return 0
        val remaining = until - nowEpochMs
        return if (remaining <= 0) 0 else ((remaining + 999) / 1000).toInt()
    }

    fun isActive(nowEpochMs: Long): Boolean = remainingSeconds(nowEpochMs) > 0

    companion object {
        /** 前 4 次不退避：手滑输错很常见，一上来就罚会让人以为应用坏了。 */
        const val FREE_ATTEMPTS = 4

        /** 第 5 次起的退避阶梯，单位秒。到顶之后一直用最后一档。 */
        val LADDER_SECONDS = listOf(30, 60, 300, 900, 3600)

        /**
         * 算下一次的退避终点。
         *
         * @param failedAttempts 已经失败了几次（含刚刚这一次）。
         * @param nowEpochMs 当前时刻，注入。
         * @return 退避终点，或 null 表示还在免费额度内。
         */
        fun nextLockedUntil(failedAttempts: Int, nowEpochMs: Long): Long? {
            if (failedAttempts <= FREE_ATTEMPTS) return null
            val index = (failedAttempts - FREE_ATTEMPTS - 1).coerceAtMost(LADDER_SECONDS.lastIndex)
            return nowEpochMs + LADDER_SECONDS[index] * 1000L
        }
    }
}

/**
 * 锁定阶段（§7.4）。`AppRoot` 按它决定整棵树画什么。
 *
 * 做成 sealed interface 而不是枚举，是因为 [Locked] 与 [BootCorrupt] 要带数据：
 * 解锁页需要退避剩余时间与生物识别可用性，而 BootCorrupt 页需要说清坏在哪
 * ——那一页只有"从备份恢复"和"清空重来"两个出口，不告诉用户为什么会走到这里
 * 等于让人在两个都很可怕的选项之间瞎猜。
 */
sealed interface LockPhase {

    /** 还在读 boot 文件。这一帧要有内容，否则冷启动会闪一下白屏。 */
    data object Loading : LockPhase

    /** 没有 boot 记录（或 `onboarded = false`）：走引导。 */
    data object Onboarding : LockPhase

    /**
     * 已锁定。
     *
     * @param backoff 退避状态。UI 按 [UnlockBackoff.remainingSeconds] 画倒计时，
     *   按 [UnlockBackoff.failedAttempts] 画"已失败 N 次"。
     *
     * 阶段1 迁移后只有 PIN 一条解锁路，`biometric` 与 `hasRecoveryKey` 两个字段已删。
     */
    data class Locked(
        val backoff: UnlockBackoff = UnlockBackoff(),
    ) : LockPhase

    /** DEK 在内存里，业务界面可用。 */
    data object Unlocked : LockPhase

    /**
     * boot 文件解析失败（红线 26）。
     *
     * **绝不静默重建**：重建等于把用户的全部密钥变成一堆解不开的密文，而用户会以为
     * "应用把我的数据删了"。所以这一档必须存在、必须显式，且只给两个出口。
     *
     * @param reason 给人看的一句话（英文诊断信息由 UI 层转成本地化文案）。
     */
    data class BootCorrupt(val reason: String) : LockPhase
}
