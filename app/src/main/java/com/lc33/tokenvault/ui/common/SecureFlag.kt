package com.lc33.tokenvault.ui.common

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * 防截屏 / 防录屏的作用域开关（§7.5）。
 *
 * 谁必须挂它：解锁页、引导页、恢复密钥展示页**始终**挂（与设置里那个开关无关——
 * 那个开关管的是"展示已存密钥时"，而这三页展示的是能解开整个库的东西）。
 *
 * 为什么带引用计数：`clearFlags` 是无条件的，两处同时要求防截屏时（比如恢复密钥页
 * 之上再弹一个确认弹层，弹层退出）内层的 `onDispose` 会把外层的保护一起撤掉，
 * 而**撤掉之后什么都不会报错**——下一次截屏才会发现。计数器让最后一个退出的人才清。
 */
@Composable
fun SecureScreen() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        SecureFlagRefCount.acquire(window)
        onDispose { SecureFlagRefCount.release(window) }
    }
}

/**
 * 进程级引用计数。
 *
 * 只在主线程被 Compose 的 `DisposableEffect` 调用，所以不需要同步。
 */
private object SecureFlagRefCount {

    private var holders = 0

    fun acquire(window: Window) {
        if (holders == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        holders++
    }

    fun release(window: Window) {
        holders--
        if (holders <= 0) {
            holders = 0
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
