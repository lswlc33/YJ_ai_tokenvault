package com.lc33.tokenvault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.lc33.tokenvault.engine.RestoreMode
import com.lc33.tokenvault.platform.openAppLocaleSettings
import com.lc33.tokenvault.platform.openExternalUrl
import com.lc33.tokenvault.platform.rememberBackupFilePicker
import com.lc33.tokenvault.screens.dashboard.BalanceBreakdownScreen
import com.lc33.tokenvault.screens.dashboard.DashboardScreen
import com.lc33.tokenvault.screens.lock.ChangePinScreen
import com.lc33.tokenvault.screens.manage.GroupsScreen
import com.lc33.tokenvault.screens.manage.ImportScreen
import com.lc33.tokenvault.screens.manage.ManageScreen
import com.lc33.tokenvault.screens.manage.ProviderDetailScreen
import com.lc33.tokenvault.screens.manage.ProviderEditorScreen
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.screens.probe.ProbeRunScreen
import com.lc33.tokenvault.screens.settings.AboutScreen
import com.lc33.tokenvault.screens.settings.AppearanceScreen
import com.lc33.tokenvault.screens.settings.BalanceThresholdsScreen
import com.lc33.tokenvault.screens.settings.ClientKeywordsScreen
import com.lc33.tokenvault.screens.settings.ProxyScreen
import com.lc33.tokenvault.screens.settings.DataScreen
import com.lc33.tokenvault.screens.settings.LogScreen
import com.lc33.tokenvault.screens.settings.ProbeSettingsScreen
import com.lc33.tokenvault.screens.settings.ProfileListScreen
import com.lc33.tokenvault.screens.settings.ProfileEditorScreen
import com.lc33.tokenvault.screens.settings.SecurityScreen
import com.lc33.tokenvault.screens.settings.SettingsScreen
import com.lc33.tokenvault.screens.settings.SyncScreen
import com.lc33.tokenvault.screens.settings.UpdateScreen
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppSecretTextField
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.LocalAppSnackbar
import com.lc33.tokenvault.ui.miuix.rememberSecretTextFieldState
import kotlinx.coroutines.launch
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.clipboard_label_account
import tokenvault.shared.generated.resources.clipboard_label_api_key
import tokenvault.shared.generated.resources.editor_group_none
import tokenvault.shared.generated.resources.editor_profile_default
import tokenvault.shared.generated.resources.group_all
import tokenvault.shared.generated.resources.profile_name_default
import tokenvault.shared.generated.resources.sync_cancel
import tokenvault.shared.generated.resources.sync_confirm
import tokenvault.shared.generated.resources.sync_mode_add_only
import tokenvault.shared.generated.resources.sync_mode_merge
import tokenvault.shared.generated.resources.sync_mode_overwrite
import tokenvault.shared.generated.resources.sync_passphrase_hint
import tokenvault.shared.generated.resources.sync_passphrase_prompt
import tokenvault.shared.generated.resources.sync_restore_mode
import tokenvault.shared.generated.resources.sync_result_exported
import tokenvault.shared.generated.resources.sync_result_failed
import tokenvault.shared.generated.resources.sync_result_restored

/**
 * 导航图。
 *
 * **M3 起管理那一支与仪表盘吃真数据**：管理页、供应商详情、供应商编辑、分组管理、
 * 仪表盘六块卡与余额明细都接 ViewModel + 仓库；分组筛选那个"当前选中"是纯 UI 状态，
 * 留在 `ManageViewModel` 里。M4 起粘贴导入的预览吃真解析器，M6 起客户端预设列表 / 编辑页
 * 与供应商编辑页的预设下拉吃真仓库——`screens/sample` 那个样例包已随 M6 删除。
 */
@Composable
fun VaultNavHost(
    nav: NavHostController,
    modifier: Modifier = Modifier,
) {
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
            val vm: DashboardViewModel = koinViewModel()
            val dashboard by vm.state.collectAsStateWithLifecycle()
            DashboardScreen(
                state = dashboard,
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
                onOpenManage = ::openManage,
                onOpenProbeRun = { nav.navigate(ProbeRunRoute) },
                onOpenSync = { nav.navigate(SyncRoute) },
                onOpenBalanceBreakdown = { nav.navigate(BalanceBreakdownRoute) },
                onStartProbe = vm::startProbe,
                onCancelProbe = vm::cancelProbe,
                onRefreshBalance = vm::refreshBalance,
            )
        }

        composable<ManageRoute> {
            val vm: ManageViewModel = koinViewModel()
            val manage by vm.state.collectAsStateWithLifecycle()
            // 「全部」那一枚 chip 的文案在资源里，而 ViewModel 读不到资源（红线 19）
            val allLabel = stringResource(Res.string.group_all)
            LaunchedEffect(allLabel) { vm.setAllGroupLabel(allLabel) }
            ManageScreen(
                state = manage,
                onSelectGroup = vm::onSelectGroup,
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
                onOpenGroups = { nav.navigate(GroupsRoute) },
                onNewProvider = { nav.navigate(ProviderEditorRoute()) },
                onImport = { nav.navigate(ImportRoute) },
                onQueryChange = vm::onQueryChange,
                onSort = vm::onSort,
                onEnterSelection = vm::enterSelection,
                onToggleSelect = vm::toggleSelect,
                onSelectAll = vm::selectAll,
                onClearSelection = vm::clearSelection,
                onBatchDelete = vm::batchDelete,
                onBatchSetGroup = vm::batchSetGroup,
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
            val vm: ProviderDetailViewModel = koinViewModel()
            val detail by vm.state.collectAsStateWithLifecycle()
            val revealed by vm.revealed.collectAsStateWithLifecycle()
            val revealedAccount by vm.revealedAccount.collectAsStateWithLifecycle()
            val clipboardLabel = stringResource(Res.string.clipboard_label_api_key)
            val accountClipboardLabel = stringResource(Res.string.clipboard_label_account)
            // 这一家可能刚被删掉（详情页还在栈上）。detail 为 null 时什么都不画：
            // 画一个空壳会让用户以为数据丢了，而真相是这一行已经不存在
            detail?.let { state ->
                ProviderDetailScreen(
                    state = state,
                    revealedKeyId = revealed?.keyId,
                    revealedText = revealed?.text,
                    revealedAccount = revealedAccount,
                    onBack = back,
                    onEdit = { nav.navigate(ProviderEditorRoute(route.id)) },
                    onAddKey = vm::onAddKey,
                    onRevealKey = vm::onRevealKey,
                    onCopyRevealed = { vm.onCopyRevealed(clipboardLabel) },
                    onCloseReveal = vm::onCloseKeySheet,
                    onSetDefaultKey = vm::onSetDefaultKey,
                    onDeleteKey = vm::onDeleteKey,
                    onRefreshBalance = vm::refreshBalance,
                    onProbeProvider = vm::probeProvider,
                    onProbeKey = vm::probeKey,
                    onRevealAccount = vm::onRevealAccount,
                    onCopyRevealedAccount = { vm.onCopyRevealedAccount(accountClipboardLabel) },
                    onCloseAccountReveal = vm::onCloseAccountSheet,
                )
            }
        }

        composable<AppearanceRoute> {
            val vm: AppearanceViewModel = koinViewModel()
            val colorScheme by vm.colorScheme.collectAsStateWithLifecycle()
            val blurNavBar by vm.blurNavBar.collectAsStateWithLifecycle()
            AppearanceScreen(
                colorScheme = colorScheme,
                blurNavBar = blurNavBar,
                onColorSchemeChange = vm::onColorSchemeChange,
                onBlurNavBarChange = vm::onBlurNavBarChange,
                onBack = back,
                onOpenSystemLocaleSettings = { openAppLocaleSettings() },
            )
        }
        composable<SecurityRoute> {
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
                onChangePin = { nav.navigate(ChangePinRoute) },
                onLockNow = vm::onLockNow,
            )
        }
        composable<ChangePinRoute> {
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
        composable<ProbeSettingsRoute> {
            val vm: ProbeSettingsViewModel = koinViewModel()
            val sniffClientProfile by vm.sniffClientProfile.collectAsStateWithLifecycle()
            val defaultProbeReachability by vm.defaultProbeReachability.collectAsStateWithLifecycle()
            val defaultProbeKeys by vm.defaultProbeKeys.collectAsStateWithLifecycle()
            val defaultProbeBalance by vm.defaultProbeBalance.collectAsStateWithLifecycle()
            val defaultProbeModels by vm.defaultProbeModels.collectAsStateWithLifecycle()
            ProbeSettingsScreen(
                sniffClientProfile = sniffClientProfile,
                defaultProbeReachability = defaultProbeReachability,
                defaultProbeKeys = defaultProbeKeys,
                defaultProbeBalance = defaultProbeBalance,
                defaultProbeModels = defaultProbeModels,
                onSniffClientProfileChange = vm::onSniffClientProfileChange,
                onDefaultProbeReachabilityChange = vm::onDefaultProbeReachabilityChange,
                onDefaultProbeKeysChange = vm::onDefaultProbeKeysChange,
                onDefaultProbeBalanceChange = vm::onDefaultProbeBalanceChange,
                onDefaultProbeModelsChange = vm::onDefaultProbeModelsChange,
                onBack = back,
                onOpenManage = ::openManage,
                onEditThresholds = { nav.navigate(BalanceThresholdsRoute) },
                onEditKeywords = { nav.navigate(ClientKeywordsRoute) },
                onEditProxy = { nav.navigate(ProxyRoute) },
            )
        }
        composable<BalanceThresholdsRoute> {
            val vm: BalanceThresholdsViewModel = koinViewModel()
            BalanceThresholdsScreen(
                viewModel = vm,
                onBack = back,
            )
        }
        composable<ClientKeywordsRoute> {
            val vm: ClientKeywordsViewModel = koinViewModel()
            ClientKeywordsScreen(
                viewModel = vm,
                onBack = back,
            )
        }
        composable<ProxyRoute> {
            val vm: ProxyViewModel = koinViewModel()
            ProxyScreen(
                viewModel = vm,
                onBack = back,
            )
        }
        composable<ProfileListRoute> {
            val vm: ProfileListViewModel = koinViewModel()
            val profiles by vm.profiles.collectAsStateWithLifecycle()
            val defaultName = stringResource(Res.string.profile_name_default)
            ProfileListScreen(
                profiles = profiles,
                defaultName = defaultName,
                onBack = back,
                onOpenProfile = { id -> nav.navigate(ProfileEditorRoute(id)) },
                onNewFromCurl = { nav.navigate(ProfileEditorRoute()) },
            )
        }
        composable<ProfileEditorRoute> { entry ->
            val route = entry.toRoute<ProfileEditorRoute>()
            val vm: ProfileEditorViewModel = koinViewModel()
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
        composable<DataRoute> {
            val vm: DataViewModel = koinViewModel()
            DataScreen(
                onBack = back,
                onOpenGroups = { nav.navigate(GroupsRoute) },
                onOpenLog = { nav.navigate(LogRoute) },
                onClearProbeResults = vm::clearProbeResults,
                onClearLog = vm::clearLog,
            )
        }
        composable<LogRoute> {
            val vm: LogViewModel = koinViewModel()
            val entries by vm.entries.collectAsStateWithLifecycle()
            LogScreen(
                entries = entries,
                onBack = back,
            )
        }
        composable<SyncRoute> {
            val vm: SyncViewModel = koinViewModel()
            SyncRouteContent(
                onBack = back,
                vm = vm,
            )
        }
        composable<UpdateRoute> {
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
        composable<ProviderEditorRoute> {
            val vm: ProviderEditorViewModel = koinViewModel()
            val draft by vm.draft.collectAsStateWithLifecycle()
            val groups by vm.groups.collectAsStateWithLifecycle()
            val profiles by vm.profiles.collectAsStateWithLifecycle()
            val loaded by vm.loaded.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.saved.collect { back() } }
            // 下标 0 固定是「未分组」，与 ProviderDraftMapping 里那张表对齐
            val ungrouped = stringResource(Res.string.editor_group_none)
            // 客户端预设下拉：0 = 「默认（不伪装）」，其余按仓库返回的预设顺序对齐。
            // 内置 `default` 那一枚的显示名要本地化，其余品牌名 / 自定义名直接用。
            val profileDefaultLabel = stringResource(Res.string.profile_name_default)
            val editorProfileDefault = stringResource(Res.string.editor_profile_default)
            if (loaded) {
                ProviderEditorScreen(
                    draft = draft,
                    groupNames = listOf(ungrouped) + groups.map { it.name },
                    profileNames = listOf(editorProfileDefault) +
                        profiles.map { if (it.builtinKey == "default") profileDefaultLabel else it.name },
                    onChange = vm::onChange,
                    onBack = back,
                    onSave = vm::onSave,
                )
            }
        }
        composable<ImportRoute> {
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
        composable<GroupsRoute> {
            val vm: ManageViewModel = koinViewModel()
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
        // 探测明细。入口在仪表盘的"查看明细"（有过一轮探测才画）。这一页只读：
        // 看上一轮结果、重试失败项。
        composable<ProbeRunRoute> {
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
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
            )
        }
        composable<BalanceBreakdownRoute> {
            val vm: DashboardViewModel = koinViewModel()
            val rows by vm.providerRows.collectAsStateWithLifecycle()
            BalanceBreakdownScreen(
                // 失败与成功分两组，而“压根没配置余额查询”的一组都不进（§9.3 的三种状态）。
                // 用 balanceFailed 而不是 `balance == null`：后者把“没查过”也归进了失败，
                // 于是一个刚建好的供应商会被报成“没能读到余额”
                providers = rows.filter { it.balance != null },
                failedProviders = rows.filter { it.balanceFailed },
                onBack = back,
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
            )
        }
    }
}

/**
 * 同步页的 SAF 接线（§12.1）。
 *
 * SAF 文件读写（`CreateDocument` / `OpenDocument`）在这里而不是 ViewModel 里做：
 * 那两个 contract 要 `ActivityResultRegistry`，属于平台能力。这里只负责：
 * 1. 导出：让用户选目标文件 → 输口令 → 写包。
 * 2. 恢复：让用户选备份文件 → 输口令 → 选合并方式 → 落库。
 *
 * 口令用 [AppSecretTextField]（键盘不联想、不记忆），用完即擦。恢复失败与成功都走
 * Snackbar 反馈，不弹状态对话框。
 */
@Composable
private fun SyncRouteContent(
    onBack: () -> Unit,
    vm: SyncViewModel,
) {
    val snackbar = LocalAppSnackbar.current
    val backup by vm.backup.collectAsStateWithLifecycle()

    val passphrasePrompt = stringResource(Res.string.sync_passphrase_prompt)
    val passphraseHint = stringResource(Res.string.sync_passphrase_hint)
    val confirm = stringResource(Res.string.sync_confirm)
    val cancel = stringResource(Res.string.sync_cancel)
    val exported = stringResource(Res.string.sync_result_exported)

    // 口令对话框：一段明文口令，只在提交那一刻活一次
    var pendingAction by remember { mutableStateOf<PendingSyncAction?>(null) }
    val passphraseState = rememberSecretTextFieldState()

    // 恢复模式选择
    var restoreModePicker by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }

    // 事件 → Snackbar
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is SyncEvent.ExportSucceeded -> snackbar?.show(exported)
                is SyncEvent.ExportFailed ->
                    snackbar?.show(getString(Res.string.sync_result_failed, event.message ?: "?"))
                is SyncEvent.RestoreSucceeded ->
                    snackbar?.show(
                        getString(Res.string.sync_result_restored, event.importedProviders),
                    )
                is SyncEvent.RestoreFailed ->
                    snackbar?.show(getString(Res.string.sync_result_failed, event.message ?: "?"))
            }
        }
    }

    // 导出/导入文件选择（平台能力，见 BackupFilePicker.kt）
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
        onBack = onBack,
        onExport = { pendingAction = PendingSyncAction.Export },
        onImport = { pendingAction = PendingSyncAction.Import },
    )

    // 口令对话框
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

    // 恢复模式选择
    AppDialog(
        show = restoreModePicker,
        onDismissRequest = {
            pendingRestore?.password?.let { it.fill(0.toChar()) }
            pendingRestore = null
            restoreModePicker = false
        },
        title = stringResource(Res.string.sync_restore_mode),
        confirmText = null,
    ) {
        AppTextButton(text = stringResource(Res.string.sync_mode_merge), onClick = {
            pendingRestore?.let { vm.restore(it.bytes, it.password, RestoreMode.MERGE) }
            restoreModePicker = false
        })
        AppTextButton(text = stringResource(Res.string.sync_mode_overwrite), onClick = {
            pendingRestore?.let { vm.restore(it.bytes, it.password, RestoreMode.OVERWRITE) }
            restoreModePicker = false
        })
        AppTextButton(text = stringResource(Res.string.sync_mode_add_only), onClick = {
            pendingRestore?.let { vm.restore(it.bytes, it.password, RestoreMode.ADD_ONLY) }
            restoreModePicker = false
        })
    }
}

/** 导出口令之后要触发的动作。 */
private enum class PendingSyncAction { Export, Import }

/** 待恢复的包字节 + 口令（口令用完必须擦）。 */
private class PendingRestore(val bytes: ByteArray, val password: CharArray)

