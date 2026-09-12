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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lc33.tokenvault.screens.model.KeyDetailUiState
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.common.labelOf
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDialog
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
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.detail_edit_cd
import tokenvault.shared.generated.resources.detail_key_reveal_hint
import tokenvault.shared.generated.resources.detail_models_refresh
import tokenvault.shared.generated.resources.detail_key_balance_value
import tokenvault.shared.generated.resources.detail_models_section
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
    onRefreshModels: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val key = state.key
    var pendingDelete by remember { mutableStateOf(false) }

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
                }
            }

            item { SectionTitle(text = stringResource(Res.string.detail_key_connection)) }
            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                ) {
                    AppText(text = key.settings.apiBaseUrl, style = AppTextStyle.Body)
                    Row(
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                    ) {
                        key.settings.protocols.forEach { AppChip(text = protocolLabel(it)) }
                    }
                    AppText(
                        text = key.settings.authStyle,
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                    key.settings.timeoutSeconds?.let {
                        AppText(
                            text = "$it s",
                            style = AppTextStyle.Footnote,
                            color = appSecondaryTextColor,
                        )
                    }
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
                    state.models.take(5).forEach { model ->
                        AppText(
                            text = model.modelId,
                            style = AppTextStyle.Body,
                            modifier = Modifier.padding(top = tokens.itemSpacing),
                        )
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
}
