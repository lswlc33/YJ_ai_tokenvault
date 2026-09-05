package com.lc33.tokenvault.ui.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.dashboard.BalanceBreakdownScreen
import com.lc33.tokenvault.screens.dashboard.DashboardScreen
import com.lc33.tokenvault.screens.lock.ChangePinScreen
import com.lc33.tokenvault.screens.lock.RecoveryKeyScreen
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
 * **M3 起管理那一支吃真数据**：管理页、供应商详情、供应商编辑、分组管理都接 ViewModel +
 * 仓库；分组筛选那个"当前选中"是纯 UI 状态，留在 `ManageViewModel` 里。
 *
 * 剩下的仍然来自 [SampleContent]：仪表盘六块卡、探测明细、余额明细、粘贴导入、
 * 客户端预设列表。它们各自等自己的里程碑（§16），而不是"接了一半假装接完"。
 */
@Composable
fun VaultNavHost(
    nav: NavHostController,
    modifier: Modifier = Modifier,
) {
    // M0.8 的设置项由这里兜着：切页保留、杀进程丢弃。等 SettingsRepository（M3 后半）。
    var settings by remember { mutableStateOf(SettingsDraft()) }
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
            val vm: ManageViewModel = hiltViewModel()
            val manage by vm.state.collectAsStateWithLifecycle()
            // 「全部」那一枚 chip 的文案在资源里，而 ViewModel 读不到资源（红线 19）
            val allLabel = stringResource(R.string.group_all)
            LaunchedEffect(allLabel) { vm.setAllGroupLabel(allLabel) }
            ManageScreen(
                state = manage,
                onSelectGroup = vm::onSelectGroup,
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
            val vm: ProviderDetailViewModel = hiltViewModel()
            val detail by vm.state.collectAsStateWithLifecycle()
            val revealed by vm.revealed.collectAsStateWithLifecycle()
            val clipboardLabel = stringResource(R.string.clipboard_label_api_key)
            // 这一家可能刚被删掉（详情页还在栈上）。detail 为 null 时什么都不画：
            // 画一个空壳会让用户以为数据丢了，而真相是这一行已经不存在
            detail?.let { state ->
                ProviderDetailScreen(
                    state = state,
                    revealedKeyId = revealed?.keyId,
                    revealedText = revealed?.text,
                    onBack = back,
                    onEdit = { nav.navigate(ProviderEditorRoute(route.id)) },
                    onAddKey = vm::onAddKey,
                    onRevealKey = vm::onRevealKey,
                    onCopyRevealed = { vm.onCopyRevealed(clipboardLabel) },
                    onCloseReveal = vm::onCloseKeySheet,
                    onSetDefaultKey = vm::onSetDefaultKey,
                    onDeleteKey = vm::onDeleteKey,
                )
            }
        }

        composable<AppearanceRoute> {
            val vm: AppearanceViewModel = hiltViewModel()
            val colorScheme by vm.colorScheme.collectAsStateWithLifecycle()
            AppearanceScreen(
                draft = settings,
                colorScheme = colorScheme,
                onChange = { settings = it },
                onColorSchemeChange = vm::onColorSchemeChange,
                onBack = back,
                onOpenSystemLocaleSettings = { openAppLocaleSettings(context) },
            )
        }
        composable<SecurityRoute> {
            val vm: SecurityViewModel = hiltViewModel()
            val biometric by vm.biometric.collectAsStateWithLifecycle()
            // 系统弹框的文案由系统画，所以要在这里取好传下去（ViewModel 读不到资源）。
            val enableTitle = stringResource(R.string.biometric_prompt_enable_title)
            val enableSubtitle = stringResource(R.string.biometric_prompt_enable_subtitle)
            val promptCancel = stringResource(R.string.biometric_prompt_cancel)
            val activity = context as? FragmentActivity
            SecurityScreen(
                draft = settings,
                biometric = biometric,
                onChange = { settings = it },
                onBiometricChange = { wanted ->
                    activity?.let {
                        vm.onBiometricChange(wanted, it, enableTitle, enableSubtitle, promptCancel)
                    }
                },
                onBack = back,
                onChangePin = { nav.navigate(ChangePinRoute) },
                onRecoveryKey = { nav.navigate(RecoveryKeyRoute) },
                onLockNow = vm::onLockNow,
            )
        }
        composable<ChangePinRoute> {
            val vm: SecurityViewModel = hiltViewModel()
            val state by vm.changePin.collectAsStateWithLifecycle()
            // 改完就退出去。用一次性事件而不是状态里的标志：标志会在重组时重放，
            // 于是这一页会在下一次进来时立刻自己弹回去。
            LaunchedEffect(vm) { vm.pinChanged.collect { back() } }
            ChangePinScreen(
                state = state,
                onDigit = vm::onPinDigit,
                onBackspace = vm::onPinBackspace,
                onBack = {
                    vm.onChangePinExit()
                    back()
                },
            )
        }
        composable<RecoveryKeyRoute> {
            val vm: SecurityViewModel = hiltViewModel()
            val state by vm.recoveryKey.collectAsStateWithLifecycle()
            val clipboardLabel = stringResource(R.string.clipboard_label_recovery_key)
            RecoveryKeyScreen(
                state = state,
                onRotate = vm::onRotateRecoveryKey,
                onCopy = { vm.onCopyRecoveryKey(clipboardLabel) },
                onSavedChange = vm::onRecoveryKeySavedChange,
                onBack = {
                    // 退出时擦掉明文并丢掉展示串：它是明文，不该在返回之后还留在状态里
                    vm.onRecoveryKeyExit()
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
        composable<ProviderEditorRoute> {
            val vm: ProviderEditorViewModel = hiltViewModel()
            val draft by vm.draft.collectAsStateWithLifecycle()
            val groups by vm.groups.collectAsStateWithLifecycle()
            val loaded by vm.loaded.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.saved.collect { back() } }
            // 下标 0 固定是「未分组」，与 ProviderDraftMapping 里那张表对齐
            val ungrouped = stringResource(R.string.editor_group_none)
            if (loaded) {
                ProviderEditorScreen(
                    draft = draft,
                    groupNames = listOf(ungrouped) + groups.map { it.name },
                    // 客户端预设还没有仓库（内置预设的 seed 在 M5），这一格暂时只有一项
                    profileNames = listOf(stringResource(R.string.editor_profile_default)),
                    onChange = vm::onChange,
                    onBack = back,
                    onSave = vm::onSave,
                )
            }
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
            val vm: ManageViewModel = hiltViewModel()
            val manage by vm.state.collectAsStateWithLifecycle()
            GroupsScreen(
                // 「全部」那一枚由页面自己过滤掉（它不入库，也就没有重命名这种操作）
                groups = manage.groups,
                onBack = back,
                onAdd = vm::onAddGroup,
                onRename = vm::onRenameGroup,
                onDelete = vm::onDeleteGroup,
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
