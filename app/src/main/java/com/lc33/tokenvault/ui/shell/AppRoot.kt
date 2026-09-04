package com.lc33.tokenvault.ui.shell

import androidx.compose.runtime.Composable
import com.lc33.tokenvault.ui.miuix.AppTheme
import com.lc33.tokenvault.ui.theme.AppColorSchemeMode

/**
 * 整棵树的根。
 *
 * M1 会在这里插入锁闸：`LockPhase` 为 Loading / Onboarding / Locked / BootCorrupt 时
 * 分别渲染对应页面，只有 Unlocked 才进 [VaultShell]（计划.md §13.1）。
 * 配色模式在 M2 接上 `SettingsRepository` 之前先跟随系统。
 */
@Composable
fun AppRoot() {
    AppTheme(mode = AppColorSchemeMode.System) {
        VaultShell()
    }
}
