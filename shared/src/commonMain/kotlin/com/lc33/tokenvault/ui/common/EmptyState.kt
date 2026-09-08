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

/**
 * 空态。计划.md §13.4 要求空态给可点的 CTA，所以 [actionText] 存在时必须给 [onAction]。
 *
 * [secondaryActionText] 非空时画第二个次级 CTA（如「粘贴导入」），两个动作并排，
 * 让空态本身就能完成最常见的起步动作，而不是把人逼到别处。
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
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
        if (secondaryActionText != null && onSecondaryAction != null) {
            AppTextButton(text = secondaryActionText, onClick = onSecondaryAction)
        }
    }
}
