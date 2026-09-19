package com.lc33.tokenvault.ui.shell

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 「设置写不进去」的统一出口。
 *
 * 这类写入散在好几个 ViewModel 里（配色、底栏模糊、返回动画、探测默认值、会员开关、
 * 日志筛选与保留期）。它们的失败后果对用户是同一件事：开关在界面上翻过去了，库里没落下
 * 来，重启又回原样。所以这里只报一个语义、由 Shell 投一条提示，而不是每个 ViewModel 各开
 * 一条失败流、每个页面各写一个收集器。
 *
 * [guard] 存在的另一半理由是**崩溃**：`viewModelScope.launch` 里冒出来的异常没有父协程
 * 接，默认处理器会直接杀掉应用——一次写库失败不该让用户正在看的页面没了。
 */
class SettingsFailures {

    private val _events = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 有一次设置没写进去。Shell 收集它并投提示（页面不必各自收集）。 */
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun report() {
        _events.tryEmit(Unit)
    }

    /** 跑一次写入：异常在这里收口，只报告、不冒出协程。 */
    suspend fun guard(block: suspend () -> Unit) {
        // 不能用 runCatching：它会把 CancellationException 也吞成"一次写失败"，
        // ViewModel 清理时协程就再也停不下来。
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            report()
        }
    }
}
