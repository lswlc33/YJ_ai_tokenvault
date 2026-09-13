package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.add_cd
import tokenvault.shared.generated.resources.dashboard_empty_new
import tokenvault.shared.generated.resources.group_all
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
import tokenvault.shared.generated.resources.manage_title
import tokenvault.shared.generated.resources.refresh_status_cd
import com.lc33.tokenvault.screens.model.ManageUiState
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.common.LoadingState
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppFab
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSearchField
import com.lc33.tokenvault.ui.miuix.AppActionRow
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
) {
    val scrollState = rememberAppTopBarScrollState()
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 搜索与分组固定在顶部，只有 topBar 随滚动收起：把筛选条也滚走
            // 会让"我刚才筛的是哪个分组"消失。
            AppSearchField(
                state = query,
                hint = stringResource(Res.string.manage_search_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
                onValueChange = onQueryChange,
            )
            GroupFilterRow(
                state = state,
                onSelectGroup = onSelectGroup,
            )
            ProviderList(
                state = state,
                scrollState = scrollState,
                selecting = selecting,
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

@Composable
private fun ProviderList(
    state: ManageUiState,
    scrollState: com.lc33.tokenvault.ui.miuix.AppTopBarScrollState,
    selecting: Boolean,
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
        modifier = Modifier
            .fillMaxSize()
            .appTopBarScroll(scrollState),
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
        item { Spacer(modifier = Modifier.height(tokens.fabListBottomSpace)) }
    }
}
