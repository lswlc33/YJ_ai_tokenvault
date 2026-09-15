package com.lc33.tokenvault.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.dashboard_title
import tokenvault.shared.generated.resources.refresh_status_cd
import com.lc33.tokenvault.screens.model.DashboardUiState
import com.lc33.tokenvault.ui.common.LoadingState
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.LocalAppDarkTheme
import com.lc33.tokenvault.ui.miuix.appLayerBackdrop
import com.lc33.tokenvault.ui.miuix.appPrimaryColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppLayerBackdrop
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
    onOpenProbeDetail: () -> Unit,
    onRefreshBalance: () -> Unit,
    onRefreshStatus: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current

    // 玻璃卡要"透过磨砂看到背后"，而纯色背景模糊前后一个样——所以这一页自己铺一层
    // 极淡的主色环境光，供卡片采样。
    //
    // **这一层里绝不能出现玻璃卡自己。** 卡片如果落在它录制的子树里，录下的画面就会
    // 包含卡片（而卡片又要采样这份画面），渲染树每帧深一层，几秒后 RenderThread 栈溢出
    // 闪退——2026-09-15 的崩溃就是这么来的（tombstone：512 帧 prepareTreeImpl 递归，
    // "stack pointer is close to top of stack; likely stack overflow"）。
    // 所以卡片是这一层的**兄弟节点**，永远不在它的子树内。
    val glassBackdrop = rememberAppLayerBackdrop()

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
        if (state.loading && state.counts.providers == 0 && state.lastRun == null && state.progress == null) {
            LoadingState(modifier = Modifier.padding(padding))
            return@AppScaffold
        }
        Box(modifier = Modifier.fillMaxSize()) {
            DashboardAmbientLayer(
                modifier = Modifier
                    .fillMaxSize()
                    .appLayerBackdrop(glassBackdrop),
            )
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
                        blurBackdrop = glassBackdrop,
                    )
                }
                item { CountsCard(state.counts, onOpenManage) }
                item { ProbeCard(state, onOpenProbeDetail) }
                // 滑到底的呼吸空间：内容画到窗口底部（透出玻璃底栏），不垫就会贴边。
                item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
            }
        }
    }
}

/**
 * 首页的环境光背景层。**只有渐变，没有任何玻璃卡**（理由见 [DashboardScreen] 里那段说明）。
 *
 * 光收在页面上部：余额卡就在那儿，背后有一束品牌色的柔光，卡片才算"玻璃"；
 * 光带之外仍是页面本来的底色——铺满整页会把首页染成一个和别的 tab 不一样的颜色。
 * 半径只给到约四成屏高，往下自然淡出，滑到下面的卡片不会踩在一块色块上。
 */
@Composable
private fun DashboardAmbientLayer(modifier: Modifier = Modifier) {
    val accent = appPrimaryColor
    val isDark = LocalAppDarkTheme.current
    // 深浅色各一档：深色下要亮一点才看得出"玻璃后面有东西"，
    // 浅色下压住，否则白底上糊一片脏颜色。
    val topAlpha = if (isDark) 0.30f else 0.16f
    Box(
        modifier = modifier.drawBehind {
            // 三段式：两段式在"到透明"那一处会留下一圈看得见的弧线边界，
            // 玻璃卡把它放大成一块硬边色斑。
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = topAlpha),
                        accent.copy(alpha = topAlpha * 0.45f),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = size.height * 0.45f,
                ),
            )
        },
    )
}
