package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.screens.model.KeyDetailUiState
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.common.labelOf
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDivider
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.common_off
import tokenvault.shared.generated.resources.common_on
import tokenvault.shared.generated.resources.detail_key_connection_allow_http
import tokenvault.shared.generated.resources.detail_key_connection_auth
import tokenvault.shared.generated.resources.detail_key_connection_base_url
import tokenvault.shared.generated.resources.detail_key_connection_profile
import tokenvault.shared.generated.resources.detail_key_connection_protocol
import tokenvault.shared.generated.resources.detail_key_connection_timeout
import tokenvault.shared.generated.resources.detail_key_timeout_value
import tokenvault.shared.generated.resources.detail_key_timeout_default
import tokenvault.shared.generated.resources.detail_key_view
import tokenvault.shared.generated.resources.detail_model_protocol
import tokenvault.shared.generated.resources.detail_models_empty_auto
import tokenvault.shared.generated.resources.detail_models_empty_manual
import tokenvault.shared.generated.resources.editor_profile_default
import tokenvault.shared.generated.resources.manage_source_discovered
import tokenvault.shared.generated.resources.manage_source_manual
import tokenvault.shared.generated.resources.detail_edit_cd
import tokenvault.shared.generated.resources.detail_key_reveal_hint
import tokenvault.shared.generated.resources.detail_models_refresh
import tokenvault.shared.generated.resources.detail_key_balance_value
import tokenvault.shared.generated.resources.detail_models_section
import tokenvault.shared.generated.resources.detail_models_source
import tokenvault.shared.generated.resources.detail_key_actions
import tokenvault.shared.generated.resources.detail_key_connection
import tokenvault.shared.generated.resources.detail_probe_key
import tokenvault.shared.generated.resources.editor_note
import tokenvault.shared.generated.resources.groups_delete
import tokenvault.shared.generated.resources.key_sort_down
import tokenvault.shared.generated.resources.key_sort_up
import tokenvault.shared.generated.resources.secret_copy_cd

/** Key 展示页：查看一把 Key、它的行为摘要、模型与操作。 */
@Composable
fun KeyDetailScreen(
    state: KeyDetailUiState,
    revealedText: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onReveal: () -> Unit,
    onCopyRevealed: () -> Unit,
    onCloseReveal: () -> Unit,
    onProbe: () -> Unit,
    onProbeModel: (String, Protocol) -> Unit,
    onRefreshModels: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val key = state.key
    var pendingDelete by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<UiModelRow?>(null) }

    LaunchedEffect(revealedText) {
        if (revealedText != null) {
            delay(30_000)
            onCloseReveal()
        }
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = key.label.ifBlank { "#" + key.sortOrder },
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = onBack,
                    )
                },
                actions = {
                    AppIconButton(
                        icon = AppIcon.Refresh,
                        contentDescription = stringResource(Res.string.detail_models_refresh),
                        onClick = onRefreshModels,
                    )
                    AppIconButton(
                        icon = AppIcon.Edit,
                        contentDescription = stringResource(Res.string.detail_edit_cd),
                        onClick = onEdit,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                ) {
                    AppText(text = key.masked, style = AppTextStyle.Body)
                    if (key.note.isNotBlank()) {
                        AppText(
                            text = stringResource(Res.string.editor_note) + ": " + key.note,
                            style = AppTextStyle.Footnote,
                            color = appSecondaryTextColor,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(color = colorOf(key.health), label = labelOf(key.health))
                        key.latencyMs?.let {
                            AppText(
                                text = "$it ms",
                                style = AppTextStyle.Footnote,
                                color = appSecondaryTextColor,
                            )
                        }
                        key.checkedAt?.let {
                            AppText(
                                text = relativeLabel(state.nowMs, it),
                                style = AppTextStyle.Footnote,
                                color = appSecondaryTextColor,
                            )
                        }
                    }
                    key.balance?.let {
                        AppText(
                            text = stringResource(
                                Res.string.detail_key_balance_value,
                                it.currency,
                                it.amount,
                            ),
                            style = AppTextStyle.Title,
                            modifier = Modifier.padding(top = tokens.itemSpacing),
                        )
                    }
                    AppActionRow(
                        text = stringResource(Res.string.detail_key_view),
                        onClick = onReveal,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.detail_key_connection)) }
            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                ) {
                    ConnectionField(
                        label = stringResource(Res.string.detail_key_connection_base_url),
                        value = key.settings.apiBaseUrl,
                        mono = true,
                    )
                    AppText(
                        text = stringResource(Res.string.detail_key_connection_protocol),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                    ) {
                        key.settings.protocols.forEach { AppChip(text = protocolLabel(it)) }
                    }
                    ConnectionField(
                        label = stringResource(Res.string.detail_key_connection_auth),
                        value = key.settings.authStyle,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                    ConnectionField(
                        label = stringResource(Res.string.detail_key_connection_profile),
                        value = key.settings.clientProfileName
                            ?: stringResource(Res.string.editor_profile_default),
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                    ConnectionField(
                        label = stringResource(Res.string.detail_key_connection_timeout),
                        value = key.settings.timeoutSeconds?.let {
                            stringResource(Res.string.detail_key_timeout_value, it)
                        } ?: stringResource(Res.string.detail_key_timeout_default),
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                    ConnectionField(
                        label = stringResource(Res.string.detail_key_connection_allow_http),
                        value = stringResource(
                            if (key.settings.allowInsecure) Res.string.common_on else Res.string.common_off,
                        ),
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                }
            }

            item {
                SectionTitle(text = stringResource(Res.string.detail_models_section))
            }
            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppText(
                            text = state.models.size.toString(),
                            style = AppTextStyle.Title,
                            modifier = Modifier.weight(1f),
                        )
                        AppIconButton(
                            icon = AppIcon.Refresh,
                            contentDescription = stringResource(Res.string.detail_models_refresh),
                            onClick = onRefreshModels,
                        )
                    }
                    if (state.models.isEmpty()) {
                        AppText(
                            text = stringResource(
                                if (key.settings.probeModels) {
                                    Res.string.detail_models_empty_auto
                                } else {
                                    Res.string.detail_models_empty_manual
                                },
                            ),
                            style = AppTextStyle.Secondary,
                            color = appSecondaryTextColor,
                            modifier = Modifier.padding(top = tokens.itemSpacing),
                        )
                    } else {
                        state.models.forEachIndexed { index, model ->
                            ModelRow(
                                row = model,
                                onClick = { selectedModel = model },
                                onProbe = null,
                                onLongPress = if (key.settings.probeQuickModel) {
                                    {
                                        Protocol.fromWireName(model.protocol)?.let { protocol ->
                                            onProbeModel(model.modelId, protocol)
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                            if (index != state.models.lastIndex) {
                                AppDivider()
                            }
                        }
                    }
                }
            }

            item {
                SectionTitle(text = stringResource(Res.string.detail_key_actions))
            }
            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                ) {
                    AppActionRow(text = stringResource(Res.string.secret_copy_cd), onClick = onReveal)
                    AppActionRow(text = stringResource(Res.string.detail_probe_key), onClick = onProbe)
                    AppActionRow(text = stringResource(Res.string.key_sort_up), onClick = onMoveUp)
                    AppActionRow(text = stringResource(Res.string.key_sort_down), onClick = onMoveDown)
                    AppActionRow(text = stringResource(Res.string.groups_delete), onClick = { pendingDelete = true })
                }
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }

    AppBottomSheet(
        show = revealedText != null,
        onDismissRequest = onCloseReveal,
        title = key.label.ifBlank { key.masked },
    ) {
        AppText(text = revealedText.orEmpty(), style = AppTextStyle.Body)
        AppText(
            text = stringResource(Res.string.detail_key_reveal_hint),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
        )
        AppActionRow(
            text = stringResource(Res.string.secret_copy_cd),
            onClick = onCopyRevealed,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    AppDialog(
        show = pendingDelete,
        onDismissRequest = { pendingDelete = false },
        title = stringResource(Res.string.groups_delete),
        confirmText = stringResource(Res.string.groups_delete),
        onConfirm = {
            pendingDelete = false
            onDelete()
        },
    )

    AppBottomSheet(
        show = selectedModel != null,
        onDismissRequest = { selectedModel = null },
        title = selectedModel?.modelId.orEmpty(),
    ) {
        val model = selectedModel ?: return@AppBottomSheet
        ConnectionField(
            label = stringResource(Res.string.detail_model_protocol),
            value = protocolLabel(model.protocol),
        )
        ConnectionField(
            label = stringResource(Res.string.detail_models_source),
            value = stringResource(
                if (model.source == com.lc33.tokenvault.screens.model.UiModelSource.Manual) {
                    Res.string.manage_source_manual
                } else {
                    Res.string.manage_source_discovered
                },
            ),
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        Row(
            modifier = Modifier.padding(top = tokens.itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = colorOf(model.health), label = labelOf(model.health))
        }
    }
}

@Composable
private fun ConnectionField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val tokens = LocalAppTokens.current
    AppText(
        text = label,
        style = AppTextStyle.Footnote,
        color = appSecondaryTextColor,
        modifier = modifier,
    )
    AppText(
        text = value,
        style = AppTextStyle.Body,
        fontFamily = if (mono) tokens.monoFontFamily else null,
    )
}
