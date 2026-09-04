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
