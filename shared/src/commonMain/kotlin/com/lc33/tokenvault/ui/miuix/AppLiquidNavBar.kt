package com.lc33.tokenvault.ui.miuix

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lc33.tokenvault.ui.miuix.liquid.IosLiquidGlassNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem

/**
 * iOS 液态玻璃风底栏（自 MIUIX v0.9.1 示例 vendor，见 ui/miuix/liquid/）。
 *
 * 与 [AppNavBar] 同形：同样的参数、同样的 [AppNavBarItem]，Shell 层换一个函数名
 * 就能在两者之间切换。`blur = false` 时走纯色回退路径（低端机 / 关闭模糊开关）。
 */
@Composable
fun AppLiquidNavBar(
    items: List<AppNavBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    blur: Boolean = false,
    blurBackdrop: AppLayerBackdrop? = null,
) {
    IosLiquidGlassNavigationBar(
        items = items.map { item ->
            NavigationItem(label = item.label, icon = item.icon.imageVector())
        },
        selectedIndex = selectedIndex,
        onItemClick = onSelect,
        backdrop = blurBackdrop?.backdrop,
        isBlurActive = blur,
        modifier = modifier,
    )
}