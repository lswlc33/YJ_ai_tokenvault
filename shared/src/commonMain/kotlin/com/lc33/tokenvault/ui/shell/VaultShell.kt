package com.lc33.tokenvault.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lc33.tokenvault.domain.model.PredictiveBackExitDirection
import com.lc33.tokenvault.domain.model.PredictiveBackStyle
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.platform.Haptics
import com.lc33.tokenvault.platform.PlatformBackHandler
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.nav_dashboard
import tokenvault.shared.generated.resources.nav_manage
import tokenvault.shared.generated.resources.nav_settings
import com.lc33.tokenvault.ui.miuix.AppLiquidNavBar
import com.lc33.tokenvault.ui.miuix.AppNavBarItem
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSnackbarHost
import com.lc33.tokenvault.ui.miuix.LocalAppBottomBarInset
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
    var backStackRevision by remember { mutableIntStateOf(0) }
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

    // 一级页是一台可左右滑的 pager，三个 tab 平级。权威的选中页在 [TopLevelPagerState]，
    // 底栏高亮和页面内容都读它——不再从 backStack 反推 tab，因为切 tab 根本不压栈了。
    // 初始页从栈底还原：进程恢复后用户停在哪一页，栈底就是哪个路由。
    val pagerState = rememberPagerState(
        initialPage = topLevelIndexOf(backStack.firstOrNull()).coerceAtLeast(0),
    ) { TopLevelRoutes.size }
    val pager = rememberTopLevelPagerState(pagerState)

    // 手指滑出来的页要收回来。底栏点击不经过这里：animateToPage 起手就认过目标页了。
    LaunchedEffect(pagerState.currentPage) { pager.syncPage() }

    // 二级页压在 pager 之上（底栏退场）；一级页时底栏高亮由 pager 决定。
    val showingTopLevel = topLevelIndexOf(backStack.lastOrNull()) >= 0
    val selectedIndex = if (showingTopLevel) pager.selectedPage else -1

    // 系统返回手势 = 回第一页（照 MIUIX 的 MainScreenBackHandler 规则）。
    // 系统返回是**边缘手势**，那一窄条在分发阶段就被系统截走，应用收不到，所以不可能让 pager
    // 去跟手；做法是让两者同向：一级页上且不在第一页时消费返回、滑回总览，于是从边缘滑和
    // 从中间滑表达的是同一件事。已经在第一页就不再消费，交给系统退出应用；二级页时 enabled
    // 为 false，返回交回 NavDisplay 处理。
    PlatformBackHandler(
        enabled = backStack.size == 1 &&
            topLevelIndexOf(backStack.lastOrNull()) >= 0 &&
            pager.selectedPage != 0,
    ) {
        pager.animateToPage(0)
    }

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
                AppLiquidNavBar(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelect = { index ->
                        // 切 tab 给轻触反馈（问题 5）。只在本页已经在底栏可见时触发，
                        // 否则首屏加载也会震一下。
                        if (selectedIndex >= 0) Haptics.tap()
                        pager.animateToPage(index)
                    },
                    blur = blurNavBar,
                    blurBackdrop = backdrop,
                )
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        CompositionLocalProvider(
            LocalAppSnackbar provides snackbar,
            LocalAppBottomBarInset provides padding.calculateBottomPadding(),
        ) {
            // 内容必须绘制到窗口底部，才能透过浮层玻璃被采样；底栏高度改为通过
            // LocalAppBottomBarInset 传给页面，由页面自己的 Scaffold 透明避让。
            // 顶部与状态栏由页面自己的 Scaffold + TopAppBar 处理（计划.md §15.16）。
            VaultNavHost(
                backStack = backStack,
                revision = backStackRevision,
                onBackStackChanged = { backStackRevision++ },
                pager = pager,
                style = backStyle,
                exitDirection = backExitDirection,
                modifier = Modifier.appLayerBackdrop(backdrop),
            )
        }
    }
}
