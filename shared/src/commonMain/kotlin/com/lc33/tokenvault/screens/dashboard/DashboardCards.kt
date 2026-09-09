package com.lc33.tokenvault.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.count_accounts
import tokenvault.shared.generated.resources.count_keys
import tokenvault.shared.generated.resources.count_models
import tokenvault.shared.generated.resources.count_providers
import tokenvault.shared.generated.resources.dashboard_attention_fix_client
import tokenvault.shared.generated.resources.dashboard_attention_none
import tokenvault.shared.generated.resources.dashboard_attention_title
import tokenvault.shared.generated.resources.dashboard_backup_last
import tokenvault.shared.generated.resources.dashboard_backup_never
import tokenvault.shared.generated.resources.dashboard_backup_now
import tokenvault.shared.generated.resources.dashboard_backup_title
import tokenvault.shared.generated.resources.dashboard_balance_failed
import tokenvault.shared.generated.resources.dashboard_balance_no_fx
import tokenvault.shared.generated.resources.dashboard_balance_none
import tokenvault.shared.generated.resources.dashboard_balance_title
import tokenvault.shared.generated.resources.dashboard_balance_updated
import tokenvault.shared.generated.resources.dashboard_counts_title
import tokenvault.shared.generated.resources.dashboard_health_all_ok
import tokenvault.shared.generated.resources.dashboard_health_empty
import tokenvault.shared.generated.resources.dashboard_health_title
import tokenvault.shared.generated.resources.dashboard_probe_cancel
import tokenvault.shared.generated.resources.dashboard_probe_counts
import tokenvault.shared.generated.resources.dashboard_probe_detail
import tokenvault.shared.generated.resources.dashboard_probe_finished
import tokenvault.shared.generated.resources.dashboard_probe_never
import tokenvault.shared.generated.resources.dashboard_probe_no_autolock
import tokenvault.shared.generated.resources.dashboard_probe_running
import tokenvault.shared.generated.resources.dashboard_probe_start
import tokenvault.shared.generated.resources.dashboard_probe_title
import tokenvault.shared.generated.resources.refresh_cd
import tokenvault.shared.generated.resources.time_duration_seconds
import com.lc33.tokenvault.screens.model.AttentionItem
import com.lc33.tokenvault.screens.model.AttentionKind
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.screens.model.BalanceSummary
import com.lc33.tokenvault.screens.model.ContentCounts
import com.lc33.tokenvault.screens.model.DashboardUiState
import com.lc33.tokenvault.screens.model.HealthBreakdown
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.ui.common.BarSegment
import com.lc33.tokenvault.ui.common.RelativeBucket
import com.lc33.tokenvault.ui.common.SegmentedBar
import com.lc33.tokenvault.ui.common.StatTile
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.common.durationSeconds
import com.lc33.tokenvault.ui.common.labelOf
import com.lc33.tokenvault.ui.common.messageOf
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDivider
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/** 卡片统一的外边距。仪表盘每块卡都用它，间距只在一处定义。 */
@Composable
private fun cardModifier(): Modifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = LocalAppTokens.current.screenPadding)

@Composable
private fun CardTitle(text: String) {
    AppText(text = text, style = AppTextStyle.Subtitle)
}

@Composable
internal fun BalanceCard(
    balance: BalanceSummary,
    nowMs: Long,
    onRefresh: () -> Unit,
    onOpenBreakdown: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val palette = LocalStatusPalette.current
    AppCard(modifier = cardModifier(), onClick = onOpenBreakdown) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                CardTitle(stringResource(Res.string.dashboard_balance_title))
                val updatedAt = balance.updatedAt
                if (updatedAt != null) {
                    // 分档在纯函数里、文案在资源里（RelativeTime.kt 就是为此拆开的），
                    // 所以 ViewModel 给的是时间戳而不是一句“12 分钟前”
                    AppText(
                        text = stringResource(
                            Res.string.dashboard_balance_updated,
                            relativeLabel(nowMs, updatedAt),
                        ),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                    )
                }
            }
            AppIconButton(
                icon = AppIcon.Refresh,
                contentDescription = stringResource(Res.string.refresh_cd),
                onClick = onRefresh,
            )
        }
        if (balance.perCurrency.isEmpty()) {
            AppText(
                text = stringResource(Res.string.dashboard_balance_none),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
            return@AppCard
        }
        balance.perCurrency.forEachIndexed { index, money ->
            AppText(
                text = "${money.currency} ${money.amount}",
                // 第一个币种放大，其余小一号：不做汇率换算，所以没有"总额"可以放大
                style = if (index == 0) AppTextStyle.Title else AppTextStyle.Body,
                modifier = Modifier.padding(top = if (index == 0) tokens.itemSpacing else 2.dp),
            )
        }
        AppText(
            text = stringResource(Res.string.dashboard_balance_no_fx),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        if (balance.failedProviderCount > 0) {
            AppText(
                text = stringResource(Res.string.dashboard_balance_failed, balance.failedProviderCount),
                style = AppTextStyle.Footnote,
                color = palette.warn,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun CountsCard(counts: ContentCounts, onOpenManage: () -> Unit) {
    val tokens = LocalAppTokens.current
    // 整张卡可点，不做"每格跳到各自的表"：管理页只有供应商一张表，
    // 四格分别跳会是假的路径指示。
    AppCard(modifier = cardModifier(), onClick = onOpenManage) {
        CardTitle(stringResource(Res.string.dashboard_counts_title))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatTile(value = counts.providers.toString(), label = stringResource(Res.string.count_providers))
            StatTile(value = counts.keys.toString(), label = stringResource(Res.string.count_keys))
            StatTile(value = counts.models.toString(), label = stringResource(Res.string.count_models))
            StatTile(value = counts.accounts.toString(), label = stringResource(Res.string.count_accounts))
        }
    }
}

@Composable
internal fun HealthCard(health: HealthBreakdown) {
    val tokens = LocalAppTokens.current
    AppCard(modifier = cardModifier()) {
        CardTitle(stringResource(Res.string.dashboard_health_title))
        if (health.total == 0) {
            AppText(
                text = stringResource(Res.string.dashboard_health_empty),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(vertical = tokens.itemSpacing),
            )
            return@AppCard
        }
        SegmentedBar(
            segments = listOf(
                BarSegment(health.ok, colorOf(UiHealth.Ok)),
                BarSegment(health.warn, colorOf(UiHealth.Warn)),
                BarSegment(health.error, colorOf(UiHealth.Error)),
                BarSegment(health.unknown, colorOf(UiHealth.Unknown)),
            ),
            modifier = Modifier.padding(vertical = tokens.itemSpacing),
        )
        if (health.allOk) {
            // 全绿时不必列四行图例，一句话更清楚
            StatusDot(
                color = colorOf(UiHealth.Ok),
                label = pluralStringResource(Res.plurals.dashboard_health_all_ok, health.total, health.total),
            )
            return@AppCard
        }
        listOf(
            UiHealth.Ok to health.ok,
            UiHealth.Warn to health.warn,
            UiHealth.Error to health.error,
            UiHealth.Unknown to health.unknown,
        ).filter { it.second > 0 }.forEach { (state, count) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(
                    color = colorOf(state),
                    label = labelOf(state),
                    modifier = Modifier.weight(1f),
                )
                AppText(text = count.toString(), style = AppTextStyle.Secondary)
            }
        }
    }
}

@Composable
internal fun AttentionCard(items: List<AttentionItem>, onOpenProvider: (Long) -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(modifier = cardModifier()) {
        CardTitle(stringResource(Res.string.dashboard_attention_title))
        if (items.isEmpty()) {
            StatusDot(
                color = colorOf(UiHealth.Ok),
                label = stringResource(Res.string.dashboard_attention_none),
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
            return@AppCard
        }
        items.forEachIndexed { index, item ->
            if (index > 0) AppDivider(modifier = Modifier.padding(vertical = tokens.itemSpacing))
            Column(modifier = Modifier.padding(top = tokens.itemSpacing)) {
                StatusDot(color = colorOf(item.health), label = item.providerName)
                AppText(
                    text = messageOf(item.kind),
                    style = AppTextStyle.Secondary,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)) {
                    // 只有“被客户端校验拦下”这一档有一键修法，其余三档点整行进详情页
                    if (item.kind == AttentionKind.ClientBlocked) {
                        AppTextButton(
                            text = stringResource(Res.string.dashboard_attention_fix_client),
                            onClick = { onOpenProvider(item.providerId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProbeCard(
    state: DashboardUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val progress = state.progress
    AppCard(modifier = cardModifier()) {
        CardTitle(stringResource(Res.string.dashboard_probe_title))
        if (progress != null) {
            AppText(
                text = stringResource(
                    Res.string.dashboard_probe_running,
                    progress.done,
                    progress.total,
                    progress.currentLabel,
                ),
                style = AppTextStyle.Secondary,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
            AppLinearProgress(
                progress = progress.fraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = tokens.itemSpacing),
            )
            // §7.4：探测进行中要暂停前台空闲锁定，而这件事必须让用户看见，
            // 否则"为什么它没锁"会被当成 bug。
            AppText(
                text = stringResource(Res.string.dashboard_probe_no_autolock),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
            AppTextButton(
                text = stringResource(Res.string.dashboard_probe_cancel),
                onClick = onCancel,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
            return@AppCard
        }
        val lastRun = state.lastRun
        if (lastRun == null) {
            AppText(
                text = stringResource(Res.string.dashboard_probe_never),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(vertical = tokens.itemSpacing),
            )
        } else {
            // 相对时间与耗时在页面层现算（红线 19：ViewModel 给时间戳，不拼文案）。
            AppText(
                text = stringResource(
                    Res.string.dashboard_probe_finished,
                    relativeLabel(state.nowMs, lastRun.finishedAtMs),
                    stringResource(
                        Res.string.time_duration_seconds,
                        durationSeconds(lastRun.durationMs),
                    ),
                ),
                style = AppTextStyle.Secondary,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
            AppText(
                text = stringResource(
                    Res.string.dashboard_probe_counts,
                    lastRun.total,
                    lastRun.succeeded,
                    lastRun.failed,
                    lastRun.skipped,
                ),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(top = 2.dp, bottom = tokens.itemSpacing),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)) {
            AppTextButton(text = stringResource(Res.string.dashboard_probe_start), onClick = onStart)
            if (lastRun != null) {
                AppTextButton(
                    text = stringResource(Res.string.dashboard_probe_detail),
                    onClick = onOpenDetail,
                )
            }
        }
    }
}

@Composable
internal fun BackupCard(backup: BackupStatus, onOpenSync: () -> Unit) {
    val tokens = LocalAppTokens.current
    val palette = LocalStatusPalette.current
    AppCard(modifier = cardModifier()) {
        CardTitle(stringResource(Res.string.dashboard_backup_title))
        val lastBackupAgo = backup.lastBackupAgo
        if (lastBackupAgo == null) {
            AppText(
                text = stringResource(Res.string.dashboard_backup_never),
                style = AppTextStyle.Secondary,
                color = palette.warn,
                modifier = Modifier.padding(vertical = tokens.itemSpacing),
            )
        } else {
            AppText(
                text = stringResource(
                    Res.string.dashboard_backup_last,
                    lastBackupAgo,
                    backup.targetLabel ?: "",
                ),
                style = AppTextStyle.Secondary,
                modifier = Modifier.padding(vertical = tokens.itemSpacing),
            )
        }
        AppTextButton(text = stringResource(Res.string.dashboard_backup_now), onClick = onOpenSync)
    }
}
