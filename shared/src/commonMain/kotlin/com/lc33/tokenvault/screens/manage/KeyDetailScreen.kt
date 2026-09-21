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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.screens.model.KeyDetailUiState
import com.lc33.tokenvault.screens.model.KEY_MODEL_PREVIEW_LIMIT
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.common.labelOf
import com.lc33.tokenvault.ui.common.protocolLabel
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDialogTextButton
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
import tokenvault.shared.generated.resources.common_close
import tokenvault.shared.generated.resources.common_off
import tokenvault.shared.generated.resources.common_on
import tokenvault.shared.generated.resources.dashboard_balance_title
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
import tokenvault.shared.generated.resources.manage_latency
import tokenvault.shared.generated.resources.manage_latency_time
import tokenvault.shared.generated.resources.detail_edit_cd
import tokenvault.shared.generated.resources.detail_key_full_content
import tokenvault.shared.generated.resources.detail_models_refresh
import tokenvault.shared.generated.resources.detail_models_count_auto
import tokenvault.shared.generated.resources.detail_models_count_manual
import tokenvault.shared.generated.resources.detail_models_open_all
import tokenvault.shared.generated.resources.detail_key_balance_value
import tokenvault.shared.generated.resources.detail_models_section
import tokenvault.shared.generated.resources.detail_key_more_cd
import tokenvault.shared.generated.resources.detail_key_connection
import tokenvault.shared.generated.resources.detail_probe_key
import tokenvault.shared.generated.resources.groups_delete
import tokenvault.shared.generated.resources.dialog_cancel
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
    /** 长按密钥卡：直接复制密钥明文（不进查看弹窗）。 */
    onCopyKey: () -> Unit,
    /** 长按 Base URL 行：复制连接地址。 */
    onCopyBaseUrl: () -> Unit,
    /** 长按模型行：长按探测关闭时复制模型 ID。 */
    onCopyModelId: (String) -> Unit,
    onProbe: () -> Unit,
    onProbeModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    /** 进整屏模型页：分组 / 排序 / 搜索 / 能力面板 / 逐行增删改都在那一页。 */
    onOpenAllModels: () -> Unit,
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
                    // 顶栏刷新 = 探测这把 Key 的全部信息：有效性（含可达性）、模型列表、余额，
                    // 各自看自己那一档开关。**不含模型可达性**——那一次会真花钱，只在长按模型时发。
                    AppIconButton(
                        icon = AppIcon.Refresh,
                        contentDescription = stringResource(Res.string.detail_probe_key),
                        onClick = onProbe,
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
                    // 长按整张卡直接复制密钥：查看弹窗是为"看一眼"准备的，抄走不必多这一步。
                    onLongPress = onCopyKey,
                ) {
                    // 左边一列讲这把 Key 是什么（备注 / 遮蔽串 / 延迟 + 时间），
                    // 可达性单独摆在**卡片右缘**、与整块内容上下居中——它说的是整把 Key 的
                    // 结论，不是某一行文字的注解（贴在那串遮蔽串右边会被读成"标题的角标"）。
                    // 余额不在这里：它是独立一张卡，混进来会让"这一行说的是什么"说不清。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (key.note.isNotBlank()) {
                                AppText(
                                    text = key.note,
                                    style = AppTextStyle.Footnote,
                                    color = appSecondaryTextColor,
                                    maxLines = 1,
                                )
                            }
                            AppText(
                                text = key.masked,
                                style = AppTextStyle.Body,
                                fontFamily = tokens.monoFontFamily,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            val timingText = when {
                                key.latencyMs != null && key.checkedAt != null -> stringResource(
                                    Res.string.manage_latency_time,
                                    stringResource(Res.string.manage_latency, key.latencyMs),
                                    relativeLabel(state.nowMs, key.checkedAt),
                                )
                                key.latencyMs != null ->
                                    stringResource(Res.string.manage_latency, key.latencyMs)
                                key.checkedAt != null -> relativeLabel(state.nowMs, key.checkedAt)
                                else -> null
                            }
                            if (timingText != null) {
                                AppText(
                                    text = timingText,
                                    style = AppTextStyle.Footnote,
                                    color = appSecondaryTextColor,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        StatusDot(color = colorOf(key.health), label = labelOf(key.health))
                    }
                }
            }
            // 余额单独一块：这一页只有这一把 Key，数字属于它自己，不跟状态挤一行。
            key.balance?.let { balance ->
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
                            AppText(
                                text = stringResource(Res.string.dashboard_balance_title),
                                style = AppTextStyle.Footnote,
                                color = appSecondaryTextColor,
                                modifier = Modifier.weight(1f),
                            )
                            key.balanceCheckedAt?.let { checkedAt ->
                                AppText(
                                    text = relativeLabel(state.nowMs, checkedAt),
                                    style = AppTextStyle.Footnote,
                                    color = appSecondaryTextColor,
                                )
                            }
                        }
                        AppText(
                            text = stringResource(
                                Res.string.detail_key_balance_value,
                                balance.currency,
                                balance.amount,
                            ),
                            style = AppTextStyle.Title,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            // 「查看密钥」是一条动作入口，单独成组：混在描述卡里，
            // 整张卡（含遮蔽串、状态）看起来都能点。
            item {
                AppPreferenceGroup {
                    AppActionRow(
                        text = stringResource(Res.string.detail_key_view),
                        onClick = onReveal,
                        modifier = Modifier.fillMaxWidth(),
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
                        // 长按这一行直接复制地址：URL 往往要贴到终端或浏览器里，抄走最省事。
                        onLongPress = onCopyBaseUrl,
                    )
                    AppValueRow(
                        title = stringResource(Res.string.detail_key_connection_protocol),
                        // 三项协议并排就超过了右侧那点宽度，默认的左右排版会把最后一项
                        // 直接截掉（"Chat Responses" 之后看不到 Anthropic）。协议是这条
                        // Key 的关键信息，宁可多占一行也不能缺。
                        //
                        // protocolLabel 是 @Composable（要读资源），不能塞进 joinToString 的
                        // lambda 里——那是非 Composable 上下文。先逐项取出来再拼。
                        value = key.settings.protocols
                            .map { protocolLabel(it) }
                            .joinToString("  "),
                        stacked = true,
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
                    // 左右内边距交给行自己（preference 行本来就带 16dp 内缩），卡片这层只留
                    // 上下：不然按下某一行时那块高亮比文字宽出一截，分隔线也两端各短一段。
                    // 与整屏模型页那张卡同一个做法。
                    insideMargin = PaddingValues(vertical = tokens.screenPadding),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.screenPadding),
                        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 「共 N 个」把数量和来源一起说清楚：只给一个数字，用户看不出
                        // 这份列表是上游同步来的还是自己一条条加的，而这两者的可编辑性不同。
                        AppText(
                            text = stringResource(
                                if (key.settings.probeModels) {
                                    Res.string.detail_models_count_auto
                                } else {
                                    Res.string.detail_models_count_manual
                                },
                                state.models.size,
                            ),
                            style = AppTextStyle.Subtitle,
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
                            modifier = Modifier.padding(
                                horizontal = tokens.screenPadding,
                                vertical = tokens.itemSpacing,
                            ),
                        )
                    } else {
                        // 外面这一卡只作名称预览：最多三行，四行以上都去整屏模型页看。
                        val preview = state.models.take(KEY_MODEL_PREVIEW_LIMIT)
                        preview.forEachIndexed { index, model ->
                            // 自动获取的列表不给编辑入口（同供应商详情页）：点开一个
                            // 下次同步就会被覆盖的字段没有意义。
                            val editable = !key.settings.probeModels
                            // 编辑页把这两个字段当一个开关写，但老库里可能只开了其中一个
                            // （v4 迁移是从 modelReachability 抄过去的，之后就各自能改）。
                            // 引擎要两个都真才会发探测，所以界面也按"两个都真"来画：
                            // 只开一个却显示可达性标签、长按却没反应，比不显示更费解。
                            val quickProbeEnabled =
                                key.settings.probeModelReachability && key.settings.probeQuickModel
                            ModelRow(
                                row = model,
                                // 与供应商页那卡同一套样式：协议 chip 与状态点都不画，只剩
                                // 名字和它下面那行展示名。这一卡是名称参考，状态与能力去
                                // 整屏模型页看；长按仍然能探测这一行（手势不在样式里）。
                                showProbe = false,
                                showProtocol = false,
                                onClick = if (editable) ({ selectedModel = model }) else null,
                                // 长按有两种用途，同一时刻只取一种：
                                // - 开了长按探测（模型可达性 + 快速探测都开）→ 发一次真花钱的探测；
                                // - 没开 → 复制模型 ID（模型名常要贴进配置里，这是那一档下唯一有用的长按动作）。
                                onLongPress = if (quickProbeEnabled) {
                                    // 只传模型 id：协议由引擎按 Chat → Anthropic 试探，
                                    // 这一行记的协议不参与（它只是用户当初填的值）。
                                    { onProbeModel(model.modelId) }
                                } else {
                                    { onCopyModelId(model.modelId) }
                                },
                            )
                            if (index != preview.lastIndex) {
                                AppDivider()
                            }
                        }
                    }
                    // 底部那一行是整屏模型页的唯一入口，预览不足三条时也给：外面只作
                    // 名称参考，要看协议、上下文、能力或逐行增删改都得进那一页。
                    // inset 用默认值（行自带 16dp），与它上面那些 ModelRow 的缩进同源。
                    AppDivider()
                    AppActionRow(
                        text = stringResource(Res.string.detail_models_open_all),
                        onClick = onOpenAllModels,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing + tokens.itemSpacing)) }
        }
    }

    // 查看密钥用 Dialog 而不是 BottomSheet：内容只有一段明文 + 两个动作，
    // 居中的小对话框更贴合"看一眼"的时长，BottomSheet 那种半屏面板显得隆重。
    // 按钮是弹层里的收尾动作，走 AppDialogTextButton（弹层允许按钮，页面主体不允许）。
    AppDialog(
        show = revealedText != null,
        onDismissRequest = onCloseReveal,
        title = stringResource(Res.string.detail_key_view),
    ) {
        AppValueRow(
            title = stringResource(Res.string.detail_key_full_content),
            value = revealedText.orEmpty(),
            stacked = true,
            mono = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppDialogTextButton(
                text = stringResource(Res.string.common_close),
                onClick = onCloseReveal,
                modifier = Modifier.weight(1f),
            )
            AppDialogTextButton(
                text = stringResource(Res.string.secret_copy_cd),
                onClick = onCopyRevealed,
                modifier = Modifier.weight(1f),
                primary = true,
            )
        }
    }
    AppDialog(
        show = pendingDelete,
        onDismissRequest = { pendingDelete = false },
        title = stringResource(Res.string.groups_delete),
        confirmText = stringResource(Res.string.groups_delete),
        // 删的是真数据（密钥 + 它名下的模型），只给一个「删除」按钮等于逼用户猜退出方式。
        dismissText = stringResource(Res.string.dialog_cancel),
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
        // 两行只读字段与状态同属"这个模型的一组属性"，包进同一个 group；
        // 摊在弹层上是几行悬空文本，没有容器边界。
        AppPreferenceGroup(inset = false) {
            AppValueRow(
                title = stringResource(Res.string.detail_model_protocol),
                value = protocolLabel(model.protocol),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = tokens.screenPadding,
                        end = tokens.screenPadding,
                        top = tokens.itemSpacing,
                        bottom = tokens.itemSpacing,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(color = colorOf(model.health), label = labelOf(model.health))
            }
        }
    }
}
