package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import com.lc33.tokenvault.platform.PlatformBackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.auth_styles
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.balance_kinds
import tokenvault.shared.generated.resources.editor_advanced_collapse
import tokenvault.shared.generated.resources.editor_advanced_expand
import tokenvault.shared.generated.resources.editor_allow_insecure
import tokenvault.shared.generated.resources.editor_auth_style
import tokenvault.shared.generated.resources.editor_auth_style_summary
import tokenvault.shared.generated.resources.editor_balance_default_key_hint
import tokenvault.shared.generated.resources.editor_balance_kind
import tokenvault.shared.generated.resources.editor_balance_kind_summary
import tokenvault.shared.generated.resources.editor_balance_none_hint
import tokenvault.shared.generated.resources.editor_balance_token
import tokenvault.shared.generated.resources.editor_balance_token_hint
import tokenvault.shared.generated.resources.editor_balance_user_id
import tokenvault.shared.generated.resources.editor_base_url
import tokenvault.shared.generated.resources.editor_base_url_hint
import tokenvault.shared.generated.resources.editor_color
import tokenvault.shared.generated.resources.editor_discard_confirm
import tokenvault.shared.generated.resources.editor_discard_summary
import tokenvault.shared.generated.resources.editor_discard_title
import tokenvault.shared.generated.resources.editor_group
import tokenvault.shared.generated.resources.editor_insecure_warning
import tokenvault.shared.generated.resources.editor_name
import tokenvault.shared.generated.resources.editor_note
import tokenvault.shared.generated.resources.editor_path_override
import tokenvault.shared.generated.resources.editor_path_override_hint
import tokenvault.shared.generated.resources.editor_pinned
import tokenvault.shared.generated.resources.editor_preview_anthropic
import tokenvault.shared.generated.resources.editor_preview_balance
import tokenvault.shared.generated.resources.editor_preview_chat
import tokenvault.shared.generated.resources.editor_preview_models
import tokenvault.shared.generated.resources.editor_preview_responses
import tokenvault.shared.generated.resources.editor_preview_title
import tokenvault.shared.generated.resources.editor_probe_balance
import tokenvault.shared.generated.resources.editor_probe_balance_summary
import tokenvault.shared.generated.resources.editor_probe_disabled_note
import tokenvault.shared.generated.resources.editor_probe_enabled
import tokenvault.shared.generated.resources.editor_probe_enabled_summary
import tokenvault.shared.generated.resources.editor_probe_keys
import tokenvault.shared.generated.resources.editor_probe_keys_summary
import tokenvault.shared.generated.resources.editor_probe_models
import tokenvault.shared.generated.resources.editor_probe_models_summary
import tokenvault.shared.generated.resources.editor_probe_reachability
import tokenvault.shared.generated.resources.editor_probe_reachability_summary
import tokenvault.shared.generated.resources.editor_save
import tokenvault.shared.generated.resources.editor_section_advanced
import tokenvault.shared.generated.resources.editor_section_balance
import tokenvault.shared.generated.resources.editor_section_basic
import tokenvault.shared.generated.resources.editor_section_client
import tokenvault.shared.generated.resources.editor_section_look
import tokenvault.shared.generated.resources.editor_section_probe
import tokenvault.shared.generated.resources.editor_section_protocols
import tokenvault.shared.generated.resources.editor_timeout
import tokenvault.shared.generated.resources.editor_timeout_hint
import tokenvault.shared.generated.resources.editor_url_err_empty
import tokenvault.shared.generated.resources.editor_url_err_host
import tokenvault.shared.generated.resources.editor_url_err_query
import tokenvault.shared.generated.resources.editor_url_err_scheme
import tokenvault.shared.generated.resources.editor_website
import tokenvault.shared.generated.resources.provider_editor_title
import tokenvault.shared.generated.resources.settings_profiles
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.endpoint.EndpointError
import com.lc33.tokenvault.endpoint.NormalizeResult
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
import com.lc33.tokenvault.ui.miuix.AppSecretTextField
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextFieldState
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.miuix.rememberSecretTextFieldState
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
 *
 * **文本框的内容在保存时才交出去**（[onSave] 带着一个补全过的 [ProviderDraft]）。
 * 每敲一个字都 `onChange` 一次会让整棵树跟着重组，而这一页有二十多个控件；
 * 反过来，让 ViewModel 自己去读文本框是做不到的——那些状态属于这一层。
 *
 * **余额访问令牌不进 draft**：它是明文秘密，只允许活在能擦掉的 `CharArray` 里（红线 1），
 * 而 [ProviderDraft] 是 Compose 长期持有的状态。所以它作为 [onSave] 的第二个参数单独交出，
 * 并且用 `rememberSecretTextFieldState`（不进 saved instance state）+ 密码键盘。
 * `null` 表示"这一格没动过"，仓库据此保留库里那份密文——否则每次改备注都会把令牌清掉。
 */
@Composable
fun ProviderEditorScreen(
    draft: ProviderDraft,
    groupNames: List<String>,
    profileNames: List<String>,
    onChange: (ProviderDraft) -> Unit,
    onBack: () -> Unit,
    onSave: (ProviderDraft, CharArray?) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    var showDiscardDialog by remember { mutableStateOf(false) }

    val name = rememberAppTextFieldState(draft.name)
    val note = rememberAppTextFieldState(draft.note)
    val website = rememberAppTextFieldState(draft.website)
    val baseUrl = rememberAppTextFieldState(draft.baseUrl)
    val balanceUserId = rememberAppTextFieldState(draft.balanceUserId)

    // 令牌那一格刻意用不可保存的状态：可保存的会被序列化进 Activity 的 saved instance
    // state（交给 system_server 放在 Bundle 里），转屏就发生一次，而那是明文秘密
    val balanceToken = rememberSecretTextFieldState()

    /**
     * 提交时把这一层持有的文本补回 draft；令牌单独走，不进 draft。
     *
     * **`draft` 必须经 [rememberUpdatedState] 读**：保存按钮在 `AppTopBar` 的 `actions`
     * 槽里，那个槽不保证跟着每次重组重建，于是 `::submit` 可能仍然是**上一次组合**里
     * 那个闭包、带着旧的 draft。真机上撞到过一次：下拉里选了分组、界面也显示成了新值，
     * 保存下去的却是 `groupIndex = 0`，于是"改分组"永远不生效而且没有任何报错。
     */
    val currentDraft by rememberUpdatedState(draft)

    fun submit() {
        onSave(
            currentDraft.copy(
                name = name.text.trim(),
                note = note.text.trim(),
                website = website.text.trim(),
                baseUrl = baseUrl.text.trim(),
                balanceUserId = balanceUserId.text.trim(),
            ),
            // 空 = 没动过（保留库里那份），非空 = 用户填了新的。"清掉令牌"要显式的删除动作，
            // 不能靠"把输入框清空再保存"——那和"没动过"在界面上长得一模一样
            balanceToken.chars.takeIf { it.isNotEmpty() },
        )
    }

    // dirty 只看地址与名称这两处足够代表"用户动过东西"了。完整的逐字段比对在
    // ViewModel 的 save 流程里做（它拿到的 draft 与这里的文本态是同一份数据），
    // 这里的判定只服务于"返回时要不要弹放弃对话框"。
    val dirty = name.text != draft.name || baseUrl.text != draft.baseUrl
    PlatformBackHandler(enabled = dirty) { showDiscardDialog = true }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.provider_editor_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = { if (dirty) showDiscardDialog = true else onBack() },
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
                // 软键盘不会自己让位：enableEdgeToEdge() 之后 manifest 的 adjustResize 失效，
                // MIUIX Scaffold 的默认 insets 也不含 ime，不加这一条最下面那几格填不了
                .imePadding()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
        ) {
            item { SectionTitle(text = stringResource(Res.string.editor_section_basic)) }
            item {
                Column(
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    AppTextField(state = name, label = stringResource(Res.string.editor_name))
                    AppTextField(state = note, label = stringResource(Res.string.editor_note))
                    AppTextField(state = website, label = stringResource(Res.string.editor_website))
                    AppTextField(
                        state = baseUrl,
                        label = stringResource(Res.string.editor_base_url),
                        supportingText = stringResource(Res.string.editor_base_url_hint),
                    )
                }
            }
            item { EndpointPreviewCard(baseUrl.text, draft, onChange) }

            item { SectionTitle(text = stringResource(Res.string.editor_section_look)) }
            item {
                AppDropdownRow(
                    title = stringResource(Res.string.editor_group),
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
                        text = stringResource(Res.string.editor_color),
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
                    title = stringResource(Res.string.editor_pinned),
                    checked = draft.pinned,
                    onCheckedChange = { onChange(draft.copy(pinned = it)) },
                )
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_protocols)) }
            item { ProtocolChips(draft, onChange) }

            item { SectionTitle(text = stringResource(Res.string.editor_section_balance)) }
            item {
                AppDropdownRow(
                    title = stringResource(Res.string.editor_balance_kind),
                    summary = stringResource(Res.string.editor_balance_kind_summary),
                    items = stringArrayResource(Res.array.balance_kinds).toList(),
                    selectedIndex = draft.balanceKindIndex,
                    onSelect = { onChange(draft.copy(balanceKindIndex = it)) },
                )
            }
            item { BalanceFields(draft, balanceToken, balanceUserId) }

            item { SectionTitle(text = stringResource(Res.string.editor_section_client)) }
            item {
                AppDropdownRow(
                    title = stringResource(Res.string.settings_profiles),
                    items = profileNames,
                    selectedIndex = draft.profileIndex,
                    onSelect = { onChange(draft.copy(profileIndex = it)) },
                )
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_probe)) }
            item { ProbeBlock(draft, onChange) }

            item { SectionTitle(text = stringResource(Res.string.editor_section_advanced)) }
            item { AdvancedBlock(draft, onChange) }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }

    AppDialog(
        show = showDiscardDialog,
        onDismissRequest = { showDiscardDialog = false },
        title = stringResource(Res.string.editor_discard_title),
        summary = stringResource(Res.string.editor_discard_summary),
        confirmText = stringResource(Res.string.editor_discard_confirm),
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
        AppText(text = stringResource(Res.string.editor_preview_title), style = AppTextStyle.Subtitle)
        when (result) {
            is NormalizeResult.Err -> AppText(
                text = stringResource(
                    when (result.error) {
                        EndpointError.Empty -> Res.string.editor_url_err_empty
                        EndpointError.UnsupportedScheme -> Res.string.editor_url_err_scheme
                        EndpointError.HasQueryOrFragment -> Res.string.editor_url_err_query
                        EndpointError.NoHost -> Res.string.editor_url_err_host
                    },
                ),
                style = AppTextStyle.Secondary,
                color = palette.error,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )

            is NormalizeResult.Ok -> {
                val e = result.endpoints
                Column(modifier = Modifier.padding(top = tokens.itemSpacing)) {
                    PreviewLine(stringResource(Res.string.editor_preview_chat), e.byProtocol[Protocol.CHAT])
                    PreviewLine(stringResource(Res.string.editor_preview_responses), e.byProtocol[Protocol.RESPONSES])
                    PreviewLine(stringResource(Res.string.editor_preview_anthropic), e.byProtocol[Protocol.ANTHROPIC])
                    PreviewLine(stringResource(Res.string.editor_preview_models), e.modelsUrl)
                    PreviewLine(stringResource(Res.string.editor_preview_balance), e.origin)
                }
                if (e.insecure) {
                    // §7.5：明文 HTTP 由应用层拦，而且要用户显式打开，不是弹一下就过
                    AppText(
                        text = stringResource(Res.string.editor_insecure_warning),
                        style = AppTextStyle.Footnote,
                        color = palette.warn,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_allow_insecure),
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
 *
 * 两个状态由上一层持有：令牌要在保存时交给仓库，而这一段是个 `item {}`，
 * 划出屏幕就会被回收——状态留在这里的表现是"滚下去再滚回来，刚填的令牌不见了"。
 */
@Composable
private fun BalanceFields(
    draft: ProviderDraft,
    token: AppTextFieldState,
    userId: AppTextFieldState,
) {
    val tokens = LocalAppTokens.current

    Column(
        modifier = Modifier.padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        when (draft.balanceKindIndex) {
            // newapi
            1 -> {
                AppSecretTextField(
                    state = token,
                    label = stringResource(Res.string.editor_balance_token),
                    supportingText = stringResource(Res.string.editor_balance_token_hint),
                )
                AppTextField(state = userId, label = stringResource(Res.string.editor_balance_user_id))
            }
            // none
            0 -> AppText(
                text = stringResource(Res.string.editor_balance_none_hint),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
            else -> AppText(
                text = stringResource(Res.string.editor_balance_default_key_hint),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
        }
    }
}

/**
 * 探测开关，**每家单独一份**（红线 36）。
 *
 * 这一段刻意不写"从设置继承而来"：用户在这里看到的应该是"这一家现在是什么样"，
 * 而不是"它从哪儿继承来的"——继承那件事只在设置那一页说一次。
 *
 * 总闸关掉时下面四项全部灰掉而不是隐藏：藏起来会让人以为设置丢了。
 */
@Composable
private fun ProbeBlock(draft: ProviderDraft, onChange: (ProviderDraft) -> Unit) {
    val tokens = LocalAppTokens.current
    val palette = LocalStatusPalette.current
    Column {
        AppSwitchRow(
            title = stringResource(Res.string.editor_probe_enabled),
            summary = stringResource(Res.string.editor_probe_enabled_summary),
            checked = draft.probeEnabled,
            onCheckedChange = { onChange(draft.copy(probeEnabled = it)) },
        )
        if (!draft.probeEnabled) {
            AppText(
                text = stringResource(Res.string.editor_probe_disabled_note),
                style = AppTextStyle.Footnote,
                color = palette.warn,
                modifier = Modifier.padding(
                    horizontal = tokens.screenPadding,
                    vertical = tokens.itemSpacing,
                ),
            )
        }
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
        // 这一项要钱，所以副文案不是"要不要自动跑"而是"这一家允不允许被这样测"
        AppSwitchRow(
            title = stringResource(Res.string.editor_probe_models),
            summary = stringResource(Res.string.editor_probe_models_summary),
            checked = draft.probeModels,
            onCheckedChange = { onChange(draft.copy(probeModels = it)) },
            enabled = draft.probeEnabled,
        )
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
                if (expanded) Res.string.editor_advanced_collapse else Res.string.editor_advanced_expand,
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
                label = stringResource(Res.string.editor_path_override),
                supportingText = stringResource(Res.string.editor_path_override_hint),
            )
            AppTextField(
                state = timeout,
                label = stringResource(Res.string.editor_timeout),
                supportingText = stringResource(Res.string.editor_timeout_hint),
            )
        }
        AppDropdownRow(
            title = stringResource(Res.string.editor_auth_style),
            summary = stringResource(Res.string.editor_auth_style_summary),
            items = stringArrayResource(Res.array.auth_styles).toList(),
            selectedIndex = draft.authStyleIndex,
            onSelect = { onChange(draft.copy(authStyleIndex = it)) },
        )
    }
}
