package com.lc33.tokenvault.screens.vault

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState

/**
 * 金库（首页）。M3 接入 `VaultUiState`：汇总卡、供应商卡列表、搜索排序、批量操作。
 */
@Composable
fun VaultScreen() {
    val scrollState = rememberAppTopBarScrollState()
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.vault_title),
                scrollState = scrollState,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
        ) {
            item {
                EmptyState(
                    title = stringResource(R.string.vault_empty_title),
                    description = stringResource(R.string.vault_empty_desc),
                )
            }
        }
    }
}
