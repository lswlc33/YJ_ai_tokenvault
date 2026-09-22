package com.lc33.tokenvault.screens.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.trend_range_7
import tokenvault.shared.generated.resources.trend_range_30
import tokenvault.shared.generated.resources.trend_range_90
import com.lc33.tokenvault.screens.model.TrendRange
import com.lc33.tokenvault.ui.miuix.AppFilterChip

/**
 * 「近 7 / 30 / 90 天」那一排。
 *
 * 余额趋势与模型变化**两页共用一份**：两处说的是同一个窗口口径（按本地日历日、今天算一天），
 * 各写一排就会长歪——一处改 90 天另一处还是 30 天默认，用户以为看的是同一段时间。
 * 文案按下标对齐 [TrendRange.entries]，顺序变了这一排就跟着错位。
 */
@Composable
fun TrendRangeChips(
    range: TrendRange,
    onSelect: (TrendRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf(
        stringResource(Res.string.trend_range_7),
        stringResource(Res.string.trend_range_30),
        stringResource(Res.string.trend_range_90),
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TrendRange.entries.forEachIndexed { index, entry ->
            AppFilterChip(
                text = labels[index],
                selected = range == entry,
                onClick = { onSelect(entry) },
            )
        }
    }
}
