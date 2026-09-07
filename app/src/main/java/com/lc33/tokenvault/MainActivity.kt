package com.lc33.tokenvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.ui.shell.AppRoot
import org.koin.android.ext.android.inject

/**
 * 前台空闲锁定与屏幕关闭即锁定（§7.4）在这里接：
 * - 触屏 / 按键重置空闲计时走 [onUserInteraction]（`Activity` 的规范钩子，触屏与按键
 *   都会回调它，而且不是 androidx 的受限 API）；
 * - 屏幕关闭通过系统广播 `ACTION_SCREEN_OFF` 收，因为进程里没有对应生命周期回调。
 */
class MainActivity : FragmentActivity() {

    private val autoLocker: AutoLocker by inject()

    /** 屏幕关闭广播。只在 onCreate 里注册一次，随 Activity 生命周期注销。 */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            autoLocker.onScreenOff()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        setContent { AppRoot() }
    }

    override fun onDestroy() {
        // 广播收不到注销的话，屏幕关闭事件会往一个已销毁的 Activity 上投
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    /** 触屏 / 按键重置前台空闲计时（§7.4）。 */
    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLocker.onUserInteraction()
    }
}
