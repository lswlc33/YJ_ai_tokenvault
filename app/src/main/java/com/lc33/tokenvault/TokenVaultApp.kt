package com.lc33.tokenvault

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt 入口。
 *
 * M8/M9 接入 WorkManager 时这里要实现 `Configuration.Provider` 并在 Manifest 里
 * 移除 `WorkManagerInitializer`，否则 `HiltWorker` 拿不到工厂；现在还没有 Worker，
 * 就不先摆一个不生效的配置。
 */
@HiltAndroidApp
class TokenVaultApp : Application()
