package com.lc33.tokenvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTrackColor

/** 分段条的一段。`weight` 是数量，为 0 的段不画。 */
data class BarSegment(val weight: Int, val color: Color)

/**
 * 横向分段条。仪表盘的密钥健康分布用它。
 *
 * 只画条不写字：图例由调用方用 [StatusDot] 逐行给出，这样"颜色 + 文字"的配对
 * 落在同一个地方（红线 17）。全部为 0 时画一条空轨道，而不是什么都不画——
 * 不画会让卡片高度跳变。
 */
@Composable
fun SegmentedBar(
    segments: List<BarSegment>,
    modifier: Modifier = Modifier,
    barHeight: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val shown = segments.filter { it.weight > 0 }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(appTrackColor),
    ) {
        shown.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight(segment.weight.toFloat())
                    .fillMaxHeight()
                    .background(segment.color),
            )
        }
    }
}

/** 一格数字 + 标签。仪表盘的内容计数用它，四格等宽。 */
@Composable
fun RowScope.StatTile(
    value: String,
    label: String,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AppText(text = value, style = AppTextStyle.Title, textAlign = TextAlign.Center)
        AppText(
            text = label,
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
