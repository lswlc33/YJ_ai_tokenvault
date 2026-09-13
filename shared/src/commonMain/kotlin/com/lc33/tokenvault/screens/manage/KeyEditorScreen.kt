package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.platform.PlatformBackHandler
import com.lc33.tokenvault.screens.model.KeyDraft
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDivider
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSecretTextField
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.miuix.rememberSecretTextFieldState
import com.lc33.tokenvault.ui.shell.KeyEditorViewModel
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.auth_styles
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.balance_kinds
import tokenvault.shared.generated.resources.detail_add_model
import tokenvault.shared.generated.resources.detail_key_secret
import tokenvault.shared.generated.resources.detail_model_delete
import tokenvault.shared.generated.resources.groups_delete
import tokenvault.shared.generated.resources.detail_models_empty_auto
import tokenvault.shared.generated.resources.detail_models_empty_manual
import tokenvault.shared.generated.resources.detail_models_refresh
import tokenvault.shared.generated.resources.detail_models_section
import tokenvault.shared.generated.resources.editor_allow_insecure
import tokenvault.shared.generated.resources.editor_auth_style
import tokenvault.shared.generated.resources.editor_auth_style_summary
import tokenvault.shared.generated.resources.editor_balance_custom_currency
import tokenvault.shared.generated.resources.editor_balance_custom_method
import tokenvault.shared.generated.resources.editor_balance_custom_path
import tokenvault.shared.generated.resources.editor_balance_custom_used_path
import tokenvault.shared.generated.resources.editor_balance_custom_value_path
import tokenvault.shared.generated.resources.editor_balance_enabled
import tokenvault.shared.generated.resources.editor_balance_enabled_summary
import tokenvault.shared.generated.resources.editor_balance_kind
import tokenvault.shared.generated.resources.editor_balance_kind_summary
import tokenvault.shared.generated.resources.editor_balance_token
import tokenvault.shared.generated.resources.editor_balance_token_hint
import tokenvault.shared.generated.resources.editor_balance_user_id
import tokenvault.shared.generated.resources.editor_base_url
import tokenvault.shared.generated.resources.editor_base_url_hint
import tokenvault.shared.generated.resources.editor_discard_confirm
import tokenvault.shared.generated.resources.editor_discard_summary
import tokenvault.shared.generated.resources.editor_discard_title
import tokenvault.shared.generated.resources.editor_error_invalid_timeout
import tokenvault.shared.generated.resources.editor_error_missing_secret
import tokenvault.shared.generated.resources.editor_error_no_protocols
import tokenvault.shared.generated.resources.editor_error_save_failed
import tokenvault.shared.generated.resources.editor_models_auto
import tokenvault.shared.generated.resources.editor_models_auto_summary
import tokenvault.shared.generated.resources.editor_models_last_sync
import tokenvault.shared.generated.resources.editor_models_manual_hint
import tokenvault.shared.generated.resources.editor_models_sync_now
import tokenvault.shared.generated.resources.editor_name
import tokenvault.shared.generated.resources.editor_note
import tokenvault.shared.generated.resources.editor_path_override
import tokenvault.shared.generated.resources.editor_path_override_hint
import tokenvault.shared.generated.resources.editor_probe_balance
import tokenvault.shared.generated.resources.editor_probe_balance_summary
import tokenvault.shared.generated.resources.editor_probe_enabled
import tokenvault.shared.generated.resources.editor_probe_enabled_summary
import tokenvault.shared.generated.resources.editor_probe_keys
import tokenvault.shared.generated.resources.editor_probe_keys_summary
import tokenvault.shared.generated.resources.editor_probe_model_reachability
import tokenvault.shared.generated.resources.editor_probe_model_reachability_summary
import tokenvault.shared.generated.resources.editor_probe_quick_model
import tokenvault.shared.generated.resources.editor_probe_quick_model_summary
import tokenvault.shared.generated.resources.editor_probe_models
import tokenvault.shared.generated.resources.editor_probe_models_summary
import tokenvault.shared.generated.resources.editor_probe_reachability
import tokenvault.shared.generated.resources.editor_probe_reachability_summary
import tokenvault.shared.generated.resources.editor_save
import tokenvault.shared.generated.resources.editor_section_advanced
import tokenvault.shared.generated.resources.editor_section_balance
import tokenvault.shared.generated.resources.editor_section_basic
import tokenvault.shared.generated.resources.editor_section_client
import tokenvault.shared.generated.resources.editor_section_endpoint
import tokenvault.shared.generated.resources.editor_timeout
import tokenvault.shared.generated.resources.editor_timeout_hint
import tokenvault.shared.generated.resources.key_enabled
import tokenvault.shared.generated.resources.key_settings_title
import tokenvault.shared.generated.resources.settings_profiles

/** Key 设置页：所有会影响这把 Key 请求行为的配置都在这里。 */
@Composable
fun KeyEditorScreen(
    draft: KeyDraft,
    profileNames: List<String>,
    models: List<UiModelRow>,
    nowMs: Long,
    baseUrlError: String?,
    saveError: KeyEditorViewModel.SaveError?,
    saving: Boolean,
    onChange: (KeyDraft) -> Unit,
    onBaseUrlChange: () -> Unit,
    onAddModel: (String, Protocol) -> Unit,
    onUpdateModel: (Long, String, Protocol, String?, Boolean) -> Unit,
    onDeleteModel: (Long) -> Unit,
    onRefreshModels: () -> Unit,
    onBack: () -> Unit,
    onSave: (KeyDraft, CharArray?, CharArray?) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val palette = LocalStatusPalette.current

    val label = rememberAppTextFieldState(draft.label)
    val note = rememberAppTextFieldState(draft.note)
    val baseUrl = rememberAppTextFieldState(draft.baseUrl)
    val override = rememberAppTextFieldState(draft.pathOverrideAnthropic)
    val timeout = rememberAppTextFieldState(draft.timeoutSeconds)
    val balanceUserId = rememberAppTextFieldState(draft.balanceUserId)
    val balanceMethod = rememberAppTextFieldState(draft.balanceMethod)
    val balancePath = rememberAppTextFieldState(draft.balancePath)
    val balanceValuePath = rememberAppTextFieldState(draft.balanceValuePath)
    val balanceUsedPath = rememberAppTextFieldState(draft.balanceUsedPath)
    val balanceCurrency = rememberAppTextFieldState(draft.balanceCurrency)
    val secret = rememberSecretTextFieldState()
    val balanceToken = rememberSecretTextFieldState()
    val currentDraft by rememberUpdatedState(draft)
    val initialDraft = remember { draft }
    var showDiscard by remember { mutableStateOf(false) }
    var secretError by remember { mutableStateOf(false) }
    var timeoutError by remember { mutableStateOf(false) }
    var addModel by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<UiModelRow?>(null) }
    var pendingDeleteModelId by remember { mutableStateOf<Long?>(null) }

    val dirty = label.text != draft.label || note.text != draft.note || baseUrl.text != draft.baseUrl ||
        override.text != draft.pathOverrideAnthropic || timeout.text != draft.timeoutSeconds ||
        balanceUserId.text != draft.balanceUserId || balanceMethod.text != draft.balanceMethod ||
        balancePath.text != draft.balancePath || balanceValuePath.text != draft.balanceValuePath ||
        balanceUsedPath.text != draft.balanceUsedPath || balanceCurrency.text != draft.balanceCurrency ||
        draft != initialDraft

    LaunchedEffect(baseUrl.state) {
        snapshotFlow { baseUrl.text }.collect { onBaseUrlChange() }
    }

    PlatformBackHandler(enabled = dirty && !saving) { showDiscard = true }

    fun submit() {
        val missingSecret = draft.id == 0L && secret.text.isBlank()
        val timeoutText = timeout.text.trim()
        val badTimeout = timeoutText.isNotEmpty() && (timeoutText.toIntOrNull() == null || timeoutText.toInt() <= 0)
        secretError = missingSecret
        timeoutError = badTimeout
        if (missingSecret || badTimeout || draft.protocols.isEmpty()) return
        val next = currentDraft.copy(
            label = label.text.trim(),
            note = note.text.trim(),
            baseUrl = baseUrl.text.trim(),
            pathOverrideAnthropic = override.text.trim(),
            timeoutSeconds = timeoutText,
            balanceUserId = balanceUserId.text.trim(),
            balanceMethod = balanceMethod.text.trim(),
            balancePath = balancePath.text.trim(),
            balanceValuePath = balanceValuePath.text.trim(),
            balanceUsedPath = balanceUsedPath.text.trim(),
            balanceCurrency = balanceCurrency.text.trim(),
        )
        onSave(
            next,
            secret.chars.takeIf { it.isNotEmpty() },
            balanceToken.chars.takeIf { it.isNotEmpty() },
        )
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.key_settings_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = { if (dirty) showDiscard = true else onBack() },
                    )
                },
                actions = {
                    AppIconButton(
                        icon = AppIcon.Ok,
                        contentDescription = stringResource(Res.string.editor_save),
                        onClick = ::submit,
                        enabled = !saving,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            item { SectionTitle(text = stringResource(Res.string.editor_section_basic)) }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    AppTextField(state = label, label = stringResource(Res.string.editor_name))
                    AppTextField(state = note, label = stringResource(Res.string.editor_note))
                    AppSecretTextField(
                        state = secret,
                        label = stringResource(Res.string.detail_key_secret),
                        errorText = if (secretError) stringResource(Res.string.editor_error_missing_secret) else null,
                    )
                }
            }
            item {
                AppPreferenceGroup {
                    AppSwitchRow(
                        title = stringResource(Res.string.key_enabled),
                        checked = draft.enabled,
                        onCheckedChange = { onChange(draft.copy(enabled = it)) },
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_endpoint)) }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    AppTextField(
                        state = baseUrl,
                        label = stringResource(Res.string.editor_base_url),
                        supportingText = stringResource(Res.string.editor_base_url_hint),
                        errorText = baseUrlError,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)) {
                        Protocol.entries.forEach { protocol ->
                            val selected = protocol in draft.protocols
                            AppFilterChip(
                                text = protocolLabel(protocol),
                                selected = selected,
                                onClick = {
                                    val next = if (selected) draft.protocols - protocol else draft.protocols + protocol
                                    if (next.isNotEmpty()) onChange(draft.copy(protocols = next))
                                },
                            )
                        }
                    }
                }
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_client)) }
            item {
                AppPreferenceGroup {
                    AppDropdownRow(
                        title = stringResource(Res.string.settings_profiles),
                        items = profileNames,
                        selectedIndex = draft.profileIndex,
                        onSelect = { onChange(draft.copy(profileIndex = it)) },
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_advanced)) }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    AppTextField(
                        state = override,
                        label = stringResource(Res.string.editor_path_override),
                        supportingText = stringResource(Res.string.editor_path_override_hint),
                    )
                    AppTextField(
                        state = timeout,
                        label = stringResource(Res.string.editor_timeout),
                        supportingText = stringResource(Res.string.editor_timeout_hint),
                        errorText = if (timeoutError) stringResource(Res.string.editor_error_invalid_timeout) else null,
                    )
                }
            }
            item {
                AppPreferenceGroup {
                    AppDropdownRow(
                        title = stringResource(Res.string.editor_auth_style),
                        summary = stringResource(Res.string.editor_auth_style_summary),
                        items = stringArrayResource(Res.array.auth_styles).toList(),
                        selectedIndex = draft.authStyleIndex,
                        onSelect = { onChange(draft.copy(authStyleIndex = it)) },
                    )
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_allow_insecure),
                        checked = draft.allowInsecure,
                        onCheckedChange = { onChange(draft.copy(allowInsecure = it)) },
                    )
                }
            }

            if (draft.id != 0L) {
                item { SectionTitle(text = stringResource(Res.string.detail_models_section)) }
                item {
                    AppPreferenceGroup {
                        AppSwitchRow(
                            title = stringResource(Res.string.editor_models_auto),
                            summary = stringResource(Res.string.editor_models_auto_summary),
                            checked = draft.probeModels,
                            onCheckedChange = { onChange(draft.copy(probeModels = it)) },
                        )
                    }
                }
                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.screenPadding),
                    ) {
                        if (draft.probeModels) {
                            val lastSeen = models.mapNotNull { it.lastSeenAt }.maxOrNull()
                            AppText(
                                text = if (lastSeen == null) {
                                    stringResource(Res.string.detail_models_empty_auto)
                                } else {
                                    stringResource(Res.string.editor_models_last_sync, relativeLabel(nowMs, lastSeen))
                                },
                                style = AppTextStyle.Secondary,
                                color = appSecondaryTextColor,
                            )
                            AppActionRow(
                                text = stringResource(Res.string.editor_models_sync_now),
                                onClick = onRefreshModels,
                                modifier = Modifier.padding(top = tokens.itemSpacing),
                            )
                        } else {
                            AppText(
                                text = stringResource(Res.string.editor_models_manual_hint),
                                style = AppTextStyle.Footnote,
                                color = appSecondaryTextColor,
                            )
                            if (models.isEmpty()) {
                                AppText(
                                    text = stringResource(Res.string.detail_models_empty_manual),
                                    style = AppTextStyle.Secondary,
                                    color = appSecondaryTextColor,
                                    modifier = Modifier.padding(top = tokens.itemSpacing),
                                )
                            } else {
                                models.forEachIndexed { index, model ->
                                    ModelRow(
                                        row = model,
                                        onClick = { editingModel = model },
                                        onProbe = null,
                                    )
                                    if (index != models.lastIndex) AppDivider()
                                }
                            }
                            AppActionRow(
                                text = stringResource(Res.string.detail_add_model),
                                onClick = { addModel = true },
                                modifier = Modifier.padding(top = tokens.itemSpacing),
                            )
                        }
                    }
                }

            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_balance)) }
            item {
                AppPreferenceGroup {
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_balance_enabled),
                        summary = stringResource(Res.string.editor_balance_enabled_summary),
                        checked = draft.balanceKindIndex != 0,
                        onCheckedChange = { enabled ->
                            onChange(draft.copy(balanceKindIndex = if (enabled) 1 else 0))
                        },
                    )
                    AppDropdownRow(
                        title = stringResource(Res.string.editor_balance_kind),
                        summary = stringResource(Res.string.editor_balance_kind_summary),
                        items = stringArrayResource(Res.array.balance_kinds).toList(),
                        selectedIndex = draft.balanceKindIndex,
                        onSelect = { onChange(draft.copy(balanceKindIndex = it)) },
                        enabled = draft.balanceKindIndex != 0,
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    when (draft.balanceKindIndex) {
                        1 -> {
                            AppSecretTextField(
                                state = balanceToken,
                                label = stringResource(Res.string.editor_balance_token),
                                supportingText = stringResource(Res.string.editor_balance_token_hint),
                            )
                            AppTextField(
                                state = balanceUserId,
                                label = stringResource(Res.string.editor_balance_user_id),
                            )
                        }
                        6 -> {
                            AppTextField(state = balanceMethod, label = stringResource(Res.string.editor_balance_custom_method))
                            AppTextField(state = balancePath, label = stringResource(Res.string.editor_balance_custom_path))
                            AppTextField(
                                state = balanceValuePath,
                                label = stringResource(Res.string.editor_balance_custom_value_path),
                            )
                            AppTextField(
                                state = balanceUsedPath,
                                label = stringResource(Res.string.editor_balance_custom_used_path),
                            )
                            AppTextField(
                                state = balanceCurrency,
                                label = stringResource(Res.string.editor_balance_custom_currency),
                            )
                        }
                    }
                }
            }

            item { SectionTitle(text = stringResource(Res.string.editor_probe_enabled)) }
            item {
                AppPreferenceGroup {
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_probe_enabled),
                        summary = stringResource(Res.string.editor_probe_enabled_summary),
                        checked = draft.probeEnabled,
                        onCheckedChange = { onChange(draft.copy(probeEnabled = it)) },
                    )
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_probe_reachability),
                        summary = stringResource(Res.string.editor_probe_reachability_summary),
                        checked = draft.probeReachability,
                        onCheckedChange = { onChange(draft.copy(probeReachability = it)) },
                        enabled = draft.probeEnabled,
                    )
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_probe_keys),
                        summary = stringResource(Res.string.editor_probe_keys_summary),
                        checked = draft.probeKeys,
                        onCheckedChange = { onChange(draft.copy(probeKeys = it)) },
                        enabled = draft.probeEnabled,
                    )
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_probe_balance),
                        summary = stringResource(Res.string.editor_probe_balance_summary),
                        checked = draft.probeBalance,
                        onCheckedChange = { onChange(draft.copy(probeBalance = it)) },
                        enabled = draft.probeEnabled,
                    )
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_probe_models),
                        summary = stringResource(Res.string.editor_probe_models_summary),
                        checked = draft.probeModels,
                        onCheckedChange = { onChange(draft.copy(probeModels = it)) },
                        enabled = draft.probeEnabled,
                    )
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_probe_model_reachability),
                        summary = stringResource(Res.string.editor_probe_model_reachability_summary),
                        checked = draft.probeModelReachability,
                        onCheckedChange = { onChange(draft.copy(probeModelReachability = it)) },
                        enabled = draft.probeEnabled,
                    )
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_probe_quick_model),
                        summary = stringResource(Res.string.editor_probe_quick_model_summary),
                        checked = draft.probeQuickModel,
                        onCheckedChange = { onChange(draft.copy(probeQuickModel = it)) },
                        enabled = draft.probeEnabled && draft.probeModelReachability,
                    )
                }
            }

            saveError?.let { error ->
                item {
                    AppText(
                        text = stringResource(
                            when (error) {
                                KeyEditorViewModel.SaveError.MissingSecret -> Res.string.editor_error_missing_secret
                                KeyEditorViewModel.SaveError.InvalidTimeout -> Res.string.editor_error_invalid_timeout
                                KeyEditorViewModel.SaveError.NoProtocols -> Res.string.editor_error_no_protocols
                                KeyEditorViewModel.SaveError.SaveFailed -> Res.string.editor_error_save_failed
                            },
                        ),
                        style = AppTextStyle.Secondary,
                        color = palette.error,
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }

    ModelDialog(
        keyId = if (addModel || editingModel != null) draft.id.takeIf { it != 0L } else null,
        editing = editingModel,
        protocols = draft.protocols.toList().ifEmpty { listOf(Protocol.CHAT) },
        onDismiss = {
            addModel = false
            editingModel = null
        },
        onConfirm = { keyId, modelId, protocol, displayName, enabled ->
            val editing = editingModel
            if (editing == null) {
                onAddModel(modelId, protocol)
            } else {
                onUpdateModel(editing.id, modelId, protocol, displayName, enabled)
            }
            addModel = false
            editingModel = null
        },
        onRequestDelete = { model ->
            pendingDeleteModelId = model.id
            editingModel = null
        },
    )

    AppDialog(
        show = pendingDeleteModelId != null,
        onDismissRequest = { pendingDeleteModelId = null },
        title = stringResource(Res.string.detail_model_delete),
        confirmText = stringResource(Res.string.groups_delete),
        onConfirm = {
            pendingDeleteModelId?.let(onDeleteModel)
            pendingDeleteModelId = null
        },
    )

    AppDialog(
        show = showDiscard,
        onDismissRequest = { showDiscard = false },
        title = stringResource(Res.string.editor_discard_title),
        summary = stringResource(Res.string.editor_discard_summary),
        confirmText = stringResource(Res.string.editor_discard_confirm),
        onConfirm = {
            showDiscard = false
            onBack()
        },
    )
}
