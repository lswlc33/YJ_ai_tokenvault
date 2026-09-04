package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 排版档位。页面不写裸 `fontSize`，只在这几档里选（计划.md §13.3）。
 */
enum class AppTextStyle {
    Title,
    Subtitle,
    Body,
    Secondary,
    Footnote,
}

@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    style: AppTextStyle = AppTextStyle.Body,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        style = when (style) {
            AppTextStyle.Title -> MiuixTheme.textStyles.title4
            AppTextStyle.Subtitle -> MiuixTheme.textStyles.subtitle
            AppTextStyle.Body -> MiuixTheme.textStyles.main
            AppTextStyle.Secondary -> MiuixTheme.textStyles.body2
            AppTextStyle.Footnote -> MiuixTheme.textStyles.footnote1
        },
    )
}

/** 次要文字色。页面需要"灰一点"的文字时用它，而不是自己拼 Color。 */
val appSecondaryTextColor: Color
    @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        insideMargin = insideMargin,
        onClick = onClick,
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    SmallTitle(text = text, modifier = modifier)
}

/** 可点进二级页的一行，右侧带箭头。 */
@Composable
fun AppArrowRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    ArrowPreference(
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
fun AppIconButton(
    icon: AppIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(imageVector = icon.imageVector(), contentDescription = contentDescription)
    }
}

/**
 * 对话框。
 *
 * 只用 MIUIX 的 `Overlay*` 系列：它画在 `Scaffold` 内的同一个窗口里，
 * 所以会继承 `FLAG_SECURE`。`Window*` 系列是独立系统窗口、不继承，
 * 而本项目的弹层里就有展示明文密钥和密码的，所以那一族被 CI 禁掉
 * （计划.md §7.5、§13.2）。
 */
@Composable
fun AppDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    OverlayDialog(
        show = show,
        modifier = modifier,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            if (confirmText != null) {
                AppTextButton(
                    text = confirmText,
                    onClick = { (onConfirm ?: onDismissRequest)() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
    }
}
