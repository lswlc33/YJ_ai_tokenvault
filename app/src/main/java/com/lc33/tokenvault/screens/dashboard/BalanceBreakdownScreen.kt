package com.lc33.tokenvault.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
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
import com.lc33.tokenvault.screens.model.UiProviderRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 余额明细（计划.md §13.4，`BalanceBreakdownRoute`）。
 *
 * 仪表盘那张余额卡只给按币种的合计，这一页给"这笔钱分别在谁那里"。
 *
 * **按币种分组，组内不求跨币种的和**（§9.3）：不做汇率换算，所以任何"总计"都只能
 * 在同一个币种内出现。查询失败的行单独一组，且与"余额为 0"必须可区分——
 * 前者是"不知道"，后者是"知道且是 0"。
 */
@Composable
fun BalanceBreakdownScreen(
    providers: List<UiProviderRow>,
    failedProviders: List<UiProviderRow>,
    onBack: () -> Unit,
    onOpenProvider: (Long) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val byCurrency = providers
        .filter { it.balance != null }
        .groupBy { it.balance!!.currency }
        .toSortedMap()

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.dashboard_balance_title),
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
            byCurrency.forEach { (currency, rows) ->
                item { SectionTitle(text = currency) }
                items(rows.size) { index -> BalanceRow(rows[index], onOpenProvider) }
            }
            item {
                AppText(
                    text = stringResource(R.string.dashboard_balance_no_fx),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                )
            }
            if (failedProviders.isNotEmpty()) {
                item { SectionTitle(text = stringResource(R.string.balance_failed_section)) }
                items(failedProviders.size) { index ->
                    FailedRow(failedProviders[index], onOpenProvider)
                }
            }
            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
}

@Composable
private fun BalanceRow(row: UiProviderRow, onOpen: (Long) -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
        onClick = { onOpen(row.id) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(
                text = row.name,
                style = AppTextStyle.Body,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            AppText(text = row.balance?.amount ?: "", style = AppTextStyle.Body)
        }
        AppText(
            text = row.host,
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            fontFamily = tokens.monoFontFamily,
            maxLines = 1,
        )
    }
}

@Composable
private fun FailedRow(row: UiProviderRow, onOpen: (Long) -> Unit) {
    val tokens = LocalAppTokens.current
    val palette = LocalStatusPalette.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding),
        onClick = { onOpen(row.id) },
    ) {
        AppText(text = row.name, style = AppTextStyle.Body, maxLines = 1)
        // "查不到"与"是 0"必须能分开（§9.3）
        AppText(
            text = stringResource(R.string.balance_failed_hint),
            style = AppTextStyle.Footnote,
            color = palette.warn,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
