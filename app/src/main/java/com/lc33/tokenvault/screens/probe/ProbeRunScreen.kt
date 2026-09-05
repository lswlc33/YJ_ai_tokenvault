package com.lc33.tokenvault.screens.probe

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
import com.lc33.tokenvault.screens.model.ProbeRunSummary
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/** 一项探测结果。`detail` 是已脱敏的上游 message 前 200 字符。 */
data class ProbeItemRow(
    val target: String,
    val providerId: Long,
    val health: UiHealth,
    /** 已本地化的结论，例如"密钥无效"或"本轮未探测"。 */
    val outcome: String,
    val detail: String?,
    val latencyMs: Long?,
)

/**
 * 探测明细（计划.md §13.4，`ProbeRunRoute`）。
 *
 * 探测是**动作**不是内容，所以它是仪表盘的二级页而不是一级页。这一页只做两件事：
 * 看结果、重试。
 *
 * **级别与范围开关不在这里**，在设置 → 探测：它们是配置不是动作，放在结果页会让人
 * 以为改了就立刻重跑。
 *
 * 失败项排在前面，"本轮未探测"单独一组——后者不是失败（红线 11），把它混进失败里
 * 会让用户以为有 3 个东西坏了，而其实只坏了 1 个、另外 2 个根本没测。
 */
@Composable
fun ProbeRunScreen(
    lastRun: ProbeRunSummary?,
    failed: List<ProbeItemRow>,
    skipped: List<ProbeItemRow>,
    succeeded: List<ProbeItemRow>,
    onBack: () -> Unit,
    onRetryFailed: () -> Unit,
    onOpenProvider: (Long) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.probe_run_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(R.string.back_cd),
                        onClick = onBack,
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
            if (lastRun != null) {
                item { SummaryCard(lastRun, failed.isNotEmpty() || skipped.isNotEmpty(), onRetryFailed) }
            }
            if (failed.isNotEmpty()) {
                item { SectionTitle(text = stringResource(R.string.probe_run_failed)) }
                items(failed.size) { index -> ItemCard(failed[index], onOpenProvider) }
            }
            if (skipped.isNotEmpty()) {
                item { SectionTitle(text = stringResource(R.string.probe_run_skipped)) }
                items(skipped.size) { index -> ItemCard(skipped[index], onOpenProvider) }
            }
            if (succeeded.isNotEmpty()) {
                item { SectionTitle(text = stringResource(R.string.probe_run_succeeded)) }
                items(succeeded.size) { index -> ItemCard(succeeded[index], onOpenProvider) }
            }
            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
}

@Composable
private fun SummaryCard(run: ProbeRunSummary, hasRetryable: Boolean, onRetry: () -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
    ) {
        AppText(
            text = stringResource(R.string.dashboard_probe_finished, run.finishedAgo, run.durationLabel),
            style = AppTextStyle.Body,
        )
        AppText(
            text = stringResource(
                R.string.dashboard_probe_counts,
                run.total,
                run.succeeded,
                run.failed,
                run.skipped,
            ),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (hasRetryable) {
            AppTextButton(
                text = stringResource(R.string.probe_run_retry),
                onClick = onRetry,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
        }
    }
}

@Composable
private fun ItemCard(row: ProbeItemRow, onOpenProvider: (Long) -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
        onClick = { onOpenProvider(row.providerId) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 两行：目标是 "host · modelId"，等宽字体下一行放不下，
            // 截成 "ps.air-outer.com ·" 等于把最要紧的那半截掉了
            AppText(
                text = row.target,
                style = AppTextStyle.Body,
                fontFamily = tokens.monoFontFamily,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            if (row.latencyMs != null) {
                AppText(
                    text = stringResource(R.string.manage_latency, row.latencyMs),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                )
            }
        }
        Column(modifier = Modifier.padding(top = 4.dp)) {
            StatusDot(color = colorOf(row.health), label = row.outcome)
            if (row.detail != null) {
                // 上游 message 已经过 Redactor：它经常回显密钥的一部分（M0.5 实测）
                AppText(
                    text = row.detail,
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
