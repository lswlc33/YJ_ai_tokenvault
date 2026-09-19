package com.lc33.tokenvault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.lc33.tokenvault.engine.AutoRefresher
import com.lc33.tokenvault.platform.BiometricPromptText
import com.lc33.tokenvault.screens.lock.LockCallbacks
import com.lc33.tokenvault.ui.miuix.AppTheme
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.biometric_prompt_cancel
import tokenvault.shared.generated.resources.biometric_prompt_unlock_subtitle
import tokenvault.shared.generated.resources.biometric_prompt_unlock_title

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
    val biometricAvailable by vm.biometricAvailable.collectAsStateWithLifecycle()

    val appearance: AppearanceViewModel = koinViewModel()
    val logMaintenance: com.lc33.tokenvault.engine.LogMaintenance = koinInject()
    val autoRefresher: AutoRefresher = koinInject()

    LaunchedEffect(logMaintenance) {
        runCatching { logMaintenance.run() }
    }
    val colorScheme by appearance.colorScheme.collectAsStateWithLifecycle()

    // LockCallbacks 必须 remember 一次：它是 @Immutable，但 Compose 比的是实例相等，
    // 每次重组新建一份就跳不过重组——而这一层的重组会带着整棵锁屏树一起重跑。
    // 验证框文案跟语言走，所以把它们一起放进 remember 的键里。
    val biometricTitle = stringResource(Res.string.biometric_prompt_unlock_title)
    val biometricSubtitle = stringResource(Res.string.biometric_prompt_unlock_subtitle)
    val biometricCancel = stringResource(Res.string.biometric_prompt_cancel)
    val callbacks = remember(vm, biometricTitle, biometricSubtitle, biometricCancel) {
        LockCallbacks(
            onPinDigit = vm::onPinDigit,
            onPinBackspace = vm::onPinBackspace,
            onBiometricUnlock = {
                vm.unlockWithBiometric(
                    BiometricPromptText(
                        title = biometricTitle,
                        subtitle = biometricSubtitle,
                        cancel = biometricCancel,
                    ),
                )
            },
            onOnboardingNext = vm::onOnboardingNext,
            onOnboardingBack = vm::onOnboardingBack,
            // 从备份恢复要走 SAF 选文件，那是 M9 的事：现在连回调都还没有，所以
            // `restoreFromBackupEnabled` 留在默认值 false —— BootCorruptScreen 会把它画成
            // 禁用态并写清"这一版还没实现"，而不是让人按了没反应。
            onRestoreFromBackup = {},
            onWipeAndStartOver = vm::onWipeAndStartOver,
        )
    }

    AppTheme(mode = colorScheme) {
        // 锁屏前所在的一级页。这一份状态**必须在 LockGate 之外**：锁定时整棵业务树会被
        // 换掉（§7.4，明文 UI 状态要随之销毁），[VaultShell] 里的 pager 连同它的
        // rememberPagerState 一起没了，不记在这里的话解锁后永远回到「总览」。
        // AppRoot 自己从不退出组合，所以这一格既活得过锁屏、也活得过转屏；给它显式 key
        // 是因为 rememberSaveable 的自动 key 按组合位置编号，锁屏前后树形一变就会错领。
        var topLevelPage by rememberSaveable(key = "top-level-page") { mutableIntStateOf(0) }
        LockGate(
            phase = phase,
            state = uiState,
            callbacks = callbacks,
            biometricAvailable = biometricAvailable,
        ) {
            // 解锁完成就是用户意义上的"打开了应用"。自动刷新要在这里补一轮：应用是先起来、
            // 后解锁的（§6.1），解锁前发请求只会得到一轮全失败。这一棵子树只在 Unlocked
            // 时组合，所以每一次"锁 → 解"都会重新走这一句；间隔那一档由 AutoRefresher 自己管。
            LaunchedEffect(autoRefresher) { autoRefresher.onUnlocked() }
            VaultShell(
                initialTabPage = topLevelPage,
                onTabPageChange = { topLevelPage = it },
            )
        }
    }
}
