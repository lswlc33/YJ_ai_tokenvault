package com.lc33.tokenvault.ui.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.common_row_gone
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar

/**
 * 详情/编辑路由在内容到位之前的占位页。
 *
 * 为什么要一整页外壳，而不是直接回一个 [LoadingState]：这些路由以前画的是
 * `LoadingState(Modifier.fillMaxSize())`，它在**任何 Scaffold 外面**——没有顶栏、
 * 没有 inset，那行"加载中"压在状态栏底下，而且此刻屏幕上没有一个能点的返回入口，
 * 用户只能靠系统手势出去。
 *
 * [gone] 说的是另一件事，也是以前被混在一起的那件事：库已经读过、那一行不在
 * （被删了，或恢复备份之后所有 id 都换了）。这种情况下继续转圈就是把"没了"显示成
 * "还在加载"——用户会等一个永远不会来的内容。加载与不存在必须分开说。
 */
@Composable
fun PagePlaceholder(
    onBack: () -> Unit,
    gone: Boolean,
) {
    AppScaffold(
        topBar = {
            AppTopBar(
                title = "",
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = onBack,
                    )
                },
            )
        },
    ) { padding ->
        if (gone) {
            EmptyState(
                title = stringResource(Res.string.common_row_gone),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LoadingState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}
