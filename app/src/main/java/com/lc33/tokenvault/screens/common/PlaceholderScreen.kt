package com.lc33.tokenvault.screens.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar

/**
 * 二级页的空壳。
 *
 * 存在的理由：路由与外壳先立起来，导航就能整条走通、底栏的显示隐藏也能验证；
 * 内容留给对应的里程碑填。比"先不声明路由、要用时再加"好的地方是——
 * 声明了却没有 `composable<T>` 的路由第一次 navigate 就崩，而这样不会。
 *
 * [titleRes] 用真实标题，这样即使是空壳，导航层级看起来也是对的。
 */
@Composable
fun PlaceholderScreen(
    titleRes: Int,
    onBack: () -> Unit,
) {
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(titleRes),
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(R.string.back_cd),
                        onClick = onBack,
                    )
                },
            )
        },
    ) { padding ->
        EmptyState(
            title = stringResource(R.string.placeholder_title),
            description = stringResource(R.string.placeholder_desc),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
