package com.lc33.tokenvault.screens.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.usage_report_chart_empty
import tokenvault.shared.generated.resources.usage_report_consumed
import tokenvault.shared.generated.resources.usage_report_metric_balance
import tokenvault.shared.generated.resources.usage_report_metric_usage
import tokenvault.shared.generated.resources.usage_report_net_change
import tokenvault.shared.generated.resources.usage_report_range_30
import tokenvault.shared.generated.resources.usage_report_range_7
import tokenvault.shared.generated.resources.usage_report_range_90
import tokenvault.shared.generated.resources.usage_report_title
import tokenvault.shared.generated.resources.usage_report_unknown_provider
import com.lc33.tokenvault.balance.ChartPoint
import com.lc33.tokenvault.balance.FormatMoney
import com.lc33.tokenvault.platform.absoluteDateLabel
import com.lc33.tokenvault.screens.model.ReportMetric
import com.lc33.tokenvault.screens.model.ReportRange
import com.lc33.tokenvault.screens.model.UsageReportProviderSeries
import com.lc33.tokenvault.screens.model.UsageReportUiState
import com.lc33.tokenvault.ui.common.LineChart
import com.lc33.tokenvault.ui.common.LineChartSeries
import com.lc33.tokenvault.ui.common.LoadingState
import com.lc33.tokenvault.ui.common.MiniSparkline
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDivider
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTabRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalProviderPalette
import kotlin.math.abs

/**
 * 用量变化报告：余额增长与消耗趋势，逐供应商折线。**只读**，从仪表盘/设置点击进入。
 *
 * 图**按币种分卡**：同一坐标轴不混 ¥ 与 $（相加或共用刻度都是错的）。多数用户只有一种
 * 币种，就一张图；有多种时各币种一张，各自的 Y 轴带自己的符号。每张图下面是该币种里
 * 每家供应商的一行：色点（与线同色）+ 名字 + 区间净变化/消耗 + 迷你折线，图例与明细合一。
 *
 * 页面只画不算：LOCF、分桶、按币种求和都在 `UsageReportAggregator`（纯函数）里做完了。
 */
@Composable
fun UsageReportScreen(
    state: UsageReportUiState,
    onBack: () -> Unit,
    onSelectMetric: (ReportMetric) -> Unit,
    onSelectRange: (ReportRange) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.usage_report_title),
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
        if (state.loading && state.series.isEmpty()) {
            LoadingState(modifier = Modifier.padding(padding))
            return@AppScaffold
        }

        val usage = state.metric == ReportMetric.USAGE
        // 按币种分组，组内保持聚合器给的（按余额降序）次序。
        val groups: List<Pair<String, List<UsageReportProviderSeries>>> =
            state.series.groupBy { it.currency }.toList()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            item { Spacer(modifier = Modifier.height(tokens.itemSpacing)) }

            item {
                MetricTabs(
                    metric = state.metric,
                    onSelect = onSelectMetric,
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                )
            }
            item {
                RangeChips(
                    range = state.range,
                    onSelect = onSelectRange,
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                )
            }

            if (!state.hasAnyData) {
                // 还没攒到历史：仍然画一张空态卡（controls 保持可见），而不是整屏白。
                item {
                    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.screenPadding)) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppText(
                                text = stringResource(Res.string.usage_report_chart_empty),
                                style = AppTextStyle.Secondary,
                                color = appSecondaryTextColor,
                            )
                        }
                    }
                }
            } else {
                groups.forEach { (currency, seriesInGroup) ->
                    item(key = currency) {
                        CurrencyGroupCard(
                            currency = currency,
                            seriesInGroup = seriesInGroup,
                            usage = usage,
                            showCurrencyTitle = groups.size > 1,
                            modifier = Modifier.padding(horizontal = tokens.screenPadding),
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
}

@Composable
private fun MetricTabs(
    metric: ReportMetric,
    onSelect: (ReportMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        stringResource(Res.string.usage_report_metric_balance),
        stringResource(Res.string.usage_report_metric_usage),
    )
    AppTabRow(
        tabs = tabs,
        selectedIndex = metric.ordinal,
        onSelect = { index -> onSelect(ReportMetric.entries[index]) },
        modifier = modifier,
    )
}

@Composable
private fun RangeChips(
    range: ReportRange,
    onSelect: (ReportRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf(
        stringResource(Res.string.usage_report_range_7),
        stringResource(Res.string.usage_report_range_30),
        stringResource(Res.string.usage_report_range_90),
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReportRange.entries.forEachIndexed { index, entry ->
            AppFilterChip(
                text = labels[index],
                selected = range == entry,
                onClick = { onSelect(entry) },
            )
        }
    }
}

@Composable
private fun CurrencyGroupCard(
    currency: String,
    seriesInGroup: List<UsageReportProviderSeries>,
    usage: Boolean,
    showCurrencyTitle: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val palette = LocalProviderPalette.current
    val unknownName = stringResource(Res.string.usage_report_unknown_provider)

    AppCard(modifier = modifier.fillMaxWidth()) {
        if (showCurrencyTitle) {
            AppText(text = currency, style = AppTextStyle.Subtitle)
        }

        val chartSeries = seriesInGroup.map { s ->
            LineChartSeries(
                label = s.providerName.ifEmpty { unknownName },
                color = palette.swatchFor(s.colorIndex),
                points = if (usage) s.usagePoints else s.balancePoints,
            )
        }
        LineChart(
            series = chartSeries,
            xLabelOf = { millis -> absoluteDateLabel(millis) },
            // Y 轴带币种符号：这张卡整卡都是同一币种，符号不会误导。
            yLabelOf = { value -> FormatMoney.format(value, currency) },
            emptyText = stringResource(Res.string.usage_report_chart_empty),
            modifier = Modifier.padding(top = if (showCurrencyTitle) tokens.itemSpacing else 0.dp),
        )

        seriesInGroup.forEach { s ->
            AppDivider(modifier = Modifier.padding(vertical = tokens.itemSpacing))
            ProviderLegendRow(
                series = s,
                usage = usage,
                unknownName = unknownName,
            )
        }
    }
}

@Composable
private fun ProviderLegendRow(
    series: UsageReportProviderSeries,
    usage: Boolean,
    unknownName: String,
) {
    val palette = LocalProviderPalette.current
    val color = palette.swatchFor(series.colorIndex)
    val label = if (usage) {
        stringResource(Res.string.usage_report_consumed)
    } else {
        stringResource(Res.string.usage_report_net_change)
    }
    val value = if (usage) {
        FormatMoney.format(series.totalConsumed, series.currency)
    } else {
        signedMoney(series.netBalanceChange, series.currency)
    }
    val points: List<ChartPoint> = if (usage) series.usagePoints else series.balancePoints

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                text = series.providerName.ifEmpty { unknownName },
                style = AppTextStyle.Body,
                maxLines = 1,
            )
            AppText(
                text = "$label $value",
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                maxLines = 1,
            )
        }
        MiniSparkline(
            points = points,
            color = color,
            modifier = Modifier.width(72.dp),
        )
    }
}

/** 净变化带正负号：`+¥50.00` / `-¥8.00`，比裸的 `¥-8.00` 一眼分得清涨跌。 */
private fun signedMoney(amount: Double, currency: String): String {
    val prefix = when {
        amount > 0 -> "+"
        amount < 0 -> "-"
        else -> ""
    }
    return prefix + FormatMoney.format(abs(amount), currency)
}
