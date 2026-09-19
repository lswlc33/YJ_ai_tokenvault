package com.lc33.tokenvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BiometricActivityHolder
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.VaultSession
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
    private val session: VaultSession by inject()
    private val clipboard: SecureClipboard by inject()

    /** 屏幕关闭广播。只在 onCreate 里注册一次，随 Activity 生命周期注销。 */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            autoLocker.onScreenOff()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 红线 21 唯一兜得住的一道：本应用显示的是 API 密钥与平台账号密码，
        // 所以这一屏**不许被截屏、不许出现在最近任务缩略图与录屏里**。
        // 逐字段遮罩（密钥卡默认打码）挡不住的是"展开之后那一刻的截屏"与通知/后台快照，
        // 而 FLAG_SECURE 是系统级强制、不依赖我们每处界面都记得遮好。
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // 剪贴板：会话锁定时要顺手清它（§7.5），而构造时机比会话晚，所以在这里绑一次。
        // 再补做上次进程被杀时没来得及执行的自动清除——那条明文不该因为进程没了就一直留着。
        session.bindClipboard(clipboard)
        runCatching { clipboard.recoverOverdueClear() }
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        setContent { AppRoot() }
    }

    /**
     * 把当前前台 Activity 交给生物识别层（§7.3）：`BiometricPrompt` 只接受
     * `FragmentActivity`，而平台层拿不到 Compose 的 LocalContext。
     */
    override fun onResume() {
        super.onResume()
        BiometricActivityHolder.current = this
    }

    override fun onPause() {
        // 只认正在前台的这一个：切走之后不该还能对着旧 Activity 弹验证框。
        if (BiometricActivityHolder.current === this) BiometricActivityHolder.current = null
        super.onPause()
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
