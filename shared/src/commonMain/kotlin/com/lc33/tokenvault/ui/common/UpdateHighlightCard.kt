package com.lc33.tokenvault.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.update_highlight_action
import tokenvault.shared.generated.resources.update_highlight_summary
import tokenvault.shared.generated.resources.update_highlight_title
import com.lc33.tokenvault.platform.APP_VERSION_NAME
import com.lc33.tokenvault.ui.miuix.AppAccentCard
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconLabel
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appOnPrimaryColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 首页与关于页共用的更新入口。
 *
 * 它只负责把人带到更新页；不在首页直接发网络请求，也不在页面主体画按钮。
 * 主题色大卡让这个“低频但重要”的入口在没有内容时也仍然可见。
 */
@Composable
fun UpdateHighlightCard(
    onOpenUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    AppAccentCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpenUpdate,
    ) {
        AppText(
            text = stringResource(Res.string.update_highlight_title),
            style = AppTextStyle.Title,
            color = appOnPrimaryColor,
        )
        AppText(
            text = stringResource(Res.string.update_highlight_summary, APP_VERSION_NAME),
            style = AppTextStyle.Secondary,
            color = appOnPrimaryColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
        AppIconLabel(
            icon = AppIcon.Update,
            text = stringResource(Res.string.update_highlight_action),
            tint = appOnPrimaryColor,
            modifier = Modifier.padding(top = tokens.sectionSpacing),
        )
    }
}