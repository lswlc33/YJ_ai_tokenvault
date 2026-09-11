package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
import com.lc33.tokenvault.platform.Haptics
import com.lc33.tokenvault.ui.theme.AppTokens
import com.lc33.tokenvault.ui.theme.LocalAppTokens
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

val appOnPrimaryColor: Color
    @Composable get() = MiuixTheme.colorScheme.onPrimary

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
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        insideMargin = insideMargin,
        onClick = onClick,
        onLongPress = onLongPress,
        content = content,
    )
}

/**
 * 一组 preference 项的容器（"分组"）。
 *
 * MIUIX 的 preference 组件（ArrowPreference / SwitchPreference / OverlayDropdownPreference…）
 * 底层是 [BasicComponent]，**不带背景**，只是 56dp 高的一行。HyperOS 设置页里它们总是
 * 包在一个圆角卡片里成组（红线：项目必须包含在 group 中）——这个组件就是那个卡片。
 *
 * 与 [AppCard] 的区别：内边距为 0。preference 项自己已经带了 16dp 的
 * [BasicComponentDefaults.InsideMargin]，外面再叠 16dp 就会变成 32dp、行明显过胖。
 */
@Composable
fun AppPreferenceGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = modifier.padding(horizontal = tokens.screenPadding),
        insideMargin = PaddingValues(0.dp),
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
        onClick = {
            Haptics.tap()
            onClick?.invoke()
        },
    )
}

/**
 * 主题色大卡片。首页与关于页的“检查更新”入口用它：它必须是页面里的一张卡，
 * 不是一枚按钮；点击只是跳到更新页，真正的动作按钮仍留在对话框里。
 */
@Composable
fun AppAccentCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAppTokens.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(tokens.cardRadius),
        color = appPrimaryColor,
        contentColor = appOnPrimaryColor,
    ) {
        Column(
            modifier = Modifier.padding(tokens.sectionSpacing),
            content = content,
        )
    }
}

/**
 * 页面主体里的动作入口。形态固定为 preference 行，而不是按钮；
 * 弹层与对话框仍可用 [AppTextButton]，页面主体一律走这里。
 */
@Composable
fun AppActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    // primary 只保留旧调用的语义占位；入口形态不随强调级别变成按钮。
    AppArrowRow(title = text, modifier = modifier, enabled = enabled, onClick = onClick)
}

/**
 * 文本按钮。页面主体与弹窗里唯一的"按钮"形态（红线：MIUIX 长方形实心按钮不进页面主体）。
 *
 * [primary] 控制强调级别：主动作（"开始"、"确认"、"从备份恢复"）用主色实底
 * （`textButtonColorsPrimary`），次动作/危险出口（"取消"、"清空重来"）用默认浅色。
 */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = if (primary) {
            ButtonDefaults.textButtonColorsPrimary()
        } else {
            ButtonDefaults.textButtonColors()
        },
    )
}

/**
 * 弹层里的文本按钮出口。
 *
 * 页面不允许直接 import [AppTextButton]（那条架构规则防止主体长出按钮堆），
 * 但对话框需要“取消 / 保存”两个动作。这个包装保留 MIUIX TextButton 的形态，
 * 同时让规则继续能区分“页面主体按钮”和“弹层按钮”。
 */
@Composable
fun AppDialogTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    AppTextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        primary = primary,
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
 * 只用 MIUIX 的 `Overlay*` 系列：它画在 `Scaffold` 内的同一个窗口里，与页面共享
 * 生命周期与组合树。`Window*` 系列是独立系统窗口、行为与页面脱钩，所以那一族被
 * CI 禁掉（计划.md §13.2）。
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
 * 底部弹层。和 [AppDialog] 一样只用 `Overlay*`——`Window*` 那一族是独立系统窗口、
 * 行为与页面脱钩。
 */
@Composable
fun AppBottomSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAppTokens.current
    OverlayBottomSheet(
        show = show,
        modifier = modifier,
        title = title,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = tokens.screenPadding,
                    vertical = tokens.itemSpacing,
                ),
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            content = content,
        )
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

/**
 * 可选中的筛选标签。管理页的分组筛选条由一排它组成。
 *
 * 用 chip 而不是 `AppTabRow`：分组数量由用户决定（可能 2 个也可能 15 个），
 * 固定分段放不下；chip 行放在 `LazyRow` 里天然可横滑。
 */
@Composable
fun AppFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MiuixTheme.colorScheme.surfaceContainerHighest
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = text,
                color = if (selected) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                style = MiuixTheme.textStyles.body2,
                maxLines = 1,
            )
            if (trailingText != null) {
                Text(
                    text = trailingText,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 1,
                )
            }
        }
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
        onCheckedChange = { value ->
            Haptics.impact()
            onCheckedChange(value)
        },
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

/** 光图标，没有按钮语义。画在色块上的对勾这类装饰用它。 */
@Composable
fun AppIconTint(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = Color.White,
) {
    Icon(
        imageVector = icon.imageVector(),
        contentDescription = null,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/**
 * 下拉刷新。
 *
 * 只给**只读页**用。编辑页不放它：下拉在那里语义含糊（刷新列表？重新探测？），
 * 而探测要花钱，必须由一个明确的按钮触发（§13.4）。
 *
 * [refreshTexts] 四段文案由调用方从资源里给，顺序是"下拉刷新 / 释放刷新 /
 * 正在刷新 / 刷新完成"——MIUIX 按这个顺序取。
 */
@Composable
fun AppRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    refreshTexts: List<String>,
    modifier: Modifier = Modifier,
    scrollState: AppTopBarScrollState? = null,
    content: @Composable () -> Unit,
) {
    PullToRefresh(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        topAppBarScrollBehavior = scrollState?.behavior,
        refreshTexts = refreshTexts,
        content = content,
    )
}

/**
 * 输入框状态。包成自己的类型，页面就不必 import Compose 的 `TextFieldState`——
 * 它是 MIUIX `TextField` 的入参形态，换实现时不该牵连页面。
 */
@Stable
class AppTextFieldState internal constructor(internal val state: TextFieldState) {
    val text: String get() = state.text.toString()

    /**
     * 不经过 `String` 的字符拷贝。
     *
     * 明文秘密（长口令）只允许活在能擦掉的 `CharArray` 里（红线 1），
     * 而 [text] 会产出一个进了字符串常量池、再也擦不掉的 `String`。
     * 输入框内部当然仍持有这段文本——这是文本框不可避免的代价，所以离开时调 [clear]。
     */
    val chars: CharArray get() = state.text.let { cs -> CharArray(cs.length) { cs[it] } }

    /**
     * 清空，**连撤销历史一起清**。
     *
     * 少清撤销历史等于没清：`clearText()` 自己也会记一条可撤销的编辑，把刚刚那段明文
     * 存进 `TextUndoManager`，于是"已经清掉了"的秘密还能被一次撤销拿回来。
     *
     * `undoState` 还是实验 API，所以这里逐个函数 opt-in 而不是全模块打开——
     * 它哪天改签名，编译器该在这一个函数上报错，不该在整个模块里静默通过。
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun clear() {
        state.clearText()
        state.undoState.clearHistory()
    }

    /**
     * 覆盖输入框文本。粘贴导入的「从剪贴板填充」用它：把剪贴板里的整段文本填进来。
     *
     * 走 `setTextAndPlaceCursorAtEnd` 而不是重新建一个 state——重建会让输入框的焦点、
     * 滚动位置与撤销历史一起丢，而且拿不到旧的 state 引用。
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun setText(text: String) {
        state.setTextAndPlaceCursorAtEnd(text)
    }
}

@Composable
fun rememberAppTextFieldState(initial: String = ""): AppTextFieldState {
    val state = rememberTextFieldState(initial)
    return remember(state) { AppTextFieldState(state) }
}

/**
 * 装明文秘密的输入框状态。**刻意不可保存**。
 *
 * [rememberAppTextFieldState] 走的是 `rememberSaveable`，于是输入框里的内容会被
 * `TextFieldState.Saver` 序列化进 Activity 的 saved instance state —— 也就是交给
 * `system_server` 放在一个 Bundle 里，转屏或切后台就发生一次，而 [AppTextFieldState.clear]
 * 完全够不到那份拷贝。恢复密钥是能解开整个库的东西（红线 1），不能这么走。
 *
 * 代价是转屏会丢掉已输入的内容。这个代价是对的：那一格本来就该重新输一次。
 */
@Composable
fun rememberSecretTextFieldState(): AppTextFieldState {
    val state = remember { TextFieldState() }
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

/**
 * 普通输入框。label 常驻（不当 placeholder），这样填完之后还看得见这一格是什么。
 *
 * [errorText] 非空时在下面补一行错误说明——校验结果必须能被看到，不能只靠边框变色。
 */
@Composable
fun AppTextField(
    state: AppTextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    errorText: String? = null,
    supportingText: String? = null,
) {
    FieldWithNote(modifier = modifier, errorText = errorText, supportingText = supportingText) {
        TextField(
            state = state.state,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        )
    }
}

/**
 * 装明文秘密的输入框（恢复密钥、备份口令、平台密码）。
 *
 * 与 [AppTextField] 的差别只有键盘配置，但那正是重点：默认的文本键盘会把输入内容喂给
 * 输入法的联想与"个性化学习"，于是这段明文之后会以候选词的形式出现在**任何人**面前——
 * 这正是 `PinPad` 宁可自己画一个数字盘也不用系统键盘的理由（§7.5），而恢复密钥比 PIN 更值钱。
 * `KeyboardType.Password` 会让输入法关掉联想与记忆。
 *
 * [onDone] 非空时回车键变成「完成」并直接提交：这一格常常是一屏里唯一的输入，
 * 让用户先收起键盘再去找按钮，等于让他盲着点。
 */
@Composable
fun AppSecretTextField(
    state: AppTextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    errorText: String? = null,
    supportingText: String? = null,
    onDone: (() -> Unit)? = null,
) {
    FieldWithNote(modifier = modifier, errorText = errorText, supportingText = supportingText) {
        TextField(
            state = state.state,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = if (onDone == null) ImeAction.Default else ImeAction.Done,
            ),
            onKeyboardAction = KeyboardActionHandler { performDefault ->
                if (onDone == null) performDefault() else onDone()
            },
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        )
    }
}

/** 输入框 + 下面那行说明。校验结果必须能被看到，不能只靠边框变色。 */
@Composable
private fun FieldWithNote(
    modifier: Modifier,
    errorText: String?,
    supportingText: String?,
    field: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        field()
        val note = errorText ?: supportingText
        if (note != null) {
            AppText(
                text = note,
                style = AppTextStyle.Footnote,
                color = if (errorText != null) MiuixTheme.colorScheme.error else appSecondaryTextColor,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}
