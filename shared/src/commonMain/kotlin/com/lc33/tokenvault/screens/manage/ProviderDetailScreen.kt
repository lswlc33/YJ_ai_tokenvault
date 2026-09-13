package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import tokenvault.shared.generated.resources.detail_models_section
import tokenvault.shared.generated.resources.detail_provider_balance_total
import tokenvault.shared.generated.resources.detail_reachability_latency
import tokenvault.shared.generated.resources.dialog_cancel
import tokenvault.shared.generated.resources.login_method_github
import tokenvault.shared.generated.resources.login_method_linuxdo
import tokenvault.shared.generated.resources.detail_accounts_empty
import tokenvault.shared.generated.resources.detail_add_account
import tokenvault.shared.generated.resources.detail_account_keep_secret
import tokenvault.shared.generated.resources.detail_account_view
import tokenvault.shared.generated.resources.detail_account_delete
import tokenvault.shared.generated.resources.detail_account_delete_title
import tokenvault.shared.generated.resources.detail_account_delete_body
import tokenvault.shared.generated.resources.login_method_password
import tokenvault.shared.generated.resources.detail_add_key
import tokenvault.shared.generated.resources.detail_balance_refresh
import tokenvault.shared.generated.resources.detail_edit_cd
import tokenvault.shared.generated.resources.detail_key_delete_body
import tokenvault.shared.generated.resources.detail_key_delete_title
import tokenvault.shared.generated.resources.detail_key_reveal_hint
import tokenvault.shared.generated.resources.detail_key_secret
import tokenvault.shared.generated.resources.detail_key_set_default
import tokenvault.shared.generated.resources.detail_key_sheet_title
import tokenvault.shared.generated.resources.detail_keys_empty
import tokenvault.shared.generated.resources.detail_models_refresh
import tokenvault.shared.generated.resources.refresh_cd
import tokenvault.shared.generated.resources.detail_section_accounts
import tokenvault.shared.generated.resources.detail_section_keys
import tokenvault.shared.generated.resources.editor_name
import tokenvault.shared.generated.resources.editor_note
import tokenvault.shared.generated.resources.editor_website
import tokenvault.shared.generated.resources.editor_save
import tokenvault.shared.generated.resources.groups_delete
import tokenvault.shared.generated.resources.import_manual
import tokenvault.shared.generated.resources.import_title
import tokenvault.shared.generated.resources.secret_copy_cd
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.platform.openExternalUrl
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.screens.model.ProviderDetailUiState
import com.lc33.tokenvault.screens.model.UiKeyRow
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDivider
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
import kotlinx.coroutines.delay

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
 * 新增密钥先进入导入方式选择，再复用 cURL 导入或密钥编辑页；这里不再内嵌密钥输入框，
 * 避免同一条明文出现两套生命周期与保存路径。
 */
@Composable
fun ProviderDetailScreen(
    state: ProviderDetailUiState,
    revealedAccount: ProviderDetailViewModel.AccountRevealState?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onCurlImport: () -> Unit,
    onManualAddKey: () -> Unit,
    onOpenKey: (Long) -> Unit,
    onRefreshBalance: () -> Unit,
    onProbeKey: (Long) -> Unit,
    onRefreshKeyModels: (Long) -> Unit,
    onAddModel: (Long, String, Protocol) -> Unit,
    onUpdateModel: (Long, String, Protocol, String?, Boolean) -> Unit,
    onDeleteModel: (Long) -> Unit,
    onProbeModel: (Long, String, Protocol) -> Unit,
    onRevealAccount: (Long) -> Unit,
    onCopyRevealedAccount: (String) -> Unit,
    onCloseAccountReveal: () -> Unit,
    onSetAccountLoginMethods: (Long, Set<LoginMethod>) -> Unit,
    onAddAccount: (String, String, CharArray?, CharArray?, Set<LoginMethod>) -> Unit,
    onUpdateAccount: (Long, String, String, CharArray?, CharArray?, Set<LoginMethod>, Boolean) -> Unit,
    onDeleteAccount: (Long) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val provider = state.provider
    var showAddDialog by remember { mutableStateOf(false) }
    var addModelKeyId by remember { mutableStateOf<Long?>(null) }
    var editingModel by remember { mutableStateOf<UiModelRow?>(null) }
    var pendingDeleteModelId by remember { mutableStateOf<Long?>(null) }
    var accountEditor by remember { mutableStateOf<AccountEditorTarget?>(null) }
    var pendingDeleteAccountId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(revealedAccount?.accountId) {
        if (revealedAccount != null) {
            delay(30_000)
            onCloseAccountReveal()
        }
    }

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
                        contentDescription = stringResource(Res.string.refresh_cd),
                        onClick = onRefreshBalance,
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
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
            if (state.keys.isEmpty()) {
                item {
                    HintCard(
                        text = stringResource(Res.string.detail_keys_empty),
                        actionText = stringResource(Res.string.detail_add_key),
                        onAction = { showAddDialog = true },
                    )
                }
            } else {
                items(state.keys.size) { index ->
                    val row = state.keys[index]
                    KeyCard(
                        row = row,
                        models = state.models.filter { it.keyId == row.id },
                        nowMs = state.nowMs,
                        onOpen = { onOpenKey(row.id) },
                        onProbeKey = { onProbeKey(row.id) },
                        onRefreshModels = { onRefreshKeyModels(row.id) },
                        onAddModel = { addModelKeyId = row.id },
                        onEditModel = { editingModel = it },
                        onProbeModel = onProbeModel,
                    )
                }
            }


            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle(
                        text = stringResource(Res.string.detail_section_accounts),
                        modifier = Modifier.weight(1f),
                    )
                    AppIconButton(
                        icon = AppIcon.Add,
                        contentDescription = stringResource(Res.string.detail_add_account),
                        onClick = { accountEditor = AccountEditorTarget.New },
                    )
                }
            }
            if (state.accounts.isEmpty()) {
                item {
                    HintCard(
                        text = stringResource(Res.string.detail_accounts_empty),
                        actionText = stringResource(Res.string.detail_add_account),
                        onAction = { accountEditor = AccountEditorTarget.New },
                    )
                }
            } else {
                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.screenPadding),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        state.accounts.forEachIndexed { index, account ->
                            AccountRow(
                                row = account,
                                onClick = { accountEditor = AccountEditorTarget.Edit(account) },
                            )
                            if (index != state.accounts.lastIndex) {
                                AppDivider()
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }

    AddKeyDialog(
        show = showAddDialog,
        onDismiss = { showAddDialog = false },
        onCurlImport = {
            showAddDialog = false
            onCurlImport()
        },
        onManualAddKey = {
            showAddDialog = false
            onManualAddKey()
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

    AccountEditorSheet(
        target = accountEditor,
        onDismiss = { accountEditor = null },
        onSave = { label, note, username, password, methods, usesPassword ->
            when (val current = accountEditor) {
                is AccountEditorTarget.New -> onAddAccount(label, note, username, password, methods)
                is AccountEditorTarget.Edit -> onUpdateAccount(
                    current.account.id,
                    label,
                    note,
                    username,
                    password,
                    methods,
                    usesPassword,
                )
                null -> Unit
            }
            accountEditor = null
        },
        onDelete = { id ->
            accountEditor = null
            pendingDeleteAccountId = id
        },
        onReveal = { id ->
            accountEditor = null
            onRevealAccount(id)
        },
    )

    RevealAccountSheet(
        account = revealedAccount,
        onCopy = { label -> onCopyRevealedAccount(label) },
        onSetLoginMethods = onSetAccountLoginMethods,
        onDismiss = onCloseAccountReveal,
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
        show = pendingDeleteAccountId != null,
        onDismissRequest = { pendingDeleteAccountId = null },
        title = stringResource(Res.string.detail_account_delete_title),
        summary = stringResource(Res.string.detail_account_delete_body),
        confirmText = stringResource(Res.string.detail_account_delete),
        onConfirm = {
            pendingDeleteAccountId?.let(onDeleteAccount)
            pendingDeleteAccountId = null
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

/** 新增密钥：先选导入方式，再进入对应流程。 */
@Composable
private fun AddKeyDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onCurlImport: () -> Unit,
    onManualAddKey: () -> Unit,
) {
    val tokens = LocalAppTokens.current

    AppDialog(
        show = show,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.detail_add_key),
    ) {
        AppActionRow(
            text = stringResource(Res.string.import_title),
            onClick = onCurlImport,
            modifier = Modifier.fillMaxWidth(),
        )
        AppActionRow(
            text = stringResource(Res.string.import_manual),
            onClick = onManualAddKey,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
        )
    }
}

private sealed interface AccountEditorTarget {
    data object New : AccountEditorTarget
    data class Edit(val account: com.lc33.tokenvault.screens.model.UiAccountRow) : AccountEditorTarget
}

/** 账号编辑底部表单：新增与编辑共用，避免两套字段顺序和校验。 */
@Composable
private fun AccountEditorSheet(
    target: AccountEditorTarget?,
    onDismiss: () -> Unit,
    onSave: (String, String, CharArray?, CharArray?, Set<LoginMethod>, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onReveal: (Long) -> Unit,
) {
    val tokens = LocalAppTokens.current
    val account = (target as? AccountEditorTarget.Edit)?.account
    val label = rememberAppTextFieldState(account?.label.orEmpty())
    val note = rememberAppTextFieldState(account?.note.orEmpty())
    val username = rememberSecretTextFieldState()
    val password = rememberSecretTextFieldState()
    var methods by remember(target) {
        mutableStateOf(
            account?.loginMethods
                ?.mapNotNull(LoginMethod::fromWireName)
                ?.toSet()
                .orEmpty(),
        )
    }
    var usesPassword by remember(target) { mutableStateOf(account?.hasPassword == true) }

    AppBottomSheet(
        show = target != null,
        onDismissRequest = {
            username.clear()
            password.clear()
            onDismiss()
        },
        title = account?.label?.takeIf { it.isNotBlank() }
            ?: stringResource(Res.string.detail_add_account),
    ) {
        if (target == null) return@AppBottomSheet
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
        AppTextField(
            state = label,
            label = stringResource(Res.string.editor_name),
        )
        AppTextField(
            state = note,
            label = stringResource(Res.string.editor_note),
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppText(
            text = stringResource(Res.string.detail_account_login_methods),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            LoginMethod.entries.forEach { method ->
                val selected = method in methods
                AppFilterChip(
                    text = loginMethodLabel(method),
                    selected = selected,
                    onClick = {
                        methods = if (selected) methods - method else methods + method
                    },
                )
            }
            AppFilterChip(
                text = stringResource(Res.string.login_method_password),
                selected = usesPassword,
                onClick = { usesPassword = !usesPassword },
            )
        }
        if (usesPassword) {
            AppSecretTextField(
                state = username,
                label = stringResource(Res.string.detail_account_username),
                supportingText = account?.let { stringResource(Res.string.detail_account_keep_secret) },
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
            AppSecretTextField(
                state = password,
                label = stringResource(Res.string.detail_account_password),
                supportingText = account?.let { stringResource(Res.string.detail_account_keep_secret) },
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
        }
        AppActionRow(
            text = stringResource(Res.string.editor_save),
            onClick = {
                val usernameChars = username.chars.takeIf { it.isNotEmpty() }
                val passwordChars = password.chars.takeIf { it.isNotEmpty() }
                username.clear()
                password.clear()
                onSave(label.text, note.text, usernameChars, passwordChars, methods, usesPassword)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
        )
        account?.let { row ->
            AppActionRow(
                text = stringResource(Res.string.detail_account_view),
                onClick = { onReveal(row.id) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppActionRow(
                text = stringResource(Res.string.detail_account_delete),
                onClick = { onDelete(row.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        }
    }
}

/** 手动添加 / 编辑模型。模型是明文元数据，不需要密码键盘。 */
@Composable
internal fun ModelDialog(
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
            items = protocols.map { protocolLabel(it) },
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

/**
 * 一张 Key 卡：Key 本身与绑定到它的模型共用一个容器。
 * 模型列表不另起卡片，避免在详情页里出现"卡片套卡片"的层级噪音。
 */
@Composable
private fun KeyCard(
    row: UiKeyRow,
    models: List<UiModelRow>,
    nowMs: Long,
    onOpen: () -> Unit,
    onProbeKey: () -> Unit,
    onRefreshModels: () -> Unit,
    onAddModel: () -> Unit,
    onEditModel: (UiModelRow) -> Unit,
    onProbeModel: (Long, String, Protocol) -> Unit,
) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
        insideMargin = PaddingValues(0.dp),
    ) {
        KeyRow(
            row = row,
            nowMs = nowMs,
            onClick = onOpen,
            onProbe = onProbeKey,
        )
        AppDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = tokens.screenPadding,
                    vertical = tokens.itemSpacing,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = stringResource(Res.string.detail_models_section),
                style = AppTextStyle.Subtitle,
                modifier = Modifier.weight(1f),
            )
            if (row.settings.probeModels) {
                AppIconButton(
                    icon = AppIcon.Refresh,
                    contentDescription = stringResource(Res.string.detail_models_refresh),
                    onClick = onRefreshModels,
                )
            }
            AppIconButton(
                icon = AppIcon.Add,
                contentDescription = stringResource(Res.string.detail_add_model),
                onClick = onAddModel,
            )
        }

        if (models.isEmpty()) {
            AppText(
                text = stringResource(
                    if (row.settings.probeModels) {
                        Res.string.detail_models_empty_auto
                    } else {
                        Res.string.detail_models_empty_manual
                    },
                ),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(
                    horizontal = tokens.screenPadding,
                    vertical = tokens.itemSpacing,
                ),
            )
        } else {
            models.forEachIndexed { index, model ->
                val modelKeyId = model.keyId
                ModelRow(
                    row = model,
                    onClick = { onEditModel(model) },
                    onProbe = if (row.settings.probeModelReachability && modelKeyId != null) {
                        {
                            onProbeModel(
                                modelKeyId,
                                model.modelId,
                                Protocol.fromWireName(model.protocol) ?: Protocol.CHAT,
                            )
                        }
                    } else {
                        null
                    },
                )
                if (index != models.lastIndex) {
                    AppDivider()
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
}

@Composable
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
        provider.websiteUrl?.takeIf { it.isNotBlank() }?.let { website ->
            AppActionRow(
                text = stringResource(Res.string.editor_website) + ": " + website,
                onClick = { openExternalUrl(website) },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = tokens.itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            provider.protocols.forEach { protocol -> AppChip(text = protocolLabel(protocol)) }
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
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = stringResource(Res.string.detail_provider_balance_total),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                )
                AppText(
                    text = when {
                        balance != null -> balance.toDisplay()
                        balanceFailed -> stringResource(Res.string.balance_failed_section)
                        else -> stringResource(Res.string.dashboard_balance_none)
                    },
                    style = if (balanceFailed) AppTextStyle.Secondary else AppTextStyle.Title,
                    color = if (balanceFailed) LocalStatusPalette.current.warn else Color.Unspecified,
                )
            }
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
