package com.lc33.tokenvault

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lc33.tokenvault.platform.AutoLocker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

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

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) = autoLocker.onEnterBackground()

                override fun onStart(owner: LifecycleOwner) = autoLocker.onEnterForeground()
            },
        )
    }
}
