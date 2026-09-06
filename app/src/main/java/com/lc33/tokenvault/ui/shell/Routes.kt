package com.lc33.tokenvault.ui.shell

import kotlinx.serialization.Serializable

/**
 * 类型安全路由（Navigation Compose 2.8+ 的 `@Serializable` 路由对象）。
 *
 * 计划.md §13.1 列了全部路由，这里只声明已经有目标页的那几个——
 * 声明一个没有 `composable<T>` 的路由，第一次 navigate 就会崩，
 * 所以路由跟着页面一起加。
 */

// ---------------------------------------------------------------- 一级页（底栏三项）

/** 只读总览。不提供任何编辑入口，每一行的点击结果都是"跳到管理页的某个详情"。 */
@Serializable
data object DashboardRoute

/** 全部内容的列表，也是唯一的编辑入口。 */
@Serializable
data object ManageRoute

/** 软件自身的配置。本身是导航面板，具体项在各二级页。 */
@Serializable
data object SettingsRoute

// ---------------------------------------------------------------- 管理下的二级页

@Serializable
data class ProviderDetailRoute(val id: Long)

@Serializable
data class ProviderEditorRoute(val id: Long? = null)

@Serializable
data object ImportRoute

@Serializable
data object GroupsRoute

// ---------------------------------------------------------------- 仪表盘下的二级页

/** 本轮 / 上轮探测的逐项结果。探测是动作不是内容，所以它在这里而不在底栏。 */
@Serializable
data object ProbeRunRoute

@Serializable
data object BalanceBreakdownRoute

// ---------------------------------------------------------------- 设置下的二级页

@Serializable
data object AppearanceRoute

@Serializable
data object SecurityRoute

/** 改 PIN。只重新包裹数据密钥，不重新加密数据（红线 2）。 */
@Serializable
data object ChangePinRoute

/** 恢复密钥：看状态、补一把或换一把。已有的那把看不到（它从不落盘）。 */
@Serializable
data object RecoveryKeyRoute

@Serializable
data object ProbeSettingsRoute

@Serializable
data object ProfileListRoute

/** 编辑 / 新建一个客户端预设。id = 0 表示新建（可从 cURL 导入）。 */
@Serializable
data class ProfileEditorRoute(val id: Long = 0)

@Serializable
data object DataRoute

/** 日志页（audit_log）。数据页的「日志」入口。 */
@Serializable
data object LogRoute

@Serializable
data object SyncRoute

@Serializable
data object UpdateRoute

@Serializable
data object AboutRoute
