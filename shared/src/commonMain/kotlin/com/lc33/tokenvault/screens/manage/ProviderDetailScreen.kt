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
import kotlinx.coroutines.delay
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.dashboard_balance_none
import tokenvault.shared.generated.resources.balance_failed_section
import tokenvault.shared.generated.resources.detail_account_password
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
import tokenvault.shared.generated.resources.secret_conceal_cd
import tokenvault.shared.generated.resources.secret_copy_cd
import tokenvault.shared.generated.resources.secret_reveal_cd
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
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
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
    onRefreshKeyModels: (Long) -> Unit,
    onAddModel: (Long, String, Protocol) -> Unit,
    onUpdateModel: (Long, String, Protocol, String?, Boolean) -> Unit,
    onDeleteModel: (Long) -> Unit,
    onRevealAccount: (Long) -> Unit,
    onCopyRevealedAccount: (Long) -> Unit,
    onCloseAccountReveal: () -> Unit,
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

    // 账号编辑层要用到展开的明文（用户名 / 密码预填进输入框，密码默认遮蔽）。
    // 目标变化时先清空旧输入，异步解密完成后再填入当前账号；展开显示只持续一段时间，
    // 自动回遮但不清除输入值，避免在编辑过程中暴露明文过久或打断用户输入。
    LaunchedEffect(accountEditor) {
        val editing = accountEditor as? AccountEditorTarget.Edit ?: return@LaunchedEffect
        onRevealAccount(editing.account.id)
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
                        // 这一行自己带了 screenPadding(16dp)，再叠 SmallTitle 默认的 28dp
                        // 就是 44dp、比卡片内容(32dp)深出去 12dp。压回 12dp 后与其它页的
                        // 区块标题左缘一致（相对卡片内容左缘略偏左 4dp，是 HyperOS 既定排版）。
                        startInset = 12.dp,
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
                        onRefreshModels = { onRefreshKeyModels(row.id) },
                        onAddModel = { addModelKeyId = row.id },
                        onEditModel = { editingModel = it },
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
                        startInset = 12.dp,
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
        revealed = revealedAccount,
        onDismiss = {
            accountEditor = null
            // 明文只在编辑层存活期间存在；关掉就擦（红线 1）。
            onCloseAccountReveal()
        },
        onCopy = { accountId -> onCopyRevealedAccount(accountId) },
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
            onCloseAccountReveal()
        },
        onDelete = { id ->
            accountEditor = null
            onCloseAccountReveal()
            pendingDeleteAccountId = id
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
    revealed: ProviderDetailViewModel.AccountRevealState?,
    onDismiss: () -> Unit,
    onCopy: (Long) -> Unit,
    onSave: (String, String, CharArray?, CharArray?, Set<LoginMethod>, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
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
    var usernameConcealed by remember(target) { mutableStateOf(true) }
    var passwordConcealed by remember(target) { mutableStateOf(true) }

    LaunchedEffect(target) {
        label.setText(account?.label.orEmpty())
        note.setText(account?.note.orEmpty())
        username.clear()
        password.clear()
        usernameConcealed = true
        passwordConcealed = true
    }

    LaunchedEffect(usernameConcealed) {
        if (!usernameConcealed) {
            delay(30_000)
            usernameConcealed = true
        }
    }

    LaunchedEffect(passwordConcealed) {
        if (!passwordConcealed) {
            delay(30_000)
            passwordConcealed = true
        }
    }

    // 明文在选中的账号弹出后才解密完成，到达时再填进输入框（不是构造时给初值）。
    LaunchedEffect(target, revealed) {
        val editing = (target as? AccountEditorTarget.Edit)?.account ?: return@LaunchedEffect
        if (revealed?.accountId != editing.id) return@LaunchedEffect
        username.setText(revealed.username.orEmpty())
        password.setText(revealed.password.orEmpty())
    }

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
        // 三种登录方式都是「开 / 关」，用开关而不是 chip：chip 让"选中"看起来像筛选，
        // 而这里每一项都是一个独立的是非题；开关也自带 ON/OFF 文字，比只有颜色的
        // 选中态更好读（红线 17：状态不能只靠颜色）。
        AppText(
            text = stringResource(Res.string.detail_account_login_methods),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppPreferenceGroup(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            inset = false,
        ) {
            LoginMethod.entries.forEach { method ->
                AppSwitchRow(
                    title = loginMethodLabel(method),
                    checked = method in methods,
                    onCheckedChange = { enabled ->
                        methods = if (enabled) methods + method else methods - method
                    },
                )
            }
            // 密码登录不是 LoginMethod 枚举的一员（枚举只有 GitHub / LINUX DO），
            // 它由 usesPassword 单独承载，所以第三行手动补上。
            AppSwitchRow(
                title = stringResource(Res.string.login_method_password),
                checked = usesPassword,
                onCheckedChange = { usesPassword = it },
            )
        }
        if (usesPassword) {
            // 用户名与密码直接预填进输入框（查看与编辑合一），密码默认遮蔽，
            // 右侧眼睛展开。明文只在弹层存活期间留在输入框里，关掉即擦。
            val revealCd = stringResource(Res.string.secret_reveal_cd)
            val concealCd = stringResource(Res.string.secret_conceal_cd)
            AppSecretTextField(
                state = username,
                label = stringResource(Res.string.detail_account_username),
                supportingText = account?.let { stringResource(Res.string.detail_account_keep_secret) },
                modifier = Modifier.padding(top = tokens.itemSpacing),
                concealed = usernameConcealed,
                toggleConcealDescription = if (usernameConcealed) revealCd else concealCd,
                onToggleConceal = { usernameConcealed = !usernameConcealed },
            )
            AppSecretTextField(
                state = password,
                label = stringResource(Res.string.detail_account_password),
                supportingText = account?.let { stringResource(Res.string.detail_account_keep_secret) },
                modifier = Modifier.padding(top = tokens.itemSpacing),
                concealed = passwordConcealed,
                toggleConcealDescription = if (passwordConcealed) revealCd else concealCd,
                onToggleConceal = { passwordConcealed = !passwordConcealed },
            )
        }
        // 保存用按钮而不是行入口：它是这个表单的收尾动作，必须一眼看出"填完了按这里"，
        // 行入口（带箭头那一类）读起来像"还能进下一页"。
        AppDialogTextButton(
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
            primary = true,
        )
        account?.let { row ->
            // 复制与删除都是对这条账号的动作，包成一组摆在表单最后。
            // 只有确实解出了凭据才给「复制」：这条账号可能只记了登录方式（没有用户名密码），
            // 那种情况下复制是无内容的死按钮。
            val canCopy = revealed?.accountId == row.id &&
                (revealed.username != null || revealed.password != null)
            AppPreferenceGroup(
                modifier = Modifier.padding(top = tokens.itemSpacing),
                inset = false,
            ) {
                if (canCopy) {
                    AppActionRow(
                        text = stringResource(Res.string.secret_copy_cd),
                        onClick = { onCopy(row.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                    AppActionRow(
                        text = stringResource(Res.string.detail_account_delete),
                        onClick = {
                            username.clear()
                            password.clear()
                            onDelete(row.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
            }
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
    onRefreshModels: () -> Unit,
    onAddModel: () -> Unit,
    onEditModel: (UiModelRow) -> Unit,
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
                ModelRow(
                    row = model,
                    onClick = { onEditModel(model) },
                )
                if (index != models.lastIndex) {
                    AppDivider()
                }
            }
        }
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
                inset = false,
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
