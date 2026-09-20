package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.platform.PlatformBackHandler
import com.lc33.tokenvault.screens.model.KeyDraft
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.ui.common.protocolLabel
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.shell.KEY_BALANCE_KINDS
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppCard
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
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.miuix.rememberSecretTextFieldState
import com.lc33.tokenvault.ui.shell.KeyEditorViewModel
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.auth_styles
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.balance_kinds
import tokenvault.shared.generated.resources.detail_add_model
import tokenvault.shared.generated.resources.detail_key_secret
import tokenvault.shared.generated.resources.detail_model_delete
import tokenvault.shared.generated.resources.groups_delete
import tokenvault.shared.generated.resources.dialog_cancel
import tokenvault.shared.generated.resources.detail_models_empty_auto
import tokenvault.shared.generated.resources.detail_models_empty_manual
import tokenvault.shared.generated.resources.detail_models_refresh
import tokenvault.shared.generated.resources.detail_models_section
import tokenvault.shared.generated.resources.editor_allow_insecure
import tokenvault.shared.generated.resources.editor_auth_style
import tokenvault.shared.generated.resources.editor_auth_style_summary
import tokenvault.shared.generated.resources.editor_balance_access_key_id
import tokenvault.shared.generated.resources.editor_balance_custom_currency
import tokenvault.shared.generated.resources.editor_balance_custom_method
import tokenvault.shared.generated.resources.editor_balance_custom_path
import tokenvault.shared.generated.resources.editor_balance_custom_used_path
import tokenvault.shared.generated.resources.editor_balance_custom_value_path
import tokenvault.shared.generated.resources.editor_key_keep_secret
import tokenvault.shared.generated.resources.editor_balance_enabled
import tokenvault.shared.generated.resources.editor_balance_enabled_summary
import tokenvault.shared.generated.resources.editor_balance_kind
import tokenvault.shared.generated.resources.editor_balance_kind_summary
import tokenvault.shared.generated.resources.editor_balance_secret_access_key
import tokenvault.shared.generated.resources.editor_balance_secret_access_key_hint
import tokenvault.shared.generated.resources.editor_balance_token
import tokenvault.shared.generated.resources.editor_balance_token_hint
import tokenvault.shared.generated.resources.editor_balance_user_id
import tokenvault.shared.generated.resources.editor_base_url
import tokenvault.shared.generated.resources.editor_base_url_hint
import tokenvault.shared.generated.resources.editor_discard_confirm
import tokenvault.shared.generated.resources.editor_discard_summary
import tokenvault.shared.generated.resources.editor_discard_title
import tokenvault.shared.generated.resources.editor_error_invalid_timeout
import tokenvault.shared.generated.resources.editor_error_missing_name
import tokenvault.shared.generated.resources.editor_error_missing_secret
import tokenvault.shared.generated.resources.editor_error_no_protocols
import tokenvault.shared.generated.resources.editor_error_save_failed
import tokenvault.shared.generated.resources.editor_models_auto
import tokenvault.shared.generated.resources.editor_models_auto_summary
import tokenvault.shared.generated.resources.editor_models_last_sync
import tokenvault.shared.generated.resources.editor_models_manual_hint
import tokenvault.shared.generated.resources.editor_models_sync_now
import tokenvault.shared.generated.resources.editor_name
import tokenvault.shared.generated.resources.editor_note
import tokenvault.shared.generated.resources.editor_path_override
import tokenvault.shared.generated.resources.editor_path_override_hint
import tokenvault.shared.generated.resources.editor_probe_balance
import tokenvault.shared.generated.resources.editor_probe_balance_summary
import tokenvault.shared.generated.resources.editor_probe_enabled
import tokenvault.shared.generated.resources.editor_probe_enabled_summary
import tokenvault.shared.generated.resources.editor_probe_keys
import tokenvault.shared.generated.resources.editor_probe_keys_summary
import tokenvault.shared.generated.resources.editor_probe_model_reachability
import tokenvault.shared.generated.resources.editor_probe_model_reachability_summary
import tokenvault.shared.generated.resources.editor_probe_reachability
import tokenvault.shared.generated.resources.editor_probe_reachability_summary
import tokenvault.shared.generated.resources.editor_save
import tokenvault.shared.generated.resources.secret_conceal_cd
import tokenvault.shared.generated.resources.secret_reveal_cd
import tokenvault.shared.generated.resources.editor_section_advanced
import tokenvault.shared.generated.resources.editor_section_balance
import tokenvault.shared.generated.resources.editor_section_basic
import tokenvault.shared.generated.resources.editor_section_client
import tokenvault.shared.generated.resources.editor_section_probe
import tokenvault.shared.generated.resources.editor_section_endpoint
import tokenvault.shared.generated.resources.editor_timeout
import tokenvault.shared.generated.resources.editor_timeout_hint
import tokenvault.shared.generated.resources.key_settings_title
import tokenvault.shared.generated.resources.settings_profiles

/** Key 设置页：所有会影响这把 Key 请求行为的配置都在这里。 */
@Composable
fun KeyEditorScreen(
    draft: KeyDraft,
    profileNames: List<String>,
    models: List<UiModelRow>,
    nowMs: Long,
    baseUrlError: String?,
    saveError: KeyEditorViewModel.SaveError?,
    saving: Boolean,
    revealed: KeyEditorViewModel.RevealedSecrets,
    onChange: (KeyDraft) -> Unit,
    onBaseUrlChange: () -> Unit,
    onAddModel: (String, Protocol) -> Unit,
    onUpdateModel: (Long, String, Protocol, String?) -> Unit,
    onDeleteModel: (Long) -> Unit,
    onRefreshModels: () -> Unit,
    onBack: () -> Unit,
    onSave: (KeyDraft, CharArray?, CharArray?) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val palette = LocalStatusPalette.current
    // 眼睛图标的无障碍文案：密钥框与余额令牌框共用同一对。
    val revealCd = stringResource(Res.string.secret_reveal_cd)
    val concealCd = stringResource(Res.string.secret_conceal_cd)

    val label = rememberAppTextFieldState(draft.label)
    val note = rememberAppTextFieldState(draft.note)
    val baseUrl = rememberAppTextFieldState(draft.baseUrl)
    val override = rememberAppTextFieldState(draft.pathOverrideAnthropic)
    val timeout = rememberAppTextFieldState(draft.timeoutSeconds)
    val balanceUserId = rememberAppTextFieldState(draft.balanceUserId)
    val balanceMethod = rememberAppTextFieldState(draft.balanceMethod)
    val balancePath = rememberAppTextFieldState(draft.balancePath)
    val balanceValuePath = rememberAppTextFieldState(draft.balanceValuePath)
    val balanceUsedPath = rememberAppTextFieldState(draft.balanceUsedPath)
    val balanceCurrency = rememberAppTextFieldState(draft.balanceCurrency)
    val secret = rememberSecretTextFieldState()
    val balanceToken = rememberSecretTextFieldState()
    val currentDraft by rememberUpdatedState(draft)
    val initialDraft = remember { draft }
    var showDiscard by remember { mutableStateOf(false) }
    var secretError by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    var timeoutError by remember { mutableStateOf(false) }
    var addModel by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<UiModelRow?>(null) }
    var pendingDeleteModelId by remember { mutableStateOf<Long?>(null) }
    // 编辑已有 Key 时两段明文直接摆在输入框里、**默认可见**：这一页要回答的问题就是
    // "里面存的是什么"，藏起来等于让用户闭着眼睛改配置。眼睛图标可以随时遮回去，
    // 一直摆着不看 30 秒也会自动遮（与密钥详情页的「查看」同一条规则）。
    var secretConcealed by remember { mutableStateOf(false) }
    var tokenConcealed by remember { mutableStateOf(false) }

    // 明文是异步解出来的，到达时再填进输入框（不是构造时给初值）。
    // 只在输入框还空着时填：用户在等待期间已经动过手的话，不能把他的输入冲掉。
    LaunchedEffect(revealed) {
        if (secret.text.isEmpty()) secret.setText(revealed.secret.orEmpty())
        if (balanceToken.text.isEmpty()) balanceToken.setText(revealed.balanceToken.orEmpty())
    }

    LaunchedEffect(secretConcealed) {
        if (!secretConcealed) {
            delay(30_000)
            secretConcealed = true
        }
    }

    LaunchedEffect(tokenConcealed) {
        if (!tokenConcealed) {
            delay(30_000)
            tokenConcealed = true
        }
    }

    // 这两格也要算进"改过没有"：它们不在 draft 里（明文不走草稿），漏掉的表现是
    // "只改了密钥、按返回 → 不弹放弃确认、改动静默消失"。
    val secretChanged = secret.text != revealed.secret.orEmpty()
    val tokenChanged = balanceToken.text != revealed.balanceToken.orEmpty()

    val dirty = label.text != draft.label || note.text != draft.note || baseUrl.text != draft.baseUrl ||
        override.text != draft.pathOverrideAnthropic || timeout.text != draft.timeoutSeconds ||
        balanceUserId.text != draft.balanceUserId || balanceMethod.text != draft.balanceMethod ||
        balancePath.text != draft.balancePath || balanceValuePath.text != draft.balanceValuePath ||
        balanceUsedPath.text != draft.balanceUsedPath || balanceCurrency.text != draft.balanceCurrency ||
        secretChanged || tokenChanged ||
        draft != initialDraft

    LaunchedEffect(baseUrl.state) {
        snapshotFlow { baseUrl.text }.collect { onBaseUrlChange() }
    }

    PlatformBackHandler(enabled = dirty && !saving) { showDiscard = true }

    fun submit() {
        val missingSecret = draft.id == 0L && secret.text.isBlank()
        val missingName = label.text.isBlank()
        val timeoutText = timeout.text.trim()
        val badTimeout = timeoutText.isNotEmpty() && (timeoutText.toIntOrNull() == null || timeoutText.toInt() <= 0)
        secretError = missingSecret
        nameError = missingName
        timeoutError = badTimeout
        // 协议不在这里挡：ViewModel 里有同一条判断，并且会把它报成 `NoProtocols` 显示在
        // 页面上。这一早退把它挡在调用之前，于是"协议一个都没选"变成按了没反应——而老备份
        // 恢复回来的 Key 恰好可能带一个空协议集（引擎按 supportedProtocols 原样搬）。
        if (missingSecret || missingName || badTimeout) return
        val next = currentDraft.copy(
            label = label.text.trim(),
            note = note.text.trim(),
            baseUrl = baseUrl.text.trim(),
            pathOverrideAnthropic = override.text.trim(),
            timeoutSeconds = timeoutText,
            balanceUserId = balanceUserId.text.trim(),
            balanceMethod = balanceMethod.text.trim(),
            balancePath = balancePath.text.trim(),
            balanceValuePath = balanceValuePath.text.trim(),
            balanceUsedPath = balanceUsedPath.text.trim(),
            balanceCurrency = balanceCurrency.text.trim(),
        )
        // 只交**改过**的那两段：原值已经在输入框里了，原样交回去会让每一次保存
        // 都重新加密一遍同一个明文（还多一条审计日志）。null = 没动，保留原值。
        //
        // "改没改"必须在这里（点击这一刻）用**活取值**算，不能复用组合期算好的
        // [secretChanged] 快照：快照一旦滞后，填好的密钥会被当成"没动"交上去，
        // 保存报的却是"请填写 API Key"——2026-09-15 实测踩到过这个坑。
        // 新建 Key 更没有"原值"可言，填了什么就交什么。
        val secretNow = secret.chars
        val tokenNow = balanceToken.chars
        val newKey = draft.id == 0L
        onSave(
            next,
            if (newKey) secretNow else secretNow.takeIf { secret.text != revealed.secret.orEmpty() },
            if (newKey) tokenNow else tokenNow.takeIf { balanceToken.text != revealed.balanceToken.orEmpty() },
        )
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.key_settings_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = { if (dirty) showDiscard = true else onBack() },
                    )
                },
                actions = {
                    AppIconButton(
                        icon = AppIcon.Ok,
                        contentDescription = stringResource(Res.string.editor_save),
                        onClick = ::submit,
                        enabled = !saving,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            item { SectionTitle(text = stringResource(Res.string.editor_section_basic)) }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    // 名称必填：空名称会让标题落到兜底串、布局抖动，所以新建预填默认名、
                    // 清空则拦下保存并说明原因。
                    AppTextField(
                        state = label,
                        label = stringResource(Res.string.editor_name),
                        errorText = if (nameError) stringResource(Res.string.editor_error_missing_name) else null,
                    )
                    AppTextField(state = note, label = stringResource(Res.string.editor_note))
                    AppSecretTextField(
                        state = secret,
                        label = stringResource(Res.string.detail_key_secret),
                        // 编辑时字段里就是原值：说清楚"清空 = 保留"，
                        // 免得用户删掉它、再看到旧密钥还在而以为没保存成功。
                        supportingText = if (draft.id != 0L) {
                            stringResource(Res.string.editor_key_keep_secret)
                        } else {
                            null
                        },
                        errorText = if (secretError) stringResource(Res.string.editor_error_missing_secret) else null,
                        concealed = secretConcealed,
                        toggleConcealDescription = if (secretConcealed) revealCd else concealCd,
                        onToggleConceal = { secretConcealed = !secretConcealed },
                    )
                }
            }
            item { SectionTitle(text = stringResource(Res.string.editor_section_endpoint)) }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    AppTextField(
                        state = baseUrl,
                        label = stringResource(Res.string.editor_base_url),
                        supportingText = stringResource(Res.string.editor_base_url_hint),
                        errorText = baseUrlError,
                    )
                    // FlowRow 而不是 Row：chip 文案是本地化的，中文/英文长度不同，而三种协议
                    // 并排在窄屏（360dp）上会顶出屏幕右缘——Row 不换行，多出来的那枚直接被裁掉，
                    // 用户于是「没有」第三个协议可点。
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)) {
                        Protocol.entries.forEach { protocol ->
                            val selected = protocol in draft.protocols
                            AppFilterChip(
                                text = protocolLabel(protocol),
                                selected = selected,
                                onClick = {
                                    val next = if (selected) draft.protocols - protocol else draft.protocols + protocol
                                    if (next.isNotEmpty()) onChange(draft.copy(protocols = next))
                                },
                            )
                        }
                    }
                }
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_client)) }
            item {
                AppPreferenceGroup {
                    AppDropdownRow(
                        title = stringResource(Res.string.settings_profiles),
                        items = profileNames,
                        selectedIndex = draft.profileIndex,
                        onSelect = { onChange(draft.copy(profileIndex = it)) },
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_advanced)) }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
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
                        errorText = if (timeoutError) stringResource(Res.string.editor_error_invalid_timeout) else null,
                    )
                }
            }
            item {
                AppPreferenceGroup {
                    AppDropdownRow(
                        title = stringResource(Res.string.editor_auth_style),
                        summary = stringResource(Res.string.editor_auth_style_summary),
                        items = stringArrayResource(Res.array.auth_styles).toList(),
                        selectedIndex = draft.authStyleIndex,
                        onSelect = { onChange(draft.copy(authStyleIndex = it)) },
                    )
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_allow_insecure),
                        checked = draft.allowInsecure,
                        onCheckedChange = { onChange(draft.copy(allowInsecure = it)) },
                    )
                }
            }

            if (draft.id != 0L) {
                // 「模型」区这一个开关就是 `probeModels` 的唯一入口：它的效果（自动同步 /
                // 手动增删）就在正下方，拨完立刻看得见。探测权限区不再重复放一遍同一个
                // 字段的开关——两个手柄管一个值，用户只会以为改错了地方。
                item { SectionTitle(text = stringResource(Res.string.detail_models_section)) }
                item {
                    AppPreferenceGroup {
                        AppSwitchRow(
                            title = stringResource(Res.string.editor_models_auto),
                            summary = stringResource(Res.string.editor_models_auto_summary),
                            checked = draft.probeModels,
                            onCheckedChange = { onChange(draft.copy(probeModels = it)) },
                        )
                    }
                }
                // 说明与模型列表/入口分开：说明讲的是规则，列表与「同步 / 添加」是内容与动作。
                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.screenPadding),
                    ) {
                        if (draft.probeModels) {
                            val lastSeen = models.mapNotNull { it.lastSeenAt }.maxOrNull()
                            AppText(
                                text = if (lastSeen == null) {
                                    stringResource(Res.string.detail_models_empty_auto)
                                } else {
                                    stringResource(Res.string.editor_models_last_sync, relativeLabel(nowMs, lastSeen))
                                },
                                style = AppTextStyle.Secondary,
                                color = appSecondaryTextColor,
                            )
                        } else {
                            AppText(
                                text = stringResource(Res.string.editor_models_manual_hint),
                                style = AppTextStyle.Footnote,
                                color = appSecondaryTextColor,
                            )
                            if (models.isEmpty()) {
                                AppText(
                                    text = stringResource(Res.string.detail_models_empty_manual),
                                    style = AppTextStyle.Secondary,
                                    color = appSecondaryTextColor,
                                    modifier = Modifier.padding(top = tokens.itemSpacing),
                                )
                            }
                        }
                    }
                }
                // 模型行单独成组：自动获取时是「立即同步」，手动时是列表 + 「添加模型」。
                item {
                    AppPreferenceGroup(
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                        inset = false,
                    ) {
                        if (draft.probeModels) {
                            AppActionRow(
                                text = stringResource(Res.string.editor_models_sync_now),
                                onClick = onRefreshModels,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            if (models.isNotEmpty()) {
                                models.forEachIndexed { index, model ->
                                    ModelRow(
                                        row = model,
                                        showProbe = draft.probeModelReachability,
                                        onClick = { editingModel = model },
                                    )
                                    if (index != models.lastIndex) AppDivider()
                                }
                            }
                            AppActionRow(
                                text = stringResource(Res.string.detail_add_model),
                                onClick = { addModel = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

            }

            // 「余额」区：总开关 + 查询类型 + 凭据。
            //
            // 开关是这里唯一的"开不开"手柄，它落库为 `BalanceKind.NONE` 或某个真实适配器；
            // 下拉里因此**不再有**「不查」那一项——两个手柄管同一个字段，拨哪个另一个都跟着动，
            // 用户看到的是"这里怎么有两处都在管余额"。
            //
            // 关掉时下方的下拉与凭据照常显示、照常保存：这一区在关掉之后仍然是
            // "把配置记下来备用"的地方，而不是被清空的表单。
            item { SectionTitle(text = stringResource(Res.string.editor_section_balance)) }
            item {
                AppPreferenceGroup {
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_balance_enabled),
                        summary = stringResource(Res.string.editor_balance_enabled_summary),
                        checked = draft.balanceEnabled,
                        onCheckedChange = { onChange(draft.copy(balanceEnabled = it)) },
                    )
                    AppDropdownRow(
                        title = stringResource(Res.string.editor_balance_kind),
                        summary = stringResource(Res.string.editor_balance_kind_summary),
                        items = stringArrayResource(Res.array.balance_kinds).toList(),
                        selectedIndex = draft.balanceKindIndex,
                        onSelect = { onChange(draft.copy(balanceKindIndex = it)) },
                    )
                    // 「探测时查询余额」紧跟在它描述的那个开关后面，而不是留在下面的
                    // 「探测设置」里：它回答的正是上面这个开关打开之后会发生什么，
                    // 隔着整个凭据区摆到另一组，读起来像一件不相干的事。
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_probe_balance),
                        summary = stringResource(Res.string.editor_probe_balance_summary),
                        checked = draft.probeBalance,
                        onCheckedChange = { onChange(draft.copy(probeBalance = it)) },
                        // 两个前置条件缺一不可：余额查询没开时没有对象可查，探测总闸
                        // 没开时（下面那一组）这一项也不会被走到——`BalanceEngine` 两条
                        // 查询路径都要求 `probe.enabled && probe.balance`。与其让它看起来
                        // 开着却什么都不做，不如灰掉。
                        enabled = draft.balanceEnabled && draft.probeEnabled,
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    when (KEY_BALANCE_KINDS.getOrNull(draft.balanceKindIndex)) {
                        BalanceKind.NEWAPI -> {
                            // 编辑时这个框里就是**存着的那串令牌**（new-api 的个人访问令牌），
                            // 默认可见、眼睛可遮——理由同上面的密钥：看不见就没法核对。
                            AppSecretTextField(
                                state = balanceToken,
                                label = stringResource(Res.string.editor_balance_token),
                                supportingText = stringResource(Res.string.editor_balance_token_hint),
                                concealed = tokenConcealed,
                                toggleConcealDescription = if (tokenConcealed) revealCd else concealCd,
                                onToggleConceal = { tokenConcealed = !tokenConcealed },
                            )
                            AppTextField(
                                state = balanceUserId,
                                label = stringResource(Res.string.editor_balance_user_id),
                            )
                        }
                        BalanceKind.VOLCENGINE -> {
                            // 火山引擎走 V4 签名而不是 Bearer：AK 是身份标识、明文可核，
                            // SK 才是秘密，所以只有后者用带眼睛的密文框。
                            AppTextField(
                                state = balanceUserId,
                                label = stringResource(Res.string.editor_balance_access_key_id),
                            )
                            AppSecretTextField(
                                state = balanceToken,
                                label = stringResource(Res.string.editor_balance_secret_access_key),
                                supportingText = stringResource(Res.string.editor_balance_secret_access_key_hint),
                                concealed = tokenConcealed,
                                toggleConcealDescription = if (tokenConcealed) revealCd else concealCd,
                                onToggleConceal = { tokenConcealed = !tokenConcealed },
                            )
                        }
                        BalanceKind.CUSTOM_JSON -> {
                            AppTextField(state = balanceMethod, label = stringResource(Res.string.editor_balance_custom_method))
                            AppTextField(state = balancePath, label = stringResource(Res.string.editor_balance_custom_path))
                            AppTextField(
                                state = balanceValuePath,
                                label = stringResource(Res.string.editor_balance_custom_value_path),
                            )
                            AppTextField(
                                state = balanceUsedPath,
                                label = stringResource(Res.string.editor_balance_custom_used_path),
                            )
                            AppTextField(
                                state = balanceCurrency,
                                label = stringResource(Res.string.editor_balance_custom_currency),
                            )
                        }
                        // NEWAPI 与 CUSTOM_JSON 之外都是内置适配器，没有额外字段要填；
                        // 下标越界（`getOrNull` 给了 null）也落到这里，不画任何输入框。
                        else -> Unit
                    }
                }
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_probe)) }
            item {
                AppPreferenceGroup {
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_probe_enabled),
                        summary = stringResource(Res.string.editor_probe_enabled_summary),
                        checked = draft.probeEnabled,
                        onCheckedChange = { onChange(draft.copy(probeEnabled = it)) },
                    )
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
                    // 「探测时查询余额」已移到上面「余额」区，紧跟它依赖的那个开关。
                    // 「模型列表检测」不在这里再放一遍：它就是上面「模型」区的
                    // `probeModels`，同一个字段两个开关。
                    //
                    // 模型可达性只留这一个开关：引擎要 `modelReachability && quickModelProbe`
                    // 同时为真才发快捷探测，第二个开关关了它就不会亮，开了它也未必生效——
                    // 那是个严格从属的开关，不是独立设置。两处消费方（模型行是否显示
                    // 可达性标签、长按是否触发探测）本来就是同一件事的两个面，所以一次写两个字段。
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_probe_model_reachability),
                        summary = stringResource(Res.string.editor_probe_model_reachability_summary),
                        checked = draft.probeModelReachability,
                        onCheckedChange = {
                            onChange(
                                draft.copy(
                                    probeModelReachability = it,
                                    probeQuickModel = it,
                                ),
                            )
                        },
                        enabled = draft.probeEnabled,
                    )
                }
            }

            saveError?.let { error ->
                item {
                    AppText(
                        text = stringResource(
                            when (error) {
                                KeyEditorViewModel.SaveError.MissingSecret -> Res.string.editor_error_missing_secret
                                KeyEditorViewModel.SaveError.MissingName -> Res.string.editor_error_missing_name
                                KeyEditorViewModel.SaveError.InvalidTimeout -> Res.string.editor_error_invalid_timeout
                                KeyEditorViewModel.SaveError.NoProtocols -> Res.string.editor_error_no_protocols
                                KeyEditorViewModel.SaveError.SaveFailed -> Res.string.editor_error_save_failed
                            },
                        ),
                        style = AppTextStyle.Secondary,
                        color = palette.error,
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }

    ModelDialog(
        keyId = if (addModel || editingModel != null) draft.id.takeIf { it != 0L } else null,
        editing = editingModel,
        protocols = draft.protocols.toList().ifEmpty { listOf(Protocol.CHAT) },
        onDismiss = {
            addModel = false
            editingModel = null
        },
        onConfirm = { keyId, modelId, protocol, displayName ->
            val editing = editingModel
            if (editing == null) {
                onAddModel(modelId, protocol)
            } else {
                onUpdateModel(editing.id, modelId, protocol, displayName)
            }
            addModel = false
            editingModel = null
        },
        onRequestDelete = { model ->
            pendingDeleteModelId = model.id
            editingModel = null
        },
    )

    AppDialog(
        show = pendingDeleteModelId != null,
        onDismissRequest = { pendingDeleteModelId = null },
        title = stringResource(Res.string.detail_model_delete),
        confirmText = stringResource(Res.string.groups_delete),
        // 删除类弹层必须给取消（AppDialog 契约）：只剩一个「删除」时，点空白退出
        // 和点确认在手指下只差几毫米。
        dismissText = stringResource(Res.string.dialog_cancel),
        onConfirm = {
            pendingDeleteModelId?.let(onDeleteModel)
            pendingDeleteModelId = null
        },
    )

    AppDialog(
        show = showDiscard,
        onDismissRequest = { showDiscard = false },
        title = stringResource(Res.string.editor_discard_title),
        summary = stringResource(Res.string.editor_discard_summary),
        confirmText = stringResource(Res.string.editor_discard_confirm),
        // 「放弃修改」同样是不可撤销的动作，退路要写在按钮上而不是让用户猜返回键。
        dismissText = stringResource(Res.string.dialog_cancel),
        onConfirm = {
            showDiscard = false
            onBack()
        },
    )
}
