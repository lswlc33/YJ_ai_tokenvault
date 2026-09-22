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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.balance_trend_unknown_provider
import tokenvault.shared.generated.resources.model_change_added
import tokenvault.shared.generated.resources.model_change_counts
import tokenvault.shared.generated.resources.model_change_empty_none
import tokenvault.shared.generated.resources.model_change_empty_window
import tokenvault.shared.generated.resources.model_change_footnote
import tokenvault.shared.generated.resources.model_change_removed
import tokenvault.shared.generated.resources.model_change_show_more
import tokenvault.shared.generated.resources.model_change_title
import com.lc33.tokenvault.catalog.ModelChangeEntry
import com.lc33.tokenvault.screens.model.ModelChangeUiState
import com.lc33.tokenvault.screens.model.ProviderModelChangeRow
import com.lc33.tokenvault.screens.model.TrendRange
import com.lc33.tokenvault.ui.common.LoadingState
import com.lc33.tokenvault.ui.common.relativeLabelWithinDay
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDivider
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalProviderPalette

/**
 * 一段（新增或下架）一次摆多少行，剩下的要点一下才继续铺。
 *
 * 与整屏模型页那组 `MODEL_GROUP_ROW_PAGE = 24` 同一个道理：一张 Key 首次抓取就能记几百条
 * 流水，全铺在一屏里既画不动也读不动。数值两边各写一份而不共用，是因为这一页每行只有一句
 * 名字加一个日期，比模型页那种带 chip 的行更密。
 */
private const val SECTION_ROW_LIMIT = 24

/**
 * 模型变化页：哪几家站点上了哪些模型、又下了哪些。**只读**，与余额趋势并列，从仪表盘/设置进入。
 *
 * 每家站点一张卡：头部是那家的身份色 + 名字 + "新增 N · 下架 M"，下面两段各列模型 id 与
 * 发生的日期。同一个模型只会出现在其中一段（末次事件定性 + 与 `models` 当前态对账），
 * 所以上面两个数相加就是这张卡一共列了多少行。
 *
 * 页面只画不算：窗口、去重、对账都在 `catalog/ModelChangeSummary`（纯函数）里做完了。
 */
@Composable
fun ModelChangeScreen(
    state: ModelChangeUiState,
    onBack: () -> Unit,
    onSelectRange: (TrendRange) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.model_change_title),
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
        if (state.loading && state.rows.isEmpty()) {
            LoadingState(modifier = Modifier.padding(padding))
            return@AppScaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            item { Spacer(modifier = Modifier.height(tokens.itemSpacing)) }

            item {
                TrendRangeChips(
                    range = state.range,
                    onSelect = onSelectRange,
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                )
            }

            if (state.rows.isEmpty()) {
                // 空态分两句话：从没记过与这段时间没有变化，是两件不同的事。把前者说成
                // "没有变化"是在断言一家站点什么都没干，而真相是这一页还没拿到过数据。
                item {
                    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.screenPadding)) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppText(
                                text = stringResource(
                                    if (state.hasAnyEvent) {
                                        Res.string.model_change_empty_window
                                    } else {
                                        Res.string.model_change_empty_none
                                    },
                                ),
                                style = AppTextStyle.Secondary,
                                color = appSecondaryTextColor,
                            )
                        }
                    }
                }
            } else {
                state.rows.forEach { row ->
                    item(key = row.providerId) {
                        ProviderChangeCard(
                            row = row,
                            nowMs = state.nowMs,
                            modifier = Modifier.padding(horizontal = tokens.screenPadding),
                        )
                    }
                }
            }

            item {
                AppText(
                    text = stringResource(Res.string.model_change_footnote),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                )
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
}

@Composable
private fun ProviderChangeCard(
    row: ProviderModelChangeRow,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val palette = LocalProviderPalette.current
    val color = palette.colorFor(row.providerId, row.colorIndex)
    // 两段各自要不要继续铺，是这一页的浏览状态，不进 ViewModel。
    var showAllAdded by remember(row.providerId) { mutableStateOf(false) }
    var showAllRemoved by remember(row.providerId) { mutableStateOf(false) }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = row.providerName.ifEmpty { stringResource(Res.string.balance_trend_unknown_provider) },
                    style = AppTextStyle.Subtitle,
                    maxLines = 1,
                )
                AppText(
                    text = stringResource(Res.string.model_change_counts, row.added.size, row.removed.size),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    maxLines = 1,
                )
            }
        }

        if (row.added.isNotEmpty()) {
            AppDivider(modifier = Modifier.padding(vertical = tokens.itemSpacing))
            ChangeSection(
                label = stringResource(Res.string.model_change_added),
                entries = row.added,
                nowMs = nowMs,
                expanded = showAllAdded,
                onExpand = { showAllAdded = true },
            )
        }
        if (row.removed.isNotEmpty()) {
            AppDivider(modifier = Modifier.padding(vertical = tokens.itemSpacing))
            ChangeSection(
                label = stringResource(Res.string.model_change_removed),
                entries = row.removed,
                nowMs = nowMs,
                expanded = showAllRemoved,
                onExpand = { showAllRemoved = true },
            )
        }
    }
}

/** 一段变化：小标题 + 至多 [SECTION_ROW_LIMIT] 行，剩下的一起点开。 */
@Composable
private fun ChangeSection(
    label: String,
    entries: List<ModelChangeEntry>,
    nowMs: Long,
    expanded: Boolean,
    onExpand: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val hidden = if (expanded) 0 else (entries.size - SECTION_ROW_LIMIT).coerceAtLeast(0)
    val shown = if (expanded) entries else entries.take(SECTION_ROW_LIMIT)

    AppText(
        text = label,
        style = AppTextStyle.Body,
        color = appSecondaryTextColor,
    )
    shown.forEachIndexed { index, entry ->
        if (index > 0) {
            Spacer(modifier = Modifier.height(tokens.itemSpacing / 2))
        }
        ChangeRow(entry = entry, nowMs = nowMs)
    }
    if (hidden > 0) {
        AppActionRow(
            text = stringResource(Res.string.model_change_show_more, hidden),
            onClick = onExpand,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing / 2),
        )
    }
}

/** 一行变化：模型 id 靠左、发生日期靠右。日期跨天就给完整日期，"3 天前"在这里不如有用。 */
@Composable
private fun ChangeRow(entry: ModelChangeEntry, nowMs: Long) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(
            text = entry.modelId,
            style = AppTextStyle.Body,
            // 模型 id 一律等宽，与整屏模型页同一条约定（长 id 中间的 1/l、0/O 要靠字形分）。
            fontFamily = tokens.monoFontFamily,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        AppText(
            text = relativeLabelWithinDay(nowMs, entry.at),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            maxLines = 1,
            // 只给下限不给定宽：本地化 MEDIUM 日期（中文是"2026年9月21日"）比 96dp 宽，
            // 定宽会把它自己截成"2026年9月2…"；有了下限，短的日期仍然右贴成一条竖线。
            modifier = Modifier.widthIn(min = 96.dp),
            textAlign = TextAlign.End,
        )
    }
}
