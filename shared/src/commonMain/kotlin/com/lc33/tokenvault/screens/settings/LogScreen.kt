package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.dialog_cancel
import tokenvault.shared.generated.resources.log_auto_scroll
import tokenvault.shared.generated.resources.log_auto_scroll_summary
import tokenvault.shared.generated.resources.log_category_account
import tokenvault.shared.generated.resources.log_category_backup
import tokenvault.shared.generated.resources.log_category_balance
import tokenvault.shared.generated.resources.log_category_catalog
import tokenvault.shared.generated.resources.log_category_http
import tokenvault.shared.generated.resources.log_category_lock
import tokenvault.shared.generated.resources.log_category_probe
import tokenvault.shared.generated.resources.log_category_vault
import tokenvault.shared.generated.resources.log_clear
import tokenvault.shared.generated.resources.log_clear_confirm_body
import tokenvault.shared.generated.resources.log_clear_confirm_title
import tokenvault.shared.generated.resources.log_empty
import tokenvault.shared.generated.resources.log_empty_title
import tokenvault.shared.generated.resources.log_filter_summary
import tokenvault.shared.generated.resources.log_filter_title
import tokenvault.shared.generated.resources.log_level_debug
import tokenvault.shared.generated.resources.log_level_error
import tokenvault.shared.generated.resources.log_level_info
import tokenvault.shared.generated.resources.log_level_warn
import tokenvault.shared.generated.resources.log_retention
import tokenvault.shared.generated.resources.log_retention_options
import tokenvault.shared.generated.resources.log_retention_summary
import tokenvault.shared.generated.resources.log_title
import com.lc33.tokenvault.domain.model.AuditEntry
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.LogRetention
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette
import com.lc33.tokenvault.ui.theme.StatusPalette

/** 日志页：等级筛选、自动滚动、保留期和清理。 */
@Composable
fun LogScreen(
    entries: List<AuditEntry>,
    levelFilter: LogLevel,
    retention: LogRetention,
    onLevelFilterChange: (LogLevel) -> Unit,
    onRetentionChange: (LogRetention) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val scrollState = rememberAppTopBarScrollState()
    val listState = rememberLazyListState()
    // 相对时间的基准必须自己走：只读一次的话，页面打开之后写进来的日志会落进
    // `relativeBucketOf` 的"未来"那一档（超过一分钟就判未来），而"刚刚"正是日志页
    // 最常见的状态。半分钟一跳，够把"刚刚 → 1 分钟前"跟住，也不至于每秒重组列表。
    var nowMs by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MILLIS)
            nowMs = nowMillis()
        }
    }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(entries, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.log_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = onBack,
                    )
                },
                actions = {
                    AppIconButton(
                        icon = AppIcon.Delete,
                        contentDescription = stringResource(Res.string.log_clear),
                        onClick = { confirmClear = true },
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SectionTitle(
                text = stringResource(Res.string.log_filter_title),
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
            AppText(
                text = stringResource(Res.string.log_filter_summary),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(horizontal = tokens.screenPadding),
            )
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = tokens.itemSpacing),
                contentPadding = PaddingValues(horizontal = tokens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                items(LogLevel.entries.size) { index ->
                    val level = LogLevel.entries[index]
                    AppFilterChip(
                        text = stringResource(levelLabelRes(level)),
                        selected = level == levelFilter,
                        onClick = { onLevelFilterChange(level) },
                    )
                }
            }

            AppPreferenceGroup(modifier = Modifier.padding(horizontal = tokens.screenPadding)) {
                // 下拉按 ordinal 取选项：`log_retention_options` 的顺序必须与
                // `LogRetention.entries` 一致（7/30/90/永久），否则选中的档位会串位。
                AppDropdownRow(
                    title = stringResource(Res.string.log_retention),
                    summary = stringResource(Res.string.log_retention_summary),
                    items = stringArrayResource(Res.array.log_retention_options).toList(),
                    selectedIndex = retention.ordinal,
                    onSelect = { onRetentionChange(LogRetention.entries[it]) },
                )
                AppSwitchRow(
                    title = stringResource(Res.string.log_auto_scroll),
                    summary = stringResource(Res.string.log_auto_scroll_summary),
                    checked = autoScroll,
                    onCheckedChange = { autoScroll = it },
                )
            }

            if (entries.isEmpty()) {
                EmptyState(
                    title = stringResource(Res.string.log_empty_title),
                    description = stringResource(Res.string.log_empty),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    items(
                        count = entries.size,
                        key = { entries[it].id },
                    ) { index ->
                        LogRow(entry = entries[index], nowMs = nowMs)
                    }
                }
            }
        }
    }

    AppDialog(
        show = confirmClear,
        onDismissRequest = { confirmClear = false },
        title = stringResource(Res.string.log_clear_confirm_title),
        summary = stringResource(Res.string.log_clear_confirm_body),
        confirmText = stringResource(Res.string.log_clear),
        // 破坏性动作给一个看得见的退路，而不是让用户去猜"点空白处关掉"。
        dismissText = stringResource(Res.string.dialog_cancel),
        onConfirm = {
            confirmClear = false
            onClear()
        },
    )
}

@Composable
private fun LogRow(entry: AuditEntry, nowMs: Long) {
    val palette = LocalStatusPalette.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(
                color = levelColor(entry.level, palette),
                label = stringResource(levelLabelRes(entry.level)),
                modifier = Modifier.weight(1f, fill = false),
            )
            AppText(
                text = stringResource(categoryLabelRes(entry.category)),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
            AppText(
                text = relativeLabel(nowMs, entry.at),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
        }
        AppText(
            text = entry.message,
            style = AppTextStyle.Body,
            modifier = Modifier.padding(top = 6.dp),
        )
        entry.detail?.let { detail ->
            AppText(
                text = detail,
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

private const val CLOCK_TICK_MILLIS = 30_000L

private fun levelColor(level: LogLevel, palette: StatusPalette): Color = when (level) {
    LogLevel.DEBUG -> palette.neutral
    LogLevel.INFO -> palette.neutral
    LogLevel.WARN -> palette.warn
    LogLevel.ERROR -> palette.error
}

private fun levelLabelRes(level: LogLevel): StringResource = when (level) {
    LogLevel.DEBUG -> Res.string.log_level_debug
    LogLevel.INFO -> Res.string.log_level_info
    LogLevel.WARN -> Res.string.log_level_warn
    LogLevel.ERROR -> Res.string.log_level_error
}

private fun categoryLabelRes(category: LogCategory): StringResource = when (category) {
    LogCategory.LOCK -> Res.string.log_category_lock
    LogCategory.VAULT -> Res.string.log_category_vault
    LogCategory.PROBE -> Res.string.log_category_probe
    LogCategory.BALANCE -> Res.string.log_category_balance
    LogCategory.CATALOG -> Res.string.log_category_catalog
    LogCategory.BACKUP -> Res.string.log_category_backup
    LogCategory.HTTP -> Res.string.log_category_http
    LogCategory.ACCOUNT -> Res.string.log_category_account
}
