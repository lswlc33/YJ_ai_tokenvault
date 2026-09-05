package com.lc33.tokenvault.screens.manage

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.R
import com.lc33.tokenvault.endpoint.EndpointError
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.endpoint.Protocol
import com.lc33.tokenvault.endpoint.normalizeBaseUrl
import com.lc33.tokenvault.screens.model.ProviderDraft
import com.lc33.tokenvault.ui.common.ColorSwatchRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 供应商编辑（计划.md §13.4）。
 *
 * 这一页是红线 16 的验收对象：每个持久化字段都要能在这里找到入口。所以看起来长是
 * 应该的——短了就说明有字段没有入口。
 *
 * 最有价值的一块是**地址下方的实时规范化预览**：它把"我填的地址会变成哪三条 URL"
 * 直接摊开，是让用户自己发现地址填错的唯一有效手段。M0.5 那次 DeepSeek 的
 * `/v1/messages` → 404 空 body 就是"猜错路径时上游一个字都不给"的例子，
 * 所以必须在录入阶段看见。
 */
@Composable
fun ProviderEditorScreen(
    draft: ProviderDraft,
    groupNames: List<String>,
    profileNames: List<String>,
    onChange: (ProviderDraft) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    var showDiscardDialog by remember { mutableStateOf(false) }

    val name = rememberAppTextFieldState(draft.name)
    val note = rememberAppTextFieldState(draft.note)
    val website = rememberAppTextFieldState(draft.website)
    val baseUrl = rememberAppTextFieldState(draft.baseUrl)

    // dirty 只看地址与名称这两处足够代表"用户动过东西"了；真正的 dirty 判定
    // 在 M3 接真数据时由 ViewModel 比对整个 draft。
    val dirty = name.text != draft.name || baseUrl.text != draft.baseUrl
    BackHandler(enabled = dirty) { showDiscardDialog = true }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.provider_editor_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(R.string.back_cd),
                        onClick = { if (dirty) showDiscardDialog = true else onBack() },
                    )
                },
                actions = {
                    AppIconButton(
                        icon = AppIcon.Ok,
                        contentDescription = stringResource(R.string.editor_save),
                        onClick = onSave,
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
        ) {
            item { SectionTitle(text = stringResource(R.string.editor_section_basic)) }
            item {
                Column(
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    AppTextField(state = name, label = stringResource(R.string.editor_name))
                    AppTextField(state = note, label = stringResource(R.string.editor_note))
                    AppTextField(state = website, label = stringResource(R.string.editor_website))
                    AppTextField(
                        state = baseUrl,
                        label = stringResource(R.string.editor_base_url),
                        supportingText = stringResource(R.string.editor_base_url_hint),
                    )
                }
            }
            item { EndpointPreviewCard(baseUrl.text, draft, onChange) }

            item { SectionTitle(text = stringResource(R.string.editor_section_look)) }
            item {
                AppDropdownRow(
                    title = stringResource(R.string.editor_group),
                    items = groupNames,
                    selectedIndex = draft.groupIndex,
                    onSelect = { onChange(draft.copy(groupIndex = it)) },
                )
            }
            item {
                Column(
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                ) {
                    AppText(
                        text = stringResource(R.string.editor_color),
                        style = AppTextStyle.Secondary,
                        modifier = Modifier.padding(bottom = tokens.itemSpacing),
                    )
                    ColorSwatchRow(
                        selectedIndex = draft.colorIndex,
                        onSelect = { onChange(draft.copy(colorIndex = it)) },
                    )
                }
            }
            item {
                AppSwitchRow(
                    title = stringResource(R.string.editor_pinned),
                    checked = draft.pinned,
                    onCheckedChange = { onChange(draft.copy(pinned = it)) },
                )
            }

            item { SectionTitle(text = stringResource(R.string.editor_section_protocols)) }
            item { ProtocolChips(draft, onChange) }

            item { SectionTitle(text = stringResource(R.string.editor_section_balance)) }
            item {
                AppDropdownRow(
                    title = stringResource(R.string.editor_balance_kind),
                    summary = stringResource(R.string.editor_balance_kind_summary),
                    items = stringArrayResource(R.array.balance_kinds).toList(),
                    selectedIndex = draft.balanceKindIndex,
                    onSelect = { onChange(draft.copy(balanceKindIndex = it)) },
                )
            }
            item { BalanceFields(draft) }

            item { SectionTitle(text = stringResource(R.string.editor_section_client)) }
            item {
                AppDropdownRow(
                    title = stringResource(R.string.settings_profiles),
                    items = profileNames,
                    selectedIndex = draft.profileIndex,
                    onSelect = { onChange(draft.copy(profileIndex = it)) },
                )
            }

            item { SectionTitle(text = stringResource(R.string.editor_section_advanced)) }
            item { AdvancedBlock(draft, onChange) }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }

    AppDialog(
        show = showDiscardDialog,
        onDismissRequest = { showDiscardDialog = false },
        title = stringResource(R.string.editor_discard_title),
        summary = stringResource(R.string.editor_discard_summary),
        confirmText = stringResource(R.string.editor_discard_confirm),
        onConfirm = {
            showDiscardDialog = false
            onBack()
        },
    )
}

/**
 * 地址的实时规范化预览。
 *
 * 三条协议 URL、模型列表 URL、余额默认地址全部摊开——这是让用户自己发现地址填错
 * 最有效的手段。带 query 的地址在这里就报错，而不是等到探测时收一个 401。
 */
@Composable
private fun EndpointPreviewCard(
    input: String,
    draft: ProviderDraft,
    onChange: (ProviderDraft) -> Unit,
) {
    val tokens = LocalAppTokens.current
    val palette = LocalStatusPalette.current
    val result = remember(input, draft.pathOverrideAnthropic) {
        normalizeBaseUrl(
            input,
            if (draft.pathOverrideAnthropic.isBlank()) {
                emptyMap()
            } else {
                mapOf(Protocol.ANTHROPIC to draft.pathOverrideAnthropic)
            },
        )
    }

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
    ) {
        AppText(text = stringResource(R.string.editor_preview_title), style = AppTextStyle.Subtitle)
        when (result) {
            is NormalizeResult.Err -> AppText(
                text = stringResource(
                    when (result.error) {
                        EndpointError.Empty -> R.string.editor_url_err_empty
                        EndpointError.UnsupportedScheme -> R.string.editor_url_err_scheme
                        EndpointError.HasQueryOrFragment -> R.string.editor_url_err_query
                        EndpointError.NoHost -> R.string.editor_url_err_host
                    },
                ),
                style = AppTextStyle.Secondary,
                color = palette.error,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )

            is NormalizeResult.Ok -> {
                val e = result.endpoints
                Column(modifier = Modifier.padding(top = tokens.itemSpacing)) {
                    PreviewLine(stringResource(R.string.editor_preview_chat), e.byProtocol[Protocol.CHAT])
                    PreviewLine(stringResource(R.string.editor_preview_responses), e.byProtocol[Protocol.RESPONSES])
                    PreviewLine(stringResource(R.string.editor_preview_anthropic), e.byProtocol[Protocol.ANTHROPIC])
                    PreviewLine(stringResource(R.string.editor_preview_models), e.modelsUrl)
                    PreviewLine(stringResource(R.string.editor_preview_balance), e.origin)
                }
                if (e.insecure) {
                    // §7.5：明文 HTTP 由应用层拦，而且要用户显式打开，不是弹一下就过
                    AppText(
                        text = stringResource(R.string.editor_insecure_warning),
                        style = AppTextStyle.Footnote,
                        color = palette.warn,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                    AppSwitchRow(
                        title = stringResource(R.string.editor_allow_insecure),
                        checked = draft.allowInsecure,
                        onCheckedChange = { onChange(draft.copy(allowInsecure = it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(label: String, url: String?) {
    val tokens = LocalAppTokens.current
    if (url == null) return
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        AppText(text = label, style = AppTextStyle.Footnote, color = appSecondaryTextColor)
        AppText(text = url, style = AppTextStyle.Footnote, fontFamily = tokens.monoFontFamily)
    }
}

@Composable
private fun ProtocolChips(draft: ProviderDraft, onChange: (ProviderDraft) -> Unit) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        Protocol.entries.forEach { protocol ->
            val selected = protocol in draft.protocols
            AppFilterChip(
                text = protocol.name,
                selected = selected,
                onClick = {
                    val next = if (selected) draft.protocols - protocol else draft.protocols + protocol
                    onChange(draft.copy(protocols = next))
                },
            )
        }
    }
}

/**
 * 余额字段按类型动态显示。
 *
 * `newapi` 要的是**独立的访问令牌 + 用户 ID**（在中转站个人安全设置里获取），
 * 与 API 密钥无关；其余适配器复用该供应商的默认 Key。这个区别不写在界面上，
 * 用户一定会把 API Key 填进访问令牌那一格。
 */
@Composable
private fun BalanceFields(draft: ProviderDraft) {
    val tokens = LocalAppTokens.current
    val token = rememberAppTextFieldState(draft.balanceToken)
    val userId = rememberAppTextFieldState(draft.balanceUserId)

    Column(
        modifier = Modifier.padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        when (draft.balanceKindIndex) {
            // newapi
            1 -> {
                AppTextField(
                    state = token,
                    label = stringResource(R.string.editor_balance_token),
                    supportingText = stringResource(R.string.editor_balance_token_hint),
                )
                AppTextField(state = userId, label = stringResource(R.string.editor_balance_user_id))
            }
            // none
            0 -> AppText(
                text = stringResource(R.string.editor_balance_none_hint),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
            else -> AppText(
                text = stringResource(R.string.editor_balance_default_key_hint),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
        }
    }
}

@Composable
private fun AdvancedBlock(draft: ProviderDraft, onChange: (ProviderDraft) -> Unit) {
    val tokens = LocalAppTokens.current
    var expanded by remember { mutableStateOf(false) }
    val override = rememberAppTextFieldState(draft.pathOverrideAnthropic)
    val timeout = rememberAppTextFieldState(draft.timeoutSeconds)

    Column {
        AppTextButton(
            text = stringResource(
                if (expanded) R.string.editor_advanced_collapse else R.string.editor_advanced_expand,
            ),
            onClick = { expanded = !expanded },
            modifier = Modifier.padding(horizontal = tokens.screenPadding),
        )
        if (!expanded) return@Column
        Column(
            modifier = Modifier.padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppTextField(
                state = override,
                label = stringResource(R.string.editor_path_override),
                supportingText = stringResource(R.string.editor_path_override_hint),
            )
            AppTextField(
                state = timeout,
                label = stringResource(R.string.editor_timeout),
                supportingText = stringResource(R.string.editor_timeout_hint),
            )
        }
        AppDropdownRow(
            title = stringResource(R.string.editor_auth_style),
            summary = stringResource(R.string.editor_auth_style_summary),
            items = stringArrayResource(R.array.auth_styles).toList(),
            selectedIndex = draft.authStyleIndex,
            onSelect = { onChange(draft.copy(authStyleIndex = it)) },
        )
    }
}
