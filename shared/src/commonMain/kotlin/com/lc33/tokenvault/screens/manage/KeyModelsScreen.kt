package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.catalog.CatalogSyncState
import com.lc33.tokenvault.domain.ModelFilter
import com.lc33.tokenvault.domain.ModelGroupBy
import com.lc33.tokenvault.domain.ModelSort
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.screens.model.KeyModelsUiState
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiModelCardRow
import com.lc33.tokenvault.screens.model.UiModelGroup
import com.lc33.tokenvault.screens.model.UiModelMeta
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.screens.model.UiModelSource
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.common.labelOf
import com.lc33.tokenvault.ui.common.protocolLabel
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppIconMenu
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppMenuGroup
import com.lc33.tokenvault.ui.miuix.AppMenuItem
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSearchField
import com.lc33.tokenvault.ui.miuix.AppTabRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.AppValueRow
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.detail_model_display_name
import tokenvault.shared.generated.resources.detail_model_id
import tokenvault.shared.generated.resources.detail_models_collapse_cd
import tokenvault.shared.generated.resources.detail_models_expand_cd
import tokenvault.shared.generated.resources.dialog_cancel
import tokenvault.shared.generated.resources.keymodels_add_model
import tokenvault.shared.generated.resources.keymodels_cap_attachment
import tokenvault.shared.generated.resources.keymodels_cap_audio
import tokenvault.shared.generated.resources.keymodels_cap_none
import tokenvault.shared.generated.resources.keymodels_cap_open_weights
import tokenvault.shared.generated.resources.keymodels_cap_reasoning
import tokenvault.shared.generated.resources.keymodels_cap_structured
import tokenvault.shared.generated.resources.keymodels_cap_tool_call
import tokenvault.shared.generated.resources.keymodels_cap_video
import tokenvault.shared.generated.resources.keymodels_cap_vision
import tokenvault.shared.generated.resources.keymodels_catalog_failed
import tokenvault.shared.generated.resources.keymodels_catalog_never
import tokenvault.shared.generated.resources.keymodels_catalog_sync
import tokenvault.shared.generated.resources.keymodels_catalog_update
import tokenvault.shared.generated.resources.keymodels_copy
import tokenvault.shared.generated.resources.keymodels_delete
import tokenvault.shared.generated.resources.keymodels_delete_summary
import tokenvault.shared.generated.resources.keymodels_delete_title
import tokenvault.shared.generated.resources.keymodels_edit
import tokenvault.shared.generated.resources.keymodels_empty
import tokenvault.shared.generated.resources.keymodels_filter_all
import tokenvault.shared.generated.resources.keymodels_filter_matched
import tokenvault.shared.generated.resources.keymodels_filter_reasoning
import tokenvault.shared.generated.resources.keymodels_filter_tool_call
import tokenvault.shared.generated.resources.keymodels_filter_unmatched
import tokenvault.shared.generated.resources.keymodels_filter_vision
import tokenvault.shared.generated.resources.keymodels_group_by_family
import tokenvault.shared.generated.resources.keymodels_group_by_none
import tokenvault.shared.generated.resources.keymodels_group_by_source
import tokenvault.shared.generated.resources.keymodels_group_other
import tokenvault.shared.generated.resources.keymodels_matched
import tokenvault.shared.generated.resources.keymodels_meta_capabilities
import tokenvault.shared.generated.resources.keymodels_meta_context
import tokenvault.shared.generated.resources.keymodels_meta_cutoff
import tokenvault.shared.generated.resources.keymodels_meta_output
import tokenvault.shared.generated.resources.keymodels_meta_released
import tokenvault.shared.generated.resources.keymodels_meta_status
import tokenvault.shared.generated.resources.keymodels_meta_vendor
import tokenvault.shared.generated.resources.keymodels_more_cd
import tokenvault.shared.generated.resources.keymodels_probe
import tokenvault.shared.generated.resources.keymodels_probed_ago
import tokenvault.shared.generated.resources.keymodels_probed_ago_with_latency
import tokenvault.shared.generated.resources.keymodels_probed_never
import tokenvault.shared.generated.resources.keymodels_refresh
import tokenvault.shared.generated.resources.keymodels_search
import tokenvault.shared.generated.resources.keymodels_sort_added
import tokenvault.shared.generated.resources.keymodels_sort_asc
import tokenvault.shared.generated.resources.keymodels_sort_context
import tokenvault.shared.generated.resources.keymodels_sort_desc
import tokenvault.shared.generated.resources.keymodels_sort_probed
import tokenvault.shared.generated.resources.keymodels_source_discovered
import tokenvault.shared.generated.resources.keymodels_source_manual
import tokenvault.shared.generated.resources.keymodels_summary
import tokenvault.shared.generated.resources.keymodels_summary_all
import tokenvault.shared.generated.resources.keymodels_summary_empty
import tokenvault.shared.generated.resources.keymodels_unmatched

/**
 * 整屏模型页：一把 Key 的模型列表，分组 / 排序 / 筛选 / 搜索 / 展开看能力。
 *
 * 页面只画不算：分组/排序/筛选的规则都在 `domain/listModels`，由 `KeyModelsViewModel`
 * 喂好状态。这里遵守分层与设计约束（不 import material3、不用裸 `Color`/`.sp`、文案全走
 * 资源、状态色只从 `colorOf`/`labelOf` 取），与 `ProbeRunScreen` 等页面同一套写法。
 *
 * 三处"不在页面里做"的判断是刻意的：
 * - **搜索与筛选不下推 SQL**：规则在 `domain/listModels`，页面只画结果。
 * - **编辑/删除只在 [KeyModelsUiState.editable] 为真时给**：自动获取的列表下次同步就
 *   覆盖，让用户改一个马上会被抹掉的字段没有意义（与详情页、供应商详情页同一条）。
 * - **探测只在 [KeyModelsUiState.quickProbe] 为真时出现**：那一次真花钱。
 */
@Composable
fun KeyModelsScreen(
    state: KeyModelsUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onGroupBy: (Int) -> Unit,
    onSort: (ModelSort) -> Unit,
    onFilter: (ModelFilter) -> Unit,
    onToggleGroup: (String) -> Unit,
    onToggleExpand: (Long) -> Unit,
    onCopyModelId: (String) -> Unit,
    onProbeModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onSyncCatalog: () -> Unit,
    onAddModel: (String, Protocol) -> Unit,
    onUpdateModel: (Long, String, Protocol, String?) -> Unit,
    onDeleteModel: (Long) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val searchState = rememberAppTextFieldState(state.query)
    var editing by remember { mutableStateOf<UiModelCardRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<UiModelCardRow?>(null) }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = state.providerName,
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
                        contentDescription = stringResource(Res.string.keymodels_refresh),
                        onClick = onRefreshModels,
                    )
                    SortMenu(current = state.sort, onSort = onSort)
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
            item { Spacer(modifier = Modifier.height(tokens.itemSpacing)) }

            item {
                AppSearchField(
                    state = searchState,
                    hint = stringResource(Res.string.keymodels_search),
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                )
            }

            item {
                GroupByTabs(
                    groupBy = state.groupBy,
                    onGroupBy = onGroupBy,
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                )
            }
            item {
                FilterChips(
                    filter = state.filter,
                    onFilter = onFilter,
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                )
            }

            // 同步进度与"目录从没下载过"都摆在列表上方：它们解释的是"下面这些行为什么
            // 没有厂商与上下文"，放在底部用户已经滚过一遍没数据的列表才看到。
            state.catalogSync?.let { sync ->
                item {
                    CatalogSyncBanner(
                        sync = sync,
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
            }
            if (state.catalogNeverSynced && state.catalogSync == null) {
                item {
                    CatalogNeverBanner(
                        onSyncCatalog = onSyncCatalog,
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
            }

            if (state.totalModels > 0) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        AppText(
                            text = summaryText(state),
                            style = AppTextStyle.Footnote,
                            color = appSecondaryTextColor,
                        )
                        AppText(
                            text = stringResource(Res.string.keymodels_matched, state.matchedModels),
                            style = AppTextStyle.Footnote,
                            color = appSecondaryTextColor,
                        )
                    }
                }
            }

            if (state.groups.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(Res.string.keymodels_empty),
                        description = "",
                    )
                }
            } else {
                state.groups.forEach { group ->
                    item(key = "group-${group.key}") {
                        GroupCard(
                            group = group,
                            editable = state.editable,
                            nowMs = state.nowMs,
                            onToggleGroup = onToggleGroup,
                            onToggleExpand = onToggleExpand,
                            onCopyModelId = onCopyModelId,
                            onProbeModel = onProbeModel,
                            onEdit = { row -> editing = row },
                            onDelete = { row -> pendingDelete = row },
                            modifier = Modifier.padding(horizontal = tokens.screenPadding),
                        )
                    }
                }
            }

            // 手动模式才给"添加模型"：自动获取的列表下一次同步就覆盖掉，加进去的东西
            // 会凭空消失（与详情页、供应商详情页同一条约束）。
            if (state.editable) {
                item {
                    AppActionRow(
                        text = stringResource(Res.string.keymodels_add_model),
                        onClick = { adding = true },
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }

    // 复用供应商/密钥页那个 ModelDialog：三处的手动增删改是同一件事，
    // 各写一个只会让协议下拉、显示名回填这些细节在三处慢慢漂开。
    val dialogTarget = editing
    ModelDialog(
        keyId = if (adding || dialogTarget != null) dialogKeyId else null,
        editing = dialogTarget?.toDialogRow(),
        protocols = Protocol.entries.toList(),
        onDismiss = {
            adding = false
            editing = null
        },
        onConfirm = { _, modelId, protocol, displayName ->
            if (dialogTarget == null) {
                onAddModel(modelId, protocol)
            } else {
                onUpdateModel(dialogTarget.id, modelId, protocol, displayName)
            }
            adding = false
            editing = null
        },
        onRequestDelete = {
            // 弹层里点"删除模型"时把它交回页面自己的确认框：删除要先问一句，
            // 而弹层内的删除按钮直接落库就没有那一步了。这个回调只在编辑态可触发，
            // 所以 editing 一定非空。
            pendingDelete = editing
            editing = null
        },
    )

    AppDialog(
        show = pendingDelete != null,
        onDismissRequest = { pendingDelete = null },
        title = stringResource(Res.string.keymodels_delete_title),
        summary = stringResource(Res.string.keymodels_delete_summary),
        confirmText = stringResource(Res.string.keymodels_delete),
        // 删除类弹层必须给取消：只剩一个「删除」时，点空白退出和点确认在手指下只差几毫米。
        dismissText = stringResource(Res.string.dialog_cancel),
        onConfirm = {
            pendingDelete?.let { onDeleteModel(it.id) }
            pendingDelete = null
        },
    )
}

/**
 * 摘要那句。
 *
 * 有筛选时说"显示 N / 总数"：只给一个数字的话，用户筛完看到"3 个"会以为库里只剩三个，
 * 而真相是那把 Key 有 200 个、被筛掉了一多半。总数为 0 时两行都不画。
 */
@Composable
private fun summaryText(state: KeyModelsUiState): String = when {
    state.visibleModels == 0 -> stringResource(Res.string.keymodels_summary_empty)
    state.visibleModels < state.totalModels ->
        stringResource(Res.string.keymodels_summary, state.visibleModels, state.totalModels)

    else -> stringResource(Res.string.keymodels_summary_all, state.totalModels)
}

/** 目录同步中/失败的横幅。失败只念"失败"，英文诊断串留给日志页。 */
@Composable
private fun CatalogSyncBanner(sync: CatalogSyncState, modifier: Modifier = Modifier) {
    val tokens = LocalAppTokens.current
    val failed = sync is CatalogSyncState.Failed
    AppCard(modifier = modifier.fillMaxWidth()) {
        AppText(
            text = stringResource(
                if (failed) Res.string.keymodels_catalog_failed else Res.string.keymodels_catalog_sync,
            ),
            style = AppTextStyle.Secondary,
            color = if (failed) colorOf(UiHealth.Warn) else appSecondaryTextColor,
        )
        if (!failed) {
            AppLinearProgress(
                // 下载阶段拿不到总长度（上游是 chunked 传输），那一段是不确定进度；
                // 导入阶段有确切的分子分母，就画真的百分比。
                progress = (sync as? CatalogSyncState.Importing)
                    ?.let { if (it.total > 0) it.rows.toFloat() / it.total else null },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.itemSpacing),
            )
        }
    }
}

/** 目录从没成功下载过。给一个明确的动作入口，而不是只留一句"暂不可用"。 */
@Composable
private fun CatalogNeverBanner(onSyncCatalog: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        AppText(
            text = stringResource(Res.string.keymodels_catalog_never),
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
        )
        AppActionRow(
            text = stringResource(Res.string.keymodels_catalog_update),
            onClick = onSyncCatalog,
            inset = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GroupByTabs(
    groupBy: ModelGroupBy,
    onGroupBy: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tab 顺序与 ModelGroupBy 声明序一致（FAMILY / SOURCE / NONE）——onGroupBy 按下标取值。
    val tabs = listOf(
        stringResource(Res.string.keymodels_group_by_family),
        stringResource(Res.string.keymodels_group_by_source),
        stringResource(Res.string.keymodels_group_by_none),
    )
    AppTabRow(tabs = tabs, selectedIndex = groupBy.ordinal, onSelect = onGroupBy, modifier = modifier)
}

/**
 * 能力筛选条。
 *
 * 用 chip 行而不是第二个 `AppTabRow`：分组那排已经是 tab 了，两排等宽 tab 会让人分不清
 * 哪一排在管什么；而筛选是"随时切"的动作，chip 的横滑形态更贴。
 *
 * 这几档是**能力筛选**，不是状态筛选（可用/失败/已启用）——分组既然按前缀，常见厂商天然
 * 就是最大的几组，再叠一个"常见"是重复；而"这把 Key 上哪些模型能看图、哪些支持工具调用"
 * 才是配客户端时真会问的问题。另外 Key 与模型都没有启用态（停用即删除）。
 */
@Composable
private fun FilterChips(
    filter: ModelFilter,
    onFilter: (ModelFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val options = listOf(
        ModelFilter.ALL to Res.string.keymodels_filter_all,
        ModelFilter.REASONING to Res.string.keymodels_filter_reasoning,
        ModelFilter.TOOL_CALL to Res.string.keymodels_filter_tool_call,
        ModelFilter.VISION to Res.string.keymodels_filter_vision,
        ModelFilter.MATCHED to Res.string.keymodels_filter_matched,
        ModelFilter.UNMATCHED to Res.string.keymodels_filter_unmatched,
    )
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        items(options) { option ->
            AppFilterChip(
                text = stringResource(option.second),
                selected = option.first == filter,
                onClick = { onFilter(option.first) },
            )
        }
    }
}

/** 排序菜单。放在顶栏而不是列表里：排序不是每次进页面都要动的东西。 */
@Composable
private fun SortMenu(current: ModelSort, onSort: (ModelSort) -> Unit) {
    val options = listOf(
        ModelSort.NAME_ASC to Res.string.keymodels_sort_asc,
        ModelSort.NAME_DESC to Res.string.keymodels_sort_desc,
        ModelSort.CONTEXT_DESC to Res.string.keymodels_sort_context,
        ModelSort.RECENT_PROBE to Res.string.keymodels_sort_probed,
        ModelSort.ADDED to Res.string.keymodels_sort_added,
    )
    AppIconMenu(
        icon = AppIcon.Sort,
        contentDescription = stringResource(Res.string.keymodels_more_cd),
        // 选完就收：这是单选菜单，不像日志页那个要连着改好几项。
        collapseOnSelection = true,
        groups = listOf(
            AppMenuGroup(
                options.map { option ->
                    AppMenuItem(
                        text = stringResource(option.second),
                        selected = option.first == current,
                        onClick = { onSort(option.first) },
                    )
                },
            ),
        ),
    )
}

@Composable
private fun GroupCard(
    group: UiModelGroup,
    editable: Boolean,
    nowMs: Long,
    onToggleGroup: (String) -> Unit,
    onToggleExpand: (Long) -> Unit,
    onCopyModelId: (String) -> Unit,
    onProbeModel: (String) -> Unit,
    onEdit: (UiModelCardRow) -> Unit,
    onDelete: (UiModelCardRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    AppCard(modifier = modifier.fillMaxWidth()) {
        if (group.title.isNotEmpty() || group.key.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleGroup(group.key) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText(
                    text = groupTitle(group),
                    style = AppTextStyle.Subtitle,
                    modifier = Modifier.weight(1f),
                )
                AppText(
                    text = group.rows.size.toString(),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                )
                AppIconButton(
                    icon = if (group.collapsed) AppIcon.Expand else AppIcon.Collapse,
                    contentDescription = stringResource(
                        if (group.collapsed) {
                            Res.string.detail_models_expand_cd
                        } else {
                            Res.string.detail_models_collapse_cd
                        },
                    ),
                    onClick = { onToggleGroup(group.key) },
                )
            }
        }
        if (!group.collapsed) {
            group.rows.forEach { row ->
                ModelRow(
                    row = row,
                    editable = editable,
                    nowMs = nowMs,
                    onToggleExpand = { onToggleExpand(row.id) },
                    onCopy = { onCopyModelId(row.modelId) },
                    onProbe = { onProbeModel(row.modelId) },
                    onEdit = { onEdit(row) },
                    onDelete = { onDelete(row) },
                )
            }
        }
    }
}

/**
 * 组标题。
 *
 * 来源分组的键是语义键（`manual`/`discovered`），要翻成资源串；族分组的标题是
 * ViewModel 已经算好的展示名（目录厂商名，或首字母大写的族键），原样画。
 * 族键为 `other` 时用那句"其他"——空 id、纯符号的兜底组。
 */
@Composable
private fun groupTitle(group: UiModelGroup): String = when (group.key) {
    "manual" -> stringResource(Res.string.keymodels_source_manual)
    "discovered" -> stringResource(Res.string.keymodels_source_discovered)
    "other" -> stringResource(Res.string.keymodels_group_other)
    else -> group.title
}

@Composable
private fun ModelRow(
    row: UiModelCardRow,
    editable: Boolean,
    nowMs: Long,
    onToggleExpand: () -> Unit,
    onCopy: () -> Unit,
    onProbe: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(
                text = row.modelId,
                style = AppTextStyle.Body,
                fontFamily = tokens.monoFontFamily,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            AppChip(text = protocolLabel(row.protocol))
            StatusDot(color = colorOf(row.health), label = labelOf(row.health))
            ModelMenu(
                row = row,
                editable = editable,
                onCopy = onCopy,
                onProbe = onProbe,
                onEdit = onEdit,
                onDelete = onDelete,
            )
        }

        // 第二行常驻"来源 + 最近探测"：这两条是每行都有的事实，不展开也该看得见。
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppText(
                text = stringResource(
                    if (row.source == UiModelSource.Manual) {
                        Res.string.keymodels_source_manual
                    } else {
                        Res.string.keymodels_source_discovered
                    },
                ),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
            AppText(
                text = probeSummary(row, nowMs),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
            // 未挂目录的行给一句轻提示：它是"这份目录还缺多少"的诊断入口，
            // 也是唯一能一眼看出上游改名的地方。
            if (row.unmatched) {
                AppText(
                    text = stringResource(Res.string.keymodels_unmatched),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                )
            }
        }

        if (row.expanded) {
            Spacer(modifier = Modifier.height(tokens.itemSpacing))
            ModelMetaPanel(meta = row.meta, modelId = row.modelId)
        }
    }
}

/**
 * 最近探测那一句。
 *
 * 没探测过就说没探测过；探测过给相对时间，有耗时就一起带上。耗时缺了不补 0——
 * "0 毫秒"看起来像一次极快的成功，而真相是那一发没记到耗时。
 */
@Composable
private fun probeSummary(row: UiModelCardRow, nowMs: Long): String {
    val probedAt = row.probedAt ?: return stringResource(Res.string.keymodels_probed_never)
    val whenText = relativeLabel(nowMs, probedAt)
    val latency = row.latencyMs
    return if (latency == null) {
        stringResource(Res.string.keymodels_probed_ago, whenText)
    } else {
        stringResource(Res.string.keymodels_probed_ago_with_latency, whenText, latency)
    }
}

/** 展开后的能力面板。没挂上目录时说清楚"没有数据"而不是画一片空白。 */
@Composable
private fun ModelMetaPanel(meta: UiModelMeta?, modelId: String) {
    val tokens = LocalAppTokens.current
    // 用 group 包住：这几行是"这个模型的一组属性"，摊在卡片里是几行悬空文本，没有容器边界。
    AppPreferenceGroup(inset = false) {
        AppValueRow(
            title = stringResource(Res.string.detail_model_id),
            value = modelId,
            stacked = true,
            mono = true,
        )
        if (meta == null) {
            AppText(
                text = stringResource(Res.string.keymodels_cap_none),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(
                    horizontal = tokens.screenPadding,
                    vertical = tokens.itemSpacing,
                ),
            )
            return@AppPreferenceGroup
        }
        meta.displayName?.let {
            AppValueRow(title = stringResource(Res.string.detail_model_display_name), value = it)
        }
        meta.vendorName?.let {
            AppValueRow(title = stringResource(Res.string.keymodels_meta_vendor), value = it)
        }
        meta.context?.let {
            AppValueRow(title = stringResource(Res.string.keymodels_meta_context), value = it)
        }
        meta.output?.let {
            AppValueRow(title = stringResource(Res.string.keymodels_meta_output), value = it)
        }
        meta.releaseDate?.let {
            AppValueRow(title = stringResource(Res.string.keymodels_meta_released), value = it)
        }
        meta.knowledgeCutoff?.let {
            AppValueRow(title = stringResource(Res.string.keymodels_meta_cutoff), value = it)
        }
        meta.status?.let {
            AppValueRow(title = stringResource(Res.string.keymodels_meta_status), value = it)
        }
        meta.description?.let {
            AppValueRow(
                title = stringResource(Res.string.keymodels_meta_capabilities),
                value = it,
                stacked = true,
            )
        }
        // 能力用 chip 摊开：它是这一页存在的理由（"这把 Key 上哪些模型能看图"），
        // 折成一行 "reasoning, tool_call, vision" 又要用户自己去分词。
        CapabilityChips(meta)
    }
}

@Composable
private fun CapabilityChips(meta: UiModelMeta) {
    val tokens = LocalAppTokens.current
    val caps = buildList {
        if (meta.reasoning) add(Res.string.keymodels_cap_reasoning)
        if (meta.toolCall) add(Res.string.keymodels_cap_tool_call)
        if (meta.vision) add(Res.string.keymodels_cap_vision)
        if (meta.audioIn) add(Res.string.keymodels_cap_audio)
        if (meta.videoIn) add(Res.string.keymodels_cap_video)
        if (meta.attachment) add(Res.string.keymodels_cap_attachment)
        if (meta.structuredOutput) add(Res.string.keymodels_cap_structured)
        if (meta.openWeights) add(Res.string.keymodels_cap_open_weights)
    }
    if (caps.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(caps) { label -> AppChip(text = stringResource(label)) }
    }
}

/**
 * 一行的操作菜单。
 *
 * 用图标菜单而不是把动作铺成按钮：一行里塞四五个按钮会把模型 id 挤没，而这一页的
 * 主语就是 id。复制与探测在开关允许时始终可用，编辑与删除只在手动模式下给。
 */
@Composable
private fun ModelMenu(
    row: UiModelCardRow,
    editable: Boolean,
    onCopy: () -> Unit,
    onProbe: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AppIconMenu(
        icon = AppIcon.More,
        contentDescription = stringResource(Res.string.keymodels_more_cd),
        collapseOnSelection = true,
        groups = listOf(
            AppMenuGroup(
                listOf(
                    AppMenuItem(
                        text = stringResource(Res.string.keymodels_probe),
                        // 模型可达性探测真花钱，两档开关没开就不给（与详情页同一条）。
                        enabled = row.quickProbe,
                        onClick = onProbe,
                    ),
                    AppMenuItem(
                        text = stringResource(Res.string.keymodels_copy),
                        onClick = onCopy,
                    ),
                    AppMenuItem(
                        text = stringResource(Res.string.keymodels_edit),
                        enabled = editable,
                        onClick = onEdit,
                    ),
                    AppMenuItem(
                        text = stringResource(Res.string.keymodels_delete),
                        enabled = editable,
                        onClick = onDelete,
                    ),
                ),
            ),
        ),
    )
}

/**
 * 弹层要的行形态。
 *
 * `ModelDialog` 收的是 [UiModelRow]（它还要显示"显示名"，而列表页那一行不带这个字段），
 * 所以在这里转一次。providerId/keyId 只用于回填协议与删除回调，页面手上没有也不该有
 * Key 级上下文，填 0 即可——弹层真正读的是 modelId / displayName / protocol 三项。
 */
private fun UiModelCardRow.toDialogRow() = UiModelRow(
    id = id,
    modelId = modelId,
    displayName = meta?.displayName,
    providerId = 0L,
    keyId = null,
    protocol = protocol,
    source = source,
    health = health,
    contextLabel = meta?.context,
)

/**
 * `ModelDialog` 用 keyId 是否非空当显示开关，而这一页本身就是"某一把 Key"的上下文，
 * 真正的 keyId 不在 UI 状态里（它是 ViewModel 的构造参数）。这个哨兵值只用于开弹层。
 */
private const val dialogKeyId = -1L
