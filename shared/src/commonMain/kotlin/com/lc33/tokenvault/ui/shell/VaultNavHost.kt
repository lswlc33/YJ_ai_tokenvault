package com.lc33.tokenvault.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
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
import com.lc33.tokenvault.engine.WebDavEngine
import com.lc33.tokenvault.platform.BiometricPromptText
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.platform.openAppLocaleSettings
import com.lc33.tokenvault.platform.openExternalUrl
import com.lc33.tokenvault.platform.rememberBackupFilePicker
import com.lc33.tokenvault.screens.dashboard.DashboardScreen
import com.lc33.tokenvault.ui.common.LoadingState
import com.lc33.tokenvault.ui.common.relativeLabelWithinDay
import com.lc33.tokenvault.screens.lock.ChangePinScreen
import com.lc33.tokenvault.screens.manage.GroupsScreen
import com.lc33.tokenvault.screens.manage.ImportScreen
import com.lc33.tokenvault.screens.manage.KeyDetailScreen
import com.lc33.tokenvault.screens.manage.KeyEditorScreen
import com.lc33.tokenvault.screens.manage.ManageScreen
import com.lc33.tokenvault.screens.manage.ProviderDetailScreen
import com.lc33.tokenvault.screens.manage.ProviderEditorScreen
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.screens.model.UiRemoteBackup
import com.lc33.tokenvault.screens.probe.ProbeRunScreen
import com.lc33.tokenvault.screens.settings.AboutScreen
import com.lc33.tokenvault.screens.settings.AppearanceScreen
import com.lc33.tokenvault.screens.settings.BalanceThresholdsScreen
import com.lc33.tokenvault.screens.settings.ClientKeywordsScreen
import com.lc33.tokenvault.screens.settings.DataScreen
import com.lc33.tokenvault.screens.settings.LicensesScreen
import com.lc33.tokenvault.screens.settings.LogDetailScreen
import com.lc33.tokenvault.screens.settings.LogScreen
import com.lc33.tokenvault.screens.settings.MemberScreen
import com.lc33.tokenvault.screens.settings.ProbeSettingsScreen
import com.lc33.tokenvault.screens.settings.ProfileEditorScreen
import com.lc33.tokenvault.screens.settings.ProfileListScreen
import com.lc33.tokenvault.screens.settings.SecurityScreen
import com.lc33.tokenvault.screens.settings.SettingsScreen
import com.lc33.tokenvault.screens.settings.SyncScreen
import com.lc33.tokenvault.screens.settings.UpdateScreen
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppSecretTextField
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppTabRow
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppFeedback
import com.lc33.tokenvault.ui.miuix.AppUndoFeedback
import com.lc33.tokenvault.ui.miuix.LocalAppFeedback
import com.lc33.tokenvault.ui.miuix.navigation.VaultNavDisplay
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberSecretTextFieldState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import tokenvault.shared.generated.resources.clipboard_label_account
import tokenvault.shared.generated.resources.clipboard_label_api_key
import tokenvault.shared.generated.resources.clipboard_label_base_url
import tokenvault.shared.generated.resources.clipboard_label_model_id
import tokenvault.shared.generated.resources.biometric_prompt_cancel
import tokenvault.shared.generated.resources.biometric_prompt_enable_subtitle
import tokenvault.shared.generated.resources.biometric_prompt_enable_title
import tokenvault.shared.generated.resources.common_undo
import tokenvault.shared.generated.resources.feedback_account_deleted
import tokenvault.shared.generated.resources.feedback_copied
import tokenvault.shared.generated.resources.feedback_group_added
import tokenvault.shared.generated.resources.feedback_group_deleted
import tokenvault.shared.generated.resources.feedback_group_renamed
import tokenvault.shared.generated.resources.feedback_key_deleted
import tokenvault.shared.generated.resources.feedback_model_deleted
import tokenvault.shared.generated.resources.detail_key_reveal_failed
import tokenvault.shared.generated.resources.feedback_model_duplicate
import tokenvault.shared.generated.resources.feedback_model_saved
import tokenvault.shared.generated.resources.feedback_providers_deleted
import tokenvault.shared.generated.resources.feedback_providers_deleted_partial
import tokenvault.shared.generated.resources.feedback_account_saved
import tokenvault.shared.generated.resources.feedback_balance_refreshed
import tokenvault.shared.generated.resources.feedback_clipboard_empty
import tokenvault.shared.generated.resources.feedback_clipboard_filled
import tokenvault.shared.generated.resources.feedback_imported
import tokenvault.shared.generated.resources.data_clear_failed
import tokenvault.shared.generated.resources.feedback_key_saved
import tokenvault.shared.generated.resources.feedback_logs_cleared
import tokenvault.shared.generated.resources.feedback_model_probed
import tokenvault.shared.generated.resources.feedback_models_refreshing
import tokenvault.shared.generated.resources.feedback_pin_changed
import tokenvault.shared.generated.resources.feedback_probe_started
import tokenvault.shared.generated.resources.feedback_probe_key_sent
import tokenvault.shared.generated.resources.feedback_probe_results_cleared
import tokenvault.shared.generated.resources.feedback_probe_retry_nothing
import tokenvault.shared.generated.resources.feedback_probe_retried
import tokenvault.shared.generated.resources.feedback_probe_stopped
import tokenvault.shared.generated.resources.feedback_profile_saved
import tokenvault.shared.generated.resources.feedback_provider_saved
import tokenvault.shared.generated.resources.feedback_undone
import tokenvault.shared.generated.resources.feedback_undo_failed
import tokenvault.shared.generated.resources.editor_default_key_name
import tokenvault.shared.generated.resources.editor_default_provider_name
import tokenvault.shared.generated.resources.editor_group_none
import tokenvault.shared.generated.resources.editor_url_err_empty
import tokenvault.shared.generated.resources.editor_url_err_host
import tokenvault.shared.generated.resources.editor_url_err_query
import tokenvault.shared.generated.resources.editor_url_err_scheme
import tokenvault.shared.generated.resources.editor_profile_default
import tokenvault.shared.generated.resources.group_all
import tokenvault.shared.generated.resources.groups_add_failed
import tokenvault.shared.generated.resources.groups_delete_failed
import tokenvault.shared.generated.resources.groups_rename_failed
import tokenvault.shared.generated.resources.manage_write_failed
import tokenvault.shared.generated.resources.profile_name_default
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.sync_cancel
import tokenvault.shared.generated.resources.sync_confirm
import tokenvault.shared.generated.resources.sync_export_failed
import tokenvault.shared.generated.resources.sync_insecure_http_warning
import tokenvault.shared.generated.resources.sync_mode_add_only
import tokenvault.shared.generated.resources.sync_mode_merge
import tokenvault.shared.generated.resources.sync_mode_overwrite
import tokenvault.shared.generated.resources.sync_overwrite_confirm_summary
import tokenvault.shared.generated.resources.sync_overwrite_confirm_title
import tokenvault.shared.generated.resources.sync_passphrase_hint
import tokenvault.shared.generated.resources.sync_passphrase_prompt
import tokenvault.shared.generated.resources.sync_passphrase_required
import tokenvault.shared.generated.resources.sync_remote_count
import tokenvault.shared.generated.resources.sync_remote_delete
import tokenvault.shared.generated.resources.sync_remote_delete_confirm_summary
import tokenvault.shared.generated.resources.sync_remote_delete_confirm_title
import tokenvault.shared.generated.resources.sync_remote_restore
import tokenvault.shared.generated.resources.sync_restore_failed
import tokenvault.shared.generated.resources.sync_restore_mode
import tokenvault.shared.generated.resources.sync_restore_mode_summary
import tokenvault.shared.generated.resources.sync_result_exported
import tokenvault.shared.generated.resources.sync_result_remote_deleted
import tokenvault.shared.generated.resources.sync_result_restored
import tokenvault.shared.generated.resources.sync_result_webdav_configured
import tokenvault.shared.generated.resources.sync_result_webdav_uploaded
import tokenvault.shared.generated.resources.sync_webdav_credentials_keep
import tokenvault.shared.generated.resources.sync_webdav_credentials_required
import tokenvault.shared.generated.resources.sync_webdav_directory_label
import tokenvault.shared.generated.resources.sync_webdav_failed
import tokenvault.shared.generated.resources.sync_webdav_insecure_required
import tokenvault.shared.generated.resources.sync_webdav_password_hide
import tokenvault.shared.generated.resources.sync_webdav_password_label
import tokenvault.shared.generated.resources.sync_webdav_password_reveal
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
    pager: TopLevelPagerState,
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

    // "设置写不进去"的六个 ViewModel 共用一条失败流（SettingsFailures 单例）。
    // 收集挂在 Shell 上：谁写失败都投同一条提示，页面不必各自开收集器。
    val settingsFailures: SettingsFailures = koinInject()
    val shellFeedback = LocalAppFeedback.current
    val settingsWriteFailed = stringResource(Res.string.manage_write_failed)
    LaunchedEffect(settingsFailures) {
        settingsFailures.events.collect {
            shellFeedback?.post(AppFeedback(settingsWriteFailed))
        }
    }

    @Composable
    fun PageContent(route: VaultRoute) {
        when (route) {
            is DashboardRoute -> {
            val vm: DashboardViewModel = koinViewModel()
            val dashboard by vm.state.collectAsStateWithLifecycle()
            val feedback = LocalAppFeedback.current
            val probeStartedText = stringResource(Res.string.feedback_probe_started)
            val balanceRefreshed = stringResource(Res.string.feedback_balance_refreshed)
            DashboardScreen(
                state = dashboard,
                // 总览与管理的入口是「切到管理的某一页」，不是往栈上压一条管理路由：
                // 三个一级页平级，压在栈上会让返回语义变成"回到总览"。
                onOpenManage = { pager.animateToPage(topLevelIndexOf(ManageRoute)) },
                onOpenProbeDetail = { navigate(ProbeRunRoute) },
                onRefreshBalance = {
                    vm.refreshBalance()
                    feedback?.post(AppFeedback(balanceRefreshed))
                },
                onRefreshStatus = {
                    // 重复点击不叠加：引擎正在跑就不发起第二轮（探测要花钱），
                    // 但余额刷新可以继续，用户按它通常是"再试一次"。
                    val probeStarted = vm.startProbe()
                    vm.refreshStatus(excludeProbe = true)
                    if (probeStarted) feedback?.post(AppFeedback(probeStartedText))
                },
            )
        }

            is ManageRoute -> {
            val vm: ManageViewModel = koinViewModel()
            val manage by vm.state.collectAsStateWithLifecycle()
            // 「全部」那一枚 chip 的文案在资源里，而 ViewModel 读不到资源（红线 19）
            val allLabel = stringResource(Res.string.group_all)
            LaunchedEffect(allLabel) { vm.setAllGroupLabel(allLabel) }
            val feedback = LocalAppFeedback.current
            val probeStartedText = stringResource(Res.string.feedback_probe_started)
            val undoLabel = stringResource(Res.string.common_undo)
            val undone = stringResource(Res.string.feedback_undone)
            val undoFailed = stringResource(Res.string.feedback_undo_failed)
            val providersDeleted = stringResource(Res.string.feedback_providers_deleted)
            val groupAdded = stringResource(Res.string.feedback_group_added)
            val groupRenamed = stringResource(Res.string.feedback_group_renamed)
            val groupDeleted = stringResource(Res.string.feedback_group_deleted)
            val writeFailed = stringResource(Res.string.manage_write_failed)
            LaunchedEffect(vm) {
                vm.events.collect { event ->
                    when (event) {
                        is ManageViewModel.Event.ProvidersDeleted -> {
                            // 按真的删掉几家、几家失败说：三家删了两家也说"已删除所选供应商"，
                            // 用户就会以为剩下那家进了回收站、其实一步没动。
                            val message = if (event.failed == 0) {
                                providersDeleted
                            } else {
                                getString(Res.string.feedback_providers_deleted_partial, event.deleted, event.failed)
                            }
                            feedback?.post(
                                AppFeedback(
                                    message = message,
                                    undo = event.undo?.let { deletion ->
                                        AppUndoFeedback(
                                            actionLabel = undoLabel,
                                            undoneMessage = undone,
                                            failedMessage = undoFailed,
                                            action = { deletion.undo() },
                                        )
                                    },
                                ),
                            )
                        }
                        ManageViewModel.Event.GroupAdded -> feedback?.post(AppFeedback(groupAdded))
                        ManageViewModel.Event.GroupRenamed -> feedback?.post(AppFeedback(groupRenamed))
                        // 删分组不提供撤销：它只把供应商落回「全部」，重新建一个即可。
                        ManageViewModel.Event.GroupDeleted -> feedback?.post(AppFeedback(groupDeleted))
                        // 这一页也会写库（批量改分组），失败不能不出声。
                        ManageViewModel.Event.WriteFailed -> feedback?.post(AppFeedback(writeFailed))
                    }
                }
            }
            ManageScreen(
                state = manage,
                onSelectGroup = vm::onSelectGroup,
                onOpenProvider = { id -> navigate(ProviderDetailRoute(id)) },
                onOpenGroups = { navigate(GroupsRoute) },
                onNewProvider = { navigate(ProviderEditorRoute()) },
                onRefreshStatus = {
                    // 与首页同一条规则：引擎正在跑就不发第二轮，但连通性 / 余额照刷。
                    val probeStarted = vm.startProbe()
                    vm.refreshStatus(excludeProbe = true)
                    if (probeStarted) feedback?.post(AppFeedback(probeStartedText))
                },
                onQueryChange = vm::onQueryChange,
                onEnterSelection = vm::enterSelection,
                onToggleSelect = vm::toggleSelect,
                onSelectAll = vm::selectAll,
                onClearSelection = vm::clearSelection,
                onBatchDelete = vm::batchDelete,
                onBatchSetGroup = vm::batchSetGroup,
                onSort = vm::onSort,
            )
        }

            is SettingsRoute -> {
            val memberVm: MemberViewModel = koinViewModel()
            val member by memberVm.isMember.collectAsStateWithLifecycle()
            SettingsScreen(
                member = member,
                onOpenAppearance = { navigate(AppearanceRoute) },
                onOpenSecurity = { navigate(SecurityRoute) },
                onOpenProbeSettings = { navigate(ProbeSettingsRoute) },
                onOpenProfiles = { navigate(ProfileListRoute) },
                onOpenData = { navigate(DataRoute) },
                onOpenLog = { navigate(LogRoute) },
                onOpenSync = { navigate(SyncRoute) },
                onOpenAbout = { navigate(AboutRoute) },
                onOpenUpdate = { navigate(UpdateRoute) },
                onOpenMember = { navigate(MemberRoute) },
                onRevertMember = memberVm::onCancel,
            )
        }

            is MemberRoute -> {
            val vm: MemberViewModel = koinViewModel()
            val member by vm.isMember.collectAsStateWithLifecycle()
            MemberScreen(
                member = member,
                onBack = back,
                onPurchase = vm::onPurchase,
                onCancel = vm::onCancel,
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
                val detailState = detail
                val revealedAccount by vm.revealedAccount.collectAsStateWithLifecycle()
                val accountClipboardLabel = stringResource(Res.string.clipboard_label_account)
                val feedback = LocalAppFeedback.current
                val undoLabel = stringResource(Res.string.common_undo)
                val undone = stringResource(Res.string.feedback_undone)
                val undoFailed = stringResource(Res.string.feedback_undo_failed)
                val modelDeleted = stringResource(Res.string.feedback_model_deleted)
                val accountDeleted = stringResource(Res.string.feedback_account_deleted)
                val copied = stringResource(Res.string.feedback_copied)
                val modelSaved = stringResource(Res.string.feedback_model_saved)
                val accountSaved = stringResource(Res.string.feedback_account_saved)
                val probeStarted = stringResource(Res.string.feedback_probe_started)
                val modelsRefreshing = stringResource(Res.string.feedback_models_refreshing)
                val writeFailed = stringResource(Res.string.manage_write_failed)
                LaunchedEffect(vm) {
                    vm.events.collect { event ->
                        // 删除留当前页（不像删密钥要退出页面），所以只投提示；
                        // 其余是"动作已发出"的确认，结果本身由状态流回填。
                        val (message, undo) = when (event) {
                            is ProviderDetailViewModel.Event.ModelDeleted ->
                                modelDeleted to event.undo
                            is ProviderDetailViewModel.Event.AccountDeleted ->
                                accountDeleted to event.undo
                            ProviderDetailViewModel.Event.Copied -> {
                                feedback?.post(AppFeedback(copied)); return@collect
                            }
                            ProviderDetailViewModel.Event.ModelSaved -> {
                                feedback?.post(AppFeedback(modelSaved)); return@collect
                            }
                            ProviderDetailViewModel.Event.AccountSaved -> {
                                feedback?.post(AppFeedback(accountSaved)); return@collect
                            }
                            ProviderDetailViewModel.Event.Probed -> {
                                feedback?.post(AppFeedback(probeStarted)); return@collect
                            }
                            ProviderDetailViewModel.Event.WriteFailed -> {
                                feedback?.post(AppFeedback(writeFailed)); return@collect
                            }
                        }
                        feedback?.post(
                            AppFeedback(
                                message = message,
                                undo = undo?.let { deletion ->
                                    AppUndoFeedback(
                                        actionLabel = undoLabel,
                                        undoneMessage = undone,
                                        failedMessage = undoFailed,
                                        action = { deletion.undo() },
                                    )
                                },
                            ),
                        )
                    }
                }
                if (detailState == null) {
                    LoadingState(Modifier.fillMaxSize())
                } else {
                    detailState.let { state ->
                        ProviderDetailScreen(
                            state = state,
                            revealedAccount = revealedAccount,
                            onBack = back,
                            onEdit = { navigate(ProviderEditorRoute(route.id)) },
                            onCurlImport = { navigate(ImportRoute(route.id)) },
                            onManualAddKey = { navigate(KeyEditorRoute(route.id, 0L)) },
                            onOpenKey = { keyId -> navigate(KeyDetailRoute(route.id, keyId)) },
                            onProbeAll = vm::probeAll,
                            onRefreshKeyModels = { keyId ->
                                // 返回值 false = 上一次刷新还在跑或锁定态。那一发压根没出去，
                                // 却念一句"正在刷新模型列表"，用户等到的就是没发生的事；
                                // 真跑起来之后引擎会从 modelResults 回一条结果，这里不用再补。
                                if (vm.refreshModels(keyId)) feedback?.post(AppFeedback(modelsRefreshing))
                            },
                            onAddModel = vm::onAddModel,
                            onUpdateModel = vm::onUpdateModel,
                            onDeleteModel = vm::onDeleteModel,
                            onRevealAccount = vm::onRevealAccount,
                            onCopyRevealedAccount = { accountId ->
                                vm.onCopyRevealedAccount(accountId, accountClipboardLabel)
                            },
                            onCloseAccountReveal = vm::onCloseAccountSheet,
                            onAddAccount = vm::onAddAccount,
                            onUpdateAccount = vm::onUpdateAccount,
                            onDeleteAccount = vm::onDeleteAccount,
                        )
                    }
                }
            }

            is KeyDetailRoute -> {
                val vm: KeyDetailViewModel = koinViewModel(
                    parameters = { parametersOf(route.providerId, route.keyId) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                val keyState = state
                val revealed by vm.revealed.collectAsStateWithLifecycle()
                val feedback = LocalAppFeedback.current
                val keyClipboardLabel = stringResource(Res.string.clipboard_label_api_key)
                val baseUrlClipboardLabel = stringResource(Res.string.clipboard_label_base_url)
                val modelClipboardLabel = stringResource(Res.string.clipboard_label_model_id)
                val keyDeleted = stringResource(Res.string.feedback_key_deleted)
                val keyUndone = stringResource(Res.string.feedback_undone)
                val keyUndoFailed = stringResource(Res.string.feedback_undo_failed)
                val undoLabel = stringResource(Res.string.common_undo)
                val copied = stringResource(Res.string.feedback_copied)
                val keyProbed = stringResource(Res.string.feedback_probe_key_sent)
                val modelsRefreshing = stringResource(Res.string.feedback_models_refreshing)
                val modelProbed = stringResource(Res.string.feedback_model_probed)
                val writeFailed = stringResource(Res.string.manage_write_failed)
                val revealFailed = stringResource(Res.string.detail_key_reveal_failed)
                LaunchedEffect(vm) {
                    vm.events.collect { event ->
                        when (event) {
                            is KeyDetailViewModel.Event.Deleted -> {
                                // 先退回上一页再投递提示：提示挂在 Shell 上，不受导航影响，
                                // 而且这样用户是在"已经看不到那把 Key 的页面"上看到撤销入口。
                                back()
                                feedback?.post(
                                    AppFeedback(
                                        message = keyDeleted,
                                        undo = event.undo?.let { deletion ->
                                            AppUndoFeedback(
                                                actionLabel = undoLabel,
                                                undoneMessage = keyUndone,
                                                failedMessage = keyUndoFailed,
                                                action = { deletion.undo() },
                                            )
                                        },
                                    ),
                                )
                            }
                            KeyDetailViewModel.Event.Copied ->
                                feedback?.post(AppFeedback(copied))
                            KeyDetailViewModel.Event.Probed ->
                                feedback?.post(AppFeedback(keyProbed))
                            KeyDetailViewModel.Event.WriteFailed ->
                                feedback?.post(AppFeedback(writeFailed))
                            KeyDetailViewModel.Event.RevealFailed ->
                                feedback?.post(AppFeedback(revealFailed))
                        }
                    }
                }
                if (keyState == null) {
                    LoadingState(Modifier.fillMaxSize())
                } else {
                    keyState.let { stateValue ->
                        KeyDetailScreen(
                            state = stateValue,
                            revealedText = revealed?.text,
                            onBack = back,
                            onEdit = { navigate(KeyEditorRoute(route.providerId, route.keyId)) },
                            onReveal = vm::reveal,
                            onCopyRevealed = { vm.copyRevealed(keyClipboardLabel) },
                            onCloseReveal = vm::closeReveal,
                            onCopyKey = { vm.copyKey(keyClipboardLabel) },
                            onCopyBaseUrl = { vm.copyBaseUrl(baseUrlClipboardLabel, stateValue.key.settings.apiBaseUrl) },
                            onCopyModelId = { modelId -> vm.copyModelId(modelClipboardLabel, modelId) },
                            onProbe = vm::probeKey,
                            onProbeModel = { modelId ->
                                vm.probeModel(modelId)
                                feedback?.post(AppFeedback(modelProbed))
                            },
                            onRefreshModels = {
                                // 同 ProviderDetailRoute：引擎没接这一发就不预告"正在刷新"。
                                if (vm.refreshModels()) feedback?.post(AppFeedback(modelsRefreshing))
                            },
                            onMoveUp = vm::moveUp,
                            onMoveDown = vm::moveDown,
                            onDelete = vm::delete,
                        )
                    }
                }
            }

            is KeyEditorRoute -> {
                // 默认名（「密钥 N」）的模板在组合期解析一次，真正取用发生在 VM 的
                // init 协程里（那时已不在组合上下文，stringResource 用不了）。
                val keyLabelTemplate = stringResource(Res.string.editor_default_key_name)
                val defaultKeyLabel: (Int) -> String = { n ->
                    defaultNameFromTemplate(keyLabelTemplate, n)
                }
                val vm: KeyEditorViewModel = koinViewModel(
                    parameters = { parametersOf(route.providerId, route.keyId, defaultKeyLabel) },
                )
                val draft by vm.draft.collectAsStateWithLifecycle()
                val profiles by vm.profiles.collectAsStateWithLifecycle()
                val loaded by vm.loaded.collectAsStateWithLifecycle()
                val models by vm.models.collectAsStateWithLifecycle()
                val saving by vm.saving.collectAsStateWithLifecycle()
                val saveError by vm.saveError.collectAsStateWithLifecycle()
                val urlError by vm.urlError.collectAsStateWithLifecycle()
                val revealedSecrets by vm.revealed.collectAsStateWithLifecycle()
                val feedback = LocalAppFeedback.current
                val keySaved = stringResource(Res.string.feedback_key_saved)
                LaunchedEffect(vm) {
                    vm.saved.collect {
                        feedback?.post(AppFeedback(keySaved))
                        back()
                    }
                }
                val undoLabel = stringResource(Res.string.common_undo)
                val undone = stringResource(Res.string.feedback_undone)
                val undoFailed = stringResource(Res.string.feedback_undo_failed)
                val modelDeleted = stringResource(Res.string.feedback_model_deleted)
                val modelSaved = stringResource(Res.string.feedback_model_saved)
                val modelDuplicate = stringResource(Res.string.feedback_model_duplicate)
                LaunchedEffect(vm) {
                    vm.events.collect { event ->
                        when (event) {
                            is KeyEditorViewModel.Event.ModelDeleted -> feedback?.post(
                                AppFeedback(
                                    message = modelDeleted,
                                    undo = event.undo?.let { deletion ->
                                        AppUndoFeedback(
                                            actionLabel = undoLabel,
                                            undoneMessage = undone,
                                            failedMessage = undoFailed,
                                            action = { deletion.undo() },
                                        )
                                    },
                                ),
                            )
                            KeyEditorViewModel.Event.ModelSaved ->
                                feedback?.post(AppFeedback(modelSaved))
                            KeyEditorViewModel.Event.ModelDuplicate ->
                                feedback?.post(AppFeedback(modelDuplicate))
                        }
                    }
                }
                val keyProfileDefaultLabel = stringResource(Res.string.profile_name_default)
                val keyEditorProfileDefault = stringResource(Res.string.editor_profile_default)
                val baseUrlError = when (urlError) {
                    null -> null
                    EndpointError.Empty -> stringResource(Res.string.editor_url_err_empty)
                    EndpointError.UnsupportedScheme -> stringResource(Res.string.editor_url_err_scheme)
                    EndpointError.HasQueryOrFragment -> stringResource(Res.string.editor_url_err_query)
                    EndpointError.NoHost -> stringResource(Res.string.editor_url_err_host)
                }
                if (!loaded) {
                    LoadingState(Modifier.fillMaxSize())
                } else {
                    KeyEditorScreen(
                        draft = draft,
                        profileNames = listOf(keyEditorProfileDefault) + profiles.map { profile ->
                            if (profile.builtinKey == "default") keyProfileDefaultLabel else profile.name
                        },
                        models = models,
                        nowMs = nowMillis(),
                        baseUrlError = baseUrlError,
                        saveError = saveError,
                        saving = saving,
                        revealed = revealedSecrets,
                        onChange = vm::onChange,
                        onBaseUrlChange = vm::clearUrlError,
                        onAddModel = vm::addModel,
                        onUpdateModel = vm::updateModel,
                        onDeleteModel = vm::deleteModel,
                        onRefreshModels = vm::refreshModels,
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
            val biometricEnabled by vm.biometricEnabled.collectAsStateWithLifecycle()
            val biometricAvailable by vm.biometricAvailable.collectAsStateWithLifecycle()
            val biometricBusy by vm.biometricBusy.collectAsStateWithLifecycle()
            // 三条"这件事没成"的说明：启用失败的系统原文、被系统暂时锁住、boot 写不进去。
            // 都从 VM 派生，页面自己不记（红线 31）。
            val biometricError by vm.biometricError.collectAsStateWithLifecycle()
            val biometricLockedOut by vm.biometricLockedOut.collectAsStateWithLifecycle()
            val biometricDisableFailed by vm.biometricDisableFailed.collectAsStateWithLifecycle()
            val bootWriteFailed by vm.bootWriteFailed.collectAsStateWithLifecycle()
            // 启用时的系统验证框文案在组合期解析（VM 读不到资源），点开关时连同结果一起交给 VM。
            val enableTitle = stringResource(Res.string.biometric_prompt_enable_title)
            val enableSubtitle = stringResource(Res.string.biometric_prompt_enable_subtitle)
            val promptCancel = stringResource(Res.string.biometric_prompt_cancel)
            SecurityScreen(
                autoLockIndex = autoLockIndex,
                idleLock = idleLock,
                lockOnScreenOff = lockOnScreenOff,
                clipboardClearIndex = clipboardClearIndex,
                biometricEnabled = biometricEnabled,
                biometricAvailable = biometricAvailable,
                biometricBusy = biometricBusy,
                onAutoLockIndexChange = vm::onAutoLockIndexChange,
                onIdleLockChange = vm::onIdleLockChange,
                onLockOnScreenOffChange = vm::onLockOnScreenOffChange,
                onClipboardClearIndexChange = vm::onClipboardClearIndexChange,
                onBiometricChange = { enabled ->
                    vm.onBiometricChange(
                        enabled,
                        BiometricPromptText(enableTitle, enableSubtitle, promptCancel),
                    )
                },
                onBack = back,
                onChangePin = { navigate(ChangePinRoute) },
                onLockNow = vm::onLockNow,
                biometricError = biometricError,
                biometricLockedOut = biometricLockedOut,
                biometricDisableFailed = biometricDisableFailed,
                bootWriteFailed = bootWriteFailed,
            )
        }
            is ChangePinRoute -> {
            val vm: SecurityViewModel = koinViewModel()
            val state by vm.changePin.collectAsStateWithLifecycle()
            // 改完就退出去。用一次性事件而不是状态里的标志：标志会在重组时重放，
            // 于是这一页会在下一次进来时立刻自己弹回去。
            val feedback = LocalAppFeedback.current
            val pinChanged = stringResource(Res.string.feedback_pin_changed)
            LaunchedEffect(vm) {
                vm.pinChanged.collect {
                    feedback?.post(AppFeedback(pinChanged))
                    back()
                }
            }
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
            val autoRefresh by vm.autoRefresh.collectAsStateWithLifecycle()
            val autoRefreshIntervalIndex by vm.autoRefreshIntervalIndex.collectAsStateWithLifecycle()
            ProbeSettingsScreen(
                sniffClientProfile = sniffClientProfile,
                autoRefresh = autoRefresh,
                autoRefreshIntervalIndex = autoRefreshIntervalIndex,
                defaultProbeReachability = defaultProbeReachability,
                defaultProbeKeys = defaultProbeKeys,
                defaultProbeBalance = defaultProbeBalance,
                defaultProbeModels = defaultProbeModels,
                defaultProbeModelReachability = defaultProbeModelReachability,
                onSniffClientProfileChange = vm::onSniffClientProfileChange,
                onAutoRefreshChange = vm::onAutoRefreshChange,
                onAutoRefreshIntervalIndexChange = vm::onAutoRefreshIntervalIndexChange,
                onDefaultProbeReachabilityChange = vm::onDefaultProbeReachabilityChange,
                onDefaultProbeKeysChange = vm::onDefaultProbeKeysChange,
                onDefaultProbeBalanceChange = vm::onDefaultProbeBalanceChange,
                onDefaultProbeModelsChange = vm::onDefaultProbeModelsChange,
                onDefaultProbeModelReachabilityChange = vm::onDefaultProbeModelReachabilityChange,
                onBack = back,
                // 探测设置是二级页：先让 pager 翻到管理，再退回一级页，露出来就是管理。
                // 顺序反过来的话用户会先看到原来那一页 tab 再滑过去。
                onOpenManage = {
                    pager.animateToPage(topLevelIndexOf(ManageRoute))
                    back()
                },
                onEditThresholds = { navigate(BalanceThresholdsRoute) },
                onEditKeywords = { navigate(ClientKeywordsRoute) },
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
            val loadFailed by vm.loadError.collectAsStateWithLifecycle()
            val feedback = LocalAppFeedback.current
            val profileSaved = stringResource(Res.string.feedback_profile_saved)
            val writeFailed = stringResource(Res.string.manage_write_failed)
            // 同 ProviderEditorRoute：读不到那一行要**摆在页面上**（不是一条会消失的 toast），
            // 否则这一页看着像"新建预设"，按保存却什么都不发生。
            LaunchedEffect(vm) { vm.failed.collect { feedback?.post(AppFeedback(writeFailed)) } }
            // 预设删除不提供撤销：自定义预设重新建一个即可，删除没有级联副作用。
            LaunchedEffect(vm) {
                vm.saved.collect {
                    feedback?.post(AppFeedback(profileSaved))
                    back()
                }
            }
            LaunchedEffect(vm) { vm.deleted.collect { back() } }
            // 同 ProviderEditorRoute：载入前给占位，否则推入动画期间这一页是空的。
            if (!loaded) {
                LoadingState(Modifier.fillMaxSize())
            } else {
                ProfileEditorScreen(
                    initial = profile,
                    loadFailed = loadFailed,
                    onBack = back,
                    onSave = vm::save,
                    onDelete = vm::delete,
                )
            }
        }
            is DataRoute -> {
            val vm: DataViewModel = koinViewModel()
            val feedback = LocalAppFeedback.current
            // 清空类操作只提示完成、不提供撤销：日志与探测结果都是批量清除，
            // 快照代价大，且确认框已经写明「不可撤销」。
            val probeResultsCleared = stringResource(Res.string.feedback_probe_results_cleared)
            val logsCleared = stringResource(Res.string.feedback_logs_cleared)
            val clearFailed = stringResource(Res.string.data_clear_failed)
            // 成败都由 ViewModel 报：按下去就投"已清空"是在赌那次事务一定成，
            // 而这两步都是真删数据（`DataViewModel.events` 存在的理由就是这个）。
            LaunchedEffect(vm) {
                vm.events.collect { event ->
                    when (event) {
                        DataViewModel.Event.ProbeResultsCleared ->
                            feedback?.post(AppFeedback(probeResultsCleared))
                        DataViewModel.Event.LogCleared ->
                            feedback?.post(AppFeedback(logsCleared))
                        DataViewModel.Event.Failed ->
                            feedback?.post(AppFeedback(clearFailed))
                    }
                }
            }
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
            val levelFilter by vm.levelFilter.collectAsStateWithLifecycle()
            val retention by vm.retention.collectAsStateWithLifecycle()
            val feedback = LocalAppFeedback.current
            val logsCleared = stringResource(Res.string.feedback_logs_cleared)
            val clearFailed = stringResource(Res.string.data_clear_failed)
            val writeFailed = stringResource(Res.string.manage_write_failed)
            // 清空与改保留期都是真写库，先报成功就可能报一句假话（红线：成败由 ViewModel 说）。
            LaunchedEffect(vm) {
                vm.events.collect { event ->
                    when (event) {
                        LogViewModel.Event.Cleared -> feedback?.post(AppFeedback(logsCleared))
                        LogViewModel.Event.ClearFailed -> feedback?.post(AppFeedback(clearFailed))
                        LogViewModel.Event.RetentionFailed -> feedback?.post(AppFeedback(writeFailed))
                    }
                }
            }
            LogScreen(
                entries = entries,
                levelFilter = levelFilter,
                retention = retention,
                onLevelFilterChange = vm::setLevelFilter,
                onRetentionChange = vm::setRetention,
                onOpenEntry = { id -> navigate(LogEntryRoute(id)) },
                onClear = vm::clear,
                onBack = back,
            )
        }
            is LogEntryRoute -> {
                val vm: LogEntryViewModel = koinViewModel(parameters = { parametersOf(route.id) })
                val entry by vm.entry.collectAsStateWithLifecycle()
                LogDetailScreen(entry = entry, onBack = back)
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
            // 新建供应商的默认名（「供应商 N」）：模板在组合期解析（红线 19），
            // 序号由 VM 数完现有供应商后回填。
            val providerLabelTemplate = stringResource(Res.string.editor_default_provider_name)
            val vm: ProviderEditorViewModel = koinViewModel(
                parameters = {
                    parametersOf(
                        route.id,
                        { n: Int -> defaultNameFromTemplate(providerLabelTemplate, n) },
                    )
                },
            )
            val draft by vm.draft.collectAsStateWithLifecycle()
            val groups by vm.groups.collectAsStateWithLifecycle()
            val loaded by vm.loaded.collectAsStateWithLifecycle()
            val nameMissing by vm.nameMissing.collectAsStateWithLifecycle()
            val loadFailed by vm.loadError.collectAsStateWithLifecycle()
            val feedback = LocalAppFeedback.current
            val providerSaved = stringResource(Res.string.feedback_provider_saved)
            val writeFailed = stringResource(Res.string.manage_write_failed)
            // 读 / 写失败都要说话：`onSave` 在 loadError 时是直接 return 的，
            // 没人订阅这两条流的话，表现就是"按了保存，页面一动不动"。
            LaunchedEffect(vm) { vm.failed.collect { feedback?.post(AppFeedback(writeFailed)) } }
            LaunchedEffect(vm) {
                vm.saved.collect {
                    feedback?.post(AppFeedback(providerSaved))
                    back()
                }
            }
            val ungrouped = stringResource(Res.string.editor_group_none)
            // 载入前必须占位，不能什么都不画：推入动画期间这一页是**空的**，
            // 于是动画看起来"丢了"——上一页滑走、下一页内容直接跳出来。
            if (!loaded) {
                LoadingState(Modifier.fillMaxSize())
            } else {
                ProviderEditorScreen(
                    draft = draft,
                    groupNames = listOf(ungrouped) + groups.map { it.name },
                    nameMissing = nameMissing,
                    loadFailed = loadFailed,
                    onChange = vm::onChange,
                    onBack = back,
                    onSave = vm::onSave,
                )
            }
        }
            is ImportRoute -> {
            val route = route
            val vm: ImportViewModel = koinViewModel(
                parameters = { parametersOf(route.providerId) },
            )
            val preview by vm.preview.collectAsStateWithLifecycle()
            val error by vm.error.collectAsStateWithLifecycle()
            val importing by vm.importing.collectAsStateWithLifecycle()
            val duplicatePrompt by vm.duplicatePrompt.collectAsStateWithLifecycle()
            val probeModels by vm.probeModels.collectAsStateWithLifecycle()
            val feedback = LocalAppFeedback.current
            val imported = stringResource(Res.string.feedback_imported)
            val clipboardFilled = stringResource(Res.string.feedback_clipboard_filled)
            val clipboardEmpty = stringResource(Res.string.feedback_clipboard_empty)
            ImportScreen(
                preview = preview,
                error = error,
                importing = importing,
                duplicatePrompt = duplicatePrompt,
                probeModels = probeModels,
                onBack = back,
                onParse = vm::parse,
                // 导入成功后先提示再退回：提示挂在 Shell 上，不受这一页退出组合影响。
                onConfirm = {
                    vm.confirm {
                        feedback?.post(AppFeedback(imported))
                        back()
                    }
                },
                onConfirmDuplicate = {
                    vm.confirmDuplicate {
                        feedback?.post(AppFeedback(imported))
                        back()
                    }
                },
                onDismissDuplicate = vm::dismissDuplicate,
                onProbeModelsChange = vm::setProbeModels,
                readClipboard = {
                    // 剪贴板为空时以前是静默无反应，用户会以为按钮坏了。
                    val text = vm.readClipboard()
                    feedback?.post(
                        AppFeedback(if (text.isNullOrEmpty()) clipboardEmpty else clipboardFilled),
                    )
                    text
                },
            )
        }
            is GroupsRoute -> {
            // 这是**第二个** ManageViewModel 实例（每条路由一个 ViewModelStore），所以它的
            // 事件与失败都得在这一页自己收：以前只收 groupError 那一条、而且统一念
            // "分组保存失败"，于是改名失败被告知"没能新建"，删除成功与排序没落库干脆没人开口。
            val vm: ManageViewModel = koinViewModel()
            val manage by vm.state.collectAsStateWithLifecycle()
            val feedback = LocalAppFeedback.current
            val groupAdded = stringResource(Res.string.feedback_group_added)
            val groupRenamed = stringResource(Res.string.feedback_group_renamed)
            val groupDeleted = stringResource(Res.string.feedback_group_deleted)
            val groupAddFailed = stringResource(Res.string.groups_add_failed)
            val groupRenameFailed = stringResource(Res.string.groups_rename_failed)
            val groupDeleteFailed = stringResource(Res.string.groups_delete_failed)
            val writeFailed = stringResource(Res.string.manage_write_failed)
            LaunchedEffect(vm) {
                vm.events.collect { event ->
                    when (event) {
                        // 批量删除只能在管理页发起，这一页删不了供应商，所以不会有这条。
                        is ManageViewModel.Event.ProvidersDeleted -> Unit
                        ManageViewModel.Event.GroupAdded -> feedback?.post(AppFeedback(groupAdded))
                        ManageViewModel.Event.GroupRenamed -> feedback?.post(AppFeedback(groupRenamed))
                        ManageViewModel.Event.GroupDeleted -> feedback?.post(AppFeedback(groupDeleted))
                        // 保存排序 / 改分组写不进去：列表看着像"排好了"，重进页面又变回去。
                        ManageViewModel.Event.WriteFailed -> feedback?.post(AppFeedback(writeFailed))
                    }
                }
            }
            LaunchedEffect(vm) {
                vm.groupError.collect { op ->
                    val message = when (op) {
                        ManageViewModel.GroupOp.Add -> groupAddFailed
                        ManageViewModel.GroupOp.Rename -> groupRenameFailed
                        ManageViewModel.GroupOp.Delete -> groupDeleteFailed
                    }
                    feedback?.post(AppFeedback(message))
                }
            }
            GroupsScreen(
                groups = manage.groups,
                providers = manage.providers,
                onBack = back,
                onAdd = vm::onAddGroup,
                onRename = vm::onRenameGroup,
                onDelete = vm::onDeleteGroup,
                onSetProviderGroup = vm::setProviderGroup,
                onReorderGroups = vm::reorderGroups,
                onReorderProviders = vm::reorderProviders,
            )
        }
        // 探测明细。入口在仪表盘的"查看明细"（有过一轮探测才画）。这一页只读：
        // 看上一轮结果、重试失败项。
            is ProbeRunRoute -> {
            val vm: ProbeRunViewModel = koinViewModel()
            val run by vm.state.collectAsStateWithLifecycle()
            val feedback = LocalAppFeedback.current
            val probeRetried = stringResource(Res.string.feedback_probe_retried)
            val probeRetryNothing = stringResource(Res.string.feedback_probe_retry_nothing)
            val probeStopped = stringResource(Res.string.feedback_probe_stopped)
            ProbeRunScreen(
                lastRun = run.lastRun,
                nowMs = run.nowMs,
                failed = run.failed,
                skipped = run.skipped,
                succeeded = run.succeeded,
                running = run.running,
                onBack = back,
                onRetryFailed = {
                    // 按引擎的返回值说：没接这一发（正在跑 / 锁定态 / 压根没有失败项）却念
                    // "正在重试失败项"，用户等到的就是一句没发生的事。
                    val started = vm.retryFailed()
                    feedback?.post(AppFeedback(if (started) probeRetried else probeRetryNothing))
                },
                onStopProbe = {
                    // 走 VM 的既有 cancel 路径（引擎侧 Job.cancel() + NonCancellable 收尾，
                    // 把 probe_runs 写成 cancelled 而不是留半截）。这一句只说"已经让它停了"，
                    // 本轮结果不再由 Shell 补发：那条流对 cancelled 一轮刻意不出声。
                    vm.cancelProbe()
                    feedback?.post(AppFeedback(probeStopped))
                },
                onOpenProvider = { id -> navigate(ProviderDetailRoute(id)) },
            )
        }
        }
    }

    // 一级页由 pager 承载：栈底那一条的内容就是整台 pager（三个 tab 平级、可左右滑），
    // 二级页照旧压栈盖在它上面。pager 留在组合里（栈底 scene 一直在场），所以从管理页
    // 压详情页走的还是同一个 NavDisplay 的进场动画，返回也直接落回原来那一页 tab。
    //
    // 这里不再有"一级页也走 NavDisplay 的横向过渡"那条路：tab 之间的位移由 pager 产生，
    // 栈底路由从头到尾不变，scene 不切换，也就不会出现"点设置却停在管理"那种中途重定向。
    val baseRoute = backStack.firstOrNull()

    VaultNavDisplay(
        backStack = routeSnapshot,
        onBack = { back() },
        style = style,
        exitDirection = exitDirection,
        modifier = modifier,
    ) { route ->
        if (route == baseRoute && topLevelIndexOf(route) >= 0) {
            HorizontalPager(
                state = pager.pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                // 每页的 rememberSaveable 由 pager 自己隔离：Pager 建在 LazyLayout 上，
                // 每个 item 都被 LazyLayoutItemContentFactory 包了一层 SaveableStateProvider，
                // 所以不需要再手工隔离。
                PageContent(TopLevelRoutes[page])
            }
        } else {
            PageContent(route)
        }
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
    val feedback = LocalAppFeedback.current
    val backup by vm.backup.collectAsStateWithLifecycle()
    val webDavConfig by vm.webDavConfig.collectAsStateWithLifecycle()
    val webDavBusy by vm.webDavBusy.collectAsStateWithLifecycle()
    val webDavChecking by vm.webDavChecking.collectAsStateWithLifecycle()

    val passphrasePrompt = stringResource(Res.string.sync_passphrase_prompt)
    val passphraseHint = stringResource(Res.string.sync_passphrase_hint)
    val passphraseRequired = stringResource(Res.string.sync_passphrase_required)
    val confirm = stringResource(Res.string.sync_confirm)
    val exported = stringResource(Res.string.sync_result_exported)
    val webDavTitle = stringResource(Res.string.sync_webdav_title)
    val webDavUrlInvalid = stringResource(Res.string.sync_webdav_url_invalid)
    val webDavInsecureRequired = stringResource(Res.string.sync_webdav_insecure_required)
    val webDavCredentialsRequired = stringResource(Res.string.sync_webdav_credentials_required)

    var pendingAction by remember { mutableStateOf<PendingSyncAction?>(null) }
    val passphraseState = rememberSecretTextFieldState()
    // 「默认沿用 PIN」的实现边界写在这里：口令得由用户亲手输，应用不保存 PIN。
    // 留空直接继续会产出一把用空口令加密的备份——之后用 PIN 永远解不开，比报错更糟。
    // 所以在确认时挡住空口令，把"为什么"说清楚。
    var passphraseError by remember { mutableStateOf<String?>(null) }

    var restoreModePicker by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    var webDavRestoreModePicker by remember { mutableStateOf(false) }
    var pendingWebDavPassword by remember { mutableStateOf<CharArray?>(null) }

    var showWebDavSettings by remember { mutableStateOf(false) }
    var webDavError by remember { mutableStateOf<String?>(null) }
    // 每次打开都从"挡住"开始：上次看过的明文不该替下一次做决定。
    var webDavPasswordRevealed by remember { mutableStateOf(false) }
    var allowInsecure by remember { mutableStateOf(false) }
    var remoteBackups by remember { mutableStateOf<List<String>?>(null) }
    // 只有用户自己点「刷新远端列表」才配那一条"远端备份：N 份"的 toast；
    // 进页面的自动拉取只更新列表。失败**仍然**照旧提示——那时它是唯一的信号。
    var remoteListRequested by remember { mutableStateOf(false) }
    // 走列表点进来的那一份。null = 走的是「从 WebDAV 恢复」那条老路（恢复最新）。
    var pendingWebDavRestoreFile by remember { mutableStateOf<String?>(null) }
    // 列表里被点开的那一份，以及「删除」二次确认盯着的那一份。两个是分开的状态：
    // 确认框取消要退回动作弹层，而不是把整个流程一起关掉。
    var pendingRemoteBackup by remember { mutableStateOf<UiRemoteBackup?>(null) }
    var pendingRemoteDelete by remember { mutableStateOf<UiRemoteBackup?>(null) }
    val webDavUrlState = rememberAppTextFieldState()
    val webDavDirectoryState = rememberAppTextFieldState()
    val webDavUsernameState = rememberSecretTextFieldState()
    val webDavPasswordState = rememberSecretTextFieldState()

    // 拉到的列表按"最近的在最前"排（引擎按文件名升序返回，时间戳就在文件名里）。
    // 不 remember：几行字符串的排序成本，比让时间标签停在第一次拉取那一刻更划算。
    val remoteRows = remoteBackups?.sortedDescending()?.map { name ->
        UiRemoteBackup(
            fileName = name,
            // 跨天的备份写完整日期而不是"N 天前"：一屏十几份时，"3 天前"认不出是哪一次，
            // 而挑备份要回答的正是"这是哪一天的那份"。
            label = WebDavEngine.backupEpochMillis(name)?.let { relativeLabelWithinDay(nowMillis(), it) } ?: name,
        )
    }

    // 配置就绪就拉一次：以前"远端到底有哪些包"得先点刷新才看得到，
    // 而这一页要回答的正是这个问题。失败仍走既有的那条例外提示，不另发明文案。
    LaunchedEffect(webDavConfig.isReady) {
        if (webDavConfig.isReady) vm.listWebDavBackups()
    }

    // 打开凭据弹层时才解密文回填（2026-09 反馈：改一次配置要重输一遍，忘了密码也看不到）。
    // 刻意等到打开之后再做：解一次字段级密文是挂起调用，不该为可能根本不看的弹层花。
    LaunchedEffect(showWebDavSettings) {
        if (!showWebDavSettings || !webDavConfig.hasCredentials) return@LaunchedEffect
        val stored = vm.storedCredentials() ?: return@LaunchedEffect
        try {
            webDavUsernameState.setText(stored.username)
            webDavPasswordState.setText(stored.password)
        } finally {
            stored.zeroize()
        }
        // 回填进来仍然是遮着的：看得见是"点一下眼睛"的结果，不是打开弹层的结果。
        webDavPasswordRevealed = false
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is SyncEvent.ExportSucceeded -> feedback?.post(AppFeedback(exported))
                // 三条失败提示都不带异常原文（原文里有 WebDAV 地址、文件名甚至凭据片段，
                // 摊在屏幕上等于把日志公开，还可能被截图）。原因由引擎写进 audit_log，
                // 这里只说"失败了 + 下一步去哪看"。
                SyncEvent.ExportFailed ->
                    feedback?.post(AppFeedback(getString(Res.string.sync_export_failed)))
                is SyncEvent.RestoreSucceeded ->
                    feedback?.post(AppFeedback(getString(Res.string.sync_result_restored, event.importedProviders)))
                SyncEvent.RestoreFailed ->
                    feedback?.post(AppFeedback(getString(Res.string.sync_restore_failed)))
                SyncEvent.WebDavConfigSaved -> {
                    remoteBackups = null
                    feedback?.post(AppFeedback(getString(Res.string.sync_result_webdav_configured)))
                }
                is SyncEvent.WebDavListSucceeded -> {
                    remoteBackups = event.names
                    if (remoteListRequested) {
                        remoteListRequested = false
                        feedback?.post(AppFeedback(getString(Res.string.sync_remote_count, event.names.size)))
                    }
                }
                is SyncEvent.WebDavUploadSucceeded ->
                    feedback?.post(
                        AppFeedback(
                            getString(
                                Res.string.sync_result_webdav_uploaded,
                                event.fileName,
                                event.prunedCount,
                            ),
                        ),
                    )
                SyncEvent.WebDavDeleted ->
                    feedback?.post(AppFeedback(getString(Res.string.sync_result_remote_deleted)))
                SyncEvent.WebDavFailed ->
                    feedback?.post(AppFeedback(getString(Res.string.sync_webdav_failed)))
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
        // 用户退出文件选择器（Android SAF 返回 null uri / iOS 的 documentPickerWasCancelled）。
        // 口令弹层是"等选完才收口"的那一层，不接这一句它就会留在屏幕上没人应答；
        // 与下面 onDismissRequest 做同样的收口，顺带把已经用不上的口令擦掉。
        onCancelled = {
            passphraseState.clear()
            passphraseError = null
            pendingAction = null
        },
    )

    SyncScreen(
        backup = backup,
        webDavConfig = webDavConfig,
        webDavBusy = webDavBusy,
        webDavChecking = webDavChecking,
        remoteBackups = remoteRows,
        onBack = onBack,
        onExport = { pendingAction = PendingSyncAction.Export },
        onImport = { pendingAction = PendingSyncAction.Import },
        onOpenWebDavSettings = {
            webDavUrlState.setText(webDavConfig.url)
            webDavDirectoryState.setText(webDavConfig.remoteDirectory)
            allowInsecure = webDavConfig.allowInsecure
            webDavUsernameState.clear()
            webDavPasswordState.clear()
            webDavPasswordRevealed = false
            webDavError = null
            showWebDavSettings = true
        },
        onUploadWebDav = { pendingAction = PendingSyncAction.WebDavUpload },
        onRestoreWebDav = {
            // 老入口 = 恢复最新那一份，清掉上一次的选中值，别让它替这一次做主。
            pendingWebDavRestoreFile = null
            pendingAction = PendingSyncAction.WebDavRestore
        },
        onPickRemoteBackup = { row ->
            // 点了不直接恢复：先弹层问"恢复还是删除"。误触一整行的代价不该是开始改库。
            pendingRemoteBackup = row
            pendingRemoteDelete = null
        },
        onRefreshWebDav = {
            remoteListRequested = true
            vm.listWebDavBackups()
        },
    )

    AppDialog(
        show = pendingAction != null,
        onDismissRequest = {
            passphraseState.clear()
            passphraseError = null
            pendingAction = null
        },
        title = passphrasePrompt,
        confirmText = confirm,
        onConfirm = {
            if (passphraseState.chars.isEmpty()) {
                // 空口令的备份之后用 PIN 解不开（Pbkdf2Kdf 只认输入），拦住比失败好。
                passphraseError = passphraseRequired
                return@AppDialog
            }
            passphraseError = null
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
            errorText = passphraseError,
        )
    }

    AppDialog(
        show = showWebDavSettings,
        onDismissRequest = {
            webDavUsernameState.clear()
            webDavPasswordState.clear()
            webDavPasswordRevealed = false
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
                webDavPasswordRevealed = false
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
        AppTextField(
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
            // 账号密码是用户自己填的 WebDAV 凭据，不是本机要保护的秘密：账号直接显示，
            // 密码默认挡住（旁边有人时不该亮着），点小眼睛可以看回明文，省得对着打错的串猜。
            concealed = !webDavPasswordRevealed,
            toggleConcealDescription = stringResource(
                if (webDavPasswordRevealed) {
                    Res.string.sync_webdav_password_hide
                } else {
                    Res.string.sync_webdav_password_reveal
                },
            ),
            onToggleConceal = { webDavPasswordRevealed = !webDavPasswordRevealed },
        )
        AppSwitchRow(
            title = stringResource(Res.string.sync_webdav_insecure_required),
            checked = allowInsecure,
            onCheckedChange = { allowInsecure = it },
            summary = stringResource(Res.string.sync_insecure_http_warning),
        )
    }

    // 列表里某一份被点开：恢复与删除两个动作放这一层（2026-09 反馈）。标题用那一份的
    // 日期、副文案用文件名——用户点的是哪一行，弹层就说哪一行，不需要再复述一遍"远端备份"。
    AppDialog(
        show = pendingRemoteBackup != null && pendingRemoteDelete == null,
        onDismissRequest = { pendingRemoteBackup = null },
        title = pendingRemoteBackup?.label.orEmpty(),
        summary = pendingRemoteBackup?.fileName.orEmpty(),
        dismissText = stringResource(Res.string.sync_cancel),
        onDismiss = { pendingRemoteBackup = null },
    ) {
        AppPreferenceGroup(inset = false) {
            AppActionRow(
                text = stringResource(Res.string.sync_remote_restore),
                enabled = !webDavBusy,
                onClick = {
                    val fileName = pendingRemoteBackup?.fileName ?: return@AppActionRow
                    pendingRemoteBackup = null
                    // 之后与老入口同一条路：要口令 → 选合并方式，只是恢复的是被点的那一份。
                    pendingWebDavRestoreFile = fileName
                    pendingAction = PendingSyncAction.WebDavRestore
                },
            )
            AppActionRow(
                text = stringResource(Res.string.sync_remote_delete),
                enabled = !webDavBusy,
                onClick = { pendingRemoteDelete = pendingRemoteBackup },
            )
        }
    }

    // 删除单独再确认一次：远端只留最新 10 份，删掉的那一份要是恰好是唯一没坏的一份，
    // 就没有下一次"从 WebDAV 恢复最新"可走了。
    AppDialog(
        show = pendingRemoteDelete != null,
        onDismissRequest = { pendingRemoteDelete = null },
        title = stringResource(Res.string.sync_remote_delete_confirm_title),
        summary = pendingRemoteDelete?.let { row ->
            stringResource(Res.string.sync_remote_delete_confirm_summary, row.fileName)
        }.orEmpty(),
        confirmText = stringResource(Res.string.sync_remote_delete),
        onConfirm = {
            pendingRemoteDelete?.let { vm.deleteWebDavBackup(it.fileName) }
            pendingRemoteDelete = null
            pendingRemoteBackup = null
        },
        dismissText = stringResource(Res.string.sync_cancel),
        onDismiss = { pendingRemoteDelete = null },
    )

    RestoreModePicker(
        show = restoreModePicker,
        onDismiss = {
            pendingRestore?.password?.zeroize()
            pendingRestore = null
            restoreModePicker = false
        },
        onChosen = { mode ->
            pendingRestore?.let {
                vm.restore(it.bytes, it.password, mode)
                it.password.zeroize()
            }
            pendingRestore = null
            restoreModePicker = false
        },
    )

    RestoreModePicker(
        show = webDavRestoreModePicker,
        onDismiss = {
            pendingWebDavPassword?.zeroize()
            pendingWebDavPassword = null
            pendingWebDavRestoreFile = null
            webDavRestoreModePicker = false
        },
        onChosen = { mode ->
            pendingWebDavPassword?.let {
                // 从列表点进来的就恢复那一份，别再"列一次取最大"——两次 PROPFIND 之间
                // 别人传了新备份的话，那样会把用户刚选的那一份换掉。
                val fileName = pendingWebDavRestoreFile
                if (fileName == null) {
                    vm.restoreLatestFromWebDav(it, mode)
                } else {
                    vm.restoreFromWebDav(fileName, it, mode)
                }
                it.zeroize()
            }
            pendingWebDavPassword = null
            pendingWebDavRestoreFile = null
            webDavRestoreModePicker = false
        },
    )

}


/**
 * 恢复模式选择：一段三选一 + 一次确认。
 *
 * 以前这里是三枚文本按钮（AppTextButton），点一下立刻写库——两个问题叠在一起：
 *
 * 1. 「覆盖」会先把本机库清空，而它当时**没有任何取消出口**（只能点弹层外或按返回，
 *    那等于让人靠猜来退出一个破坏性动作），按错一格就是数据没了；
 * 2. 三枚按钮也不表达"当前选了哪一档"。
 *
 * 现在选择与提交分开：默认落在「合并」（三档里唯一不丢东西的那一档，破坏性那档要主动选过去），
 * 确定才写入，取消/点外面都原样收回；选中「覆盖」时再单独确认一次，把要没的东西说清楚。
 *
 * 文案由调用方（composable 层）解析，本层不碰资源以外的事——但 [RestoreMode] 是引擎侧的枚举，
 * VM 读不到资源，所以档位仍然以枚举交给 [onChosen]。
 */
@Composable
private fun RestoreModePicker(
    show: Boolean,
    onDismiss: () -> Unit,
    onChosen: (RestoreMode) -> Unit,
) {
    // 顺序即分段切换的顺序，也是 indexOf 的依据；三档都在，少一档就没有"不丢东西"的默认了。
    val modes = listOf(RestoreMode.MERGE, RestoreMode.OVERWRITE, RestoreMode.ADD_ONLY)
    val labels = listOf(
        stringResource(Res.string.sync_mode_merge),
        stringResource(Res.string.sync_mode_overwrite),
        stringResource(Res.string.sync_mode_add_only),
    )
    // remember(show)：每次重新打开都回到默认档，上一次选中的「覆盖」不该替下一次做决定。
    var chosen by remember(show) { mutableStateOf(RestoreMode.MERGE) }
    var confirmOverwrite by remember(show) { mutableStateOf(false) }

    AppDialog(
        show = show && !confirmOverwrite,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.sync_restore_mode),
        summary = stringResource(Res.string.sync_restore_mode_summary),
        confirmText = stringResource(Res.string.sync_confirm),
        onConfirm = {
            if (chosen == RestoreMode.OVERWRITE) {
                confirmOverwrite = true
            } else {
                onChosen(chosen)
            }
        },
        dismissText = stringResource(Res.string.sync_cancel),
        onDismiss = onDismiss,
    ) {
        AppTabRow(
            tabs = labels,
            selectedIndex = modes.indexOf(chosen),
            onSelect = { index -> chosen = modes[index] },
        )
    }

    AppDialog(
        show = show && confirmOverwrite,
        onDismissRequest = { confirmOverwrite = false },
        title = stringResource(Res.string.sync_overwrite_confirm_title),
        summary = stringResource(Res.string.sync_overwrite_confirm_summary),
        confirmText = stringResource(Res.string.sync_confirm),
        onConfirm = { onChosen(RestoreMode.OVERWRITE) },
        // 这一层的取消只退回上一格：模式选择还开着，用户可以改选「合并」。
        dismissText = stringResource(Res.string.sync_cancel),
        onDismiss = { confirmOverwrite = false },
    )
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

/**
 * 把资源模板里的 `%1$d` 换成序号，得到「密钥 1」这样的默认名。
 *
 * 为什么不用 `String.format`：它是 **JVM 专有**的扩展，Kotlin/Native 上没有，
 * 用了 iOS 目标直接编译失败（2026-09-15 CI 的 ios job 就是这么红的）。
 * 这里的模板只有一个占位符，做一次朴素替换即可，也顺手避开了把数字按平台规则本地化的问题
 * ——序号是标识用的，不该被千分位之类影响。
 */
internal fun defaultNameFromTemplate(template: String, index: Int): String =
    template.replace("%1\$d", index.toString())
