package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.engine.BalanceRefreshOutcome
import kotlinx.coroutines.CancellationException

/**
 * 跑一趟余额刷新，把**任何**结局都压成一个可报告的 [BalanceRefreshOutcome]。
 *
 * 为什么四个刷新入口都要过这里：`BalanceEngine` 现在自己会把每一把 Key 的失败收成
 * 失败快照，但它挡不到"整趟压根没跑起来"那一层（读库那一步就炸）。以前那一层在四个
 * 调用点上各写一遍 `runCatching { ... }` 然后**把结果丢掉**，于是余额全线查不了的时候
 * 界面安静得像刚刷成功——这正是"401 / 令牌失效没有任何提示"的前半段。
 * 收在一个函数里，是因为四处各写一遍的结局一定是"某一处以后忘了补"。
 *
 * 取消仍然原样抛出去：`CancellationException` 继承 `Exception`，挡在这里就会把
 * "页面被销毁 / 锁屏"报成"余额刷新失败"。
 */
suspend fun runBalanceRefresh(
    refresh: suspend () -> BalanceRefreshOutcome,
): BalanceRefreshOutcome = try {
    refresh()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    BalanceRefreshOutcome.aborted(failure.message)
}
