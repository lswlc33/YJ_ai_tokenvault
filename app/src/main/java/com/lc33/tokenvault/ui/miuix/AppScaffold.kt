package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState

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
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
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

@Composable
fun AppNavBar(
    items: List<AppNavBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
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
