package com.lc33.tokenvault.ui.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.lc33.tokenvault.engine.RestoreMode
import com.lc33.tokenvault.screens.dashboard.BalanceBreakdownScreen
import com.lc33.tokenvault.screens.dashboard.DashboardScreen
import com.lc33.tokenvault.screens.lock.ChangePinScreen
import com.lc33.tokenvault.screens.lock.RecoveryKeyScreen
import com.lc33.tokenvault.screens.manage.GroupsScreen
import com.lc33.tokenvault.screens.manage.ImportScreen
import com.lc33.tokenvault.screens.manage.ManageScreen
import com.lc33.tokenvault.screens.manage.ProviderDetailScreen
import com.lc33.tokenvault.screens.manage.ProviderEditorScreen
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.screens.model.SettingsDraft
import com.lc33.tokenvault.screens.probe.ProbeRunScreen
import com.lc33.tokenvault.screens.settings.AboutScreen
import com.lc33.tokenvault.screens.settings.AppearanceScreen
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
            val vm: DashboardViewModel = hiltViewModel()
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
                    onRefreshBalance = vm::refreshBalance,
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
            val autoLockIndex by vm.autoLockIndex.collectAsStateWithLifecycle()
            // 系统弹框的文案由系统画，所以要在这里取好传下去（ViewModel 读不到资源）。
            val enableTitle = stringResource(R.string.biometric_prompt_enable_title)
            val enableSubtitle = stringResource(R.string.biometric_prompt_enable_subtitle)
            val promptCancel = stringResource(R.string.biometric_prompt_cancel)
            val activity = context as? FragmentActivity
            SecurityScreen(
                draft = settings,
                biometric = biometric,
                autoLockIndex = autoLockIndex,
                onChange = { settings = it },
                onBiometricChange = { wanted ->
                    activity?.let {
                        vm.onBiometricChange(wanted, it, enableTitle, enableSubtitle, promptCancel)
                    }
                },
                onAutoLockIndexChange = vm::onAutoLockIndexChange,
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
            val vm: ProfileListViewModel = hiltViewModel()
            val profiles by vm.profiles.collectAsStateWithLifecycle()
            val defaultName = stringResource(R.string.profile_name_default)
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
            val vm: ProfileEditorViewModel = hiltViewModel()
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
            val vm: DataViewModel = hiltViewModel()
            DataScreen(
                onBack = back,
                onSyncCatalog = {},
                onOpenGroups = { nav.navigate(GroupsRoute) },
                onOpenLog = { nav.navigate(LogRoute) },
                onClearProbeResults = vm::clearProbeResults,
                onClearLog = vm::clearLog,
            )
        }
        composable<LogRoute> {
            val vm: LogViewModel = hiltViewModel()
            val entries by vm.entries.collectAsStateWithLifecycle()
            LogScreen(
                entries = entries,
                onBack = back,
            )
        }
        composable<SyncRoute> {
            val vm: SyncViewModel = hiltViewModel()
            SyncRouteContent(
                draft = settings,
                onChange = { settings = it },
                onBack = back,
                vm = vm,
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
            val profiles by vm.profiles.collectAsStateWithLifecycle()
            val loaded by vm.loaded.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.saved.collect { back() } }
            // 下标 0 固定是「未分组」，与 ProviderDraftMapping 里那张表对齐
            val ungrouped = stringResource(R.string.editor_group_none)
            // 客户端预设下拉：0 = 「默认（不伪装）」，其余按仓库返回的预设顺序对齐。
            // 内置 `default` 那一枚的显示名要本地化，其余品牌名 / 自定义名直接用。
            val profileDefaultLabel = stringResource(R.string.profile_name_default)
            val editorProfileDefault = stringResource(R.string.editor_profile_default)
            if (loaded) {
                ProviderEditorScreen(
                    draft = draft,
                    groupNames = listOf(ungrouped) + groups.map { it.name },
                    profileNames = listOf(editorProfileDefault) +
                        profiles.map { it.displayName(profileDefaultLabel) },
                    onChange = vm::onChange,
                    onBack = back,
                    onSave = vm::onSave,
                )
            }
        }
        composable<ImportRoute> {
            val vm: ImportViewModel = hiltViewModel()
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
        // 探测明细。入口在仪表盘的"查看明细"（有过一轮探测才画）。这一页只读：
        // 看上一轮结果、重试失败项。
        composable<ProbeRunRoute> {
            val vm: ProbeRunViewModel = hiltViewModel()
            val run by vm.state.collectAsStateWithLifecycle()
            ProbeRunScreen(
                lastRun = run.lastRun,
                nowMs = run.nowMs,
                failed = run.failed,
                skipped = run.skipped,
                succeeded = run.succeeded,
                onBack = back,
                onRetryFailed = {},
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
            )
        }
        composable<BalanceBreakdownRoute> {
            val vm: DashboardViewModel = hiltViewModel()
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
    draft: SettingsDraft,
    onChange: (SettingsDraft) -> Unit,
    onBack: () -> Unit,
    vm: SyncViewModel,
) {
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val backup by vm.backup.collectAsStateWithLifecycle()

    val passphrasePrompt = stringResource(R.string.sync_passphrase_prompt)
    val passphraseHint = stringResource(R.string.sync_passphrase_hint)
    val confirm = stringResource(R.string.sync_confirm)
    val cancel = stringResource(R.string.sync_cancel)
    val exported = stringResource(R.string.sync_result_exported)
    val failedPrefix = stringResource(R.string.sync_result_failed)

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
                    snackbar?.show(failedPrefix.format(event.message ?: "?"))
                is SyncEvent.RestoreSucceeded ->
                    snackbar?.show(
                        context.resources.getString(R.string.sync_result_restored, event.importedProviders),
                    )
                is SyncEvent.RestoreFailed ->
                    snackbar?.show(failedPrefix.format(event.message ?: "?"))
            }
        }
    }

    // 导出目标文件选择
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val password = passphraseState.chars
        vm.export(password) { bytes ->
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw java.io.IOException("cannot open output stream")
        }
        passphraseState.clear()
        pendingAction = null
    }

    // 导入文件选择
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@rememberLauncherForActivityResult
        val password = passphraseState.chars
        pendingRestore = PendingRestore(bytes, password)
        restoreModePicker = true
        passphraseState.clear()
        pendingAction = null
    }

    SyncScreen(
        draft = draft,
        backup = backup,
        onChange = onChange,
        onBack = onBack,
        onExport = { pendingAction = PendingSyncAction.Export },
        onImport = { pendingAction = PendingSyncAction.Import },
        onBackupPassphrase = {},
        onWebDav = {},
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
                PendingSyncAction.Export -> exportLauncher.launch("yuanji-backup.yjv")
                PendingSyncAction.Import -> importLauncher.launch(arrayOf("*/*"))
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
        title = stringResource(R.string.sync_restore_mode),
        confirmText = null,
    ) {
        AppTextButton(text = stringResource(R.string.sync_mode_merge), onClick = {
            pendingRestore?.let { vm.restore(it.bytes, it.password, RestoreMode.MERGE) }
            restoreModePicker = false
        })
        AppTextButton(text = stringResource(R.string.sync_mode_overwrite), onClick = {
            pendingRestore?.let { vm.restore(it.bytes, it.password, RestoreMode.OVERWRITE) }
            restoreModePicker = false
        })
        AppTextButton(text = stringResource(R.string.sync_mode_add_only), onClick = {
            pendingRestore?.let { vm.restore(it.bytes, it.password, RestoreMode.ADD_ONLY) }
            restoreModePicker = false
        })
    }
}

/** 导出口令之后要触发的动作。 */
private enum class PendingSyncAction { Export, Import }

/** 待恢复的包字节 + 口令（口令用完必须擦）。 */
private class PendingRestore(val bytes: ByteArray, val password: CharArray)

