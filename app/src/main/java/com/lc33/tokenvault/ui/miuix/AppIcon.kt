package com.lc33.tokenvault.ui.miuix

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings

/**
 * 图标的应用级名字。
 *
 * 存在的理由与 [AppTheme] 里那个枚举一样：`ImageVector` 是 Compose 类型没问题，
 * 但取它的那些 `MiuixIcons.*` 扩展属性是 MIUIX 的 API。让页面直接引用它们，
 * MIUIX 换一次图标命名就要改一堆页面，而且 CI 的"只有 ui/miuix 能 import MIUIX"
 * 也就守不住了。
 */
enum class AppIcon {
    Vault,
    Probe,
    Settings,
    Back,
    Add,
    Refresh,
    Info,
}

internal fun AppIcon.imageVector(): ImageVector = when (this) {
    AppIcon.Vault -> MiuixIcons.Lock
    AppIcon.Probe -> MiuixIcons.Scan
    AppIcon.Settings -> MiuixIcons.Settings
    AppIcon.Back -> MiuixIcons.Back
    AppIcon.Add -> MiuixIcons.Add
    AppIcon.Refresh -> MiuixIcons.Refresh
    AppIcon.Info -> MiuixIcons.Info
}
