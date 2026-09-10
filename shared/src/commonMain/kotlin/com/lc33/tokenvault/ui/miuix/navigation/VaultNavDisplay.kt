package com.lc33.tokenvault.ui.miuix.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        popTransitionSpec = { appPopTransition(style, exitDirection) },
        predictivePopTransitionSpec = { edge ->
            appPredictivePopTransition(style, exitDirection, edge)
        },
        transitionEffects = NavDisplayTransitionEffects(
            enableCornerClip = true,
            dimAmount = if (style == PredictiveBackStyle.None) 0f else 0.5f,
            blockInputDuringTransition = false,
            popDirectionFollowsSwipeEdge = style == PredictiveBackStyle.Scale &&
                exitDirection == PredictiveBackExitDirection.FollowGesture,
        ),
    )
}

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.appPushTransition(): ContentTransform {
    return if (isTopLevelSceneTransition()) {
        fadeTopLevel()
    } else {
        horizontalPush()
    }
}

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.appPopTransition(
    style: PredictiveBackStyle,
    exitDirection: PredictiveBackExitDirection,
): ContentTransform = when {
    isTopLevelSceneTransition() -> fadeTopLevel()
    style == PredictiveBackStyle.None -> noPredictivePop()
    style == PredictiveBackStyle.Aosp -> aospPop()
    style == PredictiveBackStyle.Miuix -> miuixPop()
    style == PredictiveBackStyle.Scale -> scalePop(exitDirection)
    else -> classicPop()
}

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.appPredictivePopTransition(
    style: PredictiveBackStyle,
    exitDirection: PredictiveBackExitDirection,
    edge: Int,
): ContentTransform = when {
    isTopLevelSceneTransition() -> fadeTopLevel()
    style == PredictiveBackStyle.None -> noPredictivePop()
    style == PredictiveBackStyle.Aosp -> aospPop()
    style == PredictiveBackStyle.Miuix -> miuixPop()
    style == PredictiveBackStyle.Scale -> scalePop(exitDirection, edge)
    else -> classicPop()
}

private fun AnimatedContentTransitionScope<Scene<VaultRoute>>.isTopLevelSceneTransition(): Boolean =
    isTopLevelScene(initialState) && isTopLevelScene(targetState)

private fun isTopLevelScene(scene: Scene<VaultRoute>): Boolean =
    scene.key is DashboardRoute || scene.key is ManageRoute || scene.key is SettingsRoute

private fun fadeTopLevel(): ContentTransform =
    fadeIn(tween(TopLevelDurationMs)) togetherWith fadeOut(tween(TopLevelDurationMs))

private fun horizontalPush(): ContentTransform =
    slideInHorizontally(tween(DurationMs)) { it } + fadeIn(tween(DurationMs)) togetherWith
        slideOutHorizontally(tween(DurationMs)) { -it / 4 } + fadeOut(tween(DurationMs))

private fun miuixPop(): ContentTransform =
    slideInHorizontally(tween(DurationMs)) { -it / 4 } + fadeIn(tween(DurationMs)) togetherWith
        slideOutHorizontally(tween(DurationMs)) { it } + fadeOut(tween(DurationMs))

private fun aospPop(): ContentTransform =
    fadeIn(tween(AospDurationMs, easing = AospEasing)) +
        scaleIn(tween(AospDurationMs, easing = AospEasing), initialScale = AospMinScale) togetherWith
        scaleOut(
            animationSpec = tween(AospDurationMs, easing = AospEasing),
            targetScale = AospMinScale,
        ) + fadeOut(tween(AospDurationMs, easing = AospEasing))

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
    return fadeIn(tween(DurationMs)) +
        scaleIn(tween(DurationMs, easing = ScaleEasing), initialScale = AospMinScale) togetherWith
        scaleOut(tween(DurationMs, easing = ScaleEasing), targetScale = ScaleMinScale) +
        slideOutHorizontally(tween(DurationMs, easing = ScaleEasing), exitOffset)
}

private fun classicPop(): ContentTransform =
    fadeIn(tween(ClassicDurationMs, easing = ScaleEasing)) +
        scaleIn(tween(ClassicDurationMs, easing = ScaleEasing), initialScale = ClassicMinScale) togetherWith
        scaleOut(tween(ClassicDurationMs, easing = ScaleEasing), targetScale = ClassicMinScale) +
        fadeOut(tween(ClassicDurationMs, easing = ScaleEasing))

/** None：手势期间不把页面跟着拖走，提交后再使用普通返回动画。 */
private fun noPredictivePop(): ContentTransform =
    EnterTransition.None togetherWith fadeOut(tween(NoneDurationMs))

private const val DurationMs = 250
private const val TopLevelDurationMs = 200
private const val AospDurationMs = 450
private const val NoneDurationMs = 450
private const val ClassicDurationMs = 200
private const val AospMinScale = 0.9f
private const val ScaleMinScale = 0.85f
private const val ClassicMinScale = 0.9f
private val AospEasing = CubicBezierEasing(0.05f, 0f, 0.133333f, 1f)
private val ScaleEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
