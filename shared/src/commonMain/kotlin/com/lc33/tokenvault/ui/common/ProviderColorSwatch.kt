package com.lc33.tokenvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.ui.theme.LocalProviderPalette

/**
 * 一个供应商色块。
 *
 * `providers.color` 的**唯一入口**是编辑页那一行颜色选择（红线 16：每个持久化字段都要
 * 有 UI 入口），这个组件是那一行与它下拉里每一项共用的图形。
 *
 * 从"一整排色块任点"改成"一行 + 下拉"之后，色块不再承担点击，只回答"现在选的是哪个
 * 颜色"，所以这里**不画选中态**——选中由下拉项的勾表达，一行上再叠一个勾只会让人以为
 * 还能点。
 *
 * [index] 为 null 是「自动」这一档（下拉里排在手选色之后）：这时画的是**这家真正会拿到的
 * 生成色**，让用户点之前就看到结果；新建还没落库时（[providerId] 为 0）没有 id 可生成，
 * 退化成一条色相带，说的是"这一档由程序给"，而不是"它是某个颜色"。
 */
@Composable
fun ProviderColorSwatch(
    index: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    providerId: Long = 0L,
) {
    val palette = LocalProviderPalette.current
    if (index == null && providerId == 0L) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size / 3))
                .background(
                    // 八个手选色首尾相接一条色带：这一档不给具体颜色，给的是"程序自己挑"。
                    Brush.horizontalGradient(palette.swatches),
                ),
        )
        return
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(palette.colorFor(providerId, index)),
    )
}
