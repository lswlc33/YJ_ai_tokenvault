package com.lc33.tokenvault

import org.jetbrains.compose.ui.window.ComposeUIViewController
import com.lc33.tokenvault.data.seed.ProfileSeeder
import com.lc33.tokenvault.di.Qualifiers
import com.lc33.tokenvault.di.coreModule
import com.lc33.tokenvault.di.platformModule
import com.lc33.tokenvault.di.viewModelModule
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.ui.shell.AppRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIViewController

/**
 * iOS 入口（阶段4 落地）：Swift 壳调 `MainViewControllerKt.MainViewController()`，
 * 拿到跑着真实 Compose UI（[AppRoot]）的 UIViewController。
 *
 * 首次调用时完成一次初始化（等价 Android 端 TokenVaultApp.onCreate）：
 * 启动 Koin、挂后台/前台通知驱动 [AutoLocker]、接进程级设置订阅、种内置预设。
 *
 * 能力边界（与 Android 端的差异，都记在 迁移计划.md）：
 * - 前台空闲锁定：Android 用 Activity.onUserInteraction 重置计时，iOS 没有
 *   等价的公开钩子（要 swizzle UIApplication.sendEvent），暂不生效；
 * - 屏幕关闭即锁定：iOS 没有对应广播，暂用"进后台"那条路兜底。
 */
private var appInitialized = false

fun MainViewController(): UIViewController {
    if (!appInitialized) {
        appInitialized = true
        initIosApp()
    }
    return ComposeUIViewController { AppRoot() }
}

private fun initIosApp() {
    startKoin {
        modules(platformModule, coreModule, viewModelModule)
    }
    val koin = GlobalContext.get()
    val autoLocker = koin.get<AutoLocker>()
    val settings = koin.get<SettingsRepository>()
    val appScope = koin.get<CoroutineScope>(named(Qualifiers.APP_SCOPE))

    // 切后台/回前台驱动自动锁定（§7.4）。等价 Android 端的 ProcessLifecycleOwner 观察者。
    val center = NSNotificationCenter.defaultCenter
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, `object` = null, queue = null) { _ ->
        autoLocker.onEnterBackground()
    }
    center.addObserverForName(UIApplicationWillEnterForegroundNotification, `object` = null, queue = null) { _ ->
        autoLocker.onEnterForeground()
    }

    // 自动锁定时限接在进程级订阅上，不接在设置页的 ViewModel 上（同 Android 端的理由：
    // 那个 ViewModel 只在用户站在那一页时活着）。这几条只碰明文列，锁定态也能跑。
    appScope.launch { settings.observeAutoLockTimeout().collect { autoLocker.timeout = it } }
    appScope.launch { settings.observeIdleLock().collect { autoLocker.idleLock = it } }
    appScope.launch { settings.observeLockOnScreenOff().collect { autoLocker.lockOnScreenOff = it } }

    // 内置客户端预设（§8.2）：幂等，只碰公开数据，锁定态也能跑。
    appScope.launch { koin.get<ProfileSeeder>().seed() }
}
