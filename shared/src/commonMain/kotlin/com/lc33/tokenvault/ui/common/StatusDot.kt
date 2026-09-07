package com.lc33.tokenvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 状态圆点 **+ 文字**。
 *
 * 红线 17：状态必须同时用颜色和文字表达。所以这个组件不提供"只要点不要字"的选项——
 * 想只画一个点就得自己写，而 code review 会问为什么。
 */
@Composable
fun StatusDot(
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
    style: AppTextStyle = AppTextStyle.Secondary,
) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(tokens.statusDotSize)
                .clip(CircleShape)
                .background(color),
        )
        AppText(text = label, style = style, color = color, maxLines = 1)
    }
}
