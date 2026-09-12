package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * 浮层底栏给页面内容预留的透明 inset。
 *
 * 外层 Shell 的底栏覆盖在页面之上，不参与页面 Scaffold 的测量；页面如果不知道
 * 这部分高度，列表末项和 FAB 会压进药丸。Shell 把真实底栏高度写进来，[AppScaffold]
 * 再用一个透明的 bottomBar 占位，既保留内容穿透绘制，也让内容与 FAB 正确避让。
 */
internal val LocalAppBottomBarInset = staticCompositionLocalOf { 0.dp }

/**
 * MIUIX `Scaffold` 的包装。
 *
 * 本项目采用**分层 Scaffold**（计划.md §13.1）：外层 Shell 只管底栏与 Snackbar，
 * 每个页面自己套一个 [AppScaffold] 提供 topBar / FAB。这是官方支持的用法——
 * `Scaffold` 的 `popupHost` 默认就是 `MiuixPopupHost()`，嵌套或并列的 Scaffold
 * 各自管理自己的弹出层。分层的好处是 topBar 的折叠状态天然属于当前页面那个
 * 可滚动容器，不用往下传、换路由也不会串。
 */
@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val bottomBarInset = LocalAppBottomBarInset.current
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = {
            if (bottomBarInset > 0.dp) {
                Column {
                    bottomBar()
                    Spacer(modifier = Modifier.height(bottomBarInset))
                }
            } else {
                bottomBar()
            }
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        content = content,
    )
}

/**
 * 顶栏折叠状态。包成自己的类型，页面就不必 import MIUIX 的 `ScrollBehavior`。
 */
@Stable
class AppTopBarScrollState internal constructor(internal val behavior: ScrollBehavior)

@Composable
fun rememberAppTopBarScrollState(): AppTopBarScrollState {
    val behavior = MiuixScrollBehavior(rememberTopAppBarState())
    return remember(behavior) { AppTopBarScrollState(behavior) }
}

/**
 * 把顶栏的折叠动效绑到当前页面的可滚动容器上。少了这一步顶栏不会跟着滚动收起。
 */
fun Modifier.appTopBarScroll(state: AppTopBarScrollState): Modifier =
    this.nestedScroll(state.behavior.nestedScrollConnection)

@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    scrollState: AppTopBarScrollState? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = { actions() },
        scrollBehavior = scrollState?.behavior,
    )
}

@Stable
data class AppNavBarItem(
    val label: String,
    val icon: AppIcon,
)

/**
 * 底栏背景模糊的 backdrop 句柄。
 *
 * 把 MIUIX 的 [LayerBackdrop] 包一层，让 `ui/shell/` 层不用 import MIUIX（AGENTS.md：
 * 只有 `ui/miuix/` 能碰 MIUIX）。用法：`rememberAppLayerBackdrop()` 建一份，把
 * [Modifier.appLayerBackdrop] 挂在要作为「模糊背景」的内容容器上，再把同一个句柄
 * 传给 [AppNavBar] 的 `blurBackdrop`。
 */
@Stable
class AppLayerBackdrop internal constructor(internal val backdrop: LayerBackdrop)

@Composable
fun rememberAppLayerBackdrop(): AppLayerBackdrop {
    val backdrop = rememberLayerBackdrop()
    return remember(backdrop) { AppLayerBackdrop(backdrop) }
}

/** 捕获此容器的内容到 [AppLayerBackdrop]，供底栏 [AppNavBar] 做背景模糊。 */
fun Modifier.appLayerBackdrop(backdrop: AppLayerBackdrop): Modifier =
    this.layerBackdrop(backdrop.backdrop)

@Composable
fun AppNavBar(
    items: List<AppNavBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    blur: Boolean = false,
    blurBackdrop: AppLayerBackdrop? = null,
) {
    val blurred = modifier.then(
        if (blur && blurBackdrop != null) {
            Modifier.textureBlur(
                backdrop = blurBackdrop.backdrop,
                shape = RectangleShape,
                enabled = true,
            )
        } else {
            Modifier
        },
    )
    NavigationBar(modifier = blurred) {
        items.forEachIndexed { index, item ->
            NavBarItem(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                item = item,
            )
        }
    }
}

@Composable
private fun RowScope.NavBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    item: AppNavBarItem,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = item.icon.imageVector(),
        label = item.label,
    )
}

/**
 * Snackbar 状态。MIUIX 0.9.3 自带 Snackbar，所以不需要为了它引入 material3。
 */
@Stable
class AppSnackbarState internal constructor(internal val hostState: SnackbarHostState) {
    suspend fun show(message: String) {
        hostState.showSnackbar(message)
    }
}

@Composable
fun rememberAppSnackbarState(): AppSnackbarState {
    val hostState = remember { SnackbarHostState() }
    return remember(hostState) { AppSnackbarState(hostState) }
}

@Composable
fun AppSnackbarHost(state: AppSnackbarState, modifier: Modifier = Modifier) {
    SnackbarHost(state = state.hostState, modifier = modifier)
}

/** 由 Shell 提供，页面通过它发提示，不各自持有一个 host。 */
val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbarState?> { null }
