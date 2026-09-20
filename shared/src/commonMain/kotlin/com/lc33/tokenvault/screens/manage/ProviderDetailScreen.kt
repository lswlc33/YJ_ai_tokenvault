package com.lc33.tokenvault.screens.manage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import kotlinx.coroutines.delay
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.balance_failed_section
import tokenvault.shared.generated.resources.detail_account_password
import tokenvault.shared.generated.resources.detail_account_username
import tokenvault.shared.generated.resources.detail_account_login_methods
import tokenvault.shared.generated.resources.detail_add_model
import tokenvault.shared.generated.resources.detail_model_delete
import tokenvault.shared.generated.resources.detail_model_delete_action
import tokenvault.shared.generated.resources.detail_model_display_name
import tokenvault.shared.generated.resources.detail_model_edit
import tokenvault.shared.generated.resources.detail_model_id
import tokenvault.shared.generated.resources.detail_model_protocol
import tokenvault.shared.generated.resources.detail_models_empty_auto
import tokenvault.shared.generated.resources.detail_models_empty_manual
import tokenvault.shared.generated.resources.detail_models_expand_cd
import tokenvault.shared.generated.resources.detail_models_collapse_cd
import tokenvault.shared.generated.resources.detail_models_count_short
import tokenvault.shared.generated.resources.detail_models_section
import tokenvault.shared.generated.resources.detail_provider_balance_total
import tokenvault.shared.generated.resources.detail_reachability_latency
import tokenvault.shared.generated.resources.dialog_cancel
import tokenvault.shared.generated.resources.detail_accounts_empty_placeholder
import tokenvault.shared.generated.resources.detail_add_account
import tokenvault.shared.generated.resources.detail_account_keep_secret
import tokenvault.shared.generated.resources.detail_account_delete
import tokenvault.shared.generated.resources.detail_account_delete_title
import tokenvault.shared.generated.resources.detail_account_delete_body
import tokenvault.shared.generated.resources.login_method_password
import tokenvault.shared.generated.resources.detail_add_key
import tokenvault.shared.generated.resources.detail_balance_not_checked
import tokenvault.shared.generated.resources.editor_default_account_name
import tokenvault.shared.generated.resources.editor_error_missing_name
import tokenvault.shared.generated.resources.detail_edit_cd
import tokenvault.shared.generated.resources.detail_keys_empty
import tokenvault.shared.generated.resources.detail_models_refresh
import tokenvault.shared.generated.resources.detail_keys_empty_placeholder
import tokenvault.shared.generated.resources.probe_provider_cd
import tokenvault.shared.generated.resources.detail_section_accounts
import tokenvault.shared.generated.resources.detail_section_keys
import tokenvault.shared.generated.resources.editor_name
import tokenvault.shared.generated.resources.editor_note
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
import com.lc33.tokenvault.screens.model.UiProviderRow
import com.lc33.tokenvault.ui.common.loginMethodLabel
import com.lc33.tokenvault.ui.common.protocolLabel
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDivider
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
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
    onProbeAll: () -> Unit,
    onRefreshKeyModels: (Long) -> Unit,
    onAddModel: (Long, String, Protocol) -> Unit,
    onUpdateModel: (Long, String, Protocol, String?) -> Unit,
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
                    // 一键探测：官网连通性 + 密钥探测 + 模型列表 + 余额，一次点全发。
                    // 分成几个图标（这里曾经还有一个余额刷新）只会让用户猜哪个按了什么。
                    AppIconButton(
                        icon = AppIcon.Refresh,
                        contentDescription = stringResource(Res.string.probe_provider_cd),
                        onClick = onProbeAll,
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
            // 信息卡没有可显示的内容时整块不画：备注、官网、协议都没有的一家
            // （刚建好还没填）只会剩一张空卡占着首屏。
            if (hasProviderInfo(state.provider)) {
                item { InfoCard(state) }
            }
            // 余额独立成卡，且只在真的配了查询时才出现：`余额合计 / 金额 / 更新时间`
            // 是一组信息，塞进信息卡会和备注、官网挤成一片；而没配查询时它永远是
            // 一句"还没有配置"，摆在那里只是占屏。
            if (provider.balanceConfigured) {
                item { BalanceCard(state) }
            }

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
                // 空态只给一句灰字。区块标题那一行右侧已经有加号按钮，再摆一条
                // 「添加密钥」入口就是同一件事画两遍——而且行入口带箭头，读起来
                // 像"点进去还有下一页"，实际只是弹同一个添加弹层。
                item { HintText(stringResource(Res.string.detail_keys_empty_placeholder)) }
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
                // 同上：加号已经在标题行里，空态只留一句灰字。
                item { HintText(stringResource(Res.string.detail_accounts_empty_placeholder)) }
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

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing + tokens.itemSpacing)) }
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
        // 可选协议取**这张 Key 配的协议**，不是页头那排 chip：chip 是"这家实际在跑什么"，
        // 而这一个还没建出来。拿 chip 当选项会让只有 Anthropic 的一家在加第一个模型时
        // 只剩 Chat 可选（v3 起协议归属于 Key）。
        protocols = state.keys
            .firstOrNull { it.id == (addModelKeyId ?: editingModel?.keyId) }
            ?.settings?.protocols
            ?.mapNotNull { Protocol.fromWireName(it) }
            ?.ifEmpty { listOf(Protocol.CHAT) }
            ?: listOf(Protocol.CHAT),
        onDismiss = {
            addModelKeyId = null
            editingModel = null
        },
        onConfirm = { keyId, modelId, protocol, displayName ->
            val editing = editingModel
            if (editing == null) {
                onAddModel(keyId, modelId, protocol)
            } else {
                onUpdateModel(editing.id, modelId, protocol, displayName)
            }
            addModelKeyId = null
            editingModel = null
        },
        onRequestDelete = { model ->
            pendingDeleteModelId = model.id
            editingModel = null
        },
    )

    // 新建账号的默认名（「账号 N」）：先在组合期把序号与模板拼好，再交给表单。
    // 表单的默认值 lambda 不在组合上下文里，不能就地调 stringResource。
    val nextAccountLabel = stringResource(Res.string.editor_default_account_name, state.accounts.size + 1)

    AccountEditorSheet(
        target = accountEditor,
        revealed = revealedAccount,
        defaultNewLabel = { nextAccountLabel },
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
        // 破坏性动作给一个看得见的退路（AppDialog 的契约），别让用户靠点空白处逃生。
        dismissText = stringResource(Res.string.dialog_cancel),
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
        dismissText = stringResource(Res.string.dialog_cancel),
        onConfirm = {
            pendingDeleteAccountId?.let(onDeleteAccount)
            pendingDeleteAccountId = null
        },
    )
}

/**
 * 空态的一句话。**只有字、没有入口**：区块标题行右侧已经有加号按钮，
 * 这里再给一条带箭头的行入口就是同一件事画两遍，而且行入口读起来像"还能进下一页"。
 */
@Composable
private fun HintText(text: String) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
    ) {
        AppText(text = text, style = AppTextStyle.Secondary, color = appSecondaryTextColor)
    }
}

/**
 * 新增密钥：先选导入方式，再进入对应流程。
 *
 * 用**底部弹层** + 并排两个标准按钮：居中宽版对话框悬在屏幕中部，两个选项离拇指
 * 远、也不像系统里的其它弹层；同一页的账号编辑层已经在底部了，二选一跟着走。
 */
@Composable
private fun AddKeyDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onCurlImport: () -> Unit,
    onManualAddKey: () -> Unit,
) {
    val tokens = LocalAppTokens.current

    AppBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.detail_add_key),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppDialogTextButton(
                text = stringResource(Res.string.import_title),
                onClick = onCurlImport,
                modifier = Modifier.weight(1f),
            )
            AppDialogTextButton(
                text = stringResource(Res.string.import_manual),
                onClick = onManualAddKey,
                modifier = Modifier.weight(1f),
                primary = true,
            )
        }
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
    /** 新建账号的默认名（「账号 N」）。由页面注入：这一层读不到资源上限的序号。 */
    defaultNewLabel: () -> String = { "" },
) {
    val tokens = LocalAppTokens.current
    val account = (target as? AccountEditorTarget.Edit)?.account
    // 新建时预填默认名：空名称会让账号行的标题落到兜底串（用户点名的问题）。
    val label = rememberAppTextFieldState(
        if (account != null) account.label else defaultNewLabel(),
    )
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
        label.setText(
            when (target) {
                is AccountEditorTarget.Edit -> target.account.label
                // 切到 New 时重新取一次默认名：连续新建两个账号，序号要往后走。
                AccountEditorTarget.New -> defaultNewLabel()
                null -> ""
            },
        )
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
        // 不再自己 verticalScroll：AppBottomSheet 的内容块已经可滚，这里再套一层只是
        // 空转（外层给了无限高度，内层永远不触发滚动），以前这段是为了绕开包装层不滚。
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            // 名称与备注同卡两条输入框：以前各包一张 AppCard，两张 16dp 内边距 +
            // 卡间距叠出 40dp 的空隙，看着像断成了两节。它们都是「给这条账号填字」，
            // 归一块；两件事靠各自的 label 区分，不需要容器再表达。
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppTextField(
                    state = label,
                    label = stringResource(Res.string.editor_name),
                    // 名称必填：空名称的账号在列表里只剩遮蔽串，认不出是哪个账号。
                    errorText = if (label.text.isBlank()) {
                        stringResource(Res.string.editor_error_missing_name)
                    } else {
                        null
                    },
                )
                AppTextField(
                    state = note,
                    label = stringResource(Res.string.editor_note),
                    modifier = Modifier.padding(top = tokens.itemSpacing),
                )
            }
            // 三种登录方式都是「开 / 关」，用开关而不是 chip：chip 让"选中"看起来像筛选，
            // 而这里每一项都是一个独立的是非题；开关也自带 ON/OFF 文字，比只有颜色的
            // 选中态更好读（红线 17：状态不能只靠颜色）。
            // 登录方式本身成一块（一张卡 + 一个标题），与上面的文字块分开。
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppText(
                    text = stringResource(Res.string.detail_account_login_methods),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
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
            }
            if (usesPassword) {
                // 凭据单独成块：这两格是这一层里唯一的秘密，和"登录方式有哪些"不是一回事，
                // 摆在同一张卡里会让开关与输入框混成一片。
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    // 用户名与密码直接预填进输入框（查看与编辑合一），密码默认遮蔽，
                    // 右侧眼睛展开。明文只在弹层存活期间留在输入框里，关掉即擦。
                    val revealCd = stringResource(Res.string.secret_reveal_cd)
                    val concealCd = stringResource(Res.string.secret_conceal_cd)
                    AppSecretTextField(
                        state = username,
                        label = stringResource(Res.string.detail_account_username),
                        supportingText = account?.let { stringResource(Res.string.detail_account_keep_secret) },
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
            }
            // 复制是一条独立动作，单独成块；下面那排按钮是收尾动作，两者不同类。
            // 只有确实解出了凭据才给「复制」：这条账号可能只记了登录方式（没有用户名密码），
            // 那种情况下复制是无内容的死按钮。
            account?.let { row ->
                val canCopy = revealed?.accountId == row.id &&
                    (revealed.username != null || revealed.password != null)
                if (canCopy) {
                    // 与上面登录方式的组同构：AppCard 会再叠 16dp 内边距，把 56dp 的行撑成 88dp。
                    AppPreferenceGroup(modifier = Modifier.fillMaxWidth(), inset = false) {
                        AppActionRow(
                            text = stringResource(Res.string.secret_copy_cd),
                            onClick = { onCopy(row.id) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            // 收尾动作一排按钮：编辑时左「删除」右「保存」，新建时只有「保存」占满整行。
            // 两个都是按钮（不是行入口）：它们是这一层的结局，必须一眼看出按哪个。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.itemSpacing),
                horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                account?.let { row ->
                    AppDialogTextButton(
                        text = stringResource(Res.string.detail_account_delete),
                        onClick = {
                            username.clear()
                            password.clear()
                            onDelete(row.id)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                AppDialogTextButton(
                    text = stringResource(Res.string.editor_save),
                    onClick = {
                        // 名称必填：为空不保存。输入框下方已有错误说明，这里直接拦住。
                        if (label.text.isBlank()) return@AppDialogTextButton
                        val usernameChars = username.chars.takeIf { it.isNotEmpty() }
                        val passwordChars = password.chars.takeIf { it.isNotEmpty() }
                        username.clear()
                        password.clear()
                        onSave(label.text, note.text, usernameChars, passwordChars, methods, usesPassword)
                    },
                    modifier = Modifier.weight(1f),
                    primary = true,
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
    onConfirm: (Long, String, Protocol, String?) -> Unit,
    onRequestDelete: (UiModelRow) -> Unit,
) {
    val tokens = LocalAppTokens.current
    val modelId = rememberAppTextFieldState()
    val displayName = rememberAppTextFieldState()
    var protocol by remember { mutableStateOf(Protocol.CHAT) }
    val targetId = editing?.id

    LaunchedEffect(keyId, targetId) {
        if (keyId == null && editing == null) return@LaunchedEffect
        val source = editing
        modelId.setText(source?.modelId.orEmpty())
        displayName.setText(source?.displayName.orEmpty())
        protocol = source?.protocol?.let { Protocol.fromWireName(it) } ?: protocols.first()
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
 *
 * 模型区的两个按钮是**互斥**的，跟 Key 设置页同一套规则：自动获取开着时只给「刷新」
 * （列表由上游同步维护，手动加一条下次同步也编辑不了），关掉自动获取才给「添加」。
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
    // 自动获取：列表由探测/同步维护，不给编辑入口；手动列表才可点进编辑。
    val autoModels = row.settings.probeModels
    // 模型列表默认收起。这家有几把 Key 就是几份列表，全展开会把页面拉到好几屏，
    // 而"有哪些模型"在多数时候不是打开这一页要办的事。收起态给出数量，知道里面有多少。
    var modelsExpanded by remember(row.id) { mutableStateOf(false) }
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
            )
            if (models.isNotEmpty() && !modelsExpanded) {
                AppText(
                    text = stringResource(Res.string.detail_models_count_short, models.size),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            AppIconButton(
                icon = if (autoModels) AppIcon.Refresh else AppIcon.Add,
                contentDescription = stringResource(
                    if (autoModels) Res.string.detail_models_refresh else Res.string.detail_add_model,
                ),
                onClick = if (autoModels) onRefreshModels else onAddModel,
            )
            if (models.isNotEmpty()) {
                AppIconButton(
                    icon = if (modelsExpanded) AppIcon.Collapse else AppIcon.Expand,
                    contentDescription = stringResource(
                        if (modelsExpanded) {
                            Res.string.detail_models_collapse_cd
                        } else {
                            Res.string.detail_models_expand_cd
                        },
                    ),
                    onClick = { modelsExpanded = !modelsExpanded },
                )
            }
        }

        if (models.isEmpty()) {
            AppText(
                text = stringResource(
                    if (autoModels) {
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
            // 展开/收起给高度过渡：列表可能有几十行，一帧跳开会让人分不清是"展开了"
            // 还是"页面被替换了"。同时淡入淡出——只滑高度的话，收起时文字会被裁着走。
            AnimatedVisibility(
                visible = modelsExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    models.forEachIndexed { index, model ->
                        ModelRow(
                            row = model,
                            // 这一页不重复模型的可达性与协议：协议同一家的模型几乎全一样，
                            // 可达性没有 Key 页的探测入口，摆在这里都是只看不动的字。
                            showProbe = false,
                            showProtocol = false,
                            onClick = if (autoModels) null else ({ onEditModel(model) }),
                        )
                        if (index != models.lastIndex) {
                            AppDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 信息卡有没有东西可画。
 *
 * 抽成纯函数是为了能在 JVM 单测里钉住它——"这家什么都没填"是个正常状态（刚建好），
 * 而空卡片占着首屏是实打实的视觉噪音。
 *
 * 判定只看**这家自己记着的**三样：备注、官网地址、协议（由模型并集算出）。
 * host 不算：它是从 Key 的 apiRoot 推出来的，没有 Key 时是空串，而有 Key 时
 * 也已经有别的内容要画了。
 */
internal fun hasProviderInfo(provider: UiProviderRow): Boolean =
    !provider.note.isNullOrBlank() ||
        !provider.websiteUrl.isNullOrBlank() ||
        provider.protocols.isNotEmpty()

/**
 * 头部信息卡：备注、官网与协议。
 *
 * 官网那一行**就是**域名行，不再另外摆一条「官网: https://…」的动作行——同一个地址
 * 出现两次，第二次还带着箭头，读起来像另一个入口。整行可点，点了打开浏览器。
 *
 * 右侧是官网连通性延迟，只在**连通**时出现（失败没有可显示的耗时，硬给一个数
 * 反而像"通了但很慢"）；它与左侧两行文字上下居中，行高不吃亏。
 */
@Composable
private fun InfoCard(state: ProviderDetailUiState) {
    val tokens = LocalAppTokens.current
    val provider = state.provider
    val website = provider.websiteUrl?.takeIf { it.isNotBlank() }
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
    ) {        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                provider.note?.let { note ->
                    AppText(text = note, style = AppTextStyle.Body)
                }
                val site = website ?: provider.host
                if (site.isNotBlank()) {
                    // 不设 maxLines：这是唯一完整展示官网地址的地方，截断会让人看不出
                    // 到底是哪个域名。长了就换行，右侧的延迟照样与整块上下居中。
                    AppText(
                        text = site,
                        style = AppTextStyle.Secondary,
                        color = appSecondaryTextColor,
                        fontFamily = tokens.monoFontFamily,
                        modifier = if (website == null) {
                            Modifier
                        } else {
                            Modifier.clickable { openExternalUrl(website) }
                        },
                    )
                }
            }
            provider.reachabilityLatencyMs?.let { latency ->
                AppText(
                    text = stringResource(Res.string.detail_reachability_latency, latency),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                )
            }
        }
        // 协议 = 这家所有模型的协议并集（在 ViewModel 里算好）。配了某个协议但一个
        // 模型都没跑它，就不该在这里出现。
        if (provider.protocols.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = tokens.itemSpacing),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                provider.protocols.forEach { protocol -> AppChip(text = protocolLabel(protocol)) }
            }
        }
    }
}

/**
 * 余额卡。金额与「什么时候查的」必须一起给：一个没有时间的数字，用户没法判断
 * 它是刚查到的还是三天前的。三种状态在这里都可分辨——有金额 / 查询失败 / 还没查过。
 */
@Composable
private fun BalanceCard(state: ProviderDetailUiState) {
    val tokens = LocalAppTokens.current
    val provider = state.provider
    val balance = provider.balance
    val balanceFailed = balance == null && provider.balanceFailed
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
                text = stringResource(Res.string.detail_provider_balance_total),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.weight(1f),
            )
            provider.balanceCheckedAt?.let { checkedAt ->
                AppText(
                    text = relativeLabel(state.nowMs, checkedAt),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                )
            }
        }
        AppText(
            text = when {
                balance != null -> balance.toDisplay()
                balanceFailed -> stringResource(Res.string.balance_failed_section)
                else -> stringResource(Res.string.detail_balance_not_checked)
            },
            style = if (balanceFailed) AppTextStyle.Secondary else AppTextStyle.Title,
            color = if (balanceFailed) LocalStatusPalette.current.warn else Color.Unspecified,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 金额 + 币种符号。符号由币种查表（红线 15 不硬编码）。 */
private fun com.lc33.tokenvault.screens.model.UiMoney.toDisplay(): String =
    com.lc33.tokenvault.balance.FormatMoney.format(amount.toDoubleOrNull() ?: 0.0, currency)
