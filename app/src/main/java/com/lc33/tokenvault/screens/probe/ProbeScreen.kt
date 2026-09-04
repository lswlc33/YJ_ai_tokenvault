package com.lc33.tokenvault.screens.probe

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
 * 探测。M5 接入 `ProbeUiState`：上次摘要、本轮进度与取消、按结果分组的清单。
 */
@Composable
fun ProbeScreen() {
    val scrollState = rememberAppTopBarScrollState()
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.probe_title),
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
                    title = stringResource(R.string.probe_empty_title),
                    description = stringResource(R.string.probe_empty_desc),
                )
            }
        }
    }
}
