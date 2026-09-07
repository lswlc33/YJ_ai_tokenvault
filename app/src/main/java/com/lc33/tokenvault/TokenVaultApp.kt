package com.lc33.tokenvault

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lc33.tokenvault.data.seed.ProfileSeeder
import com.lc33.tokenvault.di.Qualifiers
import com.lc33.tokenvault.di.coreModule
import com.lc33.tokenvault.di.platformModule
import com.lc33.tokenvault.di.viewModelModule
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.platform.AutoLocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named

/**
 * Koin 入口（阶段2 迁移 Hilt→Koin；阶段4 起模块本体住在 :shared，两端共用）。
 *
 * 这里挂**进程级**生命周期观察者来驱动自动锁定（§7.4）。用 `ProcessLifecycleOwner`
 * 而不是 Activity 的生命周期：后者在 Activity 之间跳转、转屏、弹系统权限框时都会走
 * `onStop`，那会把"切出应用"和"应用内部换页"混成一件事，于是每次转屏都锁一次。
 *
 * M8/M9 接入 WorkManager 时这里要实现 `Configuration.Provider` 并在 Manifest 里
 * 移除 `WorkManagerInitializer`；现在还没有 Worker，就不先摆一个不生效的配置。
 */
class TokenVaultApp : Application() {

    private val autoLocker: AutoLocker by inject()
    private val settings: SettingsRepository by inject()
    private val profileSeeder: ProfileSeeder by inject()
    private val appScope: CoroutineScope by inject(named(Qualifiers.APP_SCOPE))

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@TokenVaultApp)
            modules(platformModule, coreModule, viewModelModule)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) = autoLocker.onEnterBackground()

                override fun onStart(owner: LifecycleOwner) = autoLocker.onEnterForeground()
            },
        )
        // 自动锁定时限接在**这里**而不是安全设置页的 ViewModel 上：那个 ViewModel 只在用户
        // 站在那一页时活着，于是"设成立即、退出设置、切后台"会退回默认的 60 秒。
        // 这条订阅活得和进程一样长，而且只碰明文列，所以锁定态也能跑（§6.1 推论 2）。
        appScope.launch {
            settings.observeAutoLockTimeout().collect { autoLocker.timeout = it }
        }
        // 前台空闲锁定与屏幕关闭即锁定：同样是进程级订阅，因为 AutoLocker 是应用单例，
        // 而且这两项得在用户还没打开设置页时就生效（§7.4）。
        appScope.launch {
            settings.observeIdleLock().collect { autoLocker.idleLock = it }
        }
        appScope.launch {
            settings.observeLockOnScreenOff().collect { autoLocker.lockOnScreenOff = it }
        }
        // 内置客户端预设（§8.2）。幂等，只碰公开数据，锁定态也能跑；启动时种一次，
        // 既覆盖新装用户，也随版本刷新"没改过"的条目。
        appScope.launch { profileSeeder.seed() }
    }
}
