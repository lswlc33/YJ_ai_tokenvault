package com.lc33.tokenvault.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.dashboard_title
import tokenvault.shared.generated.resources.refresh_status_cd
import com.lc33.tokenvault.screens.model.DashboardUiState
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 仪表盘 —— **只读**总览（计划.md §13.4）。
 *
 * 这一页不提供编辑入口，只保留三块：余额、内容概览和探测。失败明细与处理入口都回到
 * 管理页，避免首页同时承担"看总览"和"修问题"两种职责。
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onOpenManage: () -> Unit,
    onStartProbe: () -> Unit,
    onCancelProbe: () -> Unit,
    onRefreshBalance: () -> Unit,
    onRefreshStatus: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.dashboard_title),
                scrollState = scrollState,
                actions = {
                    AppIconButton(
                        icon = AppIcon.Refresh,
                        contentDescription = stringResource(Res.string.refresh_status_cd),
                        onClick = onRefreshStatus,
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
            item { Spacer(modifier = Modifier.height(tokens.itemSpacing)) }
            item {
                BalanceCard(
                    balance = state.balance,
                    nowMs = state.nowMs,
                    onRefresh = onRefreshBalance,
                )
            }
            item { CountsCard(state.counts, onOpenManage) }
            item { ProbeCard(state, onStartProbe, onCancelProbe) }
            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
}
