package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState

/**
 * 设置。M10 之前会陆续长成计划.md §13.4 的七组：外观 / 安全 / 探测 /
 * 客户端预设 / 数据 / 备份 / 关于。
 */
@Composable
fun SettingsScreen(onOpenAbout: () -> Unit) {
    val scrollState = rememberAppTopBarScrollState()
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_title),
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
            item { SectionTitle(text = stringResource(R.string.settings_section_about)) }
            item {
                AppArrowRow(
                    title = stringResource(R.string.settings_about_row),
                    summary = stringResource(R.string.settings_about_row_summary),
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
