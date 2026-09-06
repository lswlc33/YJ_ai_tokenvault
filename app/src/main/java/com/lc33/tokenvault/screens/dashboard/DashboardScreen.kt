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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.DashboardUiState
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 仪表盘 —— **只读**总览（计划.md §13.4）。
 *
 * 这一页不提供任何编辑入口：每一行的点击结果都是"跳到管理页的某个详情"，
 * 由 [onOpenProvider] / [onOpenManageTab] 往外抛。它自己也不弹编辑器。
 * 这条分工是硬规则——一旦这里长出编辑能力，同一份数据就有两个改动入口，
 * 而两个入口迟早各自维护一套校验。
 *
 * 六块卡的顺序是"先结论后细节"：钱 → 有多少东西 → 健康 → 要动手的 → 上次探测 → 备份。
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onOpenProvider: (Long) -> Unit,
    onOpenManage: () -> Unit,
    onOpenProbeRun: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenBalanceBreakdown: () -> Unit,
    onStartProbe: () -> Unit,
    onCancelProbe: () -> Unit,
    onRefreshBalance: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.dashboard_title),
                scrollState = scrollState,
            )
        },
    ) { padding ->
        if (state.isEmpty) {
            EmptyState(
                title = stringResource(R.string.dashboard_empty_title),
                description = stringResource(R.string.dashboard_empty_desc),
                actionText = stringResource(R.string.dashboard_empty_new),
                onAction = onOpenManage,
                modifier = Modifier.padding(padding),
            )
            return@AppScaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            item {
                BalanceCard(
                    balance = state.balance,
                    nowMs = state.nowMs,
                    onRefresh = onRefreshBalance,
                    onOpenBreakdown = onOpenBalanceBreakdown,
                )
            }
            item { CountsCard(state.counts, onOpenManage) }
            item { HealthCard(state.health) }
            item { AttentionCard(state.attention, onOpenProvider) }
            item { ProbeCard(state, onStartProbe, onCancelProbe, onOpenProbeRun) }
            item { BackupCard(state.backup, onOpenSync) }
            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
}
