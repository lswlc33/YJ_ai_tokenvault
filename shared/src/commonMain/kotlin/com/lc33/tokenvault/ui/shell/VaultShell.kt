package com.lc33.tokenvault.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lc33.tokenvault.domain.model.PredictiveBackExitDirection
import com.lc33.tokenvault.domain.model.PredictiveBackStyle
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.platform.Haptics
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.nav_dashboard
import tokenvault.shared.generated.resources.nav_manage
import tokenvault.shared.generated.resources.nav_settings
import com.lc33.tokenvault.ui.miuix.AppNavBar
import com.lc33.tokenvault.ui.miuix.AppNavBarItem
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSnackbarHost
import com.lc33.tokenvault.ui.miuix.LocalAppSnackbar
import com.lc33.tokenvault.ui.miuix.navigation.rememberVaultBackStack
import com.lc33.tokenvault.ui.miuix.appLayerBackdrop
import com.lc33.tokenvault.ui.miuix.rememberAppLayerBackdrop
import com.lc33.tokenvault.ui.miuix.rememberAppSnackbarState

/**
 * 外层 Shell。**只负责三件事**：底部导航、Snackbar、承载 Navigation 3 容器。
 *
 * 它不持有 topBar —— 顶栏由各页面自己的 [AppScaffold] 提供（计划.md §13.1）。
 * 把 topBar 放上来的代价很具体：要么变成一个按路由分支的巨型 `when`，
 * 要么需要"页面进入时向 Shell 注册标题"的机制（导航过程中必然有一帧错配），
 * 而且顶栏折叠动效必须和当前页面那个可滚动容器绑定，Shell 持有它就得往下传。
 */
@Composable
fun VaultShell() {
    val backStack = rememberVaultBackStack()
    val snackbar = rememberAppSnackbarState()

    // 底栏模糊与返回动画都来自 app_settings：外观页改完，这里和导航层看到的是同一条流。
    val appearance: AppearanceViewModel = koinViewModel()
    val blurNavBar by appearance.blurNavBar.collectAsStateWithLifecycle()
    val backStyle by appearance.predictiveBackStyle.collectAsStateWithLifecycle()
    val backExitDirection by appearance.predictiveBackExitDirection.collectAsStateWithLifecycle()

    // 底栏模糊：backdrop 捕获内容区，NavigationBar 挂 textureBlur。开关关掉时
    // textureBlur(enabled=false) 直接跳过模糊、内容照常画，所以 backdrop 始终创建无妨。
    val backdrop = rememberAppLayerBackdrop()

    val items = listOf(
        AppNavBarItem(label = stringResource(Res.string.nav_dashboard), icon = AppIcon.Dashboard),
        AppNavBarItem(label = stringResource(Res.string.nav_manage), icon = AppIcon.Manage),
        AppNavBarItem(label = stringResource(Res.string.nav_settings), icon = AppIcon.Settings),
    )
    val selectedIndex = topLevelIndexOf(backStack.lastOrNull())

    AppScaffold(
        bottomBar = {
            // 底栏用 AnimatedVisibility 平滑退场：直接 `if (selectedIndex >= 0)` 会在
            // 跳到二级页（如设置→检查更新）时让底栏瞬间消失、内容区一帧高度塌陷，
            // 表现为「底栏先闪一下再跳下一页」。滑入滑出把这一帧填成过渡，且不改变透明度。
            AnimatedVisibility(
                visible = selectedIndex >= 0,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                AppNavBar(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelect = { index ->
                        // 切 tab 给轻触反馈（问题 5）。只在本页已经在底栏可见时触发，
                        // 否则首屏加载也会震一下。
                        if (selectedIndex >= 0) Haptics.tap()
                        navigateTopLevel(backStack, index)
                    },
                    blur = blurNavBar,
                    blurBackdrop = backdrop,
                )
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        CompositionLocalProvider(LocalAppSnackbar provides snackbar) {
            // 只取底部：底栏高度不是 inset，页面自己的 Scaffold 无从得知，必须由这里让出来。
            // 顶部与状态栏由页面自己的 Scaffold + TopAppBar 处理（计划.md §15.16）。
            VaultNavHost(
                backStack = backStack,
                style = backStyle,
                exitDirection = backExitDirection,
                modifier = Modifier
                    .padding(bottom = padding.calculateBottomPadding())
                    .appLayerBackdrop(backdrop),
            )
        }
    }
}

/** 一级页返回 0/1/2，二级页返回 -1（此时不显示底栏）。 */
private fun topLevelIndexOf(route: VaultRoute?): Int = when (route) {
    null, DashboardRoute -> 0
    ManageRoute -> 1
    SettingsRoute -> 2
    else -> -1
}

/** 三个 tab 都是 Dashboard 后的一层；系统返回永远回到总览，不在 tab 间绕圈。 */
private fun navigateTopLevel(backStack: MutableList<VaultRoute>, index: Int) {
    val route = when (index) {
        0 -> DashboardRoute
        1 -> ManageRoute
        else -> SettingsRoute
    }
    while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    if (backStack.lastOrNull() != route) backStack.add(route)
}
