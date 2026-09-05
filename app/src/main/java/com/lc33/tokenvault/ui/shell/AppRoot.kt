package com.lc33.tokenvault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lc33.tokenvault.R
import com.lc33.tokenvault.platform.BiometricCapability
import com.lc33.tokenvault.screens.lock.LockCallbacks
import com.lc33.tokenvault.ui.miuix.AppTheme
import com.lc33.tokenvault.ui.theme.AppColorSchemeMode

/**
 * 整棵树的根。
 *
 * 锁闸在这里插入：[LockGate] 按 `LockPhase` 决定画哪一棵树，只有 `Unlocked` 才进
 * [VaultShell]（§13.1）。`LockPhase` 来自 [LockViewModel]，它背后是 `VaultSession`。
 *
 * 配色模式在 M2 接上 `SettingsRepository` 之前先跟随系统。**这一项的权威存储是 boot**
 * 而不是 `app_settings`（红线 31）——锁屏页也要用它，而那时数据库里的设置还读不到。
 */
@Composable
fun AppRoot() {
    val vm: LockViewModel = hiltViewModel()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = context as? FragmentActivity

    // 系统弹窗的文案由系统画、不由我们画，所以必须在这里取好传下去——
    // ViewModel 读不到资源，而把 resId 传进 ViewModel 等于让它知道 R 类。
    val unlockTitle = stringResource(R.string.biometric_prompt_unlock_title)
    val unlockSubtitle = stringResource(R.string.biometric_prompt_unlock_subtitle)
    val enableTitle = stringResource(R.string.biometric_prompt_enable_title)
    val enableSubtitle = stringResource(R.string.biometric_prompt_enable_subtitle)
    val promptCancel = stringResource(R.string.biometric_prompt_cancel)
    val clipboardLabel = stringResource(R.string.clipboard_label_recovery_key)

    // 引导那一步要显示"这台设备支持什么"。问系统一次就够，所以放在 LaunchedEffect 里；
    // uiState.onboarding.biometric 在拿到结果之前是 null，界面画"正在检查"。
    LaunchedEffect(Unit) {
        vm.onBiometricAvailability(BiometricCapability(context).current())
    }

    // LockCallbacks 必须 remember 一次：它是 @Immutable，但 Compose 比的是实例相等，
    // 每次重组新建一份就跳不过重组——而这一层的重组会带着整棵锁屏树一起重跑。
    val callbacks = remember(vm, activity) {
        LockCallbacks(
            onPinDigit = vm::onPinDigit,
            onPinBackspace = vm::onPinBackspace,
            onBiometricUnlock = {
                activity?.let { vm.onBiometricUnlock(it, unlockTitle, unlockSubtitle, promptCancel) }
            },
            onEnterRecoveryMode = vm::onEnterRecoveryMode,
            onExitRecoveryMode = vm::onExitRecoveryMode,
            onRecoveryUnlock = vm::onRecoveryUnlock,
            onOnboardingNext = vm::onOnboardingNext,
            onOnboardingBack = vm::onOnboardingBack,
            onBiometricOptIn = { wanted ->
                activity?.let { vm.onBiometricOptIn(wanted, it, enableTitle, enableSubtitle, promptCancel) }
            },
            onOpenBiometricEnroll = { openBiometricEnrollment(context) },
            onCopyRecoveryKey = { vm.onCopyRecoveryKey(clipboardLabel) },
            onRecoveryKeySavedChange = vm::onRecoveryKeySavedChange,
            // 从备份恢复要走 SAF 选文件，那是 M9 的事。现在按不动比按了没反应好，
            // 所以这一项留空——BootCorruptScreen 会把它画成禁用态。
            onRestoreFromBackup = {},
            onWipeAndStartOver = vm::onWipeAndStartOver,
        )
    }

    AppTheme(mode = AppColorSchemeMode.System) {
        LockGate(phase = phase, state = uiState, callbacks = callbacks) {
            VaultShell()
        }
    }
}
