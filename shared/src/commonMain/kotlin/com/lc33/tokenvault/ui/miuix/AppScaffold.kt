package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.di.Qualifiers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
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

/** 提示停留时长。页面不直接用 MIUIX 的 `SnackbarDuration`。 */
enum class AppSnackbarDuration {
    /** 4 秒。只报结果的提示用它。 */
    Short,

    /** 10 秒。**带撤销的提示必须用它**：撤销窗口就是这段时间，太短等于没给机会点。 */
    Long,
}

/**
 * Snackbar 状态。MIUIX 自带 Snackbar，所以不需要为了它引入 material3。
 *
 * **关闭按钮固定打开**（[SnackbarHostState.showSnackbar] 的 `withDismissAction = true`）：
 * 提示只要出现就一定能被主动关掉，不必等它自己走完，也不会只剩"点空白处"一条退路。
 */
@Stable
class AppSnackbarState internal constructor(internal val hostState: SnackbarHostState) {
    /**
     * 显示一条提示。
     *
     * @param actionText 非空时在右侧显示 action 按钮（撤销这类动作）。
     * @return true 表示用户点了 action，而不是让提示自己消失或被关闭按钮关掉。
     */
    suspend fun show(
        message: String,
        duration: AppSnackbarDuration = AppSnackbarDuration.Short,
        actionText: String? = null,
    ): Boolean {
        val result = hostState.showSnackbar(
            message = message,
            actionLabel = actionText,
            withDismissAction = true,
            duration = when (duration) {
                AppSnackbarDuration.Short -> SnackbarDuration.Short
                AppSnackbarDuration.Long -> SnackbarDuration.Long
            },
        )
        return result == SnackbarResult.ActionPerformed
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

/**
 * 可撤销提示的三个文案。由调用方（composable 层）从资源解析，本层不碰资源。
 */
@Immutable
data class AppUndoFeedback(
    /** action 按钮文案，例如"撤销"。 */
    val actionLabel: String,
    /** 撤销成功后的提示。 */
    val undoneMessage: String,
    /** 撤销失败后的提示（如唯一约束冲突、行已被重建）。 */
    val failedMessage: String,
    /** 真正执行撤销；返回 false 表示这次撤销没成功。 */
    val action: suspend () -> Boolean,
)

/**
 * 一条待展示的提示。
 */
@Immutable
data class AppFeedback(
    val message: String,
    /** 非空时提示带上"撤销"action，且停留 [AppSnackbarDuration.Long]。 */
    val undo: AppUndoFeedback? = null,
)

/**
 * 提示队列。Shell 持有唯一一份，页面通过 [LocalAppFeedback] 投递。
 *
 * 两个不显然但必要的地方：
 *
 * 1. **它必须挂在 Shell 上，而不是某个页面的组合里。** 删除之后页面会退出组合，
 *    如果在一个随页面销毁的作用域里 `await` snackbar 的结果，协程会被取消，
 *    用户点"撤销"时回调早已不存在——撤销按钮会点了没反应。
 * 2. **一条一条串行展示（Channel + 单消费者）。** MIUIX 的 host 支持同时堆叠多条
 *    snackbar，但那样连续删除时屏幕上会同时出现好几个"撤销"，用户不知道该撤哪一个，
 *    点错就是恢复到错误的状态。
 */
@Stable
class AppFeedbackHost internal constructor(
    private val scope: CoroutineScope,
    private val snackbar: AppSnackbarState,
) {
    private val queue = Channel<AppFeedback>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (feedback in queue) present(feedback)
        }
    }

    /** 投递提示。非阻塞：调用方（含即将被销毁的页面）不因等待提示而挂住。 */
    fun post(feedback: AppFeedback) {
        queue.trySend(feedback)
    }

    /**
     * 收口：不再取新条目。由 [rememberAppFeedbackHost] 在退出组合时调用。
     *
     * 只 `close()` 而**不取消**协程——手上那一条（尤其正在写的撤销）要让它做完，
     * 这才是把 host 挂到应用级作用域想要的结果；同时又不让它无限留着。
     */
    internal fun detach() {
        queue.close()
    }

    private suspend fun present(feedback: AppFeedback) {
        val undo = feedback.undo
        val acted = snackbar.show(
            message = feedback.message,
            // 有撤销就必须给足时间，否则用户还没看清提示它已经消失了。
            duration = if (undo == null) {
                AppSnackbarDuration.Short
            } else {
                AppSnackbarDuration.Long
            },
            actionText = undo?.actionLabel,
        )
        if (!acted || undo == null) return
        val ok = runCatching { undo.action() }.getOrDefault(false)
        snackbar.show(if (ok) undo.undoneMessage else undo.failedMessage)
    }
}

@Composable
fun rememberAppFeedbackHost(snackbar: AppSnackbarState): AppFeedbackHost {
    // 作用域不能用 rememberCoroutineScope：那条路把整台队列绑在 Shell 的组合上，
    // 一锁屏（LockGate 换掉整棵树）协程当场被取消，而"撤销"要做的写库正跑在半路上——
    // 结果是删了五家、恢复两家，剩三家无声消失。APP_SCOPE 是应用级的 SupervisorJob，
    // 活得比任何一棵组合树久，撤销那一步因此能做完。
    val appScope = koinInject<CoroutineScope>(named(Qualifiers.APP_SCOPE))
    val host = remember(snackbar, appScope) { AppFeedbackHost(appScope, snackbar) }
    // 每次锁屏/解锁都会新建一个宿主，所以退组合时必须关掉队列：
    // 循环做完手上那条就自己收口，不在 APP_SCOPE 上留一条永远悬着的协程。
    DisposableEffect(host) {
        onDispose { host.detach() }
    }
    return host
}

/** 由 Shell 提供，页面通过它发提示，不各自持有一个 host。 */
val LocalAppFeedback = staticCompositionLocalOf<AppFeedbackHost?> { null }
