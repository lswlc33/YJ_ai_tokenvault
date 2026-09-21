package com.lc33.tokenvault.engine

import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.endpoint.UpstreamMessage

/**
 * 一趟余额刷新的结果摘要。
 *
 * 为什么不是"成功了几把"这一个数：调用点要区分三种完全不同的 0——
 * 一把都没配余额查询、配了但全挂、跑完一半挂一半。只给一个 Int 时三者长得一样，
 * 于是界面上全挂也能念"余额已刷新"（这正是原来那个毛病：`refreshAll` 数的是
 * `refreshKey(key) != null`，而失败快照也是非 null，**全挂照样算成功**）。
 *
 * [failedReason] / [failedHint] 只带**第一个**失败快照的那一份。带全部是日志页的活
 * （每一把 Key 自己那一行都有），弹条提示里塞十条原因没人看得下去。
 */
data class BalanceRefreshOutcome constructor(
    /** 真的发过请求（或本机就该判定失败）的 Key 数。没配查询类型的、开关关着的都不算。 */
    val attempted: Int,

    /** 其中拿到金额的那几把。 */
    val succeeded: Int,

    /** 第一个失败快照的机器码原因（`http 401`、`token_undecryptable`…），供界面翻成文案。 */
    val failedReason: String? = null,

    /** 上游在那次失败里自己写的那句话（已从脱敏后的原文里抽出），可能是 null。 */
    val failedHint: String? = null,

    /**
     * 整趟压根没跑起来（读库那一步就炸了）。
     *
     * 必须和 [nothingAttempted] 分开：两者的 `attempted` 都是 0，但一个是"没配余额查询"，
     * 另一个是"试了，什么都没跑到"。混起来的话界面对后者也只能念"没有需要刷新的"，
     * 用户就永远不知道那一趟其实坏了。
     */
    val aborted: Boolean = false,
) {
    /** 失败的那几把。`attempted - succeeded`，不需要单独存一份。 */
    val failed: Int get() = attempted - succeeded

    /** 这一趟压根没试：不是失败，是"没什么可查的"，界面不该说"查询失败"。 */
    val nothingAttempted: Boolean get() = attempted == 0 && !aborted

    /** 一把都没成，但确实试过（或者整趟没跑起来）。这是必须出声的那种。 */
    val allFailed: Boolean get() = aborted || (attempted > 0 && succeeded == 0)

    /** 成了几把也挂了几把。合计数字照显示，但要补一句"其中有 N 把没查到"。 */
    val partiallyFailed: Boolean get() = succeeded > 0 && failed > 0

    /** 这一趟有没有什么是要告诉用户的。全成功就不出声——卡上的数字自己会动。 */
    val needsAttention: Boolean get() = allFailed || partiallyFailed

    companion object {
        /** 什么都没跑（供应商已经不在了）。 */
        val NONE = BalanceRefreshOutcome(attempted = 0, succeeded = 0)

        /** 跑都没跑成：整趟异常收场。 */
        fun aborted(reason: String?) = BalanceRefreshOutcome(
            attempted = 0,
            succeeded = 0,
            // 截断：这一串会被拼进弹出来的那句话里，而驱动/库的异常消息偶尔是一整段描述。
            failedReason = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REASON_CHARS)
                ?: "refresh_failed",
            aborted = true,
        )

        fun of(snapshots: List<BalanceSnapshot?>): BalanceRefreshOutcome {
            val present = snapshots.filterNotNull()
            val failures = present.filter { it.failed }
            val first = failures.firstOrNull()
            return BalanceRefreshOutcome(
                attempted = present.size,
                succeeded = present.size - failures.size,
                failedReason = first?.error,
                failedHint = UpstreamMessage.of(first?.raw),
            )
        }

        private const val MAX_REASON_CHARS = 200
    }
}
