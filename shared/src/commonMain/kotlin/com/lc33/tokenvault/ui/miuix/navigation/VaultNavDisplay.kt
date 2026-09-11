package com.lc33.tokenvault.ui.miuix.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigationevent.NavigationEvent
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import androidx.navigation3.ui.NavDisplayTransitionEffects
import androidx.savedstate.serialization.SavedStateConfiguration
import com.lc33.tokenvault.domain.model.PredictiveBackExitDirection
import com.lc33.tokenvault.domain.model.PredictiveBackStyle
import com.lc33.tokenvault.ui.shell.AboutRoute
import com.lc33.tokenvault.ui.shell.AppearanceRoute
import com.lc33.tokenvault.ui.shell.BalanceBreakdownRoute
import com.lc33.tokenvault.ui.shell.BalanceThresholdsRoute
import com.lc33.tokenvault.ui.shell.ChangePinRoute
import com.lc33.tokenvault.ui.shell.ClientKeywordsRoute
import com.lc33.tokenvault.ui.shell.DashboardRoute
import com.lc33.tokenvault.ui.shell.DataRoute
import com.lc33.tokenvault.ui.shell.GroupsRoute
import com.lc33.tokenvault.ui.shell.ImportRoute
import com.lc33.tokenvault.ui.shell.KeyDetailRoute
import com.lc33.tokenvault.ui.shell.KeyEditorRoute
import com.lc33.tokenvault.ui.shell.LogRoute
import com.lc33.tokenvault.ui.shell.ManageRoute
import com.lc33.tokenvault.ui.shell.ProfileEditorRoute
import com.lc33.tokenvault.ui.shell.ProfileListRoute
import com.lc33.tokenvault.ui.shell.ProviderDetailRoute
import com.lc33.tokenvault.ui.shell.ProviderEditorRoute
import com.lc33.tokenvault.ui.shell.ProxyRoute
import com.lc33.tokenvault.ui.shell.ProbeRunRoute
import com.lc33.tokenvault.ui.shell.ProbeSettingsRoute
import com.lc33.tokenvault.ui.shell.SecurityRoute
import com.lc33.tokenvault.ui.shell.SettingsRoute
import com.lc33.tokenvault.ui.shell.SyncRoute
import com.lc33.tokenvault.ui.shell.UpdateRoute
import com.lc33.tokenvault.ui.shell.VaultRoute
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** 对外只暴露 MutableList，调用方不需要知道 NavBackStack 的具体类型。 */
@Composable
fun rememberVaultBackStack(): MutableList<VaultRoute> {
    val configuration = remember {
        SavedStateConfiguration {
            serializersModule = SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(DashboardRoute::class)
                    subclass(ManageRoute::class)
                    subclass(SettingsRoute::class)
                    subclass(ProviderDetailRoute::class)
                    subclass(ProviderEditorRoute::class)
                    subclass(KeyDetailRoute::class)
                    subclass(KeyEditorRoute::class)
                                        subclass(ImportRoute::class)
                    subclass(GroupsRoute::class)
                    subclass(ProbeRunRoute::class)
                    subclass(BalanceBreakdownRoute::class)
                    subclass(AppearanceRoute::class)
                    subclass(SecurityRoute::class)
                    subclass(ChangePinRoute::class)
                    subclass(ProbeSettingsRoute::class)
                    subclass(BalanceThresholdsRoute::class)
                    subclass(ClientKeywordsRoute::class)
                    subclass(ProxyRoute::class)
                    subclass(ProfileListRoute::class)
                    subclass(ProfileEditorRoute::class)
                    subclass(DataRoute::class)
                    subclass(LogRoute::class)
                    subclass(SyncRoute::class)
                    subclass(UpdateRoute::class)
                    subclass(AboutRoute::class)
                }
            }
        }
    }
    @Suppress("UNCHECKED_CAST")
    return rememberNavBackStack(configuration, DashboardRoute) as NavBackStack<VaultRoute>
}

/**
 * MIUIX NavDisplay 的项目包装。页面只传 VaultRoute 和 composable content，
 * 不直接接触 Navigation 3 / MIUIX 的实现类型。
 */
@Composable
fun VaultNavDisplay(
    backStack: List<VaultRoute>,
    onBack: () -> Unit,
    style: PredictiveBackStyle,
    exitDirection: PredictiveBackExitDirection,
    modifier: Modifier = Modifier,
    content: @Composable (VaultRoute) -> Unit,
) {
    val currentContent by rememberUpdatedState(content)
    val entryProvider = remember {
        { key: VaultRoute ->
            NavEntry(key) { currentContent(key) }
        }
    }
    val saveableDecorator = rememberSaveableStateHolderNavEntryDecorator<VaultRoute>()
    val viewModelDecorator = rememberViewModelStoreNavEntryDecorator<VaultRoute>()
    val decorators = remember(saveableDecorator, viewModelDecorator) {
        listOf(saveableDecorator, viewModelDecorator)
    }
    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryDecorators = decorators,
        entryProvider = entryProvider,
    )

    NavDisplay(
        entries = entries,
        modifier = modifier,
        onBack = onBack,
        transitionSpec = { appPushTransition() },
        popTransitionSpec = { appPopTransition() },
        predictivePopTransitionSpec = { edge ->
            appPredictivePopTransition(style, exitDirection, edge)
        },
        transitionEffects = NavDisplayTransitionEffects(
            enableCornerClip = true,
            dimAmount = 0f,
            blockInputDuringTransition = false,
            popDirectionFollowsSwipeEdge = style == PredictiveBackStyle.Scale &&
                exitDirection == PredictiveBackExitDirection.FollowGesture,
        ),
    )
}

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.appPushTransition(): ContentTransform {
    return if (isTopLevelSceneTransition()) {
        topLevelTransition()
    } else {
        horizontalPush()
    }
}

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.appPopTransition(): ContentTransform =
    if (isTopLevelSceneTransition()) {
        topLevelTransition()
    } else {
        // 常规返回不吃“预见式返回样式”的配置：按钮返回永远是 MIUIX 的层级平移。
        miuixPop()
    }

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.appPredictivePopTransition(
    style: PredictiveBackStyle,
    exitDirection: PredictiveBackExitDirection,
    edge: Int,
): ContentTransform =
    if (isTopLevelSceneTransition()) {
        topLevelTransition()
    } else {
        when (style) {
            PredictiveBackStyle.None -> noPredictivePop()
            PredictiveBackStyle.Miuix -> miuixPop()
            PredictiveBackStyle.Scale -> scalePop(exitDirection, edge)
        }
    }

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.isTopLevelSceneTransition(): Boolean =
    isTopLevelScene(initialState) && isTopLevelScene(targetState)

private fun isTopLevelScene(scene: Scene<VaultRoute>): Boolean =
    scene.key is DashboardRoute || scene.key is ManageRoute || scene.key is SettingsRoute

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.topLevelTransition(): ContentTransform {
    // 一级页切换按 tab 顺序决定方向；只用位移，不用透明度掩盖叠层。
    val forward = topLevelIndexOf(targetState.key) > topLevelIndexOf(initialState.key)
    val enterOffset: (Int) -> Int = { if (forward) it / 4 else -it / 4 }
    val exitOffset: (Int) -> Int = { if (forward) -it / 4 else it / 4 }
    return slideInHorizontally(tween(TopLevelDurationMs), enterOffset) togetherWith
        slideOutHorizontally(tween(TopLevelDurationMs), exitOffset)
}

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.horizontalPush(): ContentTransform =
    defaultTransitionSpec<VaultRoute>().invoke(this)

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.miuixPop(): ContentTransform =
    defaultPopTransitionSpec<VaultRoute>().invoke(this)

private fun scalePop(
    direction: PredictiveBackExitDirection,
    edge: Int = NavigationEvent.EDGE_LEFT,
): ContentTransform {
    val exitToRight = when (direction) {
        PredictiveBackExitDirection.FollowGesture -> edge == NavigationEvent.EDGE_LEFT
        PredictiveBackExitDirection.AlwaysRight -> true
        PredictiveBackExitDirection.AlwaysLeft -> false
    }
    val exitOffset: (Int) -> Int = { if (exitToRight) it else -it }
    val currentExit = scaleOut(
        animationSpec = tween(DurationMs, easing = ScaleEasing),
        targetScale = ScaleMinScale,
    ) + slideOutHorizontally(tween(DurationMs, easing = ScaleEasing), exitOffset)

    // 缩放样式只处理正在退出的当前页；返回目标页保持原尺寸、原透明度。
    return EnterTransition.None togetherWith currentExit
}

/** None：不做页面过渡，手势提交后直接切回上一页。 */
private fun noPredictivePop(): ContentTransform =
    EnterTransition.None togetherWith ExitTransition.None

private fun topLevelIndexOf(route: Any?): Int = when (route) {
    DashboardRoute -> 0
    ManageRoute -> 1
    SettingsRoute -> 2
    else -> -1
}

private const val DurationMs = 250
private const val TopLevelDurationMs = 200
private const val ScaleMinScale = 0.85f
private val ScaleEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
