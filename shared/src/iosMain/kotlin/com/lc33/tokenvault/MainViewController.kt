package com.lc33.tokenvault

import androidx.compose.ui.window.ComposeUIViewController
import com.lc33.tokenvault.data.seed.ProfileSeeder
import com.lc33.tokenvault.di.Qualifiers
import com.lc33.tokenvault.di.coreModule
import com.lc33.tokenvault.di.platformModule
import com.lc33.tokenvault.di.viewModelModule
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.engine.AutoRefresher
import com.lc33.tokenvault.engine.HttpConcurrencyApplier
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.ui.shell.AppRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
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
 * 能力边界（与 Android 端的差异，都记在 迁移计划.md）。三条都由 `platform/PlatformCapabilities.kt`
 * 那三个编译期能力位决定，界面上对应的行在 iOS 直接不画（做不到的不进界面，红线 19）：
 * - 前台空闲锁定（`supportsIdleLock`）：Android 用 Activity.onUserInteraction 重置计时，iOS 没有
 *   等价的公开钩子（要 swizzle UIApplication.sendEvent）。**这里因此不订阅 idleLock 设置**——
 *   订了也没有重置方，表现是"进应用 30 秒必锁"，比不提供这一项更糟；
 * - 屏幕关闭即锁定（`supportsLockOnScreenOff`）：iOS 没有对应广播，`AutoLocker.onScreenOff`
 *   在这一端没有调用方；
 * - 应用内语言入口（`supportsInAppLanguageSwitch`）：走系统「应用语言」设置，
 *   切换后还要重启应用，所以外观页那一行在 iOS 不画。
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
    val koin: Koin = startKoin {
        modules(platformModule, coreModule, viewModelModule)
    }.koin
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
    // 「回前台补算一次离开多久」由 AutoLocker 用 sleep-aware 的墙钟差做（§7.4）：
    // iOS 的 systemUptime 睡眠期间不前进，只认它的话这一档永远不生效。
    // 「屏幕关闭即锁定」不订阅：iOS 拿不到独立的熄屏事件（见 supportsLockOnScreenOff），
    // 设置项在该端已隐藏，这里不留一条永远不会生效的死订阅。

    // 内置客户端预设（§8.2）：幂等，只碰公开数据，锁定态也能跑。
    appScope.launch { koin.get<ProfileSeeder>().seed() }

    // 自动刷新（§13.4 探测设置页）：等价 Android 端 TokenVaultApp 那一句。间隔靠应用内
    // 协程计时，所以 iOS 挂后台被冻结时不会偷偷发请求；回前台解锁后由 AppRoot 补一轮。
    koin.get<AutoRefresher>().start()
    // 并发档位：等价 Android 端 TokenVaultApp 那一句。不接在设置页 ViewModel 上，
    // 否则没进过那一页的进程会一直跑在硬默认档上。
    koin.get<HttpConcurrencyApplier>().start()
}
