package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.add_cd
import tokenvault.shared.generated.resources.dashboard_empty_new
import tokenvault.shared.generated.resources.manage_batch_change_group
import tokenvault.shared.generated.resources.manage_batch_delete
import tokenvault.shared.generated.resources.manage_batch_delete_cd
import tokenvault.shared.generated.resources.manage_batch_delete_desc
import tokenvault.shared.generated.resources.manage_batch_delete_title
import tokenvault.shared.generated.resources.manage_batch_ungrouped
import tokenvault.shared.generated.resources.manage_deselect_all
import tokenvault.shared.generated.resources.manage_empty_group_desc
import tokenvault.shared.generated.resources.manage_empty_group_title
import tokenvault.shared.generated.resources.manage_empty_search_desc
import tokenvault.shared.generated.resources.manage_empty_search_title
import tokenvault.shared.generated.resources.manage_empty_providers_desc
import tokenvault.shared.generated.resources.manage_empty_providers_title
import tokenvault.shared.generated.resources.manage_exit_selection_cd
import tokenvault.shared.generated.resources.manage_groups_cd
import tokenvault.shared.generated.resources.manage_search_hint
import tokenvault.shared.generated.resources.manage_select_all
import tokenvault.shared.generated.resources.manage_selected_count
import tokenvault.shared.generated.resources.manage_sort_balance
import tokenvault.shared.generated.resources.manage_sort_cd
import tokenvault.shared.generated.resources.manage_sort_last_probe
import tokenvault.shared.generated.resources.manage_sort_manual
import tokenvault.shared.generated.resources.manage_sort_name
import tokenvault.shared.generated.resources.manage_title
import tokenvault.shared.generated.resources.refresh_status_cd
import com.lc33.tokenvault.screens.model.ManageUiState
import com.lc33.tokenvault.screens.model.ProviderSort
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.common.LoadingState
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppFab
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppIconMenu
import com.lc33.tokenvault.ui.miuix.AppMenuGroup
import com.lc33.tokenvault.ui.miuix.AppMenuItem
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSearchField
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 管理 —— **供应商列表**，也是唯一的编辑入口（计划.md §13.4）。
 *
 * 这一页只列供应商：密钥、模型、平台账号都在供应商详情里。它们没有独立于供应商的
 * 意义，一张密钥必然属于某一家，单独列出来只会让"这是谁的 key"变成一次额外的确认——
 * 第一版做成四个分段是过度设计，四张表里三张的每一行都得带"所属供应商"这一列才看得懂。
 *
 * 顶部的筛选条是**用户自定义的分组**，横向可滑（分组数量由用户决定，可能 2 个也可能
 * 15 个，固定分段放不下）。
 *
 * 刻意**不放下拉刷新**：这是编辑页，下拉在这里语义含糊（刷新列表？重新探测？），
 * 而探测要花钱，必须由仪表盘那个明确的按钮触发。
 */
@Composable
fun ManageScreen(
    state: ManageUiState,
    onSelectGroup: (Long?) -> Unit,
    onOpenProvider: (Long) -> Unit,
    onOpenGroups: () -> Unit,
    onNewProvider: () -> Unit,
    onRefreshStatus: () -> Unit,
    onQueryChange: (String) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSelectAll: (List<Long>) -> Unit,
    onClearSelection: () -> Unit,
    onBatchDelete: (Set<Long>) -> Unit,
    onBatchSetGroup: (Set<Long>, Long?) -> Unit,
    /**
     * 排序档。以前 [ManageViewModel] 那一整套 `onSort` / `sortProviders` / `manage_sort_*`
     * 文案都齐了却没有入口，界面永远停在「手动排序」那一档。
     */
    onSort: (ProviderSort) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val listState = rememberLazyListState()
    // 搜索框跟着滚动收放（与整屏模型页同一口径）：往下滚就收，回到顶部再回来。分组筛选
    // 条那排**不**跟着收——它说的是"现在筛的是哪个分组"，收掉就等于把用户正在用的筛选
    // 藏起来。信号取"列表还能不能往上滚"，页面不去碰 MIUIX 的 scrollBehavior。
    val searchVisible = !listState.canScrollBackward
    val tokens = LocalAppTokens.current
    val query = rememberAppTextFieldState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }

    // 搜索串由状态驱动：state.query 与输入框 state 双向同步（转屏 / 重组不丢）。
    val queryState = state.query
    LaunchedEffect(queryState) {
        if (query.text != queryState) query.setText(queryState)
    }

    val selecting = state.selecting
    val visibleRows = state.visibleProviders
    val selectedCount = state.selection.size

    AppScaffold(
        topBar = {
            AppTopBar(
                title = if (selecting) {
                    stringResource(Res.string.manage_selected_count, selectedCount)
                } else {
                    stringResource(Res.string.manage_title)
                },
                scrollState = scrollState,
                navigationIcon = if (selecting) {
                    {
                        AppIconButton(
                            icon = AppIcon.Back,
                            contentDescription = stringResource(Res.string.manage_exit_selection_cd),
                            onClick = onClearSelection,
                        )
                    }
                } else {
                    {}
                },
                actions = {
                    if (selecting) {
                        val allVisibleSelected = selectedCount == visibleRows.size && visibleRows.isNotEmpty()
                        AppIconButton(
                            icon = if (allVisibleSelected) AppIcon.Backspace else AppIcon.Ok,
                            contentDescription = stringResource(
                                if (allVisibleSelected) {
                                    Res.string.manage_deselect_all
                                } else {
                                    Res.string.manage_select_all
                                },
                            ),
                            onClick = {
                                if (allVisibleSelected) onClearSelection() else onSelectAll(visibleRows.map { it.id })
                            },
                        )
                    } else {
                        AppIconButton(
                            icon = AppIcon.Refresh,
                            contentDescription = stringResource(Res.string.refresh_status_cd),
                            onClick = onRefreshStatus,
                        )
                        AppIconButton(
                            icon = AppIcon.Tune,
                            contentDescription = stringResource(Res.string.manage_groups_cd),
                            onClick = onOpenGroups,
                        )
                        // 排序收进一个菜单：四档摆成第二行 chip 会把分组筛选条挤歪，
                        // 而排序不是每次进页面都要动的东西。
                        SortMenu(current = state.sort, onSort = onSort)
                    }
                },
            )
        },
        floatingActionButton = if (selecting) {
            {
                AppFab(
                    icon = AppIcon.Delete,
                    contentDescription = stringResource(Res.string.manage_batch_delete_cd),
                    onClick = { showDeleteConfirm = true },
                )
            }
        } else {
            {
                AppFab(
                    icon = AppIcon.Add,
                    contentDescription = stringResource(Res.string.add_cd),
                    onClick = onNewProvider,
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@AppScaffold
        }
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 只避让顶部与横向 inset：MIUIX Scaffold 的 content 本来就铺满整窗，
                // 这里若吃下 bottom padding，列表会被裁断在药丸上沿，玻璃底栏采样不到
                // 内容而发黑。底部避让交给 ProviderList 的 contentPadding。
                .padding(
                    top = padding.calculateTopPadding(),
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                ),
        ) {
            AnimatedVisibility(
                visible = searchVisible,
                // 与整屏模型页同一套时长：收快放慢，放回来时突然弹出来最刺眼。
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                AppSearchField(
                    state = query,
                    hint = stringResource(Res.string.manage_search_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
                    onValueChange = onQueryChange,
                )
            }
            // 一个分组都没有时这一排只有「全部」一枚——筛不筛都一样，白白占一行。
            // 用户建了第一个分组它才出现。
            if (state.groups.size > 1) {
                GroupFilterRow(
                    state = state,
                    onSelectGroup = onSelectGroup,
                )
            }
            ProviderList(
                state = state,
                scrollState = scrollState,
                listState = listState,
                selecting = selecting,
                bottomInset = padding.calculateBottomPadding(),
                onOpenProvider = onOpenProvider,
                onEnterSelection = onEnterSelection,
                onToggleSelect = onToggleSelect,
                onNewProvider = onNewProvider,
                onBatchSetGroup = { showGroupPicker = true },
            )
        }
    }

    // 批量删除二次确认。文案里写清连带删掉什么（§13.4）。
    AppDialog(
        show = showDeleteConfirm,
        onDismissRequest = { showDeleteConfirm = false },
        title = stringResource(Res.string.manage_batch_delete_title),
        summary = stringResource(Res.string.manage_batch_delete_desc, selectedCount),
        confirmText = stringResource(Res.string.manage_batch_delete),
        onConfirm = {
            showDeleteConfirm = false
            onBatchDelete(state.selection)
        },
    )

    // 批量改分组：列出分组（含「未分组」），点一个就落。
    AppBottomSheet(
        show = showGroupPicker,
        onDismissRequest = { showGroupPicker = false },
        title = stringResource(Res.string.manage_batch_change_group),
    ) {
        GroupPickerSheet(
            groups = state.groups,
            onPick = { groupId ->
                showGroupPicker = false
                onBatchSetGroup(state.selection, groupId)
            },
        )
    }
}

/** 顶栏的排序菜单。四档各一项，当前那档打勾。 */
@Composable
private fun SortMenu(current: ProviderSort, onSort: (ProviderSort) -> Unit) {
    val options = listOf(
        ProviderSort.MANUAL to Res.string.manage_sort_manual,
        ProviderSort.NAME to Res.string.manage_sort_name,
        ProviderSort.BALANCE to Res.string.manage_sort_balance,
        ProviderSort.LAST_PROBE to Res.string.manage_sort_last_probe,
    )
    AppIconMenu(
        icon = AppIcon.Sort,
        contentDescription = stringResource(Res.string.manage_sort_cd),
        // 选完就收：这是单选菜单，不像日志页那个要连着改好几项。
        collapseOnSelection = true,
        groups = listOf(
            AppMenuGroup(
                options.map { (sort, label) ->
                    AppMenuItem(
                        text = stringResource(label),
                        selected = sort == current,
                        onClick = { onSort(sort) },
                    )
                },
            ),
        ),
    )
}

/** 分组筛选条。第一枚固定是「全部」，其余是用户自定义分组，整行横向可滑。 */
@Composable
private fun GroupFilterRow(
    state: ManageUiState,
    onSelectGroup: (Long?) -> Unit,
) {
    val tokens = LocalAppTokens.current
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = tokens.itemSpacing),
        contentPadding = PaddingValues(horizontal = tokens.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        items(state.groups.size) { index ->
            val group = state.groups[index]
            AppFilterChip(
                text = group.name,
                selected = group.id == state.selectedGroupId,
                onClick = { onSelectGroup(group.id) },
                trailingText = group.providerCount.toString(),
            )
        }
    }
}

/** 批量改分组面板：第一项「未分组」，其余是自定义分组。 */
@Composable
private fun GroupPickerSheet(
    groups: List<com.lc33.tokenvault.screens.model.UiGroup>,
    onPick: (Long?) -> Unit,
) {
    // 行入口都要包在 group 里：弹层内容和页面一样是"一组设置行"，
    // 直接铺裸行会缺了容器背景与圆角，跟页面里的同一类行看起来不是一套。
    AppPreferenceGroup(inset = false) {
        // 第一枚是「全部」伪分组（id == null），这里要的是「未分组」（groupId = null），
        // 语义不同：把它过滤掉，另放一枚「未分组」在最前。
        AppActionRow(
            text = stringResource(Res.string.manage_batch_ungrouped),
            onClick = { onPick(null) },
            modifier = Modifier.fillMaxWidth(),
        )
        groups.filter { it.id != null }.forEach { group ->
            AppActionRow(
                text = group.name,
                onClick = { onPick(group.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProviderList(
    state: ManageUiState,
    scrollState: com.lc33.tokenvault.ui.miuix.AppTopBarScrollState,
    /** 由外层持有：搜索框要知道"列表还能不能往上滚"才决定自己收不收。 */
    listState: LazyListState,
    selecting: Boolean,
    bottomInset: Dp,
    onOpenProvider: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onNewProvider: () -> Unit,
    onBatchSetGroup: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val rows = state.visibleProviders

    if (rows.isEmpty()) {
        // 整库空 vs 某个分组筛空，是两件事：后者不给"新建"CTA，
        // 否则用户会以为整个库都空了。
        if (state.query.isNotBlank()) {
            EmptyState(
                title = stringResource(Res.string.manage_empty_search_title),
                description = stringResource(Res.string.manage_empty_search_desc),
            )
        } else if (state.providers.isEmpty()) {
            EmptyState(
                title = stringResource(Res.string.manage_empty_providers_title),
                description = stringResource(Res.string.manage_empty_providers_desc),
                actionText = stringResource(Res.string.dashboard_empty_new),
                onAction = onNewProvider,
            )
        } else {
            EmptyState(
                title = stringResource(Res.string.manage_empty_group_title),
                description = stringResource(Res.string.manage_empty_group_desc),
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .appTopBarScroll(scrollState),
        // 底部避让走 contentPadding 而不是外层 padding：列表要能滚进药丸底下，
        // 玻璃才采样得到内容；滚到底时末项恰好停在底栏上沿。
        contentPadding = PaddingValues(bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        items(rows.size) { index ->
            val row = rows[index]
            ProviderRow(
                row = row,
                selecting = selecting,
                selected = row.id in state.selection,
                onClick = { id -> if (selecting) onToggleSelect(id) else onOpenProvider(id) },
                onLongPress = onEnterSelection,
            )
        }
        if (selecting) {
            // 多选态底部操作条：改分组（删除走 FAB）。
            item {
                AppActionRow(
                    text = stringResource(Res.string.manage_batch_change_group),
                    onClick = onBatchSetGroup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                )
            }
        }
        // 末尾一律让出药丸的位置：这一页**两种状态下都有 FAB**（普通态是「新建」，
        // 多选态是「删除」），而 FAB 不是系统 inset——不垫高，普通态最后一张供应商卡的
        // 右列（余额、状态点、延迟）就永远压在加号底下，怎么滚都滚不出来。
        // 分组页同一条留白就是无条件给的（GroupsScreen 的列表末尾）。
        item {
            Spacer(modifier = Modifier.height(tokens.fabListBottomSpace))
        }
    }
}
