package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState

/**
 * 二级设置页的共用外壳：带返回的 topBar + 一个可滚动列表。
 *
 * 七个二级页都长这样，样板写七遍的问题不是"多打字"，而是**改一处得改七处**——
 * 比如哪天要给所有设置页加"搜索设置项"，或者顶栏折叠行为要统一调整。
 *
 * 内容用 [LazyListScope] 而不是 `Column`：设置页的行数会长，而且 `SectionTitle`
 * 与预设行混排时用 `item {}` 更贴近 `miuix-preference` 的用法。
 */
@Composable
fun SettingsSubPage(
    titleRes: Int,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(titleRes),
                scrollState = scrollState,
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            content = content,
        )
    }
}
