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
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconMenu
import com.lc33.tokenvault.ui.miuix.AppMenuGroup
import com.lc33.tokenvault.ui.miuix.AppMenuItem
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.AppValueRow
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
import tokenvault.shared.generated.resources.detail_key_more_cd
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
                    // 排序、探测、删除都收进「更多」：它们是低频动作，铺成卡片会把
                    // 详情页正文（连接信息、模型）推到第二屏。菜单点完就收起
                    // （collapseOnSelection 默认 true），一次只做一件事。
                    AppIconMenu(
                        icon = AppIcon.More,
                        contentDescription = stringResource(Res.string.detail_key_more_cd),
                        collapseOnSelection = true,
                        groups = listOf(
                            AppMenuGroup(
                                items = listOf(
                                    AppMenuItem(
                                        text = stringResource(Res.string.detail_probe_key),
                                        onClick = onProbe,
                                    ),
                                ),
                            ),
                            AppMenuGroup(
                                items = listOf(
                                    AppMenuItem(
                                        text = stringResource(Res.string.key_sort_up),
                                        enabled = state.canMoveUp,
                                        onClick = onMoveUp,
                                    ),
                                    AppMenuItem(
                                        text = stringResource(Res.string.key_sort_down),
                                        enabled = state.canMoveDown,
                                        onClick = onMoveDown,
                                    ),
                                ),
                            ),
                            AppMenuGroup(
                                items = listOf(
                                    AppMenuItem(
                                        text = stringResource(Res.string.groups_delete),
                                        onClick = { pendingDelete = true },
                                    ),
                                ),
                            ),
                        ),
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
                        inset = false,
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.detail_key_connection)) }
            item {
                AppPreferenceGroup {
                    AppValueRow(
                        title = stringResource(Res.string.detail_key_connection_base_url),
                        value = key.settings.apiBaseUrl,
                        stacked = true,
                        mono = true,
                    )
                    AppValueRow(
                        title = stringResource(Res.string.detail_key_connection_protocol),
                        // protocolLabel 是 @Composable（要读资源），不能塞进 joinToString 的
                        // lambda 里——那是非 Composable 上下文。先逐项取出来再拼。
                        value = key.settings.protocols
                            .map { protocolLabel(it) }
                            .joinToString("  "),
                    )
                    AppValueRow(
                        title = stringResource(Res.string.detail_key_connection_auth),
                        value = key.settings.authStyle,
                    )
                    AppValueRow(
                        title = stringResource(Res.string.detail_key_connection_profile),
                        value = key.settings.clientProfileName
                            ?: stringResource(Res.string.editor_profile_default),
                    )
                    AppValueRow(
                        title = stringResource(Res.string.detail_key_connection_timeout),
                        value = key.settings.timeoutSeconds?.let {
                            stringResource(Res.string.detail_key_timeout_value, it)
                        } ?: stringResource(Res.string.detail_key_timeout_default),
                    )
                    AppValueRow(
                        title = stringResource(Res.string.detail_key_connection_allow_http),
                        value = stringResource(
                            if (key.settings.allowInsecure) Res.string.common_on else Res.string.common_off,
                        ),
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
        AppValueRow(
            title = stringResource(Res.string.detail_model_protocol),
            value = protocolLabel(model.protocol),
        )
        AppValueRow(
            title = stringResource(Res.string.detail_models_source),
            value = stringResource(
                if (model.source == com.lc33.tokenvault.screens.model.UiModelSource.Manual) {
                    Res.string.manage_source_manual
                } else {
                    Res.string.manage_source_discovered
                },
            ),
        )
        Row(
            modifier = Modifier.padding(top = tokens.itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = colorOf(model.health), label = labelOf(model.health))
        }
    }
}
