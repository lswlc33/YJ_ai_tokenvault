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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.dashboard_probe_counts
import tokenvault.shared.generated.resources.dashboard_probe_finished
import tokenvault.shared.generated.resources.dashboard_probe_never
import tokenvault.shared.generated.resources.manage_latency
import tokenvault.shared.generated.resources.probe_run_empty_desc
import tokenvault.shared.generated.resources.probe_run_failed
import tokenvault.shared.generated.resources.probe_run_retry
import tokenvault.shared.generated.resources.probe_run_skipped
import tokenvault.shared.generated.resources.probe_run_succeeded
import tokenvault.shared.generated.resources.probe_run_title
import tokenvault.shared.generated.resources.time_duration_seconds
import com.lc33.tokenvault.screens.model.ProbeRunSummary
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.common.durationSeconds
import com.lc33.tokenvault.ui.common.labelOf
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/** 一项探测结果。`detail` 是已脱敏的上游 message 前 200 字符。 */
data class ProbeItemRow(
    /** 供应商名。明细页每一项要能看出"是哪一家"（§13.4）。 */
    val providerName: String,
    val providerId: Long,
    val health: UiHealth,
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
    nowMs: Long,
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
                title = stringResource(Res.string.probe_run_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = onBack,
                    )
                },
            )
        },
    ) { padding ->
        // 三组都空且没有上一轮：这一页没有任何内容可画。不给空态的表现是一屏白，
        // 而那看起来像加载失败
        if (lastRun == null && failed.isEmpty() && skipped.isEmpty() && succeeded.isEmpty()) {
            EmptyState(
                title = stringResource(Res.string.dashboard_probe_never),
                description = stringResource(Res.string.probe_run_empty_desc),
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
            if (lastRun != null) {
                item { SummaryCard(lastRun, nowMs, failed.isNotEmpty() || skipped.isNotEmpty(), onRetryFailed) }
            }
            if (failed.isNotEmpty()) {
                item { SectionTitle(text = stringResource(Res.string.probe_run_failed)) }
                items(failed.size) { index -> ItemCard(failed[index], onOpenProvider) }
            }
            if (skipped.isNotEmpty()) {
                item { SectionTitle(text = stringResource(Res.string.probe_run_skipped)) }
                items(skipped.size) { index -> ItemCard(skipped[index], onOpenProvider) }
            }
            if (succeeded.isNotEmpty()) {
                item { SectionTitle(text = stringResource(Res.string.probe_run_succeeded)) }
                items(succeeded.size) { index -> ItemCard(succeeded[index], onOpenProvider) }
            }
            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
}

@Composable
private fun SummaryCard(
    run: ProbeRunSummary,
    nowMs: Long,
    hasRetryable: Boolean,
    onRetry: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
    ) {
        AppText(
            text = stringResource(
                Res.string.dashboard_probe_finished,
                relativeLabel(nowMs, run.finishedAtMs),
                stringResource(Res.string.time_duration_seconds, durationSeconds(run.durationMs)),
            ),
            style = AppTextStyle.Body,
        )
        AppText(
            text = stringResource(
                Res.string.dashboard_probe_counts,
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
            AppActionRow(
                text = stringResource(Res.string.probe_run_retry),
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
            AppText(
                text = row.providerName,
                style = AppTextStyle.Body,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            if (row.latencyMs != null) {
                AppText(
                    text = stringResource(Res.string.manage_latency, row.latencyMs),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                )
            }
        }
        Column(modifier = Modifier.padding(top = 4.dp)) {
            // 状态点：颜色由 health 决定，标签用四档通用文案（labelOf）。与列表页同一条
            // 约定——这里不造一套"密钥无效/余额不足"的专属文案（红线 17：同一状态全应用一套文案）。
            StatusDot(color = colorOf(row.health), label = labelOf(row.health))
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
