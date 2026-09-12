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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.platform.PlatformBackHandler
import com.lc33.tokenvault.screens.model.KeyDraft
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppSecretTextField
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.miuix.rememberSecretTextFieldState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.auth_styles
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.balance_kinds
import tokenvault.shared.generated.resources.editor_allow_insecure
import tokenvault.shared.generated.resources.editor_auth_style
import tokenvault.shared.generated.resources.editor_auth_style_summary
import tokenvault.shared.generated.resources.editor_balance_kind
import tokenvault.shared.generated.resources.editor_balance_kind_summary
import tokenvault.shared.generated.resources.editor_balance_token
import tokenvault.shared.generated.resources.editor_balance_token_hint
import tokenvault.shared.generated.resources.editor_balance_user_id
import tokenvault.shared.generated.resources.editor_base_url
import tokenvault.shared.generated.resources.editor_base_url_hint
import tokenvault.shared.generated.resources.editor_name
import tokenvault.shared.generated.resources.editor_note
import tokenvault.shared.generated.resources.editor_path_override
import tokenvault.shared.generated.resources.editor_path_override_hint
import tokenvault.shared.generated.resources.editor_probe_balance
import tokenvault.shared.generated.resources.editor_probe_balance_summary
import tokenvault.shared.generated.resources.editor_probe_enabled
import tokenvault.shared.generated.resources.editor_probe_enabled_summary
import tokenvault.shared.generated.resources.editor_probe_reachability
import tokenvault.shared.generated.resources.editor_probe_reachability_summary
import tokenvault.shared.generated.resources.editor_probe_keys
import tokenvault.shared.generated.resources.editor_probe_keys_summary
import tokenvault.shared.generated.resources.editor_probe_model_reachability
import tokenvault.shared.generated.resources.editor_probe_model_reachability_summary
import tokenvault.shared.generated.resources.editor_probe_models
import tokenvault.shared.generated.resources.editor_probe_models_summary
import tokenvault.shared.generated.resources.editor_section_endpoint
import tokenvault.shared.generated.resources.detail_key_secret
import tokenvault.shared.generated.resources.editor_discard_confirm
import tokenvault.shared.generated.resources.editor_discard_summary
import tokenvault.shared.generated.resources.editor_discard_title
import tokenvault.shared.generated.resources.editor_save
import tokenvault.shared.generated.resources.editor_section_advanced
import tokenvault.shared.generated.resources.editor_section_balance
import tokenvault.shared.generated.resources.editor_section_basic
import tokenvault.shared.generated.resources.editor_section_client
import tokenvault.shared.generated.resources.editor_section_protocols
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
    baseUrlError: String?,
    onChange: (KeyDraft) -> Unit,
    onBaseUrlChange: () -> Unit,
    onBack: () -> Unit,
    onSave: (KeyDraft, CharArray?, CharArray?) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current

    val label = rememberAppTextFieldState(draft.label)
    val note = rememberAppTextFieldState(draft.note)
    val baseUrl = rememberAppTextFieldState(draft.baseUrl)
    val override = rememberAppTextFieldState(draft.pathOverrideAnthropic)
    val timeout = rememberAppTextFieldState(draft.timeoutSeconds)
    val balanceUserId = rememberAppTextFieldState(draft.balanceUserId)
    val secret = rememberSecretTextFieldState()
    val balanceToken = rememberSecretTextFieldState()
    val currentDraft by rememberUpdatedState(draft)
    val initialDraft = remember { draft }
    var showDiscard by remember { mutableStateOf(false) }
    val dirty = label.text != draft.label || note.text != draft.note || baseUrl.text != draft.baseUrl ||
        override.text != draft.pathOverrideAnthropic || timeout.text != draft.timeoutSeconds ||
        balanceUserId.text != draft.balanceUserId || draft != initialDraft

    LaunchedEffect(baseUrl.state) {
        snapshotFlow { baseUrl.text }.collect { onBaseUrlChange() }
    }

    PlatformBackHandler(enabled = dirty) { showDiscard = true }

    fun submit() {
        val next = currentDraft.copy(
            label = label.text.trim(),
            note = note.text.trim(),
            baseUrl = baseUrl.text.trim(),
            pathOverrideAnthropic = override.text.trim(),
            timeoutSeconds = timeout.text.trim(),
            balanceUserId = balanceUserId.text.trim(),
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
                                    onChange(draft.copy(protocols = next))
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

            item { SectionTitle(text = stringResource(Res.string.editor_section_balance)) }
            item {
                AppPreferenceGroup {
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_balance_kind),
                        summary = stringResource(Res.string.editor_balance_kind_summary),
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
                    if (draft.balanceKindIndex == 1) {
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
                }
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
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
