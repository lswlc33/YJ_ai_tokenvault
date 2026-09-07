package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.R
import com.lc33.tokenvault.domain.model.AuditEntry
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette
import com.lc33.tokenvault.ui.theme.StatusPalette

/**
 * 日志页（计划.md §13.4，`LogRoute`）。
 *
 * 只读：日志由探测 / 余额 / 备份引擎在别处写入，这一页只看。「清空」在数据页里做
 * （那里才有二次确认），这里不提供清空按钮——避免用户在两处都能触发同一个危险操作。
 *
 * 每一条日志用 [StatusDot] 表达级别（红线 17：颜色 + 文字，不能只靠色点），
 * 分类做副文案，`detail` 只在有内容时画（它已经过 Redactor 脱敏，红线 32）。
 */
@Composable
fun LogScreen(
    entries: List<AuditEntry>,
    onBack: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    // 相对时间的"现在"。页面组合时取一次快照即可；日志页不强调秒级精度。
    val nowMs = remember { System.currentTimeMillis() }

    SettingsSubPage(titleRes = R.string.log_title, onBack = onBack) {
        if (entries.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.log_title),
                    description = stringResource(R.string.log_empty),
                )
            }
            return@SettingsSubPage
        }
        items(
            count = entries.size,
            key = { entries[it].id },
        ) { index ->
            LogRow(
                entry = entries[index],
                nowMs = nowMs,
                modifier = Modifier.padding(horizontal = tokens.screenPadding),
            )
        }
    }
}

@Composable
private fun LogRow(
    entry: AuditEntry,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStatusPalette.current
    AppCard(modifier = modifier.fillMaxWidth()) {
        // 第一行：级别（色点 + 文字）+ 分类 + 相对时间
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
        // 第二行：正文。可能换行，不限制行数。
        AppText(
            text = entry.message,
            style = AppTextStyle.Body,
            modifier = Modifier.padding(top = 6.dp),
        )
        val detail = entry.detail
        if (detail != null) {
            AppText(
                text = detail,
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** 级别 → 颜色。DEBUG/INFO 归到 neutral，WARN 用 warn，ERROR 用 error（红线 17）。 */
private fun levelColor(level: LogLevel, palette: StatusPalette): Color =
    when (level) {
        LogLevel.DEBUG -> palette.neutral
        LogLevel.INFO -> palette.neutral
        LogLevel.WARN -> palette.warn
        LogLevel.ERROR -> palette.error
    }

/** 级别 → 文案资源 id。文案走资源（红线 19），不在这里留字面量。 */
private fun levelLabelRes(level: LogLevel): Int = when (level) {
    LogLevel.DEBUG -> R.string.log_level_debug
    LogLevel.INFO -> R.string.log_level_info
    LogLevel.WARN -> R.string.log_level_warn
    LogLevel.ERROR -> R.string.log_level_error
}

/** 分类 → 文案资源 id。 */
private fun categoryLabelRes(category: LogCategory): Int = when (category) {
    LogCategory.LOCK -> R.string.log_category_lock
    LogCategory.VAULT -> R.string.log_category_vault
    LogCategory.PROBE -> R.string.log_category_probe
    LogCategory.BALANCE -> R.string.log_category_balance
    LogCategory.CATALOG -> R.string.log_category_catalog
    LogCategory.BACKUP -> R.string.log_category_backup
    LogCategory.HTTP -> R.string.log_category_http
    LogCategory.ACCOUNT -> R.string.log_category_account
}
