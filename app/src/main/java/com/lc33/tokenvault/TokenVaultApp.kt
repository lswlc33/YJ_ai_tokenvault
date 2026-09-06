package com.lc33.tokenvault

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lc33.tokenvault.data.seed.ProfileSeeder
import com.lc33.tokenvault.di.AppScope
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.platform.AutoLocker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Hilt 入口。
 *
 * 这里挂**进程级**生命周期观察者来驱动自动锁定（§7.4）。用 `ProcessLifecycleOwner`
 * 而不是 Activity 的生命周期：后者在 Activity 之间跳转、转屏、弹系统权限框时都会走
 * `onStop`，那会把"切出应用"和"应用内部换页"混成一件事，于是每次转屏都锁一次。
 *
 * M8/M9 接入 WorkManager 时这里要实现 `Configuration.Provider` 并在 Manifest 里
 * 移除 `WorkManagerInitializer`，否则 `HiltWorker` 拿不到工厂；现在还没有 Worker，
 * 就不先摆一个不生效的配置。
 */
@HiltAndroidApp
class TokenVaultApp : Application() {

    @Inject
    lateinit var autoLocker: AutoLocker

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var profileSeeder: ProfileSeeder

    @Inject
    @AppScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
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
        // 内置客户端预设（§8.2）。幂等，只碰公开数据，锁定态也能跑；启动时种一次，
        // 既覆盖新装用户，也随版本刷新"没改过"的条目。
        appScope.launch { profileSeeder.seed() }
    }
}
