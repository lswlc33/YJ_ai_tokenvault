package com.lc33.tokenvault.ui.miuix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu

/**
 * 选择菜单里的一项。
 *
 * [selected] 让 MIUIX 在右侧画一个对勾——这是菜单把"当前值"表达出来的唯一手段，
 * 所以等级、保留期这类选项必须把当前值标上，否则用户不知道自己在改哪一档。
 */
@Immutable
data class AppMenuItem(
    val text: String,
    val onClick: () -> Unit,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val summary: String? = null,
)

/** 菜单里的一组。组与组之间由 MIUIX 画分隔线。 */
@Immutable
data class AppMenuGroup(val items: List<AppMenuItem>)

/**
 * 顶栏图标触发的选择菜单（MIUIX `OverlayIconDropdownMenu`）。
 *
 * [AppTopBar] 的 `actions` 里放它：点图标弹出挂在图标下方的浮层，条目按 [groups] 分组。
 *
 * [`collapseOnSelection`] 默认 **false**，于是它就是一个「多选菜单」：点条目只改值、
 * 不收起，用户可以连着把等级、保留期、自动滚动在一轮展开里改完。这是 MIUIX 菜单里
 * 最接近"多选"的形态（0.9.1 没有独立的 MultiSelection 组件，菜单的分组 + 多选项
 * `DropdownItem.selected` 就是它表达多选的方式）。
 *
 * 页面不直接 import MIUIX 的 dropdown 类型（AGENTS.md：只有 `ui/miuix/` 能 import MIUIX），
 * 所以这里把条目包成 [AppMenuItem] / [AppMenuGroup]。
 */
@Composable
fun AppIconMenu(
    icon: AppIcon,
    contentDescription: String,
    groups: List<AppMenuGroup>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    collapseOnSelection: Boolean = false,
) {
    OverlayIconDropdownMenu(
        entries = groups.map { group ->
            DropdownEntry(
                items = group.items.map { item ->
                    DropdownItem(
                        text = item.text,
                        enabled = item.enabled,
                        selected = item.selected,
                        summary = item.summary,
                        onClick = item.onClick,
                    )
                },
            )
        },
        modifier = modifier,
        enabled = enabled,
        collapseOnSelection = collapseOnSelection,
    ) {
        Icon(imageVector = icon.imageVector(), contentDescription = contentDescription)
    }
}
