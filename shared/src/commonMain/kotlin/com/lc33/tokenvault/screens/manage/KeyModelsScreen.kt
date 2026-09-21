package com.lc33.tokenvault.screens.manage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.common.LoadingState
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.common.labelOf
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDivider
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
import com.lc33.tokenvault.ui.miuix.appRowInset
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.detail_model_display_name
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
import tokenvault.shared.generated.resources.keymodels_group_other
import tokenvault.shared.generated.resources.keymodels_matched
import tokenvault.shared.generated.resources.keymodels_meta_about
import tokenvault.shared.generated.resources.keymodels_meta_cutoff
import tokenvault.shared.generated.resources.keymodels_meta_output
import tokenvault.shared.generated.resources.keymodels_meta_released
import tokenvault.shared.generated.resources.keymodels_meta_status
import tokenvault.shared.generated.resources.keymodels_meta_vendor
import tokenvault.shared.generated.resources.keymodels_more_cd
import tokenvault.shared.generated.resources.keymodels_probe
import tokenvault.shared.generated.resources.keymodels_probed_ago
import tokenvault.shared.generated.resources.keymodels_probed_ago_with_latency
import tokenvault.shared.generated.resources.keymodels_refresh
import tokenvault.shared.generated.resources.keymodels_search
import tokenvault.shared.generated.resources.keymodels_sort_added
import tokenvault.shared.generated.resources.keymodels_sort_asc
import tokenvault.shared.generated.resources.keymodels_sort_context
import tokenvault.shared.generated.resources.keymodels_sort_desc
import tokenvault.shared.generated.resources.keymodels_sort_probed
import tokenvault.shared.generated.resources.keymodels_summary
import tokenvault.shared.generated.resources.keymodels_show_more
import tokenvault.shared.generated.resources.keymodels_summary_all
import tokenvault.shared.generated.resources.keymodels_summary_empty
import tokenvault.shared.generated.resources.keymodels_unmatched

/**
 * 整屏模型页：一把 Key 的模型列表，分组 / 排序 / 筛选 / 搜索，能力 chip 就摊在每行名字
 * 下面，点一行开底部弹层看这个模型的完整详情。
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
    /** 放出一组里剩下的模型（一次一批）。 */
    onShowMoreRows: (String) -> Unit,
    onCopyModelId: (String) -> Unit,
    onProbeModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onSyncCatalog: () -> Unit,
    onAddModel: (String, Protocol) -> Unit,
    onUpdateModel: (Long, String, Protocol, String?) -> Unit,
    onDeleteModel: (Long) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val listState = rememberLazyListState()
    // 搜索框跟着滚动收放：往下滚（顶栏同时折叠）就收，回到顶部就回来。信号取"列表还能
    // 不能往上滚"而不是顶栏的折叠比例——同一个手势驱动，而页面不必去碰 MIUIX 的
    // scrollBehavior（那道 import 边界是刻意留的）。用 AnimatedVisibility 而不是直接
    // 不画：一帧之间少掉 50dp，读起来像列表跳了一下。
    val searchVisible = !listState.canScrollBackward
    val tokens = LocalAppTokens.current
    val searchState = rememberAppTextFieldState(state.query)
    var editing by remember { mutableStateOf<UiModelCardRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<UiModelCardRow?>(null) }
    // 详情弹层选中哪一行是**页面自己的**临时状态：它不进 ViewModel，转屏幕方向不该丢的
    // 东西里没有它，而弹层关掉之后这个值就没有意义了。
    var detail by remember { mutableStateOf<UiModelCardRow?>(null) }

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
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            item { Spacer(modifier = Modifier.height(tokens.itemSpacing)) }

            item {
                AnimatedVisibility(
                    visible = searchVisible,
                    // 回来比收起稍慢一点：收是"让位给列表"，快一点不挡路；放是"东西又
                    // 出现了"，突然弹出来最刺眼。
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                ) {
                    AppSearchField(
                        state = searchState,
                        hint = stringResource(Res.string.keymodels_search),
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.screenPadding),
                    )
                }
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
                    // 还没读到第一帧时只能说"加载中"：那一帧 groups 也是空的，与"这把
                    // Key 确实没有模型"一模一样，直接画空态就会闪一句假话。
                    if (state.loading) {
                        LoadingState()
                    } else {
                        EmptyState(title = stringResource(Res.string.keymodels_empty))
                    }
                }
            } else {
                state.groups.forEach { group ->
                    item(key = "group-${group.key}") {
                        GroupCard(
                            group = group,
                            editable = state.editable,
                            nowMs = state.nowMs,
                            onToggleGroup = onToggleGroup,
                            onOpenDetail = { detail = it },
                            onCopyModelId = onCopyModelId,
                            onProbeModel = onProbeModel,
                            onEdit = { row -> editing = row },
                            onDelete = { row -> pendingDelete = row },
                            onShowMore = onShowMoreRows,
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

    // 详情弹层：点一行开它。选中项是页面本地状态，关掉就置空，所以内容用 `row?.modelId`
    // 当标题而不需要额外的标题文案。
    ModelDetailSheet(row = detail, nowMs = state.nowMs, onDismiss = { detail = null })

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
    // Tab 顺序与 ModelGroupBy 声明序一致（FAMILY / NONE）——onGroupBy 按下标取值。
    // 没有"按来源"那一档：一把 Key 的模型来源是同一个（自动获取是个开关，不是逐行的
    // 属性），按来源分组最多分出两堆，那一堆里还是同一种行。
    val tabs = listOf(
        stringResource(Res.string.keymodels_group_by_family),
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
    /** 点开这一行的详情弹层。 */
    onOpenDetail: (UiModelCardRow) -> Unit,
    onCopyModelId: (String) -> Unit,
    onProbeModel: (String) -> Unit,
    onEdit: (UiModelCardRow) -> Unit,
    onDelete: (UiModelCardRow) -> Unit,
    /** 放出这一组剩下的模型（每次一批，见 `MODEL_GROUP_ROW_PAGE`）。 */
    onShowMore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    // 左右内边距交给行自己（下面每行 padding(horizontal)），卡片这层只留上下——这样组里的
    // 分隔线能一直通到卡片两边，而不是缩在内容盒里、两端各短一截。
    AppCard(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(vertical = tokens.screenPadding),
    ) {
        if (group.title.isNotEmpty() || group.key.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding)
                    .clickable { onToggleGroup(group.key) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText(
                    text = groupTitle(group),
                    // 组头要比模型名大：它是"这一组"的名字，而模型名只是组里的条目。
                    // Subtitle 在 Miuix 的字号表里比 Body 还小，用它就等于组头比条目还轻。
                    style = AppTextStyle.Title,
                    // 族名可能是整个模型 id 前缀，长起来没边；不给 maxLines 就会把
                    // 组头撑成两行，与右边那个数字和箭头不在一条基线上。
                    maxLines = 1,
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
            // 行间要有分隔：能力摊到行里之后一行比一行高，没有分隔线时"一坨文字"的
            // 读感很强，而这张卡是一个整体、每条模型是它的一行。
            group.rows.forEachIndexed { index, row ->
                ModelRow(
                    row = row,
                    editable = editable,
                    nowMs = nowMs,
                    onOpenDetail = { onOpenDetail(row) },
                    onCopy = { onCopyModelId(row.modelId) },
                    onProbe = { onProbeModel(row.modelId) },
                    onEdit = { onEdit(row) },
                    onDelete = { onDelete(row) },
                )
                if (index != group.rows.lastIndex) {
                    AppDivider()
                }
            }
            // 剩下的那部分由用户自己放出来：一组一次只组合 MODEL_GROUP_ROW_PAGE 行，
            // 这一行就是"还要不要继续"的那个开关。
            if (group.hiddenRows > 0) {
                AppDivider()
                AppActionRow(
                    text = stringResource(Res.string.keymodels_show_more, group.hiddenRows),
                    onClick = { onShowMore(group.key) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 组标题。族分组的标题是 ViewModel 已经算好的展示名（目录厂商名，或首字母大写的族键），
 * 原样画；族键为 `other` 时用那句"其他"——空 id、纯符号的兜底组。
 */
@Composable
private fun groupTitle(group: UiModelGroup): String = when (group.key) {
    "other" -> stringResource(Res.string.keymodels_group_other)
    else -> group.title
}

@Composable
private fun ModelRow(
    row: UiModelCardRow,
    editable: Boolean,
    nowMs: Long,
    onOpenDetail: () -> Unit,
    onCopy: () -> Unit,
    onProbe: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val summary = probeSummary(row, nowMs)
    // 点击区是整块（名字 + 能力 + 脚注），不是只有名字那一行：行变高之后，
    // 只把第一行做成可点区域会出现"按下面没反应"的空洞。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpenDetail,
                // 长按沿用 Key 详情页那行的口径：开了快速探测就探测这一行，否则复制
                // id。菜单撤掉之后，这是自动发现列表上唯一一行动作入口。
                onLongClick = if (row.quickProbe) onProbe else onCopy,
            )
            // padding 排在 clickable 之后：整块（含左右那 16dp）都是点击区，不会出现
            // "按行两侧的空白没反应"的空洞；上下留白是行间呼吸（能力 chip 摊开之后一行
            // 两到三行高，不留白时下一行的名字直接顶在上一行的 chip 底下）。
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            // 协议 chip 不画：这一页的主语是模型 id，而一把 Key 的模型几乎全走同一个端点
            // （同一家的模型都走那一个端点），它占的宽度正是 id 被截的地方。
            // 探测过才画状态点：「未探测」不是这一行的属性，是"还没有这一项"，而每行都
            // 挂一句就把右边那条边常年占住——模型名正是被这里挤没的。
            if (row.health != UiHealth.Unknown) {
                StatusDot(color = colorOf(row.health), label = labelOf(row.health))
            }
            // 三个点只在手动列表给：自动发现的行没有可编辑的东西（改一个下次同步就覆盖），
            // 复制与探测收到长按上，一行里就不再常驻一个图标。
            if (editable) {
                ModelMenu(
                    row = row,
                    editable = editable,
                    onCopy = onCopy,
                    onProbe = onProbe,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
        }

        // 能力直接摊在名字下面，允许换行：这一页存在的理由就是"这把 Key 上哪些模型能
        // 看图、能调工具"。藏进要点开才看得见的地方等于没有——而没挂上目录的行压根没有
        // 能力可看，那一档下"点开看能力"对它不成立。
        CapabilityChips(row.meta)

        // 脚注行只在真有事要说时画。来源不逐行标：自动获取是 Key 级开关，一整页都是同一个
        // 来源；「还没探测过」也不标（探测过没有由上面那个状态点代表）——那是没有结果。
        if (summary != null || row.unmatched) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (summary != null) {
                    AppText(
                        text = summary,
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                // 没挂上目录的行给一句轻提示：它是"这份目录还缺多少"的诊断入口，
                // 也是唯一能一眼看出上游改名的地方。
                if (row.unmatched) {
                    AppText(
                        text = stringResource(Res.string.keymodels_unmatched),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 最近探测那一句。**没探测过返回 null**，由调用方把这一整行省掉——"还没探测过"是
 * 没有结果，不是一个结果，给每行都挂一句反而盖住了真正探测过的那几条。
 *
 * 探测过给相对时间，有耗时就一起带上。耗时缺了不补 0——"0 毫秒"看起来像一次极快的
 * 成功，而真相是那一发没记到耗时。
 */
@Composable
private fun probeSummary(row: UiModelCardRow, nowMs: Long): String? {
    val probedAt = row.probedAt ?: return null
    val whenText = relativeLabel(nowMs, probedAt)
    val latency = row.latencyMs
    return if (latency == null) {
        stringResource(Res.string.keymodels_probed_ago, whenText)
    } else {
        stringResource(Res.string.keymodels_probed_ago_with_latency, whenText, latency)
    }
}

/**
 * 模型详情弹层。点行开它，取代原来"就地展开一屏属性"：一行下面摊十几行属性会把列表
 * 拽得忽长忽短，而那些属性是"看这一个模型"时才要的，不是"扫一屏模型"时要的。
 *
 * 能力 chip 在列表行上就摊着，这里再给一份完整的，两处读的是同一批开关，不会行上有、
 * 弹层里没有。模型 id 不再重复一遍——它就是弹层的标题，一行里出现两次反而占位置。
 */
@Composable
private fun ModelDetailSheet(row: UiModelCardRow?, nowMs: Long, onDismiss: () -> Unit) {
    AppBottomSheet(
        show = row != null,
        onDismissRequest = onDismiss,
        title = row?.modelId.orEmpty(),
    ) {
        val meta = row?.meta
        val summary = row?.let { probeSummary(it, nowMs) }
        // 第一块是"这一把 Key 上它到底怎么样"：状态点 + 最近探测。探测过没有比目录里
        // 那几项二手资料更要紧，排在厂商与描述之前。没探测过又没有摘要时整块不画——
        // 弹层顶上悬一句「未探测」，跟列表行里刚撤掉的那个标志是同一个东西。
        val probed = row != null && row.health != UiHealth.Unknown
        if (probed || summary != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = appRowInset),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (probed && row != null) {
                    StatusDot(color = colorOf(row.health), label = labelOf(row.health))
                }
                if (summary != null) {
                    AppText(
                        text = summary,
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        maxLines = 1,
                    )
                }
            }
        }
        // chip 与下面的属性行左缘对齐：那些行自带 BasicComponent 的 16dp 内缩，弹层又已经
        // 给过一次 screenPadding，chip 不缩就比属性行整段往左凸一截。
        CapabilityChips(meta, modifier = Modifier.padding(start = appRowInset))
        if (meta == null) {
            // 没挂上目录就说清楚"没有数据"，而不是画一片空白让用户以为加载失败。
            AppText(
                text = stringResource(Res.string.keymodels_cap_none),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
            return@AppBottomSheet
        }
        // 用 group 包住：这几行是"这个模型的一组属性"，摊开是几行悬空文本，没有容器边界。
        AppPreferenceGroup(inset = false) {
            meta.displayName?.let {
                AppValueRow(title = stringResource(Res.string.detail_model_display_name), value = it)
            }
            meta.vendorName?.let {
                AppValueRow(title = stringResource(Res.string.keymodels_meta_vendor), value = it)
            }
            // 上下文不在这里重复：它已经是能力 chip 那一排的第一枚（强调色），一行里给两次
            // 同一个数，第二次还排在厂商下面，读起来像两个不同的东西。
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
                // 标题是「简介」不是「能力」：上面那排 chip 就是能力，同一弹层里再来一行叫
                // 能力的，读起来像两处有一处写错了。这段文本本身也只是目录那句一句话介绍。
                AppValueRow(
                    title = stringResource(Res.string.keymodels_meta_about),
                    value = it,
                    stacked = true,
                )
            }
        }
    }
}

/**
 * 能力 chip：列表行与详情弹层共用，两边同一批开关，改一处两处一起变。
 *
 * 上下文那枚排在最前并且用强调色——它是配客户端时第一个要看的数（"这一轮塞不塞得下"），
 * 而推理/看图那些是"能不能"。一排里只强调这一枚，两枚强调色就等于没有强调。
 */
@Composable
private fun CapabilityChips(meta: UiModelMeta?, modifier: Modifier = Modifier) {
    if (meta == null) return
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
    if (caps.isEmpty() && meta.context == null) return
    // FlowRow 而不是横向滚动的 LazyRow：chip 文案本地化后长短不一，滚动能一眼看见的
    // 只有头两三枚，"这个模型到底会几样"反而要用户横扫才知道。换行多出来的行高换来
    // 的是这一页真正要给的信息。
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        meta.context?.let { AppChip(text = it, accent = true) }
        caps.forEach { label -> AppChip(text = stringResource(label)) }
    }
}

/**
 * 一行的操作菜单。
 *
 * 用图标菜单而不是把动作铺成按钮：一行里塞四五个按钮会把模型 id 挤没，而这一页的
 * 主语就是 id。自动发现的列表压根不画这个菜单（那三项里没有一项能改，图标却常年占着
 * id 的宽度），复制与探测挂在行的长按上；手动列表才给编辑与删除。
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
