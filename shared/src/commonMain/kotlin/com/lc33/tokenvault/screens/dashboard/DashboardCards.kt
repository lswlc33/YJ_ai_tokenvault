package com.lc33.tokenvault.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.count_keys
import tokenvault.shared.generated.resources.count_models
import tokenvault.shared.generated.resources.count_providers
import tokenvault.shared.generated.resources.dashboard_balance_detail
import tokenvault.shared.generated.resources.dashboard_balance_failed
import tokenvault.shared.generated.resources.dashboard_balance_no_fx
import tokenvault.shared.generated.resources.dashboard_balance_none
import tokenvault.shared.generated.resources.dashboard_balance_title
import tokenvault.shared.generated.resources.dashboard_balance_updated
import tokenvault.shared.generated.resources.dashboard_counts_title
import tokenvault.shared.generated.resources.dashboard_probe_counts
import tokenvault.shared.generated.resources.dashboard_probe_key_label
import tokenvault.shared.generated.resources.dashboard_probe_last_updated
import tokenvault.shared.generated.resources.dashboard_probe_provider_label
import tokenvault.shared.generated.resources.dashboard_probe_detail
import tokenvault.shared.generated.resources.dashboard_probe_never
import tokenvault.shared.generated.resources.dashboard_probe_no_autolock
import tokenvault.shared.generated.resources.dashboard_probe_running
import tokenvault.shared.generated.resources.dashboard_probe_title
import tokenvault.shared.generated.resources.refresh_cd
import com.lc33.tokenvault.screens.model.BalanceSummary
import com.lc33.tokenvault.screens.model.ContentCounts
import com.lc33.tokenvault.screens.model.DashboardUiState
import com.lc33.tokenvault.ui.common.StatTile
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppAccentCard
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appOnPrimaryColor
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens

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
    /** 跳到余额明细页（`BalanceBreakdownRoute`）。 */
    onOpenDetail: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    // 实色主色底。曾经试过玻璃材质（背景模糊 + vibrancy + 动态光斑）来"彰显高级感"，
    // 结果是显示异常，已按用户要求回退——这条路上还有一个坑：被采样的背景层若包含
    // 卡片自己，渲染树每帧加深，几秒后 RenderThread 栈溢出闪退。
    AppAccentCard(modifier = cardModifier()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = stringResource(Res.string.dashboard_balance_title),
                    style = AppTextStyle.Subtitle,
                    color = appOnPrimaryColor,
                )
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
                        color = appOnPrimaryColor,
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
                color = appOnPrimaryColor,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
            return@AppAccentCard
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
            color = appOnPrimaryColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        if (balance.failedProviderCount > 0) {
            AppText(
                text = stringResource(Res.string.dashboard_balance_failed, balance.failedProviderCount),
                style = AppTextStyle.Footnote,
                color = appOnPrimaryColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    // 「查看明细」画在主题色卡**外面**：整张 accent 卡看着像一个整体，把行入口塞进去
    // 会读成"点卡的任何一处都进明细"。什么都没查到时明细页是空的，入口也就不出现。
    if (balance.perCurrency.isNotEmpty() || balance.failedProviderCount > 0) {
        AppPreferenceGroup(modifier = cardModifier(), inset = false) {
            AppActionRow(
                text = stringResource(Res.string.dashboard_balance_detail),
                onClick = onOpenDetail,
                modifier = Modifier.fillMaxWidth(),
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
        }
    }
}

/**
 * 探测区。**拆成多张卡**：状态卡（上一轮 / 进行中）与两张进度卡（供应商 / 密钥）。
 *
 * 以前三块挤在一张卡里，标签、计数、细进度条一行接一行，读起来分不清哪条属于谁；
 * 拆开之后每张卡只讲一件事，各自有完整的卡片内边距。
 *
 * **这一区没有动作入口**：开始 / 取消探测都由顶栏刷新承担（探测要花钱，入口收敛到
 * 一处反而更明确），这里只讲进度与上一轮结果。
 */
@Composable
internal fun ProbeCard(
    state: DashboardUiState,
    onOpenDetail: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val progress = state.progress
    val lastRun = state.lastRun
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        AppCard(modifier = cardModifier()) {
            CardTitle(stringResource(Res.string.dashboard_probe_title))
            when {
                progress != null -> {
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
                    // §7.4：探测进行中要暂停前台空闲锁定，而这件事必须让用户看见，
                    // 否则"为什么它没锁"会被当成 bug。
                    AppText(
                        text = stringResource(Res.string.dashboard_probe_no_autolock),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                }
                lastRun == null -> {
                    AppText(
                        text = stringResource(Res.string.dashboard_probe_never),
                        style = AppTextStyle.Secondary,
                        color = appSecondaryTextColor,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                }
                else -> {
                    AppText(
                        text = stringResource(
                            Res.string.dashboard_probe_last_updated,
                            relativeLabel(state.nowMs, lastRun.finishedAtMs),
                        ),
                        style = AppTextStyle.Secondary,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                }
            }
        }

        // 从未探测过时不铺两张全 0 的进度卡——那只是噪音，用户还没有可以看的结果。
        val providerData = progress?.let {
            ProbeMetric(it.providerFraction, it.providerTotal, it.providerSucceeded, it.providerFailed, (it.providerTotal - it.providerDone).coerceAtLeast(0))
        } ?: lastRun?.let {
            ProbeMetric(
                fraction = if (it.providerTotal <= 0) 0f else it.providerSucceeded.toFloat() / it.providerTotal.toFloat(),
                total = it.providerTotal,
                succeeded = it.providerSucceeded,
                failed = it.providerFailed,
                skipped = it.providerSkipped,
            )
        }
        val keyData = progress?.let {
            ProbeMetric(it.keyFraction, it.keyTotal, it.keySucceeded, it.keyFailed, (it.keyTotal - it.keyDone).coerceAtLeast(0))
        } ?: lastRun?.let {
            ProbeMetric(
                fraction = if (it.keyTotal <= 0) 0f else it.keySucceeded.toFloat() / it.keyTotal.toFloat(),
                total = it.keyTotal,
                succeeded = it.keySucceeded,
                failed = it.keyFailed,
                skipped = it.keySkipped,
            )
        }
        providerData?.let {
            ProbeMetricCard(
                title = stringResource(Res.string.dashboard_probe_provider_label),
                metric = it,
            )
        }
        keyData?.let {
            ProbeMetricCard(
                title = stringResource(Res.string.dashboard_probe_key_label),
                metric = it,
            )
        }
        // 「查看明细」放探测区最下面：它是回看上一轮结果，摆在进度卡之前会把
        // "看进度"和"看结果"两件事的先后顺序颠倒。从未探测过时没有明细分页可看。
        if (lastRun != null && progress == null) {
            // 用 AppPreferenceGroup 而不是 AppCard：卡片默认还叠一层 16dp 内边距，
            // 56dp 的行会被撑到 88dp，明显比其它块胖（见 AppPreferenceGroup 注释）。
            AppPreferenceGroup(modifier = cardModifier(), inset = false) {
                AppActionRow(
                    text = stringResource(Res.string.dashboard_probe_detail),
                    onClick = onOpenDetail,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 一张进度卡的数据。拆出来是为了让"进行中"与"上一轮结果"两条路径共用同一个渲染。 */
private data class ProbeMetric(
    val fraction: Float,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
)

@Composable
private fun ProbeMetricCard(title: String, metric: ProbeMetric) {
    val tokens = LocalAppTokens.current
    AppCard(modifier = cardModifier()) {
        CardTitle(title)
        AppText(
            text = stringResource(
                Res.string.dashboard_probe_counts,
                metric.total,
                metric.succeeded,
                metric.failed,
                metric.skipped,
            ),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = 2.dp),
        )
        AppLinearProgress(
            progress = metric.fraction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}
