package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.ManageTab
import com.lc33.tokenvault.screens.model.ManageUiState
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppFab
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSearchField
import com.lc33.tokenvault.ui.miuix.AppTabRow
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 管理 —— 全部内容的列表，也是**唯一的编辑入口**（计划.md §13.4）。
 *
 * 四类内容各一个分段，而不是"只列供应商、密钥藏在详情里"：想找一张密钥时
 * 不该先猜它挂在哪个供应商下面。
 *
 * 这一页刻意**不放汇总卡**（那是仪表盘的事）也**不放下拉刷新**——下拉在编辑页里
 * 语义含糊（刷新列表？重新探测？），而探测要花钱，必须由仪表盘那个明确的按钮触发。
 */
@Composable
fun ManageScreen(
    state: ManageUiState,
    onSelectTab: (ManageTab) -> Unit,
    onOpenProvider: (Long) -> Unit,
    onNewProvider: () -> Unit,
    onImport: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val query = rememberAppTextFieldState()
    var showCreateSheet by remember { mutableStateOf(false) }

    val tabs = listOf(
        stringResource(R.string.manage_tab_providers),
        stringResource(R.string.manage_tab_keys),
        stringResource(R.string.manage_tab_models),
        stringResource(R.string.manage_tab_accounts),
    )

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.manage_title),
                scrollState = scrollState,
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 搜索与分段固定在顶部，只有 topBar 随滚动收起：切分段时列表位置会变，
            // 而把筛选条一起滚走会让"我刚才筛的是什么"消失。
            AppSearchField(
                state = query,
                hint = stringResource(R.string.search_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
            )
            AppTabRow(
                tabs = tabs,
                selectedIndex = state.tab.ordinal,
                onSelect = { index -> onSelectTab(ManageTab.entries[index]) },
                modifier = Modifier.padding(
                    horizontal = tokens.screenPadding,
                    vertical = tokens.itemSpacing,
                ),
            )
            ManageList(
                state = state,
                scrollState = scrollState,
                onOpenProvider = onOpenProvider,
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

@Composable
private fun ManageList(
    state: ManageUiState,
    scrollState: com.lc33.tokenvault.ui.miuix.AppTopBarScrollState,
    onOpenProvider: (Long) -> Unit,
    onNewProvider: () -> Unit,
    onImport: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val listModifier = Modifier
        .fillMaxSize()
        .appTopBarScroll(scrollState)

    when (state.tab) {
        ManageTab.Providers -> if (state.providers.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.manage_empty_providers_title),
                description = stringResource(R.string.manage_empty_providers_desc),
                actionText = stringResource(R.string.dashboard_empty_import),
                onAction = onImport,
            )
        } else {
            LazyColumn(
                modifier = listModifier,
                verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                items(state.providers.size) { index ->
                    ProviderRow(state.providers[index], onClick = onOpenProvider)
                }
                item { Spacer(modifier = Modifier.height(tokens.fabListBottomSpace)) }
            }
        }

        ManageTab.Keys -> if (state.keys.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.manage_empty_keys_title),
                description = stringResource(R.string.manage_empty_keys_desc),
                actionText = stringResource(R.string.dashboard_empty_new),
                onAction = onNewProvider,
            )
        } else {
            LazyColumn(
                modifier = listModifier,
                verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                items(state.keys.size) { index ->
                    KeyRow(state.keys[index], onClick = onOpenProvider)
                }
                item { Spacer(modifier = Modifier.height(tokens.fabListBottomSpace)) }
            }
        }

        ManageTab.Models -> if (state.models.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.manage_empty_models_title),
                description = stringResource(R.string.manage_empty_models_desc),
            )
        } else {
            LazyColumn(
                modifier = listModifier,
                verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                items(state.models.size) { index ->
                    ModelRow(state.models[index], onClick = onOpenProvider)
                }
                item { Spacer(modifier = Modifier.height(tokens.fabListBottomSpace)) }
            }
        }

        ManageTab.Accounts -> if (state.accounts.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.manage_empty_accounts_title),
                description = stringResource(R.string.manage_empty_accounts_desc),
            )
        } else {
            LazyColumn(
                modifier = listModifier,
                verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                items(state.accounts.size) { index ->
                    AccountRow(state.accounts[index], onClick = onOpenProvider)
                }
                item { Spacer(modifier = Modifier.height(tokens.fabListBottomSpace)) }
            }
        }
    }
}
