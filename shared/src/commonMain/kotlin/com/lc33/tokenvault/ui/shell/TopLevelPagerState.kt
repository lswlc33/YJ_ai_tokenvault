package com.lc33.tokenvault.ui.shell

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 一级页顺序 = 底栏顺序。三个 tab 平级，谁也不在谁之上。 */
val TopLevelRoutes: List<VaultRoute> = listOf(DashboardRoute, ManageRoute, SettingsRoute)

/** 一级页返回 0/1/2；二级页返回 -1（此时不显示底栏）。 */
fun topLevelIndexOf(route: VaultRoute?): Int =
    if (route == null) -1 else TopLevelRoutes.indexOf(route)

/**
 * 一级页 pager 的状态。
 *
 * 权威值是 [selectedPage]，**不是** `pagerState.currentPage`。这是整套实现的关键：
 * 从「总览」点底栏「设置」，pager 必须滑过中间的「管理」，`currentPage` 会在过程中依次
 * 取值 1、2；把 `currentPage` 直接写回导航状态，动画就会被重定向到中间页——表现成
 * "点设置却停在管理"。所以这里起手就把 [selectedPage] 认成目标页，并用 [isNavigating]
 * 挡住在途的 `currentPage` 回写，只有手指真正拖出来的那一页才由 [syncPage] 采纳。
 */
@Stable
class TopLevelPagerState internal constructor(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    /** 底栏选中的一级页。底栏高亮与页面内容都以它为准。 */
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    /** 程序化翻页进行中：期间 `currentPage` 会途经中间页，不采纳。 */
    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    /**
     * 底栏点击：先认下目标页，再让 pager 滚过去。
     *
     * 不用 `animateScrollToPage`——它是 `Default` 优先级，会被 pager 自己的惯性处理打断，
     * 跨两页时可能中途停住。这里用 `UserInput` 优先级的自绘滚动，时长按跨越的页数给，
     * 和底栏跨页的距离成正比。
     */
    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage) return
        navJob?.cancel()
        selectedPage = targetIndex
        isNavigating = true
        navJob = coroutineScope.launch {
            val myJob = coroutineContext[Job]
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    val layoutInfo = pagerState.layoutInfo
                    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
                    val pagesToTravel =
                        targetIndex - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val pages = abs(targetIndex - pagerState.currentPage).coerceAtLeast(2)
                    var consumed = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = pagesToTravel * pageSize,
                        animationSpec = tween(
                            easing = FastOutSlowInEasing,
                            durationMillis = 100 * pages + 100,
                        ),
                    ) { value, _ ->
                        consumed += scrollBy(value - consumed)
                    }
                }
                // 滚动被外力打断时可能差一点点，补一次瞬时定位。
                if (pagerState.currentPage != targetIndex) pagerState.scrollToPage(targetIndex)
            } finally {
                if (navJob === myJob) {
                    isNavigating = false
                    // 翻页没走到目标（用户中途接管）时，以 pager 为准回写一次。
                    if (pagerState.currentPage != targetIndex) selectedPage = pagerState.currentPage
                }
            }
        }
    }

    /** 手指滑出来的一页：滚动期间不采纳，停稳后跟上。 */
    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

@Composable
fun rememberTopLevelPagerState(pagerState: PagerState): TopLevelPagerState {
    val scope = rememberCoroutineScope()
    return remember(pagerState, scope) { TopLevelPagerState(pagerState, scope) }
}
