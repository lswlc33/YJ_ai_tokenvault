package com.lc33.tokenvault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import com.lc33.tokenvault.screens.lock.LockCallbacks
import com.lc33.tokenvault.ui.miuix.AppTheme

/**
 * 整棵树的根。
 *
 * 锁闸在这里插入：[LockGate] 按 `LockPhase` 决定画哪一棵树，只有 `Unlocked` 才进
 * [VaultShell]（§13.1）。`LockPhase` 来自 [LockViewModel]，它背后是 `VaultSession`。
 *
 * 配色模式来自 [AppearanceViewModel]。**这一项的权威存储是 boot** 而不是 `app_settings`
 * （红线 31）——锁屏页也要用它，而那时数据库里的设置还读不到。
 *
 * **阶段1 迁移**：删掉生物识别与恢复密钥相关的回调接线。
 */
@Composable
fun AppRoot() {
    val vm: LockViewModel = koinViewModel()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    val appearance: AppearanceViewModel = koinViewModel()
    val colorScheme by appearance.colorScheme.collectAsStateWithLifecycle()

    // LockCallbacks 必须 remember 一次：它是 @Immutable，但 Compose 比的是实例相等，
    // 每次重组新建一份就跳不过重组——而这一层的重组会带着整棵锁屏树一起重跑。
    val callbacks = remember(vm) {
        LockCallbacks(
            onPinDigit = vm::onPinDigit,
            onPinBackspace = vm::onPinBackspace,
            onOnboardingNext = vm::onOnboardingNext,
            onOnboardingBack = vm::onOnboardingBack,
            // 从备份恢复要走 SAF 选文件，那是 M9 的事。现在按不动比按了没反应好，
            // 所以这一项留空——BootCorruptScreen 会把它画成禁用态。
            onRestoreFromBackup = {},
            onWipeAndStartOver = vm::onWipeAndStartOver,
        )
    }

    AppTheme(mode = colorScheme) {
        LockGate(phase = phase, state = uiState, callbacks = callbacks) {
            VaultShell()
        }
    }
}
