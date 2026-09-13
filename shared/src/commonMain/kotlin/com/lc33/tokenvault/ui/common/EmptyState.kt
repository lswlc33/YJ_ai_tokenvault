package com.lc33.tokenvault.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.common_loading

/**
 * 空态只描述状态，不放行动入口。
 *
 * 首页必须永远是完整仪表盘；管理页的新建/导入统一收进 FAB。空态里再长出一组
 * CTA 会让同一动作出现两套入口，也会让“空库”看起来像另一个页面。
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = LocalAppTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.sectionSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        AppText(text = title, style = AppTextStyle.Title, textAlign = TextAlign.Center)
        AppText(
            text = description,
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
            textAlign = TextAlign.Center,
        )
        if (actionText != null && onAction != null) {
            AppTextButton(text = actionText, onClick = onAction)
        }
    }
}

@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.sectionSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppText(
            text = stringResource(Res.string.common_loading),
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
        )
    }
}
