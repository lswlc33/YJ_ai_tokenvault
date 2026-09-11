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
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.dashboard_balance_none
import tokenvault.shared.generated.resources.balance_failed_section
import tokenvault.shared.generated.resources.detail_account_none
import tokenvault.shared.generated.resources.detail_account_password
import tokenvault.shared.generated.resources.detail_account_reveal_hint
import tokenvault.shared.generated.resources.detail_account_sheet_title
import tokenvault.shared.generated.resources.detail_account_username
import tokenvault.shared.generated.resources.detail_account_login_methods
import tokenvault.shared.generated.resources.detail_add_model
import tokenvault.shared.generated.resources.detail_model_delete
import tokenvault.shared.generated.resources.detail_model_delete_action
import tokenvault.shared.generated.resources.detail_model_display_name
import tokenvault.shared.generated.resources.detail_model_edit
import tokenvault.shared.generated.resources.detail_model_enabled
import tokenvault.shared.generated.resources.detail_model_id
import tokenvault.shared.generated.resources.detail_model_protocol
import tokenvault.shared.generated.resources.detail_models_empty_auto
import tokenvault.shared.generated.resources.detail_models_empty_manual
import tokenvault.shared.generated.resources.detail_models_quick_hint
import tokenvault.shared.generated.resources.detail_models_section
import tokenvault.shared.generated.resources.detail_reachability_latency
import tokenvault.shared.generated.resources.dialog_cancel
import tokenvault.shared.generated.resources.login_method_github
import tokenvault.shared.generated.resources.login_method_linuxdo
import tokenvault.shared.generated.resources.manage_context
import tokenvault.shared.generated.resources.manage_disabled
import tokenvault.shared.generated.resources.manage_source_discovered
import tokenvault.shared.generated.resources.manage_source_manual
import tokenvault.shared.generated.resources.detail_accounts_empty
import tokenvault.shared.generated.resources.detail_add_key
import tokenvault.shared.generated.resources.detail_balance_refresh
import tokenvault.shared.generated.resources.detail_edit_cd
import tokenvault.shared.generated.resources.detail_key_delete_body
import tokenvault.shared.generated.resources.detail_key_delete_title
import tokenvault.shared.generated.resources.detail_key_label
import tokenvault.shared.generated.resources.detail_key_label_hint
import tokenvault.shared.generated.resources.detail_key_reveal_hint
import tokenvault.shared.generated.resources.detail_key_secret
import tokenvault.shared.generated.resources.detail_key_secret_hint
import tokenvault.shared.generated.resources.detail_key_set_default
import tokenvault.shared.generated.resources.detail_key_sheet_title
import tokenvault.shared.generated.resources.detail_keys_empty
import tokenvault.shared.generated.resources.detail_models_refresh
import tokenvault.shared.generated.resources.detail_probe_key
import tokenvault.shared.generated.resources.detail_probe_provider
import tokenvault.shared.generated.resources.detail_section_accounts
import tokenvault.shared.generated.resources.detail_section_keys
import tokenvault.shared.generated.resources.editor_save
import tokenvault.shared.generated.resources.groups_delete
import tokenvault.shared.generated.resources.secret_copy_cd
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.screens.model.ProviderDetailUiState
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.screens.model.UiModelSource
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.common.labelOf
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSecretTextField
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppDialogTextButton
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.miuix.rememberSecretTextFieldState
import com.lc33.tokenvault.ui.shell.ProviderDetailViewModel
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 供应商详情 —— 这一家的密钥 / 模型 / 平台账号都在这里看、也在这里改
 * （计划.md §13.4）。
 *
 * 管理页只列供应商，所以"这是谁的 key"这个问题在进到这一页时就已经答完了：
 * 页内的每一行都不必再带"所属供应商"那一列。
 *
 * **这是全应用唯一显示密钥明文的页面**（§6.1 推论 3）：
 * 遮蔽串是解密后现算的，展开那一层还会显示完整明文。列表页拿不到明文，想画也画不出来。
 *
 * 新增密钥那一层的输入框用 `rememberSecretTextFieldState`（不进 saved instance state）
 * 与密码键盘：默认键盘会把内容喂给输入法的联想与"个性化学习"，于是这段明文之后会以
 * 候选词的形式出现在任何人面前。
 */
@Composable
fun ProviderDetailScreen(
    state: ProviderDetailUiState,
    revealedKeyId: Long?,
    revealedText: String?,
    revealedAccount: ProviderDetailViewModel.AccountRevealState?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddKey: (String, CharArray) -> Unit,
    onRevealKey: (Long) -> Unit,
    onCopyRevealed: () -> Unit,
    onCloseReveal: () -> Unit,
    onSetDefaultKey: (Long) -> Unit,
    onDeleteKey: (Long) -> Unit,
    onRefreshBalance: () -> Unit,
    onProbeProvider: () -> Unit,
    onProbeKey: (Long) -> Unit,
    onRefreshModels: () -> Unit,
    onRefreshKeyModels: (Long) -> Unit,
    onAddModel: (Long, String, Protocol) -> Unit,
    onUpdateModel: (Long, String, Protocol, String?, Boolean) -> Unit,
    onDeleteModel: (Long) -> Unit,
    onProbeModel: (Long, String, Protocol) -> Unit,
    onRevealAccount: (Long) -> Unit,
    onCopyRevealedAccount: (String) -> Unit,
    onCloseAccountReveal: () -> Unit,
    onSetAccountLoginMethods: (Long, Set<LoginMethod>) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val provider = state.provider
    var showAddDialog by remember { mutableStateOf(false) }
    var addModelKeyId by remember { mutableStateOf<Long?>(null) }
    var editingModel by remember { mutableStateOf<UiModelRow?>(null) }
    var pendingDeleteModelId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteKeyId by remember { mutableStateOf<Long?>(null) }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = provider.name,
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
                        contentDescription = stringResource(Res.string.detail_probe_provider),
                        onClick = onProbeProvider,
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
            item { HeaderCard(state, onRefreshBalance) }

            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle(
                            text = stringResource(Res.string.detail_section_keys),
                            modifier = Modifier.weight(1f),
                        )
                        AppIconButton(
                            icon = AppIcon.Add,
                            contentDescription = stringResource(Res.string.detail_add_key),
                            onClick = { showAddDialog = true },
                        )
                    }
                }
            }
            if (state.keys.isEmpty()) {
                item {
                    // 空态给的是后果而不是"暂无数据"：没有密钥这家就探不了、也查不了余额
                    HintCard(
                        text = stringResource(Res.string.detail_keys_empty),
                        actionText = stringResource(Res.string.detail_add_key),
                        onAction = { showAddDialog = true },
                    )
                }
            } else {
                item {
                    // 长按是隐藏手势，不提示用户永远不知道能单 Key 探测（§8.6）
                    AppText(
                        text = stringResource(Res.string.detail_probe_key),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
                items(state.keys.size) { index ->
                    val row = state.keys[index]
                    KeyRow(
                        row = row,
                        nowMs = state.nowMs,
                        onClick = { onRevealKey(row.id) },
                        onLongPress = { onProbeKey(row.id) },
                    )
                    KeyModelsCard(
                        keyId = row.id,
                        models = state.models.filter { it.keyId == row.id },
                        modelListEnabled = state.modelListEnabled,
                        modelReachabilityEnabled = state.modelReachabilityEnabled,
                        onRefresh = { onRefreshKeyModels(row.id) },
                        onAdd = { addModelKeyId = row.id },
                        onEdit = { editingModel = it },
                        onQuickProbe = onProbeModel,
                    )
                }
            }


            item { SectionTitle(text = stringResource(Res.string.detail_section_accounts)) }
            if (state.accounts.isEmpty()) {
                item { AccountsEmptyHint() }
            } else {
                items(state.accounts.size) { index ->
                    AccountRow(
                        row = state.accounts[index],
                        onClick = { onRevealAccount(state.accounts[index].id) },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }

    AddKeyDialog(
        show = showAddDialog,
        onDismiss = { showAddDialog = false },
        onConfirm = { label, secret ->
            showAddDialog = false
            onAddKey(label, secret)
        },
    )

    ModelDialog(
        keyId = addModelKeyId,
        editing = editingModel,
        protocols = provider.protocols.mapNotNull { Protocol.fromWireName(it) }
            .ifEmpty { listOf(Protocol.CHAT) },
        onDismiss = {
            addModelKeyId = null
            editingModel = null
        },
        onConfirm = { keyId, modelId, protocol, displayName, enabled ->
            val editing = editingModel
            if (editing == null) {
                onAddModel(keyId, modelId, protocol)
            } else {
                onUpdateModel(editing.id, modelId, protocol, displayName, enabled)
            }
            addModelKeyId = null
            editingModel = null
        },
        onRequestDelete = { model ->
            pendingDeleteModelId = model.id
            editingModel = null
        },
    )

    RevealKeySheet(
        text = revealedText,
        onCopy = onCopyRevealed,
        onSetDefault = {
            revealedKeyId?.let(onSetDefaultKey)
            onCloseReveal()
        },
        onDelete = {
            // 先收起这一层再问：确认框叠在展开的明文上面，而那一层还亮着密钥
            pendingDeleteKeyId = revealedKeyId
            onCloseReveal()
        },
        onDismiss = onCloseReveal,
    )

    RevealAccountSheet(
        account = revealedAccount,
        onCopy = { label -> onCopyRevealedAccount(label) },
        onSetLoginMethods = onSetAccountLoginMethods,
        onDismiss = onCloseAccountReveal,
    )

    AppDialog(
        show = pendingDeleteKeyId != null,
        onDismissRequest = { pendingDeleteKeyId = null },
        title = stringResource(Res.string.detail_key_delete_title),
        summary = stringResource(Res.string.detail_key_delete_body),
        confirmText = stringResource(Res.string.groups_delete),
        onConfirm = {
            pendingDeleteKeyId?.let(onDeleteKey)
            pendingDeleteKeyId = null
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
}

/** 空态的一句话 + 一个入口。 */
@Composable
private fun HintCard(text: String, actionText: String, onAction: () -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
    ) {
        AppText(text = text, style = AppTextStyle.Secondary, color = appSecondaryTextColor)
        AppActionRow(
            text = actionText,
            onClick = onAction,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
    }
}

/** 新增密钥：标准 OverlayDialog + MIUIX 按钮，不再贴到屏幕底部。 */
@Composable
private fun AddKeyDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, CharArray) -> Unit,
) {
    val tokens = LocalAppTokens.current
    val label = rememberSecretTextFieldState()
    val secret = rememberSecretTextFieldState()

    AppDialog(
        show = show,
        onDismissRequest = {
            secret.clear()
            label.clear()
            onDismiss()
        },
        title = stringResource(Res.string.detail_add_key),
    ) {
        AppTextField(
            state = label,
            label = stringResource(Res.string.detail_key_label),
            supportingText = stringResource(Res.string.detail_key_label_hint),
        )
        AppSecretTextField(
            state = secret,
            label = stringResource(Res.string.detail_key_secret),
            supportingText = stringResource(Res.string.detail_key_secret_hint),
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppDialogTextButton(
                text = stringResource(Res.string.dialog_cancel),
                onClick = {
                    secret.clear()
                    label.clear()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            AppDialogTextButton(
                text = stringResource(Res.string.editor_save),
                onClick = {
                    val chars = secret.chars
                    if (chars.isEmpty()) return@AppDialogTextButton
                    val text = label.text
                    secret.clear()
                    label.clear()
                    onConfirm(text, chars)
                },
                modifier = Modifier.weight(1f),
                primary = true,
            )
        }
    }
}

/** 手动添加 / 编辑模型。模型是明文元数据，不需要密码键盘。 */
@Composable
private fun ModelDialog(
    keyId: Long?,
    editing: UiModelRow?,
    protocols: List<Protocol>,
    onDismiss: () -> Unit,
    onConfirm: (Long, String, Protocol, String?, Boolean) -> Unit,
    onRequestDelete: (UiModelRow) -> Unit,
) {
    val tokens = LocalAppTokens.current
    val modelId = rememberAppTextFieldState()
    val displayName = rememberAppTextFieldState()
    var protocol by remember { mutableStateOf(Protocol.CHAT) }
    var enabled by remember { mutableStateOf(true) }
    val targetId = editing?.id

    LaunchedEffect(keyId, targetId) {
        if (keyId == null && editing == null) return@LaunchedEffect
        val source = editing
        modelId.setText(source?.modelId.orEmpty())
        displayName.setText(source?.displayName.orEmpty())
        protocol = source?.protocol?.let { Protocol.fromWireName(it) } ?: protocols.first()
        enabled = source?.enabled ?: true
    }

    AppDialog(
        show = keyId != null || editing != null,
        onDismissRequest = onDismiss,
        title = stringResource(
            if (editing == null) Res.string.detail_add_model else Res.string.detail_model_edit,
        ),
    ) {
        AppTextField(
            state = modelId,
            label = stringResource(Res.string.detail_model_id),
        )
        AppTextField(
            state = displayName,
            label = stringResource(Res.string.detail_model_display_name),
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppDropdownRow(
            title = stringResource(Res.string.detail_model_protocol),
            items = protocols.map { it.wireName },
            selectedIndex = protocols.indexOf(protocol).coerceAtLeast(0),
            onSelect = { index -> protocol = protocols.getOrElse(index) { protocols.first() } },
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppSwitchRow(
            title = stringResource(Res.string.detail_model_enabled),
            checked = enabled,
            onCheckedChange = { enabled = it },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppDialogTextButton(
                text = stringResource(Res.string.dialog_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            AppDialogTextButton(
                text = stringResource(Res.string.editor_save),
                onClick = {
                    val id = modelId.text.trim()
                    if (id.isEmpty() || (keyId == null && editing == null)) return@AppDialogTextButton
                    onConfirm(
                        keyId ?: editing?.keyId ?: 0L,
                        id,
                        protocol,
                        displayName.text,
                        enabled,
                    )
                },
                modifier = Modifier.weight(1f),
                primary = true,
            )
        }
        editing?.let { model ->
            AppDialogTextButton(
                text = stringResource(Res.string.detail_model_delete_action),
                onClick = { onRequestDelete(model) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 一张 Key 自己的模型列表。列表与余额一样绑定在 Key 上。 */
@Composable
private fun KeyModelsCard(
    keyId: Long,
    models: List<UiModelRow>,
    modelListEnabled: Boolean,
    modelReachabilityEnabled: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (UiModelRow) -> Unit,
    onQuickProbe: (Long, String, Protocol) -> Unit,
) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = stringResource(Res.string.detail_models_section),
                style = AppTextStyle.Subtitle,
                modifier = Modifier.weight(1f),
            )
            if (modelListEnabled) {
                AppIconButton(
                    icon = AppIcon.Refresh,
                    contentDescription = stringResource(Res.string.detail_models_refresh),
                    onClick = onRefresh,
                )
            }
            AppIconButton(
                icon = AppIcon.Add,
                contentDescription = stringResource(Res.string.detail_add_model),
                onClick = onAdd,
            )
        }

        if (models.isEmpty()) {
            AppText(
                text = stringResource(
                    if (modelListEnabled) {
                        Res.string.detail_models_empty_auto
                    } else {
                        Res.string.detail_models_empty_manual
                    },
                ),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                models.forEach { model ->
                    BoundModelRow(
                        row = model,
                        onClick = { onEdit(model) },
                        onLongPress = if (modelReachabilityEnabled && model.keyId != null) {
                            { onQuickProbe(keyId, model.modelId, protocolOf(model.protocol)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        if (modelReachabilityEnabled) {
            AppText(
                text = stringResource(Res.string.detail_models_quick_hint),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
        }
    }
}

private fun protocolOf(wireName: String): Protocol = Protocol.fromWireName(wireName) ?: Protocol.CHAT

@Composable
private fun BoundModelRow(
    row: UiModelRow,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        onLongPress = onLongPress,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LocalAppTokens.current.itemSpacing),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(text = row.modelId, style = AppTextStyle.Body, maxLines = 1)
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppChip(text = row.protocol)
                    AppChip(
                        text = stringResource(
                            when (row.source) {
                                UiModelSource.Manual -> Res.string.manage_source_manual
                                UiModelSource.Discovered -> Res.string.manage_source_discovered
                            },
                        ),
                    )
                    if (!row.enabled) AppChip(text = stringResource(Res.string.manage_disabled))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusDot(color = colorOf(row.health), label = labelOf(row.health))
                row.contextLabel?.let { context ->
                    AppText(
                        text = stringResource(Res.string.manage_context, context),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                    )
                }
            }
        }
    }
}
/**
 * 展开一把密钥。
 *
 * [text] 非空就显示这一层。它是**擦不掉的 `String`**（红线 1），
 * 关掉这一层时 ViewModel 会擦掉它背后那份 `CharArray`。
 */
@Composable
private fun RevealKeySheet(
    text: String?,
    onCopy: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    AppBottomSheet(
        show = text != null,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.detail_key_sheet_title),
    ) {
        AppText(
            text = text.orEmpty(),
            style = AppTextStyle.Body,
            fontFamily = tokens.monoFontFamily,
        )
        AppText(
            text = stringResource(Res.string.detail_key_reveal_hint),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppActionRow(
            text = stringResource(Res.string.secret_copy_cd),
            onClick = onCopy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
        )
        AppActionRow(
            text = stringResource(Res.string.detail_key_set_default),
            onClick = onSetDefault,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onDelete != null) {
            AppActionRow(
                text = stringResource(Res.string.groups_delete),
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 展开一条平台账号（红线 21：账号密码与密钥同等对待，展开看明文、关闭回遮）。
 *
 * [account] 非空就显示这一层。用户名与密码两段都可能为 null（只记了一半，§11.2），
 * 为 null 的那段显示「（未记录）」而不是空串。明文是擦不掉的 `String`，
 * 关掉这一层时 ViewModel 擦掉背后那份 `CharArray`。
 */
@Composable
private fun RevealAccountSheet(
    account: ProviderDetailViewModel.AccountRevealState?,
    onCopy: (String) -> Unit,
    onSetLoginMethods: (Long, Set<LoginMethod>) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    AppBottomSheet(
        show = account != null,
        onDismissRequest = onDismiss,
        title = account?.label?.takeIf { it.isNotBlank() }
            ?: stringResource(Res.string.detail_account_sheet_title),
    ) {
        if (account == null) return@AppBottomSheet

        // 用户名
        AppText(
            text = stringResource(Res.string.detail_account_username),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
        )
        AppText(
            text = account.username ?: stringResource(Res.string.detail_account_none),
            style = AppTextStyle.Body,
            fontFamily = tokens.monoFontFamily,
        )

        // 密码
        AppText(
            text = stringResource(Res.string.detail_account_password),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppText(
            text = account.password ?: stringResource(Res.string.detail_account_none),
            style = AppTextStyle.Body,
            fontFamily = tokens.monoFontFamily,
        )

        AppText(
            text = stringResource(Res.string.detail_account_login_methods),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)) {
            LoginMethod.entries.forEach { method ->
                val selected = method in account.loginMethods
                AppFilterChip(
                    text = loginMethodLabel(method),
                    selected = selected,
                    onClick = {
                        val next = if (selected) account.loginMethods - method
                        else account.loginMethods + method
                        onSetLoginMethods(account.accountId, next)
                    },
                )
            }
        }

        AppText(
            text = stringResource(Res.string.detail_account_reveal_hint),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppActionRow(
            text = stringResource(Res.string.secret_copy_cd),
            onClick = { onCopy(account.label) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
        )
    }
}


@Composable
private fun loginMethodLabel(method: LoginMethod): String = when (method) {
    LoginMethod.GITHUB -> stringResource(Res.string.login_method_github)
    LoginMethod.LINUX_DO -> stringResource(Res.string.login_method_linuxdo)
}@Composable
private fun HeaderCard(
    state: ProviderDetailUiState,
    onRefreshBalance: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val provider = state.provider
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
    ) {
        val note = provider.note
        if (note != null) {
            AppText(text = note, style = AppTextStyle.Body)
        }
        AppText(
            text = provider.host,
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
            fontFamily = tokens.monoFontFamily,
        )
        Row(
            modifier = Modifier.padding(top = tokens.itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            provider.protocols.forEach { protocol -> AppChip(text = protocol) }
        }
        provider.reachabilityLatencyMs?.let { latency ->
            AppText(
                text = stringResource(Res.string.detail_reachability_latency, latency),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
        }
        // 余额：三种状态要可区分（§9.3）——有金额 / 查询失败 / 没配置。
        val balance = provider.balance
        val balanceFailed = balance == null && provider.balanceFailed
        Row(
            modifier = Modifier.padding(top = tokens.itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = when {
                    balance != null -> balance.toDisplay()
                    balanceFailed -> stringResource(Res.string.balance_failed_section)
                    else -> stringResource(Res.string.dashboard_balance_none)
                },
                style = if (balanceFailed) AppTextStyle.Secondary else AppTextStyle.Title,
                color = if (balanceFailed) LocalStatusPalette.current.warn else Color.Unspecified,
                modifier = Modifier.weight(1f),
            )
            AppIconButton(
                icon = AppIcon.Refresh,
                contentDescription = stringResource(Res.string.detail_balance_refresh),
                onClick = onRefreshBalance,
            )
        }
    }
}

/** 金额 + 币种符号。符号由币种查表（红线 15 不硬编码）。 */
private fun com.lc33.tokenvault.screens.model.UiMoney.toDisplay(): String =
    com.lc33.tokenvault.balance.FormatMoney.format(amount.toDoubleOrNull() ?: 0.0, currency)

@Composable
private fun AccountsEmptyHint() {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
    ) {
        AppText(
            text = stringResource(Res.string.detail_accounts_empty),
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
        )
    }
}
