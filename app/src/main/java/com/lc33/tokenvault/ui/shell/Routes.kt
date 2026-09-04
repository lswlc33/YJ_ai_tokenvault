package com.lc33.tokenvault.ui.shell

import kotlinx.serialization.Serializable

/**
 * 类型安全路由（Navigation Compose 2.8+ 的 `@Serializable` 路由对象）。
 *
 * 计划.md §13.1 列了全部路由，这里只声明已经有目标页的那几个——
 * 声明一个没有 `composable<T>` 的路由，第一次 navigate 就会崩，
 * 所以路由跟着页面一起加。
 */
@Serializable
data object VaultRoute

@Serializable
data object ProbeRoute

@Serializable
data object SettingsRoute

@Serializable
data object AboutRoute
