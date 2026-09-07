package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.ProviderDetailUiState
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSecretTextField
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
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
    onRevealAccount: (Long) -> Unit,
    onCopyRevealedAccount: (String) -> Unit,
    onCloseAccountReveal: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val provider = state.provider
    var showAddSheet by remember { mutableStateOf(false) }
    var pendingDeleteKeyId by remember { mutableStateOf<Long?>(null) }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = provider.name,
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(R.string.back_cd),
                        onClick = onBack,
                    )
                },
                actions = {
                    AppIconButton(
                        icon = AppIcon.Edit,
                        contentDescription = stringResource(R.string.detail_edit_cd),
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
            item { HeaderCard(state, onRefreshBalance, onProbeProvider) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle(
                        text = stringResource(R.string.detail_section_keys),
                        modifier = Modifier.weight(1f),
                    )
                    AppIconButton(
                        icon = AppIcon.Add,
                        contentDescription = stringResource(R.string.detail_add_key),
                        onClick = { showAddSheet = true },
                    )
                }
            }
            if (state.keys.isEmpty()) {
                item {
                    // 空态给的是后果而不是"暂无数据"：没有密钥这家就探不了、也查不了余额
                    HintCard(
                        text = stringResource(R.string.detail_keys_empty),
                        actionText = stringResource(R.string.detail_add_key),
                        onAction = { showAddSheet = true },
                    )
                }
            } else {
                item {
                    // 长按是隐藏手势，不提示用户永远不知道能单 Key 探测（§8.6）
                    AppText(
                        text = stringResource(R.string.detail_probe_key),
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
                }
            }

            item { SectionTitle(text = stringResource(R.string.detail_section_models)) }
            items(state.models.size) { index -> ModelRow(state.models[index]) }

            item { SectionTitle(text = stringResource(R.string.detail_section_accounts)) }
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

    AddKeySheet(
        show = showAddSheet,
        onDismiss = { showAddSheet = false },
        onConfirm = { label, secret ->
            showAddSheet = false
            onAddKey(label, secret)
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
        onDismiss = onCloseAccountReveal,
    )

    AppDialog(
        show = pendingDeleteKeyId != null,
        onDismissRequest = { pendingDeleteKeyId = null },
        title = stringResource(R.string.detail_key_delete_title),
        summary = stringResource(R.string.detail_key_delete_body),
        confirmText = stringResource(R.string.groups_delete),
        onConfirm = {
            pendingDeleteKeyId?.let(onDeleteKey)
            pendingDeleteKeyId = null
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
        AppTextButton(
            text = actionText,
            onClick = onAction,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
    }
}

/**
 * 新增密钥。
 *
 * 标签是普通输入框，密钥那一格是 [AppSecretTextField] + 不可保存的状态：
 * 转屏时可保存的状态会被序列化进 Activity 的 saved instance state（交给
 * `system_server` 放在 Bundle 里），而那是明文密钥。代价是转屏丢掉已输入的内容，
 * 这个代价是对的。
 */
@Composable
private fun AddKeySheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, CharArray) -> Unit,
) {
    val tokens = LocalAppTokens.current
    val label = rememberSecretTextFieldState()
    val secret = rememberSecretTextFieldState()

    AppBottomSheet(
        show = show,
        onDismissRequest = {
            secret.clear()
            label.clear()
            onDismiss()
        },
        title = stringResource(R.string.detail_add_key),
    ) {
        AppTextField(
            state = label,
            label = stringResource(R.string.detail_key_label),
            supportingText = stringResource(R.string.detail_key_label_hint),
        )
        AppSecretTextField(
            state = secret,
            label = stringResource(R.string.detail_key_secret),
            supportingText = stringResource(R.string.detail_key_secret_hint),
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppTextButton(
            text = stringResource(R.string.editor_save),
            onClick = {
                val chars = secret.chars
                if (chars.isEmpty()) return@AppTextButton
                val text = label.text
                // 先清输入框再交出去：交出去那一份是拷贝，而输入框里那一份归这一层擦
                secret.clear()
                label.clear()
                onConfirm(text, chars)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
        )
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
        title = stringResource(R.string.detail_key_sheet_title),
    ) {
        AppText(
            text = text.orEmpty(),
            style = AppTextStyle.Body,
            fontFamily = tokens.monoFontFamily,
        )
        AppText(
            text = stringResource(R.string.detail_key_reveal_hint),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppTextButton(
            text = stringResource(R.string.secret_copy_cd),
            onClick = onCopy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
        )
        AppTextButton(
            text = stringResource(R.string.detail_key_set_default),
            onClick = onSetDefault,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onDelete != null) {
            AppTextButton(
                text = stringResource(R.string.groups_delete),
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
    onDismiss: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    AppBottomSheet(
        show = account != null,
        onDismissRequest = onDismiss,
        title = account?.label?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.detail_account_sheet_title),
    ) {
        if (account == null) return@AppBottomSheet

        // 用户名
        AppText(
            text = stringResource(R.string.detail_account_username),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
        )
        AppText(
            text = account.username ?: stringResource(R.string.detail_account_none),
            style = AppTextStyle.Body,
            fontFamily = tokens.monoFontFamily,
        )

        // 密码
        AppText(
            text = stringResource(R.string.detail_account_password),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppText(
            text = account.password ?: stringResource(R.string.detail_account_none),
            style = AppTextStyle.Body,
            fontFamily = tokens.monoFontFamily,
        )

        AppText(
            text = stringResource(R.string.detail_account_reveal_hint),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppTextButton(
            text = stringResource(R.string.secret_copy_cd),
            onClick = { onCopy(account.label) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
        )
    }
}

@Composable
private fun HeaderCard(
    state: ProviderDetailUiState,
    onRefreshBalance: () -> Unit,
    onProbeProvider: () -> Unit,
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
        // 余额：三种状态要可区分（§9.3）——有金额 / 查询失败 / 没配置。
        val balance = provider.balance
        when {
            balance != null -> {
                AppText(
                    text = balance.toDisplay(),
                    style = AppTextStyle.Title,
                    modifier = Modifier.padding(top = tokens.itemSpacing),
                )
            }
            provider.balanceFailed -> {
                AppText(
                    text = stringResource(R.string.balance_failed_section),
                    style = AppTextStyle.Secondary,
                    color = LocalStatusPalette.current.warn,
                    modifier = Modifier.padding(top = tokens.itemSpacing),
                )
            }
        }
        AppTextButton(
            text = stringResource(R.string.detail_balance_refresh),
            onClick = onRefreshBalance,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppTextButton(
            text = stringResource(R.string.detail_probe_provider),
            onClick = onProbeProvider,
            modifier = Modifier.padding(top = 4.dp),
        )
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
            text = stringResource(R.string.detail_accounts_empty),
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
        )
    }
}
