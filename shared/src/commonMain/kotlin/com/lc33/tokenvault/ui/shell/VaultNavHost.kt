package com.lc33.tokenvault.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.model.PredictiveBackExitDirection
import com.lc33.tokenvault.endpoint.EndpointError
import com.lc33.tokenvault.domain.model.PredictiveBackStyle
import com.lc33.tokenvault.engine.RestoreMode
import com.lc33.tokenvault.platform.openAppLocaleSettings
import com.lc33.tokenvault.platform.openExternalUrl
import com.lc33.tokenvault.platform.rememberBackupFilePicker
import com.lc33.tokenvault.screens.dashboard.BalanceBreakdownScreen
import com.lc33.tokenvault.screens.dashboard.DashboardScreen
import com.lc33.tokenvault.screens.lock.ChangePinScreen
import com.lc33.tokenvault.screens.manage.GroupsScreen
import com.lc33.tokenvault.screens.manage.ImportScreen
import com.lc33.tokenvault.screens.manage.KeyDetailScreen
import com.lc33.tokenvault.screens.manage.KeyEditorScreen
import com.lc33.tokenvault.screens.manage.ManageScreen
import com.lc33.tokenvault.screens.manage.ProviderDetailScreen
import com.lc33.tokenvault.screens.manage.ProviderEditorScreen
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.screens.probe.ProbeRunScreen
import com.lc33.tokenvault.screens.settings.AboutScreen
import com.lc33.tokenvault.screens.settings.AppearanceScreen
import com.lc33.tokenvault.screens.settings.BalanceThresholdsScreen
import com.lc33.tokenvault.screens.settings.ClientKeywordsScreen
import com.lc33.tokenvault.screens.settings.DataScreen
import com.lc33.tokenvault.screens.settings.LicensesScreen
import com.lc33.tokenvault.screens.settings.LogScreen
import com.lc33.tokenvault.screens.settings.ProbeSettingsScreen
import com.lc33.tokenvault.screens.settings.ProfileEditorScreen
import com.lc33.tokenvault.screens.settings.ProfileListScreen
import com.lc33.tokenvault.screens.settings.ProxyScreen
import com.lc33.tokenvault.screens.settings.SecurityScreen
import com.lc33.tokenvault.screens.settings.SettingsScreen
import com.lc33.tokenvault.screens.settings.SyncScreen
import com.lc33.tokenvault.screens.settings.UpdateScreen
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppSecretTextField
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.LocalAppSnackbar
import com.lc33.tokenvault.ui.miuix.navigation.VaultNavDisplay
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberSecretTextFieldState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import tokenvault.shared.generated.resources.clipboard_label_account
import tokenvault.shared.generated.resources.clipboard_label_api_key
import tokenvault.shared.generated.resources.editor_group_none
import tokenvault.shared.generated.resources.editor_url_err_empty
import tokenvault.shared.generated.resources.editor_url_err_host
import tokenvault.shared.generated.resources.editor_url_err_query
import tokenvault.shared.generated.resources.editor_url_err_scheme
import tokenvault.shared.generated.resources.editor_profile_default
import tokenvault.shared.generated.resources.group_all
import tokenvault.shared.generated.resources.groups_add_failed
import tokenvault.shared.generated.resources.profile_name_default
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.sync_confirm
import tokenvault.shared.generated.resources.sync_insecure_http_warning
import tokenvault.shared.generated.resources.sync_mode_add_only
import tokenvault.shared.generated.resources.sync_mode_merge
import tokenvault.shared.generated.resources.sync_mode_overwrite
import tokenvault.shared.generated.resources.sync_passphrase_hint
import tokenvault.shared.generated.resources.sync_passphrase_prompt
import tokenvault.shared.generated.resources.sync_remote_count
import tokenvault.shared.generated.resources.sync_restore_mode
import tokenvault.shared.generated.resources.sync_result_exported
import tokenvault.shared.generated.resources.sync_result_failed
import tokenvault.shared.generated.resources.sync_result_restored
import tokenvault.shared.generated.resources.sync_result_webdav_configured
import tokenvault.shared.generated.resources.sync_result_webdav_uploaded
import tokenvault.shared.generated.resources.sync_webdav_credentials_keep
import tokenvault.shared.generated.resources.sync_webdav_credentials_required
import tokenvault.shared.generated.resources.sync_webdav_directory_label
import tokenvault.shared.generated.resources.sync_webdav_insecure_required
import tokenvault.shared.generated.resources.sync_webdav_password_label
import tokenvault.shared.generated.resources.sync_webdav_settings
import tokenvault.shared.generated.resources.sync_webdav_title
import tokenvault.shared.generated.resources.sync_webdav_url_invalid
import tokenvault.shared.generated.resources.sync_webdav_url_label
import tokenvault.shared.generated.resources.sync_webdav_username_label

/**
 * Navigation 3 导航图。
 *
 * 路由 Key 是 [VaultRoute]，页面内容由 [VaultNavDisplay] 统一挂上 per-entry
 * SaveableState / ViewModelStore；带 id 的页面把 id 作为 Koin 参数传入，
 * 进程恢复时由 back stack 还原同一个 Key。
 */
@Composable
fun VaultNavHost(
    backStack: MutableList<VaultRoute>,
    revision: Int,
    onBackStackChanged: () -> Unit,
    style: PredictiveBackStyle,
    exitDirection: PredictiveBackExitDirection,
    modifier: Modifier = Modifier,
) {
    val routeSnapshot = remember(revision, backStack.size) { backStack.toList() }

    val navigate: (VaultRoute) -> Unit = { route ->
        backStack.add(route)
        onBackStackChanged()
    }

    // 统一处理二级页返回：根页面不消费返回事件，交给系统退出应用。
    val back: () -> Unit = {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            onBackStackChanged()
        }
    }

    @Composable
    fun RouteContent(route: VaultRoute) {
        when (route) {
            is DashboardRoute -> {
            val vm: DashboardViewModel = koinViewModel()
            val dashboard by vm.state.collectAsStateWithLifecycle()
            DashboardScreen(
                state = dashboard,
                onOpenManage = { navigate(ManageRoute) },
                onStartProbe = vm::startProbe,
                onCancelProbe = vm::cancelProbe,
                onRefreshBalance = vm::refreshBalance,
                onRefreshStatus = vm::refreshStatus,
            )
        }

            is ManageRoute -> {
            val vm: ManageViewModel = koinViewModel()
            val manage by vm.state.collectAsStateWithLifecycle()
            // 「全部」那一枚 chip 的文案在资源里，而 ViewModel 读不到资源（红线 19）
            val allLabel = stringResource(Res.string.group_all)
            LaunchedEffect(allLabel) { vm.setAllGroupLabel(allLabel) }
            ManageScreen(
                state = manage,
                onSelectGroup = vm::onSelectGroup,
                onOpenProvider = { id -> navigate(ProviderDetailRoute(id)) },
                onOpenGroups = { navigate(GroupsRoute) },
                onNewProvider = { navigate(ProviderEditorRoute()) },
                onRefreshStatus = vm::refreshStatus,
                onQueryChange = vm::onQueryChange,
                onEnterSelection = vm::enterSelection,
                onToggleSelect = vm::toggleSelect,
                onSelectAll = vm::selectAll,
                onClearSelection = vm::clearSelection,
                onBatchDelete = vm::batchDelete,
                onBatchSetGroup = vm::batchSetGroup,
            )
        }

            is SettingsRoute -> {
            SettingsScreen(
                onOpenAppearance = { navigate(AppearanceRoute) },
                onOpenSecurity = { navigate(SecurityRoute) },
                onOpenProbeSettings = { navigate(ProbeSettingsRoute) },
                onOpenProfiles = { navigate(ProfileListRoute) },
                onOpenData = { navigate(DataRoute) },
                onOpenSync = { navigate(SyncRoute) },
                onOpenAbout = { navigate(AboutRoute) },
                onOpenUpdate = { navigate(UpdateRoute) },
            )
        }

            is AboutRoute -> {
            AboutScreen(
                onBack = { back() },
                onOpenLicenses = { navigate(LicensesRoute) },
            )
        }


            is ProviderDetailRoute -> {
                val route = route
            val vm: ProviderDetailViewModel = koinViewModel(parameters = { parametersOf(route.id) })
            val detail by vm.state.collectAsStateWithLifecycle()
            val revealedAccount by vm.revealedAccount.collectAsStateWithLifecycle()
            val accountClipboardLabel = stringResource(Res.string.clipboard_label_account)
            // 这一家可能刚被删掉（详情页还在栈上）。detail 为 null 时什么都不画：
            // 画一个空壳会让用户以为数据丢了，而真相是这一行已经不存在
            detail?.let { state ->
                ProviderDetailScreen(
                    state = state,
                    revealedAccount = revealedAccount,
                    onBack = back,
                    onEdit = { navigate(ProviderEditorRoute(route.id)) },
                    onCurlImport = { navigate(ImportRoute) },
                    onManualAddKey = { navigate(KeyEditorRoute(route.id, 0L)) },
                    onOpenKey = { keyId -> navigate(KeyDetailRoute(route.id, keyId)) },
                    onRefreshBalance = vm::refreshBalance,
                    onProbeKey = vm::probeKey,
                    onRefreshKeyModels = { keyId -> vm.refreshModels(keyId) },
                    onAddModel = vm::onAddModel,
                    onUpdateModel = vm::onUpdateModel,
                    onDeleteModel = vm::onDeleteModel,
                    onProbeModel = vm::onProbeModel,
                    onRevealAccount = vm::onRevealAccount,
                    onCopyRevealedAccount = { vm.onCopyRevealedAccount(accountClipboardLabel) },
                    onCloseAccountReveal = vm::onCloseAccountSheet,
                    onSetAccountLoginMethods = vm::onSetAccountLoginMethods,
                )
            }
        }

            is KeyDetailRoute -> {
                val vm: KeyDetailViewModel = koinViewModel(
                    parameters = { parametersOf(route.providerId, route.keyId) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                val revealed by vm.revealed.collectAsStateWithLifecycle()
                LaunchedEffect(vm) { vm.deleted.collect { back() } }
                val keyClipboardLabel = stringResource(Res.string.clipboard_label_api_key)
                state?.let { keyState ->
                    KeyDetailScreen(
                        state = keyState,
                        revealedText = revealed?.text,
                        onBack = back,
                        onEdit = { navigate(KeyEditorRoute(route.providerId, route.keyId)) },
                        onReveal = vm::reveal,
                        onCopyRevealed = { vm.copyRevealed(keyClipboardLabel) },
                        onCloseReveal = vm::closeReveal,
                        onProbe = vm::probeKey,
                        onRefreshModels = vm::refreshModels,
                        onMoveUp = vm::moveUp,
                        onMoveDown = vm::moveDown,
                        onDelete = vm::delete,
                    )
                }
            }

            is KeyEditorRoute -> {
                val vm: KeyEditorViewModel = koinViewModel(parameters = { parametersOf(route.keyId) })
                val draft by vm.draft.collectAsStateWithLifecycle()
                val profiles by vm.profiles.collectAsStateWithLifecycle()
                val loaded by vm.loaded.collectAsStateWithLifecycle()
                val urlError by vm.urlError.collectAsStateWithLifecycle()
                LaunchedEffect(vm) { vm.saved.collect { back() } }
                val keyProfileDefaultLabel = stringResource(Res.string.profile_name_default)
                val keyEditorProfileDefault = stringResource(Res.string.editor_profile_default)
                val baseUrlError = when (urlError) {
                    null -> null
                    EndpointError.Empty -> stringResource(Res.string.editor_url_err_empty)
                    EndpointError.UnsupportedScheme -> stringResource(Res.string.editor_url_err_scheme)
                    EndpointError.HasQueryOrFragment -> stringResource(Res.string.editor_url_err_query)
                    EndpointError.NoHost -> stringResource(Res.string.editor_url_err_host)
                }
                if (loaded) {
                    KeyEditorScreen(
                        draft = draft,
                        profileNames = listOf(keyEditorProfileDefault) + profiles.map { profile ->
                            if (profile.builtinKey == "default") keyProfileDefaultLabel else profile.name
                        },
                        baseUrlError = baseUrlError,
                        onChange = vm::onChange,
                        onBaseUrlChange = vm::clearUrlError,
                        onBack = back,
                        onSave = vm::save,
                    )
                }
            }
            is AppearanceRoute -> {
            val vm: AppearanceViewModel = koinViewModel()
            val colorScheme by vm.colorScheme.collectAsStateWithLifecycle()
            val blurNavBar by vm.blurNavBar.collectAsStateWithLifecycle()
            AppearanceScreen(
                colorScheme = colorScheme,
                blurNavBar = blurNavBar,
                predictiveBackStyle = style,
                predictiveBackExitDirection = exitDirection,
                onColorSchemeChange = vm::onColorSchemeChange,
                onBlurNavBarChange = vm::onBlurNavBarChange,
                onPredictiveBackStyleChange = vm::onPredictiveBackStyleChange,
                onPredictiveBackExitDirectionChange = vm::onPredictiveBackExitDirectionChange,
                onBack = back,
                onOpenSystemLocaleSettings = { openAppLocaleSettings() },
            )
        }
            is SecurityRoute -> {
            val vm: SecurityViewModel = koinViewModel()
            val autoLockIndex by vm.autoLockIndex.collectAsStateWithLifecycle()
            val idleLock by vm.idleLock.collectAsStateWithLifecycle()
            val lockOnScreenOff by vm.lockOnScreenOff.collectAsStateWithLifecycle()
            val clipboardClearIndex by vm.clipboardClearIndex.collectAsStateWithLifecycle()
            SecurityScreen(
                autoLockIndex = autoLockIndex,
                idleLock = idleLock,
                lockOnScreenOff = lockOnScreenOff,
                clipboardClearIndex = clipboardClearIndex,
                onAutoLockIndexChange = vm::onAutoLockIndexChange,
                onIdleLockChange = vm::onIdleLockChange,
                onLockOnScreenOffChange = vm::onLockOnScreenOffChange,
                onClipboardClearIndexChange = vm::onClipboardClearIndexChange,
                onBack = back,
                onChangePin = { navigate(ChangePinRoute) },
                onLockNow = vm::onLockNow,
            )
        }
            is ChangePinRoute -> {
            val vm: SecurityViewModel = koinViewModel()
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
            is ProbeSettingsRoute -> {
            val vm: ProbeSettingsViewModel = koinViewModel()
            val sniffClientProfile by vm.sniffClientProfile.collectAsStateWithLifecycle()
            val defaultProbeReachability by vm.defaultProbeReachability.collectAsStateWithLifecycle()
            val defaultProbeKeys by vm.defaultProbeKeys.collectAsStateWithLifecycle()
            val defaultProbeBalance by vm.defaultProbeBalance.collectAsStateWithLifecycle()
            val defaultProbeModels by vm.defaultProbeModels.collectAsStateWithLifecycle()
            val defaultProbeModelReachability by vm.defaultProbeModelReachability.collectAsStateWithLifecycle()
            ProbeSettingsScreen(
                sniffClientProfile = sniffClientProfile,
                defaultProbeReachability = defaultProbeReachability,
                defaultProbeKeys = defaultProbeKeys,
                defaultProbeBalance = defaultProbeBalance,
                defaultProbeModels = defaultProbeModels,
                defaultProbeModelReachability = defaultProbeModelReachability,
                onSniffClientProfileChange = vm::onSniffClientProfileChange,
                onDefaultProbeReachabilityChange = vm::onDefaultProbeReachabilityChange,
                onDefaultProbeKeysChange = vm::onDefaultProbeKeysChange,
                onDefaultProbeBalanceChange = vm::onDefaultProbeBalanceChange,
                onDefaultProbeModelsChange = vm::onDefaultProbeModelsChange,
                onDefaultProbeModelReachabilityChange = vm::onDefaultProbeModelReachabilityChange,
                onBack = back,
                onOpenManage = { navigate(ManageRoute) },
                onEditThresholds = { navigate(BalanceThresholdsRoute) },
                onEditKeywords = { navigate(ClientKeywordsRoute) },
                onEditProxy = { navigate(ProxyRoute) },
            )
        }
            is BalanceThresholdsRoute -> {
            val vm: BalanceThresholdsViewModel = koinViewModel()
            BalanceThresholdsScreen(
                viewModel = vm,
                onBack = back,
            )
        }
            is ClientKeywordsRoute -> {
            val vm: ClientKeywordsViewModel = koinViewModel()
            ClientKeywordsScreen(
                viewModel = vm,
                onBack = back,
            )
        }
            is ProxyRoute -> {
            val vm: ProxyViewModel = koinViewModel()
            ProxyScreen(
                viewModel = vm,
                onBack = back,
            )
        }
            is ProfileListRoute -> {
            val vm: ProfileListViewModel = koinViewModel()
            val profiles by vm.profiles.collectAsStateWithLifecycle()
            val defaultName = stringResource(Res.string.profile_name_default)
            ProfileListScreen(
                profiles = profiles,
                defaultName = defaultName,
                onBack = back,
                onOpenProfile = { id -> navigate(ProfileEditorRoute(id)) },
                onNewFromCurl = { navigate(ProfileEditorRoute()) },
            )
        }
            is ProfileEditorRoute -> {
                val route = route
            val vm: ProfileEditorViewModel = koinViewModel(parameters = { parametersOf(route.id) })
            val loaded by vm.loaded.collectAsStateWithLifecycle()
            val profile by vm.profile.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.saved.collect { back() } }
            LaunchedEffect(vm) { vm.deleted.collect { back() } }
            if (loaded) {
                ProfileEditorScreen(
                    initial = profile,
                    onBack = back,
                    onSave = vm::save,
                    onDelete = vm::delete,
                )
            }
        }
            is DataRoute -> {
            val vm: DataViewModel = koinViewModel()
            DataScreen(
                onBack = back,
                onOpenGroups = { navigate(GroupsRoute) },
                onOpenLog = { navigate(LogRoute) },
                onClearProbeResults = vm::clearProbeResults,
                onClearLog = vm::clearLog,
            )
        }
            is LicensesRoute -> {
                LicensesScreen(onBack = back)
            }

            is LogRoute -> {
            val vm: LogViewModel = koinViewModel()
            val entries by vm.entries.collectAsStateWithLifecycle()
            LogScreen(
                entries = entries,
                onBack = back,
            )
        }
            is SyncRoute -> {
            val vm: SyncViewModel = koinViewModel()
            SyncRouteContent(
                onBack = back,
                vm = vm,
            )
        }
            is UpdateRoute -> {
            val vm: UpdateViewModel = koinViewModel()
            val updateState by vm.state.collectAsStateWithLifecycle()
            val updateChannel by vm.updateChannel.collectAsStateWithLifecycle()
            UpdateScreen(
                onBack = back,
                updateState = updateState,
                updateChannel = updateChannel,
                onUpdateChannelChange = vm::onUpdateChannelChange,
                onCheckNow = vm::checkNow,
                onOpenDownload = { url ->
                    openExternalUrl(url)
                },
            )
        }

        // 剩下这几个还是 M0.8 立起来的空壳，内容各归各的里程碑（见 §16）
            is ProviderEditorRoute -> {
            val vm: ProviderEditorViewModel = koinViewModel(parameters = { parametersOf(route.id) })
            val draft by vm.draft.collectAsStateWithLifecycle()
            val groups by vm.groups.collectAsStateWithLifecycle()
            val loaded by vm.loaded.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.saved.collect { back() } }
            val ungrouped = stringResource(Res.string.editor_group_none)
            if (loaded) {
                ProviderEditorScreen(
                    draft = draft,
                    groupNames = listOf(ungrouped) + groups.map { it.name },
                    onChange = vm::onChange,
                    onBack = back,
                    onSave = vm::onSave,
                )
            }
        }
            is ImportRoute -> {
            val vm: ImportViewModel = koinViewModel()
            val previews by vm.previews.collectAsStateWithLifecycle()
            val parseErrors by vm.parseErrorCount.collectAsStateWithLifecycle()
            val importing by vm.importing.collectAsStateWithLifecycle()
            ImportScreen(
                previews = previews,
                parseErrors = parseErrors,
                importing = importing,
                onBack = back,
                onParse = vm::parse,
                onToggle = vm::toggle,
                onConfirm = { vm.confirm { back() } },
                readClipboard = vm::readClipboard,
            )
        }
            is GroupsRoute -> {
            val vm: ManageViewModel = koinViewModel()
            val manage by vm.state.collectAsStateWithLifecycle()
            val snackbar = LocalAppSnackbar.current
            val groupAddFailed = stringResource(Res.string.groups_add_failed)
            LaunchedEffect(vm, groupAddFailed) {
                vm.groupError.collect { snackbar?.show(groupAddFailed) }
            }
            GroupsScreen(
                // 「全部」那一枚由页面自己过滤掉（它不入库，也就没有重命名这种操作）
                groups = manage.groups,
                onBack = back,
                onAdd = vm::onAddGroup,
                onRename = vm::onRenameGroup,
                onDelete = vm::onDeleteGroup,
            )
        }
        // 探测明细。入口在仪表盘的"查看明细"（有过一轮探测才画）。这一页只读：
        // 看上一轮结果、重试失败项。
            is ProbeRunRoute -> {
            val vm: ProbeRunViewModel = koinViewModel()
            val run by vm.state.collectAsStateWithLifecycle()
            ProbeRunScreen(
                lastRun = run.lastRun,
                nowMs = run.nowMs,
                failed = run.failed,
                skipped = run.skipped,
                succeeded = run.succeeded,
                onBack = back,
                onRetryFailed = vm::retryFailed,
                onOpenProvider = { id -> navigate(ProviderDetailRoute(id)) },
            )
        }
            is BalanceBreakdownRoute -> {
            val vm: DashboardViewModel = koinViewModel()
            val rows by vm.providerRows.collectAsStateWithLifecycle()
            BalanceBreakdownScreen(
                // 失败与成功分两组，而“压根没配置余额查询”的一组都不进（§9.3 的三种状态）。
                // 用 balanceFailed 而不是 `balance == null`：后者把“没查过”也归进了失败，
                // 于是一个刚建好的供应商会被报成“没能读到余额”
                providers = rows.filter { it.balance != null },
                failedProviders = rows.filter { it.balanceFailed },
                onBack = back,
                onOpenProvider = { id -> navigate(ProviderDetailRoute(id)) },
            )
        }
        }
    }

    // 一级页也走同一个 NavDisplay：Pager 会把“首页 → 设置”拆成两段相邻滚动，
    // 第一段经过“管理”时就会把 currentPage 写回 backStack，动画随即被重定向到中间页。
    // NavDisplay 直接比较初始/目标 scene，既能一步到达，也保留二级页的进出动画。
    VaultNavDisplay(
        backStack = routeSnapshot,
        onBack = { back() },
        style = style,
        exitDirection = exitDirection,
        modifier = modifier,
    ) { route ->
        RouteContent(route)
    }
}

/**
 * 同步页的 SAF 与 WebDAV 接线。
 *
 * 页面主体只给行入口；口令、凭据与恢复模式都在 OverlayDialog 里完成。
 */
@Composable
private fun SyncRouteContent(
    onBack: () -> Unit,
    vm: SyncViewModel,
) {
    val snackbar = LocalAppSnackbar.current
    val backup by vm.backup.collectAsStateWithLifecycle()
    val webDavConfig by vm.webDavConfig.collectAsStateWithLifecycle()
    val webDavBusy by vm.webDavBusy.collectAsStateWithLifecycle()

    val passphrasePrompt = stringResource(Res.string.sync_passphrase_prompt)
    val passphraseHint = stringResource(Res.string.sync_passphrase_hint)
    val confirm = stringResource(Res.string.sync_confirm)
    val exported = stringResource(Res.string.sync_result_exported)
    val webDavTitle = stringResource(Res.string.sync_webdav_title)
    val webDavUrlInvalid = stringResource(Res.string.sync_webdav_url_invalid)
    val webDavInsecureRequired = stringResource(Res.string.sync_webdav_insecure_required)
    val webDavCredentialsRequired = stringResource(Res.string.sync_webdav_credentials_required)

    var pendingAction by remember { mutableStateOf<PendingSyncAction?>(null) }
    val passphraseState = rememberSecretTextFieldState()

    var restoreModePicker by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    var webDavRestoreModePicker by remember { mutableStateOf(false) }
    var pendingWebDavPassword by remember { mutableStateOf<CharArray?>(null) }

    var showWebDavSettings by remember { mutableStateOf(false) }
    var webDavError by remember { mutableStateOf<String?>(null) }
    var allowInsecure by remember { mutableStateOf(false) }
    var remoteBackups by remember { mutableStateOf<List<String>?>(null) }
    val webDavUrlState = rememberAppTextFieldState()
    val webDavDirectoryState = rememberAppTextFieldState()
    val webDavUsernameState = rememberSecretTextFieldState()
    val webDavPasswordState = rememberSecretTextFieldState()

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is SyncEvent.ExportSucceeded -> snackbar?.show(exported)
                is SyncEvent.ExportFailed ->
                    snackbar?.show(getString(Res.string.sync_result_failed, event.message ?: "?"))
                is SyncEvent.RestoreSucceeded ->
                    snackbar?.show(getString(Res.string.sync_result_restored, event.importedProviders))
                is SyncEvent.RestoreFailed ->
                    snackbar?.show(getString(Res.string.sync_result_failed, event.message ?: "?"))
                SyncEvent.WebDavConfigSaved -> {
                    remoteBackups = null
                    snackbar?.show(getString(Res.string.sync_result_webdav_configured))
                }
                is SyncEvent.WebDavListSucceeded -> {
                    remoteBackups = event.names
                    snackbar?.show(getString(Res.string.sync_remote_count, event.names.size))
                }
                is SyncEvent.WebDavUploadSucceeded ->
                    snackbar?.show(
                        getString(Res.string.sync_result_webdav_uploaded, event.fileName, event.prunedCount),
                    )
                is SyncEvent.WebDavFailed ->
                    snackbar?.show(getString(Res.string.sync_result_failed, event.message ?: "?"))
            }
        }
    }

    val filePicker = rememberBackupFilePicker(
        onExportPicked = { writeBytes ->
            val password = passphraseState.chars
            vm.export(password) { bytes -> writeBytes(bytes) }
            passphraseState.clear()
            pendingAction = null
        },
        onImportPicked = { bytes ->
            val password = passphraseState.chars
            pendingRestore = PendingRestore(bytes, password)
            restoreModePicker = true
            passphraseState.clear()
            pendingAction = null
        },
    )

    SyncScreen(
        backup = backup,
        webDavConfig = webDavConfig,
        webDavBusy = webDavBusy,
        remoteBackups = remoteBackups,
        onBack = onBack,
        onExport = { pendingAction = PendingSyncAction.Export },
        onImport = { pendingAction = PendingSyncAction.Import },
        onOpenWebDavSettings = {
            webDavUrlState.setText(webDavConfig.url)
            webDavDirectoryState.setText(webDavConfig.remoteDirectory)
            allowInsecure = webDavConfig.allowInsecure
            webDavUsernameState.clear()
            webDavPasswordState.clear()
            webDavError = null
            showWebDavSettings = true
        },
        onUploadWebDav = { pendingAction = PendingSyncAction.WebDavUpload },
        onRestoreWebDav = { pendingAction = PendingSyncAction.WebDavRestore },
        onRefreshWebDav = vm::listWebDavBackups,
    )

    AppDialog(
        show = pendingAction != null,
        onDismissRequest = {
            passphraseState.clear()
            pendingAction = null
        },
        title = passphrasePrompt,
        confirmText = confirm,
        onConfirm = {
            when (pendingAction) {
                PendingSyncAction.Export -> filePicker.pickExport()
                PendingSyncAction.Import -> filePicker.pickImport()
                PendingSyncAction.WebDavUpload -> {
                    vm.uploadToWebDav(passphraseState.chars)
                    passphraseState.clear()
                    pendingAction = null
                }
                PendingSyncAction.WebDavRestore -> {
                    pendingWebDavPassword = passphraseState.chars.copyOf()
                    passphraseState.clear()
                    pendingAction = null
                    webDavRestoreModePicker = true
                }
                null -> Unit
            }
        },
    ) {
        AppSecretTextField(
            state = passphraseState,
            label = passphraseHint,
            singleLine = true,
        )
    }

    AppDialog(
        show = showWebDavSettings,
        onDismissRequest = {
            webDavUsernameState.clear()
            webDavPasswordState.clear()
            showWebDavSettings = false
        },
        title = webDavTitle,
        confirmText = confirm,
        onConfirm = {
            val url = webDavUrlState.text.trim()
            val username = webDavUsernameState.chars
            val password = webDavPasswordState.chars

            webDavError = when {
                url.isBlank() || !(url.startsWith("https://") || url.startsWith("http://")) ->
                    webDavUrlInvalid
                url.startsWith("http://") && !allowInsecure ->
                    webDavInsecureRequired
                !webDavConfig.hasCredentials && (username.isEmpty() || password.isEmpty()) ->
                    webDavCredentialsRequired
                else -> null
            }

            if (webDavError == null) {
                vm.saveWebDavConfig(
                    url = url,
                    remoteDirectory = webDavDirectoryState.text,
                    allowInsecure = allowInsecure,
                    username = username.takeIf { it.isNotEmpty() },
                    password = password.takeIf { it.isNotEmpty() },
                )
                webDavUsernameState.clear()
                webDavPasswordState.clear()
                showWebDavSettings = false
            }
        },
    ) {
        AppTextField(
            state = webDavUrlState,
            label = stringResource(Res.string.sync_webdav_url_label),
            errorText = webDavError,
        )
        AppTextField(
            state = webDavDirectoryState,
            label = stringResource(Res.string.sync_webdav_directory_label),
        )
        AppSecretTextField(
            state = webDavUsernameState,
            label = stringResource(Res.string.sync_webdav_username_label),
            supportingText = if (webDavConfig.hasCredentials) {
                stringResource(Res.string.sync_webdav_credentials_keep)
            } else {
                null
            },
        )
        AppSecretTextField(
            state = webDavPasswordState,
            label = stringResource(Res.string.sync_webdav_password_label),
        )
        AppSwitchRow(
            title = stringResource(Res.string.sync_webdav_insecure_required),
            checked = allowInsecure,
            onCheckedChange = { allowInsecure = it },
            summary = stringResource(Res.string.sync_insecure_http_warning),
        )
    }

    AppDialog(
        show = restoreModePicker,
        onDismissRequest = {
            pendingRestore?.password?.zeroize()
            pendingRestore = null
            restoreModePicker = false
        },
        title = stringResource(Res.string.sync_restore_mode),
        confirmText = null,
    ) {
        RestoreModeButton(stringResource(Res.string.sync_mode_merge)) {
            pendingRestore?.let {
                vm.restore(it.bytes, it.password, RestoreMode.MERGE)
                it.password.zeroize()
            }
            pendingRestore = null
            restoreModePicker = false
        }
        RestoreModeButton(stringResource(Res.string.sync_mode_overwrite)) {
            pendingRestore?.let {
                vm.restore(it.bytes, it.password, RestoreMode.OVERWRITE)
                it.password.zeroize()
            }
            pendingRestore = null
            restoreModePicker = false
        }
        RestoreModeButton(stringResource(Res.string.sync_mode_add_only)) {
            pendingRestore?.let {
                vm.restore(it.bytes, it.password, RestoreMode.ADD_ONLY)
                it.password.zeroize()
            }
            pendingRestore = null
            restoreModePicker = false
        }
    }

    AppDialog(
        show = webDavRestoreModePicker,
        onDismissRequest = {
            pendingWebDavPassword?.zeroize()
            pendingWebDavPassword = null
            webDavRestoreModePicker = false
        },
        title = stringResource(Res.string.sync_restore_mode),
        confirmText = null,
    ) {
        RestoreModeButton(stringResource(Res.string.sync_mode_merge)) {
            pendingWebDavPassword?.let {
                vm.restoreLatestFromWebDav(it, RestoreMode.MERGE)
                it.zeroize()
            }
            pendingWebDavPassword = null
            webDavRestoreModePicker = false
        }
        RestoreModeButton(stringResource(Res.string.sync_mode_overwrite)) {
            pendingWebDavPassword?.let {
                vm.restoreLatestFromWebDav(it, RestoreMode.OVERWRITE)
                it.zeroize()
            }
            pendingWebDavPassword = null
            webDavRestoreModePicker = false
        }
        RestoreModeButton(stringResource(Res.string.sync_mode_add_only)) {
            pendingWebDavPassword?.let {
                vm.restoreLatestFromWebDav(it, RestoreMode.ADD_ONLY)
                it.zeroize()
            }
            pendingWebDavPassword = null
            webDavRestoreModePicker = false
        }
    }

}


@Composable
private fun RestoreModeButton(text: String, onClick: () -> Unit) {
    AppTextButton(text = text, onClick = onClick, modifier = Modifier.fillMaxWidth())
}

/** 口令确认之后要触发的动作。 */
private enum class PendingSyncAction {
    Export,
    Import,
    WebDavUpload,
    WebDavRestore,
}

/** 待恢复的包字节 + 口令（口令用完必须擦）。 */
private class PendingRestore(val bytes: ByteArray, val password: CharArray)
