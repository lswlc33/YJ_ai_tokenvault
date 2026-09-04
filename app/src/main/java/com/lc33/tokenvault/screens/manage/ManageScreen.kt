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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.ManageUiState
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppFab
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSearchField
import com.lc33.tokenvault.ui.miuix.AppTextButton
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
    onImport: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val query = rememberAppTextFieldState()
    var showCreateSheet by remember { mutableStateOf(false) }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.manage_title),
                scrollState = scrollState,
                actions = {
                    AppIconButton(
                        icon = AppIcon.Tune,
                        contentDescription = stringResource(R.string.manage_groups_cd),
                        onClick = onOpenGroups,
                    )
                },
            )
        },
        floatingActionButton = {
            AppFab(
                icon = AppIcon.Add,
                contentDescription = stringResource(R.string.add_cd),
                onClick = { showCreateSheet = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 搜索与分组固定在顶部，只有 topBar 随滚动收起：把筛选条也滚走
            // 会让"我刚才筛的是哪个分组"消失。
            AppSearchField(
                state = query,
                hint = stringResource(R.string.manage_search_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
            )
            GroupFilterRow(
                state = state,
                onSelectGroup = onSelectGroup,
            )
            ProviderList(
                state = state,
                scrollState = scrollState,
                onOpenProvider = onOpenProvider,
                onSelectGroup = onSelectGroup,
                onNewProvider = onNewProvider,
                onImport = onImport,
            )
        }
    }

    AppBottomSheet(
        show = showCreateSheet,
        onDismissRequest = { showCreateSheet = false },
        title = stringResource(R.string.add_cd),
    ) {
        AppTextButton(
            text = stringResource(R.string.dashboard_empty_new),
            onClick = {
                showCreateSheet = false
                onNewProvider()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        AppTextButton(
            text = stringResource(R.string.dashboard_empty_import),
            onClick = {
                showCreateSheet = false
                onImport()
            },
            modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun ProviderList(
    state: ManageUiState,
    scrollState: com.lc33.tokenvault.ui.miuix.AppTopBarScrollState,
    onOpenProvider: (Long) -> Unit,
    onSelectGroup: (Long?) -> Unit,
    onNewProvider: () -> Unit,
    onImport: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val rows = state.visibleProviders

    if (rows.isEmpty()) {
        // 整库空 vs 某个分组筛空，是两件事：后者不给"新建"CTA，
        // 否则用户会以为整个库都空了。
        if (state.providers.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.manage_empty_providers_title),
                description = stringResource(R.string.manage_empty_providers_desc),
                actionText = stringResource(R.string.dashboard_empty_import),
                onAction = onImport,
            )
        } else {
            EmptyState(
                title = stringResource(R.string.manage_empty_group_title),
                description = stringResource(R.string.manage_empty_group_desc),
                actionText = stringResource(R.string.group_all),
                onAction = { onSelectGroup(null) },
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
            ProviderRow(rows[index], onClick = onOpenProvider)
        }
        item { Spacer(modifier = Modifier.height(tokens.fabListBottomSpace)) }
    }
}
