package com.lc33.tokenvault.ui.shell

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppNavBar
import com.lc33.tokenvault.ui.miuix.AppNavBarItem
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSnackbarHost
import com.lc33.tokenvault.ui.miuix.LocalAppSnackbar
import com.lc33.tokenvault.ui.miuix.rememberAppSnackbarState

/**
 * 外层 Shell。**只负责三件事**：底部导航、Snackbar、承载 NavHost。
 *
 * 它不持有 topBar —— 顶栏由各页面自己的 [AppScaffold] 提供（计划.md §13.1）。
 * 把 topBar 放上来的代价很具体：要么变成一个按路由分支的巨型 `when`，
 * 要么需要"页面进入时向 Shell 注册标题"的机制（导航过程中必然有一帧错配），
 * 而且顶栏折叠动效必须和当前页面那个可滚动容器绑定，Shell 持有它就得往下传。
 */
@Composable
fun VaultShell() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val snackbar = rememberAppSnackbarState()

    val items = listOf(
        AppNavBarItem(label = stringResource(R.string.nav_vault), icon = AppIcon.Vault),
        AppNavBarItem(label = stringResource(R.string.nav_probe), icon = AppIcon.Probe),
        AppNavBarItem(label = stringResource(R.string.nav_settings), icon = AppIcon.Settings),
    )
    val selectedIndex = remember(entry) { topLevelIndexOf(entry?.destination) }

    AppScaffold(
        bottomBar = {
            if (selectedIndex >= 0) {
                AppNavBar(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelect = { index -> nav.navigateTopLevel(index) },
                )
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        CompositionLocalProvider(LocalAppSnackbar provides snackbar) {
            // 只取底部：底栏高度不是 inset，页面自己的 Scaffold 无从得知，必须由这里让出来。
            // 顶部与状态栏由页面自己的 Scaffold + TopAppBar 处理（计划.md §15.16）。
            VaultNavHost(
                nav = nav,
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            )
        }
    }
}

/** 一级页返回 0/1/2，二级页返回 -1（此时不显示底栏）。 */
private fun topLevelIndexOf(destination: NavDestination?): Int = when {
    destination == null -> 0
    destination.hasRoute<VaultRoute>() -> 0
    destination.hasRoute<ProbeRoute>() -> 1
    destination.hasRoute<SettingsRoute>() -> 2
    else -> -1
}

private fun NavHostController.navigateTopLevel(index: Int) {
    val route: Any = when (index) {
        0 -> VaultRoute
        1 -> ProbeRoute
        else -> SettingsRoute
    }
    navigate(route) {
        popUpTo<VaultRoute> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
