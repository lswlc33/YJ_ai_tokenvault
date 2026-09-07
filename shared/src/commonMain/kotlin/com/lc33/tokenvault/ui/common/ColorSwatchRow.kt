package com.lc33.tokenvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconTint
import com.lc33.tokenvault.ui.theme.LocalProviderPalette

/**
 * 色块选择器 —— `providers.color` 的**唯一入口**（红线 16：每个持久化字段都必须有
 * UI 入口，不给入口就别建这一列）。
 *
 * 选中态用一个对勾而不是只加边框：边框在深色主题下和某些色块几乎看不出区别，
 * 而"我到底选了哪个"是这个控件唯一要回答的问题。
 */
@Composable
fun ColorSwatchRow(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalProviderPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        palette.swatches.forEachIndexed { index, color ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(color)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    AppIconTint(icon = AppIcon.Ok, size = 18.dp)
                }
            }
        }
    }
}
