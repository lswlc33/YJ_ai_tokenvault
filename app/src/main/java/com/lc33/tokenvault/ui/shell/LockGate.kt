package com.lc33.tokenvault.ui.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.screens.lock.BootCorruptScreen
import com.lc33.tokenvault.screens.lock.LockCallbacks
import com.lc33.tokenvault.screens.lock.LockUiState
import com.lc33.tokenvault.screens.lock.OnboardingScreen
import com.lc33.tokenvault.screens.lock.SplashScreen
import com.lc33.tokenvault.screens.lock.UnlockScreen
import com.lc33.tokenvault.ui.common.SecureScreen

/**
 * 锁闸（§7.4、§13.1）。
 *
 * 它是纯 UI：`LockPhase` 从哪来、回调做什么，都由 `AppRoot` 那一层接
 * （`VaultSession` 的 `StateFlow<LockPhase>`）。这里只做三件事：
 *
 * 1. 按 [phase] 决定画哪一棵树。
 * 2. 除 [LockPhase.Unlocked] 之外**一律防截屏**（§7.5）——这四态都是能解开整个库的输入面，
 *    包括 `BootCorrupt`（那一页上有诊断信息）与 `Loading`（下一帧就是解锁页，
 *    切换那一瞬间不该出现一个没有保护的窗口）。
 * 3. 锁定时**整棵树被替换**，不是盖一层。业务界面连同它的组合状态一起销毁，
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
    if (phase !is LockPhase.Unlocked) {
        SecureScreen()
    }
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

/**
 * 跳系统的生物识别录入页。
 *
 * 不带 `EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED`：那个 extra 的取值来自
 * `androidx.biometric`，而这一层不该把生物识别库拉进来（它归 `platform/`）。
 * 不带 extra 时系统打开的是录入入口本身，对"还没录任何指纹"这一档已经够用。
 *
 * 取不到那个页面时退回应用详情页，**不静默失败**——按钮点了没反应比按钮不存在更糟。
 */
fun openBiometricEnrollment(context: Context) {
    val enroll = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
    val details = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    runCatching { context.startActivity(enroll) }.onFailure { context.startActivity(details) }
}
