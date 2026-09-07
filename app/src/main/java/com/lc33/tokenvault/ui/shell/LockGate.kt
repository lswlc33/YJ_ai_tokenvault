package com.lc33.tokenvault.ui.shell

import androidx.compose.runtime.Composable
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.screens.lock.BootCorruptScreen
import com.lc33.tokenvault.screens.lock.LockCallbacks
import com.lc33.tokenvault.screens.lock.LockUiState
import com.lc33.tokenvault.screens.lock.OnboardingScreen
import com.lc33.tokenvault.screens.lock.SplashScreen
import com.lc33.tokenvault.screens.lock.UnlockScreen

/**
 * 锁闸（§7.4、§13.1）。
 *
 * 它是纯 UI：`LockPhase` 从哪来、回调做什么，都由 `AppRoot` 那一层接
 * （`VaultSession` 的 `StateFlow<LockPhase>`）。这里做两件事：
 *
 * 1. 按 [phase] 决定画哪一棵树。
 * 2. 锁定时**整棵树被替换**，不是盖一层。业务界面连同它的组合状态一起销毁，
 *    于是所有已展开的明文密钥、明文账号密码的 UI 状态自然没了（§7.4 的要求）——
 *    "盖一层"做不到这件事，被盖住的 composition 还活着。
 *    代价是导航栈也一起没了，所以一级页的 tab 索引要靠 `SavedStateHandle` 单独留住。
 */
@Composable
fun LockGate(
    phase: LockPhase,
    state: LockUiState,
    callbacks: LockCallbacks,
    content: @Composable () -> Unit,
) {
    when (phase) {
        LockPhase.Loading -> SplashScreen()

        LockPhase.Onboarding -> OnboardingScreen(
            state = state.onboarding,
            callbacks = callbacks,
        )

        is LockPhase.Locked -> UnlockScreen(
            locked = phase,
            state = state.unlock,
            callbacks = callbacks,
        )

        LockPhase.Unlocked -> content()

        is LockPhase.BootCorrupt -> BootCorruptScreen(
            reason = phase.reason,
            onRestoreFromBackup = callbacks.onRestoreFromBackup,
            onWipeAndStartOver = callbacks.onWipeAndStartOver,
        )
    }
}
