package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
    /** 等宽字族。密钥遮蔽串、模型 id、URL 用它，值只能来自 `AppTokens.monoFontFamily`。 */
    fontFamily: FontFamily? = null,
) {
    val base = when (style) {
        AppTextStyle.Title -> MiuixTheme.textStyles.title4
        AppTextStyle.Subtitle -> MiuixTheme.textStyles.subtitle
        AppTextStyle.Body -> MiuixTheme.textStyles.main
        AppTextStyle.Secondary -> MiuixTheme.textStyles.body2
        AppTextStyle.Footnote -> MiuixTheme.textStyles.footnote1
    }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        style = if (fontFamily == null) base else base.copy(fontFamily = fontFamily),
    )
}

/** 次要文字色。页面需要"灰一点"的文字时用它，而不是自己拼 Color。 */
val appSecondaryTextColor: Color
    @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary

/**
 * 主题色的应用级出口。
 *
 * `ui/common/` 里的组件（`StatusDot`、分段条这些）需要主题色，但它不允许 import MIUIX，
 * 所以颜色一律从这里取。这也顺带保证了"页面代码里不出现 `Color(0xFF…)`"（§13.3）。
 */
val appPrimaryColor: Color
    @Composable get() = MiuixTheme.colorScheme.primary

val appDividerColor: Color
    @Composable get() = MiuixTheme.colorScheme.dividerLine

val appChipBackgroundColor: Color
    @Composable get() = MiuixTheme.colorScheme.secondaryContainer

val appChipTextColor: Color
    @Composable get() = MiuixTheme.colorScheme.onSecondaryContainer

val appTrackColor: Color
    @Composable get() = MiuixTheme.colorScheme.surfaceContainerHigh

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

/**
 * 底部弹层。和 [AppDialog] 一样只用 `Overlay*`——`Window*` 那一族是独立系统窗口，
 * `FLAG_SECURE` 不继承，而快速复制面板里就是明文密钥。
 */
@Composable
fun AppBottomSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        modifier = modifier,
        title = title,
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * 分段切换。管理页的四类内容、详情页的协议分组都用它。
 *
 * **没有用 MIUIX 的 `TabRow`**：它给所有分段算同一个固定宽度、再在里面加内边距，
 * 四个英文标签（"Providers" / "Accounts"）在 360dp 宽的屏上必然被截成 "Provid…"，
 * 而 `minWidth` / `maxWidth` 都改不动这一点——真正的上限是"可用宽度 ÷ 分段数"。
 * 所以这里自己用 `Surface` 拼一个：分段按 `weight` 均分整行，文字自己缩放，
 * 不留固定内边距，四个中文或英文标签都放得下。
 */
@Composable
fun AppTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = if (selected) {
                    MiuixTheme.colorScheme.surfaceContainerHighest
                } else {
                    MiuixTheme.colorScheme.surfaceContainer
                },
            ) {
                Text(
                    text = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    color = if (selected) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    style = MiuixTheme.textStyles.body2,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 小标签。协议、来源、分组这类一眼扫过的元信息用它，不用它去表达状态（状态走 `StatusDot`）。 */
@Composable
fun AppChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = appChipBackgroundColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = appChipTextColor,
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 1,
        )
    }
}

/** 进度条。`progress` 为 null 时是不确定进度。 */
@Composable
fun AppLinearProgress(progress: Float?, modifier: Modifier = Modifier) {
    LinearProgressIndicator(modifier = modifier, progress = progress)
}

@Composable
fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = appDividerColor)
}

@Composable
fun AppFab(
    icon: AppIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = icon.imageVector(),
            contentDescription = contentDescription,
            tint = MiuixTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
fun AppSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
    )
}

@Composable
fun AppDropdownRow(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    OverlayDropdownPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        onSelectedIndexChange = onSelect,
    )
}

/** 一行图标 + 文字，用在按钮式的卡片行里。 */
@Composable
fun AppIconLabel(
    icon: AppIcon,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(imageVector = icon.imageVector(), contentDescription = null, tint = tint)
        AppText(text = text, style = AppTextStyle.Body, color = tint)
    }
}

/**
 * 输入框状态。包成自己的类型，页面就不必 import Compose 的 `TextFieldState`——
 * 它是 MIUIX `TextField` 的入参形态，换实现时不该牵连页面。
 */
@Stable
class AppTextFieldState internal constructor(internal val state: TextFieldState) {
    val text: String get() = state.text.toString()
}

@Composable
fun rememberAppTextFieldState(initial: String = ""): AppTextFieldState {
    val state = rememberTextFieldState(initial)
    return remember(state) { AppTextFieldState(state) }
}

/** 搜索框。单行、带放大镜、用 label 当 placeholder。 */
@Composable
fun AppSearchField(
    state: AppTextFieldState,
    hint: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        state = state.state,
        modifier = modifier,
        label = hint,
        useLabelAsPlaceholder = true,
        lineLimits = TextFieldLineLimits.SingleLine,
        leadingIcon = {
            Icon(
                imageVector = AppIcon.Search.imageVector(),
                contentDescription = null,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                tint = appSecondaryTextColor,
            )
        },
    )
}
