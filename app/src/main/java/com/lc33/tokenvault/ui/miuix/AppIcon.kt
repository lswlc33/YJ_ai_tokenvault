package com.lc33.tokenvault.ui.miuix

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update

/**
 * 图标的应用级名字。
 *
 * 存在的理由与 [AppTheme] 里那个枚举一样：`ImageVector` 是 Compose 类型没问题，
 * 但取它的那些 `MiuixIcons.*` 扩展属性是 MIUIX 的 API。让页面直接引用它们，
 * MIUIX 换一次图标命名就要改一堆页面，而且 CI 的"只有 ui/miuix 能 import MIUIX"
 * 也就守不住了。
 *
 * 命名按**用途**而不是按图形：`Key` 用的是锁的图形，但页面里写 `AppIcon.Key`，
 * 哪天换成钥匙图形也不用改页面。
 */
enum class AppIcon {
    // 底栏
    Dashboard,
    Manage,
    Settings,

    // 四类内容
    Provider,
    Key,
    Model,
    Account,

    // 动作
    Back,
    Forward,
    Add,
    Edit,
    Delete,
    Copy,
    Paste,
    More,
    Refresh,
    Search,
    Filter,
    Sort,
    Probe,
    Reveal,
    Conceal,
    OpenLink,
    Tune,

    // 状态与设置块
    Ok,
    Warning,
    Info,
    Sync,
    Update,
}

internal fun AppIcon.imageVector(): ImageVector = when (this) {
    AppIcon.Dashboard -> MiuixIcons.Home
    AppIcon.Manage -> MiuixIcons.GridView
    AppIcon.Settings -> MiuixIcons.Settings

    AppIcon.Provider -> MiuixIcons.Store
    AppIcon.Key -> MiuixIcons.Lock
    AppIcon.Model -> MiuixIcons.Layers
    AppIcon.Account -> MiuixIcons.Contacts

    AppIcon.Back -> MiuixIcons.Back
    AppIcon.Forward -> MiuixIcons.ChevronForward
    AppIcon.Add -> MiuixIcons.Add
    AppIcon.Edit -> MiuixIcons.Edit
    AppIcon.Delete -> MiuixIcons.Delete
    AppIcon.Copy -> MiuixIcons.Copy
    AppIcon.Paste -> MiuixIcons.Paste
    AppIcon.More -> MiuixIcons.More
    AppIcon.Refresh -> MiuixIcons.Refresh
    AppIcon.Search -> MiuixIcons.Search
    AppIcon.Filter -> MiuixIcons.Filter
    AppIcon.Sort -> MiuixIcons.Sort
    AppIcon.Probe -> MiuixIcons.Scan
    AppIcon.Reveal -> MiuixIcons.Show
    AppIcon.Conceal -> MiuixIcons.Hide
    AppIcon.OpenLink -> MiuixIcons.Link
    AppIcon.Tune -> MiuixIcons.Tune

    AppIcon.Ok -> MiuixIcons.Ok
    AppIcon.Warning -> MiuixIcons.Report
    AppIcon.Info -> MiuixIcons.Info
    AppIcon.Sync -> MiuixIcons.Backup
    AppIcon.Update -> MiuixIcons.Update
}
