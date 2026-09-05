package com.lc33.tokenvault.ui.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.dashboard.BalanceBreakdownScreen
import com.lc33.tokenvault.screens.dashboard.DashboardScreen
import com.lc33.tokenvault.screens.lock.ChangePinScreen
import com.lc33.tokenvault.screens.lock.ChangePinStep
import com.lc33.tokenvault.screens.lock.ChangePinUiState
import com.lc33.tokenvault.screens.lock.RecoveryKeyScreen
import com.lc33.tokenvault.screens.lock.RecoveryKeyUiState
import com.lc33.tokenvault.screens.manage.GroupsScreen
import com.lc33.tokenvault.screens.manage.ImportScreen
import com.lc33.tokenvault.screens.manage.ManageScreen
import com.lc33.tokenvault.screens.manage.ProviderDetailScreen
import com.lc33.tokenvault.screens.manage.ProviderEditorScreen
import com.lc33.tokenvault.screens.model.SettingsDraft
import com.lc33.tokenvault.screens.probe.ProbeRunScreen
import com.lc33.tokenvault.screens.sample.SampleContent
import com.lc33.tokenvault.screens.settings.AboutScreen
import com.lc33.tokenvault.screens.settings.AppearanceScreen
import com.lc33.tokenvault.screens.settings.DataScreen
import com.lc33.tokenvault.screens.settings.ProbeSettingsScreen
import com.lc33.tokenvault.screens.settings.ProfileListScreen
import com.lc33.tokenvault.screens.settings.SecurityScreen
import com.lc33.tokenvault.screens.settings.SettingsScreen
import com.lc33.tokenvault.screens.settings.SyncScreen
import com.lc33.tokenvault.screens.settings.UpdateScreen

/**
 * 导航图。
 *
 * M0.8 阶段数据来自 [SampleContent]，而"当前分段"这类**纯 UI 状态**先由 Shell 记着；
 * M3 接真数据时这两样都换成 ViewModel + `SavedStateHandle`，页面签名不用动
 * （页面只接 UiState 与回调，本来就不知道数据从哪来）。
 */
@Composable
fun VaultNavHost(
    nav: NavHostController,
    modifier: Modifier = Modifier,
) {
    // 分组筛选跨导航保留：进详情再返回，应该还停在刚才那个分组。
    var selectedGroupId by remember { mutableStateOf<Long?>(null) }
    // M0.8 的设置项由这里兜着：切页保留、杀进程丢弃。M1 / M2 换成 SettingsRepository。
    var settings by remember { mutableStateOf(SettingsDraft()) }
    // 改 PIN 与恢复密钥两页的界面状态。这里只记"输了几位"和"走到第几步"——
    // 明文 PIN 不进任何界面状态（红线 1）。真正的校验与重新包裹在 M1 的 VaultSession 里，
    // 那时这两块 remember 与上面的 settings 一起换成 ViewModel。
    var changePin by remember { mutableStateOf(ChangePinUiState()) }
    var recoveryKey by remember { mutableStateOf(RecoveryKeyUiState()) }
    val context = LocalContext.current

    fun openManage() {
        nav.navigate(ManageRoute) {
            popUpTo<DashboardRoute> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(
        navController = nav,
        startDestination = DashboardRoute,
        modifier = modifier,
    ) {
        composable<DashboardRoute> {
            DashboardScreen(
                state = SampleContent.dashboard(),
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
                onOpenManage = ::openManage,
                onOpenProbeRun = { nav.navigate(ProbeRunRoute) },
                onOpenSync = { nav.navigate(SyncRoute) },
                onOpenBalanceBreakdown = { nav.navigate(BalanceBreakdownRoute) },
                onStartProbe = {},
                onCancelProbe = {},
                onRefreshBalance = {},
            )
        }

        composable<ManageRoute> {
            ManageScreen(
                state = SampleContent.manage().copy(selectedGroupId = selectedGroupId),
                onSelectGroup = { id -> selectedGroupId = id },
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
                onOpenGroups = { nav.navigate(GroupsRoute) },
                onNewProvider = { nav.navigate(ProviderEditorRoute()) },
                onImport = { nav.navigate(ImportRoute) },
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onOpenAppearance = { nav.navigate(AppearanceRoute) },
                onOpenSecurity = { nav.navigate(SecurityRoute) },
                onOpenProbeSettings = { nav.navigate(ProbeSettingsRoute) },
                onOpenProfiles = { nav.navigate(ProfileListRoute) },
                onOpenData = { nav.navigate(DataRoute) },
                onOpenSync = { nav.navigate(SyncRoute) },
                onOpenAbout = { nav.navigate(AboutRoute) },
                onOpenUpdate = { nav.navigate(UpdateRoute) },
            )
        }

        composable<AboutRoute> { AboutScreen(onBack = { nav.popBackStack() }) }

        val back: () -> Unit = { nav.popBackStack() }

        composable<ProviderDetailRoute> { entry ->
            val route = entry.toRoute<ProviderDetailRoute>()
            ProviderDetailScreen(
                state = SampleContent.detail(route.id),
                onBack = back,
                onEdit = { nav.navigate(ProviderEditorRoute(route.id)) },
            )
        }

        composable<AppearanceRoute> {
            AppearanceScreen(
                draft = settings,
                onChange = { settings = it },
                onBack = back,
                onOpenSystemLocaleSettings = { openAppLocaleSettings(context) },
            )
        }
        composable<SecurityRoute> {
            SecurityScreen(
                draft = settings,
                onChange = { settings = it },
                onBack = back,
                onChangePin = { nav.navigate(ChangePinRoute) },
                onRecoveryKey = { nav.navigate(RecoveryKeyRoute) },
            )
        }
        composable<ChangePinRoute> {
            ChangePinScreen(
                state = changePin,
                onDigit = {
                    val next = changePin.pinLength + 1
                    if (next < changePin.pinSlots) {
                        changePin = changePin.copy(pinLength = next, error = null)
                    } else {
                        when (changePin.step) {
                            ChangePinStep.Current ->
                                changePin = changePin.copy(step = ChangePinStep.New, pinLength = 0)
                            ChangePinStep.New ->
                                changePin = changePin.copy(step = ChangePinStep.Confirm, pinLength = 0)
                            // 校验旧 PIN、比对两次新 PIN、重新包裹 DEK 都在 M1 的 VaultSession 里，
                            // 所以这里只是走完流程退出去——不弹"已修改"，那会是一句假话。
                            ChangePinStep.Confirm -> {
                                changePin = ChangePinUiState()
                                back()
                            }
                        }
                    }
                },
                onBackspace = {
                    changePin = changePin.copy(pinLength = (changePin.pinLength - 1).coerceAtLeast(0))
                },
                onBack = {
                    changePin = ChangePinUiState()
                    back()
                },
            )
        }
        composable<RecoveryKeyRoute> {
            RecoveryKeyScreen(
                state = recoveryKey,
                onRotate = {
                    recoveryKey = recoveryKey.copy(
                        hasKey = true,
                        generated = SampleContent.recoveryKeyDisplay(),
                        saved = false,
                    )
                },
                onCopy = {},
                onSavedChange = { recoveryKey = recoveryKey.copy(saved = it) },
                onBack = {
                    // 退出时丢掉展示串：它是明文，不该在返回之后还留在状态里
                    recoveryKey = RecoveryKeyUiState(hasKey = recoveryKey.hasKey)
                    back()
                },
            )
        }
        composable<ProbeSettingsRoute> {
            ProbeSettingsScreen(
                draft = settings,
                onChange = { settings = it },
                onBack = back,
                onOpenManage = ::openManage,
                onEditThresholds = {},
                onEditKeywords = {},
                onEditProxy = {},
            )
        }
        composable<ProfileListRoute> {
            ProfileListScreen(
                profiles = SampleContent.profiles(),
                onBack = back,
                onOpenProfile = {},
                onNewFromCurl = {},
            )
        }
        composable<DataRoute> {
            DataScreen(
                onBack = back,
                onSyncCatalog = {},
                onOpenGroups = { nav.navigate(GroupsRoute) },
                onOpenLog = {},
                onClearProbeResults = {},
                onClearLog = {},
            )
        }
        composable<SyncRoute> {
            SyncScreen(
                draft = settings,
                backup = SampleContent.dashboard().backup,
                onChange = { settings = it },
                onBack = back,
                onExport = {},
                onImport = {},
                onBackupPassphrase = {},
                onWebDav = {},
            )
        }
        composable<UpdateRoute> {
            UpdateScreen(
                draft = settings,
                onChange = { settings = it },
                onBack = back,
                onCheckNow = {},
            )
        }

        // 剩下这几个还是 M0.8 立起来的空壳，内容各归各的里程碑（见 §16）
        composable<ProviderEditorRoute> { entry ->
            val route = entry.toRoute<ProviderEditorRoute>()
            ProviderEditorScreen(
                draft = SampleContent.draft(route.id),
                groupNames = SampleContent.manage().groups.map { it.name },
                profileNames = SampleContent.profiles().map { it.name },
                onChange = {},
                onBack = back,
                onSave = back,
            )
        }
        composable<ImportRoute> {
            ImportScreen(
                previews = SampleContent.previews(),
                onBack = back,
                onFillFromClipboard = {},
                onParse = {},
                onToggle = {},
                onConfirm = back,
            )
        }
        composable<GroupsRoute> {
            GroupsScreen(
                groups = SampleContent.manage().groups,
                onBack = back,
                onAdd = {},
                onRename = {},
                onDelete = {},
            )
        }
        composable<ProbeRunRoute> {
            ProbeRunScreen(
                lastRun = SampleContent.dashboard().lastRun,
                failed = SampleContent.probeFailed(),
                skipped = SampleContent.probeSkipped(),
                succeeded = SampleContent.probeSucceeded(),
                onBack = back,
                onRetryFailed = {},
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
            )
        }
        composable<BalanceBreakdownRoute> {
            val providers = SampleContent.manage().providers
            BalanceBreakdownScreen(
                providers = providers.filter { it.id != 1L },
                failedProviders = providers.filter { it.id == 1L },
                onBack = back,
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
            )
        }
    }
}

/**
 * 跳系统的「应用语言」页。
 *
 * 应用内不再做一份语言选择器：Android 13+ 有系统级的 per-app locale，自己再做一个
 * 就有两个权威（红线 31 的精神）。取不到那个页面时退回应用详情页，不静默失败。
 */
private fun openAppLocaleSettings(context: Context) {
    val locale = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
    val details = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    runCatching { context.startActivity(locale) }.onFailure { context.startActivity(details) }
}
