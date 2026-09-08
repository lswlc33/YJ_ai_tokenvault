package com.lc33.tokenvault.platform

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android：同时接普通返回与预见式返回。
 *
 * 只在手势真正完成时执行回调；手势中途取消时不关闭对话框或丢弃草稿，
 * 这样编辑页的放弃确认不会因为半截侧滑而改变状态。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    PredictiveBackHandler(enabled = enabled) {
        try {
            it.collect { }
            onBack()
        } catch (_: CancellationException) {
            // 手势被取消是正常路径，不能误触发页面返回或让协程产生异常日志。
        }
    }
}
