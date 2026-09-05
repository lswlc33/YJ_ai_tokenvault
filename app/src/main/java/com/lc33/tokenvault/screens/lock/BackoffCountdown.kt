package com.lc33.tokenvault.screens.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lc33.tokenvault.domain.UnlockBackoff
import kotlinx.coroutines.delay

/**
 * 每秒重算一次的退避剩余秒数。
 *
 * 时间从这里读（`System.currentTimeMillis()`）而不是从 domain 读：红线 20 只允许平台层碰
 * 当前时间，`UnlockBackoff` 因此把 `now` 做成参数。到 0 之后循环自然结束，不再唤醒。
 *
 * 解锁页与改 PIN 页共用：**验旧 PIN 也要受退避约束**，否则拿到已解锁手机的人可以在
 * 改 PIN 那一页无限次试旧 PIN——那一页反而成了绕开退避的入口。
 */
@Composable
fun rememberRemainingSeconds(backoff: UnlockBackoff): Int {
    var remaining by remember(backoff) {
        mutableIntStateOf(backoff.remainingSeconds(System.currentTimeMillis()))
    }
    LaunchedEffect(backoff) {
        while (remaining > 0) {
            delay(1_000)
            remaining = backoff.remainingSeconds(System.currentTimeMillis())
        }
    }
    return remaining
}
