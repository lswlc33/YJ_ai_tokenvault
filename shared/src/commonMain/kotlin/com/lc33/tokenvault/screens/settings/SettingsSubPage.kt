package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

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
    titleRes: StringResource,
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
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = onBack,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // 键盘弹起时把列表顶起来：二级设置页里带输入框的那几页（阈值、客户端关键字）
                // 保存行就是列表的最后一项，不 imePadding 的话它正好被键盘盖住，
                // 用户输完却找不到"保存"。edge-to-edge 之后 manifest 的 adjustResize 不再生效，
                // 这一层是唯一的机会。页面自身不再另加 imePadding，不会双重抬高。
                .imePadding()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
        ) {
            content()
            // 列表末尾预留呼吸空间：内容画到窗口底部（为了透出玻璃底栏），
            // 不垫这一段的话最后一行会贴着底栏药丸边缘。
            item { Spacer(modifier = Modifier.height(LocalAppTokens.current.sectionSpacing)) }
        }
    }
}
